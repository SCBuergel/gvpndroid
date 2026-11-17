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
- `app/src/test/`: JVM unit tests (config summary carries no keys; config migration).
- `scripts/build.sh`: memory-guarded build, lint and unit tests, then `scripts/check-apk.sh`
  (native libs, 16 KB alignment, permissions, required classes, no packaged secrets).
- `scripts/show-config-qr.sh`: shows a config as a QR code on screen.
- `scripts/emulator.sh`, `scripts/e2e-emulator.sh`: local Android in Docker (redroid) and an
  end-to-end VPN test against it; see "Test without a phone".

## Config

Keep peer configs outside the repo (here: `~/.config/gvpndroid-secrets/`, mode 600).
`scripts/show-config-qr.sh PEER.conf` shows one as a QR code on screen for the app to scan.

## Toolchain (outside the repo, under `~/android`, survives qube reboots)

- `~/android/jdk`: Temurin JDK 17
- `~/android/sdk`: cmdline-tools, platform-tools, platforms;android-35, build-tools;35.0.0
- `~/android/env.sh`: sets JAVA_HOME, ANDROID_HOME and PATH

## Build

    scripts/build.sh            # assembleDebug, lint, unit tests, then scripts/check-apk.sh
    scripts/build.sh assembleDebug   # only the given Gradle tasks

The script refuses to start with less than 2200 MB of available memory or while
another build holds the lock. It runs Gradle without a daemon, with one worker and a
1.5 GB heap (`gradle.properties`), inside a systemd scope capped at 2300 MB, so an
overshoot kills the build and not other processes. The first build peaked at about
1.7 GB of resident memory.

Output: `app/build/outputs/apk/debug/app-debug.apk` (package `org.gnosisvpn.stub.debug`).
The debug APK carries wireguard-go for arm64-v8a, armeabi-v7a and x86_64, so the same file
runs on a phone and in redroid; release builds are ARM only. `check-apk.sh` requires those
three and rejects 32-bit x86.

## Test without a phone (redroid)

This qube has no KVM, so the Android emulator cannot run. Instead, redroid runs Android 14
(x86_64) as a Docker container on the host kernel, using its binder and tun, and adb reaches
it at `localhost:5555`.

    scripts/emulator.sh start     # start or create the container, wait for boot, adb connect
    scripts/emulator.sh status    # state, memory use, adb
    scripts/emulator.sh stop      # stop it; image and data stay
    scripts/e2e-emulator.sh ~/.config/gvpndroid-secrets/gvpndroid-emu.conf

