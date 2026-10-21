#!/usr/bin/env bash
# Desktop dev loop: run the desktop app (against the simulator until the USB transport lands).
#   scripts/dev-desktop.sh [extra gradle args]
set -euo pipefail
cd "$(dirname "$0")/.."
exec ./gradlew :desktopApp:run "$@"
