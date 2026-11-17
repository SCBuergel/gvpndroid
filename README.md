# gvpndroid

Stub Android app for Gnosis VPN: a plain WireGuard VPN built with the official
prebuilt WireGuard tunnel library, behind a small `Tunnel` interface so the backend
can later be swapped for the Gnosis VPN client. No Rust, no HOPR, no NDK.

## Layout

- `app/src/main/java/org/gnosisvpn/stub/Tunnel.kt`: the interface the UI uses
  (connect, disconnect, state, stats). Backends implement it.
- `WireGuardTunnel.kt`: backend using `com.wireguard.android:tunnel` (GoBackend, prebuilt wireguard-go).
- `TunnelService.kt`: foreground service with notification and Disconnect action; restarts
  the tunnel after process death; the always-on callback starts it too.
- `ConfigStore.kt`: the WireGuard config, imported on the phone by QR scan into
  app-private storage. Configs and keys are never in the APK or the repo.
- `App.kt`, `LogBuffer.kt`: logging of state, network changes, kill switch state, and the
  last crash (shown on the next start, since there is no logcat without adb).
- `MainActivity.kt`: Connect/Disconnect, Import QR, VPN settings, status, stats, log.

## Config

Keep peer configs outside the repo (here: `~/.config/gvpndroid-secrets/`, mode 600).
`scripts/show-config-qr.sh PEER.conf` shows one as a QR code on screen for the app to scan.

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

The developer's main phone profile is signed in to a Google account enrolled in
Google's Advanced Protection Program, which blocks every app not from Google Play
("App blocked by Advanced Protection"), even with the phone's own Device protection
switch off. The app is therefore tested in a separate Android user profile that has
no Google account. That profile has no access to Claude sessions, so builds reach it
through a short-lived public link (litterbox.catbox.moe, 1 hour expiry), typed into
Chrome there:

    curl -F reqtype=fileupload -F time=1h -F fileToUpload=@app/build/outputs/apk/debug/app-debug.apk \
        https://litterbox.catbox.moe/resources/internals/api.php

Alternatively, adb over Wi-Fi (untested on this phone; may be blocked by Advanced Protection):

    . ~/android/env.sh
    adb pair PHONE_IP:PAIR_PORT       # code from Developer options > Wireless debugging > Pair
    adb connect PHONE_IP:PORT
    adb install -r --user USER_ID app/build/outputs/apk/debug/app-debug.apk
    adb logcat -s GnosisVpnStub
