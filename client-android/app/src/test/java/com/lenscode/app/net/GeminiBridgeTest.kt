package com.lenscode.app.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiBridgeTest {

    private val none = BridgeGeminiStatus()
    private val onBridge = BridgeGeminiStatus(configured = true, source = "file")

    private fun fileWith(key: String?) = GeminiConfigResult.Loaded(GeminiConfig(apiKey = key, model = null))

    // ── which key wins ───────────────────────────────────────────────────

    @Test
    fun `nothing configured anywhere resolves to NONE`() {
        assertEquals(GeminiKeySource.NONE, resolveGeminiKeySource(GeminiConfigResult.Missing, false, none))
    }

    @Test
    fun `a key on the bridge is used when the device has none`() {
        assertEquals(GeminiKeySource.BRIDGE, resolveGeminiKeySource(GeminiConfigResult.Missing, false, onBridge))
        assertEquals(GeminiKeySource.BRIDGE, resolveGeminiKeySource(fileWith(null), false, onBridge))
    }

    @Test
    fun `a pushed key file beats the bridge`() {
        assertEquals(GeminiKeySource.CONFIG_FILE, resolveGeminiKeySource(fileWith("AIza"), false, onBridge))
    }

    @Test
    fun `a stale build key never shadows the bridge`() {
        assertEquals(GeminiKeySource.BRIDGE, resolveGeminiKeySource(GeminiConfigResult.Missing, true, onBridge))
        assertEquals(GeminiKeySource.BUILD, resolveGeminiKeySource(GeminiConfigResult.Missing, true, none))
    }

    @Test
    fun `an unreadable config file falls through to the next source`() {
        val broken = GeminiConfigResult.ParseError("bad json")
        assertEquals(GeminiKeySource.BRIDGE, resolveGeminiKeySource(broken, false, onBridge))
        assertEquals(GeminiKeySource.NONE, resolveGeminiKeySource(broken, false, none))
    }

    // ── addresses derived from the WebSocket URL ─────────────────────────

    @Test
    fun `http addresses are derived from the bridge websocket url`() {
        assertEquals("http://192.168.1.157:8137", GeminiBridgeUrls.httpBase("ws://192.168.1.157:8137"))
        assertEquals("http://192.168.1.157:8137/setup", GeminiBridgeUrls.setupPage("ws://192.168.1.157:8137"))
        assertEquals("http://192.168.1.157:8137/gemini/", GeminiBridgeUrls.proxyBase("ws://192.168.1.157:8137"))
    }

    @Test
    fun `wss maps to https and a trailing slash or path is dropped`() {
        assertEquals("https://bridge.local:8137", GeminiBridgeUrls.httpBase("wss://bridge.local:8137/"))
        assertEquals("http://10.0.2.2", GeminiBridgeUrls.httpBase("ws://10.0.2.2"))
    }

    // ── bridge status parsing ────────────────────────────────────────────

    @Test
    fun `status parses the hello field`() {
        val s = BridgeGeminiStatus.fromJson(
            JSONObject("""{"configured":true,"source":"file","model":"gemini-x"}"""),
        )
        assertTrue(s.configured)
        assertEquals("file", s.source)
        assertEquals("gemini-x", s.model)
    }

    @Test
    fun `status treats nulls and a missing field as not configured`() {
        val unset = BridgeGeminiStatus.fromJson(JSONObject("""{"configured":false,"source":null,"model":null}"""))
        assertFalse(unset.configured)
        assertNull(unset.source)
        assertNull(unset.model)
        assertEquals(BridgeGeminiStatus(), BridgeGeminiStatus.fromJson(null))
    }
}
