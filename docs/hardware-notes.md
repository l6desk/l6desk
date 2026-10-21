# Hardware notes: Zoom LiveTrak L6max

Findings from testing against a real L6max, firmware 1.10, connected by USB to Ubuntu 24.04
(kernel 7.0). Each entry says how it was established. CLAUDE.md summarises these under
"L6max MIDI facts"; the details and evidence are here.

## USB MIDI ports

| Port | ALSA name (`aconnect -l`, `amidi -l`) | ALSA device | Java Sound name | Use |
|---|---|---|---|---|
| 1 | `L6max MIDI I/O Port` | `hw:4,0,0` | `L6max [hw:4,0,0]` | Passthrough to the TRS MIDI jacks |
| 2 | `L6max Mixer Control Port` | `hw:4,0,1` | `L6max [hw:4,0,1]` | **Mixing (CC in and out)** |
| 3 | `L6max for L6 Editor Port` | `hw:4,0,2` | `L6max [hw:4,0,2]` | ZOOM L6 Editor (SysEx); read-only use only |

- **Java Sound drops the port names.** All three appear as `L6max [hw:C,D,S]` with the same
  description (`L6max, USB MIDI, L6max`). Each port is listed twice: once as an output
  (`MidiOutDevice`) and once as an input (`MidiInDevice`). The ALSA sub-device `S` (0-based)
  matches the port order, so the Mixer Control Port is `S = 1`. The card number `C` (4 here) can
  change between boots. `L6maxPorts.portNumber()` handles this; see `L6maxPortsTest`.
  *(Found by listing `MidiSystem.getMidiDeviceInfo()`, 2026-10-07.)*
- Java Sound also lists its own `Gervill` synth and `Real Time Sequencer`. The transport
  ignores them.
- **Hot-plug works on Linux without restarting the app.** With the desktop app connected,
  unplugging the USB cable showed "…was unplugged" within one poll (2 s). Replugging
  reconnected automatically, and the device came back as `hw:4,0,1`.
  *(Desktop app, milestone 5, 2026-10-07.)* Windows and macOS are still untested; Java Sound
  is reported not to see new devices there until the app restarts.

- **On a Raspberry Pi Zero 2 W** (Raspberry Pi OS trixie, kernel 6.18): the L6max appears as
  `1686:08f5 ZOOM Corporation L6max`, with the same three port names, as card 1 (`hw:1,0,1` for
  Mixer Control); the card number differs from the Ubuntu PC's 4. An ordinary micro-USB data
  cable in the Pi's **USB** port works with the Pi's own driver (`dwc_otg`, host mode by default:
  `cil_force_host=Y`). **Don't use the generic `dwc2` driver** (`dtoverlay=dwc2,dr_mode=host`, which
  the bridge set-up used to add): MIDI worked, but USB audio playback broke up into crackles and
  near-silence after 50 s–2 min (the bridge kept ALSA's buffer full throughout, so it was lost on
  the USB link), and capture from the L6max failed with an I/O error. With `dwc_otg` a whole song
  came back cleanly on CH7's USB return, and capture works. *(2026-10-09.)* With the L6max on its own
  power, the Pi reported no under-voltage (`vcgencmd get_throttled` = 0x0).
  *(Headless bridge, milestone 9, 2026-10-08.)*
- **Bridge over Wi-Fi from the Pi:** the desktop app as client → Wi-Fi → Pi Zero 2 W → USB →
  L6max worked both ways and felt responsive. The Pi's Wi-Fi power saving (on by default) gave
  pings of 2–56 ms (22 ms average) with 5% loss; with it off: 1.4–10 ms (3.3 ms average), 0% loss.
  The bridge used ~55 MB and ~0% CPU.

## Mixer Control Port behaviour

- **The mixer sends a CC whenever one of its own controls moves.** Each message carries the
  new absolute value. Fast turns jump several steps per message (encoder acceleration).
  *(97-message capture with `amidi -d` while turning level/pan encoders and pressing mutes:
  `docs/hardware-captures/l6max-fw1.10-hardware-moves.txt`, also used as test data in
  `shared/src/commonTest/.../midi/HardwareCaptures.kt`.)*
- **MIDI channel 1** (the default "MIDI Channel" setting). Every message was `Bn` with n = 0.
- **Pan:** 0–127, centre 64. **Mute:** the mixer sends 127 = on, 0 = off, and accepts the same.
- **No echo:** CCs sent to the port take effect and are **not** sent back. *(Sent CH8 mute
  on/off and level with `amidi -S` while listening on the same port: nothing came back.)*
