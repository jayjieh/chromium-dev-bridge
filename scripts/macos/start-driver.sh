#!/usr/bin/env bash
# macOS: same as Linux. Requires JDK 21 (e.g. `brew install openjdk@21`).
exec "$(cd "$(dirname "${BASH_SOURCE[0]}")/../linux" && pwd)/start-driver.sh" "$@"
