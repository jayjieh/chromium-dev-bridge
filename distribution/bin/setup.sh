#!/usr/bin/env bash
# Chromium Developer Bridge — one-time setup (macOS/Linux).
set -euo pipefail
HOME_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if ! command -v java >/dev/null 2>&1; then
  echo "Java was not found. Install Java 17 or later (e.g. https://adoptium.net) and run setup again." >&2; exit 1
fi
VER_LINE="$(java -version 2>&1 | head -1)"
MAJOR="$(echo "$VER_LINE" | sed -E 's/.*"([0-9]+).*/\1/')"
if [[ "$MAJOR" =~ ^[0-9]+$ ]] && (( MAJOR < 17 )); then echo "Java 17 or later is required; found: $VER_LINE" >&2; exit 1; fi
mkdir -p "$HOME/.chromium-dev-bridge"
cat > "$HOME/.chromium-dev-bridge/install.json" <<JSON
{ "home": "$HOME_DIR", "driverJar": "$HOME_DIR/lib/cdb-driver.jar", "mcpJar": "$HOME_DIR/lib/cdb-mcp-server.jar", "version": "$(cat "$HOME_DIR/VERSION")" }
JSON
chmod +x "$HOME_DIR"/bin/*.sh
cat <<EOF

Chromium Developer Bridge is set up ($VER_LINE).

Next steps:
  1. Start the driver:            bin/start-driver.sh        (AI tools can also start it for you)
  2. IntelliJ plugin:             Settings > Plugins > gear > Install Plugin from Disk > intellij/*.zip
  3. Chrome extension:            chrome://extensions > Developer mode > Load unpacked > chrome-extension folder
     Pairing code:                bin/pairing-code.sh
  4. Copilot / Claude / Codex:    bin/mcp-config.sh copilot-intellij
                                  (clients: copilot-intellij, copilot-vscode, copilot-cli, claude-code, codex)

EOF
