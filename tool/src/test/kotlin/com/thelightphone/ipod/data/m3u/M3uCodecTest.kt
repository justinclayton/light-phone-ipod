package com.thelightphone.ipod.data.m3u

import com.thelightphone.ipod.data.db.TrackEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun track(path: String, title: String, artist: String, durationMs: Long) = TrackEntity(
    path = path,
    sizeBytes = 0,
    mtimeMs = 0,
    title = title,
    artist = artist,
    albumArtist = null,
    album = "Album",
    discNo = null,
    trackNo = null,
    year = null,
    genre = null,
    durationMs = durationMs,
    isCompilation = false,
)

class M3uCodecTest {
    @Test
    fun encodeStartsWithExtM3uHeader() {
        val content = M3uCodec.encode(emptyList())
        assertTrue(content.startsWith("#EXTM3U"))
    }

    @Test
    fun encodeThenDecodeRoundTripsTrackPaths() {
        val tracks = listOf(
            track("Artist A/song one.mp3", "Song One", "Artist A", 61_000L),
            track("Artist B/Album/song two.flac", "Song Two", "Artist B", 200_000L),
        )

        val content = M3uCodec.encode(tracks)
        val paths = M3uCodec.decodePaths(content)

        assertEquals(tracks.map { it.path }, paths)
    }

    @Test
    fun decodeIgnoresCommentsAndBlankLines() {
        val content = """
            #EXTM3U
            #EXTINF:120,Some Artist - Some Title

            music/track.mp3
        """.trimIndent()

        assertEquals(listOf("music/track.mp3"), M3uCodec.decodePaths(content))
    }
}
