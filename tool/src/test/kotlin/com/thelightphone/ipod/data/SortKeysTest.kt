package com.thelightphone.ipod.data

import com.thelightphone.ipod.data.db.TrackEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun track(
    path: String = "t.mp3",
    title: String = "Title",
    artist: String = "Artist",
    albumArtist: String? = null,
    album: String = "Album",
    isCompilation: Boolean = false,
) = TrackEntity(
    path = path,
    sizeBytes = 0,
    mtimeMs = 0,
    title = title,
    artist = artist,
    albumArtist = albumArtist,
    album = album,
    discNo = null,
    trackNo = null,
    year = null,
    genre = null,
    durationMs = 0,
    isCompilation = isCompilation,
)

class SortKeysTest {
    @Test
    fun sortKeyStripsLeadingArticlesCaseInsensitively() {
        assertEquals("beatles", sortKey("The Beatles"))
        assertEquals("beatles", sortKey("the beatles"))
        assertEquals("team", sortKey("A Team"))
        assertEquals("astronaut", sortKey("An Astronaut"))
    }

    @Test
    fun sortKeyLeavesNonArticleWordsAlone() {
        assertEquals("theatre of pain", sortKey("Theatre of Pain")) // "The" is not a standalone word here
        assertEquals("radiohead", sortKey("Radiohead"))
    }

    @Test
    fun sortKeyIsCaseInsensitiveOverall() {
        assertEquals(sortKey("ABBA"), sortKey("abba"))
    }

    @Test
    fun albumKeyGroupsByAlbumArtistOrArtistFallback() {
        val withAlbumArtist = track(artist = "Solo Artist", albumArtist = "The Band", album = "LP")
        val withoutAlbumArtist = track(artist = "Solo Artist", albumArtist = null, album = "LP")

        assertEquals(AlbumKey("The Band", "LP", false), albumKeyOf(withAlbumArtist))
        assertEquals(AlbumKey("Solo Artist", "LP", false), albumKeyOf(withoutAlbumArtist))
    }

    @Test
    fun compilationTracksGroupUnderVariousArtistsRegardlessOfArtist() {
        val a = track(artist = "Artist A", album = "Now That's What I Call Music", isCompilation = true)
        val b = track(artist = "Artist B", album = "Now That's What I Call Music", isCompilation = true)

        val keyA = albumKeyOf(a)
        val keyB = albumKeyOf(b)

        assertEquals(keyA, keyB)
        assertEquals(VARIOUS_ARTISTS, keyA.albumArtist)
        assertTrue(keyA.isCompilation)
    }

    @Test
    fun trackInAlbumComparatorOrdersByDiscThenTrackNumber() {
        val disc2track1 = track(path = "a").copy(discNo = 2, trackNo = 1)
        val disc1track2 = track(path = "b").copy(discNo = 1, trackNo = 2)
        val disc1track1 = track(path = "c").copy(discNo = 1, trackNo = 1)

        val sorted = listOf(disc2track1, disc1track2, disc1track1).sortedWith(trackInAlbumComparator)

        assertEquals(listOf(disc1track1, disc1track2, disc2track1), sorted)
    }

    @Test
    fun trackInAlbumComparatorPutsUnsetTrackNumbersLast() {
        val withNumber = track(path = "a").copy(discNo = 1, trackNo = 1)
        val withoutNumber = track(path = "b").copy(discNo = 1, trackNo = null)

        val sorted = listOf(withoutNumber, withNumber).sortedWith(trackInAlbumComparator)

        assertEquals(listOf(withNumber, withoutNumber), sorted)
    }
}
