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
