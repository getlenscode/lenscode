package com.lenscode.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Everything the Settings "Bridge connection" section shows and can do. */
data class BridgeSettingsUi(
    /** The address currently being dialed. */
    val currentUrl: String,
    val connected: Boolean,
    /** Why the last attempt failed, already in plain words. */
    val connectError: String?,
    /** The editable address field. */
    val input: String,
    /** Validation message for [input], shown under the field. */
    val inputError: String?,
    val listening: Boolean,
    val onInputChange: (String) -> Unit,
    val onSpeak: () -> Unit,
    val onSave: () -> Unit,
)

/**
 * Where to point the app at lenscode-bridge. A release build ships with no useful
 * address baked in, so this is how a first run gets connected — by voice where
 * possible, since typing an IP on an XR soft keyboard is the slow path.
 */
@Suppress("FunctionNaming")
@Composable
fun BridgeConnectionSection(ui: BridgeSettingsUi) {
    SettingsSection("BRIDGE CONNECTION") {
        BridgeStatusRow(ui)
        Spacer(Modifier.height(12.dp))
        BridgeAddressField(ui)
        Spacer(Modifier.height(10.dp))
        BridgeActionRow(ui)
        Spacer(Modifier.height(10.dp))
        Text(
            "Enter the address of the computer running lenscode-bridge. The port is added for you " +
                "(8443 for a secure address, 8137 for a plain one). " +
                "To avoid typing, tap Speak and say the digits and \"dot\" — for example \"one nine two dot " +
                "one six eight dot one dot one five seven\".",
            color = LensCodeColors.Muted,
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
        )
    }
}

@Suppress("FunctionNaming")
@Composable
private fun BridgeStatusRow(ui: BridgeSettingsUi) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(if (ui.connected) LensCodeColors.Green else LensCodeColors.Red)
        Spacer(Modifier.width(8.dp))
        Text(
            if (ui.connected) "Connected to ${ui.currentUrl}" else "Not connected to ${ui.currentUrl}",
            color = LensCodeColors.Text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
    }
    if (!ui.connected && ui.connectError != null) {
        Spacer(Modifier.height(6.dp))
        Text(ui.connectError, color = LensCodeColors.Amber, fontSize = 12.5.sp)
    }
}

@Suppress("FunctionNaming")
@Composable
private fun BridgeAddressField(ui: BridgeSettingsUi) {
    val mono = TextStyle(color = LensCodeColors.Text, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(lensShape(4.dp))
            .background(LensCodeColors.CardBg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (ui.input.isEmpty()) {
            Text("192.168.1.157", color = LensCodeColors.Muted, style = mono)
        }
        BasicTextField(
            value = ui.input,
            onValueChange = ui.onInputChange,
            textStyle = mono,
            singleLine = true,
            cursorBrush = SolidColor(LensCodeColors.Text),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { ui.onSave() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (ui.inputError != null) {
        Spacer(Modifier.height(6.dp))
        Text(ui.inputError, color = LensCodeColors.Red, fontSize = 12.5.sp)
    }
}

@Suppress("FunctionNaming")
@Composable
private fun BridgeActionRow(ui: BridgeSettingsUi) {
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        BridgeAction("Save & connect", LensCodeColors.Blue, ui.onSave)
        BridgeAction(
            if (ui.listening) "Listening… say the address" else "🎤 Speak address",
            if (ui.listening) LensCodeColors.Green else LensCodeColors.Blue,
            ui.onSpeak,
        )
    }
}

@Suppress("FunctionNaming")
@Composable
private fun BridgeAction(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Text(
        label,
        color = color,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(lensShape(3.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
    )
}
