@file:OptIn(ExperimentalLayoutApi::class)

package com.lenscode.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lenscode.app.net.BridgeLlmStatus
import com.lenscode.app.net.DEFAULT_OPENROUTER_MODEL

/** One line of a monospace instruction block — a shell command, a path, or a JSON snippet. */
@Composable
private fun CodeLine(text: String, color: androidx.compose.ui.graphics.Color = LensCodeColors.Text) {
    Text(text, color = color, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace, lineHeight = 18.sp)
}

@Composable
private fun CodeBlock(lines: List<String>) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(lensShape(4.dp))
            .background(LensCodeColors.CardBg)
            .border(1.dp, LensCodeColors.CardBorder, lensShape(4.dp))
            .padding(12.dp),
    ) {
        for (line in lines) CodeLine(line)
    }
}

@Suppress("FunctionNaming")
@Composable
internal fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 22.dp)) {
        Text(title, color = LensCodeColors.Eyebrow, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

/**
 * Settings panel (dock → Settings): bridge connection, theme, and how to give the bridge its API keys
 * without typing them in AR. The normal path is the bridge's /setup page, opened on a phone or PC —
 * this panel only shows its address and whether the OpenRouter key (voice command routing) has
 * arrived. (Speech-to-text needs an AssemblyAI key on the same page; a missing one raises a popup
 * when you tap Speak.)
 */
@Composable
fun SettingsPanelContent(
    bridge: BridgeSettingsUi,
    bridgeLlm: BridgeLlmStatus,
    bridgeConnected: Boolean,
    setupUrl: String,
    currentThemeId: ThemeId = ThemeId.CURSOR,
    onThemeChange: (ThemeId) -> Unit = {},
) {
    GlassSurface {
        LazyColumn(Modifier.fillMaxSize().padding(24.dp)) {
            item {
                Eyebrow("SETTINGS")
                Spacer(Modifier.height(16.dp))

                BridgeConnectionSection(bridge)


                SettingsSection("APP THEME") {
                    FlowRow(
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                    ) {
                        for (id in ThemeId.entries) {
                            val selected = id == currentThemeId
                            Text(
                                id.label,
                                color = if (selected) LensCodeColors.Text else LensCodeColors.Muted,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(lensShape(6.dp))
                                    .background(if (selected) LensCodeColors.CardBg else androidx.compose.ui.graphics.Color.Transparent)
                                    .border(1.dp, if (selected) LensCodeColors.Cyan else LensCodeColors.CardBorder, lensShape(6.dp))
                                    .clickable { onThemeChange(id) }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(if (bridgeLlm.configured) LensCodeColors.Green else LensCodeColors.Idle)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (bridgeLlm.configured) "Voice command routing: active" else "Voice command routing: off",
                        color = LensCodeColors.Text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (bridgeLlm.configured) {
                        "Model: ${bridgeLlm.model ?: DEFAULT_OPENROUTER_MODEL} (OpenRouter, key on the bridge)"
                    } else {
                        "No OpenRouter key yet. Voice commands show an error until one is added."
                    },
                    color = LensCodeColors.Muted,
                    fontSize = 12.5.sp,
                )

                SettingsSection("ADD YOUR API KEYS FROM A PHONE OR PC") {
                    Text(
                        "Open this address in a browser on your phone or computer (same network as the " +
                            "bridge) and paste your keys. There's nothing to type here — this panel " +
                            "updates by itself once they're saved.",
                        color = LensCodeColors.Muted,
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    CodeBlock(listOf(setupUrl))
                    if (!bridgeConnected) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Bridge not connected. Start it on your computer with " +
                                "./scripts/setup-single-pc.sh, then open the address above.",
                            color = LensCodeColors.Red,
                            fontSize = 12.sp,
                        )
                    } else if (bridgeLlm.configured) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "An OpenRouter key is saved on the bridge. Open the page again to replace or remove it.",
                            color = LensCodeColors.Green,
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "OpenRouter (openrouter.ai/keys) turns spoken commands into actions; AssemblyAI " +
                            "(assemblyai.com) is the speech-to-text behind every Speak button. Both keys stay " +
                            "on the computer running lenscode-bridge — the headset never holds them.",
                        color = LensCodeColors.Muted,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }
}
