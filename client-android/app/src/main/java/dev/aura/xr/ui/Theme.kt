package dev.aura.xr.ui

import androidx.compose.ui.graphics.Color

/**
 * Palette ported from the mockup's CSS custom properties
 * (MR App Ideas for Productivity.html :root tokens).
 */
object AuraColors {
    val Panel = Color(0xDC0C1723)      // --panel rgba(12,23,35,.86)
    val PanelDock = Color(0xE6142435)  // dock buttons, slightly lighter
    val Line = Color(0x5AB5D3F4)       // --line hairline border
    val Text = Color(0xFFEEF7FF)       // --text
    val Muted = Color(0xFF99ABC0)      // --muted
    val Blue = Color(0xFF5BA6FF)       // --blue
    val Green = Color(0xFF59E58C)      // --green
    val Red = Color(0xFFFF7F95)        // --red
    val Amber = Color(0xFFFFD166)      // --amber
    val Purple = Color(0xFFB494FF)     // --purple
    val ActiveDock = Color(0xFF8FC6FF) // dock active label
    val Eyebrow = Color(0xFF6D8BAD)    // .eyebrow
    val Idle = Color(0xFF64748B)       // idle state dot
}
