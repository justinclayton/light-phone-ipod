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

    private fun queueOf(n: Int) = (1..n).map { track("t$it").copy(id = it.toLong()) }

    @Test
    fun removingTheCurrentTrackAdvancesToTheNextOne() {
        val result = removeTrackFromQueue(queueOf(4), currentIndex = 1, trackId = 2)
        assertEquals(listOf(1L, 3L, 4L), result.queue.map { it.id })
        assertEquals(1, result.currentIndex)
        assertTrue(result.currentRemoved)
    }

    @Test
    fun removingTheLastCurrentTrackFallsBackToTheNewLast() {
        val result = removeTrackFromQueue(queueOf(3), currentIndex = 2, trackId = 3)
        assertEquals(1, result.currentIndex)
        assertTrue(result.currentRemoved)
    }

    @Test
    fun removingAnEarlierTrackShiftsTheCurrentIndex() {
        val result = removeTrackFromQueue(queueOf(4), currentIndex = 2, trackId = 1)
        assertEquals(1, result.currentIndex)
        assertEquals(3L, result.queue[result.currentIndex].id)
        assertFalse(result.currentRemoved)
    }

    @Test
    fun removingALaterTrackLeavesTheCurrentIndex() {
        val result = removeTrackFromQueue(queueOf(4), currentIndex = 1, trackId = 4)
        assertEquals(1, result.currentIndex)
        assertFalse(result.currentRemoved)
    }

    @Test
    fun removingTheOnlyTrackEmptiesTheQueue() {
        val result = removeTrackFromQueue(queueOf(1), currentIndex = 0, trackId = 1)
        assertTrue(result.queue.isEmpty())
        assertEquals(-1, result.currentIndex)
    }

    @Test
    fun removingAnUnqueuedTrackChangesNothing() {
        val queue = queueOf(3)
        val result = removeTrackFromQueue(queue, currentIndex = 1, trackId = 99)
        assertEquals(queue, result.queue)
        assertEquals(1, result.currentIndex)
        assertFalse(result.currentRemoved)
    }
}
