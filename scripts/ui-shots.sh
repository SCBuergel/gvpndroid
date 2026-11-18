#!/bin/sh
# Screenshots of the new UI in redroid next to the prototype's deep links, for review.
# Usage: scripts/ui-shots.sh OUTDIR [STATE...]     (default: every state below)
# A state is a prototype deep link (README of ~/mobile-UI, "Deep links"), e.g. s07-d-pick-dark.
# Needs: redroid running with the debug APK installed (scripts/emulator.sh start), the prototype
# built (cd ~/mobile-UI && python3 -I tools/build.py; served from dist/android) and Playwright's Chromium in ~/.cache.
# Writes OUTDIR/<state>.app.png, <state>.ref.png and <state>.png (side by side).
set -eu
cd "$(dirname "$0")/.."
. "$HOME/android/env.sh"
OUT=${1:?usage: scripts/ui-shots.sh OUTDIR [STATE...]}; shift
mkdir -p "$OUT"
A="adb -s ${ADB_SERIAL:-localhost:5555}"
PKG=org.gnosisvpn.stub.debug
RCV=$PKG/org.gnosisvpn.stub.ui.DebugActReceiver
PROTO=${PROTO:-$HOME/mobile-UI/dist/android}
CHROME=$(ls -d "$HOME"/.cache/ms-playwright/chromium-*/chrome-linux/chrome 2>/dev/null | tail -1)
[ $# -gt 0 ] || set -- i1 i0 e1 s00 s01 s01-d s07 s07-d s08 s08-d s10 s13 s14 s14-d s18c l1 arc s01-pick s01-d-pick s01-set s01-usage \
    s00-add s14-payout s00-direct s01-d-traffic s00-sysvpn s01-balance s08-off s01-dark s07-d-pick-dark s08-dark i1-dark

act() { $A shell am broadcast -n $RCV "$@" >/dev/null; }

# serve the prototype
port=${PORT:-8799}
( cd "$PROTO" && exec timeout 1800 python3 -m http.server $port >/dev/null 2>&1 ) &
srv=$!
trap 'kill $srv 2>/dev/null || true' EXIT
sleep 1
$A shell am start -n $PKG/org.gnosisvpn.stub.ui.UiActivity >/dev/null
for st in "$@"; do
    # app: the same tokens as the prototype's applyHash()
    act --es engine "${ENGINE:-demo}" --es a reset --es mode light
    for tok in $(echo "$st" | tr '-' ' '); do
        case $tok in
            s00|s01|s07|s08|s10|s13|s14|s18c|l1|arc) act --es a preset --es v "$tok" ;;
            i1) act --es a gateRestart ;; i0) act --es a restart ;; e1) act --es a failShow ;;
            d) act --es a view ;;   # after reset the view is simple
            dark|light) act --es mode "$tok" ;;
            pick) act --es a pick ;; set) act --es a settings ;; usage) act --es a usage ;;
            add|payout|direct|traffic|sysvpn|demo) act --es a go --es v "$tok" ;;
            balance) act --es a balance ;; off) act --es a offAsk ;; connect) act --es a connect ;;
        esac
    done
    sleep "${SETTLE:-2}"
    $A exec-out screencap -p > "$OUT/$st.app.png"
    # prototype (no "static": the same moment of the animations is not reproducible anyway)
    timeout 60 "$CHROME" --headless=old --no-sandbox --disable-gpu --hide-scrollbars --window-size=360,640 \
        --force-device-scale-factor=2 --virtual-time-budget=3000 --screenshot="$OUT/$st.ref.png" \
        "http://localhost:$port/#$st-static" >/dev/null 2>&1 || echo "prototype shot failed: $st" >&2
    python3 -I - "$OUT/$st" <<'PY'
import sys
from PIL import Image, ImageDraw
b = sys.argv[1]
a, r = Image.open(b + ".app.png").convert("RGB"), Image.open(b + ".ref.png").convert("RGB")
h = max(a.height, r.height)
out = Image.new("RGB", (a.width + r.width + 24, h + 40), "white")
out.paste(a, (0, 40)); out.paste(r, (a.width + 24, 40))
d = ImageDraw.Draw(out); d.text((8, 12), "app (redroid)", fill="black"); d.text((a.width + 32, 12), "prototype", fill="black")
out.thumbnail((1000, 1000)); out.save(b + ".png")
PY
    echo "$st: $($A logcat -d -s GnosisUi:I | grep 'screen ' | tail -1 | sed 's/.*screen //')"
done
