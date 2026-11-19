# gvpndroid

Android app for Gnosis VPN, the working basis for a production UI. The UI talks to one
interface, `Tunnel`, with two backends:

- **Gnosis VPN** (`GnosisTunnel`): the Gnosis VPN client core (Rust, gnosis_vpn-client main 7d745df: 1.0.1, IPC v2) via JNI;
  WireGuard carried over a 1-hop HOPR session to an exit, with PIX payments. Tested on prod
  (piz-palu) in redroid and on a Pixel.
- **Plain WireGuard** (`WireGuardTunnel`): the official prebuilt WireGuard library, for
  separating Android VPN problems from HOPR problems.

## Layout

App (`app/src/main/java/org/gnosisvpn/stub/`):

- `Tunnel.kt`: the interface the UI uses. Core: connect, disconnect, state, stats, listeners.
  Optional capabilities with "unsupported" defaults: locations, selected location,
  `selectLocation`, funding request, log level, `shutdown`. The UI never needs to know which
  backend it talks to.
- `GnosisTunnel.kt`: Gnosis backend. Starts the core with the app, polls its status
  (locations, funding needs), answers its tun requests, keeps the tun across reconnects and
  location switches (no traffic outside the VPN), `shutdown` stops the core.
- `GnosisProtocol.kt`: the JSON contract with the core (commands, status parsing); pure, unit
  tested against `rust/gnosis_vpn-android/tests/protocol.json`.
- `GnosisNative.kt`: the JNI functions of `libgnosisvpn.so`.
- `GnosisVpnService.kt`: the Gnosis backend's VpnService (builds the tun; Always-on start).
- `GnosisSetup.kt`: "Use Gnosis VPN" copies the bundled data files to app storage and writes
  the device settings; "Export identity" saves the node key file via the system file picker.
- `WireGuardTunnel.kt`, `ConfigStore.kt`: WireGuard backend and its QR-imported config.
- `TunnelService.kt`: foreground service with notification and Disconnect action.
- `App.kt`: picks the backend; enables exactly one of the two VpnServices so Always-on VPN
  starts the backend in use. `LogBuffer.kt`, `LogLevel.kt`: on-screen log (the core's lines
  too, since a phone has no logcat) at normal/verbose/debug.
- `ui/`: the app's UI (Jetpack Compose, Material 3), see "New UI" below.
- `MainActivity.kt`: the stub's original screen, now Settings → Developer screen: backend
  switch, Gnosis setup, identity export, log level, funding QR, raw log.

Rust and build:

- `rust/gnosis_vpn-android/`: the JNI bridge crate (plays the desktop root process for the
  core); `rust/patches/0001-android-target.patch`: Android support for gnosis_vpn-client 7d745df (main after v1.0.0; `CLIENT_REV` in `scripts/do-build/run.sh`),
  plus newer HOPR dependencies in its Cargo.toml/Cargo.lock: hopr-strategy 9.2.0 (curvy-sdk 0.2.2:
  the Curvy shield fixes that end the PIX payment loop), hoprnet master ff0076ef, edgli main 6455a823,
  the same as gvpndows.
- `scripts/do-build/`: builds on throwaway DigitalOcean droplets: the Rust core (`run.sh`) and the app (`app.sh`).
- `scripts/gnosis-assets.sh`: copies the client package's data files into the APK's assets.
- `scripts/build.sh`: memory-guarded build, lint, unit tests, then `scripts/check-apk.sh`
  (native libs incl. the Rust core, data files, 16 KB alignment, permissions, no secrets).
- `scripts/emulator.sh`, `scripts/e2e-emulator.sh`, `scripts/gnosis-emulator.sh`: redroid
  (Android in Docker) and end-to-end tests for both backends.
- `scripts/show-config-qr.sh`: shows a WireGuard config as a QR code on screen.

Not covered yet: 32-bit ARM phones get no Rust core (WireGuard only); the core's in-tunnel
health ping is skipped (the app is excluded from its own VPN), so idle tunnels may reconnect.

## New UI

