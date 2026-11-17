#!/bin/bash
# Rebuild libgnosisvpn.so on the droplet kept by `KEEP=1 run.sh`: uploads rust/gnosis_vpn-android,
# builds incrementally, copies the libraries into app/src/main/jniLibs.
# Env: ABIS ("arm64-v8a x86_64"), PROFILE (fast)
set -euo pipefail
cd "$(dirname "$0")/../.."
ST=$HOME/.local/state/gvpndroid-do
. <(sed 's/^/export /' "$ST/current")
ABIS=${ABIS:-arm64-v8a x86_64}; PROFILE=${PROFILE:-fast}
SSH_OPTS=(-i "$ST/id_ed25519" -o "UserKnownHostsFile=$known_hosts" -o BatchMode=yes -o ConnectTimeout=5)
scp -q -r "${SSH_OPTS[@]}" rust/gnosis_vpn-android/Cargo.toml rust/gnosis_vpn-android/build.rs rust/gnosis_vpn-android/src rust/gnosis_vpn-android/tests \
    "root@$ip:/src/gnosis_vpn-client/gnosis_vpn-android/"
s=$(date +%s)
ssh "${SSH_OPTS[@]}" "root@$ip" "ABIS='$ABIS' PROFILE=$PROFILE bash /root/upload/build-remote.sh" 2>&1 \
    | grep -E '^(error|warning: unused)|^ *--> gnosis_vpn-android|STEP|could not compile' || true
for abi in $ABIS; do
    mkdir -p "app/src/main/jniLibs/$abi"
    scp -q "${SSH_OPTS[@]}" "root@$ip:/root/out/$abi/libgnosisvpn.so" "app/src/main/jniLibs/$abi/libgnosisvpn.so.part"
    mv "app/src/main/jniLibs/$abi/libgnosisvpn.so.part" "app/src/main/jniLibs/$abi/libgnosisvpn.so"
done
echo "rebuilt in $(( $(date +%s) - s )) s"; ls -l app/src/main/jniLibs/*/libgnosisvpn.so
