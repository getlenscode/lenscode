package com.lenscode.app.net

import android.content.Context
import android.content.SharedPreferences
import com.lenscode.app.ui.AgentTmux
import org.json.JSONObject

private const val PREFS_NAME = "lenscode_agent_tmux"
private const val KEY_BINDINGS = "bindings"

/**
 * Remembers which tmux session each agent was given, so after a restart the app does not hand
 * an agent that already has one a second session and a second copy of the instructions.
 */
class AgentTmuxStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    fun load(): Map<String, AgentTmux> {
        val raw = prefs.getString(KEY_BINDINGS, null) ?: return emptyMap()
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val out = mutableMapOf<String, AgentTmux>()
        for (agentId in json.keys()) {
            val entry = json.optJSONObject(agentId) ?: continue
            val session = entry.optString("session").takeIf { it.isNotBlank() } ?: continue
            out[agentId] = AgentTmux(session, entry.optString("machineId", "local"), entry.optString("cwd"))
        }
        return out
    }

    fun save(bindings: Map<String, AgentTmux>) {
        val json = JSONObject()
        for ((agentId, tmux) in bindings) {
            json.put(
                agentId,
                JSONObject().put("session", tmux.session).put("machineId", tmux.machineId).put("cwd", tmux.cwd),
            )
        }
        prefs.edit().putString(KEY_BINDINGS, json.toString()).apply()
    }
}
