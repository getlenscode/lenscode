package com.lenscode.app.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolModelsTest {

    @Test
    fun `agent info tolerates a sparse payload`() {
        val a = wireJson.decodeFromString<AgentInfo>("""{"agentId":"a1"}""")
        assertEquals("a1", a.agentId)
        assertEquals("agent", a.provider)
        assertEquals("idle", a.status)
        assertEquals("local", a.machineId)
        assertNull(a.title)
        assertNull(a.costUsd)
    }

    @Test
    fun `explicit nulls fall back to non-null defaults`() {
        val a = wireJson.decodeFromString<AgentInfo>("""{"agentId":"a1","status":null}""")
        assertEquals("idle", a.status)
    }

    @Test
    fun `terminal title comes from the wire name field`() {
        val p = wireJson.decodeFromString<PaneInfo>("""{"terminalId":"t1","name":"bash"}""")
        assertEquals("bash", p.title)
        assertEquals("t1", p.terminalId)
    }

    @Test
    fun `dir entry derives isDir from wire kind`() {
        assertTrue(wireJson.decodeFromString<DirEntry>("""{"name":"src","kind":"dir"}""").isDir)
        assertFalse(wireJson.decodeFromString<DirEntry>("""{"name":"f.kt","kind":"file"}""").isDir)
    }

    @Test
    fun `permission info decodes nested actions`() {
        val p = wireJson.decodeFromString<PermissionInfo>(
            """{"permissionRequestId":"p1","agentId":"a1","kind":"shell",
               "actions":[{"id":"ok","label":"Allow","behavior":"allow"}]}""",
        )
        assertEquals("p1", p.permissionRequestId)
        assertEquals(1, p.actions.size)
        assertEquals("allow", p.actions[0].behavior)
    }

    @Test
    fun `git status decodes nested file list`() {
        val s = wireJson.decodeFromString<GitStatus>(
            """{"branch":"main","files":[{"path":"a.kt","status":"M"}]}""",
        )
        assertEquals("main", s.branch)
        assertEquals("M", s.files[0].status)
    }

    @Test
    fun `unknown fields from newer bridges are ignored`() {
        val m = wireJson.decodeFromString<MachineInfo>(
            """{"id":"m1","label":"x","connected":true,"futureField":{"nested":1}}""",
        )
        assertEquals("m1", m.id)
        assertTrue(m.connected)
    }

    @Test
    fun `decodeList reads the array out of a result envelope`() {
        val result = JSONObject("""{"agents":[{"agentId":"a1"},{"agentId":"a2"}]}""")
        assertEquals(listOf("a1", "a2"), decodeList<AgentInfo>(result, "agents").map { it.agentId })
    }

    @Test
    fun `decodeList on a missing key gives empty`() {
        assertTrue(decodeList<AgentInfo>(JSONObject("{}"), "agents").isEmpty())
    }

    @Test
    fun `decodeResult reads a whole envelope`() {
        val result = JSONObject("""{"confident":true,"resolvedPath":"src/a.kt","candidates":[]}""")
        val v = decodeResult<VoiceResolution>(result)
        assertTrue(v.confident)
        assertEquals("src/a.kt", v.resolvedPath)
    }
}
