#!/usr/bin/env bash
# macOS: same as Linux. Requires JDK 17 or later (e.g. `brew install openjdk@17`).
exec "$(cd "$(dirname "${BASH_SOURCE[0]}")/../linux" && pwd)/start-driver.sh" "$@"
