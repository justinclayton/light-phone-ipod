package com.thelightphone.ipod.data.sync

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Pulls queued songs from the paired Mac into `music/` (PRD §3: the phone pulls; §8).
 *
 * One instance per process (it owns the in-flight sync). Screens read [state] and call
 * [autoSync] on entry or [syncNow] from the explicit Sync action.
 *
 * Interrupted-transfer policy (PRD §14): a song that was cut off is thrown away and
 * copied again next time; songs already copied stay, so a sync resumes at the queue
 * level, not the byte level.
 */
class SyncEngine(
    private val store: SyncStore,
    private val transport: SyncTransport,
    private val musicRoot: File,
    /** Called after at least one song landed, so the library can pick it up (a rescan). */
    private val onMusicReceived: suspend () -> Unit,
    /** `true`/`false` when known, `null` when the platform can't tell us. Only changes wording. */
    private val isOnWifi: () -> Boolean?,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val autoSyncMinIntervalMs: Long = DEFAULT_AUTO_SYNC_MIN_INTERVAL_MS,
) {
    private sealed interface Phase {
        data object Idle : Phase
        data object Connecting : Phase
        data class Downloading(val done: Int, val total: Int, val currentName: String) : Phase
        data class Done(val received: Int, val failures: List<ItemFailure>) : Phase
        data class Failed(val message: String, val received: Int) : Phase
    }

    private val phase = MutableStateFlow<Phase>(Phase.Idle)
    private val syncLock = Mutex()
    @Volatile private var lastAutoAttemptAt = 0L

    val pairing: StateFlow<MacPairing?> = store.pairing.stateIn(scope, SharingStarted.Eagerly, null)
    val lastSync: StateFlow<LastSync?> = store.lastSync.stateIn(scope, SharingStarted.Eagerly, null)

    val state: StateFlow<SyncState> = combine(pairing, phase) { paired, current ->
        if (paired == null) SyncState.NotPaired else current.toState(paired.macName)
    }.stateIn(scope, SharingStarted.Eagerly, SyncState.NotPaired)

    val isSyncing: Boolean get() = syncLock.isLocked

    suspend fun pair(newPairing: MacPairing) {
        store.savePairing(newPairing)
        store.saveLastSync(null)
        phase.value = Phase.Idle
        lastAutoAttemptAt = 0L
    }

    suspend fun unpair() {
        store.savePairing(null)
        store.saveLastSync(null)
        phase.value = Phase.Idle
    }

    /** The primary path (PRD §8): fires on tool entry, throttled so browsing around doesn't hammer the Mac. */
    fun autoSync() {
        val now = clock()
        if (now - lastAutoAttemptAt < autoSyncMinIntervalMs) return
        lastAutoAttemptAt = now
        scope.launch { sync() }
    }

    /** The explicit fallback action. Always attempts. */
    fun syncNow() {
        lastAutoAttemptAt = clock()
        scope.launch { sync() }
    }

    /** Runs one sync to completion and returns where it ended. No-op if one is already running. */
    suspend fun sync(): SyncState {
        val paired = store.pairing.first() ?: return SyncState.NotPaired
        if (!syncLock.tryLock()) return phase.value.toState(paired.macName)
        try {
            phase.value = runSync(paired)
        } finally {
            syncLock.unlock()
        }
        return phase.value.toState(paired.macName)
    }

    private suspend fun runSync(paired: MacPairing): Phase {
        phase.value = Phase.Connecting
        deletePartials(musicRoot)

        val items = try {
            transport.fetchQueue(paired)
        } catch (e: SyncTransportException) {
            return Phase.Failed(describe(e, paired, received = 0), received = 0)
        }

        var received = 0
        val failures = mutableListOf<ItemFailure>()
        var fatal: String? = null

        for ((index, item) in items.withIndex()) {
            phase.value = Phase.Downloading(index, items.size, LibraryPaths.displayName(item.path))
            val destination = LibraryPaths.resolve(musicRoot, item.path)
            when {
                destination == null -> failures += ItemFailure(item.path, SyncMessages.BAD_NAME)
                !LibraryPaths.isPlayable(item.path) -> failures += ItemFailure(item.path, SyncMessages.UNPLAYABLE)
                else -> {
                    val outcome = receive(paired, item, destination)
                    when (outcome) {
                        is ItemOutcome.Received -> received++
                        is ItemOutcome.Skipped -> failures += ItemFailure(item.path, outcome.reason)
                        is ItemOutcome.Abort -> fatal = describe(outcome.cause, paired, received)
                    }
                }
            }
            if (fatal != null) break
        }

        if (received > 0) {
            runCatching { onMusicReceived() }
        }
        if (fatal == null || received > 0) {
            store.saveLastSync(LastSync(clock(), SyncMessages.summary(received, failures.size)))
        }
        return fatal?.let { Phase.Failed(it, received) } ?: Phase.Done(received, failures)
    }

    private sealed interface ItemOutcome {
        data object Received : ItemOutcome
        data class Skipped(val reason: String) : ItemOutcome
        data class Abort(val cause: Exception) : ItemOutcome
    }

    private suspend fun receive(paired: MacPairing, item: QueueItem, destination: File): ItemOutcome {
        val alreadyHere = destination.isFile && destination.length() == item.sizeBytes
        val partial = File(destination.parentFile, destination.name + LibraryPaths.PART_SUFFIX)
        try {
            if (!alreadyHere) {
                destination.parentFile?.mkdirs()
                transport.download(paired, item, partial)
                if (item.sizeBytes > 0 && partial.length() != item.sizeBytes) {
                    partial.delete()
                    return ItemOutcome.Skipped(SyncMessages.INCOMPLETE)
                }
                if (destination.exists()) destination.delete()
                if (!partial.renameTo(destination)) {
                    partial.delete()
                    return ItemOutcome.Skipped(SyncMessages.NOT_SAVED)
                }
            }
            transport.acknowledge(paired, item)
            return ItemOutcome.Received
        } catch (e: CancellationException) {
            partial.delete()
            throw e
        } catch (e: SyncTransportException.Protocol) {
            partial.delete()
            return ItemOutcome.Skipped(e.message ?: SyncMessages.protocol(paired.macName))
        } catch (e: SyncTransportException) {
            partial.delete()
            return ItemOutcome.Abort(e)
        } catch (e: IOException) {
            partial.delete()
            return if (isOutOfSpace(e)) ItemOutcome.Abort(e) else ItemOutcome.Skipped(SyncMessages.NOT_SAVED)
        }
    }

    private fun describe(e: Exception, paired: MacPairing, received: Int): String = when (e) {
        is SyncTransportException.Unreachable ->
            if (received > 0) SyncMessages.lostConnection(paired.macName, received)
            else SyncMessages.unreachable(paired.macName, runCatching { isOnWifi() }.getOrNull())
        is SyncTransportException.Unauthorized -> SyncMessages.unauthorized(paired.macName)
        is SyncTransportException.WrongMac -> SyncMessages.wrongMac(paired.macName)
        is IOException -> if (isOutOfSpace(e)) SyncMessages.outOfSpace() else SyncMessages.protocol(paired.macName)
        else -> SyncMessages.protocol(paired.macName)
    }

    private fun Phase.toState(macName: String): SyncState = when (this) {
        Phase.Idle -> SyncState.Idle(macName)
        Phase.Connecting -> SyncState.Connecting(macName)
        is Phase.Downloading -> SyncState.Downloading(macName, done, total, currentName)
        is Phase.Done -> SyncState.Done(macName, received, failures)
        is Phase.Failed -> SyncState.Failed(macName, message, received)
    }

    private fun deletePartials(root: File) {
        if (!root.isDirectory) return
        root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(LibraryPaths.PART_SUFFIX) }
            .forEach { it.delete() }
    }

    private fun isOutOfSpace(e: IOException): Boolean {
        val text = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.joinToString(" ")
        return text.contains("ENOSPC") || text.contains("No space left", ignoreCase = true)
    }

    companion object {
        const val DEFAULT_AUTO_SYNC_MIN_INTERVAL_MS = 60_000L
    }
}
