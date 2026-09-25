package com.thelightphone.ipod.data.scan

import android.media.MediaMetadataRetriever
import java.io.File

/**
 * Production [TagReader]. [MediaMetadataRetriever] takes a plain file path — no
 * Context needed — which keeps it outside the SDK's sandbox restrictions.
 */
class MediaMetadataRetrieverTagReader : TagReader {
    override fun read(file: File): TrackTags {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            fun key(code: Int) = retriever.extractMetadata(code)?.trim()?.takeIf { it.isNotEmpty() }

            val compilationFlag = key(MediaMetadataRetriever.METADATA_KEY_COMPILATION)
            val albumArtist = key(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            TrackTags(
                title = key(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = key(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                albumArtist = albumArtist,
                album = key(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                discNo = key(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)?.leadingIntOrNull(),
                trackNo = key(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.leadingIntOrNull(),
                year = key(MediaMetadataRetriever.METADATA_KEY_YEAR)?.leadingIntOrNull(),
                genre = key(MediaMetadataRetriever.METADATA_KEY_GENRE),
                durationMs = key(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                isCompilation = compilationFlag == "1" || albumArtist.equals("Various Artists", ignoreCase = true),
            )
        } catch (_: RuntimeException) {
            TrackTags(
                title = null,
                artist = null,
                albumArtist = null,
                album = null,
                discNo = null,
                trackNo = null,
                year = null,
                genre = null,
                durationMs = 0L,
                isCompilation = false,
            )
        } finally {
            retriever.release()
        }
    }
}

/** Tag values like "3/12" (track 3 of 12) — take the leading number. */
private fun String.leadingIntOrNull(): Int? = substringBefore('/').trim().toIntOrNull()
