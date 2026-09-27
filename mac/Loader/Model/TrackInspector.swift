import Foundation
import AVFoundation

/// Preflight (PRD §7): read what the phone will read, and say ahead of time
/// what will go wrong. Reports; never repairs.
struct Inspection {
    var view: PhoneView
    var phonePath: String
    var sizeBytes: Int
    /// Reason this file cannot be sent at all. `nil` means it will be queued.
    var blocker: String?
    /// Things that will look odd on the phone but still work.
    var warnings: [String]
}

enum TrackInspector {
    static let protectedMessage = "Songs from Apple Music can't be copied. This only works with music you own."
    static let unplayableMessage = "Your phone can't play this kind of file."
    static let unreadableMessage = "This file couldn't be opened."
    static let emptyMessage = "This file is empty."
    static let unknownArtistWarning = "This will show up as Unknown Artist on your phone."
    static let unknownAlbumWarning = "This will show up as Unknown Album on your phone."
    static let noTagsWarning = "This file has no song info, so your phone will use the file name."

    static func inspect(_ url: URL) async -> Inspection {
        let filename = url.lastPathComponent
        let ext = url.pathExtension.lowercased()
        let base = (filename as NSString).deletingPathExtension
        var view = PhoneView(title: base, artist: PhoneView.unknownArtist, album: PhoneView.unknownAlbum,
                             albumArtist: nil, trackNumber: nil, discNumber: nil, isCompilation: false)

        let attrs = try? FileManager.default.attributesOfItem(atPath: url.path)
        let size = (attrs?[.size] as? Int) ?? 0
        func result(blocker: String?, warnings: [String] = []) -> Inspection {
            Inspection(view: view, phonePath: PhonePath.build(view: view, originalFilename: filename),
                       sizeBytes: size, blocker: blocker, warnings: warnings)
        }

        guard FileManager.default.isReadableFile(atPath: url.path) else { return result(blocker: unreadableMessage) }
        guard size > 0 else { return result(blocker: emptyMessage) }
        if ext == "m4p" { return result(blocker: protectedMessage) }
        guard PhonePath.supportedExtensions.contains(ext) else { return result(blocker: unplayableMessage) }

        let asset = AVURLAsset(url: url)
        var tagsRead = false
        var anyTag = false
        if let protected = try? await asset.load(.hasProtectedContent), protected {
            return result(blocker: protectedMessage)
        }
        if let metadata = try? await asset.load(.metadata) {
            tagsRead = true
            func first(_ ids: [AVMetadataIdentifier]) async -> String? {
                for id in ids {
                    for item in AVMetadataItem.metadataItems(from: metadata, filteredByIdentifier: id) {
                        if let s = try? await item.load(.stringValue)?.trimmingCharacters(in: .whitespacesAndNewlines), !s.isEmpty {
                            return s
                        }
                        if let n = try? await item.load(.numberValue) { return n.stringValue }
                    }
                }
                return nil
            }
            func leadingInt(_ s: String?) -> Int? {
                guard let s else { return nil }
                return Int(s.split(separator: "/").first?.trimmingCharacters(in: .whitespaces) ?? "")
            }

            let title = await first([.commonIdentifierTitle, .id3MetadataTitleDescription, .iTunesMetadataSongName])
            let artist = await first([.commonIdentifierArtist, .id3MetadataLeadPerformer, .iTunesMetadataArtist])
            let albumArtist = await first([.id3MetadataBand, .iTunesMetadataAlbumArtist])
            let album = await first([.commonIdentifierAlbumName, .id3MetadataAlbumTitle, .iTunesMetadataAlbum])
            let track = leadingInt(await first([.id3MetadataTrackNumber, .iTunesMetadataTrackNumber]))
            let disc = leadingInt(await first([.id3MetadataPartOfASet, .iTunesMetadataDiscNumber]))
            let compilation = await first([AVMetadataIdentifier("id3/TCMP"), .iTunesMetadataDiscCompilation])
            let isCompilation = compilation == "1" || compilation?.lowercased() == "true"
                || albumArtist?.caseInsensitiveCompare("Various Artists") == .orderedSame

            anyTag = title != nil || artist != nil || album != nil
            view = PhoneView(title: title ?? base,
                             artist: artist ?? PhoneView.unknownArtist,
                             album: album ?? PhoneView.unknownAlbum,
                             albumArtist: albumArtist,
                             trackNumber: track,
                             discNumber: disc,
                             isCompilation: isCompilation)
        }

        var warnings: [String] = []
        if tagsRead && !anyTag {
            warnings.append(noTagsWarning)
        } else {
            if view.artistIsUnknown && !view.isCompilation { warnings.append(unknownArtistWarning) }
            if view.albumIsUnknown { warnings.append(unknownAlbumWarning) }
        }
        if !tagsRead && ext == "ogg" {
            warnings.append("Your Mac can't read the song info in this file, so it's named from the file name here. Your phone may read it fine.")
        }
        return result(blocker: nil, warnings: warnings)
    }
}
