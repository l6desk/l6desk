# Installing L6Desk {{VERSION}}

Download the files from [github.com/l6desk/l6desk/releases](https://github.com/l6desk/l6desk/releases).
There are three parts. Install the ones you need:

- **The desktop app** (Ubuntu): mix from a computer connected to the L6max by USB.
- **The Android app** (tablet): mix over Wi-Fi through the Pi bridge, or with a USB-C cable
  straight into the L6max.
- **The Pi bridge** (Raspberry Pi Zero 2 W): plugs into the L6max and gives the tablet a Wi-Fi
  link to it. It's also where the music player keeps and plays its songs.

## Desktop app (Ubuntu 24.04, 64-bit PC)

```sh
sudo apt install ./l6desk_{{VERSION}}-1_amd64.deb
```

Run that in the folder holding the `.deb` (the `./` matters). The app appears in the
applications menu as **L6Desk**. It brings its own Java, so nothing else is needed.

- Connect the L6max with a USB cable and start the app: it finds the mixer's control port by
  itself and connects. Unplugging and replugging is fine; it reconnects.
- **Updating:** install the newer `.deb` the same way. Your desk, profiles and settings are kept
  (in `~/.config/l6desk/`).
- **PAD SOUNDS and RECORDINGS** (the L6max's card, in file transfer mode): the package adds a small
  system rule so Ubuntu recognises the card (the L6max formats it slightly unusually, and Ubuntu
  wouldn't mount it otherwise). Recordings are downloaded to `~/Music/L6Desk/Recordings`.
- **Music player without the Pi:** with the L6max plugged into the computer, PLAYER plays songs
  from `~/Music/L6Desk`. For MP3, AAC and FLAC files as well as WAV: `sudo apt install ffmpeg`.
- **Removing:** `sudo apt remove l6desk`.
- **Updating from L6max Remote** (0.12 or earlier): install the `.deb` as above. It replaces
  L6max Remote, and your desk, profiles and settings move across to `~/.config/l6desk/` the
  first time L6Desk starts.

**Windows (preview, 0.16 on):** `L6Desk-<version>.msi` installs it; `L6Desk-<version>-windows-portable.zip`
runs without installing (unzip, then run `L6Desk\L6Desk.exe`). Both bring their own Java.

- Windows SmartScreen will warn that the app is unrecognised (it isn't code-signed): choose
  **More info → Run anyway**.
- Install **Zoom's L6max driver** for Windows (zoomcorp.com, L6max downloads) before connecting the
  mixer by USB, and use a data cable straight into the computer: on a Surface Go 2 a USB-C adapter
  didn't work, while a USB-C to USB-C cable did.
- The **music player** works on Windows **through the Pi bridge** (the Pi plays the songs). Playing
  from the Windows computer itself, with the L6max on its USB, isn't available yet: Zoom's Windows
  driver only accepts 4-channel audio, which Java can't send there. So PLAYER only appears when
  connected through the Pi.
- Tested on Windows 11 with the L6max on USB and with the Pi bridge. Less tested than Ubuntu: please
  report problems (the app's REPORT button).

## Android app (tablet, Android 7.0 or newer)

The app isn't in the Play Store; you install the APK file directly ("sideloading").

1. Copy `l6desk-{{VERSION}}.apk` to the tablet (USB cable, cloud storage, or email to
   yourself).
   - **Samsung tablets:** **Auto Blocker** (Settings → Security and privacy → Auto Blocker)
     blocks apps from outside the Galaxy Store and Play Store. Switch it off to install, and to
     update later (the app keeps working if you switch it back on).
2. Open it in the tablet's **My Files** (or your file manager). Android asks to allow
   **Install unknown apps** for that app: allow it, go back, and tap **Install**.
3. Open **L6Desk**.

Or, with the tablet connected to a computer with USB debugging on:
`adb install -r l6desk-{{VERSION}}.apk`.

- **Updating:** install the newer APK over the old one; the desk and settings are kept. Every
  version must be signed with the same key. If Android says the app "conflicts with an existing
  package", the key changed: uninstall the old one first (which loses its saved desk and settings).
- **L6max Remote** (0.12 or earlier) is a separate app to Android. Uninstall it before or after
  installing L6Desk; its saved desk and settings don't carry over.
- An APK named `…-unsigned.apk` can't be installed: Android only installs signed apps. It needs
  building again with the signing key (`scripts/make-release-key.sh` once, then
  `scripts/release.sh`).
- **Two ways to reach the mixer:**
  - **Wi-Fi through the Pi bridge** (below): the tablet can be anywhere in range.
  - **A USB-C cable straight into the L6max** (0.17 or newer): no Pi, no Wi-Fi. The tablet finds
    the mixer by itself (no permission to give). The music player then plays from the tablet: add
    songs with UPLOAD.
  - Bluetooth MIDI isn't available yet.
- Tested on a Samsung Galaxy Tab A11 (Android 16) with the real L6max, both ways.
- The app runs full screen. Swipe in from the edge of the screen to show Android's bars.

## The Pi bridge (Raspberry Pi Zero 2 W)

### What you need

- A **Raspberry Pi Zero 2 W** and a microSD card (8 GB or more; songs for the music player take
  about 10 MB a minute).
- A good **5 V 2.5 A micro-USB power supply**.
- A **micro-USB to USB-C data cable** for the L6max (an ordinary data cable works; a
  charge-only cable doesn't).
- The L6max on **its own power** (adapter, batteries, or a power bank in its second USB port):
  the Pi can't power it.

The Pi has two micro-USB sockets. **PWR** (at the end of the board) is for the power supply.
**USB** (nearer the middle) is for the L6max. Only USB carries data: swapped round, the Pi never
sees the mixer.

### 1. Prepare the SD card

Use **Raspberry Pi Imager** (raspberrypi.com/software) on a computer:

1. Device: *Raspberry Pi Zero 2 W*. OS: *Raspberry Pi OS (other) → Raspberry Pi OS Lite (64-bit)*.
2. In the settings (*Edit settings* / the customisation step):
   - hostname **l6desk-bridge**
   - a user name and password
   - your **home Wi-Fi** name and password, and your country
   - **Enable SSH** (password authentication)
3. Write the card, put it in the Pi and power it on. The first start takes a few minutes.
4. Check you can log in from the computer: `ssh <user>@l6desk-bridge.local`.

### 2. Install the bridge

From the folder holding the bridge pack, on the computer:

```sh
scp l6desk-bridge-{{VERSION}}-pi.tar.gz <user>@l6desk-bridge.local:
ssh -t <user>@l6desk-bridge.local 'tar xzf l6desk-bridge-{{VERSION}}-pi.tar.gz && sudo ./l6desk-bridge/pi-setup.sh install'
```

This installs Java, ffmpeg (for the music player) and Avahi (so the apps can find the bridge). It
turns off Wi-Fi power saving, which otherwise makes the faders feel laggy. It then installs the
bridge as a service that starts at power-on. If it says a reboot is needed, run `sudo reboot`.

Plug the L6max into the Pi's **USB** socket. The Pi's green light shows the state:

| Green light | Meaning |
|---|---|
| Short blink every second | Waiting for the L6max on USB |
| Fast blinking | L6max connected, waiting for a tablet |
| Steady on | A tablet (or computer) is in control |
| Rapid flicker | A problem: check the log (below) |

### 3. Its own Wi-Fi network (for gigs)

So the tablet doesn't need the venue's Wi-Fi, give the Pi its own network. Choose a name and a
password of 8 or more characters:

```sh
ssh -t <user>@l6desk-bridge.local 'sudo ./l6desk-bridge/pi-setup.sh hotspot L6Desk-Bridge "your-password"'
```

At home the Pi stays on your home Wi-Fi, so you can still log in and update it. **Anywhere your
home Wi-Fi isn't in range, it starts its own network at power-on.** At the gig, join the tablet to
that network (`L6Desk-Bridge` above); the bridge is at **10.42.0.1** on it.

Try it once away from home before relying on it at a gig.

### 4. Connect the tablet

Join the tablet to the Pi's network (its own one, or your home Wi-Fi), then open the app. The
bridge is **found automatically**: it's listed as *L6Desk Bridge* in the connection panel, and a
tablet that hasn't picked anything else connects to it by itself. If it isn't found (some home
routers block this), tap the connection status at the top left and add the bridge by address:
**10.42.0.1** on the Pi's own network, or its address on your home network.

The desktop app can use the bridge too (useful for the music player, and for trying everything
at home).

### Updating the bridge

Copy the new pack across and run the install again, exactly as in step 2. Your settings, songs
and hotspot are kept.

From 0.17 the install also sets the Pi up to reach the L6max's **memory card** when the mixer is in
file transfer mode, for PAD SOUNDS and RECORDINGS through the Pi (user guide, SETUP). Nothing to do
but install; `pi-setup.sh status` shows whether the card is there.

**Updating a bridge from L6max Remote** (0.12 or earlier, installed as `l6max-bridge`): install
the new pack as in step 2. The install moves the old bridge across: its settings become
`/etc/l6desk-bridge.conf`, the default name *L6max Bridge* becomes *L6Desk Bridge* (a name you
chose is kept), and the songs and hotspot stay as they are. The Pi keeps its hostname, so use the
one you gave it (e.g. `l6max-bridge.local`) in the commands. Old copies of the app still find the
updated bridge by address, but update them too.

### Looking after it

```sh
ssh <user>@l6desk-bridge.local
./l6desk-bridge/pi-setup.sh status          # service, network, MIDI ports and recent log
journalctl -u l6desk-bridge -f              # live log
sudo nano /etc/l6desk-bridge.conf           # name, port, status light, music folder
sudo systemctl restart l6desk-bridge        # after changing the settings
sudo poweroff                              # shut down before pulling the power, when convenient
```

Pulling the power without shutting down is normally fine, but don't do it during an upload.
The app can shut the Pi down for you when you close it (see the user guide, SETUP → OPTIONS). To
stop the app being able to, set `power_off=false` in `/etc/l6desk-bridge.conf` (worth doing if
other people use the Pi's network) and restart the bridge.

- **The app shows an UNDER-VOLTAGE banner:** the Pi's power supply is, or was, too weak, which
  can drop the Wi-Fi link or a song, or restart the Pi. Use a 5 V 2.5 A supply (the official one,
  or a good power bank) and a short, thick cable. The bridge's log lists each dip:
  `journalctl -u l6desk-bridge | grep -i voltage`.
- **A PIN**, so a stray phone on the Pi's network can't take over the desk:
  `sudo ./l6desk-bridge/pi-setup.sh pin 2468` (4 to 8 digits; `pin off` removes it). The app asks
  for it once and remembers it. Apps older than 0.16 can't connect to a bridge with a PIN. The
  install says whether a PIN is set; `pi-setup.sh status` shows it too.
- **Two Pis in one place:** give each a different `name` in `/etc/l6desk-bridge.conf`.
- **Music player audio crackles or drops out after a minute or two:** something has switched the
  Pi to the generic `dwc2` USB driver. Run the install again, which removes it, and reboot.
- **Removing the bridge:** `sudo ./l6desk-bridge/pi-setup.sh uninstall` (and
  `sudo ./l6desk-bridge/pi-setup.sh hotspot-off` for the hotspot).

## Mixer settings

Over USB (from a computer, a tablet or the Pi) nothing needs changing on the L6max: the app uses
its **Mixer Control** port. If you've changed the mixer's MIDI CC assignments in ZOOM L6 Editor,
use **SETUP → READ FROM MIXER** with the L6max on the computer's or the tablet's USB. Through the
Pi, set the numbers in SETUP → MIDI MAP, or use LEARN there (see the user guide).
