import SwiftUI
import UniformTypeIdentifiers

struct ContentView: View {
    @EnvironmentObject var model: LoaderModel
    @State private var isDropTargeted = false

    var body: some View {
        VStack(spacing: 0) {
            header
            Divider()
            ForEach(model.notices, id: \.self) { notice in
                NoticeBar(text: notice) { model.dismissNotice(notice) }
                Divider()
            }
            if model.items.isEmpty {
                emptyState
            } else {
                list
            }
        }
        .frame(minWidth: 520, minHeight: 420)
        .onDrop(of: [.fileURL], isTargeted: $isDropTargeted, perform: handleDrop)
        .overlay {
            if isDropTargeted {
                RoundedRectangle(cornerRadius: 12)
                    .strokeBorder(Color.accentColor, lineWidth: 3)
                    .padding(6)
                    .allowsHitTesting(false)
            }
        }
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { model.showingCode = true } label: { Label("Show Code", systemImage: "qrcode") }
                    .help("Show the code your phone scans to connect")
            }
            ToolbarItem {
                Button("Clear Finished") { model.clearFinished() }
                    .disabled(model.sentCount + model.problemCount == 0)
            }
        }
        .sheet(isPresented: $model.showingCode) { PairingView() }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Image(systemName: model.safeToClose ? "checkmark.circle.fill" : "clock.fill")
                    .foregroundStyle(model.safeToClose ? .green : .orange)
                Text(model.headline).font(.title3).fontWeight(.semibold)
            }
            Text(model.phoneSentence).foregroundStyle(.secondary)
            Text(model.networkSentence).foregroundStyle(.secondary).font(.callout)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "arrow.down.doc").font(.system(size: 48)).foregroundStyle(.secondary)
            Text("Drag songs or folders here").font(.title2)
            Text("They wait here until you open the music tool on your phone.").foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var list: some View {
        List {
            if model.waitingCount + model.checkingCount > 0 {
                Section("Waiting for your phone") {
                    ForEach(model.items.filter { $0.status.isWaiting || $0.status == .checking }) { ItemRow(item: $0) }
                }
            }
            if model.problemCount > 0 {
                Section("Won't be sent") {
                    ForEach(model.items.filter { if case .problem = $0.status { return true }; return false }) { ItemRow(item: $0) }
                }
            }
            if model.sentCount > 0 {
                Section("On your phone") {
                    ForEach(model.items.filter { if case .sent = $0.status { return true }; return false }) { ItemRow(item: $0) }
                }
            }
        }
    }

    private func handleDrop(_ providers: [NSItemProvider]) -> Bool {
        let group = DispatchGroup()
        var urls: [URL] = []
        let lock = NSLock()
        for provider in providers where provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) {
            group.enter()
            provider.loadItem(forTypeIdentifier: UTType.fileURL.identifier) { item, _ in
                defer { group.leave() }
                var url: URL?
                if let data = item as? Data { url = URL(dataRepresentation: data, relativeTo: nil) }
                else if let u = item as? URL { url = u }
                if let url { lock.withLock { urls.append(url) } }
            }
        }
        group.notify(queue: .main) {
            model.add(urls: urls)
        }
        return true
    }
}

struct ItemRow: View {
    @EnvironmentObject var model: LoaderModel
    let item: QueueItem

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            statusIcon.frame(width: 20)
            VStack(alignment: .leading, spacing: 3) {
                Text(item.title).fontWeight(.medium)
                if item.status != .checking {
                    Text(subtitle).foregroundStyle(.secondary).font(.callout)
                }
                ForEach(item.warnings, id: \.self) { w in
                    Label(w, systemImage: "exclamationmark.triangle").font(.callout).foregroundStyle(.orange)
                }
                if case .problem(let reason) = item.status {
                    Label(reason, systemImage: "xmark.octagon").font(.callout).foregroundStyle(.red)
                }
            }
            Spacer()
            Text(trailing).foregroundStyle(.secondary).font(.callout)
        }
        .padding(.vertical, 3)
        .contextMenu {
            Button("Remove from Queue") { model.remove(item) }
            Button("Show in Finder") { NSWorkspace.shared.activateFileViewerSelecting([item.sourceURL]) }
        }
        .help("Will be saved on the phone as \(item.phonePath)")
    }

    private var subtitle: String {
        var parts = [item.artist, item.album]
        if item.isCompilation { parts.append("Compilation") }
        return parts.filter { !$0.isEmpty }.joined(separator: " · ")
    }

    private var statusIcon: some View {
        Group {
            switch item.status {
            case .checking: ProgressView().controlSize(.small)
            case .waiting: Image(systemName: "clock").foregroundStyle(.secondary)
            case .sending: Image(systemName: "arrow.up.circle").foregroundStyle(.blue)
            case .sent: Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
            case .problem: Image(systemName: "xmark.circle.fill").foregroundStyle(.red)
            }
        }
    }

    private var trailing: String {
        switch item.status {
        case .checking: return "Checking…"
        case .waiting: return ByteCountFormatter.string(fromByteCount: Int64(item.sizeBytes), countStyle: .file)
        case .sending: return "Sending…"
        case .sent(let date): return date.formatted(date: .omitted, time: .shortened)
        case .problem: return ""
        }
    }
}

struct NoticeBar: View {
    let text: String
    let dismiss: () -> Void
    var body: some View {
        HStack {
            Image(systemName: "info.circle.fill").foregroundStyle(.blue)
            Text(text)
            Spacer()
            Button("OK", action: dismiss)
        }
        .padding(.horizontal).padding(.vertical, 8)
        .background(Color.blue.opacity(0.08))
    }
}
