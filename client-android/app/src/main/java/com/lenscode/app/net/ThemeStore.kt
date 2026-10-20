package com.lenscode.app.net

import android.content.Context
import com.lenscode.app.ui.ThemeId

private const val PREFS_NAME = "lenscode_theme"
private const val KEY_THEME_ID = "theme_id"

/** Persists the selected app theme (Settings → App Theme) across restarts. */
class ThemeStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): ThemeId {
        val name = prefs.getString(KEY_THEME_ID, null) ?: return ThemeId.TERMINAL
        return runCatching { ThemeId.valueOf(name) }.getOrDefault(ThemeId.TERMINAL)
    }

    fun save(id: ThemeId) {
        prefs.edit().putString(KEY_THEME_ID, id.name).apply()
    }
}
