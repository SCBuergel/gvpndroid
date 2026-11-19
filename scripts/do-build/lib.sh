# Sourced by app.sh and run.sh.
# token_file: the absolute path of the DigitalOcean token file ($DO_TOKEN_FILE, default
# ~/do-api-token), so the deadline, which runs outside this environment, uses the same one.
# The path only: the token itself never goes into a command line or a state file.
token_file() {
    local f=${DO_TOKEN_FILE:-$HOME/do-api-token}
    case $f in "~"/*) f=$HOME/${f#"~/"} ;; esac
    realpath -m -- "$f"   # -m: a missing file is droplet.py's error to report, not this one's
}

# await_id FILE PID: print line 1 of FILE (the droplet id droplet.py create prints) as soon as it
# appears, or nothing once PID has exited without printing it. No time limit of its own: each API
# call in droplet.py is bounded, and an id printed late must still reach the deadline and teardown.
await_id() {
    local id
    while id=$(head -1 "$1" 2>/dev/null || true); [ -z "$id" ] && kill -0 "$2" 2>/dev/null; do sleep 1; done
    [ -n "$id" ] || id=$(head -1 "$1" 2>/dev/null || true)   # printed just before exiting
    echo "$id"
}

# recover_id FILE PID NAME: for teardown when no id is known but the create (PID, writing FILE)
# was started, e.g. a Ctrl-C during it: the create ignores INT, so wait for the id it prints and
# stop it; if it died without one (TERM to the group), look the droplet up by NAME. Prints the id,
# or nothing (and says why on stderr).
recover_id() {
    local id
    id=$(await_id "$1" "$2")
    kill "$2" 2>/dev/null || true   # done with it: it may still wait for the droplet to be active
    if [ -z "$id" ]; then
        if id=$(python3 -I scripts/do-build/droplet.py find "$3"); then
            [ -n "$id" ] || echo "no droplet $3 was created" >&2
        fi
    fi
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

# fetch_app_results WORKDIR: copies back whichever of the APK, test-results and reports the build on
# the droplet (via the caller's ssh_) produced: custom tasks or a lint failure leave some missing.
# Fails on a failed connection or a corrupt archive.
fetch_app_results() {
    local d
    # tar exits 2 on a missing member, so name only those that exist; none gives an empty download.
    ssh_ 'cd /root/src/app/build 2>/dev/null || exit 0; set --
          for d in outputs/apk test-results reports; do if [ -e "$d" ]; then set -- "$@" "$d"; fi; done
          if [ $# -gt 0 ]; then tar czf - "$@"; fi' > "$1/out.tgz.part"
    mv "$1/out.tgz.part" "$1/out.tgz"
    rm -rf "$1/out"; mkdir -p "$1/out" app/build/remote
    if [ -s "$1/out.tgz" ]; then tar -C "$1/out" -xzf "$1/out.tgz"; fi
    rm "$1/out.tgz"
    for d in test-results reports; do
        rm -rf "app/build/remote/$d"
        if [ -d "$1/out/$d" ]; then mv "$1/out/$d" app/build/remote/; fi
    done
    if [ -f "$1/out/outputs/apk/debug/app-debug.apk" ]; then
        mkdir -p app/build/outputs/apk/debug
        mv "$1/out/outputs/apk/debug/app-debug.apk" app/build/outputs/apk/debug/app-debug.apk.part
        mv app/build/outputs/apk/debug/app-debug.apk.part app/build/outputs/apk/debug/app-debug.apk
    fi
    rm -rf "$1/out"
}
