package com.lenscode.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class WeatherReading(
    val tempC: Double,
    val humidityPct: Int,
    val windKph: Double,
    val weatherCode: Int,
) {
    /** WMO weather code → a rough (emoji, label) pair — Open-Meteo's `current.weather_code`. */
    val icon: String get() = when (weatherCode) {
        0 -> "☀️"
        1, 2 -> "🌤️"
        3 -> "☁️"
        45, 48 -> "🌫️"
        51, 53, 55, 56, 57 -> "🌦️"
        61, 63, 65, 80, 81, 82 -> "🌧️"
        66, 67 -> "🌨️"
        71, 73, 75, 77, 85, 86 -> "❄️"
        95, 96, 99 -> "⛈️"
        else -> "🌡️"
    }

    val label: String get() = when (weatherCode) {
        0 -> "Clear"
        1, 2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        51, 53, 55, 56, 57 -> "Drizzle"
        61, 63, 65 -> "Rain"
        66, 67 -> "Freezing rain"
        71, 73, 75, 77 -> "Snow"
        80, 81, 82 -> "Rain showers"
        85, 86 -> "Snow showers"
        95, 96, 99 -> "Thunderstorm"
        else -> "—"
    }
}

/**
 * Home view's weather HUD (docs/snip-and-agent-management-plan.md follow-up
 * — "still missing" ideas raised alongside it). Open-Meteo needs no API key
 * and no account, just lat/lon — the simplest option that doesn't require
 * bundling a secret into the APK.
 */
object WeatherClient {
    private val http = OkHttpClient()

    suspend fun fetchCurrent(lat: Double, lon: Double): WeatherReading? = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,relative_humidity_2m,wind_speed_10m,weather_code"
            val request = Request.Builder().url(url).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val current = JSONObject(body).optJSONObject("current") ?: return@withContext null
                WeatherReading(
                    tempC = current.optDouble("temperature_2m", Double.NaN),
                    humidityPct = current.optInt("relative_humidity_2m", -1),
                    windKph = current.optDouble("wind_speed_10m", Double.NaN),
                    weatherCode = current.optInt("weather_code", -1),
                ).takeIf { !it.tempC.isNaN() }
            }
        } catch (e: Exception) {
            android.util.Log.w("LensCodeWeather", "fetchCurrent failed", e)
            null
        }
    }
}
