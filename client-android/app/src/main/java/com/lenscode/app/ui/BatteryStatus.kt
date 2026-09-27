package com.lenscode.app.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Live battery reading: (percent 0-100, charging). Registers a sticky-intent receiver so it updates as the OS reports changes, no polling. */
@Composable
fun rememberBatteryState(): Pair<Int, Boolean> {
    val context = LocalContext.current
    var state by remember { mutableStateOf(readBattery(context)) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                state = extractBattery(intent)
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val sticky = context.registerReceiver(receiver, filter)
        if (sticky != null) state = extractBattery(sticky)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return state
}

private fun readBattery(context: Context): Pair<Int, Boolean> {
    val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    return sticky?.let { extractBattery(it) } ?: (-1 to false)
}

private fun extractBattery(intent: Intent): Pair<Int, Boolean> {
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    val percent = if (level >= 0 && scale > 0) (level * 100) / scale else -1
    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    return percent to charging
}

/** The Android device's own battery — this XR device, not any Paseo host — shown as a small icon + percentage. */
@Composable
fun BatteryIndicator() {
    val (percent, charging) = rememberBatteryState()
    if (percent < 0) return
    val color = when {
        percent <= 15 && !charging -> LensCodeColors.Red
        percent <= 30 && !charging -> LensCodeColors.Amber
        else -> LensCodeColors.Muted
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        BatteryGlyph(percent, charging, color)
        Spacer4()
        Text(if (charging) "$percent% ⚡" else "$percent%", color = color, fontSize = 12.sp)
    }
}

@Composable
private fun Spacer4() {
    androidx.compose.foundation.layout.Spacer(Modifier.width(4.dp))
}

/** A body-and-nub battery outline with a fill proportional to charge — the shared 24-unit line-icon style from DockIcon.kt. */
@Composable
private fun BatteryGlyph(percent: Int, charging: Boolean, color: androidx.compose.ui.graphics.Color) {
    Canvas(Modifier.size(width = 18.dp, height = 10.dp)) {
        val stroke = Stroke(1.3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val bodyWidth = size.width - 2.5.dp.toPx() // leave room for the nub
        drawRoundRect(color, Offset.Zero, Size(bodyWidth, size.height), CornerRadius(2f), style = stroke)
        val nubHeight = size.height * 0.4f
        drawRoundRect(
            color,
            Offset(bodyWidth + 1.dp.toPx(), (size.height - nubHeight) / 2f),
            Size(1.8.dp.toPx(), nubHeight),
            CornerRadius(1f),
        )
        val inset = 2f
        val fillWidth = ((bodyWidth - inset * 2) * (percent.coerceIn(0, 100) / 100f)).coerceAtLeast(0f)
        if (fillWidth > 0f) {
            drawRoundRect(
                color,
                Offset(inset, inset),
                Size(fillWidth, size.height - inset * 2),
                CornerRadius(1f),
            )
        }
    }
}
