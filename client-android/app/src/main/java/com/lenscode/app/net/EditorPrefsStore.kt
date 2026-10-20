package com.lenscode.app.net

import android.content.Context
import com.lenscode.app.ui.EditorFontSize

private const val PREFS_NAME = "lenscode_editor"
private const val KEY_FONT_SIZE = "font_size"

/** Persists the code editor font size (set by voice) across restarts. */
class EditorPrefsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadFontSize(): Int =
        prefs.getInt(KEY_FONT_SIZE, EditorFontSize.DEFAULT).coerceIn(EditorFontSize.MIN, EditorFontSize.MAX)

    fun saveFontSize(size: Int) {
        prefs.edit().putInt(KEY_FONT_SIZE, size).apply()
    }
}
