package com.lenscode.app.net

import android.content.Context

private const val PREFS_NAME = "lenscode_pins"

/**
 * Certificates the user has confirmed, one per bridge (`host:port`). Pinned to the certificate
 * itself, so the bridge's IP changing — it often does — doesn't lose the trust.
 */
class PinStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun pinFor(hostPort: String): String? = prefs.getString(hostPort, null)

    fun save(hostPort: String, fingerprintHex: String) {
        prefs.edit().putString(hostPort, fingerprintHex).apply()
    }
}
