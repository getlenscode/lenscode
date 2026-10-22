package com.lenscode.app.ui

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTmuxTest {

    @Test
    fun `a session name is lc- plus four lowercase letters or digits`() {
        val name = newTmuxSession(emptySet(), Random(1))

        assertTrue(name, Regex("^lc-[a-z0-9]{4}$").matches(name))
    }

    @Test
    fun `parallel agents each get a different session`() {
        val taken = mutableSetOf<String>()
        repeat(4) { taken += newTmuxSession(taken, Random(7)) }

        assertEquals(4, taken.size)
    }

    @Test
    fun `a name already in use is never handed out again`() {
        // The same seed would repeat its first draw; the taken set must force a different one.
        val first = newTmuxSession(emptySet(), Random(42))
        val second = newTmuxSession(setOf(first), Random(42))

        assertNotEquals(first, second)
    }

    @Test
    fun `the instructions name the agent's own session everywhere`() {
        val text = tmuxPreamble("lc-ab12")

        assertTrue(text.contains("tmux send-keys -t lc-ab12 \"<command>\" Enter"))
        assertTrue(text.contains("tmux capture-pane -t lc-ab12 -p"))
        assertTrue(text.contains("tmux send-keys -t lc-ab12 C-c"))
    }

    @Test
    fun `the instructions never mention the old shared session`() {
        assertFalse(tmuxPreamble("lc-ab12").contains("lenscode-agent"))
    }

    @Test
    fun `the instructions let the agent open extra sessions named after its own`() {
        val text = tmuxPreamble("lc-ab12")

        assertTrue(text.contains("tmux new-session -d -s lc-ab12-<purpose>"))
        assertTrue(text.contains("tell the user its name"))
    }

    @Test
    fun `two agents get instructions for two different sessions`() {
        assertNotEquals(tmuxPreamble("lc-aaaa"), tmuxPreamble("lc-bbbb"))
    }
}
