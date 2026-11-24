#!/usr/bin/env bash
# Prints the Chrome extension pairing code of the running driver (host:port#token).
set -euo pipefail
FILE="${CDB_CONNECTION_FILE:-$HOME/.chromium-dev-bridge/driver.json}"
[[ -f "$FILE" ]] || { echo "Driver is not running (no $FILE)" >&2; exit 1; }
python3 - "$FILE" <<'PY'
import json, sys
info = json.load(open(sys.argv[1]))
print(f"127.0.0.1:{info['port']}#{info['extensionToken']}")
PY
