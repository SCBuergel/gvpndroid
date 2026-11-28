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
#       connect (timed) with the payment hold before traffic, public IP differs from this machine's,
#       a timed 12 MB download through tun0, disconnect, a second connect with 1 MB, disconnect.
#       Needs a funded node (setup done before). Manual only: every run pays the exit (PIX) twice.
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
    corelog() { timeout 20 $A logcat -d -s gnosisvpn:I 2>/dev/null; }
    on_tun() { timeout 10 $A shell ip route get 1.1.1.1 2>/dev/null | grep -q tun0; }
    pubip() { timeout 60 $A shell 'printf "GET / HTTP/1.0\r\nHost: ifconfig.me\r\nUser-Agent: curl\r\n\r\n" | nc -w 15 -q 30 -W 20 ifconfig.me 80' 2>/dev/null | ipaddr; }
    rx() { timeout 10 $A shell cat /proc/net/dev | awk '/tun0/{print $2}' | tr -cd '0-9'; }
    # GET $1 from host $2 through the tunnel, at most $3 bytes; prints the bytes received
    fetch() { timeout 400 $A shell "printf 'GET $1 HTTP/1.0\r\nHost: $2\r\n\r\n' | nc -w 30 -q 300 -W 30 $2 80 | head -c $3 | wc -c" 2>/dev/null | tr -cd '0-9'; }
    established() { corelog | grep -c "connection established successfully" || true; }
    # Connects, waits until the core lets user traffic through, prints the seconds it took.
    connect_timed() {
        before=$(established); t0=$(date +%s)
        "$0" connect
        wait_for 300 '[ "$(established)" -gt "$before" ]' || { echo "-1"; return; }
        echo $(( $(date +%s) - t0 ))
    }
    $A logcat -G 16M >/dev/null 2>&1 || true   # the checks read the whole run's log back
    $A logcat -c
    "$0" restart
    check "app runs the gnosis backend" "wait_for 60 'applog | grep -q \"backend: gnosis\"'"
    check "core reaches run mode running" "wait_for 240 'applog | grep -q \"run mode running\"'"
    check "locations discovered" "wait_for 120 'applog | grep -qE \"[0-9]+ locations:\"'"
    check "not on the VPN before connecting" "! on_tun"
    # The device's own address before the tunnel (not this machine's: the device may run elsewhere,
    # and an exit may share this machine's line).
    host_ip=""; for _ in 1 2 3; do host_ip=$(pubip); [ -n "$host_ip" ] && break; sleep 5; done

    # 1. First connect: the payment hold, then traffic.
    secs=$(connect_timed); echo "info: first connect took ${secs} s (tap to the core letting traffic through)"
    check "first connect within 300 s" '[ "$secs" -ge 0 ]'
    check "tunnel up (default route via tun0)" "wait_for 30 on_tun"
    # The hold: only the gateway got through until the exit answered the in-tunnel ping, then all.
    order=$(corelog | grep -oE "tunnel access access=(GatewayOnly|Open)|connection established successfully" | sed 's/(.*//' | tr '\n' '|')
    echo "info: tunnel stages: $order"
    check "user traffic held (gateway only) until the exit served, then let through" 'echo "$order" | grep -qE "access=GatewayOnly[^|]*\|tunnel access access=Open\|connection established"'
    # An exit without strict prepay serves the first ping on its free allowance and then holds traffic
    # until it sees the payment it asks for right after; so retry for a while.
    ip=""; for _ in 1 2 3 4 5 6 7 8; do ip=$(pubip); [ -n "$ip" ] && break; sleep 10; done
    echo "info: public IP through the tunnel: ${ip:-none}; the device's own: ${host_ip:-unknown (lookup failed)}"
    # Both must be known: an empty host_ip would let any reply, even a leak, pass.
    check "public IP through the tunnel differs from the device's own" '[ -n "$ip" ] && [ -n "$host_ip" ] && [ "$ip" != "$host_ip" ]'

    # 2. Sustained download: 12 MB (Debian's Contents-amd64.gz, about 12.7 MB), timed.
    b=$(rx); t0=$(date +%s); n=$(fetch /debian/dists/stable/main/Contents-amd64.gz deb.debian.org 12000000); t1=$(date +%s); a=$(rx)
    if [ "${n:-0}" -lt 12000000 ]; then
        # A stall is reported, then tried once more: the check is whether the session carries data.
        echo "info: the timed download stalled after ${n:-0} bytes ($(( t1 - t0 )) s, tun0 rx $(( ${a:-0} - ${b:-0} ))); retrying once in 20 s"
        sleep 20
        b=$(rx); t0=$(date +%s); n=$(fetch /debian/dists/stable/main/Contents-amd64.gz deb.debian.org 12000000); t1=$(date +%s); a=$(rx)
    fi
    got=$(( ${a:-0} - ${b:-0} )); dur=$(( t1 - t0 )); [ "$dur" -gt 0 ] || dur=1
    echo "info: downloaded ${n:-0} bytes in ${dur} s ($(( ${n:-0} * 8 / dur / 1000 )) kbit/s), tun0 rx $got bytes"
    check "12 MB downloaded through tun0" '[ "${n:-0}" -ge 12000000 ] && [ "$got" -ge 12000000 ]'
    check "download at 1 Mbit/s or more" '[ $(( ${n:-0} * 8 / dur )) -ge 1000000 ]'
    fails_ping=$(corelog | grep -c "in-tunnel ping failed" || true)
    echo "info: in-tunnel ping failures so far: $fails_ping; replies: $(timeout 20 $A logcat -d 2>/dev/null | grep -c "in-tunnel ping" || true) log lines"
    check "no reconnect during the first session" '! applog | grep -q "core is reconnecting"'

    # 3. Disconnect, then a second connect and download.
    "$0" connect   # the button reads Disconnect now
    check "tunnel down after disconnect" "wait_for 60 '! on_tun'"
    secs2=$(connect_timed); echo "info: second connect took ${secs2} s"
    check "second connect within 300 s" '[ "$secs2" -ge 0 ]'
    b=$(rx); n=$(fetch /debian/dists/stable/main/Contents-all.gz deb.debian.org 1000000); a=$(rx); got=$(( ${a:-0} - ${b:-0} ))
    echo "info: second session downloaded ${n:-0} bytes, tun0 rx $got bytes"
    check "1 MB downloaded through tun0 after reconnecting" '[ "${n:-0}" -ge 1000000 ] && [ "$got" -ge 1000000 ]'
    "$0" connect
    check "tunnel down after the second disconnect" "wait_for 60 '! on_tun'"
    corelog | grep -E "WARN|ERROR" | sed 's/^/core: /' | tail -40
    # The whole core log of the run, for tracing a failure afterwards (E2E_CORE_LOG, default ./e2e-core.log).
    corelog > "${E2E_CORE_LOG:-e2e-core.log}" 2>/dev/null && echo "info: core log saved to ${E2E_CORE_LOG:-e2e-core.log}"
    [ "$fails" = 0 ] && echo "E2E PASS" || { echo "E2E FAIL ($fails)"; exit 1; }
    ;;
*) sed -n '2,17p' "$0"; exit 2 ;;
esac
