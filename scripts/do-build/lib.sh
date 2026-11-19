# Sourced by app.sh and run.sh.
# await_id FILE PID: print line 1 of FILE (the droplet id droplet.py create prints) as soon as it
# appears, or nothing once PID has exited without printing it. No time limit of its own: each API
# call in droplet.py is bounded, and an id printed late must still reach the deadline and teardown.
await_id() {
    local id
    while id=$(head -1 "$1" 2>/dev/null || true); [ -z "$id" ] && kill -0 "$2" 2>/dev/null; do sleep 1; done
    [ -n "$id" ] || id=$(head -1 "$1" 2>/dev/null || true)   # printed just before exiting
    echo "$id"
}

# await_job LIMIT: wait until /root/build.log on the droplet (via the caller's ssh_) has its JOB-EXIT
# line. Exits the script with 1 once LIMIT (epoch seconds) has passed or after 8 failed ssh
# connections in a row, so teardown deletes the droplet. POLL_S (15) is the poll interval.
await_job() {
    local r fails=0
    while :; do
        # ssh exits 255 only when the connection fails; the remote command always prints 1 or 0.
        if r=$(ssh_ 'if grep -q "^JOB-EXIT" /root/build.log 2>/dev/null; then echo 1; else echo 0; fi' 2>/dev/null); then
            fails=0; [ "$r" = 1 ] && return 0
        else
            fails=$((fails + 1)); [ "$fails" -ge 8 ] && { echo "droplet stopped answering (8 ssh failures in a row)" >&2; exit 1; }
        fi
        [ "$(date +%s)" -lt "$1" ] || { echo "build still running at its time limit, just before the deadline; giving up" >&2; exit 1; }
        sleep "${POLL_S:-15}"
    done
}
