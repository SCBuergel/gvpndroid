#!/bin/sh
# Drive the Gnosis VPN backend in redroid (scripts/emulator.sh) while debugging.
# Usage:
#   scripts/gnosis-emulator.sh setup CONF_DIR SHARE_DIR [APK]
#       install the APK, grant VPN consent, copy CONF_DIR/{gnosis.toml,gnosis.properties} into
#       the app's no_backup dir and SHARE_DIR/{geoip.mmdb,curvy-zk/} into no_backup/gnosis-share
#   scripts/gnosis-emulator.sh props CONF_DIR      re-copy only gnosis.properties / gnosis.toml
#   scripts/gnosis-emulator.sh restart             force-stop and relaunch the app
#   scripts/gnosis-emulator.sh connect             tap Connect
#   scripts/gnosis-emulator.sh log [N]             last N lines of app + core logcat (default 80)
# Config files hold no keys, but stay outside the repo: they are per-device settings.
set -eu
cd "$(dirname "$0")/.."
. "$HOME/android/env.sh"
A="adb -s ${ANDROID_SERIAL:-localhost:5555}"
PKG=org.gnosisvpn.stub.debug
NB=/data/user/0/$PKG/no_backup

push_private() { # $1 local file, $2 path relative to no_backup
    $A push "$1" /data/local/tmp/gvpn-xfer >/dev/null
    $A shell chmod 644 /data/local/tmp/gvpn-xfer
    $A shell run-as $PKG mkdir -p "$NB/$(dirname "$2")"
    $A shell run-as $PKG cp /data/local/tmp/gvpn-xfer "$NB/$2"
    $A shell rm -f /data/local/tmp/gvpn-xfer
}

props() {
    push_private "$1/gnosis.toml" gnosis.toml
    push_private "$1/gnosis.properties" gnosis.properties
}

case "${1:-}" in
setup)
    conf=$2; share=$3; apk=${4:-app/build/outputs/apk/debug/app-debug.apk}
    $A install -r "$apk" | tail -1
    $A shell appops set $PKG ACTIVATE_VPN allow
    $A shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null || true
    $A shell am start -W -n $PKG/org.gnosisvpn.stub.MainActivity >/dev/null   # creates the data dirs
    props "$conf"
    push_private "$share/geoip.mmdb" gnosis-share/geoip.mmdb
    for f in "$share"/curvy-zk/*; do push_private "$f" "gnosis-share/curvy-zk/$(basename "$f")"; done
    $A shell run-as $PKG ls -la $NB $NB/gnosis-share $NB/gnosis-share/curvy-zk
    ;;
props) props "$2" ;;
restart)
    $A shell am force-stop $PKG
    $A logcat -c
    $A shell am start -W -n $PKG/org.gnosisvpn.stub.MainActivity >/dev/null
    ;;
connect)
    $A shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    xy=$($A shell cat /sdcard/ui.xml | python3 -I -c 'import re,sys
m=re.search(r"resource-id=\"[^\"]*:id/connectButton\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", sys.stdin.read())
a,b,c,d=map(int,m.groups()); print((a+c)//2, (b+d)//2)')
    $A shell input tap $xy
    ;;
log) $A logcat -d -s GnosisVpnStub:I gnosisvpn:I AndroidRuntime:E DEBUG:F | tail -n "${2:-80}" ;;
*) sed -n '2,13p' "$0"; exit 2 ;;
esac
