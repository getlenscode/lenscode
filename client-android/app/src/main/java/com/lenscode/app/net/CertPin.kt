package com.lenscode.app.net

import java.security.MessageDigest
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

private const val SHORT_FINGERPRINT_HEX_CHARS = 20
private const val SHORT_FINGERPRINT_GROUP = 4

/**
 * Fingerprints of a bridge's TLS certificate (SHA-256 of its DER encoding). The headset shows the
 * short form on first connect; the bridge prints the same string, and the user confirms they
 * match — that comparison is what makes trusting a self-signed certificate safe.
 */
object CertFingerprint {
    /** Uppercase hex, no separators — the form stored as a pin. */
    fun sha256Hex(der: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02X".format(it) }

    /**
     * What a person compares: the first 80 bits as five groups of four, e.g. `1FC2-33D6-8199-F7D7-6C7E`.
     * Must stay identical to `shortFingerprint` in lenscode-bridge/src/selfSigned.ts.
     */
    fun short(hex: String): String =
        hex.uppercase().take(SHORT_FINGERPRINT_HEX_CHARS).chunked(SHORT_FINGERPRINT_GROUP).joinToString("-")
}

/** How the certificate a bridge presented compares with the one pinned for it. */
enum class PinState { MATCHES, NOT_PINNED, CHANGED }

fun pinState(pinned: String?, presented: String): PinState = when {
    pinned == null -> PinState.NOT_PINNED
    pinned.equals(presented, ignoreCase = true) -> PinState.MATCHES
    else -> PinState.CHANGED
}

/** What the trust prompt asks about: a certificate not seen before, or one that differs from the pin. */
data class CertPrompt(val hostPort: String, val fingerprint: String, val changed: Boolean) {
    val short: String get() = CertFingerprint.short(fingerprint)
}

/** Did the connection fail because of the server's certificate (as opposed to being unreachable)? */
fun isCertificateProblem(t: Throwable): Boolean = generateSequence(t) { it.cause }.any {
    it is SSLException || it is CertificateException || it is CertPathValidatorException
}
