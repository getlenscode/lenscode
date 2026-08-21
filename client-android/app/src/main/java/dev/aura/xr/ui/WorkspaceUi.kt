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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aura.xr.net.BridgeClient

/**
 * 2D content composables for the spatial panels — ported from the mockup's
 * CSS (.glass-panel, .eyebrow, .agent, .dock, .badge). Compose has no
 * backdrop-filter, so the "glass" look is a denser semi-transparent fill +
 * a bright top-edge border standing in for the blurred highlight.
 */

private val PanelCorner = 20.dp

@Composable
fun GlassSurface(
    background: Color = AuraColors.Panel,
    corner: androidx.compose.ui.unit.Dp = PanelCorner,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(corner)
    Box(
        Modifier
            .fillMaxSize()
            .clip(shape)
            .background(background)
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(listOf(AuraColors.LineBright, AuraColors.Line)),
                shape = shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
fun Eyebrow(text: String) {
    Text(
        text,
        color = AuraColors.Eyebrow,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
    )
}

@Composable
fun StatusDot(color: Color, size: androidx.compose.ui.unit.Dp = 8.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
fun PrimaryPanelContent(state: BridgeClient.UiState) {
    GlassSurface {
        Column(
            Modifier
                .fillMaxSize()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Eyebrow("AURA WORKSPACE")
            Spacer(Modifier.height(18.dp))
            if (!state.bridgeConnected) {
                Text(
                    "Connection lost.",
                    color = AuraColors.Amber,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                )
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
    GlassSurface(corner = 16.dp) {
        Column(
            Modifier.fillMaxSize().padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Circular "logo" badge matching the mockup's .logo.claude/.codex/etc.
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF0B1119))
                    .border(1.dp, Color(0xFF2C3B4A), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    name.take(1),
                    color = AuraColors.agentAccent(name),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
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
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .fillMaxSize()
            .clip(shape)
            .background(if (active) Color(0xFF1F3A5A) else AuraColors.PanelDock)
            .border(1.dp, if (active) AuraColors.Blue else AuraColors.Line, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(icon, color = if (active) AuraColors.ActiveDock else AuraColors.Muted, fontSize = 18.sp)
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                color = if (active) AuraColors.ActiveDock else AuraColors.Muted,
                fontSize = 11.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}
