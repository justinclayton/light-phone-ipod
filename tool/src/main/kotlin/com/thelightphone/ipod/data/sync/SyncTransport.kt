package com.thelightphone.ipod.data.sync

import java.io.File
import kotlinx.serialization.Serializable

/** One song waiting on the Mac. `path` is where it lands, relative to `music/` (ADR D2). */
@Serializable
data class QueueItem(
    val id: String,
    val path: String,
    val sizeBytes: Long,
)

@Serializable
data class QueueResponse(
    val items: List<QueueItem> = emptyList(),
)

/** Why a call to the Mac failed, in categories the UI can turn into a sentence. */
sealed class SyncTransportException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** No route, refused, timed out, or the connection dropped mid-transfer. */
    class Unreachable(cause: Throwable? = null) : SyncTransportException("Mac unreachable", cause)

    /** The Mac answered but rejected our pairing token (401/403). */
    class Unauthorized : SyncTransportException("Mac rejected the pairing token")

    /** TLS handshake failed: the certificate is not the one we paired with. */
    class WrongMac(cause: Throwable? = null) : SyncTransportException("Certificate does not match the paired Mac", cause)

    /** The Mac answered something we did not expect (bad JSON, 5xx, 404 for a file, ...). */
    class Protocol(message: String, cause: Throwable? = null) : SyncTransportException(message, cause)
}

/** The three calls the phone makes against the Mac. See docs/mac-loader.protocol.md. */
interface SyncTransport {
    suspend fun fetchQueue(pairing: MacPairing): List<QueueItem>

    /** Streams the file body into [destination], creating or overwriting it. */
    suspend fun download(pairing: MacPairing, item: QueueItem, destination: File)

    /** Tells the Mac the item is safely on the phone so its queue can drain. Idempotent. */
    suspend fun acknowledge(pairing: MacPairing, item: QueueItem)
}
