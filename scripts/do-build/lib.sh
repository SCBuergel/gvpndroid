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
