package dev.aura.xr.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * WebSocket client for aura-bridge speaking XR protocol v1 (see ../protocol).
 * Runs the initial-sync sequence (spec §7): hello → snapshot → subscribe, and
 * resyncs on every reconnect because backend state wins (spec §58–59).
 */
class BridgeClient(
    private val scope: CoroutineScope,
    private val url: String,
    private val deviceId: String,
) {
    data class UiState(
        val bridgeConnected: Boolean = false,
        val herdrConnected: Boolean = false,
        val workspaces: Int = 0,
        val panes: Int = 0,
        val agents: Int = 0,
        val lastEvent: String = "—",
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val http = OkHttpClient()
    private var ws: WebSocket? = null
    private val nextId = AtomicLong(1)
    private var closedByUs = false

    fun connect() {
        closedByUs = false
        val request = Request.Builder().url(url).build()
        ws = http.newWebSocket(request, Listener())
    }

    fun close() {
        closedByUs = true
        ws?.close(1000, "bye")
    }

    private fun send(type: String, fields: JSONObject = JSONObject()): String {
        val id = "a${nextId.getAndIncrement()}"
        fields.put("id", id)
        fields.put("type", type)
        ws?.send(fields.toString())
        return id
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            send(
                "hello",
                JSONObject()
                    .put("protocolVersion", 1)
                    .put("deviceId", deviceId)
                    .put(
                        "clientInfo",
                        JSONObject()
                            .put("app", "aura-xr-android")
                            .put("version", "0.1.0")
                            .put("platform", "android-xr"),
                    ),
            )
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = JSONObject(text)
            when {
                msg.optString("type") == "event" -> handleEvent(msg)
                msg.has("result") -> handleResult(msg.getJSONObject("result"))
                // Structured errors surface through the disconnected state for now.
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w("AuraBridge", "WebSocket failure: ${t.javaClass.simpleName}: ${t.message}", t)
            _state.value = _state.value.copy(bridgeConnected = false)
            if (closedByUs) return
            // Spec §59: never assume backend processes stopped; retry and resync.
            scope.launch {
                delay(3000)
                connect()
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            _state.value = _state.value.copy(bridgeConnected = false)
        }
    }

    private fun handleResult(result: JSONObject) {
        when {
            result.has("herdrConnected") -> {
                // hello result → continue the sync sequence.
                _state.value = _state.value.copy(
                    bridgeConnected = true,
                    herdrConnected = result.getBoolean("herdrConnected"),
                )
                send("snapshot.request")
                send("events.subscribe", JSONObject().put("topics", JSONArray().put("herdr")))
            }
            result.has("herdr") -> {
                val snapshot = result.optJSONObject("herdr")?.optJSONObject("snapshot")
                _state.value = _state.value.copy(
                    workspaces = snapshot?.optJSONArray("workspaces")?.length() ?: 0,
                    panes = snapshot?.optJSONArray("panes")?.length() ?: 0,
                    agents = snapshot?.optJSONArray("agents")?.length() ?: 0,
                )
            }
        }
    }

    private fun handleEvent(msg: JSONObject) {
        if (msg.optString("topic") != "herdr") return
        _state.value = _state.value.copy(lastEvent = msg.optString("event", "—"))
        // M1 parity with the Unity client: refresh counts on any Herdr mutation.
        send("snapshot.request")
    }
}
