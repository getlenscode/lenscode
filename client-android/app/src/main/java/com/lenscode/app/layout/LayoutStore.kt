package com.lenscode.app.layout

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

private const val PREFS_NAME = "lenscode_panel_layout"

data class SavedOffset(val x: Float, val y: Float, val z: Float)
data class SavedSize(val width: Float, val height: Float)

/**
 * Persists panel move/resize across app restarts (spec §52) — plain
 * SharedPreferences keyed by a stable per-panel id, one float triple/pair
 * each. Deliberately not tied to Compose state directly: callers read once
 * at first composition and write on move/resize end, not on every frame.
 */
class LayoutStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadOffset(id: String): SavedOffset? {
        if (!prefs.contains("$id.offset.x")) return null
        return SavedOffset(
            prefs.getFloat("$id.offset.x", 0f),
            prefs.getFloat("$id.offset.y", 0f),
            prefs.getFloat("$id.offset.z", 0f),
        )
    }

    fun saveOffset(id: String, offset: SavedOffset) {
        prefs.edit()
            .putFloat("$id.offset.x", offset.x)
            .putFloat("$id.offset.y", offset.y)
            .putFloat("$id.offset.z", offset.z)
            .apply()
    }

    fun loadSize(id: String): SavedSize? {
        if (!prefs.contains("$id.size.w")) return null
        return SavedSize(prefs.getFloat("$id.size.w", 0f), prefs.getFloat("$id.size.h", 0f))
    }

    fun saveSize(id: String, size: SavedSize) {
        prefs.edit().putFloat("$id.size.w", size.width).putFloat("$id.size.h", size.height).apply()
    }

    /** Pin locks a panel's move/resize handles off (spec §52) — persisted like offset/size. */
    fun loadPinned(id: String): Boolean = prefs.getBoolean("$id.pinned", false)

    fun savePinned(id: String, pinned: Boolean) {
        prefs.edit().putBoolean("$id.pinned", pinned).apply()
    }

    /** Reset-layout action (spec §52) — clears every saved position/size back to defaults. */
    fun resetAll() {
        prefs.edit().clear().apply()
    }
}

@Composable
fun rememberLayoutStore(): LayoutStore {
    val context = LocalContext.current
    return remember { LayoutStore(context) }
}
