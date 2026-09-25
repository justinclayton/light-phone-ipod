package com.thelightphone.ipod.data.sync

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryPathsTest {
    private val root = createTempDirectory("ipod-paths").toFile()

    @Test
    fun resolvesNestedPathsUnderTheMusicRoot() {
        val file = LibraryPaths.resolve(root, "Artist/Album/01 Song.mp3")!!
        assertEquals(File(root, "Artist/Album/01 Song.mp3").canonicalFile, file)
    }

    @Test
    fun normalizesBackslashes() {
        val file = LibraryPaths.resolve(root, "Artist\\Album\\song.m4a")!!
        assertEquals(File(root, "Artist/Album/song.m4a").canonicalFile, file)
    }

    @Test
    fun rejectsEscapesAndJunk() {
        listOf(
            "../outside.mp3",
            "Artist/../../outside.mp3",
            "/abs/olute.mp3",
            "",
            "   ",
            "Artist//song.mp3",
            "./song.mp3",
            "bad\u0000name.mp3",
            "C:/Music/song.mp3",
            "song.mp3.part",
        ).forEach { path ->
            assertNull(LibraryPaths.resolve(root, path), "should reject: $path")
        }
    }

    @Test
    fun playableMatchesTheScannerExtensions() {
        assertTrue(LibraryPaths.isPlayable("a/b/Song.MP3"))
        assertTrue(LibraryPaths.isPlayable("song.flac"))
        assertFalse(LibraryPaths.isPlayable("song.wma"))
        assertFalse(LibraryPaths.isPlayable("song"))
        assertFalse(LibraryPaths.isPlayable("cover.jpg"))
    }

    @Test
    fun displayNameIsTheFileName() {
        assertEquals("01 Song.mp3", LibraryPaths.displayName("Artist/Album/01 Song.mp3"))
        assertEquals("song.mp3", LibraryPaths.displayName("song.mp3"))
    }
}