The UI implements the design of the prototype in `~/mobile-UI` (README there: screens, deep
links) on top of the real Gnosis VPN core. There is no demo backend in the app: every value on
screen is what the core reports, and what it does not report is not shown.

- `ui/Engine.kt`: the UI's state machine (navigation, sheets, messages, notification rules,
  route grades), from the prototype's `engine.js`. The UI reads its fields and calls
  `act(name, args)`; every change bumps `rev`, which recomposes the UI from the root (strong
  skipping is off for this reason, `app/build.gradle.kts`).
- `ui/GnosisEngine.kt`: the engine the app runs, fed from `Tunnel` (`GnosisTunnel`):
  - start-up: the funding gate (the funding site, and the node address as a QR code with a GnosisScan link),
    then the core's real warm-up stages as progress, or the failure page;
  - exits: the core's destinations with route health (path value against the core's own 0.9
    weak-route threshold, first relays), quick-check latency, slots and load;
  - the session: connect phases, connected, reconnecting, stalls, and the data bought for it
    (PIX chunks, 660 MB each): data left, renewals (the core renews by itself, so there is no
    Auto-renew switch), payments pending or failed, an exit that went away;
  - the wallet (`balance` every 20 s): the Safe's wxHOPR, shown as data at the configured
    `price_per_byte`, the node's xDAI, the core's low/empty levels; a rise is "Payment received";
  - live traffic from WireGuard's counters (`nerd_stats`); Lockdown as Android reports it.
- `ui/Moments.kt`: the main screen's messages, screen codes and the ongoing notification;
  `ui/Home.kt`, `ui/Pages.kt`, `ui/Sheets.kt`: the screens; `ui/Graphics.kt`: the animations;
  `ui/Theme.kt`: the light and dark color roles and type (fonts in `res/font`: Inter, JetBrains
  Mono and Doto from google/fonts, SIL Open Font License 1.1).
- `ui/Platform.kt`: notifications with actions (balance low/empty, payment received, internet
  pauses soon, exit went offline), the Quick Settings tile, and the debug-only adb hook. The
  ongoing VPN notification is `TunnelService`'s, with the app's text and actions.

A fresh install sets up the Gnosis backend by itself (data files from the APK). Lockdown is
Android's Always-on VPN with "Block connections without VPN": the app cannot set it, so every
Lockdown control opens `Settings.ACTION_VPN_SETTINGS` and reads the state on return.

Tests: `GnosisLiveDataTest` checks the parsers against client 7d745df's IPC v2 types,
`GnosisEngineTest` drives the engine over a fake tunnel (gate, start-up, exits, sessions,
renewals, balance levels, payments, pauses). `EngineTest` keeps the shared state machine on the
prototype's rules with `DemoEngine`, the prototype's fake backend, which exists only in the
unit tests.

Testing in redroid (debug builds; the receiver needs adb's DUMP permission) opens pages directly:

```sh
adb shell am broadcast -n org.gnosisvpn.stub.debug/org.gnosisvpn.stub.ui.DebugActReceiver --es a go --es v settings
adb logcat -s GnosisUi    # "screen F2 Auto: Current Best Location", "notify u4 …"
```

Building with redroid running leaves too little memory on this machine: stop it
(`scripts/emulator.sh stop`) for the build, start it again to test.

## Developer log relay (DEBUG BUILDS ONLY, remove before production)

Debug builds send the app's log (app and core lines) to the developer: collected every 10 s,
sent every 30 s for the first 15 minutes after the app starts, then as ntfy's limit allows (every
few minutes). It is **on by default**; a bar at the top of every screen and the Android 12+ splash
say so, with an off switch (also in Settings → Logs). The parts are gzip'd and sealed to the
developer's X25519 key (the construction of gvpndows' identity backup, tag `gvpndroid-log-v1`)
and posted to a private ntfy.sh topic. Anonymous ntfy.sh allows 250 messages a day per IP: a token
bucket (32 messages, refilled at 210 a day) covers the start window and stays
under it. Lines that look like a problem (error, failed, stall, …) go out at once. A backlog of
more than one message goes as one sealed attachment (up to 2 MB, kept 3 hours), so nothing trails.
When a stall or reconnect starts, the app logs the core's connection state and nerd_stats.

