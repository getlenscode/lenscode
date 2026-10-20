package com.lenscode.app.net

import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedTrustManager
import kotlin.concurrent.thread

/**
 * Pinning against a real local TLS server presenting a self-signed certificate made by keytool —
 * the situation of a bridge started with `--secure`.
 */
class PinnedTlsTest {
    private val dir: File = Files.createTempDirectory("lenscode-pinned-tls").toFile()
    private val servers = mutableListOf<TlsTestServer>()

    @After
    fun cleanUp() {
        servers.forEach { it.close() }
        dir.deleteRecursively()
    }

    private class TlsTestServer(keystore: File) : AutoCloseable {
        /** HTTP requests actually answered — stays 0 if every client aborted the handshake. */
        val served = AtomicInteger(0)
        val certificate: X509Certificate
        private val socket: SSLServerSocket

        init {
            val ks = KeyStore.getInstance("PKCS12").apply { keystore.inputStream().use { load(it, PASSWORD) } }
            certificate = ks.getCertificate("t") as X509Certificate
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, PASSWORD)
            val context = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
            val loopback = InetAddress.getByName("127.0.0.1")
            socket = context.serverSocketFactory.createServerSocket(0, 0, loopback) as SSLServerSocket
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    try {
                        (socket.accept() as SSLSocket).use { client ->
                            val input = client.inputStream
                            var tail = 0
                            // Blocks until the handshake completes and a full request arrives.
                            while (tail != END_OF_HEADERS) tail = (tail shl 8 or input.read())
                            client.outputStream.write(RESPONSE.toByteArray())
                            served.incrementAndGet()
                        }
                    } catch (ignored: IOException) {
                        // a client that aborts the handshake, or the socket closing
                    }
                }
            }
        }

        val port: Int get() = socket.localPort
        val fingerprint: String get() = CertFingerprint.sha256Hex(certificate.encoded)
        override fun close() = socket.close()

        companion object {
            val PASSWORD = "changeit".toCharArray()
            const val END_OF_HEADERS = 0x0D0A0D0A // \r\n\r\n
            const val RESPONSE = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
        }
    }

    private fun startServer(san: String): TlsTestServer {
        val file = File(dir, "ks-${servers.size}.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val exit = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "t", "-keyalg", "EC", "-keysize", "256", "-validity", "2",
            "-dname", "CN=pinned-test", "-ext", "san=$san", "-keystore", file.path,
            "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit",
        ).redirectErrorStream(true).start().waitFor()
        assertEquals("keytool failed", 0, exit)
        return TlsTestServer(file).also { servers += it }
    }

    private fun get(port: Int, pin: String?): String {
        val client = PinnedTls.okHttpClient { pin }
        val request = Request.Builder().url("https://127.0.0.1:$port/").build()
        client.newCall(request).execute().use { return it.body!!.string() }
    }

    @Test
    fun `the probe reads the presented certificate and sends no application data`() {
        val server = startServer("ip:127.0.0.1")
        val der = PinnedTls.probeCertificate("127.0.0.1", server.port)
        assertNotNull("a certificate should have been recorded", der)
        assertEquals(server.fingerprint, CertFingerprint.sha256Hex(der!!))
        Thread.sleep(200)
        assertEquals("the probe must not complete a request", 0, server.served.get())
    }

    @Test
    fun `the probe returns nothing for a server that is not there`() {
        val port = startServer("ip:127.0.0.1").also { it.close() }.port
        assertEquals(null, PinnedTls.probeCertificate("127.0.0.1", port))
    }

    @Test
    fun `a self-signed certificate is accepted once its fingerprint is pinned`() {
        val server = startServer("ip:127.0.0.1")
        assertEquals("ok", get(server.port, pin = server.fingerprint))
    }

    @Test
    fun `the pin also covers a host name the certificate was not issued for`() {
        // The bridge's address changes; its certificate does not. Pinned, it must still connect.
        val server = startServer("dns:somewhere-else.test")
        assertEquals("ok", get(server.port, pin = server.fingerprint))
    }

    @Test
    fun `an unpinned self-signed certificate is refused`() {
        val server = startServer("ip:127.0.0.1")
        try {
            get(server.port, pin = null)
            fail("a self-signed certificate must not be trusted just for being presented")
        } catch (e: SSLException) {
            assertTrue(isCertificateProblem(e))
        }
        assertEquals(0, server.served.get())
    }

    @Test
    fun `a different pin is refused`() {
        val server = startServer("ip:127.0.0.1")
        try {
            get(server.port, pin = "00".repeat(32))
            fail("a pin for another certificate must not match")
        } catch (e: SSLException) {
            assertTrue(isCertificateProblem(e))
        }
        assertEquals(0, server.served.get())
    }

    /**
     * Stands in for Android's trust manager in a build whose network security config has a
     * `<domain-config>` (every debug build): the plain two-argument check is refused outright, and
     * only the socket/engine variants — which carry the host — can succeed.
     */
    private class HostnameAwareOnly(private val trusts: Boolean) : X509ExtendedTrustManager() {
        private fun hostAware() {
            if (!trusts) throw CertificateException("not trusted")
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
            throw CertificateException("Domain specific configurations require hostname aware checks")

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
            hostAware()

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
            hostAware()

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) = Unit

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    @Test
    fun `a certificate the system trusts is accepted through the host-aware check`() {
        // The bug this locks in: delegating to the two-argument check made every certificate fail
        // in debug builds, public ones included; only the pin fallback hid it.
        val manager = PinnedTrustManager(HostnameAwareOnly(trusts = true)) { null }
        manager.checkServerTrusted(emptyArray(), "ECDSA", null as Socket?)
        manager.checkServerTrusted(emptyArray(), "ECDSA", null as SSLEngine?)
    }

    @Test
    fun `a certificate the system rejects is accepted only if it is the pinned one`() {
        val server = startServer("ip:127.0.0.1")
        val chain = arrayOf(server.certificate)
        PinnedTrustManager(HostnameAwareOnly(trusts = false)) { server.fingerprint }
            .checkServerTrusted(chain, "ECDSA", null as Socket?)
        for (wrongPin in listOf(null, "00".repeat(32))) {
            try {
                PinnedTrustManager(HostnameAwareOnly(trusts = false)) { wrongPin }
                    .checkServerTrusted(chain, "ECDSA", null as Socket?)
                fail("pin $wrongPin must not be accepted")
            } catch (expected: CertificateException) {
                assertTrue(expected.message!!.isNotEmpty())
            }
        }
    }
}
