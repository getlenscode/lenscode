package com.lenscode.app.net

import org.json.JSONObject
import java.net.URI

/** Used when the bridge doesn't say which model voice routing should use (an older bridge). */
const val DEFAULT_OPENROUTER_MODEL = "z-ai/glm-5.3-flash"

/**
 * Whether lenscode-bridge holds an OpenRouter key (hello's `openrouter` field, and the
 * `openrouter_key_changed` event). The key itself never reaches the headset: the user adds it from a
 * phone or PC at the bridge's /setup page, and voice routing calls OpenRouter through the bridge's
 * /openrouter proxy. Typing a key in AR is the thing this exists to avoid.
 */
data class BridgeLlmStatus(
    val configured: Boolean = false,
    /** "file" (saved via /setup) or "env" (OPENROUTER_API_KEY on the bridge); null when unset. */
    val source: String? = null,
    /** The model voice routing should use; null = the app default. */
    val model: String? = null,
) {
    companion object {
        /** Tolerates an absent field — bridges that predate the proxy don't send one. */
        fun fromJson(json: JSONObject?): BridgeLlmStatus {
            if (json == null) return BridgeLlmStatus()
            return BridgeLlmStatus(
                configured = json.optBoolean("configured", false),
                source = json.optStringOrNull("source"),
                model = json.optStringOrNull("model"),
            )
        }

        // org.json's optString turns a JSON null into the text "null", which would become the model id "null".
        private fun JSONObject.optStringOrNull(name: String): String? =
            if (isNull(name)) null else optString(name, "").ifBlank { null }
    }
}

/** Where the bridge's HTTP side lives, derived from the same URL the WebSocket dials. */
object BridgeUrls {
    /** ws://host:8137 → http://host:8137 (wss → https). */
    fun httpBase(bridgeWsUrl: String): String {
        val uri = URI(bridgeWsUrl)
        val scheme = if (uri.scheme.equals("wss", ignoreCase = true)) "https" else "http"
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        return "$scheme://${uri.host}$port"
    }

    /** The bridge's OpenRouter proxy; the router appends `chat/completions`. */
    fun proxyBase(bridgeWsUrl: String): String = httpBase(bridgeWsUrl) + "/openrouter/"

    /** The page the user opens on a phone or PC to paste the keys. */
    fun setupPage(bridgeWsUrl: String): String = httpBase(bridgeWsUrl) + "/setup"
}
