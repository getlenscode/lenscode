package dev.aura.xr.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Match Compose clipping to the XR compositor so neither adds extra rounding. */
object AuraCorners {
    val Panel = 10.dp
    val Card = 8.dp
    val Control = 6.dp
}

/**
 * Smoked optical glass: cool neutral surfaces, luminous edges and ice accents.
 * Keep a dark base for legibility in bright XR environments. These layers
 * simulate glass reflections; they do not blur the passthrough behind a panel.
 */
object AuraColors {
    // --text / --muted / accents
    val Text = Color(0xFFEEF7FF)
    val Muted = Color(0xFFB0C1D0)
    val Blue = Color(0xFF91CFFF)
    val Green = Color(0xFF59E58C)
    val Red = Color(0xFFFF7F95)
    val Amber = Color(0xFFFFD166)
    val Purple = Color(0xFFB494FF)
    val Cyan = Color(0xFF7DE9FF)
    val Eyebrow = Color(0xFF9EB8CC)
    val Idle = Color(0xFF8B9DAD)
    val ActiveDock = Color(0xFFE0F7FF)

    val PanelGradTop = Color(0xD0223342)
    val PanelGradBottom = Color(0xE6101B26)
    val PanelGlow = Color(0x187DDFFF)
    val PanelBorder = Color(0x246DA3BD)
    val PanelBorderTop = Color(0x65E0F4FF)
    val SheenWhite = Color(0x12FFFFFF)
    val SheenBlue = Color(0x0C86D8EF)

    val CardBorder = Color(0x386F96AB)
    val CardBg = Color(0xBA101F2C)
    val CardGradTop = Color(0xCC263947)

    val DockIdle = Color(0xFFB0C3D1)
    val DockActiveGradTop = Color(0xD03B687E)
    val DockActiveGradBottom = Color(0xD01A344A)
    val DockActiveBorder = Color(0xB09BDEFA)
    val DockBg = Color(0xCF132330)

    val LogoBg = Color(0xB3192A37)
    val LogoBorder = Color(0x527C9CAD)

    /** Per-agent accent, matching .logo.claude/.codex/.hermes/.pi. */
    fun agentAccent(provider: String): Color = when (provider.lowercase()) {
        "claude" -> Color(0xFFFF955C)
        "codex" -> Color(0xFFE7FBFF)
        "hermes" -> Color(0xFFDDE5ED)
        "pi" -> Color(0xFFD8B4FE)
        else -> Text
    }

    /**
     * Paseo's real agent lifecycle statuses (docs/paseo-migration-plan.md,
     * Phase 3) — initializing/idle/running/error/closed. `requiresAttention`
     * is a separate flag on AgentSummary, not a status value; the agent strip
     * layers its own pulse on top of this color rather than folding it in here.
     */
    fun agentStatusColor(status: String): Color = when (status) {
        "running" -> Green
        "error" -> Red
        "initializing" -> Cyan
        "closed" -> Muted
        "idle" -> Idle
        else -> Idle
    }
}
