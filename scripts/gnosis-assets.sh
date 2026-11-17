#!/bin/sh
# Copy the Gnosis VPN data files the core needs into the APK's assets (gitignored):
# the packaged v7 prod config, geoip.mmdb and the PIX proving files (curvy-zk, ~40 MB),
# plus default device settings. The app copies them to no_backup on "Use Gnosis VPN".
# Usage: scripts/gnosis-assets.sh UNPACKED_DEB_ROOT   (dpkg-deb -x gnosisvpn_<ver>_amd64.deb DIR)
set -eu
cd "$(dirname "$0")/.."
src=${1:?usage: $0 UNPACKED_DEB_ROOT}
out=app/src/main/assets/gnosis
mkdir -p "$out/curvy-zk"
cp "$src/etc/gnosisvpn/config-piz-palu-prod.toml" "$out/config.toml"
cp "$src/usr/share/gnosisvpn/geoip.mmdb" "$out/geoip.mmdb"
cp "$src"/usr/share/gnosisvpn/curvy-zk/* "$out/curvy-zk/"
cp "$src/etc/gnosisvpn/version.txt" "$out/package-version.txt"
cat > "$out/gnosis.properties" <<'PROPS'
blokli_url=https://blokli.piz-palu.gnosisvpn.io
network=piz-palu-prod
# GVPN-GER-1 (Berlin); an exit address or a destination id from the core's status
destination=0xe5cE4ae09Ac49e74F3a4d649B93CF0e2CF1DE7bf
PROPS
du -sh "$out"; ls "$out" "$out/curvy-zk"
