package com.thelightphone.ipod

import com.thelightphone.ipod.data.LibraryRepository
import com.thelightphone.ipod.data.db.AppDatabase
import com.thelightphone.ipod.data.scan.LibraryScanner
import com.thelightphone.ipod.data.scan.MediaMetadataRetrieverTagReader
import com.thelightphone.ipod.data.sync.DataStoreSyncStore
import com.thelightphone.ipod.data.sync.KtorSyncTransport
import com.thelightphone.ipod.data.sync.SyncEngine
import com.thelightphone.ipod.player.PlayerController
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.audio.DefaultLightAudio
import com.thelightphone.sdk.buildDatabase
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Composition root. Room, the detached [PlayerController] and the [SyncEngine] must
 * each exist exactly once per process (D6), so this is a singleton built once from the
 * first screen that asks for it and reused by every screen after.
 */
object AppContainer {
    class Deps(val repository: LibraryRepository, val player: PlayerController, val sync: SyncEngine)

    @Volatile private var deps: Deps? = null

    fun get(lightContext: SealedLightContext, sealedActivity: SealedLightActivity): Deps {
        deps?.let { return it }
        synchronized(this) {
            deps?.let { return it }

            val db = lightContext.buildDatabase(AppDatabase::class.java, AppDatabase.FILE_NAME)
            val musicRoot = File(lightContext.filesDir, "shared/music")
            val scanner = LibraryScanner(musicRoot, MediaMetadataRetrieverTagReader(), db.trackDao())
            val repository = LibraryRepository(db.trackDao(), db.playlistDao(), scanner, lightContext.fileShare)
            val player = PlayerController(
                audio = DefaultLightAudio(sealedActivity),
                musicRoot = musicRoot,
                dataStore = lightContext.dataStore,
                resolveTracks = { ids -> ids.mapNotNull { db.trackDao().getById(it) } },
            )

            val sync = SyncEngine(
                store = DataStoreSyncStore(lightContext.dataStore),
                transport = KtorSyncTransport(),
                musicRoot = musicRoot,
                onMusicReceived = { repository.rescan() },
                isOnWifi = { runCatching { lightContext.connectivity.currentStatus.isWifi }.getOrNull() },
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            )

            return Deps(repository, player, sync).also { deps = it }
        }
    }
}
