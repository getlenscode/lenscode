package com.lenscode.app.net

import org.json.JSONObject
import java.net.URI

/**
 * Whether lenscode-bridge holds a Gemini key (hello's `gemini` field, and the
 * `gemini_key_changed` event). The key itself never reaches the headset: the user
 * adds it from a phone or PC at the bridge's /setup page, and voice routing calls
 * Gemini through the bridge's /gemini proxy. Typing a key in AR is the thing this
 * exists to avoid.
 */
data class BridgeGeminiStatus(
    val configured: Boolean = false,
    /** "file" (saved via /setup) or "env" (GEMINI_API_KEY on the bridge); null when unset. */
    val source: String? = null,
    /** Model chosen on the setup page; null = use the app default. */
    val model: String? = null,
) {
    companion object {
        /** Tolerates an absent field — bridges that predate the proxy don't send one. */
        fun fromJson(json: JSONObject?): BridgeGeminiStatus {
            if (json == null) return BridgeGeminiStatus()
            return BridgeGeminiStatus(
                configured = json.optBoolean("configured", false),
                source = json.optStringOrNull("source"),
                model = json.optStringOrNull("model"),
            )
        }

        // org.json's optString turns a JSON null into the text "null" (the bridge sends
        // "model": null when none is chosen), which then became the model id "null".
        private fun JSONObject.optStringOrNull(name: String): String? =
            if (isNull(name)) null else optString(name, "").ifBlank { null }
    }
}

/** Where the bridge's HTTP side lives, derived from the same URL the WebSocket dials. */
object GeminiBridgeUrls {
    /** ws://host:8137 → http://host:8137 (wss → https). */
    fun httpBase(bridgeWsUrl: String): String {
        val uri = URI(bridgeWsUrl)
        val scheme = if (uri.scheme.equals("wss", ignoreCase = true)) "https" else "http"
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        return "$scheme://${uri.host}$port"
    }

    /**
     * Base URL handed to Koog, which appends `v1beta/models/<id>:generateContent`.
     * The trailing slash is load-bearing: ktor resolves that relative path like a
     * browser would, so without it `/gemini` is replaced rather than extended and
     * the request lands on `/v1beta/...` (a 404 from the bridge).
     */
    fun proxyBase(bridgeWsUrl: String): String = httpBase(bridgeWsUrl) + "/gemini/"

    /** The page the user opens on a phone or PC to paste the key. */
    fun setupPage(bridgeWsUrl: String): String = httpBase(bridgeWsUrl) + "/setup"
}

/** Where the Gemini key being used comes from, highest precedence first. */
enum class GeminiKeySource { CONFIG_FILE, BRIDGE, BUILD, NONE }

/**
 * One rule for "which key wins", shared by the router setup and the Settings
 * status line so they can't disagree. A key file pushed to this device is the
 * most specific override; the bridge's key is the normal path; a key baked into
 * the APK is the leftover fallback.
 */
fun resolveGeminiKeySource(
    file: GeminiConfigResult,
    buildKeyPresent: Boolean,
    bridge: BridgeGeminiStatus,
): GeminiKeySource = when {
    (file as? GeminiConfigResult.Loaded)?.config?.apiKey.isNullOrBlank().not() -> GeminiKeySource.CONFIG_FILE
    bridge.configured -> GeminiKeySource.BRIDGE
    buildKeyPresent -> GeminiKeySource.BUILD
    else -> GeminiKeySource.NONE
}
