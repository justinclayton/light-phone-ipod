package com.thelightphone.ipod.player

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.thelightphone.ipod.data.db.TrackEntity
import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioPlayback
import com.thelightphone.sdk.audio.LightAudioSource
import com.thelightphone.sdk.audio.LightAudioUsage
import com.thelightphone.sdk.audio.LightMediaMetadata
import com.thelightphone.sdk.audio.NO_MEDIA_ITEM
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val QUEUE_IDS_KEY = stringPreferencesKey("player_queue_track_ids")
private val CURRENT_TRACK_ID_KEY = longPreferencesKey("player_current_track_id")
private val POSITION_MS_KEY = longPreferencesKey("player_position_ms")
private val SHUFFLE_KEY = booleanPreferencesKey("player_shuffle")
private val REPEAT_KEY = stringPreferencesKey("player_repeat")

private const val PERSIST_INTERVAL_MS = 5_000L

/**
 * The single shared [com.thelightphone.sdk.audio.LightAudioPlayer] (D6). Owned
 * for the process lifetime, not by any one screen's ViewModel, since detached
 * playback must survive screens being destroyed and only one detached handle
 * may exist at a time.
 */
class PlayerController(
    audio: LightAudio,
    private val musicRoot: File,
    private val dataStore: DataStore<Preferences>,
    private val resolveTracks: suspend (List<Long>) -> List<TrackEntity>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val player = audio.newPlayer(usage = LightAudioUsage.Music, playback = LightAudioPlayback.Detached)

    /** Source order as selected (e.g. an album's track order), before shuffle. */
    private var sourceQueue: List<TrackEntity> = emptyList()

    /** Position to resume at once a fresh (idle-stopped) session's queue is rebuilt; consumed in [togglePlayPause]. */
    private var restoredPositionMs: Long = 0L

    private val _queue = MutableStateFlow<List<TrackEntity>>(emptyList())
    val queue: StateFlow<List<TrackEntity>> = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(NO_MEDIA_ITEM)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()

    private val _repeat = MutableStateFlow(RepeatMode.Off)
    val repeat: StateFlow<RepeatMode> = _repeat.asStateFlow()

    val currentTrack: StateFlow<TrackEntity?> = combine(_queue, _currentIndex) { q, i -> q.getOrNull(i) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val isPlaying: StateFlow<Boolean> = player.isPlaying
    val positionMs: StateFlow<Long> = player.positionMs
    val durationMs: StateFlow<Long> = player.durationMs
    val error = player.error

    init {
        scope.launch {
            player.currentMediaItemIndex.collect { idx ->
                if (idx != NO_MEDIA_ITEM && _repeat.value != RepeatMode.One) _currentIndex.value = idx
            }
        }
        scope.launch { watchForTrackEnd() }
        scope.launch { periodicallyPersist() }
        scope.launch { restore() }
    }

    /** Replaces the queue with [tracks] and starts playback at [startIndex] (iPod behavior: any selection replaces the queue). */
    fun play(tracks: List<TrackEntity>, startIndex: Int) {
        sourceQueue = tracks
        applyShuffle(anchorIndex = startIndex)
        pushToPlayer(_currentIndex.value)
        player.play()
        persistNow()
    }

    fun togglePlayPause() {
        if (_queue.value.isEmpty()) return
        if (player.currentMediaItemIndex.value == NO_MEDIA_ITEM) {
            // A fresh (never-connected, or idle-stopped) detached session: rebuild
            // the queue from persisted state before resuming.
            pushToPlayer(_currentIndex.value)
            if (restoredPositionMs > 0L) {
                player.seekTo(restoredPositionMs)
                restoredPositionMs = 0L
            }
        }
        if (isPlaying.value) player.pause() else player.play()
        persistNow()
    }

    fun seekTo(ms: Long) = player.seekTo(ms)

    fun skipToNext() {
        val q = _queue.value
        if (q.isEmpty()) return
        if (_repeat.value == RepeatMode.One) {
            advanceManually(1)
        } else {
            player.skipToNext()
        }
        persistNow()
    }

    fun skipToPrevious() {
        val q = _queue.value
        if (q.isEmpty()) return
        if (_repeat.value == RepeatMode.One) {
            advanceManually(-1)
        } else {
            player.skipToPrevious()
        }
        persistNow()
    }

    fun toggleShuffle() {
        _shuffle.value = !_shuffle.value
        val anchor = currentTrack.value?.let { sourceQueue.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        val wasPlaying = isPlaying.value
        val position = positionMs.value
        applyShuffle(anchorIndex = anchor)
        pushToPlayer(_currentIndex.value)
        player.seekTo(position)
        if (wasPlaying) player.play()
        persistNow()
    }

    fun cycleRepeat() {
        val wasPlaying = isPlaying.value
        val position = positionMs.value
        _repeat.value = _repeat.value.next()
        if (_queue.value.isNotEmpty()) {
            pushToPlayer(_currentIndex.value)
            player.seekTo(position)
            if (wasPlaying) player.play()
        }
        persistNow()
    }

    private fun advanceManually(delta: Int) {
        val q = _queue.value
        if (q.isEmpty()) return
        val next = (_currentIndex.value + delta).mod(q.size)
        _currentIndex.value = next
        pushToPlayer(next)
        player.play()
    }

    private fun applyShuffle(anchorIndex: Int) {
        val safeAnchor = anchorIndex.coerceIn(sourceQueue.indices)
        _queue.value = if (_shuffle.value) shuffledQueue(sourceQueue, safeAnchor) else sourceQueue
        _currentIndex.value = if (_shuffle.value) 0 else safeAnchor
    }

    /** Pushes the effective queue to the player. Repeat-one uses a single-item queue (see ADR D6). */
    private fun pushToPlayer(startAt: Int) {
        val q = _queue.value
        if (q.isEmpty() || startAt !in q.indices) return
        val items = if (_repeat.value == RepeatMode.One) {
            listOf(q[startAt].toAudioItem())
        } else {
            q.map { it.toAudioItem() }
        }
        val start = if (_repeat.value == RepeatMode.One) 0 else startAt
        player.setMediaQueue(items, start)
    }

    private fun TrackEntity.toAudioItem() = LightAudioItem(
        source = LightAudioSource.FileSource(File(musicRoot, path)),
        metadata = LightMediaMetadata(title = title, artist = artist, album = album, durationMs = durationMs),
    )

    private suspend fun watchForTrackEnd() {
        var wasPlaying = false
        combine(player.isPlaying, player.positionMs, player.durationMs) { playing, pos, dur -> Triple(playing, pos, dur) }
            .collect { (playing, pos, dur) ->
                val justStopped = wasPlaying && !playing
                wasPlaying = playing
                if (!justStopped || !isAtTrackEnd(pos, dur)) return@collect

                when (_repeat.value) {
                    RepeatMode.One -> {
                        player.seekTo(0)
                        player.play()
                    }
                    RepeatMode.All -> if (_currentIndex.value == _queue.value.lastIndex) {
                        pushToPlayer(0)
                        player.play()
                    }
                    RepeatMode.Off -> Unit // media3 auto-advances mid-queue; stops naturally at the end.
                }
            }
    }

    private suspend fun periodicallyPersist() {
        while (true) {
            delay(PERSIST_INTERVAL_MS)
            if (isPlaying.value) persistNow()
        }
    }

    private fun persistNow() {
        val track = currentTrack.value ?: return
        val ids = sourceQueue.joinToString(",") { it.id.toString() }
        val pos = positionMs.value
        scope.launch {
            dataStore.edit { prefs ->
                prefs[QUEUE_IDS_KEY] = ids
                prefs[CURRENT_TRACK_ID_KEY] = track.id
                prefs[POSITION_MS_KEY] = pos
                prefs[SHUFFLE_KEY] = _shuffle.value
                prefs[REPEAT_KEY] = _repeat.value.name
            }
        }
    }

    private suspend fun restore() {
        val prefs = dataStore.data.first()
        val ids = prefs[QUEUE_IDS_KEY]?.split(",")?.mapNotNull { it.toLongOrNull() }.orEmpty()
        if (ids.isEmpty()) return

        sourceQueue = resolveTracks(ids)
        if (sourceQueue.isEmpty()) return

        _shuffle.value = prefs[SHUFFLE_KEY] ?: false
        _repeat.value = prefs[REPEAT_KEY]?.let { name -> RepeatMode.entries.firstOrNull { it.name == name } } ?: RepeatMode.Off

        val currentTrackId = prefs[CURRENT_TRACK_ID_KEY]
        val restoredQueue = if (_shuffle.value) {
            val anchor = sourceQueue.indexOfFirst { it.id == currentTrackId }.coerceAtLeast(0)
            shuffledQueue(sourceQueue, anchor)
        } else {
            sourceQueue
        }
        _queue.value = restoredQueue
        _currentIndex.value = restoredQueue.indexOfFirst { it.id == currentTrackId }.coerceAtLeast(0)
        restoredPositionMs = prefs[POSITION_MS_KEY] ?: 0L

        // If a live detached session already holds this queue, its own flows take
        // over from here; leave it alone. Only a fresh (idle-stopped) session
        // needs the queue rebuilt, which togglePlayPause() does on next Play.
    }
}
