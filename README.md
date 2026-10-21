# L6Desk

A remote control for the **Zoom LiveTrak L6max** mixer. Mix from a tablet on a stand, or from a
computer, with the mixer tucked away at the side of the stage. Free and open source (GPL-3.0).

![The L6Desk desk: eight channel strips with EQ, pan, mute, solo and faders, and the master section with INSTANT MUTE and the sound pads](docs/images/desk.png)

**[Download the latest release](https://github.com/l6desk/l6desk/releases/latest)**. Each release
has a README, installation instructions (INSTALL.md) and a [user guide](docs/dist/USER-GUIDE.md)
alongside the files:

| File | What it is |
|---|---|
| `l6desk_<version>-1_amd64.deb` | The desktop app for Ubuntu (64-bit PC) |
| `l6desk-<version>.apk` | The Android app (tablets, Android 7.0 or newer) |
| `l6desk-bridge-<version>-pi.tar.gz` | The Wi-Fi bridge for a Raspberry Pi Zero 2 W |
| `L6Desk-<version>.msi`, `…-windows-portable.zip` | The desktop app for Windows 10/11 (preview) |

L6Desk was called L6max Remote up to version 0.12.

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

| | |
|---|---|
| ![The SENDS view](docs/images/sends.png) | ![The music player](docs/images/player.png) |
| ![SETUP](docs/images/setup.png) | ![RECORDINGS from the mixer's card](docs/images/recordings.png) |

## Status

- **Tested with a real L6max** on Ubuntu (USB, and Wi-Fi through the Pi bridge) and on a Samsung
  Galaxy Tab A11 (Android 16: through the Pi, and with a USB-C cable straight into the mixer).
- **Windows (preview, from 0.16):** mixing over USB and the Pi bridge work (tested on Windows 11);
  the music player works through the Pi, not yet with the L6max on the computer's USB. See the
  release notes for the driver and cable.
- **Bluetooth MIDI** (a BLE-MIDI adapter on the L6max's MIDI jacks) isn't available yet: it hasn't
  been built or tested, for want of an adapter. If you have one and would like to help, please get
  in touch through Issues.

## Questions and problems

Please use [Issues](https://github.com/l6desk/l6desk/issues). The app's **REPORT** button makes a
problem report for you: a zip file with a screenshot and the details needed, and a link that opens
a new issue already filled in. Drag the zip onto the issue.

Reports are only opened after a check: they must hold exactly the files the app makes, and be
signed by the copy of the app that made them. Please attach only zips made by the app.

## Building it

Everything is developed and tested on **Ubuntu 24.04**; Android Studio isn't needed. You need
JDK 17 and the Android SDK command-line tools. `scripts/setup-ubuntu.sh` installs them (and the
emulator, `adb` and `scrcpy`); [docs/dev-setup.md](docs/dev-setup.md) explains each step.

```sh
scripts/setup-ubuntu.sh --check        # what's missing (changes nothing)
scripts/setup-ubuntu.sh                # install it

./gradlew check                        # all the tests (well under a minute) and Android lint
./gradlew :desktopApp:run              # the desktop app (a simulated L6max if none is plugged in)
./gradlew :androidApp:installDebug     # "L6Desk debug" on the emulator or a connected tablet
scripts/bridge-sim.sh                  # a Wi-Fi bridge with the simulator behind it
scripts/release.sh                     # .deb, APK and Pi pack in dist/<version>/
```

No L6max is needed: the app runs against a built-in simulator. A release APK you build yourself is
signed with your own key (`scripts/make-release-key.sh`), so Android won't install it over the
official app, or the official app over it; the debug build installs alongside either. Releases are
described in [docs/release.md](docs/release.md), the Pi bridge in
[docs/bridge-setup.md](docs/bridge-setup.md), and what's been found out about the L6max's MIDI in
[docs/hardware-notes.md](docs/hardware-notes.md).

| Module | Contents |
|---|---|
| `core/` | Everything but the UI: model, MIDI, transports, bridge protocol, sync, music player, persistence |
| `shared/` | Compose UI shared by Android and the desktop |
| `androidApp/` | Android entry point, USB MIDI and audio |
| `desktopApp/` | Desktop entry point, Bridge mode, packaging (DEB, MSI) |
| `bridge/` | Headless Wi-Fi bridge for a Raspberry Pi Zero 2 W |

Built with Kotlin Multiplatform and Compose Multiplatform. [CLAUDE.md](CLAUDE.md) is the project
brief: the decisions, the architecture and the confirmed hardware facts. Contributions are welcome:
see [CONTRIBUTING.md](CONTRIBUTING.md).

## Licence and donations

L6Desk is free software under the **GNU General Public License v3** ([LICENSE](LICENSE)): you may
use, share and change it, and anything you distribute that's built from it must be shared under
the same licence.

It's free. If you find it useful, the authors would only ask that you make a donation to a charity
of your own choice. There's no obligation.

## Credits

- Originally designed and developed by **Trevor Toms**.
- Written with **Claude**, Anthropic's AI model, using Claude Code.
- The L6max Editor-port protocol, used to read the mixer's MIDI map, was learned from
  [zoom-l6-companion](https://github.com/philmillman/zoom-l6-companion) (MIT licence).

## Trademarks

Zoom, LiveTrak and L6max are trademarks of Zoom Corporation. L6Desk is an independent
project. It isn't made, endorsed or supported by Zoom Corporation; the names are used only to say
which mixer it works with. Raspberry Pi is a trademark of Raspberry Pi Ltd. Android is a
trademark of Google LLC.