- **The channel controls are endless encoders with LED rings, not faders.** After the app sets
  a value, turning the encoder continues from it. *(App set CH8 level to 40; a slow turn then
  sent 43, 44 … 50.)* So there is no physical position to fall out of step with.
- **EFX TYPE is CC 117 with six values: 0, 21, 43, 64, 85, 107**, cycling in that order (and
  wrapping 107 → 0) as the type is stepped on the mixer. Expected mapping, from the device's
  type order and zoom-l6-companion: 0 AI Noise Reduction, 21 Hall, 43 Room, 64 Spring, 85 Delay,
  107 Echo. *(Captured through the Pi bridge as a read-only client, 2026-10-08; two full cycles.)*
- **The encoder-mode buttons send nothing.** Pressing LEVEL, PAN, SUB-MIX, AUX1, AUX2, EFX and the
  EQ select buttons (HIGH, FREQ, MID, LOW) produced no MIDI at all, and they aren't in the MIDI CC
  map, so the app can neither light them nor follow them. The app's SENDS toggles are independent
  of the mixer's buttons; the send **values** still sync both ways (an encoder turned in AUX1 mode
  sends the AUX1 CC). *(Read-only bridge capture, 2026-10-08.)*
- **Scene recall uses Program Change: scene A = 0, B = 1** (so presumably C = 2, D = 3). Recalling
  on the mixer sends `C0 00` / `C0 01`, and **nothing else**: no CCs for the recalled levels, pans,
  EQ or mutes. Sending Program Change 1 then 0 to the Mixer Control Port recalls scene B then A
  (confirmed by eye), with no echo. Recalling an *empty* scene sends nothing. *(Bridge captures,
  2026-10-08.)* Consequence: after any recall the app can't know the new mix.
- **Sound pads use MIDI notes on the profile's channel: pad 1–4 = notes 60, 62, 64, 65** (C4, D4, E4,
  F4; factory default, assignable in L6 Editor). Pressing a pad sends Note On velocity 100 (`90 3C 64`),
  releasing it sends Note Off velocity 64 (`80 3C 40`), even when the pad has no sound assigned. The
  mixer reports presses only, not playback starting or ending. *(Read-only bridge capture, 2026-10-09.)*
  - The four pad notes are also in the EditorOpen snapshot, at payload[122..125] (straight after the CC
    table; `3C 3E 40 41` in the day-one capture), so they can be read with the CC map.
  - **Sent notes play the pads.** Through the Pi bridge, a Note On/Off pair 0.1 s apart played a pad
    recorded on the mixer (REC + pad). In Loop play mode, one tap started the loop and the next Note On
    stopped it, the same as pressing the pad. A note to an empty pad does nothing (it doesn't start
    recording; that needs REC held on the mixer). *(2026-10-09.)*
  - **Play modes, played from the app** (Note On on finger down, Note Off on lift; desktop app via
    the Pi bridge, 2026-10-09):
    - **Loop:** one press starts, the next stops. ✅
    - **Hold:** plays from the start while held, looping if held longer than the sound; stops on
      release. ✅
    - **One-shot:** every press restarts the sound from the beginning, and **it can't be stopped over
      MIDI**. Holding the mixer's own pad does stop it; that long press sends Note On, a Note Off
      within about a second, then another Note Off on release (capture, 2026-10-09). Sending the
      app a held note, a lone Note Off while playing, or an exact copy of that long-press sequence all
      let it play to the end, so the stop is internal to the mixer.
    - So for anything that may need stopping mid-set, use Loop or Hold.
  - **INSTANT MUTE doesn't silence the pads.** A Loop pad kept playing with the app's INSTANT MUTE on
    (2026-10-09): it mutes the 8 channels, and the pads reach the main mix through the SOUND PAD
    knob, which isn't MIDI-controllable. The desk says so while INSTANT MUTE is on.
- **USB input switches: CC 113 = CH7 USB 1/2, CC 114 = CH8 USB 3/4; 127 = USB, 0 = the channel's
  own inputs.** Pressing the buttons on the mixer sends them. *(Bridge capture, 2026-10-09.)*
