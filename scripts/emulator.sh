#!/bin/sh
# Local Android test device: redroid (Android in a Docker container), driven over adb.
# No KVM is needed; it uses the host kernel's binder and tun.
# Usage: scripts/emulator.sh start|stop|status|adb
#   start   start (or create) the container, wait for boot, prepare /dev/tun, adb connect
#   stop    stop the container (keeps image and data)
#   status  container state, boot state, memory use, adb devices
#   adb     (re)connect adb only
# Environment: REDROID_IMAGE, REDROID_NAME, REDROID_DATA, REDROID_PORT, REDROID_MEM,
#              MIN_AVAIL_MB (memory needed to start, override with FORCE=1).
set -eu
. "$HOME/android/env.sh"

IMAGE=${REDROID_IMAGE:-redroid/redroid:14.0.0-latest}
NAME=${REDROID_NAME:-gvpndroid-redroid}
# Android's /data. Under /home so it survives Qubes reboots (the rest of the qube does not).
DATA=${REDROID_DATA:-$HOME/android/redroid-data}
PORT=${REDROID_PORT:-5555}
MEM=${REDROID_MEM:-2g}
MIN_AVAIL_MB=${MIN_AVAIL_MB:-1500}
SERIAL="localhost:$PORT"

running() { [ "$(docker inspect -f '{{.State.Running}}' "$NAME" 2>/dev/null)" = true ]; }
booted() { [ "$(docker exec "$NAME" getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; }

load_modules() {
    # Android's netd uses legacy iptables (filter, mangle, raw, nat) and xt matches. Without
    # them it cannot mark incoming packets, so with a VPN up the replies of adbd go into the
    # tunnel and adb drops. Modules are not persistent on Qubes: load them on every start.
    # The legacy tables are empty with ACCEPT policies and do not affect the host's nftables.
    mods="iptable_filter iptable_mangle iptable_raw iptable_nat ip6table_filter ip6table_mangle
          ip6table_raw ip6table_nat xt_mark xt_connmark xt_TCPMSS xt_tcpmss xt_owner xt_comment
          xt_state xt_bpf xt_IDLETIMER xt_NFLOG xt_quota xt_socket xt_pkttype xt_policy
          xt_length xt_limit xt_u32 xt_CT xt_REDIRECT xt_multiport xt_iprange ipt_REJECT ip6t_REJECT"
    # shellcheck disable=SC2086
    sudo modprobe -a $mods || echo "warning: some netfilter modules did not load" >&2
}

prepare_tun() {
    # Android's VpnService opens /dev/tun (ueventd creates it on a phone); redroid has only
    # /dev/net/tun. /dev is per container start, so recreate it each time.
    docker exec "$NAME" sh -c '[ -c /dev/tun ] || mknod /dev/tun c 10 200; chown 1000:1016 /dev/tun; chmod 660 /dev/tun'
}

adb_connect() {
    for _ in $(seq 1 15); do
        adb connect "$SERIAL" >/dev/null 2>&1 || true
        [ "$(adb -s "$SERIAL" get-state 2>/dev/null)" = device ] && { echo "adb: $SERIAL connected"; return 0; }
        adb disconnect "$SERIAL" >/dev/null 2>&1 || true
        sleep 1
    done
    echo "adb: could not connect to $SERIAL" >&2
    return 1
}

start() {
    if running && booted; then
        echo "$NAME already running"
    else
        avail=$(awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo)
        echo "memory available: ${avail} MB (need ${MIN_AVAIL_MB} MB; container capped at $MEM)"
        if [ "$avail" -lt "$MIN_AVAIL_MB" ] && [ "${FORCE:-0}" != 1 ]; then
            echo "not enough free memory to start redroid; refusing (FORCE=1 to override)" >&2
            exit 2
        fi
        [ -e /dev/net/tun ] || { echo "/dev/net/tun missing" >&2; exit 1; }
        grep -qw binder /proc/filesystems || { echo "kernel has no binderfs" >&2; exit 1; }
        load_modules
        docker image inspect "$IMAGE" >/dev/null 2>&1 || {
            echo "pulling $IMAGE (about 870 MB download, 2.1 GB on disk)"
            docker pull "$IMAGE"
        }
        mkdir -p "$DATA"
        t0=$(date +%s)
        if docker inspect "$NAME" >/dev/null 2>&1; then
            running || docker start "$NAME" >/dev/null
        else
            # --privileged: redroid's init mounts binderfs itself and needs net admin for VPNs.
            docker run -d --privileged --name "$NAME" \
                --memory "$MEM" --memory-swap "$MEM" \
                -p "127.0.0.1:$PORT:5555" -v "$DATA:/data" \
                "$IMAGE" \
                androidboot.redroid_gpu_mode=guest \
                androidboot.redroid_width=720 androidboot.redroid_height=1280 \
                androidboot.redroid_dpi=320 >/dev/null
        fi
        for _ in $(seq 1 90); do booted && break; sleep 2; done
        booted || { echo "Android did not finish booting; see: docker logs $NAME" >&2; exit 1; }
        echo "booted in $(( $(date +%s) - t0 )) s"
    fi
    prepare_tun
    adb_connect
}

stop() {
    adb disconnect "$SERIAL" >/dev/null 2>&1 || true
    if running; then docker stop "$NAME" >/dev/null && echo "$NAME stopped"; else echo "$NAME not running"; fi
}

status() {
    if ! docker inspect "$NAME" >/dev/null 2>&1; then echo "$NAME: no container"; return 0; fi
    echo "$NAME: $(docker inspect -f '{{.State.Status}}' "$NAME"), image $IMAGE, data $DATA"
    if running; then
        booted && echo "android: booted" || echo "android: booting"
        docker stats --no-stream --format 'memory: {{.MemUsage}}  cpu: {{.CPUPerc}}' "$NAME"
        adb devices | grep -F "$SERIAL" || echo "adb: $SERIAL not connected"
    fi
}

case "${1:-}" in
    start) start ;;
    stop) stop ;;
    status) status ;;
    adb) running || { echo "$NAME not running" >&2; exit 1; }; adb_connect ;;
    *) echo "usage: $0 start|stop|status|adb" >&2; exit 64 ;;
esac
