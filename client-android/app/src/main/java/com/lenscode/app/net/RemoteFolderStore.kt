package com.lenscode.app.net

import android.content.Context

private const val PREFS_NAME = "lenscode_remote_folder"
private const val KEY_MACHINE_ID = "machine_id"
private const val KEY_ROOT = "root"
private const val KEY_LABEL = "label"

/** The remote machine + absolute folder Code was last opened on. */
data class SavedRemoteFolder(val machineId: String, val root: String, val label: String)

/**
 * Remembers the last remote folder opened in Code, so the next launch lands there
 * instead of "Select machine" and a fresh folder browse.
 */
class RemoteFolderStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): SavedRemoteFolder? {
        val machineId = prefs.getString(KEY_MACHINE_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val root = prefs.getString(KEY_ROOT, null)?.takeIf { it.isNotBlank() } ?: return null
        return SavedRemoteFolder(machineId, root, prefs.getString(KEY_LABEL, null) ?: root)
    }

    fun save(folder: SavedRemoteFolder) {
        prefs.edit()
            .putString(KEY_MACHINE_ID, folder.machineId)
            .putString(KEY_ROOT, folder.root)
            .putString(KEY_LABEL, folder.label)
            .apply()
    }
}
