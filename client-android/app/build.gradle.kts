import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
}

detekt {
    // Existing findings are recorded in detekt-baseline.xml — new violations
    // fail the build; whittle the baseline down instead of disabling rules.
    baseline = file("detekt-baseline.xml")
    buildUponDefaultConfig = true
}

// Release signing comes from client-android/keystore.properties (gitignored):
//   storeFile=../../path/to/upload.jks   (relative to client-android/)
//   storePassword=...  keyAlias=...  keyPassword=...
// Without that file the release build still works but is left unsigned, so a
// plain checkout never needs (or sees) the key.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.lenscode.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lenscode.app"
        // Android XR baseline (XR preview images are API 34).
        minSdk = 34
        targetSdk = 36
        // versionCode must rise with every build handed to anyone (Android uses it to
        // decide what is an upgrade); versionName is the semver shown to people.
        versionCode = 5
        versionName = "0.1.0-alpha.3"
        // lenscode-bridge WebSocket URL. Defaults to 10.0.2.2 (the emulator's
        // host-loopback alias); override for a real device on the same LAN
        // with -PbridgeUrl="ws://<host-lan-ip>:8137" (adb reverse can't help
        // here since the app dials a literal IP, not localhost).
        val bridgeUrl = (project.findProperty("bridgeUrl") as String?) ?: "ws://10.0.2.2:8137"
        buildConfigField("String", "BRIDGE_URL", "\"$bridgeUrl\"")
        // Agentic voice routing (com.lenscode.app.voice.KoogVoiceRouter) — Gemini
        // via Koog, on-device. Empty key = the router falls back to the
        // deterministic local one (VoiceCommandRouter.kt), no network call
        // attempted. Prefer pasting the key at the bridge's /setup page (see CLAUDE.md)
        // so it never lands in an APK; this is the leftover fallback. To bake one in:
        //   ./gradlew :app:assembleDebug -PbridgeUrl=... -PgeminiApiKey=AIza...
        // then adb install -r the resulting APK.
        val geminiApiKey = (project.findProperty("geminiApiKey") as String?) ?: ""
        val geminiModel = (project.findProperty("geminiModel") as String?) ?: "gemini-3.8-flash"
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
        buildConfigField("String", "GEMINI_MODEL", "\"$geminiModel\"")
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // R8 is off until keep rules are tested — kotlinx-serialization and
            // Koog's reflective internals will strip/break without them.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
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
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.core.splashscreen)

    // Jetpack XR SDK
    implementation(libs.xr.compose)
    implementation(libs.xr.scenecore)
    implementation(libs.xr.runtime)

    // Bridge protocol transport (JSON handled by the platform's built-in org.json)
    implementation(libs.okhttp)

    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Agentic voice command routing (on-device, Gemini via Koog — see
    // com.lenscode.app.voice.KoogVoiceRouter). Koog is JetBrains' Kotlin
    // multiplatform AI agent framework; Android is one of its supported targets.
    implementation(libs.koog.agents)
    // Koog's default Ktor engine (CIO) implements its own pure-Kotlin TLS
    // trust manager that only overrides the legacy 2-arg checkServerTrusted —
    // confirmed live, this crashes with a CertificateException against
    // Android's hostname-aware RootTrustManager (which the framework
    // requires once ANY <domain-config> exists in network_security_config.xml,
    // even for hosts, like Google's own API, that aren't listed in it).
    // OkHttp's Ktor engine implements the modern interface correctly.
    implementation(libs.ktor.client.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
