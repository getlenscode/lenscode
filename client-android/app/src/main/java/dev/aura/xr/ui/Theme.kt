package dev.aura.xr.ui

import androidx.compose.ui.graphics.Color

/**
 * Palette and gradient stops ported 1:1 from the mockup's CSS
 * (MR App Ideas for Productivity.html :root tokens + .panel/.dock/.node rules).
 */
object AuraColors {
    // --text / --muted / accents
    val Text = Color(0xFFEEF7FF)
    val Muted = Color(0xFF99ABC0)
    val Blue = Color(0xFF5BA6FF)
    val Green = Color(0xFF59E58C)
    val Red = Color(0xFFFF7F95)
    val Amber = Color(0xFFFFD166)
    val Purple = Color(0xFFB494FF)
    val Cyan = Color(0xFF7DE9FF)
    val Eyebrow = Color(0xFF6D8BAD)
    val Idle = Color(0xFF64748B)
    val ActiveDock = Color(0xFF8FC6FF)

    // .panel background: linear-gradient(180deg, rgba(32,49,69,.48), rgba(8,18,28,.30))
    val PanelGradTop = Color(0x7B203145)
    val PanelGradBottom = Color(0x4D08121C)
    // .panel radial-gradient(circle at 15% 0%, rgba(110,170,255,.06), transparent 34%)
    val PanelGlow = Color(0x0F6EAAFF)
    // .panel border / border-top-color
    val PanelBorder = Color(0x2EA5D3F4)
    val PanelBorderTop = Color(0x3DE1EFFF)
    // .panel::before diagonal sheen (drawn with BlendMode.Screen)
    val SheenWhite = Color(0x0BFFFFFF)
    val SheenBlue = Color(0x096EA0FF)

    // .node (floating card style used for agent chips): border #2a3d50, bg rgba(7,17,27,.32)
    val CardBorder = Color(0xFF2A3D50)
    val CardBg = Color(0x520B1119)

    // .dock button: default color #8191a4; active bg linear-gradient(180deg,
    // rgba(50,127,220,.25), rgba(37,90,155,.14)), border rgba(100,170,250,.18)
    val DockIdle = Color(0xFF8191A4)
    val DockActiveGradTop = Color(0x40327FDC)
    val DockActiveGradBottom = Color(0x24255A9B)
    val DockActiveBorder = Color(0x2E64AAFA)
    val DockBg = Color(0x33141E2A) // dock button resting fill (subtle, mostly transparent)

    // .logo circular badge: bg #0b1119, border #2c3b4a
    val LogoBg = Color(0xFF0B1119)
    val LogoBorder = Color(0xFF2C3B4A)

    /** Per-agent accent, matching .logo.claude/.codex/.hermes/.pi. */
    fun agentAccent(provider: String): Color = when (provider.lowercase()) {
        "claude" -> Color(0xFFFF955C)
        "codex" -> Color(0xFFE7FBFF)
        "hermes" -> Color(0xFFDDE5ED)
        "pi" -> Color(0xFFD8B4FE)
        else -> Text
    }
}
