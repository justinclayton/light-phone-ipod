package com.thelightphone.ipod.data.sync

import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

private val MAC = MacPairing("192.168.1.2", 48123, "tok", "a".repeat(64), "Justin's MacBook")

private class InMemorySyncStore(initial: MacPairing? = MAC) : SyncStore {
    override val pairing = MutableStateFlow(initial)
    override val lastSync = MutableStateFlow<LastSync?>(null)
    override suspend fun savePairing(pairing: MacPairing?) { this.pairing.value = pairing }
    override suspend fun saveLastSync(lastSync: LastSync?) { this.lastSync.value = lastSync }
}

/** A Mac in a box: a queue of (item, bytes), plus knobs to make each call fail. */
private class FakeMac : SyncTransport {
    val queue = linkedMapOf<QueueItem, ByteArray>()
    val acked = mutableListOf<String>()
    var queueError: SyncTransportException? = null
    var downloadErrors = mutableMapOf<String, Exception>()
    var downloadCount = 0
    /** When set, downloads write only this many bytes (simulates a cut-off transfer). */
    var truncateTo: Int? = null

    fun add(path: String, bytes: ByteArray, id: String = path): QueueItem =
        QueueItem(id, path, bytes.size.toLong()).also { queue[it] = bytes }

    override suspend fun fetchQueue(pairing: MacPairing): List<QueueItem> {
        queueError?.let { throw it }
        return queue.keys.toList()
    }

    override suspend fun download(pairing: MacPairing, item: QueueItem, destination: File) {
        downloadCount++
        downloadErrors[item.id]?.let { throw it }
        val bytes = queue.getValue(item)
        destination.writeBytes(truncateTo?.let { bytes.copyOf(it) } ?: bytes)
    }

    override suspend fun acknowledge(pairing: MacPairing, item: QueueItem) {
        acked += item.id
    }
}

class SyncEngineTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val root = createTempDirectory("ipod-sync").toFile()
    private val mac = FakeMac()
    private val store = InMemorySyncStore()
    private var rescans = 0
    private var wifi: Boolean? = true
    private var now = 1_000_000L

    private fun engine(store: SyncStore = this.store) = SyncEngine(
        store = store,
        transport = mac,
        musicRoot = root,
        onMusicReceived = { rescans++ },
        isOnWifi = { wifi },
        scope = scope,
        clock = { now },
    )

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun downloadsEverythingQueuedIntoMusicAndRescans() = runBlocking {
        mac.add("Artist/Album/01 One.mp3", byteArrayOf(1, 2, 3))
        mac.add("Artist/Album/02 Two.mp3", byteArrayOf(4, 5))

        val state = engine().sync()

        assertEquals(SyncState.Done(MAC.macName, received = 2, failures = emptyList()), state)
        assertEquals(listOf(1, 2, 3).map(Int::toByte), File(root, "Artist/Album/01 One.mp3").readBytes().toList())
        assertEquals(listOf("Artist/Album/01 One.mp3", "Artist/Album/02 Two.mp3"), mac.acked)
        assertEquals(1, rescans)
        assertEquals("2 songs added", store.lastSync.value?.summary)
        assertTrue(root.walkTopDown().none { it.name.endsWith(".part") })
    }

    @Test
    fun emptyQueueIsNothingNewAndDoesNotRescan() = runBlocking {
        val state = engine().sync()

        assertEquals(SyncState.Done(MAC.macName, 0, emptyList()), state)
        assertEquals(0, rescans)
        assertEquals("Nothing new on your Mac", store.lastSync.value?.summary)
    }

    @Test
    fun notPairedDoesNothing() = runBlocking {
        mac.add("song.mp3", byteArrayOf(1))
        val state = engine(InMemorySyncStore(initial = null)).sync()
        assertEquals(SyncState.NotPaired, state)
        assertEquals(0, mac.downloadCount)
    }

    @Test
    fun unreachableMacIsExplainedInTermsOfWifi() = runBlocking {
        mac.queueError = SyncTransportException.Unreachable()

        wifi = false
        val offWifi = engine().sync()
        assertIs<SyncState.Failed>(offWifi)
        assertEquals(SyncMessages.unreachable(MAC.macName, false), offWifi.message)
        assertTrue(offWifi.message.contains("Wi-Fi"))

        wifi = true
        val onWifi = engine().sync()
        assertIs<SyncState.Failed>(onWifi)
        assertEquals(SyncMessages.unreachable(MAC.macName, true), onWifi.message)
        assertTrue(onWifi.message.contains("music app is open"))
    }

    @Test
    fun rejectedTokenTellsHerToScanAgain() = runBlocking {
        mac.queueError = SyncTransportException.Unauthorized()
        val state = engine().sync()
        assertIs<SyncState.Failed>(state)
        assertEquals(SyncMessages.unauthorized(MAC.macName), state.message)
    }

    @Test
    fun unsafeAndUnplayableItemsAreSkippedWithReasonsAndNotAcked() = runBlocking {
        mac.add("../escape.mp3", byteArrayOf(1))
        mac.add("Artist/video.mp4", byteArrayOf(1))
        mac.add("Artist/ok.mp3", byteArrayOf(1))

        val state = engine().sync()

        assertIs<SyncState.Done>(state)
        assertEquals(1, state.received)
        assertEquals(
            listOf(
                ItemFailure("../escape.mp3", SyncMessages.BAD_NAME),
                ItemFailure("Artist/video.mp4", SyncMessages.UNPLAYABLE),
            ),
            state.failures,
        )
        assertEquals(listOf("Artist/ok.mp3"), mac.acked)
        assertFalse(File(root.parentFile, "escape.mp3").exists())
        assertEquals("1 song added, 2 couldn't be copied", store.lastSync.value?.summary)
    }

    @Test
    fun cutOffTransferIsDiscardedAndReportedNotAcked() = runBlocking {
        mac.add("song.mp3", byteArrayOf(1, 2, 3, 4))
        mac.truncateTo = 2

        val state = engine().sync()

        assertIs<SyncState.Done>(state)
        assertEquals(listOf(ItemFailure("song.mp3", SyncMessages.INCOMPLETE)), state.failures)
        assertTrue(mac.acked.isEmpty())
        assertFalse(File(root, "song.mp3").exists())
        assertFalse(File(root, "song.mp3.part").exists())
        assertEquals(0, rescans)
    }

    @Test
    fun losingTheMacMidwayKeepsWhatArrivedAndSaysSo() = runBlocking {
        mac.add("one.mp3", byteArrayOf(1))
        mac.add("two.mp3", byteArrayOf(2))
        mac.add("three.mp3", byteArrayOf(3))
        mac.downloadErrors["two.mp3"] = SyncTransportException.Unreachable()

        val state = engine().sync()

        assertIs<SyncState.Failed>(state)
        assertEquals(1, state.receivedBeforeFailure)
        assertEquals(SyncMessages.lostConnection(MAC.macName, 1), state.message)
        assertTrue(File(root, "one.mp3").exists())
        assertFalse(File(root, "three.mp3").exists()) // stopped, did not keep hammering
        assertEquals(1, rescans) // the one that arrived is in the library
    }

    @Test
    fun aSongAlreadyOnThePhoneIsAckedWithoutRedownloading() = runBlocking {
        val bytes = byteArrayOf(9, 9, 9)
        File(root, "dup.mp3").writeBytes(bytes)
        mac.add("dup.mp3", bytes)

        val state = engine().sync()

        assertEquals(SyncState.Done(MAC.macName, 1, emptyList()), state)
        assertEquals(0, mac.downloadCount)
        assertEquals(listOf("dup.mp3"), mac.acked)
    }

    @Test
    fun outOfSpaceAbortsWithAPlainSentence() = runBlocking {
        mac.add("big.mp3", byteArrayOf(1))
        mac.downloadErrors["big.mp3"] = IOException("write failed: ENOSPC (No space left on device)")

        val state = engine().sync()

        assertIs<SyncState.Failed>(state)
        assertEquals(SyncMessages.outOfSpace(), state.message)
    }

    @Test
    fun stalePartialFilesAreCleanedUpBeforeSyncing() = runBlocking {
        File(root, "Artist").mkdirs()
        File(root, "Artist/old.mp3.part").writeBytes(byteArrayOf(1))

        engine().sync()

        assertFalse(File(root, "Artist/old.mp3.part").exists())
    }

    @Test
    fun autoSyncIsThrottledButSyncNowIsNot() = runBlocking {
        val e = engine()
        e.autoSync(); awaitIdle(e)
        e.autoSync(); awaitIdle(e) // within the interval: skipped
        assertEquals(0, mac.downloadCount)
        mac.add("song.mp3", byteArrayOf(1))
        e.autoSync(); awaitIdle(e)
        assertEquals(0, mac.downloadCount)

        now += SyncEngine.DEFAULT_AUTO_SYNC_MIN_INTERVAL_MS
        e.autoSync(); awaitIdle(e)
        assertEquals(1, mac.downloadCount)

        mac.add("other.mp3", byteArrayOf(2))
        e.syncNow(); awaitIdle(e)
        assertEquals(2, mac.downloadCount)
    }

    @Test
    fun pairingAndForgettingUpdateTheStore() = runBlocking {
        val fresh = InMemorySyncStore(initial = null)
        val e = engine(fresh)
        e.pair(MAC)
        assertEquals(MAC, fresh.pairing.value)
        e.unpair()
        assertEquals(null, fresh.pairing.value)
        assertEquals(null, fresh.lastSync.value)
    }

    private suspend fun awaitIdle(e: SyncEngine) {
        // autoSync launches into the engine's scope; give it a moment then wait for the lock to clear.
        kotlinx.coroutines.delay(50)
        while (e.isSyncing) kotlinx.coroutines.delay(10)
    }
}
