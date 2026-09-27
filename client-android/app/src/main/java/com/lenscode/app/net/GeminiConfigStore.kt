package com.lenscode.app.net

import android.content.Context
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * On-device override for the Gemini key/model that [com.lenscode.app.voice.KoogVoiceRouter]
 * uses (spec: voice command routing). The build-time `-PgeminiApiKey=...` gradle
 * property (see app/build.gradle.kts, BuildConfig.GEMINI_API_KEY) bakes a key into
 * the APK, which means rebuilding just to rotate a key or hand someone else a build.
 * This lets the key live in a small JSON file on the device instead, read at
 * runtime — see the Settings panel (ui/SettingsPanelContent.kt) for the
 * user-facing instructions this mirrors.
 *
 * Precedence, applied by the caller (MainActivity's `koogVoiceRouter`): config
 * file, if present and non-blank, wins over the build-time key. Neither set
 * means no Gemini routing — the deterministic local VoiceCommandRouter handles
 * everything instead, same as today.
 */
data class GeminiConfig(val apiKey: String?, val model: String?)

/** What [GeminiConfigStore.load] found, so Settings can show *why* a key is or isn't active — not just whether. */
sealed class GeminiConfigResult {
    data class Loaded(val config: GeminiConfig) : GeminiConfigResult()
    data object Missing : GeminiConfigResult()
    data class ParseError(val message: String) : GeminiConfigResult()
}

object GeminiConfigStore {
    private const val FILE_NAME = "lenscode-config.json"
    private const val KEY_API_KEY = "geminiApiKey"
    private const val KEY_MODEL = "geminiModel"

    /**
     * App-specific external storage — no runtime permission needed for the app
     * to read/write it, and `adb push` can write it without root. Typically
     * resolves to `/storage/emulated/0/Android/data/com.lenscode.app/files/lenscode-config.json`.
     */
    fun configFile(context: Context): File = File(context.getExternalFilesDir(null), FILE_NAME)

    fun load(context: Context): GeminiConfigResult {
        val file = configFile(context)
        if (!file.exists()) return GeminiConfigResult.Missing
        return try {
            val json = JSONObject(file.readText())
            GeminiConfigResult.Loaded(
                GeminiConfig(
                    apiKey = json.optString(KEY_API_KEY, "").ifBlank { null },
                    model = json.optString(KEY_MODEL, "").ifBlank { null },
                ),
            )
        } catch (e: JSONException) {
            GeminiConfigResult.ParseError(e.message ?: "invalid JSON")
        } catch (e: java.io.IOException) {
            GeminiConfigResult.ParseError(e.message ?: "couldn't read file")
        }
    }
}
