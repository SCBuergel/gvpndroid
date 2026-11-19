# AGENTS.md: maintaining and developing gvpndroid

Guide for coding agents and maintainers. `README.md` is the human introduction; keep it in
sync when behaviour it describes changes, but put implementation detail here.

## Hard rules

- **Real core only.** The APK has no demo engine or demo controls. Every value on screen is what
  the core reports, and what it doesn't report isn't shown. Fakes belong in `app/src/test`
  only; `DemoEngine` exists only there.
- **No secrets in the repo, in commits or on command lines.** WireGuard configs, keys, the ntfy
  topic and the log keys live in `~/.config/gvpndroid-secrets/` (mode 600). `.gitignore` blocks
  `*.conf`, `*.key`, `*.keystore` and `*.jks`, and `scripts/check-apk.sh` rejects secrets in
  the APK. Never print key material.
- **Funds are real.** The node identity controls a Safe with wxHOPR on Gnosis Chain. Never
  delete or overwrite `no_backup/gnosis/`. Back up the identity before funding. Never move
  funds or redeem funding codes without the owner's explicit approval of the exact amount, and
  never write funding codes into files.
- **The debug log relay must never reach release builds**, and it sends only sealed
  (encrypted) parts. Keep the `DEBUG ONLY` blocks intact; see [Debug log relay](#debug-log-relay).
- **Rented machines are always deleted.** Every DigitalOcean script deletes its droplet on every
  exit path and arms a deletion deadline first. Don't weaken that, and verify that the droplet
  is gone after a run.
- **Push only when asked.** `origin` is `git@github.com:SCBuergel/gvpndroid.git`; local `ui` is
  pushed as `main`.

## Tests: what to run

| What changed | Run |
|---|---|
| Anything (the gate) | `. ~/android/env.sh && ./gradlew --no-daemon --max-workers=1 testDebugUnitTest lintDebug` |
| A full build with the APK check | `scripts/build.sh` (assembleDebug, lint, unit tests, `scripts/check-apk.sh`) |
| Anything under `rust/` | `scripts/do-build/run.sh` on a droplet: the contract tests (`cargo test -p gnosis_vpn-android`), then the core build |
| The droplet-id wait in `scripts/do-build/lib.sh` | `scripts/do-build/test-await-id.sh` (about 35 s, manual) |
| VPN behaviour end to end | redroid, see [Testing in redroid](#testing-in-redroid) |

- **Rust is not in the local gate.** The gate never compiles the Rust crate; its tests need the
  full upstream workspace, which is too heavy for this machine. Run `run.sh` before merging Rust
  changes, and after every change under `rust/`, because the libraries in `jniLibs/` are not
  tracked.
- **Test inputs:** the unit tests also run the scripts' regression tests:
  - `scripts/test_r6_1_relay_attachment.py` and `scripts/test_r6_2_relay_follow.py`;
  - `scripts/do-build/test-await-job.sh`;
  - the R7_* script tests.

  `rust/gnosis_vpn-android/tests/protocol.json`, the patch and `scripts/` are declared as test
  inputs in `app/build.gradle.kts`. A test that reads a file outside `app/` must declare it
  there, or Gradle reports the tests up to date and skips them (`R7_12TestInputsTest` checks
  this).
- **Test naming:** regression tests are named after their finding, for example
  `R2_4StartRetryTest.kt`, one file per finding.
- **Contract tests:**
  - `GnosisLiveDataTest` checks the parsers against client 8a0e3be's IPC v3 types;
  - `GnosisEngineTest` drives the engine over a fake tunnel;
  - `EngineTest` keeps the shared state machine on the UI prototype's rules.

## Memory limits

Build only through `scripts/build.sh`, or through Gradle with the flags above. The script:
- refuses to start with less than 2200 MB available, or while another build holds
  `~/android/.build.lock`;
- runs Gradle without a daemon, with one worker and a 1.5 GB heap (`gradle.properties`);
- caps the build with a systemd scope at 2300 MB, so an overshoot kills the build and not
  other processes.

A full build peaks at about 1.7 GB resident. Don't run a Gradle build and redroid together on a
low-memory machine. If local memory is short, build on a droplet (`scripts/do-build/app.sh`)
instead of waiting.

## Architecture

### Tunnel interface

The UI talks to one interface, `Tunnel` (`app/src/main/java/org/gnosisvpn/stub/Tunnel.kt`):
- **Core members:** connect, disconnect, state, stats and listeners.
- **Optional capabilities**, with "unsupported" defaults: locations, the selected location,
  `selectLocation`, the funding request, the log level and `shutdown`.

The UI never needs to know which backend it talks to.

- **`GnosisTunnel.kt`: the Gnosis backend.**
  - It starts the core with the app and polls its status (locations, funding needs).
  - It answers the core's tun requests and keeps the tun across reconnects and location
    switches, so no traffic leaves outside the VPN.
  - `shutdown` stops the core.
  - The helper classes `ConnectIntent`, `TunnelSequence`, `TunSlot`, `StartRetry`, `CoreExit`,
    `TunFailure` and `TunnelCommand` hold its ordering rules. These rules cover connect during
    a disconnect, a switch racing a disconnect, refusing a tun request after a stop, and
    bounded core restarts. Change them together with their R*-tests.
- **`GnosisProtocol.kt`: the JSON contract with the core** (commands, status parsing). It is
  pure code, unit tested against `rust/gnosis_vpn-android/tests/protocol.json`, the fixture the
  Rust side is tested against too.
- **`GnosisNative.kt`:** the JNI functions of `libgnosisvpn.so`.
- **`GnosisVpnService.kt`:** the Gnosis backend's VpnService. It builds the tun and handles the
  Always-on start.
- **`GnosisSetup.kt`:** "Use Gnosis VPN" copies the bundled data files to app storage and
  writes the device settings. "Export identity" saves the node key file through the system file
  picker.
- **`WireGuardTunnel.kt`, `ConfigStore.kt`:** the WireGuard backend and its QR-imported config.
- **`TunnelService.kt`:** the foreground service with its notification and Disconnect action.
  It decides whether to stop from the tunnel's current state, not from a posted event.
- **`App.kt`:** picks the backend and enables exactly one of the two VpnServices, so Always-on
  VPN starts the backend in use.
- **`LogBuffer.kt`, `LogLevel.kt`:** the on-screen log, which includes the core's lines because
  a phone has no logcat to read them from. Levels are normal, verbose and debug.
- **`MainActivity.kt`:** the original stub screen, now Settings → Developer screen. It has the
  backend switch, Gnosis setup, identity export, the log level, the funding QR code and the raw
  log.

A fresh install sets up the Gnosis backend by itself, using the data files from the APK. A
WireGuard choice on the developer screen is kept across restarts. The Gnosis backend is used
when `no_backup/gnosis.properties` exists in the app's private storage.

### UI (`app/src/main/java/org/gnosisvpn/stub/ui/`)

The UI implements the design of a separate HTML prototype (`~/mobile-UI`, not in this repo) on
top of the real core.

- **`Engine.kt`: the UI's state machine**, ported from the prototype's `engine.js`. It covers
  navigation, sheets, messages, notification rules and route grades. The UI reads its fields
  and calls `act(name, args)`. Every change bumps `rev`, which recomposes the UI from the root;
  for that reason strong skipping is off in `app/build.gradle.kts`.
- **`GnosisEngine.kt`: the engine the app runs**, fed from `Tunnel`. It handles:
  - **Start-up:** the funding gate (run mode `preparing_safe`: the funding site, and the node
    address as a QR code with a GnosisScan link), then the core's real start-up stages, or the
    failure page.
  - **Exits:** the core's destinations with route health, quick-check latency, slots and load.
    Route health compares the path value against the core's own 0.9 weak-route threshold and
    also shows the first relays.
  - **The session:** connect phases, connected, reconnecting and stalls. It also tracks the
    data bought for the session (PIX chunks of 660 MB): data left, renewals, pending or failed
    payments, and an exit that went away. The core renews by itself, so there is no Auto-renew
    switch.
  - **The wallet:** `balance` every 20 s. It shows the Safe's wxHOPR as data at the configured
    `price_per_byte`, the node's xDAI, and the core's low and empty levels. A rise in the
    balance is "Payment received". The funding gate's "node is funded" note fires only when the
    core itself reports leaving the funding step.
  - **Live traffic and Lockdown:** live traffic comes from WireGuard's counters (`nerd_stats`),
    and Lockdown is shown as Android reports it.
- **`Moments.kt`:** the main screen's messages, the screen codes and the ongoing notification.
- **`Home.kt`, `Pages.kt`, `Sheets.kt`:** the screens. **`Graphics.kt`:** the animations.
- **`Theme.kt`:** the light and dark color roles and the type. The fonts in `res/font` are
  Inter, JetBrains Mono and Doto from google/fonts, under the SIL OFL 1.1.
- **`Platform.kt`:** notifications with actions (balance low or empty, payment received,
  internet pauses soon, exit went offline), the Quick Settings tile, and the debug-only adb
  hook.
  - Notification actions that need a screen go through the activity. Android 12+ blocks
    activity starts from receivers and from backgrounded activities.
  - The ongoing VPN notification belongs to `TunnelService`, with the app's text and actions.

Lockdown is Android's Always-on VPN with "Block connections without VPN". The app can't set it,
so every Lockdown control opens `Settings.ACTION_VPN_SETTINGS` and reads the state on return.

Debug builds (whose receiver needs adb's DUMP permission) can open pages directly:

```sh
adb shell am broadcast -n org.gnosisvpn.stub.debug/org.gnosisvpn.stub.ui.DebugActReceiver --es a go --es v settings
adb logcat -s GnosisUi    # "screen F2 Auto: Current Best Location", "notify u4 ..."
```

### Rust core and bridge

- **`rust/gnosis_vpn-android/`: the JNI bridge crate.** It is built as `libgnosisvpn.so` inside
  a gnosis_vpn-client checkout (`CLIENT_REV` in `scripts/do-build/run.sh`, currently 8a0e3be,
  v1.1.1) with `rust/patches/0001-android-target.patch` applied. It plays the part of the
  desktop root process:
  - the tun comes from `GnosisVpnService`;
  - the kill switch is Android's Always-on VPN;
  - in-tunnel pings are skipped, because the app is excluded from its own VPN.

  Tun file descriptors go only to the core that asked for one. Descriptors no core took are
  closed when the tun is closed for good (`dropQueuedTuns`), and the core's receive gives up
  after 5 s.
- **The patch:**
  - It adds Android support (`killswitch/android.rs`).
  - It makes `Core::start` shut the node down on every return, because in the app no process
    exit does that.
  - Its `Cargo.lock` moves hoprnet master from 2acc3a58 to ff0076ef. This transport fix judges
    a relayer over the hop its replies use.

  The client already pins hopr-strategy 9.2.0 and edgli main 7bafb47.
- **Bumping the client:**
  1. Regenerate the patch against the new commit.
  2. Update `CLIENT_REV`.
  3. Update `tests/protocol.json` and the Kotlin parsers together.
  4. Run `run.sh`.
- **Device settings, outside the repo:**
  - `gnosis.properties`: `blokli_url`, `network`, and `destination` (an exit address or id).
  - `gnosis.toml`: the packaged v7 config plus `geoip_db` and `curvy_zk_dir`. Both point at
    `no_backup/gnosis-share/`, whose files come from the official gnosisvpn package via
    `scripts/gnosis-assets.sh UNPACKED_DEB_ROOT`.
- **Identity:** the node identity lives in `no_backup/gnosis/`. A new node shows its address as
  a QR code while it waits for funds.

Not covered yet: 32-bit ARM phones get no Rust core (WireGuard only). Because the in-tunnel
health ping is skipped, idle tunnels may reconnect.

## Building

The toolchain lives outside the repo, under `~/android`:
- `jdk`: Temurin JDK 17;
- `sdk`: cmdline-tools, platform-tools, platforms;android-35 and build-tools;35.0.0;
- `env.sh`: sets `JAVA_HOME`, `ANDROID_HOME` and `PATH`.

    scripts/build.sh                             # assembleDebug, lint, unit tests, check-apk.sh
    scripts/build.sh assembleDebug               # only the given Gradle tasks
    scripts/build.sh assembleDebug -PphoneOnly   # ARM-only APK, then PHONE_ONLY=1 scripts/check-apk.sh

The output is `app/build/outputs/apk/debug/app-debug.apk` (package
`org.gnosisvpn.stub.debug`). The debug APK carries wireguard-go for arm64-v8a, armeabi-v7a and
x86_64, so the same file runs on a phone and in redroid. Release builds are ARM only.
`check-apk.sh` requires those three ABIs and rejects 32-bit x86. It also checks native
libraries (including the Rust core), data files, 16 KB alignment, permissions and secrets.

### Rust core on a droplet

    scripts/do-build/run.sh                      # both ABIs, fast profile, about 13 min
    ABIS=arm64-v8a scripts/do-build/run.sh       # phone only
    KEEP=1 scripts/do-build/run.sh               # keep the droplet: rebuild.sh, then down.sh
    scripts/do-build/app.sh                      # the APK on a droplet (~15 min, 1 h deadline)

`run.sh` does the following:
1. It rents a DigitalOcean c-32 droplet (about $1/h).
2. It runs the contract tests. A failing test stops the build; `TESTS=0` skips them.
3. It builds the core and copies the libraries into `app/src/main/jniLibs/`, which is
   gitignored.
4. It deletes the droplet on every exit path.

Several safeguards back up the delete:
- **The deadline:** a deletion deadline is armed as a backstop, 6 h by default (`DEADLINE_S`).
  The owner's standing rule is a 1 h emergency kill, so pass `DEADLINE_S=3600`.
- **Lost create reply:** a create whose reply is lost is found again by name.
- **Interrupted create:** a Ctrl-C during the create recovers the droplet id before teardown.
- **Retries:** DELETE retries on timeouts, 429 and 5xx responses.
- **Kept droplets:** only one droplet may be kept at a time (`~/.local/state/gvpndroid-do/current`).

`droplet.py` reads the token from `$DO_TOKEN_FILE` (default `~/do-api-token`). The
deadline gets the file's path, never the token. `PROFILE=release` uses the client's fat LTO
profile, which takes about 18 min for two ABIs, mostly single-threaded.

## Testing in redroid

This machine has no KVM, so the Android emulator can't run. Instead, redroid runs Android 14
(x86_64) as a Docker container on the host kernel, using its binder and tun. adb reaches it at
the container's own address (`adb -s "$(scripts/emulator.sh serial)"`). Don't also connect to
`localhost:5555`: two transports to one adbd drop each other.

    scripts/emulator.sh start     # start or create the container, wait for boot, adb connect
    scripts/emulator.sh status    # state, memory use, adb
    scripts/emulator.sh stop      # stop it; image and data stay
    scripts/e2e-emulator.sh ~/.config/gvpndroid-secrets/gvpndroid-emu.conf   # WireGuard backend e2e

    scripts/gnosis-emulator.sh setup CONF_DIR SHARE_DIR
    scripts/gnosis-emulator.sh connect
    scripts/gnosis-emulator.sh log
    scripts/gnosis-emulator.sh e2e     # Gnosis backend PASS/FAIL; manual only: each run pays the exit (PIX)

**WireGuard e2e (`e2e-emulator.sh`).**

Setup:
- It installs the debug APK.
- It grants the notification permission and VPN consent without UI (`pm grant`,
  `appops set ... ACTIVATE_VPN allow`).
- It copies the config into the app's `no_backup/tunnel.conf` with `run-as`.
- It taps through the UI with `uiautomator dump` and `input tap`.

Checks, each printed as PASS or FAIL:
- the tunnel is connected and the app's stats show a handshake;
- `dumpsys connectivity` shows a VPN network;
- the default route goes via tun0;
- the public IP, seen from inside Android;
- the HTTP reply arrived through tun0;
- after the process is killed, the system restarts the service and the tunnel;
- the notification's Disconnect action works;
- after Disconnect, the route and the IP return to their pre-connect state.

It stops the container at the end; `KEEP=1` keeps it. `EXPECT_IP` overrides the expected tunnel
IP (default: the config's Endpoint host).

Use a router peer reserved for the container, never the phone's: one key in two places breaks
both. On this LAN the machine already exits through the router's public IP, so the before and
after IP are the same. The tun0 route and byte counters are what prove the tunnel.

Details:
- **Image:** `redroid/redroid:14.0.0-latest`, about 870 MB to download and 2.1 GB on disk.
  Docker's data-root is `/home/user/.build-host/docker`. Android's `/data` is
  `~/android/redroid-data`.
- **Boot and memory:** boot takes about 15 to 25 s. The container uses about 700 to 760 MB and
  is capped at 2 GB. `emulator.sh start` wants 1500 MB available (`FORCE=1` overrides).
- **Binder:** redroid mounts binderfs itself, and the container runs `--privileged`.
- **tun:**
  - `VpnService` opens `/dev/tun`, which redroid lacks (only `/dev/net/tun`), so `establish()`
    fails until the node exists.
  - `emulator.sh start` creates the node and loads the tun module on every start.
- **netd:**
  - It needs the legacy iptables tables and xt modules; without them adb goes offline once the
    tunnel is up.
  - `emulator.sh start` loads them with `sudo modprobe`. They are lost on reboot, so always
    start through the script.
  - `quota2` stays missing, which affects only data-usage alerts.
- **VPN consent:** the consent from `appops` doesn't survive a container restart; the e2e
  scripts set it on every run.
- **Pitfalls:**
  - Reinstalling or force-stopping the app while its VPN is up can break networking until
    `emulator.sh stop`/`start`.
  - Never `adb root` while the VPN is up: adb goes offline.
  - redroid's lmkd floods logcat, so `emulator.sh` stops it.
  - Each `uiautomator dump` costs about 100 file descriptors.
  - On host kernel 6.18, `system_server` leaks Unix-socket fds minutes after boot and then
    restarts about every 2 min. Run e2e tests right after a fresh start.

## Debug log relay

DEBUG BUILDS ONLY; remove before production. This section covers the implementation; the README
describes its behaviour for users.

- **Code:**
  - `app/src/debug/java/.../DebugLogRelay.kt` and `DebugLogRelayCore.kt`;
  - the splash notice in `app/src/debugRelay/res`, added only when the topic and key are
    present;
  - the `DEBUG ONLY` blocks in `app/build.gradle.kts` and `scripts/check-apk.sh`.

  Release builds get the no-op `app/src/release/.../DebugLogRelay.kt`.
- **Format:**
  - Parts are gzipped and sealed to the developer's X25519 key, with the same construction as
    gvpndows' identity backup (tag `gvpndroid-log-v1`). They are posted to a private ntfy.sh
    topic.
  - Anonymous ntfy.sh allows 250 messages a day per IP. A token bucket (32 messages, refilled
    at 210 a day) covers the start window and stays under that limit.
  - A backlog of more than one message goes as one sealed attachment (up to 2 MB, kept 3 h).
  - When a stall or reconnect starts, the app logs the core's connection state and
    `nerd_stats`.
- **Topic and public key:** read at build time from `~/.config/gvpndroid-secrets/` (`ntfy-topic`,
  `log-x25519.pub`). Without them the relay is off and hidden.
- **Reading:** `scripts/relay-read.py [SINCE]`, or `-f` to follow; it needs `log-x25519.key`
  from the same folder.
  - It fetches only `https://ntfy.sh/file/<id>` links, with size and time limits, and strips
    control characters.
  - In follow mode it reconnects and resumes after the last message shown.
- **Removing it:** delete those files and the `DEBUG ONLY` blocks, then delete the calls the
  compiler reports.

## Installing on the maintainer's phone

The maintainer's main phone profile blocks sideloaded apps (Google Advanced Protection), so
builds are tested in a separate Android profile without a Google account. That profile has no
access to agent sessions, so builds reach it through a short-lived public link:
litterbox.catbox.moe, with a 1 h expiry.

1. Note the APK's `sha256sum` before uploading.
2. Upload it:

        curl -F reqtype=fileupload -F time=1h -F fileToUpload=@app/build/outputs/apk/debug/app-debug.apk \
            https://litterbox.catbox.moe/resources/internals/api.php

3. Download it back and compare the checksum.
4. Give the owner the URL together with a QR code image, never an ASCII QR code.

Bump `versionCode` and `versionName` in `app/build.gradle.kts` for every build handed out, so
it installs over the previous one.

Alternatively, use adb over Wi-Fi. This is untested and may be blocked:

    . ~/android/env.sh
    adb pair PHONE_IP:PAIR_PORT       # code from Developer options > Wireless debugging > Pair
    adb connect PHONE_IP:PORT
    adb install -r --user USER_ID app/build/outputs/apk/debug/app-debug.apk

## WireGuard configs

Keep peer configs outside the repo (`~/.config/gvpndroid-secrets/`, mode 600).
`scripts/show-config-qr.sh PEER.conf` shows one as a QR code on screen for the app to scan.
