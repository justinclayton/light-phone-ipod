package com.thelightphone.ipod.data.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val FINGERPRINT = "ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12"

class PairingCodeTest {
    @Test
    fun parsesAFullCode() {
        val result = PairingCode.parse(
            "lp3music://pair?v=1&h=192.168.1.20&p=48123&t=s3cret&f=${FINGERPRINT.uppercase()}&n=Justin%27s+MacBook",
        )

        val pairing = result.getOrThrow()
        assertEquals("192.168.1.20", pairing.host)
        assertEquals(48123, pairing.port)
        assertEquals("s3cret", pairing.token)
        assertEquals(FINGERPRINT, pairing.certSha256) // normalized to lowercase
        assertEquals("Justin's MacBook", pairing.macName)
        assertEquals("https://192.168.1.20:48123", pairing.baseUrl)
    }

    @Test
    fun encodeRoundTrips() {
        val original = MacPairing("10.0.0.5", 443, "tok/en+with=chars", FINGERPRINT, "Living Room Mac")
        assertEquals(original, PairingCode.parse(PairingCode.encode(original)).getOrThrow())
    }

    @Test
    fun rejectsForeignAndIncompleteCodes() {
        listOf(
            "https://example.com/not-ours",
            "otpauth://totp/x?secret=abc",
            "lp3music://pair?h=1.2.3.4&p=80&t=x", // no fingerprint
            "lp3music://pair?h=1.2.3.4&p=80&f=$FINGERPRINT", // no token
            "lp3music://pair?h=1.2.3.4&p=99999&t=x&f=$FINGERPRINT", // bad port
            "lp3music://pair?v=2&h=1.2.3.4&p=80&t=x&f=$FINGERPRINT", // unknown version
            "lp3music://pair?h=1.2.3.4&p=80&t=x&f=notahash",
            "",
        ).forEach { raw ->
            assertTrue(PairingCode.parse(raw).isFailure, "should reject: $raw")
        }
    }

    @Test
    fun missingNameFallsBackToGenericLabel() {
        val pairing = PairingCode.parse("lp3music://pair?h=1.2.3.4&p=80&t=x&f=$FINGERPRINT").getOrThrow()
        assertEquals("your Mac", pairing.macName)
    }
}
