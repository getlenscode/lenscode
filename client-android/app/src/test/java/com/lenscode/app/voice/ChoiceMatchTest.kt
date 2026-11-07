package com.lenscode.app.voice

import com.lenscode.app.net.MachineInfo
import com.lenscode.app.net.ProviderInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChoiceMatchTest {
    private val devA = MachineInfo("dev-a", "Dev A", connected = true)
    private val devB = MachineInfo("dev-b", "Dev B")
    private val mini = MachineInfo("macmini.local", "Mac mini")
    private val all = listOf(MachineInfo("local", "Local"), devA, devB, mini)

    @Test
    fun `speech spacing and filler still find the machine`() {
        assertEquals(ChoiceMatch.Found(devA), matchMachine("dev a", all))
        assertEquals(ChoiceMatch.Found(mini), matchMachine("the mac mini", all))
        assertEquals(ChoiceMatch.Found(mini), matchMachine("Mac Mini machine", all))
        assertEquals(ChoiceMatch.Found(devB), matchMachine("dev-b", all))
    }

    @Test
    fun `a partial name that fits one machine finds it, one that fits several is ambiguous`() {
        assertEquals(ChoiceMatch.Found(mini), matchMachine("mini", all))
        val r = matchMachine("dev", all)
        assertTrue(r is ChoiceMatch.Ambiguous && r.candidates == listOf(devA, devB))
    }

    @Test
    fun `an unknown name, an empty name and the local pseudo-machine are no match`() {
        assertEquals(ChoiceMatch.None, matchMachine("the staging box", all))
        assertEquals(ChoiceMatch.None, matchMachine("the machine", all))
        assertEquals(ChoiceMatch.None, matchMachine("local", all))
    }

    @Test
    fun `the machine popup lists what is available`() {
        val msg = unknownMachineMessage("staging", ChoiceMatch.None, all)
        assertTrue(msg.contains("isn't in your connected machines"))
        assertTrue(msg.contains("Dev A, Dev B, Mac mini"))
        assertTrue(unknownMachineMessage("x", ChoiceMatch.None, listOf(MachineInfo("local"))).contains("none are registered"))
        assertTrue(unknownMachineMessage("dev", matchMachine("dev", all), all).contains("more than one machine"))
    }

    @Test
    fun `the agent popup lists only available agents with a few models`() {
        val providers = listOf(
            ProviderInfo("claude", true, listOf("opus", "sonnet", "haiku", "a", "b")),
            ProviderInfo("codex", true, emptyList()),
            ProviderInfo("pi", false, listOf("x")),
        )
        val msg = unknownAgentModelMessage("gemini", providers)
        assertTrue(msg.contains("\"gemini\""))
        assertTrue(msg.contains("claude (opus, sonnet, haiku, a, …)"))
        assertTrue(msg.contains("codex"))
        assertTrue(!msg.contains("pi"))
        assertTrue(unknownAgentModelMessage("x", emptyList()).contains("no agents are available"))
    }
}

class ClosestAgentModelTest {
    private val providers = listOf(
        ProviderInfo("claude", true, listOf("claude-opus-4-1", "claude-sonnet-5-5", "claude-haiku-5-5")),
        ProviderInfo("codex", true, listOf("gpt-5-codex")),
        ProviderInfo("pi", true, emptyList()),
        ProviderInfo("omp", false, listOf("hidden-model")),
    )

    private fun pick(heard: String) = closestAgentModel(heard, providers)

    @Test
    fun `a speech slip finds the agent`() {
        assertEquals(AgentModelChoice("codex", null), pick("codecs"))
        assertEquals(AgentModelChoice("claude", null), pick("cloud"))
        assertEquals(AgentModelChoice("pi", null), pick("pie"))
    }

    @Test
    fun `a misspelt model name finds the model through its pieces`() {
        assertEquals(AgentModelChoice("claude", "claude-sonnet-5-5"), pick("sonet"))
        assertEquals(AgentModelChoice("claude", "claude-opus-4-1"), pick("opuss"))
        assertEquals(AgentModelChoice("claude", "claude-haiku-5-5"), pick("hiku"))
    }

    @Test
    fun `something unrelated, empty, or only on an unavailable agent gives nothing`() {
        assertEquals(null, pick("gemini ultra"))
        assertEquals(null, pick(""))
        assertEquals(null, pick("hidden model"))
    }

    @Test
    fun `the label names the agent and model`() {
        assertEquals("codex", AgentModelChoice("codex", null).label)
        assertEquals("claude · claude-sonnet-5-5", AgentModelChoice("claude", "claude-sonnet-5-5").label)
    }

    @Test
    fun `similarity is 1 for identical text and falls with edits`() {
        assertEquals(1.0, similarity("codex", "codex"), 0.0)
        assertTrue(similarity("codecs", "codex") > 0.6)
        assertTrue(similarity("abc", "xyz") < 0.1)
    }
}
