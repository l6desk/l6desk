#!/usr/bin/env bash
# Sets up Ubuntu 24.04 for L6Desk development. Safe to re-run: every
# step checks first and skips what is already in place.
#
#   scripts/setup-ubuntu.sh            install / configure everything
#   scripts/setup-ubuntu.sh --check    report status only, change nothing
#   scripts/setup-ubuntu.sh --no-sudo  skip the steps that need sudo
#
# Steps that need sudo: apt packages, adding you to the kvm group.
# Everything else installs under your home directory.

set -euo pipefail

CHECK=0
NO_SUDO=0
for arg in "$@"; do
  case "$arg" in
    --check) CHECK=1 ;;
    --no-sudo) NO_SUDO=1 ;;
    -h|--help) sed -n '2,11p' "$0"; exit 0 ;;
    *) echo "Unknown option: $arg" >&2; exit 2 ;;
  esac
done

# ---- What we want ----------------------------------------------------------
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
APT_PACKAGES=(openjdk-17-jdk unzip curl git alsa-utils android-sdk-platform-tools-common)
# Package paths as used by the `android sdk` tool (`/` separators); also the directory under $ANDROID_HOME.
# The platform must match android-compileSdk in gradle/libs.versions.toml.
SDK_PACKAGES=(
  "platform-tools"
  "emulator"
  "build-tools/37.0.0"
  "platforms/android-37.0"
  "system-images/android-35/google_apis_playstore_tablet/x86_64"
)
# Emulator approximating the Galaxy Tab A11 (8.7", 1340x800, Android 15 / API 35).
# Density is a first guess: compare with `adb shell wm density` on the real tablet.
AVD_NAME="Tab_A11_API35"
AVD_IMAGE="system-images;android-35;google_apis_playstore_tablet;x86_64"   # avdmanager still uses ";"
AVD_WIDTH=1340
AVD_HEIGHT=800
AVD_DENSITY=213
SCRCPY_DIR="$HOME/.local/opt/scrcpy"
PROFILE="$HOME/.profile"
PROFILE_MARK="# >>> l6maxcontrol android env >>>"

# ---- Output helpers -----------------------------------------------------------
ok()   { printf '  \e[32m✓\e[0m %s\n' "$*"; }
todo() { printf '  \e[33m•\e[0m %s\n' "$*"; MISSING=$((MISSING + 1)); }
info() { printf '    %s\n' "$*"; }
step() { printf '\n\e[1m%s\e[0m\n' "$*"; }
MISSING=0

sdkmanager_bin() { echo "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"; }
avdmanager_bin() { echo "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"; }
android_bin()    { echo "$ANDROID_HOME/cmdline-tools/latest/bin/android"; }
sdk_path() { echo "$ANDROID_HOME/${1//;//}"; }   # "platforms/android-37.0" or "platforms;android-37.0" -> $ANDROID_HOME/platforms/android-37.0

# ---- 1. apt packages ------------------------------------------------------------
step "1. System packages (apt)"
missing_apt=()
for p in "${APT_PACKAGES[@]}"; do
  if dpkg-query -W -f='${Status}' "$p" 2>/dev/null | grep -q 'install ok installed'; then ok "$p"; else missing_apt+=("$p"); fi
