import Foundation
import Combine
import AppKit

/// The whole app's state. Owns the queue, the server, and the pairing record,
/// and turns all of it into sentences the UI shows (PRD §5: never fail silently).
@MainActor
final class LoaderModel: ObservableObject {
    enum ServerStatus: Equatable {
        case starting
        case serving(port: Int)
        case failed(String)
    }

    enum PhoneStatus: Equatable {
        case neverSeen
        case seen(Date)
        case active
    }

    @Published private(set) var items: [QueueItem] = []
    @Published private(set) var server: ServerStatus = .starting
    @Published private(set) var phone: PhoneStatus = .neverSeen
    @Published private(set) var pairing: Pairing?
    @Published private(set) var address: LocalAddress.Candidate?
    @Published private(set) var notices: [String] = []
    @Published var showingCode = false

    let files: AppFiles
    let macName: String
    private var identity: ServerIdentity?
    private var token: String?
    private var httpServer: HTTPServer?
    private var api: SyncAPI?
    private var addressTimer: Timer?
    private var lastContact: Date?
    private var hasEverConnected: Bool {
        get { UserDefaults.standard.bool(forKey: "hasEverConnected") }
        set { UserDefaults.standard.set(newValue, forKey: "hasEverConnected") }
    }

    init(files: AppFiles = AppFiles(), macName: String? = nil) {
        self.files = files
        self.macName = macName ?? Host.current().localizedName ?? "your Mac"
        self.items = QueueFile.load(from: files.queue)
    }

    // MARK: - Lifecycle

