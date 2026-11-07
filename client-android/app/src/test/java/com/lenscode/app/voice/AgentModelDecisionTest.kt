package com.lenscode.app.voice

import com.lenscode.app.net.ProviderInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModelDecisionTest {
    private val providers = listOf(
        ProviderInfo("claude", true, listOf("claude-opus-4-1", "claude-opus-4-1-fast", "claude-sonnet-5-5")),
        ProviderInfo("codex", true, listOf("gpt-5-codex")),
        ProviderInfo("pi", true, emptyList()),
        ProviderInfo("omp", true, emptyList()),
        ProviderInfo("opencode", false, listOf("hidden-model")),
    )

    private fun decide(heard: String) = decideAgentModel(heard, providers)

    // ── exact matches are applied straight away ─────────────────────────

    @Test
    fun `an agent's own name is used`() {
        assertEquals(AgentModelDecision.Use(AgentModelChoice("codex", null)), decide("codex"))
        assertEquals(AgentModelDecision.Use(AgentModelChoice("pi", null)), decide("Pi"))
    }

    @Test
    fun `oh my pi means omp`() {
        assertEquals(AgentModelDecision.Use(AgentModelChoice("omp", null)), decide("oh my pi"))
    }

    @Test
    fun `a model family picks the shortest id`() {
        assertEquals(AgentModelDecision.Use(AgentModelChoice("claude", "claude-opus-4-1")), decide("opus"))
        assertEquals(AgentModelDecision.Use(AgentModelChoice("claude", "claude-sonnet-5-5")), decide("sonnet"))
    }

    // ── near misses are suggested, never applied ────────────────────────

    @Test
    fun `a speech slip is suggested rather than used`() {
        assertEquals(AgentModelDecision.Suggest(AgentModelChoice("codex", null)), decide("codecs"))
        assertEquals(AgentModelDecision.Suggest(AgentModelChoice("claude", "claude-sonnet-5-5")), decide("sonet"))
    }

    // ── nothing close is rejected with the list of what exists ──────────

    @Test
    fun `an unknown name is rejected and the message lists what is available`() {
        val d = decide("gemini ultra") as AgentModelDecision.Reject
        assertTrue(d.message.contains("\"gemini ultra\""))
        assertTrue(d.message.contains("claude"))
        assertTrue(d.message.contains("codex"))
        assertTrue(!d.message.contains("opencode"))
    }

    @Test
    fun `an agent that is not available is not offered or used`() {
        assertTrue(decide("opencode") is AgentModelDecision.Reject)
        assertTrue(decide("hidden model") is AgentModelDecision.Reject)
    }

    @Test
    fun `an empty name is rejected`() {
        assertTrue(decide("") is AgentModelDecision.Reject)
        assertEquals(null, resolveAgentModel("  ", providers))
    }

    @Test
    fun `with no agents loaded it says so instead of suggesting`() {
        val d = decideAgentModel("codex", emptyList()) as AgentModelDecision.Reject
        assertTrue(d.message.contains("no agents are available"))
    }
}
