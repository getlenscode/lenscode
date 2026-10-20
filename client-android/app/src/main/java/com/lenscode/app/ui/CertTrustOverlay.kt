package com.lenscode.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lenscode.app.net.CertPrompt

@Suppress("MagicNumber") // A literal ARGB value: the dimmed backdrop behind the card.
private val SCRIM = Color(0xCC000000)

/**
 * First-contact trust for a bridge that uses its own certificate (`lenscode-bridge --secure`).
 * The user compares the fingerprint here with the one the bridge printed; matching proves this is
 * their bridge and not something else on the network. Also shown, more loudly, when a bridge
 * presents a certificate different from the one already trusted.
 */
@Suppress("FunctionNaming")
@Composable
fun CertTrustOverlay(prompt: CertPrompt?, onTrust: () -> Unit, onDecline: () -> Unit) {
    if (prompt == null) return
    val accent = if (prompt.changed) LensCodeColors.Red else LensCodeColors.Amber
    Box(
        Modifier.fillMaxSize().background(SCRIM).clickable(enabled = false) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .clip(lensShape(8.dp))
                // Opaque on purpose: a translucent card lets the screen behind it show through the text.
                .background(LensCodeColors.WindowBg)
                .border(1.dp, accent, lensShape(8.dp))
                .padding(24.dp),
        ) {
            Text(
                if (prompt.changed) "Bridge certificate changed" else "Trust this bridge?",
                color = accent,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(prompt.hostPort, color = LensCodeColors.Muted, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(14.dp))
            Text(explanation(prompt.changed), color = LensCodeColors.Text, fontSize = 14.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(16.dp))
            Text(
                prompt.short,
                color = LensCodeColors.Text,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(18.dp))
            TrustButtons(prompt.changed, onTrust, onDecline)
        }
    }
}

private fun explanation(changed: Boolean): String = if (changed) {
    "The certificate for this bridge is different from the one you trusted before. That happens if " +
        "the bridge made a new certificate — or if someone is intercepting your connection. Only " +
        "continue if this fingerprint matches what your bridge prints now."
} else {
    "This bridge uses its own certificate. Check that the fingerprint below is exactly what " +
        "lenscode-bridge printed on your computer. If it isn't, don't continue."
}

@Suppress("FunctionNaming")
@Composable
private fun TrustButtons(changed: Boolean, onTrust: () -> Unit, onDecline: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(
            if (changed) "Replace & connect" else "Trust & connect",
            color = if (changed) LensCodeColors.Red else LensCodeColors.Green,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(lensShape(3.dp)).clickable(onClick = onTrust).padding(vertical = 6.dp),
        )
        Text(
            "Cancel",
            color = LensCodeColors.Muted,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(lensShape(3.dp)).clickable(onClick = onDecline).padding(vertical = 6.dp),
        )
    }
}
