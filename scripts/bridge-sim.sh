#!/usr/bin/env bash
# Runs a Wi-Fi bridge with the L6max simulator behind it, so the tablet (or the Android
# emulator, which reaches this computer at 10.0.2.2) can be tested without the mixer.
#   scripts/bridge-sim.sh [PORT [CARD]]    (default 5704)
# CARD: an L6max card's folder (e.g. /media/$USER/ZOOM_SD) whose recordings it offers (read only).
set -euo pipefail
cd "$(dirname "$0")/.."
exec ./gradlew --console=plain -q :desktopApp:runBridgeSim --args="${1:-5704} ${2:-}"
