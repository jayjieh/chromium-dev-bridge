#!/usr/bin/env bash
# Runs the MCP server over stdio (normally started by your MCP client, not by hand).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
exec java -jar "$ROOT/mcp-server/target/cdb-mcp-server.jar" --driver-jar "$ROOT/browser-driver/target/cdb-driver-exec.jar" "$@"