    func start() {
        do {
            token = try files.loadOrCreateToken()
            switch try ServerIdentity.loadOrCreate(certificateFile: files.certificate) {
            case .existing(let id):
                identity = id
            case .regenerated(let id, let reason):
                identity = id
                if reason != "first run" {
                    notices.append("\(reason) Your phone will need to scan the code again.")
                }
            }
        } catch {
            server = .failed("Couldn't set up sharing on this Mac. \(error.localizedDescription)")
            return
        }
        if !hasEverConnected { showingCode = true }
        refreshAddress()
        addressTimer = Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.refreshAddress(); self?.refreshPhoneStatus() }
        }
        startServer(port: files.loadPort(), fallbackToAnyPort: true)
    }

    private func startServer(port: Int, fallbackToAnyPort: Bool) {
        guard let identity, let token else { return }
        httpServer?.stop()
        let api = SyncAPI(token: token, source: self)
        self.api = api
        let server = HTTPServer(identity: identity.identity, handler: { api.handle($0) }) { [weak self] event in
            Task { @MainActor in self?.handle(event, requestedPort: port, fallbackToAnyPort: fallbackToAnyPort) }
        }
        httpServer = server
        self.server = .starting
        do {
            try server.start(port: port)
        } catch {
            self.server = .failed("Couldn't start sharing on this Mac. \(error.localizedDescription)")
        }
    }

    private func handle(_ event: HTTPServer.Event, requestedPort: Int, fallbackToAnyPort: Bool) {
        switch event {
        case .ready(let port):
            server = .serving(port: port)
            if port != files.loadPort() {
                files.savePort(port)
                if hasEverConnected {
                    notices.append("Sharing moved to a different port because the usual one was busy. Your phone will need to scan the code again.")
                }
            }
            rebuildPairing()
        case .failed(let message):
            if fallbackToAnyPort && requestedPort != 0 {
                startServer(port: 0, fallbackToAnyPort: false)
            } else {
                server = .failed("Couldn't start sharing on this Mac. \(message)")
            }
        case .request(let request):
            lastContact = Date()
            if !hasEverConnected {
                hasEverConnected = true
                showingCode = false
                notices.removeAll { $0.hasSuffix("scan the code again.") }
            }
            if request.path.hasPrefix("/v1/files/") || request.path.hasPrefix("/v1/queue") {
                phone = .active
            }
        }
    }

    func stop() {
        httpServer?.stop()
        addressTimer?.invalidate()
    }

    private func refreshAddress() {
        let best = LocalAddress.best()
        if best != address {
            address = best
            rebuildPairing()
        }
    }

    private func refreshPhoneStatus() {
        guard let lastContact else { return }
        if Date().timeIntervalSince(lastContact) > 20, phone == .active {
            phone = .seen(lastContact)
        }
    }

    private func rebuildPairing() {
        guard let identity, let token, let address, case .serving(let port) = server else {
            pairing = nil
            return
        }
        pairing = Pairing(host: address.address, port: port, token: token,
                          certSha256: identity.fingerprintHex, macName: macName)
    }

    var pairingCodeText: String? { pairing.map(PairingCode.encode) }

    // MARK: - Queue

    func add(urls: [URL]) {
        let expanded = expand(urls)
        guard !expanded.isEmpty else { return }
        var newItems: [QueueItem] = []
        for url in expanded {
            if items.contains(where: { $0.sourcePath == url.path && $0.status != .problem("") && $0.status.isWaiting }) {
                continue // same file already waiting
            }
            newItems.append(.checking(url: url))
        }
        items.append(contentsOf: newItems)
        persist()
        Task {
            for item in newItems {
                let inspection = await TrackInspector.inspect(item.sourceURL)
                apply(inspection, to: item.id)
            }
        }
    }

    private func apply(_ inspection: Inspection, to id: String) {
        guard let index = items.firstIndex(where: { $0.id == id }) else { return }
        var item = items[index]
        item.apply(inspection)
        if item.status == .waiting,
           items.contains(where: { $0.id != id && $0.phonePath == item.phonePath && $0.status.isWaiting }) {
            item.status = .problem("This song is already in the queue.")
        }
        items[index] = item
        persist()
    }

    /// Folders are walked; hidden files are skipped; order is stable.
    private func expand(_ urls: [URL]) -> [URL] {
        var out: [URL] = []
        for url in urls {
            var isDir: ObjCBool = false
            guard FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir) else { continue }
            if isDir.boolValue {
                let keys: [URLResourceKey] = [.isRegularFileKey, .isHiddenKey]
                if let e = FileManager.default.enumerator(at: url, includingPropertiesForKeys: keys, options: [.skipsHiddenFiles]) {
                    var found: [URL] = []
                    for case let f as URL in e {
                        if (try? f.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true { found.append(f) }
                    }
                    out += found.sorted { $0.path.localizedStandardCompare($1.path) == .orderedAscending }
                }
            } else {
                out.append(url)
            }
        }
        return out
    }

    func remove(_ item: QueueItem) {
        items.removeAll { $0.id == item.id }
        persist()
    }

    func clearFinished() {
        items.removeAll {
            if case .sent = $0.status { return true }
            if case .problem = $0.status { return true }
            return false
        }
        persist()
    }

    func dismissNotice(_ notice: String) {
        notices.removeAll { $0 == notice }
    }

    private func persist() {
        QueueFile.save(items, to: files.queue)
    }

    // MARK: - Derived sentences

    var waitingCount: Int { items.filter { $0.status.isWaiting }.count }
    var sentCount: Int { items.filter { if case .sent = $0.status { return true }; return false }.count }
    var problemCount: Int { items.filter { if case .problem = $0.status { return true }; return false }.count }
    var checkingCount: Int { items.filter { $0.status == .checking }.count }

    /// The one line that answers "is it safe to close this?" (PRD §6).
    var headline: String {
        if case .failed(let message) = server { return message }
        if address == nil { return "This Mac isn't on a network. Join the same Wi-Fi as your phone." }
        if checkingCount > 0 { return "Checking \(checkingCount == 1 ? "a song" : "\(checkingCount) songs")…" }
        if waitingCount > 0 {
            let n = waitingCount == 1 ? "1 song" : "\(waitingCount) songs"
            if phone == .active { return "Your phone is picking up \(n) now. Keep this open." }
            return "Keep this open. Your phone will pick up \(n) the next time you open its music tool."
        }
        if items.isEmpty { return "Drop songs here to send them to your phone." }
        return "Everything is on your phone. It's safe to close this."
    }

    var safeToClose: Bool { waitingCount == 0 && checkingCount == 0 }

    var phoneSentence: String {
        switch phone {
        case .neverSeen:
            return hasEverConnected ? "Your phone hasn't checked in since this Mac restarted." : "Your phone hasn't connected yet. Scan the code to pair it."
        case .active:
            return "Your phone is connected right now."
        case .seen(let date):
            let formatter = RelativeDateTimeFormatter()
            formatter.unitsStyle = .full
            return "Your phone last checked in \(formatter.localizedString(for: date, relativeTo: Date()))."
        }
    }

    var networkSentence: String {
        if let address { return "Sharing on \(address.address) as “\(macName)”." }
        return "Not connected to any network."
    }
}

// MARK: - Server-facing queue

extension LoaderModel: SyncQueueSource {
    nonisolated func waitingItems() -> [(id: String, path: String, fileURL: URL, sizeBytes: Int)] {
        DispatchQueue.main.sync {
            MainActor.assumeIsolated {
                items.filter { $0.status.isWaiting }
                    .map { ($0.id, $0.phonePath, $0.sourceURL, $0.sizeBytes) }
            }
        }
    }

    nonisolated func fileRequested(id: String) {
        Task { @MainActor in
            if let i = items.firstIndex(where: { $0.id == id }), items[i].status == .waiting {
                items[i].status = .sending
            }
        }
    }

    nonisolated func acknowledged(id: String) {
        Task { @MainActor in
            if let i = items.firstIndex(where: { $0.id == id }), items[i].status.isWaiting {
                items[i].status = .sent(Date())
                persist()
            }
        }
    }
}
