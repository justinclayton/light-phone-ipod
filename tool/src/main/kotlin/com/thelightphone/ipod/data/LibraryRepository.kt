package com.thelightphone.ipod.data

import com.thelightphone.ipod.data.db.PlaylistDao
import com.thelightphone.ipod.data.db.PlaylistEntity
import com.thelightphone.ipod.data.db.TrackDao
import com.thelightphone.ipod.data.db.TrackEntity
import com.thelightphone.ipod.data.m3u.M3uCodec
import com.thelightphone.ipod.data.scan.LibraryScanner
import com.thelightphone.ipod.data.scan.ScanResult
import com.thelightphone.sdk.LightFileShare
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One playlist row for display: the track it points to, or `null` when the file has gone missing. */
data class PlaylistTrackRow(
    val entryId: Long,
    val track: TrackEntity?,
)

private const val PLAYLISTS_DIR = "playlists"

class LibraryRepository(
    private val trackDao: TrackDao,
    private val playlistDao: PlaylistDao,
    private val scanner: LibraryScanner,
    private val fileShare: LightFileShare,
) {
    /** All non-missing tracks, sorted iPod-style (artist, album, disc/track). */
    val songs: Flow<List<TrackEntity>> = trackDao.observeAll().map { tracks ->
        tracks.filter { !it.missing }.sortedWith(
            compareBy(
                { sortKey(it.artist) },
                { sortKey(it.album) },
                { it.discNo ?: Int.MAX_VALUE },
                { it.trackNo ?: Int.MAX_VALUE },
            ),
        )
    }

    val albums: Flow<List<Pair<AlbumKey, List<TrackEntity>>>> = songs.map { tracks ->
        tracks.groupBy(::albumKeyOf)
            .mapValues { (_, albumTracks) -> albumTracks.sortedWith(trackInAlbumComparator) }
            .toList()
            .sortedWith(
                compareByDescending<Pair<AlbumKey, List<TrackEntity>>> { it.first.isCompilation }
                    .then(albumComparator),
            )
    }

    /** iPod rule: an artist who appears only on compilation tracks is not listed. */
    val artists: Flow<List<String>> = songs.map { tracks ->
        val nonCompilationArtists = tracks.filter { !it.isCompilation }
            .map { it.albumArtist ?: it.artist }
            .toSet()
        nonCompilationArtists.sortedBy(::sortKey)
    }

    val playlists: Flow<List<PlaylistEntity>> = playlistDao.observeAll()

    fun albumsByArtist(artist: String): Flow<List<Pair<AlbumKey, List<TrackEntity>>>> = albums.map { all ->
        all.filter { (key, _) -> !key.isCompilation && key.albumArtist == artist }
    }

    fun songsByArtist(artist: String): Flow<List<TrackEntity>> = songs.map { all ->
        all.filter { !it.isCompilation && (it.albumArtist ?: it.artist) == artist }
    }

    fun observePlaylistTracks(playlistId: Long): Flow<List<PlaylistTrackRow>> =
        playlistDao.observeEntries(playlistId).combine(trackDao.observeAll()) { entries, allTracks ->
            val byId = allTracks.associateBy { it.id }
            entries.map { PlaylistTrackRow(it.id, byId[it.trackId]?.takeUnless { t -> t.missing }) }
        }

    private val scanLock = Mutex()

    /** Serialized: the main menu and a finishing sync can both ask at once, and two scans would race on inserts. */
    suspend fun rescan(): ScanResult = scanLock.withLock {
        val result = scanner.scan()
        importM3uPlaylists()
        result
    }

    // ---- Playlist mutations (D3: Room is source of truth, mirrored to m3u8) ----

    suspend fun createPlaylist(name: String): Long {
        val now = Instant.now().toEpochMilli()
        val id = playlistDao.insertPlaylist(PlaylistEntity(name = name, createdAt = now, updatedAt = now))
        exportPlaylist(id)
        return id
    }

    suspend fun renamePlaylist(id: Long, name: String) {
        val previous = playlistDao.get(id) ?: return
        playlistDao.rename(id, name, Instant.now().toEpochMilli())
        fileShare.delete("$PLAYLISTS_DIR/${previous.name}.m3u8")
        exportPlaylist(id)
    }

    suspend fun deletePlaylist(id: Long) {
        val playlist = playlistDao.get(id) ?: return
        playlistDao.deletePlaylist(id)
        fileShare.delete("$PLAYLISTS_DIR/${playlist.name}.m3u8")
    }

    suspend fun addTrackToPlaylist(playlistId: Long, trackId: Long) {
        playlistDao.addTrack(playlistId, trackId, Instant.now().toEpochMilli())
        exportPlaylist(playlistId)
    }

    suspend fun removeEntryFromPlaylist(playlistId: Long, entryId: Long) {
        playlistDao.removeEntry(playlistId, entryId, Instant.now().toEpochMilli())
        exportPlaylist(playlistId)
    }

    suspend fun reorderPlaylist(playlistId: Long, orderedEntryIds: List<Long>) {
        playlistDao.reorder(playlistId, orderedEntryIds, Instant.now().toEpochMilli())
        exportPlaylist(playlistId)
    }

    private suspend fun exportPlaylist(playlistId: Long) {
        val playlist = playlistDao.get(playlistId) ?: return
        val tracks = playlistDao.entries(playlistId)
            .mapNotNull { entry -> trackDao.getById(entry.trackId)?.takeUnless { it.missing } }
        fileShare.write("$PLAYLISTS_DIR/${playlist.name}.m3u8") { it.write(M3uCodec.encode(tracks)) }
    }

    private suspend fun importM3uPlaylists() {
        val fileNames = runCatching { fileShare.list(PLAYLISTS_DIR) }.getOrDefault(emptyList())
        for (fileName in fileNames) {
            if (!fileName.endsWith(".m3u8")) continue
            val name = fileName.removeSuffix(".m3u8")
            if (playlistDao.getByName(name) != null) continue // Room wins conflicts

            val content = fileShare.read("$PLAYLISTS_DIR/$fileName") { it.readText() } ?: continue
            val trackIds = M3uCodec.decodePaths(content).mapNotNull { path -> trackDao.getByPath(path)?.id }
            val now = Instant.now().toEpochMilli()
            playlistDao.createWithEntries(
                PlaylistEntity(name = name, createdAt = now, updatedAt = now),
                trackIds,
            )
        }
    }
}
