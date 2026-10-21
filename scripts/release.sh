#!/usr/bin/env bash
# Builds release packages into dist/: the Ubuntu .deb, the Android APK (signed if
# keystore.properties exists; see scripts/make-release-key.sh), the Pi bridge pack, and the
# README, INSTALL and USER-GUIDE documents (from docs/dist/).
# The Windows .msi has to be built on Windows: gradlew.bat :desktopApp:packageMsi
set -euo pipefail
cd "$(dirname "$0")/.."

version=$(grep '^l6desk.version=' gradle.properties | cut -d= -f2)
./gradlew --console=plain check :desktopApp:packageDeb :androidApp:assembleRelease :bridge:bridgeDist

out=dist/$version
rm -rf "$out" && mkdir -p "$out"
# The app was called L6max Remote (package l6max-remote) before 0.13: mark the .deb as replacing it,
# so installing it removes the old package instead of leaving two apps in the menu.
deb=$(ls desktopApp/build/compose/binaries/main/deb/*.deb | head -1)
work=$(mktemp -d)
dpkg-deb -R "$deb" "$work/pkg"
sed -i '/^\s*$/d' "$work/pkg/DEBIAN/control" # jpackage leaves a blank line at the end
printf 'Conflicts: l6max-remote\nReplaces: l6max-remote\n' >> "$work/pkg/DEBIAN/control"
# The udev rule that lets Ubuntu mount the L6max's card in file transfer mode (PAD SOUNDS; see
# desktopApp/linux/99-l6desk-zoom-sd.rules), reloaded when installed and removed.
install -D -m 644 desktopApp/linux/99-l6desk-zoom-sd.rules "$work/pkg/usr/lib/udev/rules.d/99-l6desk-zoom-sd.rules"
sed -i 's|^xdg-desktop-menu install .*|&\n    udevadm control --reload-rules 2>/dev/null \|\| true|' "$work/pkg/DEBIAN/postinst"
sed -i 's|^    purge\|remove\|.*)$|&\n        udevadm control --reload-rules 2>/dev/null \|\| true|' "$work/pkg/DEBIAN/postrm"
dpkg-deb --root-owner-group -b "$work/pkg" "$out/$(basename "$deb")" >/dev/null
rm -rf "$work"
# Pi pack: jar + service + config + pi-setup.sh. On the Pi: tar xzf …; sudo ./l6desk-bridge/pi-setup.sh install
tar -czf "$out/l6desk-bridge-$version-pi.tar.gz" -C bridge/build/dist l6desk-bridge
apk=$(ls androidApp/build/outputs/apk/release/*.apk | head -1)
if [[ "$apk" == *unsigned* ]]; then
  cp "$apk" "$out/l6desk-$version-unsigned.apk"
  echo "NOTE: the APK is unsigned (no keystore.properties). Run scripts/make-release-key.sh first." >&2
else
  "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/37.0.0/apksigner verify "$apk"
  cp "$apk" "$out/l6desk-$version.apk"
fi
# README, installation instructions and user guide, with this version filled in.
for doc in README INSTALL USER-GUIDE; do
  # Pictures: relative links on GitHub; in the release's copy, the same files under its tag.
  sed -e "s/{{VERSION}}/$version/g" \
      -e "s#](\.\./images/#](https://raw.githubusercontent.com/l6desk/l6desk/v$version/docs/images/#g" \
      -e "s#src=\"\.\./images/#src=\"https://raw.githubusercontent.com/l6desk/l6desk/v$version/docs/images/#g" \
      "docs/dist/$doc.md" > "$out/$doc.md"
done
(cd "$out" && sha256sum * > SHA256SUMS)
echo "Release $version:"; ls -la "$out"
