package dev.aura.xr

import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.xr.compose.platform.requestFullSpace
import kotlinx.coroutines.launch
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
import androidx.xr.compose.subspace.layout.padding
import androidx.xr.compose.subspace.layout.width
import dev.aura.xr.net.BridgeClient
import dev.aura.xr.ui.AgentChipContent
import dev.aura.xr.ui.AuraColors
import dev.aura.xr.ui.DockButtonContent
import dev.aura.xr.ui.PrimaryPanelContent

class MainActivity : ComponentActivity() {

    private lateinit var bridge: BridgeClient

    override fun onCreate(savedInstanceState: Bundle?) {
        // All UI lives inside Subspace, which SceneCore renders through its own
        // spatial compositor rather than the Activity's normal window content
        // view — so the system's default splash icon never gets a "first
        // frame drawn" signal to dismiss itself, and sits on screen forever.
        // Dismiss it immediately; there is no real loading gate to wait on.
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { false }

        super.onCreate(savedInstanceState)

        // The Activity's own 2D window is separate from Subspace content and
        // is opaque black by default — without this it renders as a solid
        // black panel with the launcher icon behind the spatial panels.
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(AndroidColor.TRANSPARENT))

        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?: "android-xr-dev"
        // 10.0.2.2 = host loopback from the Android emulator (aura-bridge).
        bridge = BridgeClient(lifecycleScope, "ws://10.0.2.2:8137", deviceId)
        bridge.connect()

        setContent { AuraWorkspace(bridge) }

        // The manifest's FULL_SPACE_MANAGED start mode only sets the default
        // launch mode — actually entering full space (so SpatialPanel content
        // attaches to a real scene node instead of the system Home
        // environment staying HOME_VISIBLE) requires this explicit call.
        lifecycleScope.launch { requestFullSpace() }
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
            // Fixed like every other panel in the layout — no movable()/
            // resizable() so the whole workspace stays locked together.
            SpatialPanel(
                SubspaceModifier
                    .width(900.dp)
                    .height(520.dp)
                    .padding(12.dp),
            ) {
                PrimaryPanelContent(state)
            }

            // ── Quick dock (icons match the mockup's dock glyphs) ───────────
            SpatialRow {
                for (
                dock in listOf(
                    Triple("▣", "Code", "code"),
                    Triple("▤", "Terminal", "terminal"),
                    Triple("±", "Diff", "diff"),
                    Triple("⑂", "Git", "git"),
                    Triple("◎", "Agents", "agents"),
                    Triple("▥", "Remote", "remote"),
                )
                ) {
                    val (icon, label, id) = dock
                    SpatialPanel(
                        SubspaceModifier
                            .width(150.dp)
                            .height(70.dp)
                            .padding(6.dp),
                    ) {
                        DockButtonContent(icon, label, active = activeView == id) { activeView = id }
                    }
                }
            }
        }
    }
}
