#!/bin/sh
# Announces the bridge on the network while it runs (mDNS service _l6desk-bridge._tcp, through
# Avahi), so the app lists it without an IP address being typed in. The l6desk-bridge service runs
# "announce.sh on" before starting and "announce.sh off" after stopping. Port and name come from
# /etc/l6desk-bridge.conf.
ROOT="${L6DESK_ROOT:-}"
CONF="$ROOT/etc/l6desk-bridge.conf"
OUT="$ROOT/etc/avahi/services/l6desk-bridge.service"

if [ "${1:-}" = off ]; then rm -f "$OUT"; exit 0; fi
[ -d "$(dirname "$OUT")" ] || exit 0 # no Avahi: nothing to announce

get() { sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" "$CONF" 2>/dev/null | tail -1 | sed 's/[[:space:]]*$//'; }
port=$(get port)
case "$port" in '' | *[!0-9]*) port=5704 ;; esac
name=$(get name)
[ -n "$name" ] || name="L6Desk Bridge"
xml() { printf '%s' "$1" | sed 's/&/\&amp;/g; s/</\&lt;/g; s/>/\&gt;/g'; }

cat > "$OUT.tmp" <<XML
<?xml version="1.0" standalone='no'?>
<!DOCTYPE service-group SYSTEM "avahi-service.dtd">
<!-- Written by the l6desk-bridge service while it runs (announce.sh); edits are overwritten. -->
<service-group>
  <name>$(xml "$name")</name>
  <service>
    <type>_l6desk-bridge._tcp</type>
    <port>$port</port>
    <txt-record>protocol=1</txt-record>
  </service>
</service-group>
XML
mv "$OUT.tmp" "$OUT"
