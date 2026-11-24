#!/usr/bin/env bash
# Prints the MCP configuration for an AI client with this bundle's paths filled in.
#   bin/mcp-config.sh copilot-intellij
# Clients: copilot-intellij, copilot-vscode, copilot-cli, claude-code, codex
set -euo pipefail
JAR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib/cdb-mcp-server.jar"
case "${1:-}" in
  copilot-intellij) cat <<EOF
{
  "servers": {
    "chromium-bridge": {
      "command": "java",
      "args": ["-jar", "$JAR"]
    }
  }
}
EOF
  ;;
  copilot-vscode) cat <<EOF
{
  "servers": {
    "chromium-bridge": {
      "type": "stdio",
      "command": "java",
      "args": ["-jar", "$JAR"],
      "env": { "CDB_CLIENT_NAME": "copilot" }
    }
  }
}
EOF
  ;;
  copilot-cli) echo "copilot mcp add chromium-bridge --env CDB_CLIENT_NAME=copilot -- java -jar $JAR" ;;
  claude-code) echo "claude mcp add chromium-bridge --env CDB_CLIENT_NAME=claude-code -- java -jar $JAR" ;;
  codex) cat <<EOF
[mcp_servers.chromium-bridge]
command = "java"
args = ["-jar", "$JAR"]
env = { CDB_CLIENT_NAME = "codex" }
startup_timeout_sec = 90
tool_timeout_sec = 180
EOF
  ;;
  *) echo "Usage: bin/mcp-config.sh copilot-intellij|copilot-vscode|copilot-cli|claude-code|codex" >&2; exit 1 ;;
esac
