package com.thelightphone.ipod.data

import com.thelightphone.ipod.data.db.TrackEntity

private val LEADING_ARTICLES = listOf("the ", "a ", "an ")

/**
 * iPod-style sort key: case-insensitive, leading "The "/"A "/"An " stripped.
 * Never used for display — callers keep showing the original string.
 */
fun sortKey(value: String): String {
    val lower = value.lowercase()
    val article = LEADING_ARTICLES.firstOrNull { lower.startsWith(it) }
    return if (article != null) lower.substring(article.length) else lower
}

/** Groups tracks into albums per D7: (albumArtist ?? artist, album), with a shared "Compilations" bucket. */
data class AlbumKey(
    val albumArtist: String,
    val albumName: String,
    val isCompilation: Boolean,
)

const val VARIOUS_ARTISTS = "Various Artists"

fun albumKeyOf(track: TrackEntity): AlbumKey =
    if (track.isCompilation) {
        AlbumKey(albumArtist = VARIOUS_ARTISTS, albumName = track.album, isCompilation = true)
    } else {
        AlbumKey(albumArtist = track.albumArtist ?: track.artist, albumName = track.album, isCompilation = false)
    }

/** Albums sort by year (undated last) then name. */
val albumComparator: Comparator<Pair<AlbumKey, List<TrackEntity>>> =
    compareBy(
        { (_, tracks) -> tracks.mapNotNull { it.year }.minOrNull() ?: Int.MAX_VALUE },
        { (key, _) -> sortKey(key.albumName) },
    )

/** Tracks within an album sort by disc then track number (unset sorts last). */
val trackInAlbumComparator: Comparator<TrackEntity> =
    compareBy(
        { it.discNo ?: Int.MAX_VALUE },
        { it.trackNo ?: Int.MAX_VALUE },
        { sortKey(it.title) },
    )
