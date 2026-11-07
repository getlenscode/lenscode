package com.lenscode.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Suppress("MagicNumber") // A literal ARGB value: the dimmed backdrop behind the card.
private val SCRIM = Color(0xCC000000)

/** What a [VoiceProblemOverlay] says: a missing key, an unreachable service, a failed routing call. */
data class VoiceProblem(val title: String, val message: String)

/** Popup shown when voice can't work: no AssemblyAI/OpenRouter key on the bridge, or a call failed. There is no fallback. */
@Suppress("FunctionNaming")
@Composable
fun VoiceProblemOverlay(problem: VoiceProblem?, onDismiss: () -> Unit) {
    if (problem == null) return
    Box(
        Modifier.fillMaxSize().background(SCRIM).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .clip(lensShape(8.dp))
                .background(LensCodeColors.WindowBg)
                .border(1.dp, LensCodeColors.Red, lensShape(8.dp))
                .clickable(enabled = false) {}
                .padding(24.dp),
        ) {
            Text(problem.title, color = LensCodeColors.Red, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            Text(problem.message, color = LensCodeColors.Text, fontSize = 14.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(18.dp))
            Text(
                "OK",
                color = LensCodeColors.Green,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(lensShape(3.dp)).clickable(onClick = onDismiss).padding(vertical = 6.dp),
            )
        }
    }
}
