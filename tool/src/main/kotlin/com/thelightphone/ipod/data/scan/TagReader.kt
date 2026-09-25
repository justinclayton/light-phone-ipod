package com.thelightphone.ipod.data.scan

import java.io.File

/** Tags extracted from one audio file. Missing values are `null` — fallback rules live in [LibraryScanner]. */
data class TrackTags(
    val title: String?,
    val artist: String?,
    val albumArtist: String?,
    val album: String?,
    val discNo: Int?,
    val trackNo: Int?,
    val year: Int?,
    val genre: String?,
    val durationMs: Long,
    val isCompilation: Boolean,
)

/**
 * Reads ID3/iTunes-style tags from an audio file. The production implementation
 * ([MediaMetadataRetrieverTagReader]) needs a device; tests supply a fake so
 * scanning/sorting logic stays pure-JVM testable.
 */
fun interface TagReader {
    fun read(file: File): TrackTags
}
