#!/bin/bash
# Checks await_job (lib.sh) without a droplet, over a fake ssh_: scripts/do-build/test-await-job.sh (~1 s)
set -euo pipefail
. "$(dirname "$0")/lib.sh"
d=$(mktemp -d); trap 'rm -rf "$d"' EXIT
export POLL_S=0
fail=0
ssh_() { local n; n=$(( $(cat "$d/n") + 1 )); echo "$n" > "$d/n"; eval "$body"; }   # in await_job's $(...)
check() {  # NAME WANT_RC LIMIT_OFFSET_S SSH_BODY: the fake ssh_ runs SSH_BODY; $n is its call number
    local rc=0
    body=$4; echo 0 > "$d/n"
    ( await_job $(( $(date +%s) + $3 )) ) 2>"$d/err" || rc=$?
    if [ "$rc" = "$2" ]; then echo "ok   $1"; else echo "FAIL $1: rc $rc, want $2 ($(cat "$d/err"))"; fail=1; fi
}
check done-at-once 0 60 'echo 1'
check done-after-polls 0 60 '[ "$n" -ge 3 ] && echo 1 || echo 0'
check survives-7-ssh-failures 0 60 '[ "$n" -le 7 ] && return 255; echo 1'
check gives-up-after-8-ssh-failures 1 60 'return 255'
check failures-must-be-in-a-row 0 60 '[ "$n" -le 14 ] && [ "$n" != 7 ] && return 255; [ "$n" -le 14 ] && echo 0 || echo 1'
check gives-up-at-the-time-limit 1 -1 'echo 0'
for f in app.sh run.sh; do   # both wait for the remote build through it
    grep -q '^await_job ' "$(dirname "$0")/$f" && echo "ok   $f uses await_job" || { echo "FAIL $f does not use await_job"; fail=1; }
done
exit $fail
