package com.lenscode.app.ui

import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lenscode.app.net.WeatherReading
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Fancy" home-view HUD (Stark-HUD-style Rainmeter reference the user
 * pointed at) — glowing cyan ring gauges over the app's own existing
 * smoked-glass/cyan-accent palette (LensCodeColors) rather than a mismatched
 * reskin. Fills what used to be the mostly-empty space above the
 * connection-status block, which stays exactly where it was underneath.
 */

/** HH:mm:ss + a short weekday/date line, ticking every second — local device clock, no network. */
@Composable
fun rememberClock(): Pair<String, String> {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(1000)
        }
    }
    val time = remember(now) { SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(now) }
    val date = remember(now) { SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(now) }
    return time to date
}

/** System-wide RAM used %, polled every few seconds via ActivityManager.MemoryInfo (no permission needed, unlike per-process CPU). */
@Composable
fun rememberRamUsagePercent(): Int {
    val context = LocalContext.current
    var percent by remember { mutableStateOf(0) }
    LaunchedEffect(context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        while (true) {
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            if (info.totalMem > 0) {
                percent = (((info.totalMem - info.availMem) * 100) / info.totalMem).toInt()
            }
            delay(4000)
        }
    }
    return percent
}

/**
 * One glowing circular gauge — a dim full-circle track plus a bright arc
 * proportional to [progress] (0f–1f), with a soft double-stroke glow rather
 * than a real blur (untested whether RenderEffect blur behaves correctly
 * hosted inside a Subspace panel's 2D surface, so this avoids relying on it).
 */
@Composable
fun HudRing(
    diameter: Dp,
    progress: Float,
    color: Color,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(diameter)) {
            val strokeWidth = size.minDimension * 0.06f
            val inset = strokeWidth / 2
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            val topLeft = Offset(inset, inset)
            // Dim full track.
            drawArc(
                color = color.copy(alpha = 0.18f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(strokeWidth, cap = StrokeCap.Round),
            )
            val sweep = 360f * progress.coerceIn(0f, 1f)
            // Wide, faint stroke behind the sharp one — a cheap glow.
            drawArc(
                color = color.copy(alpha = 0.35f),
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(strokeWidth * 2.2f, cap = StrokeCap.Round),
            )
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(strokeWidth, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = LensCodeColors.Text, fontSize = (diameter.value * 0.16f).sp, fontWeight = FontWeight.Bold)
            Text(label, color = LensCodeColors.Muted, fontSize = (diameter.value * 0.075f).sp, letterSpacing = 1.sp)
        }
    }
}

/** Current-conditions card — icon, temperature, condition label, humidity/wind. Shows a placeholder while loading/unavailable rather than fake data. */
@Composable
fun WeatherCard(reading: WeatherReading?, locationDenied: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(LensCodeCorners.Card))
            .background(LensCodeColors.CardBg)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            reading != null -> {
                Text(reading.icon, fontSize = 30.sp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${reading.tempC.toInt()}°C",
                            color = LensCodeColors.Text,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(reading.label, color = LensCodeColors.Cyan, fontSize = 13.sp)
                    }
                    Text(
                        "humidity ${reading.humidityPct}% · wind ${reading.windKph.toInt()} km/h",
                        color = LensCodeColors.Muted,
                        fontSize = 11.sp,
                    )
                }
            }
            locationDenied -> {
                Text("🌦️", fontSize = 24.sp, modifier = Modifier.padding(end = 12.dp))
                Text(
                    "Location permission denied — weather unavailable.",
                    color = LensCodeColors.Muted,
                    fontSize = 12.sp,
                )
            }
            else -> {
                Text("🌦️", fontSize = 24.sp, modifier = Modifier.padding(end = 12.dp))
                Text("Finding your weather…", color = LensCodeColors.Muted, fontSize = 12.sp)
            }
        }
    }
}

/** Time / battery / RAM rings side by side, plus the weather card beneath — the whole HUD header. */
@Composable
fun HomeHud(
    batteryPercent: Int,
    batteryCharging: Boolean,
    weather: WeatherReading?,
    weatherLocationDenied: Boolean,
    modifier: Modifier = Modifier,
) {
    val (time, date) = rememberClock()
    val ramPercent = rememberRamUsagePercent()
    // Seconds-hand sweep for the time ring — recomputed each time `time`
    // itself ticks, so it stays in lockstep with the digital readout.
    val secondsProgress = remember(time) { java.util.Calendar.getInstance().get(java.util.Calendar.SECOND) / 60f }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            HudRing(
                diameter = 96.dp,
                progress = secondsProgress,
                color = LensCodeColors.Cyan,
                value = time,
                label = date.uppercase(),
            )
            Spacer(Modifier.weight(0.6f))
            HudRing(
                diameter = 96.dp,
                progress = batteryPercent / 100f,
                color = if (batteryPercent in 0..15 && !batteryCharging) LensCodeColors.Red else LensCodeColors.Green,
                value = if (batteryCharging) "$batteryPercent%⚡" else "$batteryPercent%",
                label = "BATTERY",
            )
            Spacer(Modifier.weight(0.6f))
            HudRing(
                diameter = 96.dp,
                progress = ramPercent / 100f,
                color = if (ramPercent >= 85) LensCodeColors.Amber else LensCodeColors.Blue,
                value = "$ramPercent%",
                label = "RAM",
            )
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(18.dp))
        WeatherCard(weather, weatherLocationDenied, modifier = Modifier.fillMaxWidth())
    }
}
