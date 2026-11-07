package com.lenscode.app.ui

import com.lenscode.app.net.ProviderInfo
import com.lenscode.app.net.ProviderModeInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerDefaultModeTest {
    private fun mode(id: String, label: String) = ProviderModeInfo(id = id, label = label)

    private val claude = ProviderInfo(
        "claude", true,
        modes = listOf(mode("plan", "Plan Mode"), mode("default", "Always Ask"), mode("acceptEdits", "Accept File Edits"), mode("auto", "Auto mode")),
    )
    private val claudeNoAuto = ProviderInfo("claude", true, modes = listOf(mode("default", "Always Ask")))
    private val codex = ProviderInfo("codex", true, modes = listOf(mode("auto", "Default Permissions"), mode("full-access", "Full Access")))

    @Test
    fun `claude starts in auto mode when the daemon offers it`() {
        assertEquals("auto", defaultModeIdFor("claude", listOf(claude)))
        assertEquals("Auto mode", composerModeFor("", "claude", listOf(claude)).label)
    }

    @Test
    fun `an explicit choice still wins over the default`() {
        assertEquals("acceptEdits", composerModeFor("acceptEdits", "claude", listOf(claude)).id)
        assertEquals("default", composerModeFor("default", "claude", listOf(claude)).id)
    }

    @Test
    fun `without an auto mode, or for another agent, the provider default is used`() {
        assertEquals("", defaultModeIdFor("claude", listOf(claudeNoAuto)))
        assertEquals(ComposerDefaultMode, composerModeFor("", "claude", listOf(claudeNoAuto)))
        // Codex's "auto" id is its ordinary approval flow, not the classifier mode.
        assertEquals("", defaultModeIdFor("codex", listOf(codex)))
        assertEquals("", defaultModeIdFor("claude", emptyList()))
    }
}
