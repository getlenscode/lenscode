package com.lenscode.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssemblyAiTokenTest {
    @Test
    fun `a good answer yields the token, model and socket url`() {
        val r = parseAssemblyToken(
            200,
            """{"token":"t1","model":"universal-streaming-multilingual","url":"wss://streaming.assemblyai.com/v3/ws"}""",
        )
        assertEquals(
            AssemblyTokenResult.Ok("t1", "universal-streaming-multilingual", "wss://streaming.assemblyai.com/v3/ws"),
            r,
        )
    }

    @Test
    fun `a missing model falls back to the english streaming model`() {
        val r = parseAssemblyToken(200, """{"token":"t1","url":"wss://x/v3/ws"}""") as AssemblyTokenResult.Ok
        assertEquals("universal-streaming-english", r.model)
    }

    @Test
    fun `no key on the bridge is unavailable with the bridge's own explanation`() {
        val r = parseAssemblyToken(503, """{"error":"No AssemblyAI key is set on the bridge. Open its /setup page to add one."}""")
        assertTrue((r as AssemblyTokenResult.Unavailable).reason.contains("/setup"))
        assertTrue(r.keyMissing)
        assertEquals(false, (parseAssemblyToken(401, """{"error":"rejected"}""") as AssemblyTokenResult.Unavailable).keyMissing)
    }

    @Test
    fun `an old bridge without the route, or a bad body, is unavailable`() {
        assertEquals(AssemblyTokenResult.Unavailable("HTTP 404"), parseAssemblyToken(404, "lenscode-bridge: not found"))
        assertTrue(parseAssemblyToken(200, "{}") is AssemblyTokenResult.Unavailable)
    }
}
