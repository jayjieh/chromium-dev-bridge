#!/usr/bin/env bash
# Builds everything and assembles the shareable release bundles in dist/ (see package-release.ps1).
# Usage: scripts/package-release.sh [--skip-build] [platform ...]
#   platforms: windows-x64 macos-x64 macos-arm64 linux-x64 linux-arm64 all-platforms (default: all of them)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="$(tr -d '[:space:]' < "$ROOT/VERSION")"
NAME="chromium-dev-bridge-$VERSION"
DIST="$ROOT/dist"
SKIP_BUILD=false
if [[ "${1:-}" == "--skip-build" ]]; then SKIP_BUILD=true; shift; fi
PLATFORMS=("$@")
[[ ${#PLATFORMS[@]} -gt 0 ]] || PLATFORMS=(windows-x64 macos-x64 macos-arm64 linux-x64 linux-arm64 all-platforms)

pw_platform() {
  case "$1" in
    windows-x64) echo win32_x64 ;; macos-x64) echo mac ;; macos-arm64) echo mac-arm64 ;;
    linux-x64) echo linux ;; linux-arm64) echo linux-arm64 ;; all-platforms) echo "" ;;
    *) echo "Unknown platform $1" >&2; exit 1 ;;
  esac
}

if [[ "$SKIP_BUILD" == false ]]; then
  (cd "$ROOT" && mvn -q -B install -DskipTests)
  (cd "$ROOT/intellij-plugin" && ./gradlew buildPlugin --console=plain -q)
fi
PLUGIN_ZIP="$(ls "$ROOT"/intellij-plugin/build/distributions/*.zip | head -1)"
FULL_DRIVER="$ROOT/browser-driver/target/cdb-driver-exec.jar"
mkdir -p "$DIST"

for platform in "${PLATFORMS[@]}"; do
  PW="$(pw_platform "$platform")"
  STAGE="$DIST/stage-$platform"
  OUT="$STAGE/$NAME"
  rm -rf "$STAGE" && mkdir -p "$OUT/lib" "$OUT/bin" "$OUT/intellij"
  if [[ -n "$PW" ]]; then
    java "$ROOT/scripts/SlimDriverJar.java" "$FULL_DRIVER" "$OUT/lib/cdb-driver.jar" "$PW"
  else
    cp "$FULL_DRIVER" "$OUT/lib/cdb-driver.jar"
  fi
  cp "$ROOT/mcp-server/target/cdb-mcp-server.jar" "$OUT/lib/cdb-mcp-server.jar"
  cp "$PLUGIN_ZIP" "$OUT/intellij/"
  cp -R "$ROOT/chrome-extension" "$OUT/chrome-extension"
  cp "$ROOT"/distribution/bin/* "$OUT/bin/"
  chmod +x "$OUT"/bin/*.sh
  cp "$ROOT/distribution/README.md" "$ROOT/LICENSE" "$OUT/"
  printf '%s' "$VERSION" > "$OUT/VERSION"
  rm -f "$DIST/$NAME-$platform.zip"
  (cd "$STAGE" && zip -qr "$DIST/$NAME-$platform.zip" "$NAME")
  rm -rf "$STAGE"
  echo "$NAME-$platform.zip  $(du -h "$DIST/$NAME-$platform.zip" | cut -f1)"
done
cp "$PLUGIN_ZIP" "$DIST/chromium-dev-bridge-intellij-$VERSION.zip"
echo "chromium-dev-bridge-intellij-$VERSION.zip"
rm -f "$DIST/chromium-dev-bridge-extension-$VERSION.zip"
(cd "$ROOT/chrome-extension" && zip -qr "$DIST/chromium-dev-bridge-extension-$VERSION.zip" .)
echo "chromium-dev-bridge-extension-$VERSION.zip"
