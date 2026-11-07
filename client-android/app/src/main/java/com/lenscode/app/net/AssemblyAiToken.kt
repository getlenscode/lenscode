package com.lenscode.app.net

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** What the bridge's `/assemblyai/token` route answered; see lenscode-bridge/src/setupHttp.ts. */
sealed class AssemblyTokenResult {
    /** [url] is the WebSocket to open, [token] a one-minute credential for it. The account key never leaves the bridge. */
    data class Ok(val token: String, val model: String, val url: String) : AssemblyTokenResult()

    /**
     * No token: [keyMissing] when the bridge has no AssemblyAI key (HTTP 503); otherwise an old bridge
     * without the route, a rejected key, or the bridge/AssemblyAI being unreachable.
     */
    data class Unavailable(val reason: String, val keyMissing: Boolean = false) : AssemblyTokenResult()
}

fun parseAssemblyToken(httpCode: Int, body: String): AssemblyTokenResult {
    val json = runCatching { JSONObject(body) }.getOrNull()
    if (httpCode != 200) {
        val why = json?.optString("error").orEmpty().ifBlank { "HTTP $httpCode" }
        return AssemblyTokenResult.Unavailable(why, keyMissing = httpCode == 503)
    }
    val token = json?.optString("token").orEmpty()
    val url = json?.optString("url").orEmpty()
    if (token.isBlank() || url.isBlank()) return AssemblyTokenResult.Unavailable("The bridge sent no token.")
    return AssemblyTokenResult.Ok(token, json?.optString("model").orEmpty().ifBlank { "universal-streaming-english" }, url)
}

/** Blocking: call from a background thread. [authHeaders] are the paired-device headers the Gemini proxy also uses. */
fun fetchAssemblyToken(
    http: OkHttpClient,
    bridgeWsUrl: String,
    authHeaders: Map<String, String>,
): AssemblyTokenResult = try {
    val request = Request.Builder()
        .url(GeminiBridgeUrls.httpBase(bridgeWsUrl) + "/assemblyai/token")
        .apply { authHeaders.forEach { (k, v) -> header(k, v) } }
        .build()
    http.newCall(request).execute().use { parseAssemblyToken(it.code, it.body?.string().orEmpty()) }
} catch (e: Exception) {
    AssemblyTokenResult.Unavailable(e.message ?: "Could not reach the bridge.")
}
