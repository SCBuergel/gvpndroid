#!/bin/bash
# Runs ON the build droplet (Ubuntu 24.04, root): toolchain + NDK + pinned sources.
# Prints "STEP <name> <seconds>" lines for timing.
set -euo pipefail
CLIENT_REV=${CLIENT_REV:?}            # gnosis_vpn-client commit to build
NDK_VER=${NDK_VER:-r27c}
NDK_SHA1=${NDK_SHA1:-090e8083a715fdb1a3e402d0763c388abb03fb4e}
export DEBIAN_FRONTEND=noninteractive NEEDRESTART_SUSPEND=1  # skip needrestart service restarts
t=$(date +%s); step() { n=$(date +%s); echo "STEP $1 $((n - t))"; t=$n; }

# cloud-init may still hold the apt lock right after boot
cloud-init status --wait >/dev/null 2>&1 || true
apt-get update -qq
apt-get install -y -qq build-essential cmake git unzip pkg-config clang curl ca-certificates libmnl-dev libnftnl-dev >/dev/null  # nftables libs: host build of the crate tests
step apt

curl -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal --default-toolchain none >/dev/null 2>&1
. "$HOME/.cargo/env"
step rustup

mkdir -p /opt && cd /opt
curl -sSfLO "https://dl.google.com/android/repository/android-ndk-${NDK_VER}-linux.zip"
echo "${NDK_SHA1}  android-ndk-${NDK_VER}-linux.zip" | sha1sum -c --quiet
unzip -q "android-ndk-${NDK_VER}-linux.zip" && rm "android-ndk-${NDK_VER}-linux.zip"
step ndk

mkdir -p /src && cd /src
git clone -q https://github.com/gnosis/gnosis_vpn-client.git
cd gnosis_vpn-client && git checkout -q "$CLIENT_REV"
rustup show active-toolchain >/dev/null 2>&1 || rustup toolchain install "$(sed -n 's/^channel *= *"\(.*\)"/\1/p' rust-toolchain.toml)" --profile minimal
rustup target add aarch64-linux-android x86_64-linux-android >/dev/null
rustc --version
step toolchain

cargo install --locked cargo-ndk >/dev/null 2>&1
step cargo-ndk

cargo fetch --locked >/dev/null 2>&1
du -sh ~/.cargo/registry ~/.cargo/git 2>/dev/null | tr '\n' ' '; echo
step fetch
echo SETUP-DONE
