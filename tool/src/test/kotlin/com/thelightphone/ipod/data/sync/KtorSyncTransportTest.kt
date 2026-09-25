package com.thelightphone.ipod.data.sync

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Drives the real ktor/OkHttp transport against a local HTTPS server with a
 * self-signed certificate, the way the Mac app will present itself. Uses the
 * JDK's `keytool` to mint the certificate, so it needs a JDK, not just a JRE.
 */
class KtorSyncTransportTest {
    private lateinit var mac: TinyHttpsMac
    private lateinit var pairing: MacPairing
    private val fileBytes = ByteArray(200_000) { (it % 251).toByte() }

    @BeforeTest
    fun startMac() {
        val dir = createTempDirectory("ipod-tls").toFile()
        val keystore = File(dir, "mac.p12")
        val password = "changeit"
        val keytool = File(System.getProperty("java.home"), "bin/keytool")
        val process = ProcessBuilder(
            keytool.path, "-genkeypair", "-alias", "mac", "-keyalg", "RSA", "-keysize", "2048",
            "-storetype", "PKCS12", "-keystore", keystore.path, "-storepass", password,
            "-dname", "CN=Loader", "-validity", "2", "-ext", "SAN=ip:127.0.0.1",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), "keytool failed: $output")

        val ks = KeyStore.getInstance("PKCS12").apply { keystore.inputStream().use { load(it, password.toCharArray()) } }
        val cert = ks.getCertificate("mac") as X509Certificate
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, password.toCharArray()) }
        val sslContext = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }

        mac = TinyHttpsMac(sslContext, token = "good-token", file = "abc" to fileBytes).also { it.start() }

        pairing = MacPairing(
            host = "127.0.0.1",
            port = mac.port,
            token = "good-token",
            certSha256 = PinnedCertificateTrustManager.sha256Hex(cert.encoded),
            macName = "Test Mac",
        )
    }

    @AfterTest
    fun stopMac() = mac.stop()

    @Test
    fun fullRoundTripOverPinnedTls() = runBlocking {
        val transport = KtorSyncTransport()
        val items = transport.fetchQueue(pairing)
        assertEquals(listOf(QueueItem("abc", "A/B/song.mp3", fileBytes.size.toLong())), items)

        val dest = File(createTempDirectory("ipod-dl").toFile(), "song.part")
        transport.download(pairing, items.single(), dest)
        assertTrue(dest.readBytes().contentEquals(fileBytes))

        transport.acknowledge(pairing, items.single())
        assertEquals(listOf("abc"), mac.acked)
    }

    @Test
    fun wrongTokenIsUnauthorized() = runBlocking {
        assertFailsWith<SyncTransportException.Unauthorized> {
            KtorSyncTransport().fetchQueue(pairing.copy(token = "stale"))
        }
        Unit
    }

    @Test
    fun differentCertificateIsRefusedAsWrongMac() = runBlocking {
        assertFailsWith<SyncTransportException.WrongMac> {
            KtorSyncTransport().fetchQueue(pairing.copy(certSha256 = "0".repeat(64)))
        }
        Unit
    }

    @Test
    fun closedPortIsUnreachable() = runBlocking {
        val closed = ServerSocket(0).use { it.localPort }
        assertFailsWith<SyncTransportException.Unreachable> {
            KtorSyncTransport(connectTimeoutSeconds = 2).fetchQueue(pairing.copy(port = closed))
        }
        Unit
    }

    @Test
    fun missingFileIsReportedAsGoneFromMac() = runBlocking {
        val e = assertFailsWith<SyncTransportException.Protocol> {
            KtorSyncTransport().download(pairing, QueueItem("nope", "x.mp3", 1), File.createTempFile("ipod", ".part"))
        }
        assertEquals(SyncMessages.GONE_FROM_MAC, e.message)
    }
}

/** Just enough HTTP/1.1 over TLS to play the Mac side of docs/mac-loader.protocol.md. */
private class TinyHttpsMac(
    sslContext: SSLContext,
    private val token: String,
    private val file: Pair<String, ByteArray>,
) {
    private val serverSocket = (sslContext.serverSocketFactory.createServerSocket(0) as SSLServerSocket)
    private val pool = Executors.newCachedThreadPool()
    val acked = mutableListOf<String>()
    val port: Int get() = serverSocket.localPort

    fun start() {
        pool.execute {
            while (!serverSocket.isClosed) {
                val socket = runCatching { serverSocket.accept() }.getOrNull() ?: break
                pool.execute { runCatching { handle(socket) } }
            }
        }
    }

    fun stop() {
        serverSocket.close()
        pool.shutdownNow()
    }

    private fun handle(socket: Socket) = socket.use { s ->
        val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
        val out = s.getOutputStream()
        while (true) {
            val requestLine = reader.readLine() ?: return
            val headers = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }
                .associate { line -> line.substringBefore(':').trim().lowercase() to line.substringAfter(':').trim() }
            headers["content-length"]?.toIntOrNull()?.let { length -> repeat(length) { reader.read() } }

            val (method, path) = requestLine.split(' ').let { it[0] to it[1] }
            val (status, body) = when {
                headers["authorization"] != "Bearer $token" -> 401 to ByteArray(0)
                method == "GET" && path == "/v1/queue" ->
                    200 to """{"items":[{"id":"${file.first}","path":"A/B/song.mp3","sizeBytes":${file.second.size},"extra":1}]}""".toByteArray()
                method == "GET" && path == "/v1/files/${file.first}" -> 200 to file.second
                method == "GET" && path.startsWith("/v1/files/") -> 404 to ByteArray(0)
                method == "POST" && path.startsWith("/v1/ack/") -> {
                    synchronized(acked) { acked += path.substringAfterLast('/') }
                    204 to ByteArray(0)
                }
                else -> 404 to ByteArray(0)
            }
            val reason = when (status) { 200 -> "OK"; 204 -> "No Content"; 401 -> "Unauthorized"; else -> "Not Found" }
            val head = buildString {
                append("HTTP/1.1 $status $reason\r\n")
                if (status != 204) append("Content-Length: ${body.size}\r\n")
                append("Connection: keep-alive\r\n\r\n")
            }
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.write(body)
            out.flush()
        }
    }
}
