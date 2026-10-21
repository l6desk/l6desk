#!/usr/bin/env bash
# Tablet dev loop: build, install and launch on the emulator or the Galaxy Tab A11, mirror it
# with scrcpy, and tail the app's log.
#   scripts/dev-tablet.sh               # the only connected device
#   scripts/dev-tablet.sh -s SERIAL     # a specific device (see `adb devices`)
#   scripts/dev-tablet.sh --no-scrcpy   # skip mirroring (e.g. on the emulator)
set -euo pipefail
cd "$(dirname "$0")/.."

# The debug build ("L6Desk debug") installs beside the release, with its own app id.
APP_ID="io.github.l6desk.debug"
ACTIVITY="$APP_ID/io.github.l6desk.MainActivity"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
SCRCPY="$(command -v scrcpy || echo "$HOME/.local/opt/scrcpy/scrcpy")"
MIRROR=1

while (($#)); do
  case "$1" in
    -s) export ANDROID_SERIAL="$2"; shift 2 ;;
    --no-scrcpy) MIRROR=0; shift ;;
    -h|--help) sed -n '2,7p' "$0"; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done

devices=$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')
if [[ -z "${ANDROID_SERIAL:-}" ]]; then
  count=$(wc -w <<<"$devices")
  if ((count == 0)); then echo "No device. Start the emulator (emulator -avd Tab_A11_API35 &) or connect the tablet." >&2; exit 1; fi
  if ((count > 1)); then echo "Several devices; pick one with -s:" >&2; echo "$devices" >&2; exit 1; fi
  export ANDROID_SERIAL="$devices"
fi
echo "Device: $ANDROID_SERIAL"

./gradlew :androidApp:installDebug
"$ADB" shell am start -n "$ACTIVITY" >/dev/null
echo "Launched $ACTIVITY"

if ((MIRROR)) && [[ -x "$SCRCPY" ]]; then
  ADB="$ADB" "$SCRCPY" --stay-awake --window-title "Tab A11 — L6Desk" >/dev/null 2>&1 &
  trap 'kill %1 2>/dev/null || true' EXIT
fi

sleep 1
pid=$("$ADB" shell pidof -s "$APP_ID" | tr -d '\r')
echo "Logs for pid $pid (Ctrl+C to stop)"
"$ADB" logcat --pid="$pid"
