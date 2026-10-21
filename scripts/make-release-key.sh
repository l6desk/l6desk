#!/usr/bin/env bash
# Creates the Android release signing key, once. The keystore lives OUTSIDE the repository
# (default ~/.android/l6desk-release.jks) and keystore.properties (git-ignored) points at it.
#
# Keep the keystore and its password safe and backed up: every update to the app on the tablet
# must be signed with the same key, or Android refuses to install it over the old one.
#   scripts/make-release-key.sh [KEYSTORE_PATH]
set -euo pipefail
cd "$(dirname "$0")/.."

store="${1:-$HOME/.android/l6desk-release.jks}"
alias=l6desk
if [[ -e "$store" ]]; then echo "$store already exists; not overwriting it." >&2; exit 1; fi
mkdir -p "$(dirname "$store")"

read -r -s -p "Choose a keystore password (6+ characters): " pass; echo
read -r -s -p "Repeat it: " pass2; echo
[[ "$pass" == "$pass2" && ${#pass} -ge 6 ]] || { echo "Passwords don't match or are too short." >&2; exit 1; }

keytool -genkeypair -keystore "$store" -storetype PKCS12 -alias "$alias" \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$pass" -keypass "$pass" \
  -dname "CN=L6Desk"
chmod 600 "$store"

umask 077
cat > keystore.properties <<PROPS
# Android release signing (git-ignored). The keystore itself: $store
storeFile=$store
storePassword=$pass
keyAlias=$alias
keyPassword=$pass
PROPS
echo "Created $store and keystore.properties. Back up the keystore and remember the password."