The e2e script installs the debug APK, grants the notification permission and VPN
consent without UI (`pm grant`, `appops set ... ACTIVATE_VPN allow`), copies the config into
the app's `no_backup/tunnel.conf` with `run-as` (no camera, no QR scan), then taps through the
UI with `uiautomator dump` and `input tap`. It checks, printing PASS/FAIL per step: connected
status and a handshake in the app's stats, a VPN network in `dumpsys connectivity`, the
default route via tun0, the public IP from inside Android (`nc` to ifconfig.me), and that the
HTTP reply arrived through tun0; then process death (the system restarts the service and the
tunnel), the notification's Disconnect action, and that after Disconnect the route and IP
return to the pre-connect state. It stops the container at the end (`KEEP=1` keeps it).
`EXPECT_IP` overrides the expected tunnel IP (default: the config's Endpoint host).

Use a router peer reserved for the container, never the phone's: one key in two places
breaks both. On this LAN the qube already exits through the router's public IP, so the
before/after IP is the same; the tun0 route and byte counters are what prove the tunnel.

Details a later session needs:

- Image `redroid/redroid:14.0.0-latest` (about 870 MB download, 2.1 GB on disk). Docker's
  data-root here is `/home/user/.build-host/docker`, so the image survives reboots.
  Android's `/data` is `~/android/redroid-data`.
- Boot takes about 15 to 25 s; the container uses about 700 to 760 MB and is capped at 2 GB.
  `emulator.sh start` wants 1500 MB available (`FORCE=1` overrides).
- redroid mounts binderfs itself; the container runs `--privileged`.
- Android's VpnService opens `/dev/tun`, which redroid lacks (only `/dev/net/tun`), so
  `establish()` fails with "Cannot create interface" until the node exists.
  `emulator.sh start` creates it on every start.
- netd needs the legacy iptables tables and xt modules. Without them it cannot mark
  incoming packets, the VPN captures adbd's replies, and adb goes offline as soon as the
  tunnel is up. `emulator.sh start` loads the modules with `sudo modprobe`; they are lost
  on reboot, so start the container through the script. `quota2` (Android-only) stays
  missing, which affects only data-usage alerts.
- The VPN consent from `appops` did not survive a container restart; the e2e script sets
  it on every run.

## Install on a phone

The developer's main phone profile is signed in to a Google account enrolled in
Google's Advanced Protection Program, which blocks every app not from Google Play
("App blocked by Advanced Protection"), even with the phone's own Device protection
switch off. The app is therefore tested in a separate Android user profile that has
no Google account. That profile has no access to Claude sessions, so builds reach it
through a short-lived public link (litterbox.catbox.moe, 1 hour expiry), typed into
Chrome there. Before uploading, note the APK's `sha256sum`; download it back and
compare, and compare again on the phone if it has a checksum tool:

    curl -F reqtype=fileupload -F time=1h -F fileToUpload=@app/build/outputs/apk/debug/app-debug.apk \
        https://litterbox.catbox.moe/resources/internals/api.php

Alternatively, adb over Wi-Fi (untested on this phone; may be blocked by Advanced Protection):

    . ~/android/env.sh
    adb pair PHONE_IP:PAIR_PORT       # code from Developer options > Wireless debugging > Pair
    adb connect PHONE_IP:PORT
    adb install -r --user USER_ID app/build/outputs/apk/debug/app-debug.apk
    adb logcat -s GnosisVpnStub

## Gnosis VPN backend (Rust core)

The app has two `Tunnel` backends: plain WireGuard (`WireGuardTunnel`) and the Gnosis VPN
client core (`GnosisTunnel`, WireGuard carried over a HOPR session). The Gnosis backend is used
when `no_backup/gnosis.properties` exists in the app's private storage.

- `rust/gnosis_vpn-android`: JNI bridge, built as `libgnosisvpn.so` inside a
  gnosis_vpn-client checkout (v1.0.0) with `rust/patches/0001-android-target.patch` applied.
  It plays the desktop root process: the tun comes from `GnosisVpnService`, the kill switch is
  Android's Always-on VPN, in-tunnel pings are skipped (the app is excluded from its own VPN).
- Device settings (outside the repo): `gnosis.properties` (`blokli_url`, `network`,
  `destination` = exit address or id) and `gnosis.toml` (the packaged v7 config plus
  `geoip_db` and `curvy_zk_dir` pointing at `no_backup/gnosis-share/`, whose files come from
  the official gnosisvpn package).
- The node identity lives in `no_backup/gnosis/`. Back it up before funding it.

### Build the Rust core

    scripts/do-build/run.sh                      # both ABIs, fast profile, about 9 min
    ABIS=arm64-v8a scripts/do-build/run.sh       # phone only

It rents a DigitalOcean c-32 droplet, builds, copies the libraries into
`app/src/main/jniLibs/` (gitignored), and deletes the droplet on every exit path, with a 6 h
deletion deadline as a backstop. `PROFILE=release` uses the client's fat LTO profile
(about 18 min for two ABIs, mostly single-threaded).

### Test in redroid

    scripts/emulator.sh start
    scripts/gnosis-emulator.sh setup CONF_DIR SHARE_DIR
    scripts/gnosis-emulator.sh connect
    scripts/gnosis-emulator.sh log

Pitfalls: never `adb root` while the VPN is up (a root adbd's replies are routed into the
tunnel and adb goes offline); redroid's lmkd floods logcat, so `emulator.sh` stops it.
