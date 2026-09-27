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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.mutableIntStateOf
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import androidx.compose.foundation.text.KeyboardOptions
import com.lenscode.app.net.AgentInfo
import com.lenscode.app.net.BridgeClient
import com.lenscode.app.net.DirEntry
import com.lenscode.app.net.FileContent
import com.lenscode.app.net.PaneInfo
import com.lenscode.app.net.PairingState
import com.lenscode.app.net.VoiceResolution

/**
 * Spatial glass surfaces share restrained corners and reflective edge lighting.
 * Reflections sit behind content so text and syntax colors stay crisp.
 */

private val PanelCorner = LensCodeCorners.Panel
private val CardCorner = LensCodeCorners.Card
private val DockCorner = LensCodeCorners.Control

/** Cache reflection brushes at the panel's size instead of allocating each frame. */
private fun Modifier.glassFinish(corner: Dp, compact: Boolean = false): Modifier {
    val shape = RoundedCornerShape(corner)
    return clip(shape)
        .background(Brush.verticalGradient(
            if (compact) listOf(LensCodeColors.CardGradTop, LensCodeColors.CardBg)
            else listOf(LensCodeColors.PanelGradTop, LensCodeColors.PanelGradBottom),
        ))
        .drawWithCache {
            val glow = Brush.radialGradient(
                colors = listOf(LensCodeColors.PanelGlow, Color.Transparent),
                center = Offset(size.width * 0.12f, 0f),
                radius = (size.width * 0.65f).coerceAtLeast(1f),
            )
            val sheen = Brush.linearGradient(
                colorStops = arrayOf(
                    0f to LensCodeColors.SheenWhite,
                    0.34f to Color.Transparent,
                    0.76f to Color.Transparent,
                    1f to LensCodeColors.SheenBlue,
                ),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            )
            onDrawBehind {
                drawRect(glow)
                drawRect(sheen, blendMode = BlendMode.Screen)
            }
        }
        .border(
            1.dp,
            Brush.linearGradient(
                0f to LensCodeColors.PanelBorderTop,
                0.4f to LensCodeColors.CardBorder,
                0.75f to LensCodeColors.PanelBorder,
                1f to LensCodeColors.CardBorder,
            ),
            shape,
        )
}

/** The .panel style: gradient fill + corner glow + screen-blend sheen + top-bright border. */
@Composable
fun GlassSurface(corner: Dp = PanelCorner, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().glassFinish(corner),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** Compact glass for floating agent cards. */
@Composable
fun CardSurface(corner: Dp = CardCorner, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().glassFinish(corner, compact = true),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
fun Eyebrow(text: String) {
    Text(text, color = LensCodeColors.Eyebrow, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
}

@Composable
fun StatusDot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

@Composable
fun PrimaryPanelContent(
    state: BridgeClient.UiState,
    onResetLayout: () -> Unit = {},
    pinned: Boolean = false,
    onTogglePin: () -> Unit = {},
    onBringCloser: () -> Unit = {},
    onPushFarther: () -> Unit = {},
    followMeEnabled: Boolean = false,
    onToggleFollowMe: () -> Unit = {},
    onEnterPip: () -> Unit = {},
    pairingState: PairingState = PairingState.Authenticated,
    deviceName: String = "",
    onDeviceNameChange: (String) -> Unit = {},
    onBeginPairing: () -> Unit = {},
    pairingCode: String = "",
    onPairingCodeChange: (String) -> Unit = {},
    onSubmitPairingCode: () -> Unit = {},
    onRetryPairing: () -> Unit = {},
    weather: com.lenscode.app.net.WeatherReading? = null,
    weatherLocationDenied: Boolean = false,
    /** Which workspace the file browser is currently rooted at — e.g. "local" or a worktree/branch name. */
    projectLabel: String = "local",
    /** "local" or a connected remote machine's SSH host alias — never a raw IP (spec §54/§36). */
    projectMachineId: String = "local",
    /** Absolute path on whichever machine [projectMachineId] refers to. Null until the first dir.list completes. */
    projectRootPath: String? = null,
) {
    GlassSurface {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Eyebrow("LENSCODE")
            Spacer(Modifier.height(18.dp))
            if (state.bridgeConnected && pairingState != PairingState.Authenticated) {
                PairingFlowContent(
                    pairingState,
                    deviceName,
                    onDeviceNameChange,
                    onBeginPairing,
                    pairingCode,
                    onPairingCodeChange,
                    onSubmitPairingCode,
                    onRetryPairing,
                )
            } else if (!state.bridgeConnected) {
                Text("Connection lost.", color = LensCodeColors.Amber, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Agents and terminals may still be running.\nReconnecting…",
                    color = LensCodeColors.Muted,
                    fontSize = 15.sp,
                )
            } else {
                val (batteryPercent, batteryCharging) = rememberBatteryState()
                HomeHud(
                    batteryPercent = batteryPercent,
                    batteryCharging = batteryCharging,
                    weather = weather,
                    weatherLocationDenied = weatherLocationDenied,
                )
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(LensCodeColors.Green)
                    Spacer(Modifier.width(6.dp))
                    Text("bridge connected", color = LensCodeColors.Text, fontSize = 16.sp)
                    Spacer(Modifier.width(20.dp))
                    StatusDot(if (state.backendConnected) LensCodeColors.Green else LensCodeColors.Red)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (state.backendConnected) "paseo connected" else "paseo offline",
                        color = LensCodeColors.Text,
                        fontSize = 16.sp,
                    )
                    Spacer(Modifier.width(20.dp))
                    // This XR device's own battery — not any Paseo host's.
                    BatteryIndicator()
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "workspaces ${state.workspaces} · panes ${state.panes} · agents ${state.agents}",
                    color = LensCodeColors.Muted,
                    fontSize = 15.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text("last event: ${state.lastEvent}", color = LensCodeColors.Muted, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                Text(
                    "project: $projectLabel  ·  machine: $projectMachineId",
                    color = LensCodeColors.Muted,
                    fontSize = 12.sp,
                )
                if (projectRootPath != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        projectRootPath,
                        color = LensCodeColors.Eyebrow,
                        fontSize = 11.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                }
                Spacer(Modifier.height(20.dp))
                // Panel layout controls (spec §52): pin locks move/resize off,
                // bring-closer/push-farther nudge depth, reset clears everything
                // back to defaults. Position/size/pin all persist across restarts.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (pinned) "📌 Pinned" else "📌 Pin",
                        color = if (pinned) LensCodeColors.Green else LensCodeColors.Eyebrow,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(onClick = onTogglePin),
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        "◀ Closer",
                        color = LensCodeColors.Eyebrow,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(onClick = onBringCloser),
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        "Farther ▶",
                        color = LensCodeColors.Eyebrow,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(onClick = onPushFarther),
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        "⟲ Reset Layout",
                        color = LensCodeColors.Eyebrow,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(onClick = onResetLayout),
                    )
                    Spacer(Modifier.width(16.dp))
                    // Jetpack XR's app-side equivalent of the pin-to-headset
                    // button a Home Space window (e.g. Chrome) gets from the OS —
                    // this is a full-space app rendering its own panels, so it
                    // never gets that system button automatically. Requires the
                    // HEAD_TRACKING runtime permission — first tap prompts for it.
                    Text(
                        if (followMeEnabled) "🧭 Following" else "🧭 Follow Me",
                        color = if (followMeEnabled) LensCodeColors.Green else LensCodeColors.Eyebrow,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(onClick = onToggleFollowMe),
                    )
                    Spacer(Modifier.width(16.dp))
                    // Experiment: standard Android picture-in-picture — confirmed
                    // live it has no visible effect for this app (see MainActivity's
                    // onEnterPip doc comment) — kept only in case a future Android
                    // XR revision changes how PiP interacts with Full Space.
                    Text(
                        "📺 PiP",
                        color = LensCodeColors.Eyebrow,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(onClick = onEnterPip),
                    )
                }
            }
        }
    }
}

/**
 * Device pairing flow (spec §55). Only ever shown when the bridge is running
 * in prod mode and this device hasn't paired yet — dev mode auto-authenticates
 * and never surfaces this. The 6-digit code itself is intentionally never
 * shown here: it's displayed on the bridge's own console/desktop, and the
 * human reads it across and types (or speaks) it in here — the code is never
 * sent to an unauthenticated device over the network.
 */
