package com.lenscode.app.ui

import com.lenscode.app.net.ProviderInfo
import com.lenscode.app.net.ProviderModeInfo
import org.junit.Assert.*
import org.junit.Test

class AgentProviderModesTest {
    private val providers = listOf(
        ProviderInfo(provider = "pi", available = true),
        ProviderInfo(provider = "codex", available = true, modes = listOf(
            ProviderModeInfo("auto", "Default Permissions"),
            ProviderModeInfo("full-access", "Full Access", dangerous = true),
        )),
        ProviderInfo(provider = "omp", available = true, modes = listOf(
            ProviderModeInfo("ask", "Always Ask"),
            ProviderModeInfo("write", "Write Approval"),
            ProviderModeInfo("full", "Full Access", dangerous = true),
        )),
    )

    @Test fun piOmitsModesAndOtherProvidersKeepNativeIds() {
        assertTrue(composerModesFor("pi", providers).isEmpty())
        assertEquals(listOf("auto", "full-access"), composerModesFor("codex", providers).map { it.id })
        assertEquals(listOf("ask", "write", "full"), composerModesFor("omp", providers).map { it.id })
        assertTrue(composerModesFor("omp", providers).last().dangerous)
    }

    @Test fun staleClaudeModesFallBackToProviderDefault() {
        for (provider in listOf("pi", "codex", "omp")) {
            assertEquals("", composerModeFor("acceptEdits", provider, providers).id)
        }
        assertEquals("", composerModeFor("auto", "pi", providers).id)
        assertEquals("", composerModeFor("ask", "omp", emptyList()).id)
        assertEquals("ask", composerModeFor("ask", "omp", providers).id)
    }
}