- **USB audio from the Pi:** the Pi (as USB host) sees the L6max as ALSA card 1 with a 4-channel
  48 kHz playback device (USB 1/2 + USB 3/4; S32_LE/FLOAT_LE natively; Java Sound's
  `L6max [plughw:1,0]` accepts 16/24/32-bit, 1–4 channels). A 44.1 kHz 16-bit stereo WAV,
  converted to 48 kHz by Java Sound and written as 4 channels with the song on 1/2, played on CH7
  (switched to USB 1/2) at the right speed and pitch, clean, both sides present, using 4% of one
  core on the Pi Zero 2 W. *(Test program on the Pi, 2026-10-09.)* So the Pi can be a backing-track
  player over the same USB cable that carries MIDI.
- **The current mix can't be read back.** Probed on the Editor port with read-only requests
  (Identity, EditorOpen, heartbeat `31 0B`, GetParam `46 <group> <index>`), 2026-10-08:
  - GetParam answers only groups 0–4: 0 = pad file assigned (`00 00`), 1 = a 505-byte blob (same
    for any index), 2 = pad file name (`7F 7F 00 00` = none), 3 = `00`, 4 = the 94-entry CC table.
    Groups 5–127: no reply.
  - None of them changed after turning CH1's level and muting CH2 on the mixer.
  - The EditorOpen settings snapshot was byte-identical to the day-one capture, despite the mix,
    the EFX type and two newly saved scenes having changed: it holds settings only.
  So the mixer's mix is only known as controls move (and as the app sets it).
