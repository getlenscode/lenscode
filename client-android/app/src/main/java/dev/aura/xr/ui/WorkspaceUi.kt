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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aura.xr.net.BridgeClient

/**
 * 2D content composables for the spatial panels — the glass style ported from
 * the mockup's CSS (.glass-panel, .eyebrow, .agent, .dock).
 */

@Composable
fun GlassSurface(
    background: Color = AuraColors.Panel,
    corner: Int = 24,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(corner.dp))
            .background(background)
            .border(1.dp, AuraColors.Line, RoundedCornerShape(corner.dp)),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
fun PrimaryPanelContent(state: BridgeClient.UiState) {
    GlassSurface {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "AURA WORKSPACE",
                color = AuraColors.Eyebrow,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(16.dp))
            if (!state.bridgeConnected) {
                Text("Connection lost.", color = AuraColors.Amber, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Agents and terminals may still be running.\nReconnecting…",
                    color = AuraColors.Muted,
                    fontSize = 15.sp,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(AuraColors.Green)
                    Text(" bridge connected   ", color = AuraColors.Text, fontSize = 16.sp)
                    StatusDot(if (state.herdrConnected) AuraColors.Green else AuraColors.Red)
                    Text(
                        if (state.herdrConnected) " herdr connected" else " herdr offline",
                        color = AuraColors.Text,
                        fontSize = 16.sp,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "workspaces ${state.workspaces} · panes ${state.panes} · agents ${state.agents}",
                    color = AuraColors.Muted,
                    fontSize = 15.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text("last event: ${state.lastEvent}", color = AuraColors.Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun StatusDot(color: Color) {
    Box(
        Modifier
            .size(9.dp)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
fun AgentChipContent(name: String, stateLabel: String, dotColor: Color) {
    GlassSurface(corner = 16) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(dotColor)
                Spacer(Modifier.width(6.dp))
                Text(name, color = AuraColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
            Text(stateLabel, color = AuraColors.Muted, fontSize = 12.sp)
        }
    }
}

@Composable
fun DockButtonContent(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(14.dp))
            .background(AuraColors.PanelDock)
            .border(1.dp, if (active) AuraColors.Blue else AuraColors.Line, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (active) AuraColors.ActiveDock else AuraColors.Muted,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
