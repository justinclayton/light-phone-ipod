package com.thelightphone.ipod.data.m3u

import com.thelightphone.ipod.data.db.TrackEntity

/**
 * Encodes/decodes the `#EXTM3U` mirror of a Room playlist (D3). Paths are
 * relative to `music/`, matching [com.thelightphone.ipod.data.db.TrackEntity.path].
 */
object M3uCodec {
    fun encode(tracks: List<TrackEntity>): String = buildString {
        appendLine("#EXTM3U")
        for (track in tracks) {
            val durationSeconds = (track.durationMs / 1000L).coerceAtLeast(0L)
            appendLine("#EXTINF:$durationSeconds,${track.artist} - ${track.title}")
            appendLine(track.path)
        }
    }

    /** Returns the ordered list of track paths (relative to `music/`) referenced by an m3u8 file. */
    fun decodePaths(content: String): List<String> =
        content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
}
