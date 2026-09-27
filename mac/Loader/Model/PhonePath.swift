import Foundation

/// What the phone will see for one file, derived from its tags exactly the way
/// the tool's `LibraryScanner` does (title ← filename; artist/album ← Unknown
/// buckets; compilation ← flag or album artist "Various Artists").
struct PhoneView: Equatable {
    var title: String
    var artist: String
    var album: String
    var albumArtist: String?
    var trackNumber: Int?
    var discNumber: Int?
    var isCompilation: Bool

    static let unknownArtist = "Unknown Artist"
    static let unknownAlbum = "Unknown Album"

    var artistIsUnknown: Bool { artist == PhoneView.unknownArtist }
    var albumIsUnknown: Bool { album == PhoneView.unknownAlbum }
}

/// Builds the `path` field of a queue item: `<Artist>/<Album>/<NN Title>.<ext>`,
/// relative to the phone's `music/` folder, sanitized so the phone accepts it
/// (protocol §2: no `..`, `:`, control characters, empty segments, `.part`).
enum PhonePath {
    static let supportedExtensions: Set<String> = ["mp3", "m4a", "aac", "wav", "ogg", "flac"]
    static let maxSegmentLength = 120

    static func build(view: PhoneView, originalFilename: String) -> String {
        let ext = (originalFilename as NSString).pathExtension.lowercased()
        let folderArtist: String
        if let aa = view.albumArtist, !aa.isEmpty {
            folderArtist = aa
        } else if view.isCompilation {
            folderArtist = "Various Artists"
        } else {
            folderArtist = view.artist
        }
        var file = view.title
        if let n = view.trackNumber, n > 0 {
            let disc = (view.discNumber ?? 1) > 1 ? "\(view.discNumber!)-" : ""
            file = disc + String(format: "%02d", n) + " " + file
        }
        return [segment(folderArtist, fallback: PhoneView.unknownArtist),
                segment(view.album, fallback: PhoneView.unknownAlbum),
                segment(file, fallback: "Untitled") + "." + ext].joined(separator: "/")
    }

    /// One path component the phone will store as-is.
    static func segment(_ raw: String, fallback: String) -> String {
        var scalars = String.UnicodeScalarView()
        for s in raw.unicodeScalars {
            switch s {
            case "/", ":", "\\", "\0":
                scalars.append("-")
            case let c where c.value < 0x20 || c.value == 0x7f:
                scalars.append(" ")
            default:
                scalars.append(s)
            }
        }
        var s = String(scalars)
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "."))
            .trimmingCharacters(in: .whitespaces)
        while s.contains("  ") { s = s.replacingOccurrences(of: "  ", with: " ") }
        if s.count > maxSegmentLength { s = String(s.prefix(maxSegmentLength)).trimmingCharacters(in: .whitespaces) }
        if s.isEmpty || s == ".." || s.lowercased().hasSuffix(".part") { return fallback }
        return s
    }
}