- **Level curve: unknown.** Level 40 (31% of the range) lit about a quarter of the LED ring
  (9 o'clock), so the ring display or the level curve may not be linear.
- **End to end with the desktop app (milestone 5):** encoder turns and mute presses on the
  mixer moved the app's faders and mutes. The app's CH8 mute and fader controlled the mixer.
  The system's byte counters (`/proc/asound/card4/midi0`) confirmed traffic on sub-device 1
  only.

## Editor Port (read-only)

- **Identity Request** `F0 7E 00 06 01 F7` → `F0 7E 00 06 02 52 72 00 0D 00 30 31 31 30 F7`
  (Zoom, family 72 00, member 0D 00, firmware "0110" = 1.10).
- **EditorOpen** `F0 52 00 00 2B F7` → `F0 52 00 00 2A <176-byte payload> F7`, layout byte
  `0x2E`. The 94-entry CC table starts at payload offset 28. On this unit it matches the
  factory map exactly.
  *(Capture: `docs/hardware-captures/l6max-fw1.10-editor-open-reply.hex`.)*
- The app never sends editor *write* commands.

## The L6max itself

- **No solo function and no master mute**, and the MASTER knob can't be controlled over MIDI
  (manual, MIDI CC# Mapping parameter list). The app provides all three itself.
- **MONO ×2** (channels 5 and 6) accepts two mono sources, but they share level, EQ, pan and
  sends (manual), so it doesn't add channels.

## Still to verify

- Level curve (value → dB) and what values switch MONO ×2.
- What MONO ×2 does to panning (inputs kept left/right, or summed to the centre).
- Whether mixer-originated CCs also come out of the TRS MIDI OUT jack (route B), with
  MIDI Out Mode = Out.
- ~~Java Sound hot-plug on Windows.~~ ✅ Works (Surface Go 2, Windows 11, Zoom's L6max driver): unplugged and plugged back in, the L6max is listed again without restarting the app.

## Windows (Surface Go 2, Windows 11, 2026-10-14)

- Needs Zoom's **L6max driver** for Windows. Without a working USB link, neither L6Desk nor ZOOM L6
  Editor sees the mixer: a USB-C adapter on the Surface failed; a direct USB-C to USB-C cable works.
- Java Sound names a port's input and output differently. Seen: inputs `ZOOM L6max`,
  `MIDIIN2 (ZOOM L6max)`, `MIDIIN3 (ZOOM L6max)`; outputs `ZOOM L6max`, `MIDIOUT2 (ZOOM L6max)`,
  `MIDIOUT3 (ZOOM L6max)`. The app pairs them (`pairKey` in `JavaSoundMidiTransport`), so the Mixer
  Control port is `MIDI2 (ZOOM L6max)`. Faders work both ways over USB.
- Audio: Java Sound (DirectSound) offers the L6max as **`Line (ZOOM L6max Audio)`, stereo only**
  (Java drives at most 2 channels per device on Windows). So on Windows the player uses USB 1/2
  (CH7) only (`JavaSoundPcmOutput` stereo mode, `PlayerState.usbPairs = 1`). Windows' own volume for
  that device applies on top: keep it at 100 %.
- **But it can't be opened.** Zoom's driver fixes the device's Windows format at "4 channel, 24 bit,
  48000 Hz" (the Default Format list is greyed out). Windows' own programs convert to that (the
  Test tone plays on CH7), but Java Sound's DirectSound refused every stereo format tried
  (48/44.1/96 kHz × 16/24/32 bit): 16-bit opens failed in the driver (`LineUnavailableException:
  line with format … not supported`), and 24/32-bit aren't offered by Java's DirectSound at all
  (`IllegalArgumentException: Line unsupported`). Report ETBGAPXC. So the desktop's own player is turned off on Windows (no
  PLAYER with the L6max on USB); the Pi bridge's player works. A fix would need WASAPI (native).
- Reports list every MIDI device Java Sound sees (`midiDevices`), so an unrecognised name shows up.

## The memory card in file transfer mode (2026-10-15, fw 1.10)

- Switched on in the L6max's own menu. It re-enumerates as USB `1686:08f4` (normally `08f5`), a
  mass-storage device "ZOOM L6max SD R&W", and still offers its MIDI ports. It doesn't mix or record
  meanwhile.
- The card (16 GB here) is **FAT32, label `ZOOM_SD`**, but its boot sector has no jump code and a
  blank OEM name, so `blkid` doesn't recognise it and Ubuntu won't mount it (Windows does). The
  kernel mounts it fine with `-t vfat`. The `.deb` installs a udev rule
  (`desktopApp/linux/99-l6desk-zoom-sd.rules`) that marks it vfat, so it mounts like any card
  (`/media/<user>/ZOOM_SD`). Mounting can need a replug after the mode switch.
- Layout: `ZOOM_L6max.sys` ("ZOOM SYSTEM FILE", 2 KB), `RECORDER/` (recordings; empty here),
  `SOUND_PAD/PAD1`–`PAD4`, and `SOUND_PAD/L6MAXPADSETTING.zst`.
- Pad sounds recorded on the mixer are named `YYMMDD_HHMMSS.wav`: 32-bit float, 48 kHz stereo, with
  a BWF `bext` chunk ("ZOOM L6max").
- `L6MAXPADSETTING.zst` isn't zstd: a UTF-16LE header "ZOOM L6MAX  PAD SETTING VER0001", then one
  522-byte block per pad from 0x60, starting with the pad's file (`A:\SOUND_PAD\PAD1\x.wav`) or
  "NO ASSIGN"; more settings follow the four blocks (probably play modes and levels).
- **A pad doesn't simply play the first file alphabetically** (as first thought): it plays its
  assigned file. Tested: (1) a 16-bit 48 kHz WAV and a 32-bit float WAV, each put in an unassigned
  pad's folder, both played, and the mixer then recorded them as the pads' files; (2) with PAD1's
  assigned file removed and a new WAV in its folder, the pad played the new file, and the mixer
  reassigned it. So an app sets a pad's sound by leaving just the new file in the folder.
- ✅ PAD SOUNDS in the app (2026-10-16): a whole 3 min 9 s song (MP3 → 16-bit 48 kHz WAV, 36 MB)
  on pad 4 plays; no length limit met. The first try left a 0-byte file: the mixer left file transfer
  mode (USB disconnect) while Linux still had writes pending ("lost async page write"). The app now
  runs `sync -f` on the card after each change and says to EJECT before switching the mode off.
- ✅ PAD SOUNDS through the Pi bridge (2026-10-16): with the L6max on the Pi's USB in file transfer
  mode, the Pi still talks to it over MIDI, its udev rule mounts the card at `/run/l6desk-card`,
  and the bridge service (ProtectSystem=strict, started before the card appeared) sees and writes
  the mount without extra ReadWritePaths. Pad 4 set to a library song ("Because"), ejected through
  the root helper, and it plays once the mixer leaves file transfer mode.

## Recordings on the card (2026-10-16, fw 1.10)

A 6 min 26 s test recording (vocal on CH1, a stereo backing track on CH5), looked at read-only
through the Pi's mount, before and after a bounce:
- One folder per recording in `RECORDER/`, named `YYMMDD_HHMMSS` (when recording started):
  `RECORDER/261016_104237/`.
- **Every track is recorded, used or not:** `TRACK01_01.wav`–`TRACK04_01.wav` (mono) and
  `TRACK05_ST_01.wav`–`TRACK08_ST_01.wav` (stereo), plus `MASTER_01.wav` (stereo), even with nothing
  on CH2–4 and CH6–8 (their sampled peaks were −50 to −87 dBFS, CH7 digital silence). The `_01` is
  presumably the take. So the app shouldn't assume a file means a used track; the level does.
- All **32-bit float, 48 kHz** (format 3): mono 192 000 B/s, stereo 384 000 B/s, so about 11.5 MB
  a minute per mono track and 23 MB per stereo one. This one: 74 MB per mono track, 148 MB per
  stereo track and the master, ~1.1 GB in all.
- Header: `RIFF`/`WAVE`, `fmt ` (16 bytes), then a `PAD ` chunk of 858 zero bytes (pads the
  audio to 910 bytes from the start), then `data`. No `bext` here, unlike recorded pad sounds.
  All files of a recording are exactly the same length.
- `MASTER_01.wav` is recorded alongside the tracks (it isn't a bounce): here it peaked at only
  −33.7 dBFS (RMS −52) against the vocal track's −2.8 dBFS, so it seems to follow a level after
  the channels (the master knob, or the monitor level?) — to check.
- `L6MAXPRJDATA.prj` (3388 bytes): header "ZOOM L6MAX   PROJECT DATA VER0001", then per-track
  mixer settings (values 0x40 = centre/flat, as on the mixer) in two blocks (from 0x32 and from
  0x29a). Read only if ever needed; never written.
- **A bounce adds a new master take** in the same folder: `MASTER_02.wav` (written 15 minutes
  after the recording; nothing else changed except the `.prj`). Same format and exactly the same
  length as the tracks (32-bit float 48 kHz stereo, 6 min 26 s), but a **plain 44-byte header**
  (no `PAD ` chunk), so it's 866 bytes smaller. `MASTER_01.wav` is kept. So the newest master is
  the highest `MASTER_nn`; an app should offer that one (and say which take it is), not assume `_01`.
  This bounce peaked at −23.1 dBFS (RMS −41.5), 10 dB above the recorded master but still low:
  both follow the mixer's levels at the time, so a fetched master may want normalising.
- After the bounce the `.prj` changed: the 32-bit values at 0x28c and 0x290 went from 1 to 2
  (number of master takes, and the current one), and the second settings block, blank before,
  now holds the same pattern as the first (probably the playback mix used for the bounce). Values
  23 and 59 (0x17, 0x3b) appeared at 0x150 and 0x3b8: not understood.
- **32-bit float keeps overs:** the vocal track (CH1) peaks at **+13 dBFS** (4.46; 1 529 samples
  over full scale), so the recording isn't clipped, but a 24-bit copy would be. RECORDINGS counts
  clipped samples in a 24-bit download and says NORMALISE (or 32-bit float) keeps them whole.
  Normalised to −1 dBFS, both CH1 and the master checked at −1.0 dB with ffmpeg; CH5 came down by
  the same 14 dB (shared gain). Checking ~4 MB per file for "has sound" took 6 s for this
  recording over USB; the card reads at about 10 MB/s (148 MB master in 15 s).
- ✅ Through the Pi bridge (2026-10-17): the Pi reads the card at about 8.5 MB/s (371 MB to find
  the peaks for normalising in 43 s) and sends over its 2.4 GHz Wi-Fi at 2–3 MB/s: used tracks,
  32-bit, normalised (370 MB) took 180 s plus that reading. MIDI to the mixer during a download
  didn't slow it. The first attempt lost the link part-way; it couldn't be reproduced, and the
  likely cause (the app swapping a found bridge that missed discovery rounds for the simulator)
  was fixed.

## The Galaxy Tab A11 and the L6max over USB-OTG (2026-10-17)

- Galaxy Tab A11 (SM-X130), Android 16 / One UI 8, 1340×800 at 213 dpi (1006×600 dp): the same as
  the emulator profile used so far. Samsung's **Auto Blocker** has to be off for USB debugging and
  for installing APKs from outside the stores.
- A USB-C cable from the tablet to the L6max: the tablet is the USB host and powers nothing extra.
  Android's MIDI service lists **"ZOOM CORPORATION L6max"** (USB 1686:08f5) with **3 input and 3
  output ports**, and the device also offers USB audio playback and capture (ALSA card 1). Port
  order matches the ALSA sub-devices on Ubuntu: 1 = MIDI I/O, 2 = Mixer Control, 3 = Editor.
  No USB permission prompt: MidiManager gives access on its own.
- Verified: mixing both ways, sound pads, INSTANT MUTE, READ FROM MIXER (Editor port, the factory
  map), unplug and replug (reconnects by itself).
