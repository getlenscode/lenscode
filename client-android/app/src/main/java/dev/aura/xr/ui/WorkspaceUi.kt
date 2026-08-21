package dev.aura.xr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aura.xr.net.BridgeClient

/**
 * 2D content composables for the spatial panels — ported from the mockup's
 * .panel/.node/.dock/.agent CSS. Compose has no backdrop-filter, so the glass
 * look is built from the same *other* layers the CSS uses on top of the blur:
 * a two-stop gradient fill, a corner radial highlight, a brighter top border
 * edge, and a diagonal white/blue sheen composited with BlendMode.Screen
 * (the mockup's `.panel::before { mix-blend-mode:screen }`).
 */

private val PanelCorner = 20.dp
private val CardCorner = 13.dp
private val DockCorner = 11.dp

/** The .panel style: gradient fill + corner glow + screen-blend sheen + top-bright border. */
@Composable
fun GlassSurface(corner: Dp = PanelCorner, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(corner)
    Box(
        Modifier
            .fillMaxSize()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(AuraColors.PanelGradTop, AuraColors.PanelGradBottom)))
            .drawWithContent {
                drawContent()
                // .panel radial-gradient(circle at 15% 0%, rgba(110,170,255,.06), transparent 34%)
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(AuraColors.PanelGlow, Color.Transparent),
                        center = Offset(size.width * 0.15f, 0f),
                        radius = size.width * 0.5f,
                    ),
                )
                // .panel::before diagonal sheen, mix-blend-mode:screen
                drawRect(
                    brush = Brush.linearGradient(
                        colorStops = arrayOf(
                            0f to AuraColors.SheenWhite,
                            0.28f to Color.Transparent,
                            0.72f to Color.Transparent,
                            1f to AuraColors.SheenBlue,
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(size.width, size.height),
                    ),
                    blendMode = BlendMode.Screen,
                )
            }
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(listOf(AuraColors.PanelBorderTop, AuraColors.PanelBorder)),
                shape = shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** The .node/.tcard style: a smaller flat floating card, no gradient fill. */
@Composable
fun CardSurface(corner: Dp = CardCorner, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(corner)
    Box(
        Modifier
            .fillMaxSize()
            .clip(shape)
            .background(AuraColors.CardBg)
            .border(1.dp, AuraColors.CardBorder, shape),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
fun Eyebrow(text: String) {
    Text(text, color = AuraColors.Eyebrow, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
}

@Composable
fun StatusDot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

@Composable
fun PrimaryPanelContent(state: BridgeClient.UiState) {
    GlassSurface {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Eyebrow("AURA WORKSPACE")
            Spacer(Modifier.height(18.dp))
            if (!state.bridgeConnected) {
                Text("Connection lost.", color = AuraColors.Amber, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Agents and terminals may still be running.\nReconnecting…",
                    color = AuraColors.Muted,
                    fontSize = 15.sp,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(AuraColors.Green)
                    Spacer(Modifier.width(6.dp))
                    Text("bridge connected", color = AuraColors.Text, fontSize = 16.sp)
                    Spacer(Modifier.width(20.dp))
                    StatusDot(if (state.herdrConnected) AuraColors.Green else AuraColors.Red)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (state.herdrConnected) "herdr connected" else "herdr offline",
                        color = AuraColors.Text,
                        fontSize = 16.sp,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "workspaces ${state.workspaces} · panes ${state.panes} · agents ${state.agents}",
                    color = AuraColors.Muted,
                    fontSize = 15.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text("last event: ${state.lastEvent}", color = AuraColors.Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun AgentChipContent(name: String, stateLabel: String, dotColor: Color) {
    CardSurface {
        Column(
            Modifier.fillMaxSize().padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // .logo circular badge: bg #0b1119, border #2c3b4a, per-agent accent color.
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(AuraColors.LogoBg)
                    .border(1.dp, AuraColors.LogoBorder, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(name.take(1), color = AuraColors.agentAccent(name), fontWeight = FontWeight.Black, fontSize = 14.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text(name, color = AuraColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(dotColor, size = 6.dp)
                Spacer(Modifier.width(4.dp))
                Text(stateLabel, color = AuraColors.Muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun DockButtonContent(icon: String, label: String, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(DockCorner)
    Box(
        Modifier
            .fillMaxSize()
            .clip(shape)
            .background(
                if (active) {
                    Brush.verticalGradient(listOf(AuraColors.DockActiveGradTop, AuraColors.DockActiveGradBottom))
                } else {
                    Brush.verticalGradient(listOf(AuraColors.DockBg, AuraColors.DockBg))
                },
            )
            .border(1.dp, if (active) AuraColors.DockActiveBorder else Color.Transparent, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(icon, color = if (active) AuraColors.ActiveDock else AuraColors.DockIdle, fontSize = 18.sp)
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                color = if (active) AuraColors.ActiveDock else AuraColors.DockIdle,
                fontSize = 11.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}
