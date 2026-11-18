#!/bin/bash
# Build, lint and unit-test the app (Gradle) on a throwaway DigitalOcean droplet instead of this
# machine, then copy the APK and reports back and delete the droplet on every exit path.
# A deadline (systemd user timer, 1 h by default) deletes the droplet even if this script dies.
# Usage: scripts/do-build/app.sh [GRADLE TASKS...]   (default: assembleDebug lintDebug testDebugUnitTest)
# Env: SIZE (s-8vcpu-16gb, $0.143/h), REGION (fra1), DEADLINE_S (3600)
# Uploads: the working tree without build output, VCS data or secret-looking files; the debug
# keystore (so the APK updates the installed app); the log relay's topic and PUBLIC key only.
# Results: app/build/outputs/apk/debug/app-debug.apk (checked with scripts/check-apk.sh),
#          app/build/remote/{test-results,reports}, app/build/remote/build.log
set -euo pipefail
cd "$(dirname "$0")/../.."
REPO=$(pwd)
SIZE=${SIZE:-s-8vcpu-16gb}; REGION=${REGION:-fra1}; DEADLINE_S=${DEADLINE_S:-3600}
DEADLINE=${DEADLINE:-$HOME/.claude/plugins/cache/sebs-ai-tools/ephemeral-cloud-run/0.1.1/scripts/deadline}
TASKS=${*:-assembleDebug lintDebug testDebugUnitTest}
ST=$HOME/.local/state/gvpndroid-do; mkdir -p "$ST"; chmod 700 "$ST"
NAME=gvpndroid-app-$(date +%s)
W=$ST/$NAME; mkdir -p "$W"; chmod 700 "$W"     # under /home: /tmp is a small tmpfs here
[ -f "$ST/id_ed25519" ] || ssh-keygen -q -t ed25519 -N '' -C gvpndroid-build -f "$ST/id_ed25519"
t0=$(date +%s); t=$t0
step() { n=$(date +%s); echo "STEP $1 $((n - t))"; t=$n; }
id=""
teardown() {
    rc=$?
    if [ -n "$id" ]; then
        if python3 -I scripts/do-build/droplet.py delete "$id"; then
            python3 "$DEADLINE" cancel "$NAME" >/dev/null 2>&1 || true
        else
            echo "!!! droplet $id NOT verified deleted; its deadline stays armed. Check https://cloud.digitalocean.com/droplets !!!" >&2
        fi
        echo "STEP lifetime $(( $(date +%s) - t0 ))"
    fi
    rm -f "$W/src.tgz"
    exit $rc
}
trap teardown EXIT
trap 'exit 130' INT TERM HUP

python3 -I scripts/do-build/droplet.py create "$NAME" "$SIZE" "$REGION" "$ST/id_ed25519.pub" > "$W/create" &
cpid=$!
for _ in $(seq 1 30); do id=$(head -1 "$W/create" 2>/dev/null || true); [ -n "$id" ] && break; sleep 1; done
[ -n "$id" ] || { wait $cpid || true; echo "droplet creation failed" >&2; exit 1; }
python3 "$DEADLINE" start "$NAME" "$DEADLINE_S" -- python3 -I "$REPO/scripts/do-build/droplet.py" delete "$id"
wait $cpid
ip=$(sed -n 2p "$W/create" | cut -d' ' -f1)
echo "droplet $id at $ip ($SIZE, \$$(sed -n 2p "$W/create" | cut -d' ' -f2)/h)"
step create

SSH_OPTS=(-i "$ST/id_ed25519" -o StrictHostKeyChecking=accept-new -o "UserKnownHostsFile=$W/known_hosts"
          -o BatchMode=yes -o ConnectTimeout=5 -o ServerAliveInterval=30)
ssh_() { ssh "${SSH_OPTS[@]}" "root@$ip" "$@"; }
for _ in $(seq 1 60); do ssh_ true 2>/dev/null && break; sleep 2; done
step ssh

tar -C "$REPO" -czf "$W/src.tgz" --exclude=./build --exclude=./app/build --exclude=.gradle --exclude=.kotlin \
    --exclude=.git --exclude=.claude --exclude=local.properties --exclude='*.conf' --exclude='*.key' \
    --exclude='*.keystore' --exclude='*.jks' --exclude='rust/*/target' .
ssh_ 'mkdir -p /root/src /root/.android /root/relay && chmod 700 /root/relay'
ssh_ 'tar -C /root/src -xzf -' < "$W/src.tgz"
ssh_ 'cat > /root/.android/debug.keystore' < "$HOME/.android/debug.keystore"
for f in ntfy-topic log-x25519.pub; do   # DEBUG ONLY log relay: topic and public key, never the private key
    [ -f "$HOME/.config/gvpndroid-secrets/$f" ] && ssh_ "cat > /root/relay/$f" < "$HOME/.config/gvpndroid-secrets/$f"
done
step upload

ssh_ 'bash -s' > "$W/setup.log" 2>&1 <<'EOF'
set -e
export DEBIAN_FRONTEND=noninteractive NEEDRESTART_SUSPEND=1
cloud-init status --wait >/dev/null 2>&1 || true
apt-get update -qq && apt-get install -y -qq openjdk-17-jdk-headless unzip >/dev/null
mkdir -p /opt/sdk/cmdline-tools && cd /tmp
curl -fsSLo clt.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q clt.zip -d /opt/sdk/cmdline-tools && mv /opt/sdk/cmdline-tools/cmdline-tools /opt/sdk/cmdline-tools/latest
yes | /opt/sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/opt/sdk --licenses >/dev/null
/opt/sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/opt/sdk "platforms;android-35" "build-tools;35.0.0" "build-tools;34.0.0" >/dev/null
echo SETUP-DONE
EOF
grep -q SETUP-DONE "$W/setup.log" || { tail -20 "$W/setup.log"; exit 1; }
step setup

# Detached on the droplet so a dropped SSH connection does not kill the build.
ssh_ "cd /root/src && nohup bash -c 'ANDROID_HOME=/opt/sdk GVPNDROID_SECRETS=/root/relay ./gradlew --no-daemon $TASKS; echo JOB-EXIT \$?' > /root/build.log 2>&1 &"
until ssh_ 'grep -q "^JOB-EXIT" /root/build.log' 2>/dev/null; do sleep 15; done
mkdir -p app/build/remote
ssh_ 'cat /root/build.log' > app/build/remote/build.log
grep -E 'BUILD|^e: |FAILED|JOB-EXIT|tests completed' app/build/remote/build.log | tail -8
step build

ssh_ 'cd /root/src/app/build && tar czf - outputs/apk test-results reports 2>/dev/null' > "$W/out.tgz.part"
mv "$W/out.tgz.part" "$W/out.tgz"
rm -rf app/build/remote/test-results app/build/remote/reports
mkdir -p "$W/out" && tar -C "$W/out" -xzf "$W/out.tgz" && rm "$W/out.tgz"
[ -d "$W/out/test-results" ] && mv "$W/out/test-results" "$W/out/reports" app/build/remote/
if [ -f "$W/out/outputs/apk/debug/app-debug.apk" ]; then
    mkdir -p app/build/outputs/apk/debug
    mv "$W/out/outputs/apk/debug/app-debug.apk" app/build/outputs/apk/debug/app-debug.apk.part
    mv app/build/outputs/apk/debug/app-debug.apk.part app/build/outputs/apk/debug/app-debug.apk
fi
rm -rf "$W/out"
step download
grep -q '^JOB-EXIT 0' app/build/remote/build.log
if [ -f app/build/outputs/apk/debug/app-debug.apk ]; then scripts/check-apk.sh; fi
