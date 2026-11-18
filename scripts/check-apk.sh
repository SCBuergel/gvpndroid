#!/bin/sh
# Smoke-check the built debug APK: native libs (ARM + x86_64), the Gnosis core and data files,
# 16 KB alignment, permissions, no secrets.
# Usage: scripts/check-apk.sh [APK]
set -eu
cd "$(dirname "$0")/.."
. "$HOME/android/env.sh"
apk=${1:-app/build/outputs/apk/debug/app-debug.apk}
bt="$ANDROID_HOME/build-tools/35.0.0"
fail=0
err() { echo "FAIL: $*" >&2; fail=1; }

[ -s "$apk" ] || { echo "FAIL: no APK at $apk" >&2; exit 1; }
listing=$(unzip -l "$apk")
abis="arm64-v8a armeabi-v7a x86_64"; [ "${PHONE_ONLY:-0}" = 1 ] && abis="arm64-v8a armeabi-v7a"
for abi in $abis; do
    echo "$listing" | grep -q "lib/$abi/libwg-go.so" || err "lib/$abi/libwg-go.so missing"
done
# x86_64 is for redroid (debug builds); 32-bit x86 has no use here.
echo "$listing" | grep -qE 'lib/x86/' && err "32-bit x86 libraries present (abiFilters not applied)"
echo "$listing" | grep -qiE '\.(conf|key)$' && err "a .conf or .key file is packaged"
"$bt/zipalign" -c -P 16 4 "$apk" || err "not 16 KB zip-aligned"

# The Gnosis backend: the Rust core per ABI and the data files (both gitignored local inputs, so
# a build without them must fail here, not on the phone). GNOSIS=0 for a WireGuard-only APK.
if [ "${GNOSIS:-1}" = 1 ]; then
    gabis="arm64-v8a x86_64"; [ "${PHONE_ONLY:-0}" = 1 ] && gabis="arm64-v8a"
    for abi in $gabis; do
        echo "$listing" | grep -q "lib/$abi/libgnosisvpn.so" || err "lib/$abi/libgnosisvpn.so missing (scripts/do-build/run.sh)"
    done
    for f in config.toml gnosis.properties geoip.mmdb curvy-zk/verifyPixAggregationBound_2_9_30-final.zkey curvy-zk/pix-aggregation-bound-2-9-30.signet.zst; do
        echo "$listing" | grep -q "assets/gnosis/$f" || err "assets/gnosis/$f missing (scripts/gnosis-assets.sh)"
    done
fi

# Classes the QR scanner needs at runtime but does not declare (crashed 0.2.1 when missing).
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
unzip -q -o "$apk" 'classes*.dex' -d "$tmp"
# Match class definitions (dexdump), not references: zxing's own code references them anyway.
defined=$(for d in "$tmp"/classes*.dex; do "$bt/dexdump" "$d" 2>/dev/null; done | grep "Class descriptor")
for c in androidx/core/content/ContextCompat androidx/core/app/ActivityCompat; do
    echo "$defined" | grep -qF "'L$c;'" || err "class $c not defined in the APK"
done

# DEBUG ONLY (remove with the log relay): a debug build made where the relay's topic exists must
# carry the relay and its splash notice; one without the topic must not show the notice.
# grep -f reads the topic from its file, so it never appears on a command line.
topicf=${GVPNDROID_SECRETS:-$HOME/.config/gvpndroid-secrets}/ntfy-topic
if [ -f "$topicf" ] && [ "${RELAY:-1}" = 1 ]; then
    grep -qaF -f "$topicf" "$tmp"/classes*.dex || err "debug log relay: topic not built in"
    echo "$defined" | grep -qF "'Lorg/gnosisvpn/stub/DebugLogRelayCore;'" || err "debug log relay: DebugLogRelayCore missing"
    echo "$listing" | grep -q 'splash_debug_notice.png' || err "debug log relay: splash notice missing"
elif echo "$listing" | grep -q 'splash_debug_notice.png'; then
    err "splash notice about the log relay, but the relay is not built in"
fi

perms=$("$bt/aapt2" dump permissions "$apk")
for p in INTERNET ACCESS_NETWORK_STATE FOREGROUND_SERVICE FOREGROUND_SERVICE_SPECIAL_USE POST_NOTIFICATIONS CAMERA; do
    echo "$perms" | grep -q "android.permission.$p'" || err "permission $p missing"
done

[ "$fail" = 0 ] && echo "APK check ok: $apk ($(du -k "$apk" | cut -f1) KB, sha256 $(sha256sum "$apk" | cut -c1-16)...)"
exit "$fail"
