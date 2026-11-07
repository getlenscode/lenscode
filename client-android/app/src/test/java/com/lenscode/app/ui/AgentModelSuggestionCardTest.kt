package com.lenscode.app.ui

import com.lenscode.app.voice.AgentModelChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModelSuggestionCardTest {
    private val suggestion = AgentModelChoice("claude", "claude-sonnet-5-5")

    private class Recorder {
        val events = mutableListOf<String>()
        var accepted: AgentModelChoice? = null
    }

    private fun card(r: Recorder, suffix: String = "") = agentModelSuggestionCard(
        heard = "sonet",
        suggestion = suggestion,
        suffix = suffix,
        onAccept = { r.accepted = it; r.events += "accept" },
        onClose = { r.events += "close" },
    )

    @Test
    fun `confirming closes the card, then applies the suggestion`() {
        val r = Recorder()
        card(r).onConfirm()
        assertEquals(listOf("close", "accept"), r.events)
        assertEquals(suggestion, r.accepted)
    }

    @Test
    fun `cancelling only closes the card and applies nothing`() {
        val r = Recorder()
        card(r).onCancel()
        assertEquals(listOf("close"), r.events)
        assertEquals(null, r.accepted)
    }

    @Test
    fun `nothing happens until one of the buttons is used`() {
        val r = Recorder()
        card(r)
        assertTrue(r.events.isEmpty())
    }

    @Test
    fun `the card names what was heard, the suggestion, and how to answer`() {
        val c = card(Recorder(), suffix = " The task starts only if you confirm.")
        assertEquals("Did you mean claude · claude-sonnet-5-5?", c.title)
        assertTrue(c.message.contains("\"sonet\""))
        assertTrue(c.message.contains("claude · claude-sonnet-5-5"))
        assertTrue(c.message.contains("“confirm”"))
        assertTrue(c.message.endsWith("The task starts only if you confirm."))
        assertEquals("Use claude-sonnet-5-5", c.confirmLabel)
    }

    @Test
    fun `an agent-only suggestion labels the button with the agent`() {
        val c = agentModelSuggestionCard("codecs", AgentModelChoice("codex", null), "", {}, {})
        assertEquals("Did you mean codex?", c.title)
        assertEquals("Use codex", c.confirmLabel)
    }
}
