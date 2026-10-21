#!/usr/bin/env bash
# Sets up the L6Desk headless bridge on a Raspberry Pi (64-bit Raspberry Pi OS Lite,
# bookworm or trixie). Run on the Pi, from the folder containing this script and the jar.
#
#   sudo ./pi-setup.sh install                 Java, the bridge service (starts at boot)
#   sudo ./pi-setup.sh hotspot SSID PASSWORD   Pi's own Wi-Fi network, used when no known
#                                              network is in range (so SSH at home keeps working)
#   sudo ./pi-setup.sh hotspot SSID PASSWORD --always
#                                              Hotspot always, starting now (ends Wi-Fi SSH sessions)
#   sudo ./pi-setup.sh hotspot-off             Remove the hotspot
#   sudo ./pi-setup.sh pin 1234                PIN the app must give to connect (4 to 8 digits)
#   sudo ./pi-setup.sh pin off                 No PIN: any app on the network can connect
#   ./pi-setup.sh status                       Service, network, MIDI and recent log
#   sudo ./pi-setup.sh uninstall               Remove the bridge (keeps Java and the hotspot)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
# Paths can be overridden for testing (e.g. L6DESK_ROOT=/tmp/fake-root); normally unset.
ROOT="${L6DESK_ROOT:-}"
APP_DIR=$ROOT/opt/l6desk-bridge
CONF=$ROOT/etc/l6desk-bridge.conf
UNIT=$ROOT/etc/systemd/system/l6desk-bridge.service
# Powering off when the app asks: a path unit (root) watching for the bridge's request file.
POWEROFF_UNITS="l6desk-poweroff.path l6desk-poweroff.service"
# PAD SOUNDS through the Pi: a udev rule mounts the L6max's card (file transfer mode) for the bridge,
# and a path unit (root) ejects it when the bridge asks.
CARD_UNITS="l6desk-card@.service l6desk-card-eject.path l6desk-card-eject.service"
CARD_RULE=$ROOT/etc/udev/rules.d/90-l6desk-card.rules
USER_NAME=l6desk-bridge
MUSIC_DIR=$ROOT/srv/l6max-music
HOTSPOT=L6Desk-Hotspot
# Before 0.13 the project was called L6max Remote and the bridge l6max-bridge (see migrate_old_names).
OLD_NAME=l6max-bridge
OLD_HOTSPOT=L6max-Hotspot
HOTSPOT_IP=10.42.0.1

need_root() { [[ $EUID -eq 0 || -n "$ROOT" ]] || { echo "Run with sudo: sudo $0 $*" >&2; exit 1; }; }
say() { printf '\e[1m%s\e[0m\n' "$*"; }

