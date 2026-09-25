package com.thelightphone.ipod.player

import com.thelightphone.ipod.data.db.TrackEntity
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun track(path: String) = TrackEntity(
    path = path,
    sizeBytes = 0,
    mtimeMs = 0,
    title = path,
    artist = "Artist",
    albumArtist = null,
    album = "Album",
    discNo = null,
    trackNo = null,
    year = null,
    genre = null,
    durationMs = 0,
    isCompilation = false,
)

class QueueLogicTest {
    @Test
    fun shuffledQueuePutsCurrentTrackFirstAndKeepsTheSameSet() {
        val tracks = (1..10).map { track("t$it") }

        val shuffled = shuffledQueue(tracks, currentIndex = 4, random = Random(seed = 1))

        assertEquals(tracks[4], shuffled.first())
        assertEquals(tracks.toSet(), shuffled.toSet())
        assertEquals(tracks.size, shuffled.size)
    }

    @Test
    fun shuffledQueueOfSingleTrackIsUnchanged() {
        val tracks = listOf(track("only"))

        val shuffled = shuffledQueue(tracks, currentIndex = 0)

        assertEquals(tracks, shuffled)
    }

    @Test
    fun repeatModeCyclesOffAllOneOff() {
        assertEquals(RepeatMode.All, RepeatMode.Off.next())
        assertEquals(RepeatMode.One, RepeatMode.All.next())
        assertEquals(RepeatMode.Off, RepeatMode.One.next())
    }

    @Test
    fun trackEndDetectionRequiresPositionNearDuration() {
        assertTrue(isAtTrackEnd(positionMs = 59_500L, durationMs = 60_000L))
        assertFalse(isAtTrackEnd(positionMs = 30_000L, durationMs = 60_000L))
    }

    @Test
    fun trackEndDetectionIgnoresUnknownDuration() {
        assertFalse(isAtTrackEnd(positionMs = 0L, durationMs = 0L))
    }
}
