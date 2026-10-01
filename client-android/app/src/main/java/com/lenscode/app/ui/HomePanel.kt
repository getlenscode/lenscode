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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lenscode.app.net.BridgeClient
import com.lenscode.app.net.PairingState

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
internal fun PairingTextField(value: String, onValueChange: (String) -> Unit, placeholder: String, onSubmit: () -> Unit) {
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
