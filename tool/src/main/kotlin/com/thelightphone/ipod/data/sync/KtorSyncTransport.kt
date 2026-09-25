package com.thelightphone.ipod.data.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * HTTPS client for the Mac's local server (docs/mac-loader.protocol.md).
 *
 * The Mac's certificate is self-signed, so trust is not a CA: it is the SHA-256
 * fingerprint carried in the pairing code. A client is built per pairing so the
 * pinned certificate travels with it.
 */
class KtorSyncTransport(
    private val connectTimeoutSeconds: Long = 4,
    private val readTimeoutSeconds: Long = 30,
) : SyncTransport {
    private val json = Json { ignoreUnknownKeys = true }

    private var cached: Pair<String, HttpClient>? = null

    override suspend fun fetchQueue(pairing: MacPairing): List<QueueItem> = call {
        val response = clientFor(pairing).get("${pairing.baseUrl}/v1/queue") { authorize(pairing) }
        requireSuccess(response)
        json.decodeFromString<QueueResponse>(response.bodyAsText()).items
    }

    override suspend fun download(pairing: MacPairing, item: QueueItem, destination: File) = call {
        clientFor(pairing).prepareGet("${pairing.baseUrl}/v1/files/${encode(item.id)}") { authorize(pairing) }
            .execute { response ->
                if (response.status == HttpStatusCode.NotFound) {
                    throw SyncTransportException.Protocol(SyncMessages.GONE_FROM_MAC)
                }
                requireSuccess(response)
                val channel = response.bodyAsChannel()
                withContext(Dispatchers.IO) {
                    destination.outputStream().use { out -> channel.toInputStream().copyTo(out) }
                }
                Unit
            }
    }

    override suspend fun acknowledge(pairing: MacPairing, item: QueueItem) = call {
        val response = clientFor(pairing).post("${pairing.baseUrl}/v1/ack/${encode(item.id)}") { authorize(pairing) }
        requireSuccess(response)
    }

    private fun HttpRequestBuilder.authorize(pairing: MacPairing) {
        header(HttpHeaders.Authorization, "Bearer ${pairing.token}")
    }

    private fun requireSuccess(response: HttpResponse) {
        when {
            response.status.isSuccess() -> Unit
            response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden ->
                throw SyncTransportException.Unauthorized()
            else -> throw SyncTransportException.Protocol("Mac answered HTTP ${response.status.value}")
        }
    }

    /** Translates everything ktor/OkHttp can throw into the four categories the engine understands. */
    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: SyncTransportException) {
        throw e
    } catch (e: SSLException) {
        throw SyncTransportException.WrongMac(e)
    } catch (e: IOException) {
        throw SyncTransportException.Unreachable(e)
    } catch (e: SerializationException) {
        throw SyncTransportException.Protocol("Mac sent something unexpected", e)
    } catch (e: Exception) {
        throw SyncTransportException.Protocol(e.message ?: "Unexpected error", e)
    }

    @Synchronized
    private fun clientFor(pairing: MacPairing): HttpClient {
        cached?.let { (fingerprint, client) -> if (fingerprint == pairing.certSha256) return client }
        cached?.second?.close()
        val trustManager = PinnedCertificateTrustManager(pairing.certSha256)
        val sslContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
        val client = HttpClient(OkHttp) {
            expectSuccess = false
            engine {
                config {
                    connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
                    readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                    writeTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                    sslSocketFactory(sslContext.socketFactory, trustManager)
                    // Identity is the pinned certificate, not the (LAN IP) hostname.
                    hostnameVerifier(HostnameVerifier { _, _ -> true })
                }
            }
        }
        cached = pairing.certSha256 to client
        return client
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}

/** Accepts exactly one server certificate: the one whose DER SHA-256 matches the pairing code. */
class PinnedCertificateTrustManager(expectedSha256Hex: String) : X509TrustManager {
    private val expected = expectedSha256Hex.lowercase()

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("Empty certificate chain")
        if (sha256Hex(leaf.encoded) != expected) {
            throw CertificateException("Server certificate does not match the paired Mac")
        }
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        throw CertificateException("Client certificates are not used")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    companion object {
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
