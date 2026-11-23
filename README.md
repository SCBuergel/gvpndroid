# gvpndroid: Gnosis VPN on Android (prototype)

> **This is a prototype, not a product.** Debug builds send the app's complete log to the
> developer, and this is **on by default**. The log contains the app's lines and the Gnosis VPN
> core's lines, including connection state, exits used and payment activity. Each part is
> encrypted to the developer's key before it is sent; see
> [What the debug log relay sends](#what-the-debug-log-relay-sends) before you install one.
> The node identity and the funds you put on it are real. Use small amounts on a test device
> or a separate Android profile, and export the identity before you fund it.

gvpndroid runs the Gnosis VPN client on an Android phone. It uses the same Rust client core as
the desktop apps (gnosis_vpn-client), so it is a HOPR node in its own right. It opens a 1-hop
HOPR session to an exit, carries WireGuard through that session, and pays the exit with PIX
payments from its own Safe. No desktop app or remote server is involved.

The goal is a working basis for a production Android UI. Every number on screen comes from the
real core, and there is no demo mode. It has been tested on the prod network (piz-palu), on a
Pixel and in redroid (Android in Docker).

## How it works

Android only lets an app route traffic through a VPN by giving it a tun interface from
`VpnService`. On the desktop, a separate root process creates the tun and installs a firewall
for the core. On Android, the app takes that job:

1. **The app starts the core in its own process.** The core is gnosis_vpn-client (pinned commit,
   IPC v5; since 0.0.3 a fork commit with draft fixes for payment stalls, see `AGENTS.md`) with
   a small Android patch, compiled to `libgnosisvpn.so`. A Rust
   bridge crate exposes it to Kotlin over JNI. The app talks to the core with the same JSON
   commands and status messages the desktop CLI uses.
2. **The core runs the HOPR node.** It discovers exits, opens a channel and a session to the
   chosen exit, and buys data in chunks with PIX payments from the node's Safe.
3. **When the session is ready, the core asks for a tun.** The app gets one from Android's
   `VpnService` and hands its file descriptor to the core. The core then runs WireGuard over
   the HOPR session through that tun.
4. **Android enforces the kill switch.** Instead of the desktop firewall, the app relies on
   Android's Always-on VPN with "Block connections without VPN" ("Lockdown" in the app). An
   app can't turn that on by itself, so the app links to the system setting and shows whether
   it is on.

The app itself is excluded from its own VPN, so the core's HOPR traffic goes out directly.
One consequence: the core can't run its in-tunnel health ping, so an idle tunnel may reconnect
now and then.

A second backend, plain WireGuard to a fixed server, sits behind the same interface. It exists
to tell Android VPN problems apart from HOPR problems; the UI does not depend on which backend
runs.

## Trying it out

You need an Android 8.0+ phone (64-bit ARM for the Gnosis backend; 32-bit ARM phones get only
the WireGuard backend), and a small amount of wxHOPR and xDAI on Gnosis Chain, or a funding
code.

1. **Get an APK.** Download `gvpndroid-0.0.N-debug-logging.apk` from the repository's
   [Releases](../../releases) page, or build one yourself (see [Building](#building)). The
   `.sha256` file next to it has the checksum. These are debug builds with the debug log relay
   on (see [below](#what-the-debug-log-relay-sends)). They install as
   `org.gnosisvpn.stub.debug`, next to any other version, and each one installs over the
   previous one.
2. **Install and open it.** On first start the app sets up the Gnosis backend by itself, with
   the data files bundled in the APK.
3. **Back up the identity.** Settings → Developer screen → Export identity saves the node key
   file. The Safe and its funds are controlled only by this key.
4. **Fund the node.** A new node waits on a funding screen that shows its address as a QR code
   and links to the Gnosis VPN funding site, where you can pay from a wallet or redeem a funding
   code. The app continues by itself once the funds arrive.
5. **Connect.** Pick an exit or let the app choose one. The first connection opens a payment
   channel on chain, which can take a minute or two. Every connect then waits about 40 s on
   "Opening the route and paying the exit": the core pays the exit first and attaches the
   tunnel only once the exit can see the payment, so traffic does not stop at the exit's free
   allowance (about 14 MB).
6. **Optional: turn on Lockdown** from the app, which opens Android's VPN settings, so no
   traffic leaves the phone outside the VPN.

The main screen shows each exit's name ("GVPN-GER-1", "Bonnie Prince Charlie") under its
country, so you can tell exits in the same country apart.

**Developer screen** (Settings → Developer screen), for testing:

- **Log level** cycles normal, verbose and debug for the app and the core.
- **Send logs now** sends everything logged so far to the developer at once, instead of waiting
  for the relay's schedule. The result shows in the log below it.
- **Lab exits** (off by default) adds the vpn-lab's two test exits, "Lab exit Frankfurt" and
  "Lab exit New York", to the exit list. The network does not announce them, so they are
  pinned by address; everything else, including relay choice, stays as normal. Disconnect
  before switching it: the core restarts to read the change. These are temporary machines
  (Frankfurt until 2026-11-23 22:30 UTC, New York until 2026-11-24 22:35 UTC).

Disconnect before installing an update.

## What the debug log relay sends

Debug builds include a relay that sends the developer the same log you can see under
Settings → Logs. That log covers the app and the core, at the selected log level.

- **When:** collected every 10 s and sent every 30 s for the first 15 minutes after the app
  starts, then every few minutes. Lines that look like a problem (errors, stalls) go out at
  once.
- **How:** each part is compressed, then sealed to the developer's X25519 public key, and posted
  to a private ntfy.sh topic. Only the holder of the private key can read the contents.
- **What others can see:** the topic name is built into every debug APK, so anyone with the APK
  can see when messages are sent and how large they are, and could post fake ones. They cannot
  read them.
- **Where from:** the app is outside its own VPN, so the relay posts from the phone's own IP
  address.
- **Turning it off:** a bar at the top of every screen says the relay is on and has an off
  switch; the same switch is in Settings → Logs.
- **Sending at once:** Settings → Developer screen → Send logs now sends what was collected
  so far without waiting for the schedule.

The APKs on the Releases page are debug builds and always have the relay on: the release
workflow refuses to build without the topic and key. Android release builds (`assembleRelease`)
don't contain the relay; they get an empty stand-in that also hides the bar and the setting. A
local build made without the developer's topic and key files also has the relay off and hidden.

## Building

The app builds with Gradle (JDK 17, Android SDK 35). The Rust core is built separately and is
not stored in the repository:

- **Rust core:** `scripts/do-build/run.sh` compiles `libgnosisvpn.so` on a temporary
  DigitalOcean droplet and copies it into `app/src/main/jniLibs/`. It needs a DigitalOcean API
  token, takes about 10 to 15 minutes and costs well under a dollar. The script deletes the
  droplet on every exit path, and a deletion deadline backs that up. It also runs the Rust
  contract tests first.
- **APK:** `scripts/build.sh` builds the debug APK, runs lint and the unit tests, then checks
  the APK (native libraries, data files, permissions, no secrets). The result is
  `app/build/outputs/apk/debug/app-debug.apk`.

The core's data files come from the official Gnosis VPN package: run
`scripts/gnosis-assets.sh UNPACKED_DEB_ROOT` once before the first APK build.

The GitHub release workflow (`.github/workflows/release.yml`) does all of this on a GitHub
runner (the core for 64-bit ARM, the data files, the APK and its checks) and publishes the APK
as a release when a `v0.0.N` tag is pushed.

## Status and known gaps

- Prototype only: versions stay at 0.0.x, the package name is still `org.gnosisvpn.stub`, and
  every published APK is a debug build (with the log relay described above, when it is enabled
  for that release; the release notes say whether it is).
- No core for 32-bit ARM phones.
- No in-tunnel health ping (see [How it works](#how-it-works)), so idle tunnels may reconnect.
- In November 2026 a multi-agent review of the whole code base went through 25 review passes
  and fixed 47 findings: 1 High, 13 Medium, 31 Low, 2 Info. The High was the core leaving its
  HOPR node running after it stopped by itself. Behaviour on real devices after those fixes
  still needs testing.

## Repository layout

- `app/`: the Android app (Kotlin, Jetpack Compose). The UI talks only to the `Tunnel`
  interface; `GnosisTunnel` and `WireGuardTunnel` are its two backends.
- `rust/gnosis_vpn-android/`: the JNI bridge crate.
- `rust/patches/0001-android-target.patch`: the Android patch for gnosis_vpn-client.
- `scripts/`: building, APK checks, the redroid test setup and the log reader.
- [`AGENTS.md`](AGENTS.md): the detailed maintenance and development guide, written for coding
  agents and maintainers.

## License

GPLv3, see [LICENSE](LICENSE). The bundled fonts (Inter, JetBrains Mono, Doto) are under the SIL
Open Font License 1.1.
