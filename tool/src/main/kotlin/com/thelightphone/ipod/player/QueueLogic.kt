package com.thelightphone.ipod.player

import com.thelightphone.ipod.data.db.TrackEntity
import kotlin.random.Random

enum class RepeatMode {
    Off,
    One,
    All,
    ;

    fun next(): RepeatMode = when (this) {
        Off -> All
        All -> One
        One -> Off
    }
}

/** Reorders [source] with the track at [currentIndex] first and the rest shuffled. */
fun shuffledQueue(source: List<TrackEntity>, currentIndex: Int, random: Random = Random.Default): List<TrackEntity> {
    if (source.isEmpty()) return source
    val current = source[currentIndex]
    val rest = (source.indices - currentIndex).map { source[it] }.shuffled(random)
    return listOf(current) + rest
}

/**
 * A track is considered to have played to its natural end (rather than been
 * paused by the user) once its reported position is within this many
 * milliseconds of the reported duration. [LightAudioPlayer] does not expose a
 * distinct "ended" playback state, so this threshold is how repeat handling
 * tells the two apart (see ADR D6).
 */
const val TRACK_END_THRESHOLD_MS = 750L

fun isAtTrackEnd(positionMs: Long, durationMs: Long): Boolean =
    durationMs > 0L && positionMs >= durationMs - TRACK_END_THRESHOLD_MS

/** Result of dropping a deleted track from the playback queue. */
data class QueueRemoval(
    val queue: List<TrackEntity>,
    /** Index to play from in [queue]; `NO_MEDIA_ITEM`-equivalent (-1) when [queue] is empty. */
    val currentIndex: Int,
    /** True when the removed track was the one playing, so playback must move on to a new item. */
    val currentRemoved: Boolean,
)

/**
 * Removes [trackId] from [queue]. If it was the current track, the item that
 * followed it becomes current (or the new last item); otherwise the current
 * track is kept and its index shifted.
 */
fun removeTrackFromQueue(queue: List<TrackEntity>, currentIndex: Int, trackId: Long): QueueRemoval {
    val removedIndex = queue.indexOfFirst { it.id == trackId }
    if (removedIndex < 0) return QueueRemoval(queue, currentIndex, currentRemoved = false)
    val remaining = queue.filterIndexed { i, _ -> i != removedIndex }
    if (remaining.isEmpty()) return QueueRemoval(remaining, -1, currentRemoved = removedIndex == currentIndex)
    return when {
        removedIndex == currentIndex ->
            QueueRemoval(remaining, currentIndex.coerceAtMost(remaining.lastIndex), currentRemoved = true)
        removedIndex < currentIndex -> QueueRemoval(remaining, currentIndex - 1, currentRemoved = false)
        else -> QueueRemoval(remaining, currentIndex, currentRemoved = false)
    }
}