done
if ((${#missing_apt[@]})); then
  if ((CHECK)); then todo "missing: ${missing_apt[*]}"
  elif ((NO_SUDO)); then todo "skipped (--no-sudo): ${missing_apt[*]}"
  else
    info "Installing: ${missing_apt[*]}"
    sudo apt-get update -qq
    sudo apt-get install -y "${missing_apt[@]}"
  fi
fi

# ---- 2. KVM ---------------------------------------------------------------------
step "2. KVM (fast emulator)"
if [[ ! -e /dev/kvm ]]; then
  todo "/dev/kvm missing: enable virtualisation (VT-x/AMD-V) in the BIOS"
else
  if [[ -r /dev/kvm && -w /dev/kvm ]]; then ok "/dev/kvm is usable now"; else todo "/dev/kvm exists but you can't open it yet"; fi
  if id -nG "$USER" | tr ' ' '\n' | grep -qx kvm; then
    ok "$USER is in the kvm group"
  elif ((CHECK)); then
    todo "$USER is not in the kvm group (access currently relies on the desktop-session ACL)"
  elif ((NO_SUDO)); then
    todo "skipped (--no-sudo): add $USER to the kvm group"
  else
    sudo usermod -aG kvm "$USER"
    ok "added $USER to the kvm group (takes effect at next login)"
  fi
fi

# ---- 3. Android SDK command-line tools ----------------------------------------
step "3. Android SDK command-line tools ($ANDROID_HOME)"
if [[ -x "$(sdkmanager_bin)" ]]; then
  ok "cmdline-tools: $(grep -m1 Pkg.Revision "$ANDROID_HOME/cmdline-tools/latest/source.properties" | cut -d= -f2 | tr -d ' ')"
elif ((CHECK)); then
  todo "cmdline-tools (sdkmanager, avdmanager) not installed"
else
  # The download URL changes with each release, so read it from Google's repository index.
  info "Looking up the latest cmdline-tools release…"
  zip_name="$(curl -fsSL https://dl.google.com/android/repository/repository2-3.xml | python3 -I -c '
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.stdin).getroot()
best = None
for pkg in root.iter("remotePackage"):
    ch = pkg.find("channelRef")
    if pkg.get("path") != "cmdline-tools;latest" or ch is None or ch.get("ref") != "channel-0":
        continue
    for a in pkg.iter("archive"):
        if (a.findtext("host-os") or "") == "linux":
            best = a.find("complete").findtext("url")
print(best or "")')"
  [[ -n "$zip_name" ]] || { echo "Could not find the cmdline-tools download" >&2; exit 1; }
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  info "Downloading $zip_name"
  curl -fL --progress-bar -o "$tmp/cmdline-tools.zip" "https://dl.google.com/android/repository/$zip_name"
  unzip -q "$tmp/cmdline-tools.zip" -d "$tmp"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  ok "cmdline-tools installed"
fi

# ---- 4. SDK packages --------------------------------------------------------------
step "4. SDK packages"
missing_sdk=()
for p in "${SDK_PACKAGES[@]}"; do
  if [[ -d "$(sdk_path "$p")" ]]; then ok "$p"; else missing_sdk+=("$p"); fi
done
if ((${#missing_sdk[@]})); then
  if ((CHECK)) || [[ ! -x "$(sdkmanager_bin)" ]]; then todo "missing: ${missing_sdk[*]}"
  else
    info "Installing: ${missing_sdk[*]}"
    if [[ -x "$(android_bin)" ]]; then
      # cmdline-tools 23+: sdkmanager is deprecated in favour of `android sdk`, which needs --sdk.
      "$(android_bin)" --sdk="$ANDROID_HOME" sdk install "${missing_sdk[@]}"
    else
      yes | "$(sdkmanager_bin)" --licenses >/dev/null || true
      "$(sdkmanager_bin)" --install "${missing_sdk[@]//\//;}"
    fi
  fi
fi

# ---- 5. Tablet emulator -----------------------------------------------------------
step "5. Emulator: $AVD_NAME (Galaxy Tab A11 approximation)"
avd_ini="$HOME/.android/avd/$AVD_NAME.avd/config.ini"
if [[ -f "$avd_ini" ]]; then
  ok "$AVD_NAME exists"
elif ((CHECK)) || [[ ! -x "$(avdmanager_bin)" ]] || [[ ! -d "$(sdk_path "$AVD_IMAGE")" ]]; then
  todo "$AVD_NAME not created"
else
  echo no | "$(avdmanager_bin)" create avd --name "$AVD_NAME" --package "$AVD_IMAGE" --device "pixel_tablet" >/dev/null
  # Override the screen to the Tab A11's panel, landscape.
  sed -i -e '/^hw.lcd.width=/d' -e '/^hw.lcd.height=/d' -e '/^hw.lcd.density=/d' \
         -e '/^hw.initialOrientation=/d' -e '/^showDeviceFrame=/d' -e '/^skin\./d' "$avd_ini"
  cat >> "$avd_ini" <<EOF
hw.lcd.width=$AVD_WIDTH
hw.lcd.height=$AVD_HEIGHT
hw.lcd.density=$AVD_DENSITY
hw.initialOrientation=landscape
hw.keyboard=yes
showDeviceFrame=no
skin.dynamic=yes
EOF
  ok "$AVD_NAME created (${AVD_WIDTH}x${AVD_HEIGHT}, ${AVD_DENSITY} dpi)"
fi

# ---- 6. scrcpy ---------------------------------------------------------------------
step "6. scrcpy (mirror the real tablet)"
if [[ -x "$SCRCPY_DIR/scrcpy" ]]; then
  ok "scrcpy $("$SCRCPY_DIR/scrcpy" --version 2>/dev/null | head -1 | awk '{print $2}') in $SCRCPY_DIR"
elif ((CHECK)); then
  todo "scrcpy not installed (Ubuntu's apt version 1.25 is too old; the official static build is used)"
else
  url="$(curl -fsSL https://api.github.com/repos/Genymobile/scrcpy/releases/latest \
        | python3 -I -c 'import json,sys; print(next(a["browser_download_url"] for a in json.load(sys.stdin)["assets"] if "linux-x86_64" in a["name"] and a["name"].endswith(".tar.gz")))')"
  tmp2="$(mktemp -d)"
  info "Downloading $url"
  curl -fL --progress-bar -o "$tmp2/scrcpy.tar.gz" "$url"
  tar -xzf "$tmp2/scrcpy.tar.gz" -C "$tmp2"
  rm -rf "$SCRCPY_DIR"
  mkdir -p "$(dirname "$SCRCPY_DIR")"
  mv "$(find "$tmp2" -mindepth 1 -maxdepth 1 -type d | head -1)" "$SCRCPY_DIR"
  rm -rf "$tmp2"
  ok "scrcpy installed in $SCRCPY_DIR"
fi

# ---- 7. Environment -----------------------------------------------------------------
step "7. Environment ($PROFILE)"
if grep -qF "$PROFILE_MARK" "$PROFILE" 2>/dev/null; then
  ok "ANDROID_HOME and PATH block present"
elif ((CHECK)); then
  todo "ANDROID_HOME / PATH not configured"
else
  cat >> "$PROFILE" <<EOF

$PROFILE_MARK
export ANDROID_HOME="$ANDROID_HOME"
export PATH="\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/emulator:$SCRCPY_DIR:\$PATH"
# scrcpy should use the SDK's adb so two adb servers don't fight over the device.
export ADB="\$ANDROID_HOME/platform-tools/adb"
# <<< l6maxcontrol android env <<<
EOF
  ok "added ANDROID_HOME and PATH (log out and back in, or run: source ~/.profile)"
fi

# ---- Summary --------------------------------------------------------------------------
step "Summary"
if ((MISSING)); then
  echo "  $MISSING item(s) still to do (marked •)."
  ((CHECK)) && echo "  Run scripts/setup-ubuntu.sh to install them."
else
  echo "  All set. Quick checks:"
  echo "    adb devices                         # tablet listed after USB debugging is allowed"
  printf "    %-36s# tablet-sized emulator\n" "emulator -avd $AVD_NAME &"
  echo "    scrcpy                              # mirror the connected tablet"
  echo "    amidi -l                            # L6max MIDI ports"
fi
