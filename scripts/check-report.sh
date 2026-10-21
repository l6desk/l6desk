#!/usr/bin/env bash
# Checks a problem report zip downloaded from a GitHub issue before anyone opens it: only the
# expected files, within size limits, well-formed, and signed (docs/report-issue-plan.md).
# If it passes and an out-dir is given, it's extracted to <out-dir>/<report id>/.
#
#   scripts/check-report.sh ~/Downloads/l6desk-report-ABCD2345.zip [out-dir]
#
# Exit code: 0 passed, 1 rejected, 2 usage or I/O error. Never unzip a report by hand.
set -euo pipefail
[[ $# -ge 1 && $# -le 2 ]] || { echo "Usage: $0 <report.zip> [out-dir]" >&2; exit 2; }
zip=$(realpath "$1")
out=()
if [[ $# -eq 2 ]]; then mkdir -p "$2"; out=("$(realpath "$2")"); fi
cd "$(dirname "$0")/.."
classpath=$(./gradlew -q --console=plain :desktopApp:printToolsClasspath | tail -n 1)
exec java -cp "$classpath" io.github.l6desk.tools.CheckReportKt "$zip" "${out[@]}"
