package com.lenscode.app.ui

import com.lenscode.app.net.ProviderInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentChoicesTest {

    private fun provider(name: String, available: Boolean = true) = ProviderInfo(provider = name, available = available)

    @Test
    fun `the usual four come first in a fixed order`() {
        val shuffled = listOf(provider("omp"), provider("pi"), provider("claude"), provider("codex"))

        assertEquals(listOf("claude", "codex", "pi", "omp"), agentChoices(shuffled).map { it.provider })
    }

    @Test
    fun `agents that are not installed are left out`() {
        val list = listOf(provider("claude"), provider("copilot", available = false), provider("pi"))

        assertEquals(listOf("claude", "pi"), agentChoices(list).map { it.provider })
    }

    @Test
    fun `other installed agents follow the four and keep their order`() {
        val list = listOf(provider("opencode"), provider("pi"), provider("hermes"), provider("claude"))

        assertEquals(listOf("claude", "pi", "opencode", "hermes"), agentChoices(list).map { it.provider })
    }

    @Test
    fun `no providers loaded gives an empty list`() {
        assertEquals(emptyList<ProviderInfo>(), agentChoices(emptyList()))
    }
}
