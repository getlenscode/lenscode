package com.lenscode.app.net

import android.content.Context
import android.content.SharedPreferences

private const val PREFS_NAME = "lenscode_composer"
private const val KEY_PROVIDER = "provider"
private const val KEY_MODEL = "model"
private const val KEY_ATTACH_OPEN_FILES = "attach_open_files"

/** The agent (claude, codex, pi, omp, …) and model every new agent starts with. */
data class AgentChoice(val provider: String, val model: String?)

/**
 * Remembers the agent + model picked in the Code view (by tap or by voice) so the next launch, and
 * every new agent after it, starts with the same choice.
 */
class ComposerPrefsStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    fun load(): AgentChoice? {
        val provider = prefs.getString(KEY_PROVIDER, null)?.takeIf { it.isNotBlank() } ?: return null
        return AgentChoice(provider, prefs.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() })
    }

    fun save(choice: AgentChoice) {
        prefs.edit()
            .putString(KEY_PROVIDER, choice.provider)
            .putString(KEY_MODEL, choice.model)
            .apply()
    }

    /** The "attach open editor file paths" checkbox; off until the user turns it on. */
    fun loadAttachOpenFiles(): Boolean = prefs.getBoolean(KEY_ATTACH_OPEN_FILES, false)

    fun saveAttachOpenFiles(on: Boolean) {
        prefs.edit().putBoolean(KEY_ATTACH_OPEN_FILES, on).apply()
    }
}
