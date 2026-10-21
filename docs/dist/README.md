# L6Desk {{VERSION}}

A remote control for the **Zoom LiveTrak L6max** mixer. Mix from a tablet on a stand, or from a
computer, with the mixer tucked away at the side of the stage.

L6Desk was called **L6max Remote** up to version 0.12. Updating from it is covered in INSTALL.md.

## What's in this folder

| File | What it is |
|---|---|
| `README.md` | This file |
| `INSTALL.md` | How to install the app on Ubuntu and Android, and how to set up the Raspberry Pi bridge |
| `USER-GUIDE.md` | How to use the app: the desk, sends and scenes, sound pads, the music player, the mixer's card, setup |
| `l6desk_{{VERSION}}-1_amd64.deb` | The desktop app for Ubuntu (64-bit PC) |
| `l6desk-{{VERSION}}.apk` | The Android app (tablets, Android 7.0 or newer) |
| `l6desk-bridge-{{VERSION}}-pi.tar.gz` | The Wi-Fi bridge for a Raspberry Pi Zero 2 W |
| `SHA256SUMS` | Checksums, to check the downloads are intact (`sha256sum -c SHA256SUMS`) |

An APK named `…-unsigned.apk` was built without the signing key and **won't install** on a
tablet; see INSTALL.md.

## Status of this release

- **Tested with a real L6max** on Ubuntu (USB, and Wi-Fi through the Pi bridge) and on a Samsung
  Galaxy Tab A11 (Android 16: through the Pi, and with a USB-C cable straight into the mixer).
- Bluetooth MIDI isn't available yet.

## What it does

- **All 8 channels:** level, pan, three-band EQ with mid frequency, mute, and solo (done by the
  app, because the L6max has none). MONO ×2 on CH5/CH6 and the USB switches on CH7/CH8.
- **Master section:** a master fader that scales every channel, **INSTANT MUTE** for feedback
  emergencies, and the four **sound pads**.
- **Landscape or portrait:** on a tablet held upright, the strips come in two banks of four
  (CH 1–4 / CH 5–8) with longer faders, and a lamp for each channel's mute and solo in the other bank.
- **SENDS view:** AUX 1, AUX 2, SUB MIX and EFX sends on the faders, the effect type,
  the compressor, and four **scenes** you can store and recall.
- **Music player:** backing tracks, walk-on and interval music played into CH7 or CH8, in
  playlists you upload from the app: from the Pi bridge, or from the computer or tablet the L6max
  is plugged into.
- **The mixer's memory card:** set the sound pads' sounds, and download the L6max's recordings
  (the master, the tracks with sound, or everything; 24-bit or 32-bit float; normalised if you
  like), from a computer or through the Pi.
- **Two ways to reach the mixer:** a USB cable (from a computer, or a tablet with USB-C), or Wi-Fi
  through a small Raspberry Pi plugged into the mixer. The Pi can run its own Wi-Fi network, so no
  venue Wi-Fi is needed.

## Quick start

1. Install the app (INSTALL.md).
2. **Computer:** plug the L6max in by USB and start *L6Desk*. It connects by itself.
   **Tablet:** plug the L6max in with a USB-C cable, or set up the Pi bridge (INSTALL.md) and join
   its Wi-Fi network. Start the app: it finds the mixer or the bridge by itself.
3. The mixer can't tell the app its current settings, so the app starts with the desk as you
   last left it. Press **SEND DESK** (lit amber) to make the mixer match the app, or
   **SETUP → RESET MIX** to start from a clean slate.

Then read USER-GUIDE.md, at least the parts on SEND DESK and INSTANT MUTE, before using it at a gig.

## Open source, licence and donations

L6Desk is **free software** under the **GNU General Public License v3 (GPL-3.0)**. The source code
is at [github.com/l6desk/l6desk](https://github.com/l6desk/l6desk), with instructions for building
it yourself.

It's free. If you find it useful, the authors would only ask that you make a donation to a
charity of your own choice. There's no obligation.

## Credits

- Originally designed and developed by **Trevor Toms**.
- Written with **Claude**, Anthropic's AI model, using Claude Code.
- The L6max Editor-port protocol was learned from
  [zoom-l6-companion](https://github.com/philmillman/zoom-l6-companion) (MIT licence).

## Questions and problems

New versions: [github.com/l6desk/l6desk/releases](https://github.com/l6desk/l6desk/releases).
Report problems and ask questions in
[Issues](https://github.com/l6desk/l6desk/issues) there. The app's **REPORT** button (top bar, or
SETUP → REPORT A PROBLEM) makes a report for you: a zip file to attach, and a link that opens a
new issue already filled in (user guide, section 9).

## Trademarks

Zoom, LiveTrak and L6max are trademarks of Zoom Corporation. L6Desk is an independent
project. It isn't made, endorsed or supported by Zoom Corporation; the names are used only to say
which mixer it works with. Raspberry Pi is a trademark of Raspberry Pi Ltd. Android is a
trademark of Google LLC.
