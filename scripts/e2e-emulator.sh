#!/bin/sh
# End-to-end test of the debug APK's WireGuard VPN in redroid (scripts/emulator.sh).
# Usage: scripts/e2e-emulator.sh PEER.conf [APK]
#   PEER.conf  a WireGuard config for a router peer reserved for this container (never the
#              phone's: one key in two places breaks both). It is copied into the app's private
#              storage over adb and never printed.
# Environment: EXPECT_IP  public IP expected through the tunnel (default: the Endpoint host)
#              KEEP=1     leave the container running at the end (default: stop it)
# Prints PASS/FAIL per step; exits non-zero if any step failed.
set -u
cd "$(dirname "$0")/.."
. "$HOME/android/env.sh"

conf=${1:?usage: $0 PEER.conf [APK]}
apk=${2:-app/build/outputs/apk/debug/app-debug.apk}
PKG=org.gnosisvpn.stub.debug
SERIAL=localhost:${REDROID_PORT:-5555}
A="adb -s $SERIAL"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
fails=0

pass() { echo "PASS: $*"; }
fail() { echo "FAIL: $*"; fails=$((fails + 1)); }
check() { msg=$1; shift; if "$@"; then pass "$msg"; else fail "$msg"; fi; }

# Public IP as seen from inside Android (toybox nc, plain HTTP; the shell's uid is VPN-routed).
public_ip() {
    $A shell 'printf "GET / HTTP/1.0\r\nHost: ifconfig.me\r\nUser-Agent: curl\r\n\r\n" | nc -w 8 ifconfig.me 80' 2>/dev/null \
        | tr -d '\r' | tail -1 | grep -oE '^[0-9a-fA-F.:]+$'
}
tun_rx() { $A shell cat /proc/net/dev | awk '$1 == "tun0:" {print $2}'; }
has_tun() { $A shell ip link show tun0 >/dev/null 2>&1; }
route_dev() { $A shell ip route get 1.1.1.1 | grep -oE 'dev [a-z0-9]+' | head -1 | cut -d' ' -f2; }
vpn_up() { $A shell dumpsys connectivity | grep -q 'ni{VPN CONNECTED'; }
ui() { $A shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; $A shell cat /sdcard/ui.xml > "$tmp/ui.xml"; }
ui_text() { # text of the view with resource id $1
    ui; python3 -I -c 'import re,sys
s=open(sys.argv[1]).read()
m=re.search(r"text=\"([^\"]*)\" resource-id=\"[^\"]*:id/"+sys.argv[2]+"\"", s)
print(m.group(1) if m else "")' "$tmp/ui.xml" "$1"
}
tap_id() { # tap the centre of the view with resource id $1
    ui; xy=$(python3 -I -c 'import re,sys
s=open(sys.argv[1]).read()
m=re.search(r"resource-id=\"[^\"]*:id/"+sys.argv[2]+r"\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", s)
if m: a,b,c,d=map(int,m.groups()); print((a+c)//2, (b+d)//2)' "$tmp/ui.xml" "$1")
    [ -n "$xy" ] && $A shell input tap $xy
}
wait_for() { # wait up to $1 seconds for command "$@" to succeed
    n=$1; shift
    while [ "$n" -gt 0 ]; do "$@" && return 0; sleep 1; n=$((n - 1)); done
    return 1
}
status_is() { ui_text statusText | grep -q "Status: $1"; }
open_app() { $A shell am start -n "$PKG/org.gnosisvpn.stub.MainActivity" >/dev/null; sleep 2; }

[ -r "$conf" ] || { echo "FAIL: cannot read $conf"; exit 1; }
[ -s "$apk" ] || { echo "FAIL: no APK at $apk (run scripts/build.sh)"; exit 1; }
expect=${EXPECT_IP:-$(sed -n 's/^[[:space:]]*Endpoint[[:space:]]*=[[:space:]]*\([0-9.]*\):[0-9]*.*/\1/p' "$conf" | head -1)}

scripts/emulator.sh start || { echo "FAIL: emulator start"; exit 1; }

# 1. Install and grant permissions without UI.
check "install APK" $A install -r "$apk"
$A shell pm grant $PKG android.permission.POST_NOTIFICATIONS
# VPN consent; set on every run because redroid may not persist it across restarts.
$A shell appops set $PKG ACTIVATE_VPN allow
check "VPN consent granted" sh -c "$A shell appops get $PKG ACTIVATE_VPN | grep -q allow"

# 2. Import the config into noBackupFilesDir (the app is debuggable, so run-as works).
$A shell am force-stop $PKG
$A shell run-as $PKG sh -c "'mkdir -p no_backup && cat > no_backup/tunnel.conf && chmod 600 no_backup/tunnel.conf'" < "$conf"
$A logcat -c
open_app
sleep 1
log=$($A logcat -d -s GnosisVpnStub)
check "app log shows the config summary" sh -c 'echo "$1" | grep -q "config: address="' _ "$log"
check "app log has no private key" sh -c "! $A logcat -d | grep -qiE 'PrivateKey|PresharedKey'"

# 3. Baseline before connecting.
before=$(public_ip)
echo "info: public IP before connecting: ${before:-unknown}; expected through the tunnel: ${expect:-unknown}"
check "no tunnel before connect" sh -c "! $A shell ip link show tun0 >/dev/null 2>&1"
[ -n "$before" ] && [ "$before" = "$expect" ] &&
    echo "info: this host already exits via $expect; the IP check alone cannot tell the states apart, tun0 counters and routes decide"

# 4. Connect.
tap_id connectButton
check "status connected" wait_for 20 status_is connected
stats_handshake() { ui_text statsText | grep -q handshake; }
check "stats show a handshake" wait_for 15 stats_handshake
echo "info: stats: $(ui_text statsText)"
check "dumpsys connectivity has a VPN network" wait_for 10 vpn_up
check "default route via tun0" sh -c "[ \"\$($A shell ip route get 1.1.1.1 | grep -oE 'dev [a-z0-9]+' | head -1)\" = 'dev tun0' ]"
rx0=$(tun_rx)
during=$(public_ip)
rx1=$(tun_rx)
echo "info: public IP while connected: ${during:-none}; tun0 rx bytes ${rx0:-?} -> ${rx1:-?}"
check "public IP through the tunnel is $expect" [ -n "$during" -a "$during" = "$expect" ]
check "HTTP reply came in through tun0" [ "${rx1:-0}" -gt "${rx0:-0}" ]

# 5. Process death while connected: the system should restart TunnelService and reconnect.
pid=$($A shell pidof $PKG | tr -d '\r')
$A shell su 0 kill -9 "$pid"
check "tunnel back after process death" wait_for 20 sh -c "p=\$($A shell pidof $PKG | tr -d '\r'); [ -n \"\$p\" ] && [ \"\$p\" != '$pid' ] && $A shell ip link show tun0 >/dev/null 2>&1"

# 6. Disconnect from the notification action.
open_app
$A shell cmd statusbar expand-notifications; sleep 2
ui
xy=$(python3 -I -c 'import re,sys
s=open(sys.argv[1]).read()
m=re.search(r"content-desc=\"Disconnect\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", s)
if m: a,b,c,d=map(int,m.groups()); print((a+c)//2, (b+d)//2)' "$tmp/ui.xml")
if [ -n "$xy" ]; then $A shell input tap $xy; else echo "info: notification Disconnect button not found"; fi
$A shell cmd statusbar collapse
check "notification Disconnect brings the tunnel down" wait_for 10 sh -c "! $A shell ip link show tun0 >/dev/null 2>&1"

# 7. Connect again from the app, then Disconnect from the app.
open_app
tap_id connectButton
check "reconnect" wait_for 20 status_is connected
tap_id connectButton
check "status disconnected" wait_for 10 status_is disconnected
check "tun0 gone" wait_for 10 sh -c "! $A shell ip link show tun0 >/dev/null 2>&1"
check "no VPN network in dumpsys" sh -c "! $A shell dumpsys connectivity | grep -q 'ni{VPN CONNECTED'"
check "default route via eth0" sh -c "[ \"\$($A shell ip route get 1.1.1.1 | grep -oE 'dev [a-z0-9]+' | head -1)\" = 'dev eth0' ]"
after=$(public_ip)
echo "info: public IP after disconnecting: ${after:-none}"
check "public IP back to $before" [ -n "$after" -a "$after" = "$before" ]

[ "${KEEP:-0}" = 1 ] || scripts/emulator.sh stop
if [ "$fails" = 0 ]; then echo "E2E PASS"; else echo "E2E FAIL ($fails failed)"; fi
[ "$fails" = 0 ]