@Composable
private fun PairingFlowContent(
    pairingState: PairingState,
    deviceName: String,
    onDeviceNameChange: (String) -> Unit,
    onBeginPairing: () -> Unit,
    pairingCode: String,
    onPairingCodeChange: (String) -> Unit,
    onSubmitPairingCode: () -> Unit,
    onRetryPairing: () -> Unit,
) {
    Text("Pairing required", color = LensCodeColors.Amber, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(10.dp))
    when (pairingState) {
        is PairingState.NeedsPairing -> {
            Text(
                "This device isn't paired with lenscode-bridge yet.",
                color = LensCodeColors.Muted,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(16.dp))
            PairingTextField(deviceName, onDeviceNameChange, "Device name", onSubmit = onBeginPairing)
            Spacer(Modifier.height(12.dp))
            Text(
                "Start Pairing",
                color = LensCodeColors.Blue,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .clickable(onClick = onBeginPairing)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        is PairingState.CodeSent -> {
            Text(
                "A pairing code is now shown on the bridge's console.\nEnter it below to finish pairing “${pairingState.deviceName}”.",
                color = LensCodeColors.Muted,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(16.dp))
            PairingTextField(pairingCode, onPairingCodeChange, "6-digit code", onSubmit = onSubmitPairingCode)
            Spacer(Modifier.height(12.dp))
            Text(
                "Submit Code",
                color = LensCodeColors.Blue,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .clickable(onClick = onSubmitPairingCode)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        is PairingState.Failed -> {
            Text(pairingState.message, color = LensCodeColors.Red, fontSize = 14.sp)
            Spacer(Modifier.height(16.dp))
            PairingTextField(pairingCode, onPairingCodeChange, "6-digit code", onSubmit = onSubmitPairingCode)
            Spacer(Modifier.height(12.dp))
            Row {
                Text(
                    "Retry",
                    color = LensCodeColors.Blue,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .clickable(onClick = onSubmitPairingCode)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "Start Over",
                    color = LensCodeColors.Muted,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .clickable(onClick = onRetryPairing)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        PairingState.Authenticated -> {} // never reached — caller gates on this
    }
}

@Composable
private fun PairingTextField(value: String, onValueChange: (String) -> Unit, placeholder: String, onSubmit: () -> Unit) {
    Box(
        Modifier
            .width(220.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(VsCodeColors.EditorBg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (value.isEmpty()) {
            Text(placeholder, color = LensCodeColors.Muted, fontSize = 14.sp)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(color = LensCodeColors.Text, fontSize = 14.sp),
            singleLine = true,
            cursorBrush = androidx.compose.ui.graphics.SolidColor(LensCodeColors.Text),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun AgentChipContent(name: String, stateLabel: String, dotColor: Color, kind: String = name) {
    CardSurface {
        Column(
            Modifier.fillMaxSize().padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // .logo circular badge: bg #0b1119, border #2c3b4a, per-agent accent color.
            // Accent is looked up by kind (e.g. "claude"), not the user's custom
            // display name — those rarely match one of the known providers.
            Box(
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(DockCorner))
                    .background(LensCodeColors.LogoBg)
                    .border(1.dp, LensCodeColors.LogoBorder, RoundedCornerShape(DockCorner)),
                contentAlignment = Alignment.Center,
            ) {
                Text(name.take(1), color = LensCodeColors.agentAccent(kind), fontWeight = FontWeight.Black, fontSize = 14.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text(name, color = LensCodeColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(dotColor, size = 6.dp)
                Spacer(Modifier.width(4.dp))
                Text(stateLabel, color = LensCodeColors.Muted, fontSize = 11.sp)
            }
        }
    }
}

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
 * Code view styled like VS Code: an Explorer sidebar, a tab bar for the open
 * file, a breadcrumb showing its full path, and a gutter + syntax-highlighted
 * editor pane. Backed by real dir.list / file.open results from lenscode-bridge.
 */
@Composable
fun FilesPanelContent(
    rootLabel: String,
    path: String,
    entries: List<DirEntry>,
    file: FileContent?,
    onOpenDir: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onUp: () -> Unit,
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    searchResults: List<com.lenscode.app.net.FileSearchHit> = emptyList(),
) {
    Box(
        Modifier.fillMaxSize().glassFinish(PanelCorner),
    ) {
        Row(Modifier.fillMaxSize()) {
            // ── Explorer sidebar ─────────────────────────────────────────
            Column(
                Modifier
                    .width(220.dp)
                    .fillMaxSize()
                    .background(VsCodeColors.SidebarBg)
                    .padding(vertical = 10.dp),
            ) {
                Text(
                    "EXPLORER",
                    color = VsCodeColors.Breadcrumb,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
                Text(
                    rootLabel,
                    color = VsCodeColors.LineNumberActive,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                )
                Spacer(Modifier.height(8.dp))
                // Typed file search (spec §13 back-end) — separate from voice
                // search: a plain results list, no confidence gating, never
                // auto-opens anything.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(VsCodeColors.EditorBg)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    if (searchQuery.isEmpty()) {
                        Text("🔍 Search files…", color = VsCodeColors.Breadcrumb, fontSize = 12.sp)
                    }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        textStyle = TextStyle(color = VsCodeColors.Plain, fontSize = 12.sp),
                        singleLine = true,
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(VsCodeColors.Plain),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (searchQuery.isNotEmpty()) {
                    if (searchResults.isEmpty()) {
                        Text(
                            "No matches.",
                            color = VsCodeColors.Breadcrumb,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    } else {
                        LazyColumn {
                            items(searchResults) { hit ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { onOpenFile(hit.path) }
                                        .padding(horizontal = 14.dp, vertical = 5.dp),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(if (hit.kind == "text") "🔎" else "📄", fontSize = 12.sp)
                                        Spacer(Modifier.width(6.dp))
                                        Text(hit.path, color = VsCodeColors.Plain, fontSize = 13.sp)
                                    }
                                    if (hit.preview != null) {
                                        Text(
                                            hit.preview.trim().take(60) + (hit.line?.let { " · L$it" } ?: ""),
                                            color = VsCodeColors.Breadcrumb,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.padding(start = 18.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onUp)
                            .padding(horizontal = 14.dp, vertical = 5.dp),
                    ) {
                        Text("↑ ..", color = VsCodeColors.Breadcrumb, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    }
                    if (entries.isEmpty()) {
                        Text(
                            "Empty.",
                            color = VsCodeColors.Breadcrumb,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    } else {
                        LazyColumn {
                            items(entries) { entry ->
                                val isOpen = !entry.isDir && file != null &&
                                    file.path == if (path == ".") entry.name else "$path/${entry.name}"
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(if (isOpen) VsCodeColors.HoverRow else Color.Transparent)
                                        .clickable {
                                            val next = if (path == ".") entry.name else "$path/${entry.name}"
                                            if (entry.isDir) onOpenDir(next) else onOpenFile(next)
                                        }
                                        .padding(horizontal = 14.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(if (entry.isDir) "📁" else "📄", fontSize = 12.sp)
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        entry.name,
                                        color = if (isOpen) VsCodeColors.LineNumberActive else VsCodeColors.Plain,
                                        fontSize = 13.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── Editor ───────────────────────────────────────────────────
            Column(Modifier.fillMaxSize()) {
                if (file == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Select a file to open it.", color = VsCodeColors.Breadcrumb, fontSize = 14.sp)
                    }
                } else {
                    val fileName = file.path.substringAfterLast('/')
                    // Tab bar — one active tab, matching VS Code's tab strip.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(VsCodeColors.TabBarBg),
                    ) {
                        Column {
                            Row(
                                Modifier
                                    .background(VsCodeColors.ActiveTabBg)
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("📄", fontSize = 12.sp)
                                Spacer(Modifier.width(6.dp))
                                Text(fileName, color = VsCodeColors.Plain, fontSize = 13.sp)
                            }
                            Box(Modifier.fillMaxWidth().height(2.dp).background(VsCodeColors.ActiveTabAccent))
                        }
                    }
                    // Breadcrumb — the full open-file path, VS Code style.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(VsCodeColors.EditorBg)
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        val segments = file.path.split("/").filter { it.isNotEmpty() }
                        segments.forEachIndexed { i, segment ->
                            if (i > 0) {
                                Text(" › ", color = VsCodeColors.Breadcrumb, fontSize = 12.sp)
                            }
                            Text(
                                segment,
                                color = if (i == segments.lastIndex) VsCodeColors.LineNumberActive else VsCodeColors.Breadcrumb,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(VsCodeColors.Border))

                    // Gutter + syntax-highlighted source, one row per line.
                    val annotated = remember(file.content, file.language) { highlightCode(file.content, file.language) }
                    val lines = remember(file.content) { file.content.split("\n") }
                    val annotatedLines = remember(annotated, lines) {
                        var offset = 0
                        lines.map { line ->
                            val end = (offset + line.length).coerceAtMost(annotated.length)
                            val slice = if (offset <= end) annotated.subSequence(offset, end) else AnnotatedString("")
                            offset = end + 1
                            slice
                        }
                    }

                    // Find-in-file (spec §15) — purely client-side, the file's
                    // already fully loaded; no bridge round-trip needed.
                    var findQuery by remember(file.path) { mutableStateOf("") }
                    var findIndex by remember(file.path) { mutableStateOf(0) }
                    val findMatches = remember(findQuery, lines) {
                        if (findQuery.isBlank()) emptyList()
                        else lines.indices.filter { lines[it].contains(findQuery, ignoreCase = true) }
                    }
                    LaunchedEffect(findMatches) { findIndex = 0 }
                    val listState = rememberLazyListState()
                    LaunchedEffect(findIndex, findMatches) {
                        findMatches.getOrNull(findIndex)?.let { listState.scrollToItem(it, scrollOffset = -200) }
                    }
                    fun jump(delta: Int) {
                        if (findMatches.isEmpty()) return
                        findIndex = ((findIndex + delta) % findMatches.size + findMatches.size) % findMatches.size
                    }

                    // Symbol outline (spec §15) — same regex-based, no-parser
                    // spirit as highlightCode; jumps scroll the same LazyColumn
                    // find-in-file already uses.
                    val symbols = remember(file.content, file.language) { extractSymbols(file.content, file.language) }
                    var showOutline by remember(file.path) { mutableStateOf(false) }
                    var focusSymbol by remember(file.path) { mutableStateOf<CodeSymbol?>(null) }
                    val editorScope = rememberCoroutineScope()

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(VsCodeColors.EditorBg)
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .width(200.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(VsCodeColors.SidebarBg)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            if (findQuery.isEmpty()) {
                                Text("🔎 Find in file…", color = VsCodeColors.Breadcrumb, fontSize = 12.sp)
                            }
                            BasicTextField(
                                value = findQuery,
                                onValueChange = { findQuery = it },
                                textStyle = TextStyle(color = VsCodeColors.Plain, fontSize = 12.sp),
                                singleLine = true,
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(VsCodeColors.Plain),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { jump(1) }),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (findQuery.isNotEmpty()) {
                            Spacer(Modifier.width(10.dp))
                            Text(
                                if (findMatches.isEmpty()) "no matches" else "${findIndex + 1}/${findMatches.size}",
                                color = VsCodeColors.Breadcrumb,
                                fontSize = 12.sp,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("▲", color = LensCodeColors.Blue, fontSize = 13.sp, modifier = Modifier.clickable { jump(-1) })
                            Spacer(Modifier.width(10.dp))
                            Text("▼", color = LensCodeColors.Blue, fontSize = 13.sp, modifier = Modifier.clickable { jump(1) })
                        }
                        Spacer(Modifier.weight(1f))
                        if (symbols.isNotEmpty()) {
                            Text(
                                "🗂 Outline (${symbols.size})",
                                color = if (showOutline) LensCodeColors.Blue else VsCodeColors.Breadcrumb,
                                fontSize = 12.sp,
                                modifier = Modifier.clickable { showOutline = !showOutline },
                            )
                        }
                    }

                    val currentMatchLine = findMatches.getOrNull(findIndex)
                    Box(Modifier.fillMaxSize()) {
                        LazyColumn(Modifier.fillMaxSize().padding(vertical = 8.dp), state = listState) {
                            itemsIndexed(annotatedLines) { idx, lineText ->
                                val isMatch = idx in findMatches
                                val isCurrent = idx == currentMatchLine
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(
                                            when {
                                                isCurrent -> Color(0x552F81F7)
                                                isMatch -> Color(0x33F0C040)
                                                else -> Color.Transparent
                                            },
                                        ),
                                ) {
                                    Text(
                                        (idx + 1).toString(),
                                        color = VsCodeColors.LineNumber,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.width(44.dp).padding(end = 12.dp),
                                    )
                                    Text(lineText, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                        if (showOutline) {
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 4.dp, end = 8.dp)
                                    .width(260.dp)
                                    .heightIn(max = 320.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(VsCodeColors.SidebarBg)
                                    .border(1.dp, VsCodeColors.Border, RoundedCornerShape(3.dp))
                                    .padding(vertical = 6.dp),
                            ) {
                                LazyColumn {
                                    items(symbols) { sym ->
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    showOutline = false
                                                    editorScope.launch { listState.scrollToItem(sym.line, scrollOffset = -100) }
                                                }
                                                .padding(horizontal = 10.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                symbolKindIcon(sym.kind),
                                                fontSize = 11.sp,
                                                modifier = Modifier.width(20.dp),
                                            )
                                            Text(
                                                sym.name,
                                                color = VsCodeColors.Plain,
                                                fontSize = 13.sp,
                                                maxLines = 1,
                                                modifier = Modifier.weight(1f),
                                            )
                                            Text(
                                                "${sym.line + 1}",
                                                color = VsCodeColors.Breadcrumb,
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            // Focus card (spec §16) — isolates just this symbol's
                                            // body, separate from "jump to it in the full file"
                                            // (clicking the row itself).
                                            Text(
                                                "⤢",
                                                color = LensCodeColors.Blue,
                                                fontSize = 13.sp,
                                                modifier = Modifier.clickable {
                                                    showOutline = false
                                                    focusSymbol = sym
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (focusSymbol != null) {
                            val range = remember(focusSymbol, lines, file.language) {
                                symbolBodyRange(lines, focusSymbol!!, file.language)
                            }
                            Box(
                                Modifier.fillMaxSize().background(Color(0xDD000000)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(
                                    Modifier
                                        .fillMaxWidth(0.92f)
                                        .fillMaxHeight(0.88f)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(VsCodeColors.EditorBg)
                                        .border(1.dp, VsCodeColors.Border, RoundedCornerShape(3.dp))
                                        .padding(16.dp),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(symbolKindIcon(focusSymbol!!.kind), fontSize = 15.sp)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            focusSymbol!!.name,
                                            color = VsCodeColors.Plain,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "${focusSymbol!!.kind} · L${range.first + 1}–${range.last + 1}",
                                            color = VsCodeColors.Breadcrumb,
                                            fontSize = 12.sp,
                                        )
                                        Spacer(Modifier.weight(1f))
                                        Text(
                                            "✕ Close",
                                            color = LensCodeColors.Muted,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.clickable { focusSymbol = null },
                                        )
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    Box(Modifier.fillMaxWidth().height(1.dp).background(VsCodeColors.Border))
                                    Spacer(Modifier.height(6.dp))
                                    LazyColumn(Modifier.fillMaxSize()) {
                                        items((range.first..range.last).toList()) { idx ->
                                            Row(Modifier.fillMaxWidth()) {
                                                Text(
                                                    (idx + 1).toString(),
                                                    color = VsCodeColors.LineNumber,
                                                    fontSize = 12.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    modifier = Modifier.width(44.dp).padding(end = 12.dp),
                                                )
                                                Text(annotatedLines[idx], fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (file.truncated) {
                        Text(
                            "(truncated — file is larger than the preview limit)",
                            color = LensCodeColors.Amber,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Which Paseo permission mode voice's ask_agent creates new agents with
 * (docs/paseo-migration-plan.md follow-up — confirmed live: an agent left on
 * Paseo's own "default" mode blocks forever on its first tool call's
 * permission request, since nothing in a hands-free voice flow ever answers
 * it). "manual" keeps that default deliberately — see the app's Permissions
 * queue and AskAgentRespondPermission for how to answer one when picked.
 */
private val VoiceAgentModes = listOf(
    "auto" to "Auto",
    "bypass" to "Bypass",
    "manual" to "Manual",
)

@Composable
private fun VoiceAgentModeSelector(selected: String, onSelect: (String) -> Unit) {
    Column {
        Text("NEW AGENT PERMISSIONS (VOICE)", color = LensCodeColors.Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((id, label) in VoiceAgentModes) {
                val active = id == selected
                Text(
                    label,
                    color = if (active) LensCodeColors.Text else LensCodeColors.Muted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (active) LensCodeColors.DockActiveGradTop else LensCodeColors.CardBg)
                        .border(1.dp, if (active) LensCodeColors.DockActiveBorder else LensCodeColors.CardBorder, RoundedCornerShape(3.dp))
                        .clickable { onSelect(id) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            when (selected) {
                "auto" -> "Paseo's classifier auto-approves low-risk tool calls, still asks about risky ones."
                "bypass" -> "No approval prompts at all — fully hands-free, use with caution."
                else -> "Every tool call waits for you — approve in Permissions, or by voice (\"allow\"/\"deny\")."
            },
            color = LensCodeColors.Muted,
            fontSize = 11.sp,
        )
    }
}

/** One row in the grouped agent-history list — a date divider or an entry under it. */
private sealed class HistoryRow(val rowKey: String) {
    class DateHeader(val label: String) : HistoryRow("header-$label")
    class Entry(val entry: com.lenscode.app.net.AgentHistoryEntry) : HistoryRow(entry.agentId)
}

/** "Today" / "Yesterday" / "September 18, 2026" — the session's local creation date. */
private fun formatHistoryDateLabel(createdAtIso: String): String {
    return try {
        val date = java.time.Instant.parse(createdAtIso).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        val today = java.time.LocalDate.now()
        when (date) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> date.format(java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy"))
        }
    } catch (e: Exception) {
        "Unknown date"
    }
}

/**
 * Groups already-sorted history entries under a date header per distinct day
 * (by creation date), inserting a new header only when the date changes from
 * the entry immediately before it — matches how the backend already orders
 * the list rather than re-sorting client-side.
 */
private fun buildHistoryRows(history: List<com.lenscode.app.net.AgentHistoryEntry>): List<HistoryRow> {
    val rows = mutableListOf<HistoryRow>()
    var lastLabel: String? = null
    for (entry in history) {
        val label = formatHistoryDateLabel(entry.createdAt)
        if (label != lastLabel) {
            rows.add(HistoryRow.DateHeader(label))
            lastLabel = label
        }
        rows.add(HistoryRow.Entry(entry))
    }
    return rows
}

/** Agents view: real Paseo agent.list — honestly empty until an agent is started. */
@Composable
fun AgentsPanelContent(
    agents: List<AgentInfo>,
    onSelect: (AgentInfo) -> Unit,
    onArchive: (AgentInfo) -> Unit = {},
    voiceAgentModeId: String = "auto",
    onVoiceAgentModeChange: (String) -> Unit = {},
    // Archived/closed agents (docs/snip-and-agent-management-plan.md) — Paseo
    // still has their full record even after agent.list drops them; this is
    // LensCode's equivalent of Paseo Desktop's own History view.
    history: List<com.lenscode.app.net.AgentHistoryEntry> = emptyList(),
    historyHasMore: Boolean = false,
    onShowHistory: () -> Unit = {},
    onLoadMoreHistory: () -> Unit = {},
    onSelectHistory: (com.lenscode.app.net.AgentHistoryEntry) -> Unit = {},
) {
    // Local, not lifted to the caller — this list's own close button is the
    // only thing that can open it, same as TerminalPanelContent's stopConfirm.
    var archiveTarget by remember { mutableStateOf<AgentInfo?>(null) }
    var showingHistory by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
    GlassSurface {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Eyebrow(if (showingHistory) "AGENT HISTORY" else "AGENTS")
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, LensCodeColors.Cyan.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                        .clickable {
                            showingHistory = !showingHistory
                            if (showingHistory) onShowHistory()
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        if (showingHistory) "Live" else "History",
                        color = LensCodeColors.Cyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            if (showingHistory) {
                Text(
                    "Archived and closed agents — read only.",
                    color = LensCodeColors.Muted,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(14.dp))
                if (history.isEmpty()) {
                    Text("No agent history yet.", color = LensCodeColors.Muted, fontSize = 13.sp)
                } else {
                    val rows = remember(history) { buildHistoryRows(history) }
                    LazyColumn(Modifier.fillMaxWidth()) {
                        itemsIndexed(rows, key = { index, row -> "$index-${row.rowKey}" }) { _, row ->
                            when (row) {
                                is HistoryRow.DateHeader -> Text(
                                    row.label,
                                    color = LensCodeColors.Amber,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
                                )
                                is HistoryRow.Entry -> {
                                    val entry = row.entry
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(3.dp))
                                            .clickable { onSelectHistory(entry) }
                                            .padding(vertical = 6.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        StatusDot(LensCodeColors.Muted, size = 8.dp)
                                        Spacer(Modifier.width(8.dp))
                                        Text(entry.label, color = LensCodeColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.width(8.dp))
                                        Text("(${entry.provider})", color = LensCodeColors.Eyebrow, fontSize = 12.sp)
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            entry.archivedAt?.let { "archived" } ?: entry.status,
                                            color = LensCodeColors.Muted,
                                            fontSize = 13.sp,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                        }
                        if (historyHasMore) {
                            item {
                                Text(
                                    "Load more",
                                    color = LensCodeColors.Cyan,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(onClick = onLoadMoreHistory)
                                        .padding(vertical = 10.dp),
                                )
                            }
                        }
                    }
                }
                return@Column
            }
            if (agents.isNotEmpty()) {
                Text("Tap an agent to see status and respond.", color = LensCodeColors.Muted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(14.dp))
            VoiceAgentModeSelector(voiceAgentModeId, onVoiceAgentModeChange)
            Spacer(Modifier.height(14.dp))
            if (agents.isEmpty()) {
                Text(
                    "No agents running.",
                    color = LensCodeColors.Text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Paseo reports zero active agents right now — start one\nwith `paseo run` to see it appear here.",
                    color = LensCodeColors.Muted,
                    fontSize = 13.sp,
                )
            } else {
                LazyColumn {
                    items(agents) { agent ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(3.dp))
                                .clickable { onSelect(agent) }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            StatusDot(LensCodeColors.agentStatusColor(agent.status), size = 8.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(agent.label, color = LensCodeColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            Text("(${agent.provider})", color = LensCodeColors.Eyebrow, fontSize = 12.sp)
                            Spacer(Modifier.width(10.dp))
                            Text(agent.status, color = LensCodeColors.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text(
                                "×",
                                color = LensCodeColors.Muted,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { archiveTarget = agent }.padding(horizontal = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
    // Closes the agent's runtime — its history stays in Paseo, it just can't
    // take new messages, same guarantee as the detail card's Archive action
    // (spec §57: destructive actions always confirm first).
    if (archiveTarget != null) {
        val target = archiveTarget!!
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
                    Text("Archive this agent?", color = LensCodeColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This closes ${target.label}'s runtime. Its history stays in Paseo, but it can't take new messages.",
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
                            modifier = Modifier.clickable { archiveTarget = null }.padding(8.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Archive",
                            color = LensCodeColors.Amber,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable {
                                onArchive(target)
                                archiveTarget = null
                            }.padding(8.dp),
                        )
                    }
                }
            }
        }
    }
    }
}

/**
 * Permissions queue (docs/paseo-migration-plan.md, Phase 3) — the spatial
 * "approve this tool call" card. This is the single highest-value new
 * affordance the Paseo migration adds: heads-up approval from a glasses card
 * beats reaching for a phone. Every pending request across every local agent,
 * flattened into one list (`permission.list` already spans all agents).
 */
@Composable
fun PermissionsPanelContent(
    permissions: List<com.lenscode.app.net.PermissionInfo>,
    agents: List<AgentInfo>,
    onRespond: (com.lenscode.app.net.PermissionInfo, String, String?) -> Unit,
) {
    GlassSurface {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Eyebrow("PERMISSIONS")
            Spacer(Modifier.height(4.dp))
            if (permissions.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("Nothing waiting on you.", color = LensCodeColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tool calls and other approvals an agent needs from you show up here.",
                    color = LensCodeColors.Muted,
                    fontSize = 13.sp,
                )
            } else {
                Text("Tap Allow or Deny to answer — never sent without your say.", color = LensCodeColors.Muted, fontSize = 12.sp)
                Spacer(Modifier.height(14.dp))
                LazyColumn {
                    items(permissions, key = { it.permissionRequestId }) { permission ->
                        val agentLabel = agents.find { it.agentId == permission.agentId }?.label ?: permission.agentId
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(LensCodeColors.CardBg)
                                .border(1.dp, LensCodeColors.CardBorder, RoundedCornerShape(3.dp))
                                .padding(14.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(agentLabel, color = LensCodeColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(8.dp))
                                Text("(${permission.kind})", color = LensCodeColors.Eyebrow, fontSize = 11.sp)
                            }
                            if (permission.title != null) {
                                Spacer(Modifier.height(4.dp))
                                Text(permission.title, color = LensCodeColors.Text, fontSize = 13.sp)
                            }
                            if (permission.description != null) {
                                Spacer(Modifier.height(4.dp))
                                Text(permission.description, color = LensCodeColors.Muted, fontSize = 12.sp)
                            }
                            Spacer(Modifier.height(10.dp))
                            Row {
                                if (permission.actions.isEmpty()) {
                                    Text(
                                        "Allow",
                                        color = LensCodeColors.Green,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.clickable { onRespond(permission, "allow", null) }.padding(end = 20.dp),
                                    )
                                    Text(
                                        "Deny",
                                        color = LensCodeColors.Red,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.clickable { onRespond(permission, "deny", null) },
                                    )
                                } else {
                                    for (action in permission.actions) {
                                        Text(
                                            action.label,
                                            color = if (action.behavior == "allow") LensCodeColors.Green else LensCodeColors.Red,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier
                                                .clickable { onRespond(permission, action.behavior, action.id) }
                                                .padding(end = 20.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * State for the branch-switch dirty-conflict card (spec §27 — never resolve
 * uncommitted changes silently). Shown whenever the bridge reports the
 * working tree is dirty; the user picks how to handle it.
 */
data class BranchConflictState(
    val target: String,
    val onStash: () -> Unit,
    val onWorktree: () -> Unit,
    val onCancel: () -> Unit,
)

/** Generic destructive-action confirmation (spec §57) — branch delete, worktree removal, etc. */
data class DeleteConfirmState(
    val title: String,
    val message: String,
    val onConfirm: () -> Unit,
    val onCancel: () -> Unit,
)

/** Git / Worktrees view: branch switcher up top, real Paseo worktree.list below. */
@Composable
fun GitPanelContent(
    worktrees: List<com.lenscode.app.net.WorktreeInfo>,
    branches: List<String>,
    currentBranch: String?,
    onOpenInCode: (com.lenscode.app.net.WorktreeInfo) -> Unit,
    onSwitchBranch: (String) -> Unit,
    onDeleteBranch: (String) -> Unit,
    onRemoveWorktree: (com.lenscode.app.net.WorktreeInfo) -> Unit,
    conflict: BranchConflictState?,
    deleteConfirm: DeleteConfirmState?,
) {
    Box(Modifier.fillMaxSize()) {
    GlassSurface {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Eyebrow("BRANCH" + (currentBranch?.let { " · $it" } ?: ""))
            Spacer(Modifier.height(10.dp))
            if (branches.isEmpty()) {
                Text("No branches found.", color = LensCodeColors.Muted, fontSize = 13.sp)
            } else {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    for (branch in branches) {
                        val isCurrent = branch == currentBranch
                        Row(
                            Modifier
                                .padding(end = 8.dp)
                                .clip(RoundedCornerShape(CardCorner))
                                .background(if (isCurrent) LensCodeColors.DockActiveGradTop else LensCodeColors.CardBg)
                                .border(
                                    1.dp,
                                    if (isCurrent) LensCodeColors.DockActiveBorder else LensCodeColors.CardBorder,
                                    RoundedCornerShape(CardCorner),
                                )
                                .clickable(enabled = !isCurrent) { onSwitchBranch(branch) }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                branch,
                                color = if (isCurrent) LensCodeColors.ActiveDock else LensCodeColors.Text,
                                fontSize = 13.sp,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            )
                            // Never the current branch — git itself refuses that anyway.
                            if (!isCurrent) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "×",
                                    color = LensCodeColors.Muted,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable { onDeleteBranch(branch) },
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Eyebrow("WORKTREES")
            Spacer(Modifier.height(4.dp))
            Text(
                "Tap a worktree to open its checkout in Code.",
                color = LensCodeColors.Muted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(14.dp))
            if (worktrees.isEmpty()) {
                Text("No worktrees found.", color = LensCodeColors.Muted, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Create one with `paseo` or the desktop app's worktree picker.",
                    color = LensCodeColors.Muted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(worktrees) { wt ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(3.dp))
                                .clickable { onOpenInCode(wt) }
                                .padding(horizontal = 6.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Paseo's worktree list is Paseo-owned linked worktrees only —
                            // it never includes the repo's main checkout (docs/paseo-migration-plan.md,
                            // Phase 3), so every entry here is removable.
                            StatusDot(LensCodeColors.Cyan, size = 7.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.width(220.dp)) {
                                Text(
                                    wt.branch ?: "(detached)",
                                    color = LensCodeColors.Text,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    "linked worktree",
                                    color = LensCodeColors.Muted,
                                    fontSize = 11.sp,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                wt.path,
                                color = LensCodeColors.Eyebrow,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "🗑",
                                fontSize = 13.sp,
                                modifier = Modifier.clickable { onRemoveWorktree(wt) }.padding(4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
    // A dirty tree is never resolved silently (spec §27) — this always
    // appears first, and the actual switch only happens after a choice here.
    if (conflict != null) {
        Box(
            Modifier.fillMaxSize().background(Color(0xAA000000)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .width(380.dp)
                    .glassFinish(PanelCorner)
                    .padding(20.dp),
            ) {
                Column {
                    Text(
                        "Uncommitted changes",
                        color = LensCodeColors.Text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Switching to ${conflict.target} would affect files you haven't committed.",
                        color = LensCodeColors.Muted,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(18.dp))
                    Column {
                        Text(
                            "Stash changes and switch",
                            color = LensCodeColors.Amber,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth().clickable(onClick = conflict.onStash).padding(vertical = 8.dp),
                        )
                        Text(
                            "Open in a new worktree instead",
                            color = LensCodeColors.Cyan,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth().clickable(onClick = conflict.onWorktree).padding(vertical = 8.dp),
                        )
                        Text(
                            "Cancel",
                            color = LensCodeColors.Muted,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth().clickable(onClick = conflict.onCancel).padding(vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
    // Branch delete / worktree removal — never happens without this (spec §57).
    if (deleteConfirm != null) {
        Box(
            Modifier.fillMaxSize().background(Color(0xAA000000)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .width(380.dp)
                    .glassFinish(PanelCorner)
                    .padding(20.dp),
            ) {
                Column {
                    Text(deleteConfirm.title, color = LensCodeColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(deleteConfirm.message, color = LensCodeColors.Muted, fontSize = 13.sp)
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Cancel",
                            color = LensCodeColors.Muted,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable(onClick = deleteConfirm.onCancel).padding(8.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Delete",
                            color = LensCodeColors.Red,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable(onClick = deleteConfirm.onConfirm).padding(8.dp),
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

/** Badge color per `GitFileStatus.status` (added/modified/deleted/renamed/untracked). */
private fun gitStatusColor(status: String): Color = when (status) {
    "added" -> LensCodeColors.Green
    "deleted" -> LensCodeColors.Red
    "renamed" -> LensCodeColors.Purple
    "untracked" -> LensCodeColors.Cyan
    else -> LensCodeColors.Amber // modified
}

private fun gitStatusLabel(status: String): String = when (status) {
    "added" -> "A"
    "deleted" -> "D"
    "renamed" -> "R"
    "untracked" -> "U"
    else -> "M"
}

/** Icon for one outline entry's kind (spec §15) — same casual mapping as the file tree's 📁/📄. */
private fun symbolKindIcon(kind: String): String = when (kind) {
    "function" -> "ƒ"
    "class" -> "🄲"
    "interface" -> "🄸"
    "struct" -> "🅂"
    "enum" -> "🄴"
    "trait" -> "🅃"
    "impl" -> "▸"
    "type" -> "🅃"
    "method" -> "ƒ"
    else -> "•"
}

/** One `@@ … @@` block (its header line + every line until the next hunk/EOF). */
data class DiffHunk(val lines: List<String>)

/**
 * Splits a unified diff for one file into its file-header lines (`diff --git`,
 * `index`, `---`, `+++`) and its hunks — used both for display and to
 * reconstruct a single-hunk patch for `git.hunk.apply` (spec §31): a valid
 * patch is just the file header followed by exactly one `@@` block.
 */
fun parseDiffHunks(diffText: String): Pair<List<String>, List<DiffHunk>> {
    val lines = diffText.split("\n")
    val firstHunkIdx = lines.indexOfFirst { it.startsWith("@@") }
    if (firstHunkIdx == -1) return lines to emptyList()
    val header = lines.subList(0, firstHunkIdx)
    val hunks = mutableListOf<DiffHunk>()
    var start = firstHunkIdx
    for (i in (firstHunkIdx + 1)..lines.size) {
        if (i == lines.size || lines[i].startsWith("@@")) {
            hunks.add(DiffHunk(lines.subList(start, i)))
            start = i
        }
    }
    return header to hunks
}

/** One hunk's patch text, ready to pipe to `git apply` (spec §31). */
fun hunkPatch(header: List<String>, hunk: DiffHunk): String =
    (header + hunk.lines).joinToString("\n") + "\n"

/**
 * Diff view: real `git status` on the left (scoped to whatever the Code view
 * is currently rooted at), a colored unified diff for the selected file on
 * the right — VS Code-style +/- line tinting, no external diff library.
 * Each hunk carries its own Stage/Discard row (spec §31) — "stage" adds just
 * that hunk to the index via `git apply --cached`; "discard" is destructive
 * (spec §57) and reverses it out of the working tree, gated on the same
 * confirmation-card pattern used for branch/worktree deletion.
 */
@Composable
fun DiffPanelContent(
    status: com.lenscode.app.net.GitStatus?,
    diffPath: String?,
    diffText: String?,
    onSelectFile: (String) -> Unit,
    onStageHunk: (path: String, patch: String) -> Unit = { _, _ -> },
    onDiscardHunk: (path: String, patch: String) -> Unit = { _, _ -> },
    discardConfirm: DeleteConfirmState? = null,
    stagedHunks: Set<Int> = emptySet(),
) {
    Box(Modifier.fillMaxSize()) {
    GlassSurface {
        Row(Modifier.fillMaxSize().padding(20.dp)) {
            Column(Modifier.width(240.dp).fillMaxSize()) {
                Eyebrow("DIFF" + (status?.branch?.let { " · $it" } ?: ""))
                Spacer(Modifier.height(10.dp))
                val files = status?.files.orEmpty()
                if (status == null) {
                    Text("Loading…", color = LensCodeColors.Muted, fontSize = 13.sp)
                } else if (files.isEmpty()) {
                    Text("No changes.", color = LensCodeColors.Muted, fontSize = 13.sp)
                } else {
                    LazyColumn {
                        items(files) { f ->
                            val isSelected = f.path == diffPath
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(if (isSelected) LensCodeColors.DockActiveGradTop else Color.Transparent)
                                    .clickable { onSelectFile(f.path) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    gitStatusLabel(f.status),
                                    color = gitStatusColor(f.status),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.width(18.dp),
                                )
                                Text(
                                    f.path.substringAfterLast('/'),
                                    color = LensCodeColors.Text,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(3.dp))
                    .background(VsCodeColors.EditorBg)
                    .padding(14.dp),
            ) {
                when {
                    diffPath == null -> Text("Select a changed file.", color = LensCodeColors.Muted, fontSize = 13.sp)
                    diffText == null -> Text("Loading diff…", color = LensCodeColors.Muted, fontSize = 13.sp)
                    diffText.isBlank() -> Text("No textual diff to show.", color = LensCodeColors.Muted, fontSize = 13.sp)
                    else -> {
                        val (header, hunks) = remember(diffText) { parseDiffHunks(diffText) }
                        fun lineStyle(line: String): Pair<Color, Color> = when {
                            line.startsWith("+++") || line.startsWith("---") || line.startsWith("diff ") || line.startsWith("index ") ->
                                VsCodeColors.Breadcrumb to Color.Transparent
                            line.startsWith("@@") -> LensCodeColors.Purple to Color.Transparent
                            line.startsWith("+") -> Color(0xFFB8E6B0) to Color(0x2A3FB950)
                            line.startsWith("-") -> Color(0xFFF2B8B5) to Color(0x2AF85149)
                            else -> VsCodeColors.Plain to Color.Transparent
                        }
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(header) { line ->
                                val (color, bg) = lineStyle(line)
                                Text(
                                    line,
                                    color = color,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.fillMaxWidth().background(bg),
                                )
                            }
                            itemsIndexed(hunks) { index, hunk ->
                                Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                                    for (line in hunk.lines) {
                                        val (color, bg) = lineStyle(line)
                                        Text(
                                            line,
                                            color = color,
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.fillMaxWidth().background(bg),
                                        )
                                    }
                                    val staged = hunkPatch(header, hunk).hashCode() in stagedHunks
                                    Row(
                                        Modifier.padding(top = 2.dp, start = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (staged) {
                                            Text("✓ staged", color = LensCodeColors.Green, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        } else {
                                            Text(
                                                "✓ Stage",
                                                color = LensCodeColors.Blue,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.clickable {
                                                    onStageHunk(diffPath, hunkPatch(header, hunk))
                                                },
                                            )
                                        }
                                        Spacer(Modifier.width(14.dp))
                                        Text(
                                            "✕ Discard",
                                            color = LensCodeColors.Red,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.clickable {
                                                onDiscardHunk(diffPath, hunkPatch(header, hunk))
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    // Discard reverses a hunk out of the working tree — destructive (spec §57).
    if (discardConfirm != null) {
        Box(
            Modifier.fillMaxSize().background(Color(0xAA000000)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .width(380.dp)
                    .glassFinish(PanelCorner)
                    .padding(20.dp),
            ) {
                Column {
                    Text(discardConfirm.title, color = LensCodeColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(discardConfirm.message, color = LensCodeColors.Muted, fontSize = 13.sp)
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Cancel",
                            color = LensCodeColors.Muted,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable(onClick = discardConfirm.onCancel).padding(8.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Discard",
                            color = LensCodeColors.Red,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable(onClick = discardConfirm.onConfirm).padding(8.dp),
                        )
                    }
                }
            }
        }
    }
    }
}

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

/**
 * Voice file search overlay (spec §13, §63) — sits on top of whatever view is
 * active, since the mic is reachable from anywhere. Three states, mutually
 * exclusive: listening (live partial transcript), an error, or an ambiguous
 * result's candidate list. A confident result never reaches here — the
 * caller opens it directly and clears state before this ever renders.
 */
@Composable
fun VoiceOverlay(
    isListening: Boolean,
    partial: String,
    error: String?,
    result: VoiceResolution?,
    onSelectCandidate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val showCandidates = result != null && !result.confident
    if (!isListening && error == null && !showCandidates) return

    Box(
        Modifier.fillMaxSize().background(Color(0xAA000000)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(420.dp)
                .glassFinish(PanelCorner)
                // Swallow clicks so tapping inside the card doesn't dismiss it.
                .clickable(enabled = false) {}
                .padding(20.dp),
        ) {
            Column {
                when {
                    isListening -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(LensCodeColors.Red, size = 8.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Listening…", color = LensCodeColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            partial.ifBlank { "Say a filename…" },
                            color = LensCodeColors.Muted,
                            fontSize = 14.sp,
                        )
                    }
                    error != null -> {
                        Text("Voice search", color = LensCodeColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        Text(error, color = LensCodeColors.Amber, fontSize = 14.sp)
                    }
                    result != null -> {
                        Text(
                            if (result.candidates.isEmpty()) "No match" else "Which one?",
                            color = LensCodeColors.Text,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Not confident enough to open one automatically.",
                            color = LensCodeColors.Muted,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(12.dp))
                        Column(Modifier.heightIn(max = 260.dp)) {
                            for (candidate in result.candidates) {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(3.dp))
                                        .clickable { onSelectCandidate(candidate.path) }
                                        .padding(vertical = 8.dp, horizontal = 6.dp),
                                ) {
                                    Text(
                                        candidate.path,
                                        color = LensCodeColors.Text,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    if (candidate.preview != null) {
                                        Text(
                                            candidate.preview,
                                            color = LensCodeColors.Muted,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "Tap outside to dismiss",
                    color = LensCodeColors.Eyebrow,
                    fontSize = 11.sp,
                    modifier = Modifier.clickable(onClick = onDismiss),
                )
            }
        }
    }
}

/**
 * Global voice command overlay — record, then review the transcript before
 * anything happens. Four mutually exclusive states: listening (live partial
 * text), an error, the review step (final transcript + Submit/Cancel), or
 * routing (Gemini classifying the confirmed transcript). Submit hands the
 * transcript to [com.lenscode.app.voice.KoogVoiceRouter] (MainActivity owns
 * dispatch); Cancel discards it — nothing is ever acted on without this
 * explicit confirmation, same discipline as every other destructive-action
 * card in the app (spec §57).
 */
@Composable
fun VoiceCommandOverlay(
    isListening: Boolean,
    partial: String,
    error: String?,
    reviewText: String?,
    isRouting: Boolean,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    onDismissError: () -> Unit,
) {
    if (!isListening && error == null && reviewText == null) return

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xAA000000))
            .clickable(onClick = if (reviewText != null) onCancel else onDismissError),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(420.dp)
                .glassFinish(PanelCorner)
                // Swallow clicks so tapping inside the card doesn't dismiss it.
                .clickable(enabled = false) {}
                .padding(20.dp),
        ) {
            Column {
                when {
                    reviewText != null && isRouting -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(LensCodeColors.Cyan, size = 8.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Thinking…", color = LensCodeColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(10.dp))
                        Text("\"$reviewText\"", color = LensCodeColors.Muted, fontSize = 14.sp)
                    }
                    reviewText != null -> {
                        Text("Did I get that right?", color = LensCodeColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(3.dp))
                                .background(VsCodeColors.EditorBg)
                                .padding(12.dp),
                        ) {
                            Text(reviewText, color = LensCodeColors.Text, fontSize = 15.sp)
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "✕ Cancel",
                                color = LensCodeColors.Muted,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable(onClick = onCancel).padding(10.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "✓ Submit",
                                color = LensCodeColors.Green,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable(onClick = onSubmit).padding(10.dp),
                            )
                        }
                    }
                    isListening -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(LensCodeColors.Red, size = 8.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Listening…", color = LensCodeColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            partial.ifBlank { "Say a command — \"open diff\", \"accept changes\", \"kill terminal\"…" },
                            color = LensCodeColors.Muted,
                            fontSize = 14.sp,
                        )
                    }
                    error != null -> {
                        Text("Voice command", color = LensCodeColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        Text(error, color = LensCodeColors.Amber, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

/** What the agent detail card shows (docs/paseo-migration-plan.md, Phase 3). */
data class AgentDetailState(val agent: AgentInfo, val promptError: String? = null)

/**
 * Agent detail card (spec §10 — a chip "expands to a detail card" rather than
 * navigating away). Paseo agents are first-class objects, not a scraped
 * terminal pane (see AgentInfo's doc), so unlike the Herdr-era version this
 * shows real agent metadata (model, cwd, cost, last error) instead of a raw
 * terminal snippet, and offers "Open Workspace" (the agent's own cwd/
 * workspace in Files+Terminal) rather than a fake "its terminal". A prompt
 * box only makes sense once the agent isn't closed — Paseo's own attention/
 * permission flow (see the Permissions queue) is the sanctioned way to
 * unblock one that's waiting on something, not a raw terminal keystroke.
 */
/**
 * What the agent is actually doing — every message and tool call, oldest
 * first (docs/paseo-migration-plan.md follow-up). Before this existed the
 * only way to see an agent's shell commands/output was querying the Paseo
 * daemon directly from outside the app.
 */
@Composable
private fun AgentActivityFeed(timeline: List<com.lenscode.app.net.AgentTimelineEntry>) {
    val listState = rememberLazyListState()
    LaunchedEffect(timeline.size) {
        if (timeline.isNotEmpty()) listState.animateScrollToItem(timeline.size - 1)
    }
    Column {
        Text("ACTIVITY", color = LensCodeColors.Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp, max = 220.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(VsCodeColors.EditorBg)
                .padding(10.dp),
        ) {
            if (timeline.isEmpty()) {
                Text("No activity yet.", color = LensCodeColors.Muted, fontSize = 12.sp)
            } else {
                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(timeline) { entry -> AgentActivityRow(entry) }
                }
            }
        }
    }
}

@Composable
private fun AgentActivityRow(entry: com.lenscode.app.net.AgentTimelineEntry) {
    when (entry.kind) {
        "user_message" -> Text(
            "you: ${entry.text ?: ""}",
            color = LensCodeColors.Text,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
        "assistant_message" -> Text(
            entry.text ?: "",
            color = LensCodeColors.Cyan,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
        "tool_call" -> Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val statusColor = when (entry.status) {
                    "completed" -> LensCodeColors.Green
                    "error" -> LensCodeColors.Red
                    "running" -> LensCodeColors.Amber
                    else -> LensCodeColors.Muted
                }
                StatusDot(statusColor, size = 6.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    entry.toolName ?: "tool",
                    color = LensCodeColors.Muted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (entry.command != null) {
                Text(
                    "$ ${entry.command}",
                    color = LensCodeColors.Text,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (entry.output != null) {
                Text(
                    entry.output.take(500),
                    color = LensCodeColors.Muted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 8,
                )
            }
        }
        else -> Text(
            entry.text ?: entry.kind,
            color = LensCodeColors.Muted,
            fontSize = 11.sp,
        )
    }
}

@Composable
fun AgentDetailOverlay(
    state: AgentDetailState?,
    timeline: List<com.lenscode.app.net.AgentTimelineEntry> = emptyList(),
    promptText: String,
    onPromptChange: (String) -> Unit,
    onSendPrompt: () -> Unit,
    onArchive: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onDismiss: () -> Unit,
    isListeningForPrompt: Boolean = false,
    onVoiceInput: () -> Unit = {},
) {
    if (state == null) return
    val agent = state.agent
    val canPrompt = agent.status != "closed"

    Box(
        Modifier.fillMaxSize().background(Color(0xAA000000)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(440.dp)
                .glassFinish(PanelCorner)
                .clickable(enabled = false) {} // swallow taps so the card itself doesn't dismiss
                .padding(20.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(28.dp).clip(RoundedCornerShape(DockCorner)).background(LensCodeColors.LogoBg)
                            .border(1.dp, LensCodeColors.LogoBorder, RoundedCornerShape(DockCorner)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            agent.label.take(1),
                            color = LensCodeColors.agentAccent(agent.provider),
                            fontWeight = FontWeight.Black,
                            fontSize = 13.sp,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(agent.label, color = LensCodeColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(LensCodeColors.agentStatusColor(agent.status), size = 6.dp)
                            Spacer(Modifier.width(4.dp))
                            Text("${agent.provider} · ${agent.status}", color = LensCodeColors.Muted, fontSize = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(3.dp))
                        .background(VsCodeColors.EditorBg)
                        .padding(10.dp),
                ) {
                    Column {
                        Text(agent.cwd, color = VsCodeColors.Plain, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        if (agent.model != null) {
                            Text("model ${agent.model}", color = LensCodeColors.Muted, fontSize = 11.sp)
                        }
                        if (agent.costUsd != null) {
                            Text("cost \$${"%.4f".format(agent.costUsd)}", color = LensCodeColors.Muted, fontSize = 11.sp)
                        }
                        if (agent.pendingPermissionCount > 0) {
                            Text(
                                "⚠ ${agent.pendingPermissionCount} permission request(s) pending — see Permissions",
                                color = LensCodeColors.Amber,
                                fontSize = 11.sp,
                            )
                        }
                        if (agent.lastError != null) {
                            Text(agent.lastError, color = LensCodeColors.Red, fontSize = 11.sp)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                AgentActivityFeed(timeline)
                Spacer(Modifier.height(14.dp))
                if (canPrompt) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(3.dp))
                                .background(VsCodeColors.EditorBg)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            if (promptText.isEmpty() && !isListeningForPrompt) {
                                Text("Send a message…", color = LensCodeColors.Muted, fontSize = 13.sp)
                            }
                            BasicTextField(
                                value = promptText,
                                onValueChange = onPromptChange,
                                textStyle = TextStyle(color = LensCodeColors.Text, fontSize = 13.sp),
                                singleLine = true,
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(LensCodeColors.Text),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = { onSendPrompt() }),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        // Voice fills the field above rather than sending on its
                        // own — always something to review before Send actually
                        // reaches the agent.
                        if (isListeningForPrompt) {
                            StatusDot(LensCodeColors.Red, size = 8.dp)
                        } else {
                            Text(
                                "🎤",
                                fontSize = 15.sp,
                                modifier = Modifier.clickable(onClick = onVoiceInput).padding(8.dp),
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Send",
                            color = LensCodeColors.Blue,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable(onClick = onSendPrompt).padding(8.dp),
                        )
                    }
                    if (state.promptError != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(state.promptError, color = LensCodeColors.Amber, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                } else {
                    Text(
                        "This agent is closed — open its workspace to start a new one there.",
                        color = LensCodeColors.Amber,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                }
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Open Workspace",
                        color = LensCodeColors.Cyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(onClick = onOpenWorkspace),
                    )
                    Text(
                        "Archive",
                        color = LensCodeColors.Amber,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(onClick = onArchive),
                    )
                    Text(
                        "Close",
                        color = LensCodeColors.Muted,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(onClick = onDismiss),
                    )
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
    state: com.lenscode.app.net.BridgeClient.RemoteBrowseState?,
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

/**
 * Browser dock button's prompt (docs/remote-setup-and-limits.md's "Web
 * preview" row: Code has no embedded live preview, so this is the app's only
 * way to open one). Launches as a normal Android activity — in XR that means
 * the system opens Chrome as its own separate spatial window in Home Space,
 * not something this app can dock beside itself or pin to a side; the user
 * repositions it like any other XR window once it appears.
 */
@Composable
fun BrowserLaunchOverlay(
    visible: Boolean,
    url: String,
    onUrlChange: (String) -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    isListeningForUrl: Boolean = false,
    onVoiceInput: () -> Unit = {},
) {
    if (!visible) return
    Box(
        Modifier.fillMaxSize().background(Color(0xAA000000)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(420.dp)
                .glassFinish(PanelCorner)
                .clickable(enabled = false) {}
                .padding(20.dp),
        ) {
            Column {
                Text("Open in Chrome", color = LensCodeColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Opens as its own window, requested docked to the right — reposition it if it lands somewhere else.",
                    color = LensCodeColors.Muted,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        PairingTextField(url, onUrlChange, "http://192.168.1.x:3000", onSubmit = onOpen)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isListeningForUrl) "…" else "🎤",
                        fontSize = 18.sp,
                        modifier = Modifier.clickable(enabled = !isListeningForUrl, onClick = onVoiceInput).padding(6.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Cancel",
                        color = LensCodeColors.Muted,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(onClick = onDismiss),
                    )
                    Text(
                        "Open",
                        color = LensCodeColors.Blue,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable(enabled = url.isNotBlank(), onClick = onOpen),
                    )
                }
            }
        }
    }
}

/**
 * Embedded browser panel — renders inside this app's own window via
 * [WebView] instead of launching Chrome as a separate Android activity.
 * Added because a separate Chrome window occludes/hides this app's other
 * panels (Terminal in particular) in Home Space rather than sitting beside
 * them; see BrowserLaunchOverlay's doc comment for why external Chrome was
 * the original approach and docs/remote-setup-and-limits.md's "Web preview"
 * row for the limitation this closes.
 */
@Composable
fun BrowserPanelContent(
    url: String,
    onChangeUrl: () -> Unit,
    onOpenInChrome: () -> Unit,
) {
    var displayedUrl by remember(url) { mutableStateOf(url) }
    var canGoBack by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize()) {
    GlassSurface {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canGoBack) {
                    Text(
                        "←",
                        color = LensCodeColors.Text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { webViewRef?.goBack() }.padding(end = 10.dp),
                    )
                }
                Text(
                    "↻",
                    color = LensCodeColors.Muted,
                    fontSize = 15.sp,
                    modifier = Modifier.clickable { reloadKey++ }.padding(end = 10.dp),
                )
                Text(
                    displayedUrl,
                    color = LensCodeColors.Muted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Change",
                    color = LensCodeColors.Cyan,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(onClick = onChangeUrl).padding(start = 10.dp),
                )
                Text(
                    "Open in Chrome",
                    color = LensCodeColors.Blue,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(onClick = onOpenInChrome).padding(start = 14.dp),
                )
            }
            if (isLoading || loadError != null) {
                Text(
                    loadError?.let { "Couldn't load this page — $it" } ?: "Loading…",
                    color = if (loadError != null) LensCodeColors.Amber else LensCodeColors.Muted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // LAN dev servers are almost always same-origin http — mixed
                        // content only comes up if the page itself pulls an https
                        // sub-resource, but default-block would silently blank it.
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, startedUrl: String, favicon: android.graphics.Bitmap?) {
                                isLoading = true
                                loadError = null
                            }

                            override fun onPageFinished(view: WebView, finishedUrl: String) {
                                isLoading = false
                                displayedUrl = finishedUrl
                                canGoBack = view.canGoBack()
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: android.webkit.WebResourceRequest,
                                error: android.webkit.WebResourceError,
                            ) {
                                if (!request.isForMainFrame) return
                                isLoading = false
                                loadError = "${error.description} (${error.errorCode})"
                                android.util.Log.w("LensCodeBrowser", "load failed for ${request.url}: ${error.description} (${error.errorCode})")
                            }

                            override fun onReceivedHttpError(
                                view: WebView,
                                request: android.webkit.WebResourceRequest,
                                errorResponse: android.webkit.WebResourceResponse,
                            ) {
                                if (!request.isForMainFrame) return
                                isLoading = false
                                loadError = "HTTP ${errorResponse.statusCode}"
                                android.util.Log.w("LensCodeBrowser", "load failed for ${request.url}: HTTP ${errorResponse.statusCode}")
                            }

                            override fun onReceivedSslError(
                                view: WebView,
                                handler: android.webkit.SslErrorHandler,
                                error: android.net.http.SslError,
                            ) {
                                isLoading = false
                                loadError = "SSL error (${error.primaryError})"
                                android.util.Log.w("LensCodeBrowser", "SSL error for ${error.url}: ${error.primaryError}")
                                handler.cancel() // never silently trust an untrusted cert
                            }
                        }
                        webChromeClient = WebChromeClient()
                        webViewRef = this
                        loadUrl(url)
                    }
                },
                update = { view ->
                    // reloadKey bump is the only signal to actually reload — a
                    // recomposition from e.g. displayedUrl changing shouldn't
                    // also re-navigate and stomp forward/back history.
                    if (reloadKey > 0) view.reload()
                },
            )
        }
    }
    }
}