cmd_install() {
  need_root install
  [[ -f "$HERE/l6desk-bridge.jar" ]] || { echo "l6desk-bridge.jar not found next to this script" >&2; exit 1; }
  say "Installing Java, ALSA tools, Avahi and ffmpeg…"
  apt-get update -qq
  apt-get install -y --no-install-recommends default-jre-headless alsa-utils avahi-daemon ffmpeg
  java -version 2>&1 | head -1

  migrate_old_names
  say "Installing the bridge…"
  id -u "$USER_NAME" >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin --groups audio "$USER_NAME"
  command install -d "$APP_DIR" "$(dirname "$UNIT")"
  command install -m 0644 "$HERE/l6desk-bridge.jar" "$APP_DIR/"
  command install -m 0755 "$HERE/announce.sh" "$APP_DIR/"
  if [[ -f "$CONF" ]]; then echo "Keeping existing $CONF"; else command install -m 0644 "$HERE/l6desk-bridge.conf" "$CONF"; fi
  command install -m 0644 "$HERE/l6desk-bridge.service" "$UNIT"
  for u in $POWEROFF_UNITS $CARD_UNITS; do command install -m 0644 "$HERE/$u" "$(dirname "$UNIT")/$u"; done
  command install -D -m 0644 "$HERE/90-l6desk-card.rules" "$CARD_RULE"
  udevadm control --reload-rules 2>/dev/null || true
  # Music folder: owned by the user who ran sudo (so songs can be copied in over SSH), shared with
  # the bridge through its group (it keeps playlists, uploads and settings there). That user joins
  # the group too, so playlists the bridge made can be copied into.
  local owner="${SUDO_USER:-root}"
  if [[ ! -d "$MUSIC_DIR" ]]; then
    command install -d -m 2775 -o "$owner" -g "$USER_NAME" "$MUSIC_DIR"
    echo "Created the music folder $MUSIC_DIR (upload songs from the app's PLAYER panel)"
  fi
  if [[ "$owner" != root ]] && ! id -nG "$owner" | grep -qw "$USER_NAME"; then
    usermod -aG "$USER_NAME" "$owner"
    echo "Added $owner to the $USER_NAME group (for scripts/music-pi.sh)"
  fi
  # Folders made before playlists existed: group-writable throughout.
  chmod -R g+rwX "$MUSIC_DIR" 2>/dev/null || true
  find "$MUSIC_DIR" -type d -exec chmod g+s {} + 2>/dev/null || true
  if [[ -f "$CONF" ]] && ! grep -qE '^\s*music\s*=' "$CONF"; then
    printf '\n# Music player folder (empty = off)\nmusic=/srv/l6max-music\n' >> "$CONF"
    echo "Added the music player to $CONF"
  fi
  systemctl daemon-reload
  systemctl enable l6desk-bridge >/dev/null
  systemctl enable --now l6desk-poweroff.path >/dev/null
  systemctl enable --now l6desk-card-eject.path >/dev/null
  if [[ -f "$CONF" ]] && ! grep -qE '^\s*power_off\s*=' "$CONF"; then
    printf '\n# Let the app power the Pi off (its "closing the app shuts down the Pi bridge" option)\npower_off=true\n' >> "$CONF"
    echo "Added power_off=true to $CONF (the app can shut the Pi down when it closes)"
  fi
  if [[ -f "$CONF" ]] && ! grep -qE '^\s*pin\s*=' "$CONF"; then
    printf '\n# PIN (4 to 8 digits) the app must give to connect. Empty = none.\npin=\n' >> "$CONF"
    echo "Added an empty pin= to $CONF (set one so a stray phone can't take over the desk)"
  fi

  local reboot_needed=""
  # USB: leave the Pi's own driver (dwc_otg) in charge. It's in host mode by default
  # (cil_force_host), so any data cable to the L6max works, and it carries USB audio cleanly.
  # Earlier versions of this script switched Zero boards to the generic dwc2 driver: MIDI was
  # fine, but the music player's audio broke up into crackles after a minute or two. Undo that.
  local boot_cfg="$ROOT/boot/firmware/config.txt"
  if [[ -f "$boot_cfg" ]] && grep -q '^# L6max bridge: USB host mode with any cable' "$boot_cfg"; then
    cp "$boot_cfg" "$boot_cfg.l6max-bak"
    awk '/^\[/{s=$0} !(s=="[all]" && ($0=="dtoverlay=dwc2,dr_mode=host" || $0=="# L6max bridge: USB host mode with any cable"))' \
      "$boot_cfg.l6max-bak" > "$boot_cfg"
    echo "Switched USB back to the Pi's own driver in $boot_cfg (backup: config.txt.l6max-bak)"
    reboot_needed=1
  fi
  # Wi-Fi power saving makes the Pi doze between packets: uneven latency (tens of ms) and
  # occasional loss, which the tablet feels as laggy faders. Turn it off for all Wi-Fi.
  local nm_conf="$ROOT/etc/NetworkManager/conf.d/90-l6desk-wifi-powersave.conf"
  if [[ -d "$ROOT/etc/NetworkManager" && ! -f "$nm_conf" ]]; then
    command install -d "$(dirname "$nm_conf")"
    printf '# L6max bridge: no Wi-Fi power saving (steady latency for the tablet)\n[connection]\nwifi.powersave = 2\n' > "$nm_conf"
    echo "Turned off Wi-Fi power saving ($nm_conf)"
    reboot_needed=1
  fi

  systemctl restart l6desk-bridge
  sleep 4
  systemctl --no-pager --lines=0 status l6desk-bridge | head -3 || true
  journalctl -u l6desk-bridge -n 5 --no-pager -o cat || true
  say "Installed. The bridge starts at boot. Logs: journalctl -u l6desk-bridge -f"
  pin_hint
  if [[ -n "$reboot_needed" ]]; then say "Reboot to apply the USB / Wi-Fi changes: sudo reboot"; fi
}

# The bridge's PIN (pin= in the config), or nothing for none.
conf_pin() { sed -n 's/^[[:space:]]*pin[[:space:]]*=[[:space:]]*//p' "$CONF" 2>/dev/null | tail -1 | tr -d '[:space:]'; }

