# L6Desk — project brief for Claude Code

A cross-platform control-surface app for the **Zoom LiveTrak L6max** digital mixer. It sends and receives MIDI Control Change (CC) messages so a user can mix from a tablet or PC away from the desk (village halls, small theatres).

**Primary use: live gigs.** A tablet on a stand by the performer, with the L6max tucked away behind (reached through the Pi Zero 2 W bridge or Bluetooth). Studio use on the desktop is a bonus. When trade-offs arise, favour reliability on stage: robust Wi-Fi links, fast recovery, instant mute, glanceable state, nothing that can go wrong mid-set. The user may make the bridge fully portable (battery and case for the Pi).

This is a personal side project. Keep it simple, robust and dependency-light.

## Platforms and roles
| Platform | Role |
|---|---|
| **Ubuntu 24.04** desktop | **Primary development and test platform.** Everything must build, run and be testable here first. |
| **Android tablet** (minSdk 24 / Android 7.0) | **Sign-off and live use.** Target device: **Samsung Galaxy Tab A11** (8.7", landscape; ✅ arrived 2026-10-17, SM-X130, Android 16). Don't depend on Google Play services. |
| **Windows 10/11** desktop | Secondary target. Should build and package, but it is not the dev loop. |

## Stack (decided)
- **Kotlin Multiplatform** + **Compose Multiplatform**. `shared` targets `androidLibrary {}` and `jvm()`; source sets are `commonMain`, `androidMain`, `jvmMain` (desktop).
- AGP 9 doesn't allow the KMP plugin and `com.android.application` in one module. So `shared` uses `com.android.kotlin.multiplatform.library`, and the Android entry point lives in `androidApp` (`com.android.application`). `desktopApp` is a plain Kotlin/JVM module. This is JetBrains' current default layout.
- Versions follow JetBrains' official `Kotlin/KMP-App-Template`: Kotlin 2.4.10, Compose Multiplatform 1.11.1, AGP 9.1.1, Gradle 9.6.1, compileSdk/targetSdk 37, minSdk 24, JDK 17. Upgrade them together, not piecemeal.
- Application ID / package: `io.github.l6desk`.
- **Name: L6Desk** (called L6max Remote up to 0.12; renamed to keep clear of Zoom's trademarks). Call the mixer the L6max, but never name the app or its parts after it.
  - Old names are kept on purpose so existing installs keep working: the Pi's music folder `/srv/l6max-music` and `.l6max-player.json`.
  - Migrations: `pi-setup.sh install` moves an `l6max-bridge` install across; the apps also look for `_l6max-bridge._tcp`; the desktop app moves `~/.config/l6max-remote`; the `.deb` conflicts with and replaces `l6max-remote`.
  - No personal names or addresses in anything shipped: the maintainer is `L6Desk <noreply@l6desk.invalid>` (`.invalid` can never be registered); the signing key is `CN=L6Desk`. Source and downloads: github.com/l6desk/l6desk (GPL-3.0), pushed and published by the project account `l6desk-releases` (`scripts/publish-release.sh`, see `docs/release.md`); commits there are authored `L6Desk <8302096+l6desk-releases@users.noreply.github.com>` (the project account's GitHub no-reply address; git is set up that way in that working copy). Never switch the active `gh` account; other projects use it. The only personal name anywhere is the credit in the READMEs ("Originally designed and developed by …"): no other names, email addresses, host names or home paths, including in test data.
- Gradle Kotlin DSL, version catalog (`gradle/libs.versions.toml`)
- Desktop packaging via Compose `nativeDistributions`: DEB for Ubuntu, MSI for Windows
- All development happens on Ubuntu. Use the Android SDK command-line tools, build APKs with Gradle, and install them onto the tablet with `adb` (USB, or `adb` over Wi-Fi). Don't assume Android Studio.

## The earlier web proof of concept
A browser-based (Web MIDI) proof of concept came first. It is **superseded** by this Kotlin build: browsers handle BLE-MIDI poorly, a browser can't host the Wi-Fi bridge, and a native app gives more control over USB and Bluetooth permissions on Android. Its UI layout, control behaviour, CC mappings and hardware findings were carried into this design and `docs/hardware-notes.md`; its hardware captures are in `docs/hardware-captures/`. It isn't part of the public repository.

## Development and QA on Ubuntu
All day-to-day development, testing and QA runs on Ubuntu 24.04. Set this up in milestone 1 and document it in `docs/dev-setup.md`, with a script (`scripts/setup-ubuntu.sh`) that installs what it can.

**Toolchain**
- JDK 17 or 21 (match what the Kotlin/Compose/AGP versions require) via apt or SDKMAN.
- Android SDK **command-line tools** only (`cmdline-tools`, `platform-tools`, `build-tools`, one platform, the emulator, and an x86_64 system image). Android Studio is optional and not required.
- KVM enabled for the emulator (`/dev/kvm` access; user in the `kvm` group).
- `scrcpy` to mirror and control the real tablet on the Ubuntu desktop over `adb`, so QA on the device doesn't need the tablet in hand.
- `alsa-utils` (`aconnect`, `amidi`) for MIDI inspection.

**Test layers, fastest first**
1. **Unit tests** (`core/src/commonTest`, run on the JVM): model, CcCodec, MixerController throttling and echo suppression with a virtual clock, bridge protocol framing and handshake. These are the bulk of the tests; `./gradlew check` must run in well under a minute.
2. **Desktop app against the simulator:** `./gradlew :desktopApp:run` with `SimulatorTransport`. This is the main loop for UI work. Add Compose desktop UI tests for the key controls (drag a fader, a knob double-tap reset, Learn mode).
3. **Desktop app against virtual ALSA MIDI:** load `snd-virmidi` (or use a virtual port) and a small test script that plays the part of the mixer, so `JavaSoundMidiTransport` is exercised end to end without hardware.
4. **Android emulator on Ubuntu** for layout at tablet sizes, permissions flows and the **Wi-Fi bridge route** (the emulator reaches the host's bridge at `10.0.2.2`, or set up port forwarding). The emulator **cannot** test BLE-MIDI or USB-OTG MIDI. Headless emulator boots may come up without Wi-Fi: `adb shell cmd wifi connect-network AndroidWifi open`.
5. **Real tablet via `adb` (USB or Wi-Fi) + `scrcpy`:** install with `./gradlew :androidApp:installDebug`, and read logs with `adb logcat` filtered to the app tag. The debug build is `io.github.l6desk.debug`, named "L6Desk debug", so it installs beside the signed release (the gig app) with its own settings and songs; only have one of them connected to the Pi at a time. This is the only place BLE-MIDI and the real Wi-Fi network are tested, and it is the sign-off step.
6. **Real L6max:** USB from Ubuntu first, then the tablet routes.

**Rules**
- Every milestone must be verifiable with layers 1–4 alone unless it is a hardware or sign-off milestone.
- Add a debug overlay (toggle in settings) showing the connection route, last 20 MIDI messages in and out, and message rate. It's the main QA aid on the tablet. ✅ `MidiLogOverlay`, toggled by the MIDI button in the top bar until a settings screen exists.
- Provide Gradle or script shortcuts: `scripts/dev-desktop.sh`, `scripts/dev-tablet.sh` (build, install, launch, start `scrcpy`, tail logcat), `scripts/bridge-sim.sh` (run the bridge with the simulator behind it).

## How the app reaches the mixer
The L6max has USB and 3.5 mm TRS MIDI jacks, but **no network port**. There are three routes:

```
A. USB (desktop dev loop)
   Ubuntu app ──USB──► L6max Mixer Control Port

B. Bluetooth (live, tablet)
   Android app ──BLE-MIDI──► BLE-MIDI dongle (e.g. CME WIDI Jack) ──TRS MIDI──► L6max

C. Private Wi-Fi (live, tablet)
   Android app ──Wi-Fi──► Bridge ──USB──► L6max
   The bridge is either the Ubuntu desktop app in "Bridge mode",
   or a headless bridge on a small Linux board (e.g. Raspberry Pi) that also
   runs its own Wi-Fi access point, so no venue network is involved.
```

Routes B and C must both be production quality. Route A is the main development route; C also works for development, using the Ubuntu PC as the bridge.

## Transports
All MIDI goes through one shared interface; platform code only implements it.

```kotlin
interface MidiTransport {
    val devices: StateFlow<List<MidiDeviceInfo>>
    suspend fun open(device: MidiDeviceInfo)
    fun close()
    fun send(message: MidiMessage)
    val incoming: SharedFlow<MidiMessage>
    val state: StateFlow<ConnectionState>   // Disconnected / Connecting / Connected / Error(reason)
}
```

| Implementation | Where | Purpose |
|---|---|---|
| `JavaSoundMidiTransport` | `shared/jvmMain` (desktop, and the headless bridge later) | USB to the L6max via `javax.sound.midi` (ALSA on Ubuntu). ✅ Working with the real L6max. |
| `AndroidMidiTransport` | Android (`androidApp`) | `android.media.midi`: ✅ **USB-OTG** working on the real Galaxy Tab A11 (L6max straight into the tablet: "ZOOM CORPORATION L6max", 3 ports each way, no permission prompt; ports named as on the desktop, port 2 = Mixer Control; ids stable across replugs; READ FROM MIXER over the Editor port). BLE-MIDI deferred (no adapter). |
| `NetworkMidiTransport` | common | Client side of the Wi-Fi bridge protocol (below). ✅ Tested on the emulator against `scripts/bridge-sim.sh`. |
| `SimulatorTransport` | common | In-process fake L6max for development and tests without hardware |
| `MultiTransport` | common | Offers several transports as one (e.g. USB + simulator); opening a device closes the previous transport |
| `LoggingTransport` | common | Records the last 20 messages each way for the MIDI debug overlay |

`ConnectionManager` (in `sync/`) auto-connects: the L6max Mixer Control port whenever one is present, otherwise the simulator. Once the user picks a device it sticks to that choice (reconnecting when it reappears); so does the device remembered from last time once it's connected, and a connected device that drops out of the list for a moment (a found bridge missing discovery rounds while its Wi-Fi is busy) is kept, never swapped for the simulator. After a failed open it retries only when the device list changes. Persisting the last choice comes with milestone 8.

The desktop app's BLE-MIDI support is **out of scope**; on Ubuntu, BlueZ exposes BLE-MIDI awkwardly. Use USB on desktop.

### Wi-Fi bridge protocol
Keep it deliberately simple:
- **TCP**, default port 5704 (make it configurable). Messages are length-prefixed raw MIDI bytes, plus a small handshake (protocol version, bridge name, connected-mixer status).
- **Discovery:** advertise `_l6desk-bridge._tcp` via mDNS. Android uses `NsdManager`; the desktop bridge uses a small mDNS library, or Avahi on the headless build. Always allow manual IP:port entry as a fallback.
- The bridge forwards MIDI in both directions between the TCP client and the L6max Mixer Control Port. It accepts **one controlling client at a time**; later a second client could be view-only.
- **Keepalive** ping every 2 s. The client shows "link lost" within ~5 s and auto-reconnects.
- No auth in v1, since it runs on a private network, but leave a hook for a shared PIN.
- ✅ **Implemented (milestone 6), version 1:** frames are `[type][length: 2 bytes BE][payload]`: HELLO, WELCOME, MIDI, PING, STATUS (mixer connected at the bridge), BUSY. `FrameSession` (shared by both ends) runs the reader, a single background writer (never blocks the UI thread), the 2 s ping and the 5 s watchdog, and closes the socket to unblock reads. `NetworkMidiTransport` keeps reconnecting every 2 s until closed. `BridgeServer` (`shared/jvmMain`, reusable by the headless bridge) forwards both ways between one client and the desktop app's transport. The version byte in HELLO/WELCOME is the PIN hook.
- ✅ **Version 2** (music player: TRACKS, PLAYBACK, COMMAND frames, JSON via `PlayerJson`) and **version 3** (playlists; uploads: UPLOAD_START/DATA/STATE/CANCEL, each data frame acknowledged, at most 96 KB in flight; `FrameSession.sendBulk` queues upload data behind everything else, so MIDI is never stuck behind a file). Unknown frame types are skipped. The bridge accepts v1–v8 and answers in the lower version (the 0.11.0 app still mixes through it, without the player); an older bridge refuses a newer app, naming its version ("this bridge speaks v3"), and the app reconnects in that version. Details in `docs/bridge-setup.md`.
- ✅ **Version 4: powering the Pi off.** The bridge sends CAN_POWER_OFF after WELCOME only if given a `powerOff` action (the Pi service with `power_off=true`; never desktop Bridge mode). The app's POWER_OFF is echoed back, then the bridge creates `/run/l6desk-bridge/power-off` (its `RuntimeDirectory`), which the root `l6desk-poweroff.path` unit turns into `systemctl poweroff` (so the service stays unprivileged). Verified on the real Pi.
- ✅ **Version 5: bridge info.** BRIDGE_INFO (JSON, `BridgeInfo`): kind (pi / desktop), version, OS, Java, board, uptime and, on a Pi, the under-voltage alarm (now, count and last time since the bridge started), temperature (now, max) and free space for songs, plus plain-words `warnings`. Sent after WELCOME, when a warning appears or clears, and on BRIDGE_INFO_REQUEST (problem reports ask for it fresh). `BridgeHealth` (core/jvmMain) polls `/sys` every 2 s on the Pi; `vcgencmd` isn't usable (/dev/vcio is root's). Desktop Bridge mode sends kind=desktop. The app logs warnings in its event log, and shows them in a banner under the top bar (`BridgeWarningsBanner`, one row at a time so the faders keep their travel, "1 of n"): under-voltage red while it's happening, amber once it has passed; heat; little space for songs. DISMISS hides a warning until it changes (another dip) or clears and comes back.
- ✅ **Version 6: PIN.** PIN_REQUIRED (16 random bytes) after HELLO when the bridge has a PIN (`pin=` in the Pi config, set with `pi-setup.sh pin 1234` / `pin off`, which the install's last line suggests while there's none; `--pin`; desktop SETUP → Bridge mode PIN, `BridgeHost.setPin`, changeable while running); the app answers PIN_ANSWER = HMAC-SHA256(PIN, nonce) (`BridgePin`), so the PIN never crosses the network; wrong → BUSY "Wrong PIN" and the app shows `PinDialog` again (`NetworkMidiTransport.pinRequest`, waits for `setPin`). 5 wrong from one address → locked out a minute (`PinAttempts`). PINs remembered per device id (`AppSettings.bridgePins`). Clients < v6 are asked to update. ✅ Verified with the real Pi.
- ✅ **Version 8: RECORDINGS through the Pi** (see Audio files 3). RECORDINGS (JSON list), REC_COMMAND (start / ack / cancel), REC_STATE (preparing / sending + file sizes / done / failed), REC_DATA (`[id][bytes]`). The DONE state can overtake the last data (states aren't queued behind bulk): the app keeps it until all bytes are in.
- ✅ **Version 7: PAD SOUNDS through the Pi** (verified on the real Pi 2026-10-16: the Pi still talks to the L6max in file transfer mode; the sandboxed service sees and writes the card mounted after it started; pad 4 set from the library and plays). PAD_CARD (JSON, `PadCardJson`; "null" = no card) after WELCOME and on change; PAD_COMMAND (set a pad from a library song / clear / eject) → PAD_RESULT. The Pi mounts the L6max's card in file transfer mode itself (`90-l6desk-card.rules` → root `l6desk-card@.service`, vfat, owned by l6desk-bridge, at `/run/l6desk-card`); eject = the bridge creates `/run/l6desk-bridge/eject-card`, the root `l6desk-card-eject.path` unmounts. `MountedPadCard` (core/jvmMain) on the bridge (only under the service with the rule installed); `BridgePadCardSupport` in the app, preferred over the desktop's own when the bridge offers it; CHOOSE… then picks from the Pi's library in the panel. Backups in `/srv/l6max-music/.pad-backups`. Not in desktop Bridge mode.
- After a reconnect, SEND DESK lights up again (the mixer may have been changed meanwhile). The desktop desk doesn't follow changes made from the tablet while bridging.
- ✅ **Discovery, no new dependency:** the Pi announces `_l6desk-bridge._tcp` through Avahi (the apps also query the pre-0.13 `_l6max-bridge._tcp`) (`bridge/deploy/announce.sh`, run by the service at start/stop). The apps find bridges with a small built-in mDNS client (`net/Mdns.kt`, `BridgeDiscovery`): a legacy-unicast PTR query every 3 s from an ordinary UDP port, answered straight back (so Android needs no multicast lock or NsdManager). Found bridges are devices `bridge-found:<name>`, resolved to their current address on every connection attempt (so the hotspot vs home IP doesn't matter); auto-connect prefers one over the simulator. Dropped after 4 missed rounds. Manual IP entry stays as the fallback. Verified from Ubuntu against the real Pi (`RealNetworkDiscoveryTest`, `L6MAX_NETWORK=1`).
- ✅ **The desktop's Bridge mode announces itself too** (`AnnouncedBridge` around `BridgeServer`; `MdnsResponder`, core/jvmMain, no dependency): it shares UDP 5353 with Avahi (SO_REUSEADDR), joins the mDNS group on each network, answers PTR/SRV/TXT/A questions for `_l6desk-bridge._tcp` (legacy-unicast questions straight back with their id and question, TTL 10; others by multicast), with this PC's address on the asker's subnet, under its own host name (`<name>-l6desk.local`, never the PC's own). Hello twice on start, goodbye (TTL 0) on stop. If the port can't be shared, the bridge still works by address and SETUP says so (`BridgeStatus.announce`). Verified: found by the app's discovery on this PC (`MdnsResponderTest`) and from the Pi. Loops are prevented: the desktop never lists its own bridge (`NetworkMidiTransport.setOwnBridge`), and a bridge whose desk is itself on a bridge (now, or on its last attempt) refuses tablets with BUSY ("connect to that bridge directly") and ends a session if the desk switches to one (found through a REPORT: the desktop had connected to its own Bridge mode).
- **Bridge mode** lives in the desktop app (a toggle), so the Ubuntu PC is the first bridge. The **headless bridge** is a separate small JVM module (`bridge/`) that reuses `JavaSoundMidiTransport` and the protocol code, runs as a systemd service, and needs no UI. Document how to set it up with a Wi-Fi hotspot in `docs/bridge-setup.md`.

## L6max MIDI facts
From Zoom's operation manual, plus findings already confirmed on the real unit (marked ✅).

- **Channels:** 8. Channels 1–4 are mono and 5–8 are stereo. Channels 5 and 6 each have a **MONO ×2** switch that accepts two mono sources instead of one stereo source. The two inputs **share** level, EQ, pan and sends, so MONO ×2 does not create extra channels. Channels 7 and 8 have USB 1/2 and USB 3/4 input switches. ✅ CC113 = CH7 USB 1/2, CC114 = CH8 USB 3/4 (factory map); 127 = USB, 0 = the channel's own inputs; the mixer reports presses.
- Over USB, the L6max exposes three MIDI ports:
  - Port 1, **MIDI I/O Port:** plain passthrough to the TRS MIDI jacks.
  - Port 2, **Mixer Control Port:** the one to use for mixing.
  - Port 3, **L6 Editor Port:** Zoom's editor app uses it. Only use it **read-only**, to fetch the CC map (see below), and only while ZOOM L6 Editor isn't running.
  - ✅ ALSA names on Ubuntu (`aconnect -l`): `L6max MIDI I/O Port`, `L6max Mixer Control Port`, `L6max for L6 Editor Port`. Windows names: `MIDIIN2/MIDIOUT2 (ZOOM L6max)` and similar.
  - ✅ **Java Sound on Linux loses the port names:** all three are `L6max [hw:C,D,S]`, each listed once as an input and once as an output. The ALSA sub-device `S` (0-based) is the port order, so Mixer Control is `S = 1`; the card number varies. `L6maxPorts` recognises all three platforms' naming. ✅ Hot-plug works on Linux without a restart (unplug → error within 2 s, replug → auto-reconnect). Details in `docs/hardware-notes.md`.
- Select the port **by name pattern**, not by index. Show the list and let the user override.
- **MIDI-controllable per channel:** EQ HI, EQ MID FREQ, EQ MID, EQ LO, SUB MIX, AUX1, AUX2 and EFX sends, PAN, LEVEL, MUTE. **Also controllable:** MONO ×2 (ch 5, 6), USB 1/2 (ch 7), USB 3/4 (ch 8), EFX TYPE, COMPRESSOR. Program Change 0–3 recalls scenes A–D.
- **Not MIDI-controllable:** the MASTER knob, and the encoder-mode buttons (LEVEL/PAN/SUB-MIX/AUX/EFX/EQ select), which also send nothing when pressed (✅ checked), so the app's SENDS toggles can't mirror them. ✅ The L6max has **no solo** function at all, and **no master mute**. The app implements master level, master mute and solo itself (see Features).
- The user can assign the CC numbers in ZOOM L6 Editor ("MIDI CC# Mapping"). **Never hard-code CC numbers;** treat the factory map as a default profile.
  - ✅ The factory map was read from a real L6max (fw 1.10) and matches the proof of concept's table. The capture is in `docs/hardware-captures/l6max-fw1.10-editor-open-reply.hex`.
  - The **current map can be read from the mixer:** on the Editor port, send Identity Request `F0 7E 00 06 01 F7`, then EditorOpen `F0 52 00 00 2B F7`. The reply `F0 52 00 00 2A <176-byte payload> F7` has layout byte `0x2E` at payload[0] and the 94-entry CC table at payload[28..121]. The order is field-major: [EQ HI, MID FREQ, MID, LO, SUB MIX, AUX1, AUX2, EFX, PAN, LEVEL, MUTE] × ch1–8, then MONO×2 ch5, ch6, USB 1/2, USB 3/4, EFX TYPE, COMPRESSOR. ✅ Verified on hardware. This is read-only; never send editor *write* commands. Protocol source: github.com/philmillman/zoom-l6-companion (MIT).
- ✅ **The mixer sends a CC whenever one of its own controls moves** (USB Mixer Control Port, fw 1.10). Each message carries the absolute new value, and fast turns jump several steps at a time. A sample is in `docs/hardware-captures/l6max-fw1.10-hardware-moves.txt`. So bidirectional sync and Learn mode both work.
- ✅ The channel controls are **endless rotary encoders with LED rings, not faders**. There's no "physical fader position" to get out of step with: after the app sets a value, the encoder carries on from it (✅ tested: app set CH8 to 40, then a slow turn sent 43, 44 … 50).
- ✅ **Pan:** 0–127, centre 64. **Mute:** the mixer sends 127 = muted, 0 = unmuted. It accepts the same values back.
- ✅ **No echo:** CCs sent to the Mixer Control Port (mute and level on CH8) took effect on the hardware and were **not** echoed back. Keep echo suppression anyway: it's cheap, and the TRS/BLE and bridge routes are untested.
- Level hint: level 40 (31% of 0–127) lit the LED ring to about a quarter (9 o'clock), so the ring or the level curve may not be linear.
- The L6max uses **one MIDI channel** for everything (its "MIDI Channel" setting, default 1). It is a global setting, not one per CC.
- **Route B (TRS jacks):** the mixer only responds when "Mixer Control via MIDI" is **On**. It can be set in the device menu or in L6 Editor. Mixer moves only come back out of the TRS MIDI OUT jack if **MIDI Out Mode = Out** (not Thru). Show both as hints in the connection UI.

### Must verify on real hardware (don't assume)
- What dB each level value corresponds to (the level curve). What values switch MONO ×2.
- ✅ EFX TYPE is verified: CC 117 = 0/21/43/64/85/107 (AI NR, Hall, Room, Spring, Delay, Echo).
- ✅ **Sound pads:** MIDI notes on the profile channel, factory pad 1–4 = notes 60, 62, 64, 65 (assignable in L6 Editor; also in the EditorOpen snapshot at payload[122..125], after the CC table). Pressing a pad on the mixer sends Note On vel 100, releasing it Note Off vel 64; it reports presses, not playback. Sent notes play the pads: Loop starts/stops on alternate presses, Hold plays while the note is held. **One-shot can't be stopped over MIDI** (every note restarts it; the long-press stop is internal to the mixer, tested with every variant of the mixer's own messages). ✅ **INSTANT MUTE doesn't silence the pads** (they bypass the channels, via the SOUND PAD knob).
- ✅ Scene recall: Program Change A = 0, B = 1 (C/D presumably 2/3); recalling on the mixer sends only the Program Change, never the recalled values.
- ✅ **The mix can't be read back** from the mixer (Editor-port GetParam groups 0–127 and the settings snapshot probed; nothing reflects the mix). The app only knows values it set or saw move. See `docs/hardware-notes.md`.
- What MONO ×2 does to panning: are the two inputs kept left and right, or summed to the centre?
- ✅ Java Sound hot-plug on Windows works (Surface Go 2, Windows 11, 2026-10-14): a replugged L6max is listed again without a restart.
- Whether mixer-originated CCs come out of the TRS MIDI OUT jack too (needed for route B's bidirectional sync), or only over USB.
- Record all findings in `docs/hardware-notes.md`.

## Features, v1
- **8 channel strips**, each with:
  - vertical **level slider**
  - rotary knobs for **pan**, **EQ high**, **EQ mid**, **mid frequency**, **EQ low**
  - **mute** and **solo** toggle buttons
  - editable channel name (stored locally)
  - channels 5 and 6 also have a **MONO ×2** toggle. It does not add strips, because the two mono inputs share one level and pan on the mixer.
- **Master section** (all app-side, since the mixer's MASTER knob isn't MIDI-controllable). This is carried over from the web proof of concept:
  - **Master slider:** a "VCA" that scales every channel level sent to the mixer. **Unity is at 6** on the 0–10 scale (value 76, marked in amber), and the master starts there, so by default the mixer gets the channel fader values unchanged. Above 6 every channel is boosted (up to ×1.67 at 10); below 6 everything is turned down. Level sent = min(127, channel fader × master / 76). The scaled level can go **above 127 internally**; only the value sent is capped. So a channel that maxes out stays at 127 while the master rises, and as the master comes back down it only starts dropping once its internal level falls below 127, returning to the original balance. If the hardware reports 127 for a maxed channel, the app keeps its internal level. Double-tap returns the master to 6. The hardware MASTER knob still applies after all this.
  - **Master mute** (instant mute for feedback emergencies): mutes **all 8 channels** while it's engaged. On release, each channel goes back to its own mute and solo state. Show it unmistakably: a large red state and a flashing frame around the desk.
  - A "solo active" indicator and a **clear solo** button.
- **How mute, solo and master mute are carried out:**
  - **Channel mute:** use the L6max's own MUTE parameter via its CC mapping.
  - **Solo** (app-side, since the mixer has none): when any channel is soloed, the app mutes every non-soloed channel. Multiple solos are allowed. This affects the **main mix**, not a headphone bus, so say so in the UI. When the last solo is released, each channel returns to its own mute state (a user mute stays on). Show "soloed-out" channels with a distinct indicator, separate from a user mute.
  - **Effective mute** for a channel = master mute OR user mute OR (any solo AND not this channel soloed).
  - The rules are pure functions on `MixerState` (`effectiveMute`, `effectiveLevel`, `outboundValue`, `faderForDeviceLevel`), tested in `MixerStateTest`. `MixerController` applies them: it keeps the user's values in `MixerState`, tracks what was sent, and sends only the differences. Unit-test every transition: solo on/off with user mutes present, multiple solos, master mute on/off with user mutes and solos present, master slider moves, and reconnecting while muted.
  - On reconnect, re-send the effective state so the mixer matches the app.
  - **Desk changes while app-side overrides are active:**
    - A mute pressed at the desk updates that channel's **user** mute. If master mute or solo still requires the channel to be muted, the app re-mutes it immediately: master mute means *everything stays off* until it's released.
    - A level moved at the desk sets the channel fader to (incoming value × 76 / master), so the desk and app agree. Don't echo the rounded result back to the mixer.
- **CC map editor**: each (channel, parameter) → CC number, plus **one global MIDI channel**. Save/load named profiles as JSON. Ship the factory profile (verified on fw 1.10). ✅ Milestone 8: SETUP screen with profiles (save as, delete, factory map; editing the factory profile copies it first), a grid editor (±1/±10, clear, duplicates in red), Learn and Read from mixer.
- **Read map from mixer:** fetch the current CC table over the Editor port (see L6max MIDI facts). This is the quickest and most reliable way to match the user's mapping. ✅ Works over USB on the real L6max (`CcMapReader`, implemented by `JavaSoundMidiTransport`, which opens the Editor port on the same ALSA card; `RealL6maxTest` runs it with `L6MAX_HARDWARE=1`).
- **Learn mode**: tap a control in the app, move a control on the mixer, and the app captures the CC. This is a fallback; it works because the mixer sends CCs when its controls move (✅ verified). Implemented in the map editor: pick a cell, LEARN, move the control; it then moves on to the same control on the next channel. The learned message's MIDI channel becomes the profile's channel; incoming CCs aren't applied to the desk while learning.
- **Connection screen**: choose the route (USB / Bluetooth / Wi-Fi bridge / Simulator), device or bridge list, auto-reconnect to the last connection, and a clear status indicator always visible.
- **Bridge mode** toggle in the desktop app, showing its IP, port and connected client. ✅ SETUP → Options; see `docs/bridge-setup.md`.
- The layout fits all 8 strips plus the master section on the Galaxy Tab A11 in landscape, with no scrolling; the web proof of concept shows this works. It must also work in a desktop window. Keep the screen awake while connected on Android.
- **SENDS view** (top bar → SENDS; BACK TO MIX returns): a separate view so the live MAIN desk stays exactly as it is. A band of four toggles (AUX 1, AUX 2, SUB MIX, EFX; one lit) picks the send the **faders** set; fader caps take that send's colour (blue, green, amber, purple). Each strip's EQ/pan area becomes an overview of all four send levels (tap a row to select it). The master section shows INSTANT MUTE (always), EFX TYPE (AI NR, Hall, Room, Spring, Delay, Echo) and COMP, and no master fader. MUTE/SOLO keep their meaning. Reopens on the last send used (saved); the app always starts on MAIN.
  - **App scenes A–D** (SENDS master section): **hold** a button (~0.7 s) to store the desk; **tap twice** to recall (the first tap arms it, flashing; the second within 3 s recalls), so one stray tap can't swap the mix mid-song. A recall loads the snapshot and sends it all, so desk and mixer match. A scene holds levels, pan, EQ, mutes, MONO ×2, master, EFX TYPE, COMP and known sends; not names, solos or instant mute. A recall never releases instant mute and keeps sends the scene didn't know. Saved in `scenes.json`. These are app scenes, deliberately not the L6max's own scenes (Program Change A=0…), because those can't be read back.
  - **Scenes recalled on the mixer itself** (it sends Program Change 0–3, never the values): by default an amber banner warns "Scene X recalled on the L6max: the desk no longer matches the mixer", with SEND DESK (also lit in the top bar) and DISMISS; SEND DESK or recalling an app scene clears it. Opt-in setting "The mixer's scene buttons recall the app's scenes" (SETUP → Options, off by default): a mixer recall makes the app recall and send its scene in that slot (green confirmation for 4 s); an empty slot falls back to the warning. The mixer's own scene plays for a moment until the app's arrives.
  - **Sends, EFX TYPE and COMP start unknown** (the mixer can't be read): shown as "—" / a grey parked fader / "COMP ?", known once set in the app or reported by the hardware, and only known values are sent or pushed, so SEND DESK never zeroes a monitor mix. Known values are saved with the desk.
- **Sound pads** (MAIN master section, under CLEAR SOLO; 2×2, teal): momentary like the hardware pads. Note On when the finger goes down, Note Off when it lifts, so the mixer applies the pad's play mode (One-shot, Loop, Hold). Lit while held in the app or on the mixer (the mixer doesn't report playback). The pad notes belong to the CC map profile: PAD NOTE row in SETUP → MIDI MAP (±1/±12, clear, Learn by pressing the pad on the mixer); READ FROM MIXER picks them up; unmapped pads are greyed out. While INSTANT MUTE is on, the pads' label turns red: "PADS NOT MUTED". `MixerController.setPad`/`pads`; profiles saved before pads get the factory notes.
- **USB input switches** (CH7 USB 1/2, CH8 USB 3/4, in the strip headers): like sends, unknown ("USB ?") until set in the app or reported by the mixer, never sent while unknown, kept by scene recalls that don't know them and by RESET MIX (`LEARNED_PARAMS` = sends + USB_INPUT).
- **Music player on the desktop, without the Pi** (0.16): when the L6max is on the desktop's USB and there's no bridge player, the same panel drives `LocalPlayer` (core/jvmMain): the Pi's `WavPlayer` on this computer, library `~/Music/L6Desk`, uploads copied in (ffmpeg if installed). Both are `PlayerHost`s (`NetworkMidiTransport` is the other); App picks the bridge's player first. Made on first use only (no folder otherwise). ✅ Verified on Ubuntu with the real L6max on USB (plays cleanly on CH7). ✅ **The tablet's own player over USB-OTG** (2026-10-18): the same `WavPlayer`/`LocalPlayer`, now in core's `jvmAndroidMain` source set (shared by jvm and Android; no Java Sound or java.nio.file: `WavReader`/`WavWriter` read and write WAVs in plain Kotlin, `PlatformFiles` expect/actual), `AndroidPcmOutput` (AudioTrack, channel index mask 0b1111, routed to the L6max: the Tab A11 gives it all 4 channels), `MediaCodecConverter` (Android's decoders for MP3/AAC/FLAC, then 48 kHz via WavReader). Library in the app's own Music folder (`Android/data/io.github.l6desk/files/Music/L6Desk`); songs are added with UPLOAD (files adb pushes there are invisible to the app on Android 11+). Verified on the real tablet: clean playback on CH7/CH8; and with 0.17.0 installed, the Pi's and the desktop's players (now on WavReader too) play fine (2026-10-18).
- **Music player** (top bar → PLAYER; Pi bridge, or the desktop as above): the Pi plays songs from its SD card into the L6max's USB audio, USB 1/2 → CH7 or USB 3/4 → CH8 (48 kHz, 4 channels, Java Sound `JavaSoundPcmOutput`), so Wi-Fi can't interrupt a song. Playlists are folders in `/srv/l6max-music`; songs keep their order as a number at the start of the file name, which the app hides. Tap = cue, PLAY plays the cued song (asks to switch the channel to USB if it isn't known to be); per-song end: Stop / Play next (gapless) / Loop; pause, stop, volume (squared curve, ramped, never above the file's level), output. Opening the panel changes nothing: both OUTPUT buttons are off until the user chooses CH7 or CH8 (PLAY before that asks for one). Choosing notes that channel's level and USB switch (`playerHeldLevels`, `playerHeldUsb`; an unknown switch isn't given back), switches it to USB at full level, and sets the player's volume to 10 % the first time; choosing the other channel gives the first back as it was. The other channel is never raised (an unused channel at full brought up its hum on the real L6max). Closing gives it all back (after the song if one is playing; the chosen output is the held one, so it survives closing mid-song and restarts; 0.16's list of two held levels is still read). UPLOAD (desktop file dialog, Android document picker) into the playlist shown or a new one; the Pi converts anything but 48 kHz WAV with ffmpeg on arrival. EDIT: ▲ ▼ renumber the files, DELETE (two taps), type over a title to rename the song (the file keeps its number; Enter or losing focus sends it, Escape reverts); for the playlist shown, RENAME (end settings, playing and cued songs follow) and DELETE PLAYLIST (two taps; stops its song if playing; deletes only plain files and links in the folder, never following a link; refused while songs are arriving). Offered only when the bridge sets `PlayerState.canEditPlaylists` (0.15 on). Playlist row: fixed-width buttons (long names cut short); when they don't all fit, as many as fit (always the one shown) plus ALL (n), which opens a list of every playlist with + NEW. `WavPlayer` (core/jvmMain) also numbers and converts files copied in by hand once they stop changing (`scripts/music-pi.sh`), moves loose files into "Songs", and tidies up after interrupted uploads.
- **SEND DESK:** the mixer can't report its current positions, so on connect the app shows its last saved state and offers to send the whole desk to the mixer. The button stays highlighted until pressed. There's also an option to send automatically on connect. Until it's pressed, an edit sends only the control that changed (the desk's values are the diffing baseline), never the whole desk.
- **Closing shuts down the Pi** (SETUP → Options, on by default, `AppSettings.powerOffBridgeOnExit`): closing the app (desktop window close; Android back at the desk, via `ExitRequests`) while connected to a bridge that can power off asks SHUT DOWN PI & CLOSE / CLOSE, LEAVE PI ON / CANCEL (warns if music is playing), because a powered-off Pi needs replugging. Otherwise it closes straight away. Swiping the app away in Android's recents doesn't ask and leaves the Pi on.
- **REPORT** (top bar, from 900 dp wide; SETUP → REPORT A PROBLEM): makes a problem report zip to attach to a GitHub issue (`docs/report-issue-plan.md`).
  - Contents: the screenshot (from a `GraphicsLayer` recording of the whole app), the user's message, the connection and bridge details, versions and platform, and recent `AppLog` events plus the MIDI log. IP addresses are masked, except the Pi hotspot's.
  - Signing: with a per-install ECDSA P-256 key (Android Keystore; desktop `report-key.txt` in the settings folder). Proves the report is unchanged and which install made it, not that the app was genuine.
  - Saving and opening: SAVE (desktop dialog, Android picker), SHARE (Android), then OPEN GITHUB ISSUE (title and body in the URL).
  - Checking: received zips are only ever opened through `scripts/check-report.sh` (`ReportCheck`: exact file names, size limits, hashes, signature, PNG and text checks).
- **RESET MIX** (SETUP → New mix, with a confirmation): a clean slate for a new mix, because the L6max powers up exactly as it was switched off. Sets every level to 0, pan centre, EQ flat (incl. mid freq), EFX sends to a known 0, mutes and solos off, master to unity; keeps names, MONO ×2 and AUX/SUB MIX sends (as they were, known or not). Only while connected. `MixerState.cleanSlate()` + `MixerController.resetMix()`.

### Release plan (agreed 2026-10-13)
- **0.16.0:**
  - ✅ a bridge PIN;
  - ✅ the Pi's other warnings (hot, low space for songs) shown on the desk like under-voltage;
  - ✅ the music player on the desktop without the Pi (L6max on USB);
  - also the desktop Bridge mode announcing itself (pushed after 0.15.0).
- **0.17.0 (released 2026-10-18), before and after the gig:**
  - ✅ sound-pad audio files to the L6max's microSD (desktop); ✅ through the Pi (any app, songs from the Pi's library; verified on the real Pi);
  - ✅ fetching its recordings (file transfer mode): desktop and through the Pi, both verified.
- **Now the Galaxy Tab A11 is here (2026-10-17):**
  - ✅ USB-OTG on Android (milestone 7's second half);
  - the player on the tablet over USB-OTG (the tablet sees the L6max's USB audio too).
- **Bluetooth MIDI: deferred (decided 2026-10-17)** until the user has a BLE-MIDI adapter for the L6max's MIDI jacks. The Pi is cheaper than an adapter and has more range, so most users are expected to use the Pi.
- **Later, if wanted:** the desktop player on Windows through WASAPI (native code or a library such as JNA: ask first).
- **Low priority:**
  - the Pi's read-only mode;
  - view-only second client;
  - per-performer monitor layouts;
  - multiple layouts.
- **Windows:** ✅ built by GitHub Actions (`.github/workflows/windows.yml`: tests, `.msi`, portable zip; run by hand or on v* tags), USB MIDI verified on a Surface Go 2 (Windows 11). Windows quirks: MIDI inputs and outputs have different names ("MIDIIN2 (…)" / "MIDIOUT2 (…)"), paired by `pairKey`; an open file can't be renamed or deleted, so songs are opened through NIO (FILE_SHARE_DELETE), and a playlist folder can't be renamed while one of its songs plays (the app says so); renames and deletes are retried for up to a second (antivirus); Java's audio on Windows is stereo-only ("Line (ZOOM L6max Audio)") and Zoom's driver fixes the device at 4 channels, so DirectSound refuses every format: the desktop's own player is turned off on Windows (desktop main passes no `localPlayer`); the Pi's player works. A Windows desktop player would need WASAPI (native; a dependency decision). The stereo fallback (`StereoAdapter`, `usbPairs`) stays for other stereo-only devices; ffmpeg is looked for on the PATH. The L6max needs Zoom's Windows driver, and the Surface needed a direct USB-C to USB-C cable (an adapter didn't work).

### Later (not v1)
Recalling the L6max's own scenes (Program Change; verified A=0, B=1, but their contents can't be read back, hence app scenes), "sends on faders" per-performer monitor layouts, multiple layouts, view-only second client, bridge PIN. (Sends, EFX TYPE and COMP: ✅ done in the SENDS view. USB input switches: ✅ done.)

**Pi bridge, once the model is complete:** an optional read-only (overlay) file system so a gig box can have its power pulled safely (`raspi-config` → Performance → Overlay File System), plus an easy way to switch it off for updates. Deliberately deferred while updates are deployed frequently.

**Audio files and sound pads (high value for live gigs):**
1. ✅ **Sound pad buttons:** done (see Features). Works over every route that carries MIDI.
2. ✅ **Before the gig: pad sounds** (0.17, desktop): SETUP → PAD SOUNDS… (`PadCardPanel`; `PadCard` in core/jvmMain, `PadCardDesktop`). Verified on the real L6max (docs/hardware-notes.md): the card is FAT32 label ZOOM_SD (USB 1686:08f4 in file transfer mode, MIDI still offered), but its boot sector has no jump code, so blkid ignores it; the `.deb` installs `99-l6desk-zoom-sd.rules` (udev: ID_FS_TYPE=vfat) so Ubuntu mounts it. Pads are `SOUND_PAD/PAD1`–`PAD4`; the mixer records each pad's file in `SOUND_PAD/L6MAXPADSETTING.zst` (UTF-16 paths, 522-byte block per pad from 0x60, "NO ASSIGN"; read, never written) and, when that file has gone or a pad is unassigned, adopts the file in the folder and records it. So the app sets a sound by leaving just the new file there (16-bit 48 kHz WAV, converted with ffmpeg or Java Sound), after copying the old files to `~/Music/L6Desk/Pad backups/<date>/PADn`. The user switches file transfer mode on the mixer (decided: the app sends no Editor-port commands); EJECT uses udisksctl on Linux. After each change the app flushes the card (`sync -f`): a card switched off with writes pending kept a 0-byte file. ✅ Verified on the real L6max: a 3-minute song on pad 4 plays.
3. **After the gig: receive the L6max's recordings** (32-bit float WAV, multitrack or master) from the microSD.
   - ✅ **Desktop (0.17, verified on the real L6max 2026-10-16): SETUP → THE MIXER'S CARD → RECORDINGS…** (`RecordingsPanel`; `RecorderCard`/`RecordingCopier` in core/jvmMain `recordings/`, `RecordingsDesktop`). Recordings are `RECORDER/<yymmdd_hhmmss>/` (every track recorded whether used or not; `MASTER_nn` per bounce, the highest newest; see hardware notes). Listed newest first with the newest picked; tracks "with sound" found by sampling each file's peak (> −40 dBFS). Options (remembered, `AppSettings.recordingOptions`): MASTER (newest take) / USED TRACKS (+ that master) / ALL; 32-bit float (copied unchanged) or 24-bit; normalise (master's peak to −1 dBFS on its own, tracks all by one gain from the loudest; anything below −60 dBFS left alone). To `~/Music/L6Desk/Recordings/<folder>/`, names marked "(24-bit, normalised)"; written as `.part` then renamed; cancellable; OPEN FOLDER; EJECT CARD. Nothing on the card is changed.
   - **Through the Pi (protocol v8, any app):** same panel, all options (with a Wi-Fi time estimate). `MountedRecordings` (core/jvmMain) on the Pi's card mount; the Pi streams converted files (`RecordingCopier.plan`/`write` to a `ByteSink`, nothing stored), REC_DATA behind MIDI, 128 KB window; `NetworkMidiTransport.downloadRecording` into a `RecordingSaver` (desktop `DesktopRecordingSaver`; Android `AndroidRecordingSaver`: MediaStore Music/L6Desk/Recordings, WAVs only). `BridgeRecordingsSupport` preferred when the bridge offers it. `scripts/bridge-sim.sh PORT CARD_DIR` serves a card's recordings for emulator tests. ✅ Verified on the real Pi (2026-10-17): used tracks, 32-bit, normalised, 370 MB in 180 s over Wi-Fi (2.1 MB/s) after ~45 s reading the files to normalise (8.5 MB/s from the card; remembered afterwards). The first try dropped mid-download: most likely the auto-connect swapping a found bridge missing discovery rounds for the simulator (fixed in ConnectionManager); link-lost messages now give the cause.
   - Items 2–3 use the L6max's **file transfer mode** (microSD appears as a USB drive). zoom-l6-companion documents switching it with Editor-port SysEx `F0 52 00 00 31 09 01 F7` (on) / `… 09 00 F7` (off), verified on an L6max; the device re-enumerates and **stops mixing and recording** while it's on, so this is strictly pre-/post-show and needs a clear warning and confirmation in the UI. Verify on our unit first (entering/leaving, re-enumeration, card mounting on Linux and Android).
   - Desktop over USB is the straightforward case. Through the Pi bridge it means the Pi mounting the card and serving files over Wi-Fi (slow for large multitrack WAVs on 2.4 GHz; consider pulling only selected tracks, or the master). Tablet over USB-OTG: Android USB mass storage access needs the Storage Access Framework.
4. ✅ **Live audio player:** done on the Pi bridge (see Features: Music player), which plays from its own SD card, so the audio never crosses Wi-Fi. Playing from the desktop or the tablet on USB-OTG directly is not done. Recording the L6max's USB channels is DAW territory.

## Architecture
```
core/           Everything but the UI (KMP: jvm + androidLibrary; no Compose, so the Pi bridge stays small):
  model/        MixerState (immutable, derives effective values), ChannelState, Control/ChannelParam, CcMap, Profile, L6maxLayout (CC table order + factory map)
  midi/         MidiMessage, MidiStreamParser (raw bytes → messages, running status), MidiTransport interface, CcCodec, L6maxSysEx (Editor-port snapshot),
                SimulatorTransport, MultiTransport, LoggingTransport, L6maxPorts; jvmMain: JavaSoundMidiTransport
  net/          Bridge protocol (framing, handshake, keepalive), FrameSession, NetworkMidiTransport (+ uploads), Mdns + BridgeDiscovery; jvmMain: BridgeServer; java.net sockets in jvm/androidMain
  player/       Player model and JSON (Track, PlayerState, PlayerCommand, uploads), UploadQueue (app side); jvmMain: WavPlayer (playback + library), JavaSoundPcmOutput, FfmpegConverter
  sync/         MixerController (state ↔ MIDI, throttling, echo suppression), ConnectionManager (auto-connect)
  report/       Problem reports: AppLog, ProblemReport, ReportPackage (zip + manifest + signature), ReportIssue (GitHub link);
                jvmMain: FileReportKey, ReportCheck (the receiving-side checker); androidMain: KeystoreReportKey
  persistence/  AppStorage: desk.json, profiles.json, settings.json (kotlinx.serialization) in a TextStore. FileTextStore writes atomically;
                plus scenes.json (app scenes). Desktop dir ~/.config/l6desk (Windows %APPDATA%\L6Desk), Android filesDir. Unreadable files are kept as <name>.bad.
                Solo and master mute aren't saved (they always start off); the last user-picked device is, and is reconnected to.
shared/         Compose UI (depends on core via api): App(), ui/ (ChannelStrip, Fader, RotaryKnob, ConnectionPanel, SetupPanel, MidiLogOverlay…).
                Must also lay out in portrait: Android 16+ ignores orientation locks on large screens. ✅ Portrait (or any width where 8 strips
                don't fit): banks of 4 (CH 1–4 / CH 5–8 buttons with a mute/solo lamp per channel; no swipe, so a brush can't flip
                banks mid-song), longer faders, compact top bar (no app name or MIDI button), SETUP's map cells narrow to fit.
                Verified on the real Galaxy Tab A11 (2026-10-20).
androidApp/     Activity, AndroidMidiTransport (BLE + USB, milestone 7), Bluetooth permission handling
desktopApp/     main(), Bridge mode (BridgeServer around the app's transport), BridgeSim.kt (scripts/bridge-sim.sh)
bridge/         Headless bridge (depends on core only): config file, status LED, single jar; deploy/ has the systemd unit, default
                config and pi-setup.sh (install, fallback hotspot). Target: Raspberry Pi Zero 2 W, 64-bit Raspberry Pi OS Lite.
```

- Single source of truth: `MixerController` exposes `StateFlow<MixerState>`. UI renders it and sends intents.
- **Throttling:** while a control is dragged, coalesce outgoing CCs to at most ~50 msgs/s per control (BLE is slow); always send the final value on release.
- **Echo suppression:** ignore incoming CCs for a control that the user is currently touching, and for a short window (~150 ms) after the app sent the same value.
- Knobs: vertical drag to change value (not circular gesture), double-tap resets to default (pan centre, EQ flat). Fine adjust with a modifier key on desktop.
- Controls must be large enough for fingers: minimum 48 dp touch targets. Exception: the small MONO ×2 toggle in the channel 5/6 header (rarely used, and there's no height to spare at 600 dp).
- Android runs immersive full screen (system bars hidden, swipe to reveal) so the faders get the most travel, and keeps the screen awake only while connected. Its transports run on `Dispatchers.Default`, never the UI thread (a pulsing SEND DESK once slowed a bridge download a hundredfold on the emulator); continuous animations read their value in `graphicsLayer`/`drawBehind`, so only their own layer redraws.
- `MixerController` (milestones 3–4): state, change-only sending, hardware moves, overrides, throttling (20 ms per control, leading edge sent at once, latest value at the end of the window, flushed on release) and echo suppression (touched controls; any value sent in the last 150 ms). Timing uses coroutine delays, so `MixerControllerTimingTest` runs on the virtual clock. Knobs and faders report touch start/end via `StripActions.setTouching`.
- `SimulatorTransport(echo = true)` echoes every CC, standing in for the untested BLE/bridge routes.
- Dark theme by default (dim venues).

## Build order
Work in these milestones; each should build, run and pass tests before moving on. Milestones 1–5 are Ubuntu-only.

1. **Skeleton and dev setup:** `scripts/setup-ubuntu.sh` and `docs/dev-setup.md`; KMP project, desktop app launches on Ubuntu, Android debug APK runs in the emulator and installs on the tablet via `adb`, with `scrcpy` mirroring working.
2. **Model + CcCodec + SimulatorTransport** with unit tests (encode/decode, 7-bit ranges, map lookups).
3. **UI components:** Fader and RotaryKnob, then ChannelStrip and the full mixer screen, driven by the simulator on desktop.
4. **MixerController:** throttling and echo suppression, with tests using a virtual clock.
5. **Desktop USB transport** (`javax.sound.midi`): device list, name-pattern port selection, open/close, hot-plug by polling. On Windows and macOS, Java Sound often doesn't see newly plugged devices until the app restarts. If so, show a "replug detected, restart to connect" message rather than failing silently. *First hardware session: fill in `docs/hardware-notes.md`.*
6. **Wi-Fi bridge:** protocol with tests (loopback in-process), Bridge mode in the desktop app, `NetworkMidiTransport`, NSD discovery on Android. *First tablet sign-off: Android → Ubuntu bridge → L6max.* ✅ **Signed off on the real Galaxy Tab A11 (SM-X130, Android 16 / One UI 8, 1340×800 at 213 dpi) on 2026-10-17, through the Pi bridge:** found by discovery, PIN, all 8 strips + master fit in landscape; tablet → mixer, mixer → tablet, INSTANT MUTE, sound pads and SEND DESK all work. Link loss: ✅ the tablet's Wi-Fi switched off for ~10 s → "link lost", then reconnected by itself; walking out of range (a weak, patchy signal) still to try. Samsung's Auto Blocker must be off for USB debugging and sideloading; `adb tcpip 5555` + `adb connect <tablet IP>:5555` frees the cable until the tablet restarts.
7. **Android BLE-MIDI transport:** BLE scan and pairing via `MidiManager.openBluetoothDevice`, runtime Bluetooth permissions across API levels (pre- and post-Android 12), then USB-OTG. *Second sign-off: tablet → BLE dongle → L6max.* ✅ **USB-OTG signed off on the real tablet (2026-10-17):** auto-connects, both directions, pads, INSTANT MUTE, READ FROM MIXER, unplug and replug. BLE deferred until there's an adapter.
8. **CC map editor, Learn mode and profile persistence.**
9. **Headless bridge** module and `docs/bridge-setup.md` (systemd service and Wi-Fi hotspot setup on a Raspberry Pi-class board). ✅ Working on a real Pi Zero 2 W (Raspberry Pi OS trixie, Java 21): desktop app → Wi-Fi → Pi → USB → L6max, both directions, "feels responsive"; ~55 MB, ~0% CPU, no under-voltage. Needed: Wi-Fi power saving off (ping 22 ms avg / 5% loss → 3 ms / 0%); `pi-setup.sh install` does it. Keep the Pi's own USB driver (`dwc_otg`): the generic `dwc2` breaks USB audio (see hardware notes). Still to try: the fallback hotspot away from home.
10. **Packaging and polish:** icons, DEB (and MSI), and a signed release APK sideloaded onto the Galaxy Tab A11. ✅ Icons (art/l6desk-icon.svg → `:desktopApp:generateIcons`; Android adaptive + themed), one version in `gradle.properties` (`l6desk.version`), DEB with bundled runtime (verified), MSI configured (build on Windows), release signing via `scripts/make-release-key.sh` + `keystore.properties` (verified with a throwaway key; the release APK runs on the emulator), `scripts/release.sh` → `dist/`. See `docs/release.md`. Still to do: the real key, and sideloading onto the tablet.

Until the L6max is available, everything runs against `SimulatorTransport`. The bridge can also run with the simulator behind it, so the full tablet → Wi-Fi → bridge path can be tested without hardware.

## Conventions
- Kotlin official style; no `!!` in shared code.
- Unit tests in `core/src/commonTest` (kotlin.test + kotlinx-coroutines-test); Compose UI tests and screenshots in `shared/src/jvmTest`; bridge tests in `bridge/src/test`.
- Keep third-party dependencies minimal; ask before adding one outside the Kotlin/JetBrains/AndroidX ecosystems. (mDNS ended up needing none: see the bridge protocol section.)
- Record hardware findings and any MIDI surprises in `docs/hardware-notes.md`.
