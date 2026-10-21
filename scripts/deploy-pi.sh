#!/usr/bin/env bash
# Builds the headless bridge and installs it on a Raspberry Pi over SSH.
#   scripts/deploy-pi.sh USER@HOST                         install or update the bridge
#   scripts/deploy-pi.sh USER@HOST --hotspot SSID PASSWORD also set up the fallback hotspot
# The Pi needs 64-bit Raspberry Pi OS Lite with SSH enabled (see docs/bridge-setup.md).
set -euo pipefail
cd "$(dirname "$0")/.."

target="${1:-}"
[[ -n "$target" ]] || { sed -n '2,5p' "$0"; exit 2; }
shift

./gradlew --console=plain -q :bridge:bridgeDist
dist=bridge/build/dist/l6desk-bridge
echo "Copying to $target…"
# In the home folder, not /tmp: the Pi is often powered off from the app, and a restart clears /tmp.
ssh "$target" 'rm -rf ~/l6desk-bridge && mkdir -p ~/l6desk-bridge'
scp -q "$dist"/* "$target:l6desk-bridge/"
ssh -t "$target" 'sudo ~/l6desk-bridge/pi-setup.sh install'
if [[ "${1:-}" == "--hotspot" ]]; then
  ssh -t "$target" "sudo ~/l6desk-bridge/pi-setup.sh hotspot $(printf '%q %q' "${2:-}" "${3:-}")"
fi
echo "Done. Status any time: ssh $target ./l6desk-bridge/pi-setup.sh status"
