package com.thelightphone.ipod.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tracks",
    indices = [Index(value = ["path"], unique = true)],
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val sizeBytes: Long,
    val mtimeMs: Long,
    val title: String,
    val artist: String,
    val albumArtist: String?,
    val album: String,
    val discNo: Int?,
    val trackNo: Int?,
    val year: Int?,
    val genre: String?,
    val durationMs: Long,
    val isCompilation: Boolean,
    /** Set when the file behind [path] is no longer found on disk; kept (not deleted) so playlists can still reference it. */
    val missing: Boolean = false,
)

@Entity(
    tableName = "playlists",
    indices = [Index(value = ["name"], unique = true)],
)
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "playlist_entries",
    indices = [Index(value = ["playlistId"]), Index(value = ["trackId"])],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PlaylistEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val trackId: Long,
    val position: Int,
)
