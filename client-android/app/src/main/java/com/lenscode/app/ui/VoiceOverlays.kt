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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.lenscode.app.net.VoiceResolution

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
