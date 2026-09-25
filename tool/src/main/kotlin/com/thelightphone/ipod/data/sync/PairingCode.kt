package com.thelightphone.ipod.data.sync

import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.serialization.Serializable

/**
 * The Mac this phone pulls music from. Established once by scanning the code the
 * Mac app shows (PRD §6) and remembered until the user forgets it.
 *
 * `certSha256` is the hex SHA-256 of the Mac's self-signed TLS certificate (DER).
 * The phone trusts only that certificate, and the Mac trusts only [token], so the
 * two devices "know each other and only each other".
 */
@Serializable
data class MacPairing(
    val host: String,
    val port: Int,
    val token: String,
    val certSha256: String,
    val macName: String,
) {
    val baseUrl: String get() = "https://$host:$port"
}

/**
 * Wire format of the QR code (see docs/mac-loader.protocol.md):
 *
 *     lp3music://pair?v=1&h=<host>&p=<port>&t=<token>&f=<cert sha256 hex>&n=<mac name>
 *
 * Values are `application/x-www-form-urlencoded` (so `+` means space).
 */
object PairingCode {
    const val SCHEME = "lp3music"
    private const val PREFIX = "$SCHEME://pair?"
    private val HEX_SHA256 = Regex("^[0-9a-fA-F]{64}$")

    fun parse(raw: String): Result<MacPairing> = runCatching {
        val trimmed = raw.trim()
        require(trimmed.startsWith(PREFIX, ignoreCase = true)) { "Not a pairing code" }
        val params = trimmed.substring(PREFIX.length)
            .split('&')
            .filter { it.isNotEmpty() }
            .associate { part ->
                val split = part.indexOf('=')
                if (split < 0) decode(part) to "" else decode(part.substring(0, split)) to decode(part.substring(split + 1))
            }

        val version = params["v"]?.toIntOrNull() ?: 1
        require(version == 1) { "Unsupported pairing code version $version" }
        val host = params["h"]?.trim()?.takeIf { it.isNotEmpty() && it.none { c -> c.isWhitespace() || c == '/' } }
            ?: error("Pairing code has no host")
        val port = params["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: error("Pairing code has no valid port")
        val token = params["t"]?.takeIf { it.isNotBlank() } ?: error("Pairing code has no token")
        val fingerprint = params["f"]?.takeIf { HEX_SHA256.matches(it) }?.lowercase()
            ?: error("Pairing code has no certificate fingerprint")
        val name = params["n"]?.trim()?.takeIf { it.isNotEmpty() } ?: "your Mac"
        MacPairing(host = host, port = port, token = token, certSha256 = fingerprint, macName = name)
    }

    fun encode(pairing: MacPairing): String = PREFIX + listOf(
        "v" to "1",
        "h" to pairing.host,
        "p" to pairing.port.toString(),
        "t" to pairing.token,
        "f" to pairing.certSha256,
        "n" to pairing.macName,
    ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

    private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")
}
