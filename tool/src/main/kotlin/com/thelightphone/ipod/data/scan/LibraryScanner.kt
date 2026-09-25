package com.thelightphone.ipod.data.scan

import com.thelightphone.ipod.data.db.TrackDao
import com.thelightphone.ipod.data.db.TrackEntity
import com.thelightphone.ipod.data.db.TrackFingerprint
import java.io.File

data class ScanResult(
    val added: Int,
    val updated: Int,
    val removed: Int,
    val totalTracks: Int,
)

/**
 * Walks the library's music folder, diffs it against the cached [TrackDao] rows
 * by (path, size, mtime), and parses tags only for new/changed files.
 *
 * `musicRoot` is read directly via `File`, not `LightFileShare.list()`, because
 * that call is not recursive (see ADR risk notes).
 */
class LibraryScanner(
    private val musicRoot: File,
    private val tagReader: TagReader,
    private val trackDao: TrackDao,
) {
    suspend fun scan(): ScanResult {
        val onDisk = walk(musicRoot).associateBy { it.relativePath }
        val cached = trackDao.fingerprints().associateBy { it.path }

        val missingNow = (cached.keys - onDisk.keys).mapNotNull { cached[it]?.id }
        if (missingNow.isNotEmpty()) {
            trackDao.markMissing(missingNow)
        }

        var added = 0
        var updated = 0
        for ((relativePath, entry) in onDisk) {
            val existing = cached[relativePath]
            val unchanged = existing != null &&
                existing.sizeBytes == entry.file.length() &&
                existing.mtimeMs == entry.file.lastModified()
            if (unchanged) continue

            val track = toTrackEntity(entry, tagReader.read(entry.file))
            if (existing == null) {
                trackDao.insert(track)
                added++
            } else {
                trackDao.insert(track.copy(id = existing.id))
                updated++
            }
        }

        return ScanResult(
            added = added,
            updated = updated,
            removed = missingNow.size,
            totalTracks = trackDao.count(),
        )
    }

    private data class WalkEntry(val relativePath: String, val file: File)

    private fun walk(root: File): List<WalkEntry> {
        if (!root.isDirectory) return emptyList()
        val results = mutableListOf<WalkEntry>()
        fun visit(dir: File) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                when {
                    child.isDirectory -> visit(child)
                    child.extension.lowercase() in SUPPORTED_EXTENSIONS -> {
                        val relativePath = child.relativeTo(root).path
                        results += WalkEntry(relativePath, child)
                    }
                }
            }
        }
        visit(root)
        return results
    }

    private fun toTrackEntity(entry: WalkEntry, tags: TrackTags): TrackEntity {
        val fallbackTitle = entry.file.nameWithoutExtension
        return TrackEntity(
            path = entry.relativePath,
            sizeBytes = entry.file.length(),
            mtimeMs = entry.file.lastModified(),
            title = tags.title ?: fallbackTitle,
            artist = tags.artist ?: UNKNOWN_ARTIST,
            albumArtist = tags.albumArtist,
            album = tags.album ?: UNKNOWN_ALBUM,
            discNo = tags.discNo,
            trackNo = tags.trackNo,
            year = tags.year,
            genre = tags.genre,
            durationMs = tags.durationMs,
            isCompilation = tags.isCompilation,
        )
    }

    companion object {
        /** File types the scanner indexes; the sync engine refuses anything else before downloading. */
        val SUPPORTED_EXTENSIONS: Set<String> = setOf("mp3", "m4a", "aac", "wav", "ogg", "flac")
        const val UNKNOWN_ARTIST = "Unknown Artist"
        const val UNKNOWN_ALBUM = "Unknown Album"
    }
}
