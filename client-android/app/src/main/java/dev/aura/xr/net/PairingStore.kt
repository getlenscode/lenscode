package dev.aura.xr.net

import android.content.Context

private const val PREFS_NAME = "aura_pairing"
private const val KEY_DEVICE_TOKEN = "device_token"

/**
 * Persists the device token issued once by `pair.complete` (spec §55) so the
 * app doesn't re-pair on every launch. Plain SharedPreferences is good enough
 * here: this is the same trust boundary as the rest of the app's on-device
 * storage, and the bridge itself never re-sends the secret after issuing it.
 */
class PairingStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadToken(): String? = prefs.getString(KEY_DEVICE_TOKEN, null)

    fun saveToken(token: String) {
        prefs.edit().putString(KEY_DEVICE_TOKEN, token).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_DEVICE_TOKEN).apply()
    }
}
