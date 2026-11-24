#!/usr/bin/env bash
# Starts the Chromium Developer Bridge driver on 127.0.0.1:7878 (override with CDB_PORT).
exec java ${CDB_JAVA_OPTS:-} -jar "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib/cdb-driver.jar" "$@"
