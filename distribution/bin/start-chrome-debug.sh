#!/usr/bin/env bash
# Starts Chrome/Chromium with remote debugging on 127.0.0.1:9222 and a dedicated profile ("quick attach").
set -euo pipefail
PORT="${1:-9222}"
PROFILE="$HOME/.chromium-dev-bridge/chrome-debug-profile"
for c in google-chrome google-chrome-stable chromium chromium-browser "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"; do
  if command -v "$c" >/dev/null 2>&1 || [[ -x "$c" ]]; then CHROME="$c"; break; fi
done
[[ -n "${CHROME:-}" ]] || { echo "Chrome not found" >&2; exit 1; }
mkdir -p "$PROFILE"
"$CHROME" --remote-debugging-port="$PORT" --remote-debugging-address=127.0.0.1 --user-data-dir="$PROFILE" --no-first-run >/dev/null 2>&1 &
echo "Chrome started with remote debugging on http://127.0.0.1:$PORT"
