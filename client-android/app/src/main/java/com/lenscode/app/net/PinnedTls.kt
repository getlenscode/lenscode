package com.lenscode.app.net

import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

private const val DEFAULT_WSS_PORT = 443
private const val DEFAULT_WS_PORT = 80
private const val PROBE_TIMEOUT_MS = 5_000

/** `host:port` of a bridge URL — the key its pin is stored under. */
fun bridgeHostPort(wsUrl: String): String {
    val uri = URI(wsUrl)
    val secure = uri.scheme.equals("wss", ignoreCase = true)
    val port = when {
        uri.port >= 0 -> uri.port
        secure -> DEFAULT_WSS_PORT
        else -> DEFAULT_WS_PORT
    }
    return "${uri.host}:$port"
}

/**
 * TLS for the bridge connection. A certificate the system trusts (a public CA, or a user CA in a
 * debug build) is accepted as usual. Otherwise — a bridge running `--secure` with its own
 * certificate — the connection is accepted only if the certificate is exactly the one the user
 * pinned, and never merely because it is self-signed.
 */
object PinnedTls {
    /** @param pinned the fingerprint (hex) pinned for the bridge currently being dialed, or null. */
    fun okHttpClient(pinned: () -> String?): OkHttpClient {
        val base = OkHttpClient()
        val trust = PinnedTrustManager(systemTrustManager(), pinned)
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), null) }
        return base.newBuilder()
            .sslSocketFactory(context.socketFactory, trust)
            .hostnameVerifier(PinnedHostnameVerifier(base.hostnameVerifier, pinned))
            .build()
    }

    /**
     * Reads the certificate a server presents without trusting it: the handshake is started, the
     * certificate is recorded, and then the handshake is deliberately failed, so no application
     * data is ever sent or accepted. Used once, to show the user the fingerprint to confirm.
     */
    fun probeCertificate(host: String, port: Int): ByteArray? {
        val seen = AtomicReference<X509Certificate?>(null)
        val recorder = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                throw CertificateException("client certificates are not used")
            }

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                seen.set(chain.firstOrNull())
                throw CertificateException("certificate recorded for confirmation; connection refused on purpose")
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(recorder), null) }
        try {
            (context.socketFactory.createSocket() as SSLSocket).use { socket ->
                socket.soTimeout = PROBE_TIMEOUT_MS
                socket.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS)
                runCatching { socket.startHandshake() } // fails by design; the certificate is already recorded
            }
        } catch (ignored: IOException) {
            // Unreachable or timed out: nothing was seen, and the caller treats that as "no certificate".
        }
        return seen.get()?.encoded
    }

    private fun systemTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }
}

/**
 * System trust first; failing that, only the exact pinned certificate.
 *
 * It is an X509ExtendedTrustManager on purpose: once a network security config has any
 * `<domain-config>` (debug builds do), Android's own trust manager refuses the plain two-argument
 * check with "Domain specific configurations require that hostname aware checkServerTrusted ... is
 * used", so the socket/engine variants — which carry the host — must be passed through to it.
 */
class PinnedTrustManager(
    private val system: X509TrustManager,
    private val pinned: () -> String?,
) : X509ExtendedTrustManager() {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        system.checkClientTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
        extended()?.checkClientTrusted(chain, authType, socket) ?: system.checkClientTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
        extended()?.checkClientTrusted(chain, authType, engine) ?: system.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
        orPinned(chain) { system.checkServerTrusted(chain, authType) }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
        orPinned(chain) {
            extended()?.checkServerTrusted(chain, authType, socket) ?: system.checkServerTrusted(chain, authType)
        }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
        orPinned(chain) {
            extended()?.checkServerTrusted(chain, authType, engine) ?: system.checkServerTrusted(chain, authType)
        }

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

    private fun extended(): X509ExtendedTrustManager? = system as? X509ExtendedTrustManager

    private inline fun orPinned(chain: Array<X509Certificate>, check: () -> Unit) {
        try {
            check()
        } catch (e: CertificateException) {
            if (!matchesPin(chain.firstOrNull(), pinned())) throw e
        }
    }
}

/** The usual host-name check, or — for the pinned certificate — the pin itself, so a changed IP still connects. */
class PinnedHostnameVerifier(
    private val standard: HostnameVerifier,
    private val pinned: () -> String?,
) : HostnameVerifier {
    override fun verify(hostname: String, session: SSLSession): Boolean =
        standard.verify(hostname, session) ||
            matchesPin(session.peerCertificates.firstOrNull() as? X509Certificate, pinned())
}

/** Is [leaf] exactly the certificate pinned as [pin]? */
private fun matchesPin(leaf: X509Certificate?, pin: String?): Boolean =
    leaf != null && pinState(pin, CertFingerprint.sha256Hex(leaf.encoded)) == PinState.MATCHES
