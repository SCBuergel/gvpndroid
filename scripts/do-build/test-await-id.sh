#!/bin/bash
# Checks await_id (lib.sh) without the DigitalOcean API: scripts/do-build/test-await-id.sh (~35 s)
set -euo pipefail
. "$(dirname "$0")/lib.sh"
d=$(mktemp -d); trap 'rm -rf "$d"' EXIT
fail=0
check() {  # NAME EXPECTED MAX_SECONDS COMMAND (runs in the background, stdout to the create file)
    local f="$d/$1" pid got t0 s
    bash -c "$4" > "$f" & pid=$!
    t0=$SECONDS; got=$(await_id "$f" "$pid"); s=$((SECONDS - t0))
    kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true
    if [ "$got" = "$2" ] && [ "$s" -le "$3" ]; then echo "ok   $1"
    else echo "FAIL $1: got '$got' after ${s} s, want '$2' within $3 s"; fail=1; fi
}
check late-id 123 40 'sleep 32; echo 123; sleep 1; echo "1.2.3.4 0.1"'   # after the old 30 s limit
check id-then-exit 456 5 'echo 456; exit 1'
check no-id "" 5 'sleep 1; exit 1'
check returns-early 789 5 'echo 789; sleep 30'
exit $fail
