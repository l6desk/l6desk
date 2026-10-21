#!/usr/bin/env bash
# Copies songs from this computer into a playlist on the Pi bridge's music player.
#   scripts/music-pi.sh USER@HOST PLAYLIST FILE_OR_FOLDER...
# e.g. scripts/music-pi.sh pi@l6desk-bridge.local "The Beatles" ~/Music/Beatles/*.mp3
# WAV, MP3, AAC (.m4a) and FLAC; the bridge converts them to WAV and adds them to the end of the
# playlist in name order (a new playlist is made if needed). Reorder or delete them in the app.
# The app's PLAYER → UPLOAD does the same from the desktop or the tablet; this is handy for a whole
# folder at once.
set -euo pipefail

target="${1:-}"
playlist="${2:-}"
[[ -n "$target" && -n "$playlist" && $# -ge 3 ]] || { sed -n '2,8p' "$0"; exit 2; }
shift 2
[[ "$playlist" != */* && "$playlist" != .* ]] || { echo "A playlist name can't contain / or start with ." >&2; exit 2; }

files=()
for arg in "$@"; do
  if [[ -d "$arg" ]]; then
    while IFS= read -r -d '' f; do files+=("$f"); done < <(find "$arg" -maxdepth 1 -type f \
      \( -iname '*.wav' -o -iname '*.mp3' -o -iname '*.m4a' -o -iname '*.aac' -o -iname '*.flac' \) -print0 | sort -z)
  elif [[ -f "$arg" ]]; then
    files+=("$arg")
  else
    echo "No file or folder $arg" >&2; exit 1
  fi
done
[[ ${#files[@]} -gt 0 ]] || { echo "No music files found" >&2; exit 1; }

# The playlist folder is shared with the bridge (group-writable, like the ones it makes).
ssh "$target" "mkdir -p -m 2775 /srv/l6max-music/$(printf %q "$playlist")"
# rsync writes each file under a hidden name and renames it when complete, so the bridge never
# picks up half a file. Group-writable, so the bridge can number and convert them.
rsync -t --progress --chmod=D2775,F664 "${files[@]}" "$target:/srv/l6max-music/$playlist/"
echo "Copied ${#files[@]} file(s) to \"$playlist\". The bridge adds them within a few seconds (longer for MP3/FLAC, which it converts)."
