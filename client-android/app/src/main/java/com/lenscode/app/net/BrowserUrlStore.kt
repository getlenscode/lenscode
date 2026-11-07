package com.lenscode.app.net

import android.content.Context

private const val PREFS_NAME = "lenscode_browser"
private const val KEY_LAST_URL = "last_url"
private const val KEY_HOST = "last_host"

/**
 * Remembers the last URL the Browser dock button launched — almost always a
 * dev server on the same LAN as lenscode-bridge (spec: no embedded web preview,
 * see docs/remote-setup-and-limits.md's "Web preview" row), so re-opening it
 * after an agent's next change shouldn't require retyping the address.
 */
class BrowserUrlStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): String? = prefs.getString(KEY_LAST_URL, null)

    /** The machine the dropdown last picked ("local" or a remote's id). */
    fun loadHost(): String = prefs.getString(KEY_HOST, null) ?: "local"

    fun saveHost(id: String) {
        prefs.edit().putString(KEY_HOST, id).apply()
    }

    fun save(url: String) {
        prefs.edit().putString(KEY_LAST_URL, url).apply()
    }
}
