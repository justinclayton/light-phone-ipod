import Foundation

/// One dropped file and where it stands.
struct QueueItem: Identifiable, Codable, Equatable {
    enum Status: Codable, Equatable {
        case checking
        case waiting
        case sending
        case sent(Date)
        /// Will never be sent; the phone never hears about it.
        case problem(String)

        var isWaiting: Bool { self == .waiting || self == .sending }
    }

    let id: String
    let sourcePath: String
    let addedAt: Date
    var phonePath: String
    var sizeBytes: Int
    var title: String
    var artist: String
    var album: String
    var isCompilation: Bool
    var warnings: [String]
    var status: Status

    var sourceURL: URL { URL(fileURLWithPath: sourcePath) }
    var filename: String { sourceURL.lastPathComponent }

    static func checking(url: URL) -> QueueItem {
        QueueItem(id: UUID().uuidString.lowercased(),
                  sourcePath: url.path,
                  addedAt: Date(),
                  phonePath: url.lastPathComponent,
                  sizeBytes: 0,
                  title: (url.lastPathComponent as NSString).deletingPathExtension,
                  artist: "", album: "", isCompilation: false,
                  warnings: [], status: .checking)
    }

    mutating func apply(_ inspection: Inspection) {
        phonePath = inspection.phonePath
        sizeBytes = inspection.sizeBytes
        title = inspection.view.title
        artist = inspection.view.artist
        album = inspection.view.album
        isCompilation = inspection.view.isCompilation
        warnings = inspection.warnings
        status = inspection.blocker.map { .problem($0) } ?? .waiting
    }
}

/// Queue persistence: survives relaunch so a closed-then-reopened app still
/// offers the songs she dropped. Only waiting/problem items are kept; sent ones
/// are kept for a day so "what's already gone across" stays visible.
enum QueueFile {
    static func load(from url: URL) -> [QueueItem] {
        guard let data = try? Data(contentsOf: url) else { return [] }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        var items = (try? decoder.decode([QueueItem].self, from: data)) ?? []
        let cutoff = Date().addingTimeInterval(-24 * 3600)
        items.removeAll { item in
            if case .sent(let at) = item.status { return at < cutoff }
            return false
        }
        for i in items.indices where items[i].status == .sending || items[i].status == .checking {
            items[i].status = .waiting
        }
        return items
    }

    static func save(_ items: [QueueItem], to url: URL) {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        guard let data = try? encoder.encode(items) else { return }
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? data.write(to: url, options: .atomic)
    }
}
