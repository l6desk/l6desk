#!/usr/bin/env bash
# Publishes dist/<version>/ (made by scripts/release.sh) as a GitHub release of the public downloads
# repository, github.com/l6desk/l6desk, as the project account. Run on its own when the release
# has been tested; it never changes which gh account is active (other projects use the main one).
#   scripts/publish-release.sh [VERSION]      default: l6desk.version in gradle.properties
#   scripts/publish-release.sh windows VERSION add dist/VERSION-windows/ (the Windows build, see
#                                             docs/release.md) to an existing release, with its notes
# Needs: gh auth login, once, as the project account (then gh auth switch back to your own).
set -euo pipefail
cd "$(dirname "$0")/.."

repo=l6desk/l6desk
account=l6desk-releases

if [[ "${1:-}" == windows ]]; then
  version="${2:?Usage: $0 windows VERSION}"
  win=dist/$version-windows
  [[ -d "$win" ]] || { echo "No $win: download the Windows build first (docs/release.md)" >&2; exit 1; }
  (cd "$win" && sha256sum -c --quiet SHA256SUMS-windows)
  GH_TOKEN=$(gh auth token --user "$account") || { echo "Log gh in as $account first: gh auth login" >&2; exit 1; }
  export GH_TOKEN
  gh release upload "v$version" --repo "$repo" --clobber $(find "$win" -maxdepth 1 -type f ! -name NOTES.md | sort)
  if [[ -f "$win/NOTES.md" ]]; then
    notes=$(mktemp)
    gh release view "v$version" --repo "$repo" --json body --jq .body > "$notes"
    grep -q "^## Windows" "$notes" || { echo; cat "$win/NOTES.md"; } >> "$notes"
    gh release edit "v$version" --repo "$repo" --notes-file "$notes"
    rm -f "$notes"
  fi
  echo "Added the Windows build to https://github.com/$repo/releases/tag/v$version"
  exit 0
fi

version="${1:-$(grep '^l6desk.version=' gradle.properties | cut -d= -f2)}"
dir=dist/$version
[[ -d "$dir" ]] || { echo "No $dir: run scripts/release.sh first" >&2; exit 1; }
(cd "$dir" && sha256sum -c --quiet SHA256SUMS)
if ls "$dir"/*-unsigned.apk >/dev/null 2>&1; then echo "$dir has an unsigned APK; not publishing it" >&2; exit 1; fi

# This account's login for these commands only (the active gh account stays as it is).
GH_TOKEN=$(gh auth token --user "$account") || { echo "Log gh in as $account first: gh auth login" >&2; exit 1; }
export GH_TOKEN

notes=$(mktemp)
{
  echo "L6Desk $version: a remote control for the Zoom LiveTrak L6max mixer."
  echo
  echo "Start with **README.md**; **INSTALL.md** says which file is which and how to install it,"
  echo "**USER-GUIDE.md** how to use it. Check downloads with \`sha256sum -c SHA256SUMS\`."
  if [[ -f "$dir/NOTES.md" ]]; then echo; cat "$dir/NOTES.md"; fi
} > "$notes"
win=dist/$version-windows
if [[ -d "$win" ]]; then
  (cd "$win" && sha256sum -c --quiet SHA256SUMS-windows)
  if [[ -f "$win/NOTES.md" ]]; then { echo; cat "$win/NOTES.md"; } >> "$notes"; fi
fi
gh release create "v$version" --repo "$repo" --title "L6Desk $version" --notes-file "$notes" \
  $(find "$dir" -maxdepth 1 -type f ! -name NOTES.md | sort) \
  $( [[ -d "$win" ]] && find "$win" -maxdepth 1 -type f ! -name NOTES.md | sort)
rm -f "$notes"
echo "Published: https://github.com/$repo/releases/tag/v$version"
