# gvpndroid

Stub Android app for Gnosis VPN. Milestone 1: build pipeline and a fake tunnel.
No Rust, no HOPR, no NDK.

## Layout

- `app/src/main/java/org/gnosisvpn/stub/Tunnel.kt`: the interface the UI uses
  (connect, disconnect, state, stats). Backends implement it.
- `FakeTunnel.kt`: milestone 1 backend, moves no traffic.
- `MainActivity.kt`: one screen with a button, a status line and a log.

## Toolchain (outside the repo, under `~/android`, survives qube reboots)

- `~/android/jdk`: Temurin JDK 17
- `~/android/sdk`: cmdline-tools, platform-tools, platforms;android-35, build-tools;35.0.0
- `~/android/env.sh`: sets JAVA_HOME, ANDROID_HOME and PATH

## Build

    scripts/build.sh            # assembleDebug
    scripts/build.sh clean assembleDebug

The script refuses to start with less than 2200 MB of available memory or while
another build holds the lock. It runs Gradle without a daemon, with one worker and a
1.5 GB heap (`gradle.properties`), inside a systemd scope capped at 2300 MB, so an
overshoot kills the build and not other processes. The first build peaked at about
1.7 GB of resident memory.

Output: `app/build/outputs/apk/debug/app-debug.apk` (package `org.gnosisvpn.stub.debug`).

## Install on a phone

Download the APK on the phone and open it (allow "install unknown apps" for the app
you opened it with), or use adb over Wi-Fi:

    . ~/android/env.sh
    adb pair PHONE_IP:PAIR_PORT       # code from Developer options > Wireless debugging > Pair
    adb connect PHONE_IP:PORT
    adb install -r app/build/outputs/apk/debug/app-debug.apk
    adb logcat -s GnosisVpnStub
