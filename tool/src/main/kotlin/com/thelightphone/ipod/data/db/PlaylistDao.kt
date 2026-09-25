package com.thelightphone.ipod.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun get(id: Long): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE name = :name COLLATE NOCASE")
    suspend fun getByName(name: String): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: Long, name: String, updatedAt: Long)

    @Query("UPDATE playlists SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: Long, updatedAt: Long)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    @Query("SELECT * FROM playlist_entries WHERE playlistId = :playlistId ORDER BY position ASC")
    fun observeEntries(playlistId: Long): Flow<List<PlaylistEntryEntity>>

    @Query("SELECT * FROM playlist_entries WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun entries(playlistId: Long): List<PlaylistEntryEntity>

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_entries WHERE playlistId = :playlistId")
    suspend fun maxPosition(playlistId: Long): Int

    @Insert
    suspend fun insertEntry(entry: PlaylistEntryEntity): Long

    @Insert
    suspend fun insertEntries(entries: List<PlaylistEntryEntity>)

    @Query("DELETE FROM playlist_entries WHERE id = :entryId")
    suspend fun deleteEntry(entryId: Long)

    @Query("DELETE FROM playlist_entries WHERE playlistId = :playlistId")
    suspend fun clearEntries(playlistId: Long)

    @Query("UPDATE playlist_entries SET position = :position WHERE id = :entryId")
    suspend fun setPosition(entryId: Long, position: Int)

    @Transaction
    suspend fun addTrack(playlistId: Long, trackId: Long, updatedAt: Long) {
        val nextPosition = maxPosition(playlistId) + 1
        insertEntry(PlaylistEntryEntity(playlistId = playlistId, trackId = trackId, position = nextPosition))
        touch(playlistId, updatedAt)
    }

    @Transaction
    suspend fun removeEntry(playlistId: Long, entryId: Long, updatedAt: Long) {
        deleteEntry(entryId)
        entries(playlistId).forEachIndexed { index, entry -> setPosition(entry.id, index) }
        touch(playlistId, updatedAt)
    }

    /** Reorders [playlistId]'s entries to match [orderedEntryIds] (must contain every existing entry id once). */
    @Transaction
    suspend fun reorder(playlistId: Long, orderedEntryIds: List<Long>, updatedAt: Long) {
        orderedEntryIds.forEachIndexed { index, entryId -> setPosition(entryId, index) }
        touch(playlistId, updatedAt)
    }

    /** Creates a playlist with an initial ordered set of entries in one transaction (used by m3u8 import). */
    @Transaction
    suspend fun createWithEntries(playlist: PlaylistEntity, trackIds: List<Long>): Long {
        val playlistId = insertPlaylist(playlist)
        insertEntries(
            trackIds.mapIndexed { index, trackId ->
                PlaylistEntryEntity(playlistId = playlistId, trackId = trackId, position = index)
            },
        )
        return playlistId
    }
}
