# Contributing to L6Desk

Thanks for wanting to help. L6Desk is a small project with one main purpose: mixing a live gig
from a tablet. When in doubt, favour what keeps a show running: robust links, fast recovery,
instant mute, state you can read at a glance, and nothing that can go wrong mid-set.

## Reporting problems

Open an [issue](https://github.com/l6desk/l6desk/issues). The app's **REPORT** button makes a zip
with the details needed; drag it onto the issue. Say which L6max firmware version you have, if you
know it, and how the app reaches it (USB, the Pi bridge, or the desktop's Bridge mode).

**Security problems** (for example in the bridge's network protocol or the problem-report checker):
please report them privately, through the repository's **Security → Report a vulnerability**,
not in a public issue.

## Changing the code

1. Set up Ubuntu as in [docs/dev-setup.md](docs/dev-setup.md) (`scripts/setup-ubuntu.sh`).
2. Read [CLAUDE.md](CLAUDE.md): the project brief, with the decisions already made, the
   architecture and the confirmed facts about the L6max. Please don't re-open settled decisions
   without a good reason, and say what it is.
3. Make the change with tests. `./gradlew check` must pass and stay quick (well under a minute):
   - unit tests in `core/src/commonTest` (the model, MIDI, controller timing on a virtual clock,
     the bridge protocol);
   - Compose UI tests and screenshot renders in `shared/src/jvmTest`
     (`build/screenshots/` shows how the screens look, with no window opened);
   - bridge tests in `bridge/src/test`.
4. Try it: `./gradlew :desktopApp:run` (with the simulator, or a real L6max on USB), and
   `./gradlew :androidApp:installDebug` for the emulator or a tablet.
5. Open a pull request saying what changed, why, and how it was tested, including on real hardware
   if you have it.

### Ground rules

- **Dependencies:** keep them few. Anything outside the Kotlin, JetBrains and AndroidX ecosystems
  needs discussing in an issue first.
- **Never hard-code CC numbers.** The user can reassign them in ZOOM L6 Editor; the factory map is
  a default profile.
- **The L6max's Editor port is read-only** for L6Desk: never send it editor write commands.
- **Hardware findings** (anything learned from a real L6max) go in
  [docs/hardware-notes.md](docs/hardware-notes.md), marked as verified, with the firmware version.
- **Bluetooth MIDI** (milestone 7's first half) hasn't been built for want of an adapter. If you
  have a BLE-MIDI adapter for the L6max's MIDI jacks and want to work on it, please say so in an
  issue first.
- Kotlin official style; no `!!` in shared code. Touch targets at least 48 dp.
- Write user-facing text in plain words, and update the user guide
  ([docs/dist/USER-GUIDE.md](docs/dist/USER-GUIDE.md)) when behaviour changes.

## Licence

L6Desk is licensed under the GNU General Public License v3 ([LICENSE](LICENSE)). By contributing,
you agree that your contribution is licensed under it too.
