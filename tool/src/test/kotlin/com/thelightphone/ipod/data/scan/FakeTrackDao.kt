package com.thelightphone.ipod.data.scan

import com.thelightphone.ipod.data.db.TrackDao
import com.thelightphone.ipod.data.db.TrackEntity
import com.thelightphone.ipod.data.db.TrackFingerprint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [TrackDao] so scan-diffing logic is testable without Room/Android (see ADR M1). */
class FakeTrackDao : TrackDao {
    private val state = MutableStateFlow<List<TrackEntity>>(emptyList())
    private var nextId = 1L

    override fun observeAll(): Flow<List<TrackEntity>> = state

    override suspend fun fingerprints(): List<TrackFingerprint> =
        state.value.map { TrackFingerprint(it.id, it.path, it.sizeBytes, it.mtimeMs) }

    override suspend fun getByPath(path: String): TrackEntity? = state.value.firstOrNull { it.path == path }

    override suspend fun getById(id: Long): TrackEntity? = state.value.firstOrNull { it.id == id }

    override suspend fun insert(track: TrackEntity): Long {
        val id = if (track.id != 0L) track.id else nextId++
        val saved = track.copy(id = id)
        state.value = state.value.filterNot { it.id == id } + saved
        return id
    }

    override suspend fun markMissing(ids: List<Long>) {
        state.value = state.value.map { if (it.id in ids) it.copy(missing = true) else it }
    }

    override suspend fun count(): Int = state.value.count { !it.missing }
}
