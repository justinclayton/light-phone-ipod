package com.thelightphone.ipod.data.scan

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val SILENT_TAGS = TrackTags(
    title = null,
    artist = null,
    albumArtist = null,
    album = null,
    discNo = null,
    trackNo = null,
    year = null,
    genre = null,
    durationMs = 1_000L,
    isCompilation = false,
)

class LibraryScannerTest {
    @Test
    fun scanAddsNewFilesAndAppliesTagFallbacks() = kotlinx.coroutines.runBlocking {
        val root = createTempDirectory("ipod-library").toFile()
        File(root, "Track One.mp3").writeBytes(byteArrayOf(1, 2, 3))
        val dao = FakeTrackDao()
        val scanner = LibraryScanner(root, TagReader { SILENT_TAGS }, dao)

        val result = scanner.scan()

        assertEquals(1, result.added)
        assertEquals(1, result.totalTracks)
        val track = dao.fingerprints().single()
        val saved = dao.getByPath(track.path)!!
        assertEquals("Track One", saved.title) // falls back to filename
        assertEquals(LibraryScanner.UNKNOWN_ARTIST, saved.artist)
        assertEquals(LibraryScanner.UNKNOWN_ALBUM, saved.album)
    }

    @Test
    fun scanUsesTagsWhenPresent() = kotlinx.coroutines.runBlocking {
        val root = createTempDirectory("ipod-library").toFile()
        File(root, "song.mp3").writeBytes(byteArrayOf(1))
        val tags = SILENT_TAGS.copy(title = "Real Title", artist = "Real Artist", album = "Real Album")
        val dao = FakeTrackDao()
        val scanner = LibraryScanner(root, TagReader { tags }, dao)

        scanner.scan()

        val saved = dao.getByPath("song.mp3")!!
        assertEquals("Real Title", saved.title)
        assertEquals("Real Artist", saved.artist)
        assertEquals("Real Album", saved.album)
    }

    @Test
    fun rescanSkipsUnchangedFilesAndReParsesModifiedOnes() = kotlinx.coroutines.runBlocking {
        val root = createTempDirectory("ipod-library").toFile()
        val file = File(root, "song.mp3").apply { writeBytes(byteArrayOf(1)) }
        var readCount = 0
        val dao = FakeTrackDao()
        val scanner = LibraryScanner(root, TagReader { readCount++; SILENT_TAGS }, dao)

        scanner.scan()
        assertEquals(1, readCount)

        val unchanged = scanner.scan()
        assertEquals(0, unchanged.added)
        assertEquals(0, unchanged.updated)
        assertEquals(1, readCount) // no re-parse when size/mtime match

        file.setLastModified(file.lastModified() + 5_000L)
        file.writeBytes(byteArrayOf(1, 2))
        val changed = scanner.scan()
        assertEquals(0, changed.added)
        assertEquals(1, changed.updated)
        assertEquals(2, readCount)
    }

    @Test
    fun scanMarksDisappearedFilesMissingWithoutDeletingThem() = kotlinx.coroutines.runBlocking {
        val root = createTempDirectory("ipod-library").toFile()
        val file = File(root, "song.mp3").apply { writeBytes(byteArrayOf(1)) }
        val dao = FakeTrackDao()
        val scanner = LibraryScanner(root, TagReader { SILENT_TAGS }, dao)
        scanner.scan()

        file.delete()
        val result = scanner.scan()

        assertEquals(1, result.removed)
        assertEquals(0, result.totalTracks) // missing tracks don't count toward the visible total
        val stillTracked = dao.getByPath("song.mp3")
        assertTrue(stillTracked != null && stillTracked.missing) // kept so playlists can still reference it
    }

    @Test
    fun scanOnlyFindsSupportedAudioExtensionsRecursively() = kotlinx.coroutines.runBlocking {
        val root = createTempDirectory("ipod-library").toFile()
        File(root, "cover.jpg").writeBytes(byteArrayOf(1))
        val nested = File(root, "Artist/Album").apply { mkdirs() }
        File(nested, "01 song.flac").writeBytes(byteArrayOf(1))
        val dao = FakeTrackDao()
        val scanner = LibraryScanner(root, TagReader { SILENT_TAGS }, dao)

        val result = scanner.scan()

        assertEquals(1, result.added)
        assertEquals("Artist/Album/01 song.flac", dao.fingerprints().single().path)
    }
}
