package dev.aura.xr.net

import android.content.Context

private const val PREFS_NAME = "aura_browser"
private const val KEY_LAST_URL = "last_url"

/**
 * Remembers the last URL the Browser dock button launched — almost always a
 * dev server on the same LAN as aura-bridge (spec: no embedded web preview,
 * see docs/remote-setup-and-limits.md's "Web preview" row), so re-opening it
 * after an agent's next change shouldn't require retyping the address.
 */
class BrowserUrlStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): String? = prefs.getString(KEY_LAST_URL, null)

    fun save(url: String) {
        prefs.edit().putString(KEY_LAST_URL, url).apply()
    }
}
