#!/usr/bin/env bash
exec "$(cd "$(dirname "${BASH_SOURCE[0]}")/../linux" && pwd)/start-mcp.sh" "$@"
