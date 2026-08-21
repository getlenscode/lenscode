package dev.aura.xr.ui

import androidx.compose.ui.graphics.Color

/**
 * Palette ported 1:1 from the mockup's CSS custom properties
 * (MR App Ideas for Productivity.html :root tokens).
 *
 *   --bg:#06111b; --panel:rgba(12,23,35,.46); --panel2:rgba(17,30,46,.36);
 *   --line:rgba(168,203,242,.18); --text:#eef7ff; --muted:#99abc0;
 *   --blue:#5ba6ff; --green:#59e58c; --red:#ff7f95; --amber:#ffd166;
 *   --purple:#b494ff; --cyan:#7de9ff; --r:20px (panel corner radius)
 */
object AuraColors {
    // Background gradient stops (app.background linear-gradient 180deg).
    val BgTop = Color(0xFF08131F)
    val BgBottom = Color(0xFF050A10)

    // --panel rgba(12,23,35,.46) composited a bit denser since Compose has no
    // real backdrop blur to soften a lighter fill the way CSS blur does.
    val Panel = Color(0xC00C1723)
    val PanelDock = Color(0xCC142435) // dock buttons, slightly denser fill
    val Line = Color(0x2EA8CBF2)      // --line rgba(168,203,242,.18)
    val LineBright = Color(0x3DE1EFFF) // panel top-border highlight

    val Text = Color(0xFFEEF7FF)   // --text
    val Muted = Color(0xFF99ABC0) // --muted
    val Blue = Color(0xFF5BA6FF)   // --blue
    val Green = Color(0xFF59E58C)  // --green
    val Red = Color(0xFFFF7F95)    // --red
    val Amber = Color(0xFFFFD166)  // --amber
    val Purple = Color(0xFFB494FF) // --purple
    val Cyan = Color(0xFF7DE9FF)   // --cyan

    val ActiveDock = Color(0xFF8FC6FF) // dock active label + highlight
    val Eyebrow = Color(0xFF6D8BAD)    // .eyebrow
    val Idle = Color(0xFF64748B)       // idle state dot

    /** Per-agent accent, matching the mockup's .logo.claude/.codex/.hermes/.pi. */
    fun agentAccent(provider: String): Color = when (provider.lowercase()) {
        "claude" -> Color(0xFFFF955C)
        "codex" -> Color(0xFFE7FBFF)
        "hermes" -> Color(0xFFDDE5ED)
        "pi" -> Purple
        else -> Text
    }
}
