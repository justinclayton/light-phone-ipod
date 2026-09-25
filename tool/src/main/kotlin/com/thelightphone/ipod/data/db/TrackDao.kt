package com.thelightphone.ipod.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Minimal projection used to diff the on-disk library against what's cached. */
data class TrackFingerprint(
    val id: Long,
    val path: String,
    val sizeBytes: Long,
    val mtimeMs: Long,
)

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT id, path, sizeBytes, mtimeMs FROM tracks")
    suspend fun fingerprints(): List<TrackFingerprint>

    @Query("SELECT * FROM tracks WHERE path = :path")
    suspend fun getByPath(path: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getById(id: Long): TrackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(track: TrackEntity): Long

    @Query("UPDATE tracks SET missing = 1 WHERE id IN (:ids)")
    suspend fun markMissing(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM tracks WHERE NOT missing")
    suspend fun count(): Int
}
