# Development setup (Ubuntu 24.04)

All development, testing and QA happens on Ubuntu. The Galaxy Tab A11 is used for sign-off and
live use. Android Studio is **not** required. If it's installed (e.g. the snap), it shares the
same SDK and does no harm.

## 1. Run the setup script

```sh
scripts/setup-ubuntu.sh --check   # see what's missing; changes nothing
scripts/setup-ubuntu.sh           # install it (asks for your sudo password)
source ~/.profile                 # or log out and back in
```

It's safe to re-run: each step checks first and skips what's already in place. Use `--no-sudo` to
skip the steps that need sudo.

| Step | What | Where |
|---|---|---|
| 1 | `openjdk-17-jdk`, `unzip`, `curl`, `git`, `alsa-utils`, `android-sdk-platform-tools-common` (udev rules so `adb` can see USB devices) | apt (sudo) |
| 2 | Add you to the `kvm` group for the emulator. Your desktop session already has access; the group makes it work over SSH and in other sessions too. Takes effect at next login. | sudo |
| 3 | Android SDK **command-line tools** (`sdkmanager`, `avdmanager`), latest release | `~/Android/Sdk/cmdline-tools/latest` |
| 4 | SDK packages: platform-tools, emulator, build-tools 37.0.0, platform android-37.0 (the app's compileSdk), API 35 tablet system image | `~/Android/Sdk` |
| 5 | Emulator `Tab_A11_API35`: 1340×800 landscape, Android 15, like the Galaxy Tab A11 | `~/.android/avd` |
| 6 | `scrcpy`, the official static build. Ubuntu's apt version (1.25) is too old for Android 15. | `~/.local/opt/scrcpy` |
| 7 | `ANDROID_HOME`, `PATH` and `ADB` added to `~/.profile` | `~/.profile` |

**JDK:** 17 builds the project (Kotlin 2.4, Compose Multiplatform 1.11, AGP 9.1, Gradle 9.6).

**SDK tool change:** from cmdline-tools 23, `sdkmanager` is deprecated and hands off to
`android sdk …`. The new tool ignores `ANDROID_HOME` (pass `--sdk=$ANDROID_HOME`) and names packages
with `/`, e.g. `android --sdk=$ANDROID_HOME sdk install platforms/android-37.0`. The script handles
both.

The emulator's screen density (213 dpi) is a first guess. Once the real tablet is connected, run
`adb shell wm size` and `adb shell wm density`. If they differ, update `AVD_DENSITY` in the script,
delete the emulator (`avdmanager delete avd -n Tab_A11_API35`) and re-run.

## 2. Prepare the Galaxy Tab A11

1. **Developer options:** *Settings → About tablet → Software information*, then tap **Build number**
   seven times.
2. **USB debugging:** *Settings → Developer options → USB debugging* on.
3. Connect the tablet to the PC by USB, then accept the "Allow USB debugging?" prompt on the
   tablet (tick *Always allow*).
4. Check that `adb devices` lists it as `device` (not `unauthorized`).

**Wireless debugging** (no cable, so the tablet's USB port stays free for the L6max). The tablet
and PC must be on the same network.

1. *Developer options → Wireless debugging* on → **Pair device with pairing code**.
2. `adb pair <ip>:<pairing-port>`, then enter the code.
3. `adb connect <ip>:<port>`, using the port shown on the Wireless debugging screen (not the
   pairing port).

## 3. Everyday tools

| Task | Command |
|---|---|
| List devices | `adb devices` |
| Start the tablet emulator | `emulator -avd Tab_A11_API35 &` |
| Mirror and control the real tablet on the desktop | `scrcpy` (add `--stay-awake`, or `-s <serial>` if several devices) |
| App logs | `adb logcat --pid=$(adb shell pidof -s <app id>)` |
| L6max MIDI ports | `amidi -l`, `aconnect -l` |
| Watch L6max mixer messages | `amidi -p hw:<card>,0,1 -d` (card number from `amidi -l`) |

The emulator's own toolbar can rotate it, take screenshots and simulate the network. The
emulator **can't** test USB-OTG or Bluetooth MIDI; those need the real tablet (see the test layers
in `CLAUDE.md`).

## 4. Project commands

Added as each milestone lands:

| Command | Purpose | Milestone |
|---|---|---|
| `./gradlew check` | Unit and Compose UI tests (JVM), Android lint | 1 ✅ |
| `./gradlew :desktopApp:run` | Desktop app (simulator for now, USB from milestone 5) | 1 ✅ |
| `./gradlew :androidApp:installDebug` | Build and install "L6Desk debug" (`io.github.l6desk.debug`, beside the release) on the emulator or tablet | 1 ✅ |
| `scripts/dev-desktop.sh` | Desktop dev loop | 3 ✅ |
| `scripts/dev-tablet.sh [-s SERIAL] [--no-scrcpy]` | Build, install, launch, `scrcpy`, tail logcat | 3 ✅ |
| `./gradlew :bridge:run --args="--no-led"` | Headless bridge on this computer (add `--simulator` to run without the L6max) | 9 ✅ |
| `scripts/deploy-pi.sh USER@HOST [--hotspot SSID PASS]` | Build the bridge and install it on a Raspberry Pi (see `docs/bridge-setup.md`) | 9 ✅ |
| `./gradlew :shared:jvmTest --tests '*ScreenshotRender*'` | Render the desk offscreen to `shared/build/screenshots/` (tablet landscape/portrait, desktop) | 3 ✅ |
| `scripts/bridge-sim.sh [PORT]` | Wi-Fi bridge with the simulator behind it; the emulator reaches it at 10.0.2.2 (see `docs/bridge-setup.md`) | 6 ✅ |

## App data

The desktop app keeps its desk, CC-map profiles and settings in `~/.config/l6desk/`
(`desk.json`, `profiles.json`, `settings.json`). Delete the folder to start fresh. On Android
they're in the app's private storage; *Settings → Apps → L6Desk → Storage → Clear data*
resets them.

## Troubleshooting

- **`adb devices` shows `no permissions`:** the udev rules (step 1) aren't active yet. Run
  `sudo udevadm control --reload-rules`, then unplug and replug.
- **Two adb versions fighting** ("adb server version doesn't match"): something else started its
  own adb. Make sure `which adb` points into `~/Android/Sdk/platform-tools`. `ADB` in `~/.profile`
  makes scrcpy use the same one.
- **Emulator has no network** (`adb shell ip -4 addr` shows only 127.0.0.1, so the bridge is
  unreachable): `adb shell svc wifi enable && adb shell cmd wifi connect-network AndroidWifi open`.
- **Emulator is very slow or says KVM isn't available:** check `ls -l /dev/kvm` and `groups`, then
  log out and back in after step 2.
- **The L6max isn't in `amidi -l`:** check that it's powered on, and use a data USB-C cable (some
  are charge-only).
