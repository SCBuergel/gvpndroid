#!/bin/bash
# Build the Rust client core (libgnosisvpn.so) for Android on a throwaway DigitalOcean droplet,
# copy it into app/src/main/jniLibs, and delete the droplet on every exit path.
# A deadline (systemd user timer, default 6 h) deletes the droplet even if this script dies.
# Usage: scripts/do-build/run.sh
#        KEEP=1 scripts/do-build/run.sh   keep the droplet for iterating: scripts/do-build/rebuild.sh,
#                                          then scripts/do-build/down.sh (the deadline stays armed)
# Env: SIZE (c-32), REGION (ams3), PROFILE (fast | release), ABIS ("arm64-v8a x86_64"),
#      CLIENT_REV (gnosis_vpn-client commit the patch is made for), DEADLINE_S (21600), TESTS (1)
# The DigitalOcean token is read by droplet.py from $DO_TOKEN_FILE (default ~/do-api-token).
set -euo pipefail
cd "$(dirname "$0")/../.."
. scripts/do-build/lib.sh
REPO=$(pwd)
DO_TOKEN_FILE=$(token_file); export DO_TOKEN_FILE   # one token file for create, teardown and the deadline
SIZE=${SIZE:-c-32}; REGION=${REGION:-ams3}; PROFILE=${PROFILE:-fast}; ABIS=${ABIS:-arm64-v8a x86_64}
CLIENT_REV=${CLIENT_REV:-8a0e3be}; DEADLINE_S=${DEADLINE_S:-21600}
TESTS=${TESTS:-1}; case $TESTS in 0|1) ;; *) echo "TESTS must be 0 or 1" >&2; exit 2 ;; esac   # 0 skips the contract tests
DEADLINE=${DEADLINE:-$HOME/.claude/plugins/cache/sebs-ai-tools/ephemeral-cloud-run/0.1.1/scripts/deadline}
ST=$HOME/.local/state/gvpndroid-do; mkdir -p "$ST"; chmod 700 "$ST"
# One kept droplet at a time: rebuild.sh and down.sh know only $ST/current. Checked before any create.
if [ "${KEEP:-0}" = 1 ] && [ -e "$ST/current" ]; then
    echo "a droplet is already kept ($ST/current): use scripts/do-build/rebuild.sh, or scripts/do-build/down.sh first" >&2
    exit 1
fi
NAME=gvpndroid-build-$(date +%s)
KNOWN_HOSTS=$ST/$NAME.known_hosts   # fresh per run: droplet IPs get reused
[ -f "$ST/id_ed25519" ] || ssh-keygen -q -t ed25519 -N '' -C gvpndroid-build -f "$ST/id_ed25519"
t0=$(date +%s); t=$t0
step() { n=$(date +%s); echo "STEP $1 $((n - t))"; t=$n; }
id=""
teardown() {
    rc=$?
    if [ -n "$id" ] && [ "${KEEP:-0}" = 1 ] && [ "$rc" = 0 ]; then
        # noclobber: a droplet kept meanwhile by another run is never overwritten; this one is deleted.
        if (set -C; printf 'id=%s\nip=%s\nname=%s\nknown_hosts=%s\ntoken_file=%q\n' "$id" "$ip" "$NAME" "$KNOWN_HOSTS" "$DO_TOKEN_FILE" > "$ST/current") 2>/dev/null; then
            echo "KEPT droplet $id at $ip (deadline $NAME armed); iterate with scripts/do-build/rebuild.sh, end with scripts/do-build/down.sh"
            exit 0
        fi
        echo "another droplet was kept meanwhile ($ST/current); deleting this one" >&2
    fi
    if [ -n "$id" ]; then
        if python3 -I scripts/do-build/droplet.py delete "$id"; then
            python3 "$DEADLINE" cancel "$NAME" >/dev/null 2>&1 || true
        else
            echo "!!! droplet $id NOT verified deleted; its deadline stays armed. Check https://cloud.digitalocean.com/droplets !!!" >&2
        fi
        echo "STEP lifetime $(( $(date +%s) - t0 ))"
    fi
    exit $rc
}
trap teardown EXIT
trap 'exit 130' INT TERM HUP

python3 -I scripts/do-build/droplet.py create "$NAME" "$SIZE" "$REGION" "$ST/id_ed25519.pub" > "$ST/$NAME.create" &
cpid=$!
id=$(await_id "$ST/$NAME.create" $cpid)   # as long as the create runs: a late id still gets a deadline
[ -n "$id" ] || { wait $cpid || true; echo "droplet creation failed" >&2; exit 1; }
python3 "$DEADLINE" start "$NAME" "$DEADLINE_S" -- env "DO_TOKEN_FILE=$DO_TOKEN_FILE" python3 -I "$REPO/scripts/do-build/droplet.py" delete "$id"
wait $cpid
ip=$(sed -n 2p "$ST/$NAME.create" | cut -d' ' -f1)
echo "droplet $id at $ip ($SIZE, \$$(sed -n 2p "$ST/$NAME.create" | cut -d' ' -f2)/h)"
step create

SSH_OPTS=(-i "$ST/id_ed25519" -o StrictHostKeyChecking=accept-new -o "UserKnownHostsFile=$KNOWN_HOSTS"
          -o BatchMode=yes -o ConnectTimeout=5 -o ServerAliveInterval=30)
ssh_() { ssh "${SSH_OPTS[@]}" "root@$ip" "$@"; }
for _ in $(seq 1 60); do ssh_ true 2>/dev/null && break; sleep 2; done
step ssh

ssh_ mkdir -p /root/upload
scp -q -r "${SSH_OPTS[@]}" scripts/do-build/setup-remote.sh scripts/do-build/build-remote.sh \
    rust/patches/0001-android-target.patch rust/gnosis_vpn-android "root@$ip:/root/upload/"
ssh_ "CLIENT_REV=$CLIENT_REV bash /root/upload/setup-remote.sh" | grep -E '^STEP|^rustc|SETUP-DONE'
step setup

# Detached on the droplet so a dropped SSH connection does not kill the build.
ssh_ "ABIS='$ABIS' PROFILE=$PROFILE TESTS=$TESTS nohup bash -c 'bash /root/upload/build-remote.sh; echo JOB-EXIT \$?' > /root/build.log 2>&1 &"
# Bounded: stop before the deadline deletes the droplet, or when it stops answering.
await_job $(( t0 + DEADLINE_S - 300 ))
ssh_ 'grep -E "^(error|STEP|JOB-EXIT|test )|could not compile|panicked" /root/build.log'
ssh_ 'grep -q "^JOB-EXIT 0" /root/build.log' || { ssh_ 'tail -40 /root/build.log'; exit 1; }
step build

for abi in $ABIS; do
    mkdir -p "app/src/main/jniLibs/$abi"
    scp -q "${SSH_OPTS[@]}" "root@$ip:/root/out/$abi/libgnosisvpn.so" "app/src/main/jniLibs/$abi/libgnosisvpn.so.part"
    mv "app/src/main/jniLibs/$abi/libgnosisvpn.so.part" "app/src/main/jniLibs/$abi/libgnosisvpn.so"
done
ls -l app/src/main/jniLibs/*/libgnosisvpn.so
step download
