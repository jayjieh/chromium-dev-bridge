#!/usr/bin/env bash
# Prints the Chrome extension pairing code of the running driver.
set -euo pipefail
FILE="${CDB_CONNECTION_FILE:-$HOME/.chromium-dev-bridge/driver.json}"
[[ -f "$FILE" ]] || { echo "The driver is not running. Start it with bin/start-driver.sh" >&2; exit 1; }
PORT="$(sed -nE 's/.*"port"[^0-9]*([0-9]+).*/\1/p' "$FILE" | head -1)"
TOKEN="$(sed -nE 's/.*"extensionToken"[^"]*"([^"]+)".*/\1/p' "$FILE" | head -1)"
echo "127.0.0.1:$PORT#$TOKEN"
