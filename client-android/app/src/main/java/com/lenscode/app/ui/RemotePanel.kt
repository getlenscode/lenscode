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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lenscode.app.net.BridgeClient

/**
 * Remote view: real SSH host aliases from `~/.ssh/config` (spec §36). Only
 * ever a bare alias — no IP, user, or key path ever leaves the bridge, and
 * any alias that was itself an IP address is filtered out server-side since
 * there'd be no other label to show. Connect opens a real SSH tunnel to that
 * machine's own Herdr socket (spec §37); once connected, its terminals are
 * reachable the same way local ones are, just scoped to that machine id.
 */
@Composable
fun RemotePanelContent(
    machines: List<com.lenscode.app.net.MachineInfo>,
    connectingMachineId: String?,
    connectError: String?,
    onConnect: (String) -> Unit,
    onDisconnect: (String) -> Unit,
    onViewTerminals: (String) -> Unit,
    onBrowse: (String) -> Unit,
) {
    GlassSurface {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Eyebrow("REMOTE · SSH MACHINES")
            Spacer(Modifier.height(4.dp))
            Text(
                "From ~/.ssh/config, tunneled straight to that machine's own Herdr.",
                color = LensCodeColors.Muted,
                fontSize = 12.sp,
            )
            if (connectError != null) {
                Spacer(Modifier.height(6.dp))
                Text(connectError, color = LensCodeColors.Amber, fontSize = 12.sp)
            }
            Spacer(Modifier.height(14.dp))
            if (machines.isEmpty()) {
                Text("No SSH hosts found.", color = LensCodeColors.Muted, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Add a Host entry to ~/.ssh/config to see it here.",
                    color = LensCodeColors.Muted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(machines) { machine ->
                        val isConnecting = connectingMachineId == machine.id
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            StatusDot(if (machine.connected) LensCodeColors.Green else LensCodeColors.Idle, size = 7.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                machine.label,
                                color = LensCodeColors.Text,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            when {
                                isConnecting -> Text("Connecting…", color = LensCodeColors.Muted, fontSize = 12.sp)
                                machine.connected -> Row {
                                    Text(
                                        "Browse",
                                        color = LensCodeColors.Blue,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.clickable { onBrowse(machine.id) },
                                    )
                                    Spacer(Modifier.width(14.dp))
                                    Text(
                                        "Terminals",
                                        color = LensCodeColors.Cyan,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.clickable { onViewTerminals(machine.id) },
                                    )
                                    Spacer(Modifier.width(14.dp))
                                    Text(
                                        "Disconnect",
                                        color = LensCodeColors.Muted,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.clickable { onDisconnect(machine.id) },
                                    )
                                }
                                else -> Text(
                                    "Connect",
                                    color = LensCodeColors.Blue,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable { onConnect(machine.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Remote folder browser (spec §37) — appears right after Connect so picking
 * a folder is the natural next step, not a separate hunt. "Open" re-roots
 * both Code and a fresh Terminal at whatever directory is on screen when
 * tapped, so what you see here is exactly what you get.
 */
@Composable
fun RemoteBrowseOverlay(
    state: com.lenscode.app.net.RemoteBrowseState?,
    onNavigate: (String) -> Unit,
    onUp: () -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (state == null) return
    Box(
        Modifier.fillMaxSize().background(Color(0xAA000000)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(460.dp)
                .heightIn(max = 420.dp)
                .glassFinish(PanelCorner)
                .clickable(enabled = false) {}
                .padding(20.dp),
        ) {
            Column {
                Text(
                    "Open folder on ${state.machineLabel}",
                    color = LensCodeColors.Text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    state.path,
                    color = LensCodeColors.Muted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onUp).padding(vertical = 5.dp),
                ) {
                    Text("↑ ..", color = LensCodeColors.Blue, fontSize = 13.sp)
                }
                Box(Modifier.heightIn(min = 80.dp, max = 220.dp)) {
                    if (state.loading) {
                        Text("Loading…", color = LensCodeColors.Muted, fontSize = 13.sp)
                    } else if (state.entries.none { it.isDir }) {
                        Text("No subfolders here.", color = LensCodeColors.Muted, fontSize = 13.sp)
                    } else {
                        LazyColumn {
                            items(state.entries.filter { it.isDir }) { entry ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val next = if (state.path.endsWith("/")) "${state.path}${entry.name}" else "${state.path}/${entry.name}"
                                            onNavigate(next)
                                        }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("📁", fontSize = 13.sp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(entry.name, color = LensCodeColors.Text, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Cancel",
                        color = LensCodeColors.Muted,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(onClick = onDismiss),
                    )
                    Text(
                        "Open this folder",
                        color = LensCodeColors.Blue,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(onClick = onOpen),
                    )
                }
            }
        }
    }
}
