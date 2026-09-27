plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.lenscode.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lenscode.app"
        // Android XR baseline (XR preview images are API 34).
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // lenscode-bridge WebSocket URL. Defaults to 10.0.2.2 (the emulator's
        // host-loopback alias); override for a real device on the same LAN
        // with -PbridgeUrl="ws://<host-lan-ip>:8137" (adb reverse can't help
        // here since the app dials a literal IP, not localhost).
        val bridgeUrl = (project.findProperty("bridgeUrl") as String?) ?: "ws://10.0.2.2:8137"
        buildConfigField("String", "BRIDGE_URL", "\"$bridgeUrl\"")
        // Agentic voice routing (com.lenscode.app.voice.KoogVoiceRouter) — Gemini
        // via Koog, on-device. Empty key = the router falls back to the
        // deterministic local one (VoiceCommandRouter.kt), no network call
        // attempted. Set with:
        //   ./gradlew :app:assembleDebug -PbridgeUrl=... -PgeminiApiKey=AIza...
        // then adb install -r the resulting APK — see MEMORY/plan docs.
        val geminiApiKey = (project.findProperty("geminiApiKey") as String?) ?: ""
        val geminiModel = (project.findProperty("geminiModel") as String?) ?: "gemini-3.8-flash"
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
        buildConfigField("String", "GEMINI_MODEL", "\"$geminiModel\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            // Koog's transitive HTTP client deps (netty et al.) ship duplicate
            // META-INF metadata files across several jars — none of these are
            // needed at runtime on Android.
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Jetpack XR SDK
    implementation("androidx.xr.compose:compose:1.0.0-alpha17")
    implementation("androidx.xr.scenecore:scenecore:1.0.0-beta02")
    implementation("androidx.xr.runtime:runtime:1.0.0-beta02")

    // Bridge protocol transport (JSON handled by the platform's built-in org.json)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Agentic voice command routing (on-device, Gemini via Koog — see
    // com.lenscode.app.voice.KoogVoiceRouter). Koog is JetBrains' Kotlin
    // multiplatform AI agent framework; Android is one of its supported targets.
    implementation("ai.koog:koog-agents:0.5.0")
    // Koog's default Ktor engine (CIO) implements its own pure-Kotlin TLS
    // trust manager that only overrides the legacy 2-arg checkServerTrusted —
    // confirmed live, this crashes with a CertificateException against
    // Android's hostname-aware RootTrustManager (which the framework
    // requires once ANY <domain-config> exists in network_security_config.xml,
    // even for hosts, like Google's own API, that aren't listed in it).
    // OkHttp's Ktor engine implements the modern interface correctly.
    implementation("io.ktor:ktor-client-okhttp:3.3.0")

    testImplementation("junit:junit:4.13.2")
}
