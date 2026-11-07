package com.lenscode.app.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterBridgeTest {

    @Test
    fun `http addresses are derived from the bridge websocket url`() {
        assertEquals("http://192.168.0.10:8137", BridgeUrls.httpBase("ws://192.168.0.10:8137"))
        assertEquals("http://192.168.0.10:8137/setup", BridgeUrls.setupPage("ws://192.168.0.10:8137"))
        assertEquals("http://192.168.0.10:8137/openrouter/", BridgeUrls.proxyBase("ws://192.168.0.10:8137"))
    }

    @Test
    fun `wss maps to https and a trailing slash or path is dropped`() {
        assertEquals("https://bridge.local:8137", BridgeUrls.httpBase("wss://bridge.local:8137/"))
        assertEquals("http://10.0.2.2", BridgeUrls.httpBase("ws://10.0.2.2"))
    }

    @Test
    fun `status parses the hello field`() {
        val s = BridgeLlmStatus.fromJson(
            JSONObject("""{"configured":true,"source":"file","model":"z-ai/glm-5.3-flash"}"""),
        )
        assertTrue(s.configured)
        assertEquals("file", s.source)
        assertEquals("z-ai/glm-5.3-flash", s.model)
    }

    @Test
    fun `status treats nulls and a missing field as not configured`() {
        val unset = BridgeLlmStatus.fromJson(JSONObject("""{"configured":false,"source":null,"model":null}"""))
        assertFalse(unset.configured)
        assertNull(unset.source)
        assertNull(unset.model)
        assertEquals(BridgeLlmStatus(), BridgeLlmStatus.fromJson(null))
    }
}
