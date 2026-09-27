package com.thelightphone.ipod.data.sync

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

/**
 * Talks to a *running* Mac app with the phone's real transport. Skipped unless
 * the pairing code shown by the Mac is passed in:
 *
 *     ./gradlew :tool:testDebugUnitTest --tests '*LiveMacContractTest*' -Dmac.pairing='lp3music://pair?...'
 *
 * Downloads every queued file into a temp folder but does NOT acknowledge, so the
 * Mac's queue is left as it was.
 */
class LiveMacContractTest {
    @Test
    fun queueAndFilesComeAcrossThePinnedConnection() = runBlocking {
        val code = System.getProperty("mac.pairing") ?: System.getenv("MAC_PAIRING")
        assumeTrue("no -Dmac.pairing given; skipping live Mac test", !code.isNullOrBlank())
        val pairing = PairingCode.parse(code!!).getOrThrow()
        val transport = KtorSyncTransport()

        val items = transport.fetchQueue(pairing)
        println("Mac '${pairing.macName}' at ${pairing.baseUrl} has ${items.size} item(s) queued")
        val dir = createTempDirectory("ipod-live").toFile()
        for (item in items) {
            LibraryPaths.resolve(dir, item.path) ?: error("Mac produced a path the phone would refuse: ${item.path}")
            assertTrue(LibraryPaths.isPlayable(item.path), "Mac queued an unplayable file: ${item.path}")
            val dest = File(dir, item.id + ".part")
            transport.download(pairing, item, dest)
            assertEquals(item.sizeBytes, dest.length(), "size mismatch for ${item.path}")
            println("  ok ${item.path} (${item.sizeBytes} bytes)")
        }
        assertTrue(items.isNotEmpty(), "Expected the Mac to have something queued for this test")
    }
}
