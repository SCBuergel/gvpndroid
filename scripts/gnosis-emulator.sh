#!/bin/sh
# Drive the Gnosis VPN backend in redroid (scripts/emulator.sh) while debugging.
# Usage:
#   scripts/gnosis-emulator.sh setup CONF_DIR SHARE_DIR [APK]
#       install the APK, grant VPN consent, copy CONF_DIR/{gnosis.toml,gnosis.properties} into
#       the app's no_backup dir and SHARE_DIR/{geoip.mmdb,curvy-zk/} into no_backup/gnosis-share
#   scripts/gnosis-emulator.sh props CONF_DIR      re-copy only gnosis.properties / gnosis.toml
#   scripts/gnosis-emulator.sh restart             force-stop and relaunch the app
#   scripts/gnosis-emulator.sh connect             tap Connect
#   scripts/gnosis-emulator.sh log [N]             last N lines of app logcat (default 80)
#   scripts/gnosis-emulator.sh e2e                 end-to-end test, PASS/FAIL per step: core running,
#       connect, public IP differs from this machine's, 1 MB through tun0, disconnect. Needs a
#       funded node (setup done before). Manual only: every run pays the exit (PIX) from that node.
# Config files hold no keys, but stay outside the repo: they are per-device settings.
set -eu
cd "$(dirname "$0")/.."
. "$HOME/android/env.sh"
A="adb -s ${ANDROID_SERIAL:-$(scripts/emulator.sh serial)}"
PKG=org.gnosisvpn.stub.debug
NB=/data/user/0/$PKG/no_backup
ACT=org.gnosisvpn.stub.ui.UiActivity   # the launcher activity (MainActivity is the unexported developer screen)

push_private() { # $1 local file, $2 path relative to no_backup
    $A push "$1" /data/local/tmp/gvpn-xfer >/dev/null
    $A shell chmod 644 /data/local/tmp/gvpn-xfer
    $A shell run-as $PKG mkdir -p "$NB/$(dirname "$2")"
    $A shell run-as $PKG cp /data/local/tmp/gvpn-xfer "$NB/$2"
    $A shell rm -f /data/local/tmp/gvpn-xfer
}

ipaddr() { tr -d '\r' | tail -1 | grep -E '^[0-9a-fA-F.:]+$' || true; }   # last line if an IP literal, else empty

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
    $A shell am start -W -n $PKG/$ACT >/dev/null   # creates the data dirs
    props "$conf"
    push_private "$share/geoip.mmdb" gnosis-share/geoip.mmdb
    for f in "$share"/curvy-zk/*; do push_private "$f" "gnosis-share/curvy-zk/$(basename "$f")"; done
    $A shell run-as $PKG ls -la $NB $NB/gnosis-share $NB/gnosis-share/curvy-zk
    ;;
props) props "$2" ;;
restart)
    $A shell am force-stop $PKG
    $A logcat -c
    $A shell am start -W -n $PKG/$ACT >/dev/null
    ;;
connect)
    # Bring the app to the front and wait until it is displayed: a tap that lands on the
    # launcher instead does nothing for the app (and types into the home screen's search box).
    # A sleeping display ignores taps: wake it and keep it on while plugged in (redroid is).
    $A shell input keyevent KEYCODE_WAKEUP; $A shell svc power stayon true
    $A shell am start -n $PKG/$ACT >/dev/null 2>&1
    n=90; until $A shell dumpsys activity activities 2>/dev/null | grep -q "topResumedActivity.*$PKG/$ACT"; do
        n=$((n - 3)); [ "$n" -gt 0 ] || { echo "app not in front" >&2; exit 1; }; sleep 3
    done
    sleep 2
    # uiautomator often fails in redroid ("null root node"); then tap the Connect/Disconnect
    # button's fixed position (the wide button at the bottom of the 720x1280 home screen).
    xy=$($A shell 'uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1 && cat /data/local/tmp/ui.xml' 2>/dev/null | python3 -I -c 'import re,sys
m=re.search(r"text=\"(?:Connect|Disconnect)(?: · [^\"]*)?\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", sys.stdin.read())
print((int(m[1])+int(m[3]))//2, (int(m[2])+int(m[4]))//2) if m else print("360 1095")')
    $A shell rm -f /data/local/tmp/ui.xml 2>/dev/null || true
    $A shell input tap $xy
    ;;
log) $A logcat -d -s GnosisVpnStub:I gnosisvpn:I AndroidRuntime:E DEBUG:F | tail -n "${2:-80}" ;;
e2e)
    fails=0
    # check/wait_for eval their argument: quote it with '...' so values are expanded there as data.
    check() { if eval "$2"; then echo "PASS: $1"; else echo "FAIL: $1"; fails=$((fails + 1)); fi; }
    wait_for() { n=$1; shift; while [ "$n" -gt 0 ]; do eval "$1" && return 0; sleep 3; n=$((n - 3)); done; return 1; }
    applog() { timeout 20 $A logcat -d -s GnosisVpnStub:I 2>/dev/null; }
    on_tun() { timeout 10 $A shell ip route get 1.1.1.1 2>/dev/null | grep -q tun0; }
    pubip() { timeout 60 $A shell 'printf "GET / HTTP/1.0\r\nHost: ifconfig.me\r\nUser-Agent: curl\r\n\r\n" | nc -w 15 -q 30 -W 20 ifconfig.me 80' 2>/dev/null | ipaddr; }
    host_ip=$(curl -sS -m 15 https://ifconfig.me | ipaddr)
    "$0" restart
    check "app runs the gnosis backend" "wait_for 60 'applog | grep -q \"backend: gnosis\"'"
    check "core reaches run mode running" "wait_for 240 'applog | grep -q \"run mode running\"'"
    check "locations discovered" "wait_for 120 'applog | grep -qE \"[0-9]+ locations:\"'"
    check "not on the VPN before connecting" "! on_tun"
    "$0" connect
    check "tunnel up (default route via tun0)" "wait_for 240 on_tun"
    ip=""; for _ in 1 2 3 4 5 6; do ip=$(pubip); [ -n "$ip" ] && break; sleep 10; done   # PIX payment hold
    echo "info: public IP through the tunnel: ${ip:-none}; this machine: ${host_ip:-unknown (lookup failed)}"
    # Both must be known: an empty host_ip would let any reply, even a leak, pass.
    check "public IP through the tunnel differs from this machine's" '[ -n "$ip" ] && [ -n "$host_ip" ] && [ "$ip" != "$host_ip" ]'
    rx() { timeout 10 $A shell cat /proc/net/dev | awk '/tun0/{print $2}' | tr -cd '0-9'; }
    b=$(rx); n=$(timeout 150 $A shell "printf 'GET /debian/dists/stable/main/Contents-all.gz HTTP/1.0\r\nHost: deb.debian.org\r\n\r\n' | nc -w 30 -q 120 -W 30 deb.debian.org 80 | head -c 1000000 | wc -c" 2>/dev/null | tr -cd '0-9')
    a=$(rx); got=$(( ${a:-0} - ${b:-0} )); echo "info: downloaded ${n:-0} bytes, tun0 rx $got bytes"
    check "1 MB downloaded through tun0" '[ "${n:-0}" -ge 1000000 ] && [ "$got" -ge 1000000 ]'
    "$0" connect   # the button reads Disconnect now
    check "tunnel down after disconnect" "wait_for 60 '! on_tun'"
    [ "$fails" = 0 ] && echo "E2E PASS" || { echo "E2E FAIL ($fails)"; exit 1; }
    ;;
*) sed -n '2,17p' "$0"; exit 2 ;;
esac