pin_hint() {
  if [[ -z "$(conf_pin)" ]]; then
    say "No PIN set: any app on the bridge's network can connect. To set one: sudo $0 pin 1234"
  else
    say "A PIN is set: apps ask for it once. Change it: sudo $0 pin NEW   Remove it: sudo $0 pin off"
  fi
}

cmd_pin() {
  need_root pin
  local pin="${1:-}"
  case "$pin" in
    off | none) pin="" ;;
    *) [[ "$pin" =~ ^[0-9]{4,8}$ ]] || { echo "Usage: sudo $0 pin DIGITS   (4 to 8 digits)   or: sudo $0 pin off" >&2; exit 2; } ;;
  esac
  [[ -f "$CONF" ]] || { echo "No $CONF yet: install the bridge first (sudo $0 install)" >&2; exit 1; }
  if grep -qE '^[[:space:]]*pin[[:space:]]*=' "$CONF"; then
    sed -i -E "s/^[[:space:]]*pin[[:space:]]*=.*/pin=$pin/" "$CONF"
  else
    printf '\n# PIN (4 to 8 digits) the app must give to connect. Empty = none.\npin=%s\n' "$pin" >> "$CONF"
  fi
  systemctl restart l6desk-bridge 2>/dev/null || true
  if [[ -n "$pin" ]]; then
    say "PIN set. Apps ask for it the next time they connect, and remember it. (Apps older than 0.16 can't connect.)"
  else
    say "PIN removed: any app on the bridge's network can connect."
  fi
}

# A bridge installed before the rename (l6max-bridge) becomes l6desk-bridge: its service is removed,
# and its settings, user and group (renamed, so the music folder's ownership and group members carry
# over), Wi-Fi setting and hotspot are kept under the new names. The music folder stays where it is.
migrate_old_names() {
  local old_unit="$ROOT/etc/systemd/system/$OLD_NAME.service" old_conf="$ROOT/etc/$OLD_NAME.conf"
  if [[ -f "$old_unit" ]]; then
    say "Moving the old $OLD_NAME install to $USER_NAME…"
    systemctl disable --now "$OLD_NAME" 2>/dev/null || true
    rm -f "$old_unit" "$ROOT/etc/avahi/services/$OLD_NAME.service"
    rm -rf "$ROOT/opt/$OLD_NAME"
    systemctl daemon-reload
    systemctl reset-failed "$OLD_NAME" 2>/dev/null || true # else it's listed as "not-found failed"
  fi
  if [[ -f "$old_conf" && ! -f "$CONF" ]]; then
    sed -e 's/^\(\s*name\s*=\s*\)L6max Bridge\s*$/\1L6Desk Bridge/' \
        -e "s/$OLD_NAME/$USER_NAME/g" -e 's/L6max Remote/L6Desk/g' "$old_conf" > "$CONF"
    rm -f "$old_conf"
    echo "Moved $old_conf to $CONF"
  fi
  if id -u "$OLD_NAME" >/dev/null 2>&1 && ! id -u "$USER_NAME" >/dev/null 2>&1; then
    usermod -l "$USER_NAME" "$OLD_NAME"
    groupmod -n "$USER_NAME" "$OLD_NAME" 2>/dev/null || true
    echo "Renamed the $OLD_NAME user and group to $USER_NAME"
  fi
  local old_nm="$ROOT/etc/NetworkManager/conf.d/90-l6max-wifi-powersave.conf"
  if [[ -f "$old_nm" ]]; then
    mv "$old_nm" "$ROOT/etc/NetworkManager/conf.d/90-l6desk-wifi-powersave.conf"
  fi
  if command -v nmcli >/dev/null && nmcli -t -f NAME connection show 2>/dev/null | grep -qx "$OLD_HOTSPOT"; then
    nmcli connection modify "$OLD_HOTSPOT" connection.id "$HOTSPOT" && echo "Renamed the hotspot connection to $HOTSPOT"
  fi
}

