#!/bin/sh
# Show a WireGuard config as a QR code on this machine's screen, for scanning with the app.
# Usage: scripts/show-config-qr.sh PATH/TO/peer.conf
# The PNG lives only in a private tmpfs dir and is deleted when the viewer closes.
set -eu
conf=${1:?usage: $0 peer.conf}
dir=$(mktemp -d "${XDG_RUNTIME_DIR:-/tmp}/wgqr.XXXXXX")
chmod 700 "$dir"
trap 'rm -rf "$dir"' EXIT INT TERM
uv run --quiet --with segno==1.6.6 python -I -c '
import sys, segno
segno.make(open(sys.argv[1]).read().strip(), error="m").save(sys.argv[2], scale=8, border=4)
' "$conf" "$dir/config.png"
# The viewer must block until closed: the trap deletes the PNG as soon as it returns.
command -v ristretto >/dev/null || { echo "ristretto not installed" >&2; exit 1; }
ristretto "$dir/config.png"
