package com.lenscode.app.net

import com.lenscode.app.ui.AgentTmux
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentTmuxStoreTest {

    private val a = AgentTmux("lc-aaaa", "maclaptop", "/Users/bread/clawd")
    private val b = AgentTmux("lc-bbbb", "pastetreee", "/home/me/app")

    @Test
    fun `nothing saved loads as an empty map`() {
        assertEquals(emptyMap<String, AgentTmux>(), AgentTmuxStore(FakeSharedPreferences()).load())
    }

    @Test
    fun `several agents' sessions round-trip`() {
        val prefs = FakeSharedPreferences()
        AgentTmuxStore(prefs).save(mapOf("agent-1" to a, "agent-2" to b))

        assertEquals(mapOf("agent-1" to a, "agent-2" to b), AgentTmuxStore(prefs).load())
    }

    @Test
    fun `saving replaces what was stored before`() {
        val prefs = FakeSharedPreferences()
        val store = AgentTmuxStore(prefs)
        store.save(mapOf("agent-1" to a, "agent-2" to b))
        store.save(mapOf("agent-2" to b))

        assertEquals(mapOf("agent-2" to b), store.load())
    }

    @Test
    fun `unreadable stored data loads as empty instead of crashing`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("bindings", "{not json").apply()

        assertEquals(emptyMap<String, AgentTmux>(), AgentTmuxStore(prefs).load())
    }

    @Test
    fun `an entry without a session is skipped and the rest still load`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString(
            "bindings",
            """{"agent-1":{"session":"lc-aaaa","machineId":"box","cwd":"/x"},"agent-2":{"machineId":"box"}}""",
        ).apply()

        assertEquals(mapOf("agent-1" to AgentTmux("lc-aaaa", "box", "/x")), AgentTmuxStore(prefs).load())
    }
}
