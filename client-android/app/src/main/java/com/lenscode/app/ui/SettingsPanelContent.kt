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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lenscode.app.net.GeminiConfigResult

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
            .clip(RoundedCornerShape(4.dp))
            .background(LensCodeColors.CardBg)
            .border(1.dp, LensCodeColors.CardBorder, RoundedCornerShape(4.dp))
            .padding(12.dp),
    ) {
        for (line in lines) CodeLine(line)
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 22.dp)) {
        Text(title, color = LensCodeColors.Eyebrow, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

/**
 * Settings panel (dock → Settings). Currently one thing lives here: how to
 * point voice routing (com.lenscode.app.voice.KoogVoiceRouter) at a Gemini API key
 * without rebuilding the APK. See net/GeminiConfigStore.kt for the file this
 * reads and MainActivity's `koogVoiceRouter` for how the result is applied —
 * config file wins over the build-time `-PgeminiApiKey` gradle property.
 */
@Composable
fun SettingsPanelContent(
    geminiResult: GeminiConfigResult,
    buildKeyPresent: Boolean,
    configPath: String,
    onReload: () -> Unit,
    currentThemeId: ThemeId = ThemeId.CURSOR,
    onThemeChange: (ThemeId) -> Unit = {},
) {
    GlassSurface {
        LazyColumn(Modifier.fillMaxSize().padding(24.dp)) {
            item {
                Eyebrow("SETTINGS")
                Spacer(Modifier.height(16.dp))

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
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (selected) LensCodeColors.CardBg else androidx.compose.ui.graphics.Color.Transparent)
                                    .border(1.dp, if (selected) LensCodeColors.Cyan else LensCodeColors.CardBorder, RoundedCornerShape(6.dp))
                                    .clickable { onThemeChange(id) }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                }

                val (dotColor, statusText, detail) = when (geminiResult) {
                    is GeminiConfigResult.Loaded -> if (!geminiResult.config.apiKey.isNullOrBlank()) {
                        Triple(LensCodeColors.Green, "Gemini voice routing: active (config file)", null)
                    } else if (buildKeyPresent) {
                        Triple(LensCodeColors.Green, "Gemini voice routing: active (build key)", null)
                    } else {
                        Triple(LensCodeColors.Idle, "Gemini voice routing: off", "No key in the config file or the build. Voice falls back to the local command matcher.")
                    }
                    GeminiConfigResult.Missing -> if (buildKeyPresent) {
                        Triple(LensCodeColors.Green, "Gemini voice routing: active (build key)", null)
                    } else {
                        Triple(LensCodeColors.Idle, "Gemini voice routing: off", "No config file found and no key baked into the build.")
                    }
                    is GeminiConfigResult.ParseError -> Triple(
                        LensCodeColors.Red,
                        "Gemini voice routing: config file error",
                        "Couldn't read lenscode-config.json: ${geminiResult.message}",
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(dotColor)
                    Spacer(Modifier.width(8.dp))
                    Text(statusText, color = LensCodeColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                if (detail != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(detail, color = LensCodeColors.Muted, fontSize = 12.5.sp)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Reload",
                    color = LensCodeColors.Blue,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .clickable(onClick = onReload)
                        .padding(vertical = 4.dp),
                )

                SettingsSection("SET A GEMINI KEY WITHOUT REBUILDING") {
                    Text(
                        "Without a key, voice commands still work through the deterministic " +
                            "local matcher (navigate, open a named file, accept/discard, etc.) — " +
                            "a Gemini key only adds free-form routing (\"add a test for the login " +
                            "route\" → handed to the coding agent).",
                        color = LensCodeColors.Muted,
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp,
                    )
                    Spacer(Modifier.height(14.dp))
                    Text("1. Create this file:", color = LensCodeColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    CodeBlock(
                        listOf(
                            "{",
                            "  \"geminiApiKey\": \"AIza...your key...\",",
                            "  \"geminiModel\": \"gemini-3.8-flash\"",
                            "}",
                        ),
                    )
                    Text(
                        "geminiModel is optional — omit it to keep the build's default.",
                        color = LensCodeColors.Muted,
                        fontSize = 11.5.sp,
                    )

                    Spacer(Modifier.height(14.dp))
                    Text("2. Push it to this device at:", color = LensCodeColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    CodeBlock(listOf(configPath))

                    Spacer(Modifier.height(14.dp))
                    Text("3. From a computer with adb (USB or same-Wi-Fi debugging):", color = LensCodeColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    CodeBlock(listOf("adb push lenscode-config.json \\", "  \"$configPath\""))

                    Spacer(Modifier.height(14.dp))
                    Text(
                        "4. Come back here and tap Reload — no reinstall or app restart needed.",
                        color = LensCodeColors.Text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )

                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Get a key at aistudio.google.com/apikey. The file lives in this app's " +
                            "own storage — adb is the only way to place it there without a file " +
                            "manager that has All files access.",
                        color = LensCodeColors.Muted,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }
}