- Code: `app/src/debug/java/.../DebugLogRelay.kt` and `DebugLogRelayCore.kt`; the splash
  notice in `app/src/debugRelay/res` (added only when the topic and key are present); the
  `DEBUG ONLY` blocks in `app/build.gradle.kts` and `scripts/check-apk.sh`. Release builds do not contain it: they get the
  no-op `app/src/release/.../DebugLogRelay.kt`, which hides the bar and the setting.
- Topic and public key: read at build time from `~/.config/gvpndroid-secrets/` (`ntfy-topic`,
  `log-x25519.pub`). Not in the repo. Without them the relay is off and hidden.
- The app is excluded from its own VPN, so it posts from the device's own IP. The topic is in
  every debug APK, including ones shared by link: whoever has the APK can see the messages' titles
  and timing and post forged messages. The contents stay sealed. Rotate the topic if that matters.
- Read the logs: `scripts/relay-read.py [SINCE]`, or `-f` to follow. It needs `log-x25519.key`
  from the same folder.
- Removing it: delete those files and the `DEBUG ONLY` blocks, then delete the calls the compiler
  reports.

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
  gnosis_vpn-client checkout (7d745df) with `rust/patches/0001-android-target.patch` applied.
  It plays the desktop root process: the tun comes from `GnosisVpnService`, the kill switch is
  Android's Always-on VPN, in-tunnel pings are skipped (the app is excluded from its own VPN).
- Device settings (outside the repo): `gnosis.properties` (`blokli_url`, `network`,
  `destination` = exit address or id) and `gnosis.toml` (the packaged v7 config plus
  `geoip_db` and `curvy_zk_dir` pointing at `no_backup/gnosis-share/`, whose files come from
  the official gnosisvpn package).
- The node identity lives in `no_backup/gnosis/`. Back it up before funding it (in the app:
  Export identity). A new node shows its address as a QR code while it waits for funds.

### On a phone (no adb)

    scripts/gnosis-assets.sh UNPACKED_DEB_ROOT     # once: data files into the APK's assets
    scripts/build.sh assembleDebug -PphoneOnly     # ARM-only APK, then PHONE_ONLY=1 scripts/check-apk.sh

In the app: "Use Gnosis VPN", then Connect. Disconnect before installing an update.

### Build the Rust core

    scripts/do-build/run.sh                      # both ABIs, fast profile, about 9 min
    ABIS=arm64-v8a scripts/do-build/run.sh       # phone only
    KEEP=1 scripts/do-build/run.sh               # keep the droplet: rebuild.sh, then down.sh
    scripts/do-build/app.sh                      # the APK: assemble, lint, unit tests on a droplet (~15 min, 1 h deadline)

Every build first runs the crate's contract tests (`cargo test -p gnosis_vpn-android`, host
target, part of `build-remote.sh`; `TESTS=0` skips them); a failing test stops the build. Rebuild after every change under `rust/`: the libraries in `jniLibs/` are not tracked.

It rents a DigitalOcean c-32 droplet, builds, copies the libraries into
`app/src/main/jniLibs/` (gitignored), and deletes the droplet on every exit path, with a 6 h
deletion deadline as a backstop. `PROFILE=release` uses the client's fat LTO profile
(about 18 min for two ABIs, mostly single-threaded).

### Test in redroid

    scripts/emulator.sh start
    scripts/gnosis-emulator.sh setup CONF_DIR SHARE_DIR
    scripts/gnosis-emulator.sh connect
    scripts/gnosis-emulator.sh log
    scripts/gnosis-emulator.sh e2e     # PASS/FAIL; manual only: each run pays the exit (PIX)

Pitfalls: reinstalling or force-stopping the app while its VPN is up can break redroid's
networking until `emulator.sh stop`/`start`; never `adb root` while the VPN is up (a root adbd's replies are routed into the
tunnel and adb goes offline); redroid's lmkd floods logcat, so `emulator.sh` stops it.
