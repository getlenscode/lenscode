package com.lenscode.app.net

import com.lenscode.app.voice.normalizeSpokenAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class BridgeAddressTest {

    private fun ok(input: String, secureOnly: Boolean = false) =
        (normalizeBridgeAddress(input, secureOnly) as BridgeAddressResult.Ok).url

    private fun invalid(input: String, secureOnly: Boolean = false) =
        (normalizeBridgeAddress(input, secureOnly) as BridgeAddressResult.Invalid).message

    // ── debug builds: plain ws:// is fine ────────────────────────────────

    @Test
    fun `a bare ip gets the default port and ws`() {
        assertEquals("ws://192.168.1.157:8137", ok("192.168.1.157"))
        assertEquals("ws://192.168.1.157:8137", ok("  192.168.1.157  "))
    }

    @Test
    fun `the default port depends on whether the address is secure`() {
        assertEquals("ws://192.168.1.157:8137", ok("192.168.1.157"))
        assertEquals("wss://192.168.1.157:8443", ok("wss://192.168.1.157"))
        assertEquals("ws://192.168.1.157:8137", ok("ws://192.168.1.157"))
        assertEquals("wss://192.168.1.157:9000", ok("wss://192.168.1.157:9000"))
    }

    @Test
    fun `an explicit port and scheme are kept`() {
        assertEquals("ws://192.168.1.157:9000", ok("192.168.1.157:9000"))
        assertEquals("ws://host.local:8137", ok("ws://host.local:8137/"))
        assertEquals("ws://10.0.2.2:8137", ok("http://10.0.2.2"))
    }

    @Test
    fun `https and wss map to wss`() {
        assertEquals("wss://bridge.example.com:8443", ok("https://bridge.example.com"))
        assertEquals("wss://bridge.example.com:443", ok("wss://bridge.example.com:443"))
    }

    @Test
    fun `a dictated address works end to end`() {
        val spoken = normalizeSpokenAddress("one nine two dot one six eight dot one dot one five seven")
        assertEquals("ws://192.168.1.157:8137", ok(spoken))
        assertEquals(
            "ws://192.168.1.157:3000",
            ok(
                normalizeSpokenAddress(
                    "one nine two dot one six eight dot one dot one five seven colon three zero zero zero",
                ),
            ),
        )
    }

    // ── release builds: only wss:// can work ─────────────────────────────

    @Test
    fun `secure-only builds default to wss and refuse ws`() {
        assertEquals("wss://192.168.1.157:8443", ok("192.168.1.157", secureOnly = true))
        assertEquals("wss://host:8443", ok("https://host", secureOnly = true))
        assertTrue(invalid("ws://192.168.1.157", secureOnly = true).contains("secure"))
        // with no port typed, the suggestion is the secure form on the secure port
        assertTrue(invalid("ws://192.168.1.157", secureOnly = true).contains("wss://192.168.1.157:8443"))
        // an explicit port is kept in the suggestion
        assertTrue(invalid("http://host:9000", secureOnly = true).contains("wss://host:9000"))
    }

    // ── rejects ──────────────────────────────────────────────────────────

    @Test
    fun `empty and malformed input is explained, not accepted`() {
        assertTrue(invalid("").contains("Enter"))
        assertTrue(invalid("   ").contains("Enter"))
        assertTrue(invalid("not a host").contains("doesn't look like"))
        assertTrue(invalid("ftp://host").contains("ws://"))
        assertTrue(invalid("bad_host").contains("characters"))
    }

    @Test
    fun `ports outside 1 to 65535 are rejected`() {
        assertTrue(invalid("192.168.1.157:0").contains("port"))
        assertTrue(invalid("192.168.1.157:99999").contains("port"))
    }

    // ── connection errors in words ───────────────────────────────────────

    @Test
    fun `connect failures are described for a person`() {
        assertTrue(describeConnectError(UnknownHostException("nope")).contains("Can't find"))
        assertTrue(describeConnectError(ConnectException("refused")).contains("Is the bridge running"))
        assertTrue(describeConnectError(SocketTimeoutException()).contains("Is the bridge running"))
        assertTrue(describeConnectError(SSLHandshakeException("bad cert")).contains("certificate"))
        val cleartext = IOException("CLEARTEXT communication to 1.2.3.4 not permitted by network security policy")
        assertTrue(describeConnectError(cleartext).contains("wss://"))
    }

    @Test
    fun `wss to a plain-text port says the port is not secure, not that a certificate is untrusted`() {
        // What Android reports when it speaks TLS to a server that answers in plain text.
        val error = javax.net.ssl.SSLException("Unable to parse TLS packet header")
        val message = describeConnectError(error)
        assertTrue(message, message.contains("isn't secure"))
        assertTrue(message.contains("--secure"))
    }

    @Test
    fun `a nested cause is still recognised`() {
        assertTrue(describeConnectError(RuntimeException("wrapped", UnknownHostException("x"))).contains("Can't find"))
        assertEquals("Couldn't connect to the bridge.", describeConnectError(IllegalStateException("?")))
    }
}
