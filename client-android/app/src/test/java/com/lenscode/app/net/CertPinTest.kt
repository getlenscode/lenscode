package com.lenscode.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException

class CertPinTest {
    @Test
    fun `sha256 of a known input is the published vector, in uppercase hex`() {
        // SHA-256("abc") from FIPS 180-2
        assertEquals(
            "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD",
            CertFingerprint.sha256Hex("abc".toByteArray()),
        )
    }

    @Test
    fun `the short fingerprint matches what the bridge prints`() {
        // Same input and expected output as the bridge's selfSigned.test.ts
        assertEquals("1FC2-33D6-8199-F7D7-6C7E", CertFingerprint.short("1FC233D6819 9F7D76C7EAABBCC".replace(" ", "")))
        assertEquals("1FC2-33D6-8199-F7D7-6C7E", CertFingerprint.short("1fc233d68199f7d76c7eaabbcc"))
    }

    @Test
    fun `a certificate is compared with the pin for its bridge`() {
        assertEquals(PinState.NOT_PINNED, pinState(null, "AB12"))
        assertEquals(PinState.MATCHES, pinState("AB12", "AB12"))
        assertEquals(PinState.MATCHES, pinState("ab12", "AB12"))
        assertEquals(PinState.CHANGED, pinState("AB12", "CD34"))
    }

    @Test
    fun `host and port key a bridge's pin`() {
        assertEquals("192.168.1.157:8443", bridgeHostPort("wss://192.168.1.157:8443"))
        assertEquals("bridge.example.com:443", bridgeHostPort("wss://bridge.example.com"))
        assertEquals("10.0.2.2:8137", bridgeHostPort("ws://10.0.2.2:8137"))
    }

    @Test
    fun `only certificate failures count as a certificate problem`() {
        assertTrue(isCertificateProblem(SSLHandshakeException("bad")))
        assertTrue(isCertificateProblem(RuntimeException("wrapped", CertificateException("x"))))
        assertTrue(isCertificateProblem(RuntimeException(CertPathValidatorException("anchor"))))
        assertFalse(isCertificateProblem(ConnectException("refused")))
        assertFalse(isCertificateProblem(UnknownHostException("nope")))
    }

    @Test
    fun `the prompt carries the short fingerprint and whether the certificate changed`() {
        val prompt = CertPrompt("h:1", "1FC233D68199F7D76C7EAABBCC", changed = true)
        assertEquals("1FC2-33D6-8199-F7D7-6C7E", prompt.short)
        assertTrue(prompt.changed)
    }
}
