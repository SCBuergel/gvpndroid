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
        versionCode = 3
        versionName = "0.2.2"

        // Phones only: keep the prebuilt wireguard-go libraries for ARM, drop x86.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
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
}
