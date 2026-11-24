#!/usr/bin/env bash
# Starts the Chromium Developer Bridge driver on 127.0.0.1:7878 (override with CDB_PORT).
# Extra arguments are passed to Spring Boot, e.g. --cdb.browser.channel=chrome --cdb.browser.headless=false
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAR="${CDB_DRIVER_JAR:-$ROOT/browser-driver/target/cdb-driver-exec.jar}"
if [[ ! -f "$JAR" ]]; then
  echo "Driver jar not found at $JAR — run 'mvn install -DskipTests' in $ROOT first." >&2
  exit 1
fi
exec java ${CDB_JAVA_OPTS:-} -jar "$JAR" "$@"
