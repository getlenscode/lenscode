package dev.aura.xr

import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialColumn
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.SpatialRow
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.movable
import androidx.xr.compose.subspace.layout.padding
import androidx.xr.compose.subspace.layout.resizable
import androidx.xr.compose.subspace.layout.width
import dev.aura.xr.net.BridgeClient
import dev.aura.xr.ui.AgentChipContent
import dev.aura.xr.ui.AuraColors
import dev.aura.xr.ui.DockButtonContent
import dev.aura.xr.ui.PrimaryPanelContent

class MainActivity : ComponentActivity() {

    private lateinit var bridge: BridgeClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?: "android-xr-dev"
        // 10.0.2.2 = host loopback from the Android emulator (aura-bridge).
        bridge = BridgeClient(lifecycleScope, "ws://10.0.2.2:8137", deviceId)
        bridge.connect()

        setContent { AuraWorkspace(bridge) }
    }

    override fun onDestroy() {
        super.onDestroy()
        bridge.close()
    }
}

/**
 * Spatial layout mirroring the mockup (spec §71): agent strip on top, one
 * dominant primary panel, quick dock underneath.
 */
@Composable
fun AuraWorkspace(bridge: BridgeClient) {
    val state by bridge.state.collectAsState()
    var activeView by remember { mutableStateOf("code") }

    Subspace {
        SpatialColumn {
            // ── Agent strip ────────────────────────────────────────────────
            SpatialRow {
                for (
                agent in listOf(
                    Triple("Claude", "Working", AuraColors.Green),
                    Triple("Codex", "Testing", AuraColors.Green),
                    Triple("Hermes", "Idle", AuraColors.Idle),
                    Triple("Pi", "Approval", AuraColors.Amber),
                )
                ) {
                    SpatialPanel(
                        SubspaceModifier
                            .width(220.dp)
                            .height(110.dp)
                            .padding(8.dp),
                    ) {
                        AgentChipContent(agent.first, agent.second, agent.third)
                    }
                }
            }

            // ── Primary panel ──────────────────────────────────────────────
            SpatialPanel(
                SubspaceModifier
                    .width(900.dp)
                    .height(520.dp)
                    .padding(12.dp)
                    .movable()
                    .resizable(),
            ) {
                PrimaryPanelContent(state)
            }

            // ── Quick dock ─────────────────────────────────────────────────
            SpatialRow {
                for (view in listOf("Code", "Terminal", "Diff", "Git", "Agents", "Remote")) {
                    val id = view.lowercase()
                    SpatialPanel(
                        SubspaceModifier
                            .width(150.dp)
                            .height(70.dp)
                            .padding(6.dp),
                    ) {
                        DockButtonContent(view, active = activeView == id) { activeView = id }
                    }
                }
            }
        }
    }
}
