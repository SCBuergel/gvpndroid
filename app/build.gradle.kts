plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.gnosisvpn.stub"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.gnosisvpn.stub"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.4.0"

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
        }
    }

    testOptions {
        // The WireGuard config parser reads Build.VERSION; let android.jar stubs return defaults.
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
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

    testImplementation("junit:junit:4.13.2")
}
