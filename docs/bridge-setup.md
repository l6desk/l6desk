# Wi-Fi bridge setup

The L6max has no network port, so the tablet reaches it through a **bridge**: a computer
connected to the L6max by USB, which passes MIDI to and from the tablet over Wi-Fi
(route C in CLAUDE.md).

```
Tablet ──Wi-Fi (TCP 5704)──► Bridge ──USB──► L6max Mixer Control Port
```

## Desktop app in Bridge mode

1. Connect the L6max to the computer by USB and start the desktop app
   (`scripts/dev-desktop.sh`). It connects to the Mixer Control port by itself.
2. **SETUP → Options → Bridge mode: ON.** Setup and the top bar show the address(es) to use,
   e.g. `192.168.1.107:5704`, and "Waiting for a tablet". Bridge mode stays on after a restart.
3. On the tablet: tap the connection status in the top bar, type the computer's address in the
   field (just the IP if the port is 5704), and tap **ADD BRIDGE**. Then tap the new
   **Bridge …** row to connect. The bridge is remembered.
4. The tablet's status reads e.g. `my-desktop (bridge 192.168.1.107) → L6max Mixer Control
   Port (hw:4,0,1)`, and the desktop shows `BRIDGE: Galaxy Tab A11 (192.168.1.50)`.
   Press **SEND DESK** on the tablet (or turn on "send the desk as soon as it connects").

Notes:
- **One controlling tablet at a time.** A second one is told the bridge is busy, and gets in
  automatically when the first disconnects.
- **Link lost:** if Wi-Fi drops, the tablet shows "Link to bridge … lost — reconnecting…"
  within 5 seconds, and reconnects by itself when the bridge is reachable again. Afterwards,
  SEND DESK lights up again because the app can't know whether anything changed meanwhile.
- If the L6max is unplugged from the bridge, the tablet stays connected to the bridge and shows
  "no mixer connected". It recovers when the L6max is plugged back in.
- **The desktop desk doesn't follow changes made on the tablet.** Moves made on the L6max itself
  do reach both. Mix from one place at a time.
- **Firewall:** Ubuntu's firewall is off by default. If `ufw` is enabled:
  `sudo ufw allow 5704/tcp`.
- The tablet and computer must be on the same network. In a venue, a private Wi-Fi network
  (e.g. a travel router, or a hotspot on the bridge machine) avoids relying on the venue's
  network, or use the headless Raspberry Pi bridge below, which runs its own.
- Bridge mode announces itself on the network (mDNS, like the Pi), so the tablet lists the PC by
  its name without an address being typed in. SETUP shows "Announced on the network as …". If
  it can't be (another program won't share the mDNS port), it says so; add the bridge by address
  instead.

## Headless bridge on a Raspberry Pi Zero 2 W

A small board that sits by the mixer, starts the bridge at power-on and runs its own Wi-Fi
network, so no laptop and no venue Wi-Fi are needed. It's the same bridge code as the desktop's
Bridge mode, without the screen (`bridge/` module, a single 4.5 MB jar, about 65 MB of memory).

### What you need
- Raspberry Pi Zero 2 W and a microSD card (8 GB is plenty).
- A good 5 V 2.5 A micro-USB power supply, into the Pi's **PWR** port (at the end of the board).
- A micro-USB data cable or OTG adapter to USB-C, from the Pi's **USB** port (nearer the middle)
  to the L6max. Both ports can power the Pi, but only **USB** carries data: with the supply in
  USB and the L6max in PWR, the Pi never sees the mixer. An ordinary data cable works: the Pi's
  own USB driver is in host mode by default. Don't switch it to the generic `dwc2` driver
  (`dtoverlay=dwc2`): MIDI still works, but the music player's audio breaks up into crackles.
- **Power the L6max separately** (its own adapter or batteries, or a power bank in its other USB
  port). The Pi's data port can't supply enough current for the mixer, especially with phantom
  power on.

### 1. Prepare the SD card (Raspberry Pi Imager)
- OS: **Raspberry Pi OS Lite (64-bit)**.
- Edit settings: hostname `l6desk-bridge`, a user name and password, your **home Wi-Fi** and its
  **country**, and on the Services tab **enable SSH**.
- Boot the Pi on your home network and check that `ssh <user>@l6desk-bridge.local` works (or use
  its IP address from your router).

### 2. Install the bridge (from the Ubuntu machine)
```sh
scripts/deploy-pi.sh <user>@l6desk-bridge.local
```
This builds the bridge, copies it to the Pi, installs Java (`default-jre-headless`: Java 17 on
bookworm, 21 on trixie) and `alsa-utils`, turns off Wi-Fi power saving (it causes uneven latency
and dropped packets), installs `ffmpeg` and Avahi, and installs and starts the
`l6desk-bridge` service
(it runs as an unprivileged user in the `audio` group, starts at boot and restarts if it stops).
Running it again updates the bridge and keeps `/etc/l6desk-bridge.conf`. A bridge installed before 0.13, as `l6max-bridge`, is moved across: the old service is removed, its settings move to `/etc/l6desk-bridge.conf` (the default name becomes L6Desk Bridge), the user and group are renamed (so the music folder's ownership carries over), and the hotspot connection is renamed. The music folder stays `/srv/l6max-music`. It says when a reboot is
needed (the first time, for the USB and Wi-Fi changes). `sudo` on the Pi asks for your password,
so run the script in your own terminal.

If you flashed the desktop version of Raspberry Pi OS rather than Lite, boot it to the console to
free about 100 MB: `sudo systemctl set-default multi-user.target && sudo reboot`.

### 3. Its own Wi-Fi network
```sh
scripts/deploy-pi.sh <user>@l6desk-bridge.local --hotspot L6Desk-Bridge 'a-password'
```
The hotspot (WPA2, 2.4 GHz, Pi at **10.42.0.1**) has lower priority than your home Wi-Fi.
**At home** the Pi stays on your network, so SSH and updates keep working. **Anywhere else**
(no known network in range) it starts the hotspot by itself at power-on. To use the hotspot
always: `sudo /tmp/l6desk-bridge/pi-setup.sh hotspot L6Desk-Bridge 'a-password' --always` on the Pi
(this drops Wi-Fi SSH sessions; reconnect through the hotspot to 10.42.0.1).

### 4. On the tablet
Join the Pi's network (its hotspot away from home, or your home Wi-Fi). The bridge **finds
itself**: it's listed as "L6Desk Bridge (address)" in the connection panel, and a tablet that
hasn't picked anything yet connects to it automatically. It stays the same entry whichever
network the Pi is on, so a tablet set up at home reconnects at the venue.
- The Pi announces itself through Avahi (mDNS `_l6desk-bridge._tcp`, under the `name` in
  `/etc/l6desk-bridge.conf`) while the bridge runs; `announce.sh` writes
  `/etc/avahi/services/l6desk-bridge.service` at each start.
- If it isn't found (some Wi-Fi routers block mDNS between devices), add bridge **10.42.0.1** on the
  hotspot, or the Pi's home IP address, by hand.
- Two Pis in one place need different `name`s.

### 5. The music player
The Pi can play backing tracks, walk-on music and interval music into the L6max over USB:
**USB 1/2 is heard on CH7 and USB 3/4 on CH8**, once that channel's USB switch is on (the
**USB 1/2** / **USB 3/4** buttons in the CH7/CH8 strip headers, or on the mixer). It plays from the
Pi's SD card, so a Wi-Fi hiccup can't interrupt a song; the app only sends commands.

In the app: **PLAYER** in the top bar.
- **Playlists** are the buttons along the top; **+ NEW** makes one. Each is a folder in
  `/srv/l6max-music` on the Pi.
- **UPLOAD** adds files to the end of the playlist shown (desktop: file dialog; Android: the
  document picker). WAV, MP3, AAC (`.m4a`) and FLAC; the Pi converts anything but 48 kHz WAV once,
  when it arrives (ffmpeg). Uploads carry on with the panel closed. MIDI always goes ahead of
  upload data, but upload at soundcheck rather than mid-song.
- **Tap a song to cue it; PLAY plays the cued song** (a stray tap can't start music). Each song's
  end button: **Stop** (and cue the next), **Play next** (gapless, to the end of the playlist) or
  **Loop**. PAUSE/RESUME and STOP; the top bar shows the song and time left while it plays.
- **EDIT** shows ▲ ▼ to reorder songs and DELETE (tap twice). The Pi keeps the order as a number
  at the start of each file name (`The Beatles/03 Yesterday.wav`); the app shows titles without it.
- **OUTPUT** picks CH7 (USB 1/2) or CH8 (USB 3/4); **VOLUME** sets the player's level. Opening the
  panel sets CH7 and CH8 to full level and the player's volume to 10 %, so the slider covers the
  channel's whole range; closing it puts CH7/CH8 back (after the song, if one is still playing).
- If the output channel isn't known to be on USB, PLAY asks first: **SWITCH TO USB & PLAY** sets it.

On the Pi:
- Songs copied in by hand (`scripts/music-pi.sh <user>@l6desk-bridge.local "Playlist" files…`) are
  converted and numbered too, once they've finished copying. Loose files in `/srv/l6max-music` go
  into a playlist called **Songs**. The list updates within a few seconds; no restart.
- Settings (each song's end button, output, volume, playlist shown) are in
  `/srv/l6max-music/.l6max-player.json`. `music=` in `/etc/l6desk-bridge.conf` (empty = no player).
- The folder is shared between your login and the bridge through the `l6desk-bridge` group
  (`pi-setup.sh install` sets that up).
- **Keep the Pi's own USB driver.** Don't add `dtoverlay=dwc2` to `config.txt`: MIDI still works,
  but the player's audio breaks up into crackles after a minute or two. (`pi-setup.sh install`
  removes the line that older versions of it added.)

### 6. The sound pads' sounds (PAD SOUNDS)
The pads play WAV files on the L6max's memory card. Through the Pi, a pad's sound comes from the
Pi's music library, so nothing large crosses Wi-Fi at the gig:
1. Upload the sound in the **PLAYER** panel (any playlist; a short sound works best, since a pad
   plays the whole file).
2. On the L6max, switch on **file transfer mode** (it stops mixing and recording while it's on),
   with its USB still connected to the Pi. The Pi mounts the card by itself at `/run/l6desk-card`.
3. In the app: **SETUP → PAD SOUNDS…**, **CHOOSE…** on a pad, then the playlist and the song. The
   sound a pad had before is copied to `/srv/l6max-music/.pad-backups/<date>/PADn` first.
   **REMOVE** (two taps) clears a pad.
4. **EJECT CARD**, *then* switch off file transfer mode on the L6max. Its pads play the new sounds.

How it works: `pi-setup.sh install` adds a udev rule (`/etc/udev/rules.d/90-l6desk-card.rules`)
that starts `l6desk-card@<partition>.service` when a partition of the L6max in file transfer mode
(USB `1686:08f4`) appears. That mounts it as FAT32 (the card's boot sector has no jump code, so
it isn't recognised by itself), owned by the bridge's user; the unit stops when the card goes.
EJECT CARD makes the bridge create `/run/l6desk-bridge/eject-card`, which the root
`l6desk-card-eject.path` unit turns into a sync and unmount (the bridge itself stays
unprivileged). `pi-setup.sh status` shows whether the card is mounted.

### 7. Downloading recordings (RECORDINGS)
With the L6max in file transfer mode on the Pi's USB (as for PAD SOUNDS), **SETUP → RECORDINGS…**
lists its recordings, newest first. Pick what to download (the newest MASTER take, USED TRACKS, or
ALL), 32-bit float or 24-bit, and whether to normalise; the Pi reads the card, converts on the way
and sends the files over Wi-Fi. The desktop saves them in `~/Music/L6Desk/Recordings/<recording>`,
Android in the device's `Music/L6Desk/Recordings/<recording>` (the mixer's project file isn't kept
there: the Music folder takes only audio). Over the Pi's 2.4 GHz Wi-Fi a master (23 MB a minute)
is quick; whole multitracks are better downloaded over USB on the desktop.

### The status LED (the Pi's green light)
| LED | Meaning |
|---|---|
| Short blink every second | Waiting for the L6max on USB |
| Fast blinking | L6max connected, waiting for a tablet |
| Steady on | A tablet is in control |
| Rapid flicker | Error (e.g. the port is already in use): check the log |

### Looking after it
```sh
ssh <user>@l6desk-bridge.local
/tmp/l6desk-bridge/pi-setup.sh status        # service, network, MIDI ports, recent log
journalctl -u l6desk-bridge -f               # live log (every connect/disconnect)
sudo nano /etc/l6desk-bridge.conf            # port, name, LED, MIDI logging, music folder
sudo systemctl restart l6desk-bridge
```
**Powering off from the app:** with SETUP → OPTIONS → "Closing the app shuts down the Pi bridge"
(on by default), closing the app while it's connected to the Pi asks whether to shut the Pi down
too (SHUT DOWN PI & CLOSE / CLOSE, LEAVE PI ON / CANCEL). To start it again, replug its power.
The bridge service runs unprivileged and can't power off itself: it creates
`/run/l6desk-bridge/power-off` (its own runtime folder), and `l6desk-poweroff.path` (a root
systemd path unit, installed and enabled by `pi-setup.sh install`) sees it and runs
`systemctl poweroff`. Anyone who can connect the app can do this; `power_off=false` in
`/etc/l6desk-bridge.conf` turns it off (the app then never asks). Run by hand rather than as the
service, the bridge never offers it.

Pulling the power is fine for the bridge: it only writes when you change player settings or
upload, move or delete songs (and tidies up an interrupted upload at the next start). The SD card
is safest with `sudo poweroff` first when convenient, and certainly not mid-upload.

Running the bridge on Ubuntu (e.g. to test with the L6max on USB):
`./gradlew :bridge:run --args="--no-led"`, or with the simulator: `--args="--no-led --simulator"`.

## Testing without the L6max

`scripts/bridge-sim.sh [PORT]` runs a bridge with the simulator behind it, and prints every
message the tablet sends. The Android emulator reaches this computer at **10.0.2.2**, so add
bridge `10.0.2.2` in the emulator.

**Emulator without network:** if the app says it can't reach the bridge and
`adb shell ip -4 addr` shows only `127.0.0.1`, the emulator's virtual Wi-Fi didn't join its
network (this happens with headless `-no-window` boots). Fix:

```sh
adb shell svc wifi enable
adb shell cmd wifi connect-network AndroidWifi open
```

## Protocol (version 8)

TCP, default port 5704. Each frame is `[type: 1 byte][length: 2 bytes, big-endian][payload]`:
HELLO (client version and name) → WELCOME (the version both speak, bridge name, mixer status) or
BUSY; then MIDI (raw bytes), STATUS (mixer connected or not) and PING (every 2 s; 5 s of silence =
link lost). Frame types a side doesn't know are skipped.
- **Version 2** adds the music player: TRACKS and PLAYBACK (bridge → app) and COMMAND (app →
  bridge), JSON (`player/Player.kt`, `PlayerJson`).
- **Version 3** adds playlists (in that JSON) and uploads: UPLOAD_START (JSON), UPLOAD_DATA
  (`[id: 4 bytes][bytes]`, empty = end), UPLOAD_STATE (JSON, bridge → app: bytes received, then
  converting / done / failed) and UPLOAD_CANCEL. The app keeps at most 96 KB unacknowledged and
  sends MIDI ahead of upload data.
- **Version 4** adds powering off: a bridge allowed to (the Pi service with `power_off=true`;
  never the desktop's Bridge mode) sends CAN_POWER_OFF after WELCOME; the app may send POWER_OFF,
  which the bridge echoes back before shutting down.
- **Version 5** adds BRIDGE_INFO (JSON, bridge → app; `net/BridgeInfo.kt`): the bridge's kind (Pi
  or desktop Bridge mode), its L6Desk version, OS, Java, board, uptime, and on a Pi the
  under-voltage alarm (now, how many times since the bridge started, and when last), the
  temperature (now and highest) and the free space for songs. It's sent after WELCOME, again
  whenever a warning appears or clears, and in answer to BRIDGE_INFO_REQUEST (the app asks when
  making a problem report). The Pi watches its sensors every 2 s (`BridgeHealth`), because the
  under-voltage alarm only says "now"; it reads world-readable files in `/sys` and `/proc`, so
  the service needs no extra rights. Dips also go in the bridge's log
  (`journalctl -u l6desk-bridge | grep -i voltage`).
- **Version 6** adds a PIN. A bridge with one (`pin=` in the Pi's config, set with
  `sudo ./l6desk-bridge/pi-setup.sh pin 1234`, or SETUP on a desktop in Bridge mode) answers HELLO with PIN_REQUIRED (16 random bytes); the app answers PIN_ANSWER, the
  HMAC-SHA256 of those bytes keyed with the PIN, so the PIN never crosses the network. Right:
  WELCOME; wrong: BUSY "Wrong PIN" (the app asks again). Five wrong answers from one address lock
  it out for a minute. A bridge with a PIN asks apps older than v6 to update; one without works
  with every app as before. The app remembers each bridge's PIN (`bridgePins` in its settings).
- **Version 7** adds PAD SOUNDS through the Pi (`padcard/PadCardState.kt`, `PadCardJson`): a
  bridge set up to mount the L6max's card sends PAD_CARD (JSON; `null` while there's no card)
  after WELCOME and whenever it changes; the app sends PAD_COMMAND (set a pad from a library song,
  clear a pad, eject), each answered by PAD_RESULT (a sentence for the user, and whether it
  failed). The desktop's Bridge mode doesn't offer it (PAD SOUNDS there uses its own USB).
- **Version 8** adds RECORDINGS through the Pi (`recordings/Recordings.kt`, `RecordingsJson`): with
  the card mounted, the bridge sends RECORDINGS (JSON list, newest first, with each track's
  measured peak; `null` while there's no card) after WELCOME and on change. The app's REC_COMMAND
  `start` (recording, what, format, normalise) is answered by REC_STATE: PREPARING (normalising
  reads the files first, with progress), SENDING (the files that follow, with their sizes), then
  the bytes in REC_DATA frames (`[id: 4 bytes][bytes]`, files back to back) and DONE (any clipped
  samples) or FAILED. The Pi converts on the way (24-bit, normalised) and stores nothing. Data goes
  behind MIDI with at most 128 KB unacknowledged (the app acks every 32 KB); the app gives up after
  30 s of silence, and a cancelled download is abandoned at both ends.

A bridge accepts apps from version 1 up and answers in the lower version, so the 0.11.0 app still
mixes through a newer bridge (without the player). An older bridge refuses a newer app, naming
the version it speaks ("this bridge speaks v3"); the app then connects again in that version, so
it works with a bridge that hasn't been updated, without the newer features. Code: `core/src/commonMain/.../net/`, `BridgeServer` in
`core/src/jvmMain`. Tests: `BridgeProtocolTest`, `BridgeLoopbackTest`, `BridgeHealthTest`, `UploadLoopbackTest`, `PadCardLoopbackTest` and `RecordingsLoopbackTest`
(real TCP on localhost).
