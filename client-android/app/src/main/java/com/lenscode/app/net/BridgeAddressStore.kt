package com.lenscode.app.net

import android.content.Context

private const val PREFS_NAME = "lenscode_bridge"
private const val KEY_BRIDGE_URL = "bridge_url"

/**
 * The bridge address the user set in Settings. Falls back to the build-time
 * BuildConfig.BRIDGE_URL when unset, so debug builds keep working as before and a
 * release build (which has no useful baked-in address) is configured on the headset.
 */
class BridgeAddressStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): String? = prefs.getString(KEY_BRIDGE_URL, null)

    fun save(url: String) {
        prefs.edit().putString(KEY_BRIDGE_URL, url).apply()
    }
}
