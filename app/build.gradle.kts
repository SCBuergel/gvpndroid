plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---- DEBUG ONLY. REMOVE BEFORE PRODUCTION: the developer log relay (app/src/debug/.../DebugLogRelay.kt).
// The ntfy.sh topic and the developer's X25519 public key are read at build time from
// $GVPNDROID_SECRETS (default ~/.config/gvpndroid-secrets: ntfy-topic, log-x25519.pub) and baked
// into debug builds only. They are not in the repository. Without the files the relay is off and hidden.
val relaySecrets = File(System.getenv("GVPNDROID_SECRETS") ?: "${System.getProperty("user.home")}/.config/gvpndroid-secrets")
fun relaySecret(name: String, ok: Regex): String =
    File(relaySecrets, name).takeIf { it.isFile }?.readText()?.trim()?.takeIf { ok.matches(it) } ?: ""
val relayTopic = relaySecret("ntfy-topic", Regex("[A-Za-z0-9_-]{16,64}"))
val relayPubkey = relaySecret("log-x25519.pub", Regex("[A-Za-z0-9+/]{43}="))
// ---- end DEBUG ONLY

android {
    namespace = "org.gnosisvpn.stub"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.gnosisvpn.stub"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.5.0"

        // Phones: keep the prebuilt wireguard-go libraries for ARM, drop x86.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            // Debug builds also run in redroid (x86_64 Android in Docker, scripts/emulator.sh),
            // so the same debug APK works on the phone and in the container. No 32-bit x86.
            // -PphoneOnly leaves x86_64 out (smaller APK to download onto a phone).
            if (!project.hasProperty("phoneOnly")) ndk { abiFilters += "x86_64" }
            // DEBUG ONLY. REMOVE BEFORE PRODUCTION: developer log relay settings (see the top of this file).
            buildConfigField("String", "DEBUG_LOG_TOPIC", "\"$relayTopic\"")
            buildConfigField("String", "DEBUG_LOG_PUBKEY", "\"$relayPubkey\"")
        }
    }

    testOptions {
        // The WireGuard config parser reads Build.VERSION; let android.jar stubs return defaults.
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures { compose = true; buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

composeCompiler {
    // The UI reads the engine's plain fields and recomposes from the root on every engine change
    // (Engine.rev). Strong skipping would skip children whose engine parameter is the same instance.
    featureFlags = setOf(org.jetbrains.kotlin.compose.compiler.gradle.ComposeFeatureFlag.StrongSkipping.disabled())
}

dependencies {
    // Official WireGuard tunnel library with prebuilt wireguard-go native code.
    implementation("com.wireguard.android:tunnel:1.0.20260102")
    // QR scanner for importing the config on the phone.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // The scanner calls androidx.core (ContextCompat, ActivityCompat) at runtime but does not declare it.
    implementation("androidx.core:core:1.13.1")
    // Must equal the rustls-platform-verifier-android version in the Rust core's Cargo.lock.
    implementation("org.rustls:rustls-platform-verifier:0.2.0")

    // The UI: Jetpack Compose with Material 3.
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    // The "Discovering the network" animation (the demo's Lottie file).
    implementation("com.airbnb.android:lottie-compose:6.6.2")

    // DEBUG ONLY. REMOVE BEFORE PRODUCTION: X25519 + ChaCha20-Poly1305 for the developer log relay
    // (Android has X25519 only from API 33). debugImplementation: release builds do not get it.
    debugImplementation("org.bouncycastle:bcprov-jdk18on:1.77")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for unit tests (android.jar's is a stub that returns defaults).
    testImplementation("org.json:json:20240303")
}
