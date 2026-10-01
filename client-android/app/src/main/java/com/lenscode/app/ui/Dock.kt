package com.lenscode.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Not-yet-implemented dock views (Diff, Remote — Phase 2/3) — honest placeholder, not fake data. */
@Composable
fun PlaceholderPanelContent(title: String, note: String) {
    GlassSurface {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Eyebrow(title)
            Spacer(Modifier.height(12.dp))
            Text(note, color = LensCodeColors.Muted, fontSize = 14.sp)
        }
    }
}

@Composable
fun DockButtonContent(
    id: String,
    label: String,
    active: Boolean,
    /** Something needs the user's response here (e.g. a pending agent permission) — independent of [active], since the dock item doesn't have to be the current view to need attention. */
    attention: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(DockCorner)
    // Pulsing glow, not a static highlight — a permission sitting unanswered
    // should keep catching the eye, not just flag once and fade into the
    // rest of the dock's idle chrome.
    val glowAlpha = if (attention) {
        val transition = rememberInfiniteTransition(label = "dockAttentionGlow")
        val alpha by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(700, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "dockAttentionGlowAlpha",
        )
        alpha
    } else {
        0f
    }
    Box(
        Modifier
            .fillMaxSize()
            .clip(shape)
            .background(
                if (active) {
                    Brush.verticalGradient(listOf(LensCodeColors.DockActiveGradTop, LensCodeColors.DockActiveGradBottom))
                } else {
                    Brush.verticalGradient(listOf(LensCodeColors.DockBg, LensCodeColors.DockBg))
                },
            )
            .border(
                1.dp,
                Brush.verticalGradient(listOf(
                    if (active) LensCodeColors.DockActiveBorder else LensCodeColors.CardBorder,
                    LensCodeColors.CardBorder,
                )),
                shape,
            )
            .then(
                if (attention) {
                    Modifier.border(2.dp, LensCodeColors.Amber.copy(alpha = glowAlpha), shape)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            DockIcon(id, color = if (active) LensCodeColors.ActiveDock else LensCodeColors.DockIdle)
            Spacer(Modifier.height(6.dp))
            Text(
                label,
                color = if (active) LensCodeColors.ActiveDock else LensCodeColors.DockIdle,
                fontSize = 11.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                letterSpacing = 0.3.sp,
            )
        }
        if (active) {
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp)
                    .width(16.dp).height(2.dp)
                    .background(LensCodeColors.Cyan, RoundedCornerShape(1.dp)),
            )
        }
    }
}