cmd_hotspot() {
  need_root hotspot
  local ssid="${1:-}" pass="${2:-}" always="${3:-}"
  [[ -n "$ssid" && ${#pass} -ge 8 ]] || { echo "Usage: sudo $0 hotspot SSID PASSWORD [--always]   (password: 8+ characters)" >&2; exit 1; }
  systemctl is-active --quiet NetworkManager || { echo "NetworkManager isn't running (needed for the hotspot)." >&2; exit 1; }
  apt-get install -y --no-install-recommends dnsmasq-base >/dev/null
  if command -v iw >/dev/null && iw reg get 2>/dev/null | grep -q 'country 00'; then
    echo "WARNING: no Wi-Fi country is set; the hotspot may not start. Set it: sudo raspi-config → Localisation → WLAN Country" >&2
  fi
  nmcli connection delete "$HOTSPOT" >/dev/null 2>&1 || true
  nmcli connection delete "$OLD_HOTSPOT" >/dev/null 2>&1 || true
  # Lower priority than the home Wi-Fi: at home the Pi joins it (SSH keeps working);
  # anywhere else it starts the hotspot. --always: hotspot first, every time.
  local priority=-10
  [[ "$always" == "--always" ]] && priority=100
  nmcli connection add type wifi ifname wlan0 con-name "$HOTSPOT" autoconnect yes ssid "$ssid" \
    connection.autoconnect-priority "$priority" \
    802-11-wireless.mode ap 802-11-wireless.band bg \
    ipv4.method shared ipv4.addresses "$HOTSPOT_IP/24" ipv6.method disabled \
    wifi-sec.key-mgmt wpa-psk wifi-sec.psk "$pass" >/dev/null
  say "Hotspot \"$ssid\" configured. On it, the bridge is at $HOTSPOT_IP:$(conf_port)."
  if [[ "$always" == "--always" ]]; then
    echo "Starting the hotspot now: Wi-Fi SSH sessions will drop. Reconnect via the \"$ssid\" network to $HOTSPOT_IP."
    nmcli connection up "$HOTSPOT" >/dev/null &
  else
    echo "It starts by itself when no known Wi-Fi network is in range (e.g. at a venue). At home the Pi"
    echo "stays on your network. To try it now: sudo nmcli connection up $HOTSPOT  (ends Wi-Fi SSH)."
  fi
}

cmd_hotspot_off() {
  need_root hotspot-off
  nmcli connection delete "$OLD_HOTSPOT" >/dev/null 2>&1 || true
  nmcli connection delete "$HOTSPOT" && say "Hotspot removed."
}

cmd_status() {
  say "Service";  systemctl --no-pager --lines=0 status l6desk-bridge 2>&1 | head -4 || true
  say "Network";  nmcli -t -f NAME,DEVICE,TYPE connection show --active 2>/dev/null || ip -4 addr show wlan0
  hostname -I | sed 's/^/Addresses: /'
  say "MIDI";     amidi -l 2>/dev/null || echo "amidi not installed"
  say "L6max card (PAD SOUNDS)"; findmnt -n -o SOURCE,TARGET /run/l6desk-card 2>/dev/null || echo "Not mounted (the L6max isn't in file transfer mode)"
  say "PIN";      if [[ -n "$(conf_pin)" ]]; then echo "Set (apps must give it)"; else echo "None (any app on the network can connect)"; fi
  say "Recent log"; journalctl -u l6desk-bridge -n 15 --no-pager -o short-iso 2>/dev/null || true
}

cmd_uninstall() {
  need_root uninstall
  systemctl disable --now l6desk-bridge 2>/dev/null || true
  systemctl disable --now l6desk-poweroff.path l6desk-card-eject.path 2>/dev/null || true
  systemctl stop 'l6desk-card@*.service' 2>/dev/null || true
  for u in $POWEROFF_UNITS $CARD_UNITS; do rm -f "$(dirname "$UNIT")/$u"; done
  rm -f "$CARD_RULE"
  udevadm control --reload-rules 2>/dev/null || true
  rm -f "$ROOT/etc/avahi/services/l6desk-bridge.service"
  rm -f "$UNIT"
  systemctl daemon-reload
  rm -rf "$APP_DIR"
  userdel "$USER_NAME" 2>/dev/null || true
  say "Removed the bridge. Kept $CONF, Java and any hotspot."
}

conf_port() { grep -E '^\s*port\s*=' "$CONF" 2>/dev/null | tail -1 | cut -d= -f2 | tr -d ' ' || echo 5704; }

# Commands are cmd_* functions so they can't shadow system commands (install, status…).
case "${1:-}" in
  install) cmd_install ;;
  hotspot) shift; cmd_hotspot "$@" ;;
  hotspot-off) cmd_hotspot_off ;;
  pin) shift; cmd_pin "$@" ;;
  status) cmd_status ;;
  uninstall) cmd_uninstall ;;
  *) sed -n '2,17p' "$0"; exit 2 ;;
esac
