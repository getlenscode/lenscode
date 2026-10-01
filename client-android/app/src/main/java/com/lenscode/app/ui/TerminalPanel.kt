package com.lenscode.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lenscode.app.net.PaneInfo

/** State for the Ctrl+C confirmation card (spec §20 — never SIGINT without one). */
data class StopConfirmState(
    val pane: PaneInfo,
    val processLabel: String,
    val onConfirm: () -> Unit,
    val onCancel: () -> Unit,
)

/** Terminal view: real Paseo terminals on the left, selected terminal's scrollback on the right. */
@Composable
fun TerminalPanelContent(
    terminals: List<PaneInfo>,
    machineId: String,
    selected: PaneInfo?,
    output: String?,
    onSelect: (PaneInfo) -> Unit,
    onNew: () -> Unit,
    onClose: (PaneInfo) -> Unit,
    onRequestStop: (PaneInfo) -> Unit,
    stopConfirm: StopConfirmState?,
) {
    Box(Modifier.fillMaxSize()) {
    GlassSurface {
        Row(Modifier.fillMaxSize().padding(20.dp)) {
            Column(Modifier.width(220.dp).fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Eyebrow("TERMINALS" + (if (machineId != "local") " · $machineId" else ""))
                    Text(
                        "+ New",
                        color = LensCodeColors.Blue,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(onClick = onNew),
                    )
                }
                Spacer(Modifier.height(10.dp))
                if (terminals.isEmpty()) {
                    Text("No panes yet.", color = LensCodeColors.Muted, fontSize = 13.sp)
                } else {
                    LazyColumn {
                        items(terminals) { pane ->
                            val isSelected = pane.terminalId == selected?.terminalId
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(if (isSelected) LensCodeColors.DockActiveGradTop else Color.Transparent)
                                    .clickable { onSelect(pane) }
                                    .padding(8.dp),
                            ) {
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        StatusDot(terminalActivityColor(pane.agentStatus), size = 6.dp)
                                        Spacer(Modifier.width(6.dp))
                                        Text(pane.title, color = LensCodeColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Text(pane.cwd, color = LensCodeColors.Muted, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.fillMaxSize()) {
                if (selected != null) {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(selected.title, color = LensCodeColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Row {
                            Text(
                                "Stop",
                                color = LensCodeColors.Amber,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { onRequestStop(selected) },
                            )
                            Spacer(Modifier.width(16.dp))
                            Text(
                                "Close",
                                color = LensCodeColors.Red,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { onClose(selected) },
                            )
                        }
                    }
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(3.dp))
                        .background(LensCodeColors.CardBg)
                        .padding(14.dp),
                ) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        if (selected == null) {
                            Text("Select a terminal.", color = LensCodeColors.Muted, fontSize = 13.sp)
                        } else {
                            Text(
                                output ?: "…loading…",
                                color = LensCodeColors.Text,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
    }
    // Ctrl+C is never sent without this card (spec §20, §57) — Stop always
    // routes here first regardless of how "sure" the click looked.
    if (stopConfirm != null) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xAA000000)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .width(360.dp)
                    .glassFinish(PanelCorner)
                    .padding(20.dp),
            ) {
                Column {
                    Text("Stop this process?", color = LensCodeColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Sends Ctrl+C to ${stopConfirm.processLabel} in ${stopConfirm.pane.title}.",
                        color = LensCodeColors.Muted,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Cancel",
                            color = LensCodeColors.Muted,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable(onClick = stopConfirm.onCancel).padding(8.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Stop process",
                            color = LensCodeColors.Amber,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable(onClick = stopConfirm.onConfirm).padding(8.dp),
                        )
                    }
                }
            }
        }
    }
    }
}

/**
 * Terminal list status dot (docs/paseo-migration-plan.md, Phase 1) — Paseo's
 * per-terminal activity state (idle/working/attention), not the old Herdr
 * "focused" flag, which has no server-side equivalent anymore.
 */
private fun terminalActivityColor(activity: String?): Color = when (activity) {
    "working" -> LensCodeColors.Green
    "attention" -> LensCodeColors.Amber
    else -> LensCodeColors.Idle
}
