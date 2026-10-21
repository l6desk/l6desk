#!/usr/bin/env bash
# Renders the screenshots (shared/src/jvmTest/.../ScreenshotRender.kt, the simulator's desk) and
# copies the ones the README and the user guide show into docs/images/.
#   scripts/guide-images.sh
set -euo pipefail
cd "$(dirname "$0")/.."

./gradlew -q :shared:jvmTest --tests 'io.github.l6desk.ui.ScreenshotRender' --rerun
from=shared/build/screenshots
mkdir -p docs/images
while read -r render image; do
  cp "$from/$render.png" "docs/images/$image.png"
done <<'LIST'
tablet-landscape desk
tablet-portrait desk-portrait
sends-tablet sends
tablet-player player
tablet-midi-log midi-log
setup-tablet setup
tablet-recordings recordings
tablet-report report
LIST
ls -la docs/images
