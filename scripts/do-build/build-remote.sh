#!/bin/bash
# Runs ON the build droplet after setup-remote.sh: applies our patch to the client, adds the
# Android crate, builds libgnosisvpn.so. Output: /root/out/<abi>/libgnosisvpn.so
# (cargo-ndk also copies stray dylibs of dependencies that declare cdylib targets; only
# libgnosisvpn.so is used.)
# Env: ABIS ("arm64-v8a x86_64"), PROFILE (fast | release), ANDROID_API (26), TESTS (1);
#      SRC, UP, OUT (the client checkout, upload and output folders; for tests)
set -euo pipefail
. "$HOME/.cargo/env"
export ANDROID_NDK_HOME=${ANDROID_NDK_HOME:-/opt/android-ndk-r27c}
ABIS=${ABIS:-arm64-v8a x86_64}; PROFILE=${PROFILE:-fast}; ANDROID_API=${ANDROID_API:-26}
SRC=${SRC:-/src/gnosis_vpn-client}; UP=${UP:-/root/upload}; OUT=${OUT:-/root/out}
# A failed patch, test or build must not leave the previous library for rebuild.sh or run.sh to copy.
rm -f "$OUT"/*/libgnosisvpn.so
cd "$SRC"
P=$UP/0001-android-target.patch; A=$SRC/.git/applied-android.patch   # the patch the tree has now
if [ ! -d gnosis_vpn-android ]; then
    git apply "$P"; cp "$P" "$A"   # not &&: set -e must stop on a patch that fails
    cp -r "$UP/gnosis_vpn-android" .
elif ! cmp -s "$P" "$A"; then
    # A changed patch (rebuild.sh uploads it): back to the pristine client, keeping the crate and
    # the ignored build cache, then the new patch, never one stacked on the other.
    echo "STEP patch changed, re-applying"
    rm -f "$A"; git checkout -q -- .; git clean -fdq -e gnosis_vpn-android
    git apply "$P"; cp "$P" "$A"
fi
targets=""; for a in $ABIS; do targets="$targets -t $a"; done
if [ "$PROFILE" = fast ]; then
    # Release without LTO: minutes from scratch instead of a fat-LTO link per ABI.
    export CARGO_PROFILE_RELEASE_LTO=false CARGO_PROFILE_RELEASE_CODEGEN_UNITS=16 CARGO_PROFILE_RELEASE_STRIP=true
fi
# The crate's contract tests (tests/protocol.json against the client's real types) on the host
# target; a failure stops the build. TESTS=0 skips them.
if [ "${TESTS:-1}" = 1 ]; then
    s=$(date +%s)
    cargo test --locked -p gnosis_vpn-android 2>&1 | grep -E '^(error|test |test result)|panicked' || true
    cargo test --locked -q -p gnosis_vpn-android >/dev/null 2>&1 || { echo "STEP test FAILED"; exit 1; }
    echo "STEP test $(( $(date +%s) - s ))"
fi
s=$(date +%s)
# --locked: the patch carries the Cargo.lock entry, so nothing may be re-resolved.
cargo ndk $targets -P "$ANDROID_API" -o "$OUT" build --locked --release -p gnosis_vpn-android
echo "STEP build-$PROFILE $(( $(date +%s) - s ))"
ls -l "$OUT"/*/libgnosisvpn.so
