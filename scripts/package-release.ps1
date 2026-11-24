# Builds everything and assembles the shareable release bundles in dist/:
#   chromium-dev-bridge-<version>-windows-x64.zip   (also macos-x64, macos-arm64, linux-x64, linux-arm64; ~70 MB each)
#   chromium-dev-bridge-<version>-all-platforms.zip (one bundle for every OS; ~250 MB)
#   chromium-dev-bridge-extension-<version>.zip     (just the Chrome extension)
# Each bundle: lib/ (driver + MCP server), intellij/ (plugin zip), chrome-extension/, bin/ (setup and helper scripts).
# Usage: .\scripts\package-release.ps1 [-SkipBuild] [-Platforms windows-x64,linux-x64]
param([switch]$SkipBuild, [string[]]$Platforms = @("windows-x64", "macos-x64", "macos-arm64", "linux-x64", "linux-arm64", "all-platforms"))
$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$version = (Get-Content (Join-Path $root "VERSION") -Raw).Trim()
$dist = Join-Path $root "dist"
$playwrightPlatform = @{ "windows-x64" = "win32_x64"; "macos-x64" = "mac"; "macos-arm64" = "mac-arm64";
    "linux-x64" = "linux"; "linux-arm64" = "linux-arm64"; "all-platforms" = $null }

if (-not $SkipBuild) {
    Push-Location $root
    try {
        & mvn -q -B install -DskipTests
        if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }
        Push-Location (Join-Path $root "intellij-plugin")
        & .\gradlew.bat buildPlugin --console=plain -q
        if ($LASTEXITCODE -ne 0) { throw "Plugin build failed" }
        Pop-Location
    } finally { Pop-Location }
}
$pluginZip = Get-ChildItem (Join-Path $root "intellij-plugin\build\distributions") -Filter "*.zip" | Select-Object -First 1
if (-not $pluginZip) { throw "Plugin zip not found; build the plugin first" }
$fullDriver = Join-Path $root "browser-driver\target\cdb-driver-exec.jar"
New-Item -ItemType Directory -Force $dist | Out-Null

foreach ($platform in $Platforms) {
    if (-not $playwrightPlatform.ContainsKey($platform)) { throw "Unknown platform $platform" }
    $name = "chromium-dev-bridge-$version"
    $stage = Join-Path $dist "stage-$platform"
    $out = Join-Path $stage $name
    if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
    New-Item -ItemType Directory -Force (Join-Path $out "lib"), (Join-Path $out "bin"), (Join-Path $out "intellij") | Out-Null
    $pw = $playwrightPlatform[$platform]
    if ($pw) {
        & java (Join-Path $root "scripts\SlimDriverJar.java") $fullDriver (Join-Path $out "lib\cdb-driver.jar") $pw
        if ($LASTEXITCODE -ne 0) { throw "Slimming the driver jar failed" }
    } else {
        Copy-Item $fullDriver (Join-Path $out "lib\cdb-driver.jar")
    }
    Copy-Item (Join-Path $root "mcp-server\target\cdb-mcp-server.jar") (Join-Path $out "lib\cdb-mcp-server.jar")
    Copy-Item $pluginZip.FullName (Join-Path $out "intellij")
    Copy-Item -Recurse (Join-Path $root "chrome-extension") (Join-Path $out "chrome-extension")
    Copy-Item (Join-Path $root "distribution\bin\*") (Join-Path $out "bin")
    Copy-Item (Join-Path $root "distribution\README.md"), (Join-Path $root "LICENSE") $out
    Set-Content -NoNewline -Encoding ascii (Join-Path $out "VERSION") $version
    $zip = Join-Path $dist "$name-$platform.zip"
    Remove-Item -Force $zip -ErrorAction SilentlyContinue
    Push-Location $stage
    try { & tar.exe -a -c -f $zip $name; if ($LASTEXITCODE -ne 0) { throw "zip failed" } } finally { Pop-Location }
    Remove-Item -Recurse -Force $stage
    Write-Host ("{0,-60} {1,8:N0} MB" -f (Split-Path $zip -Leaf), ((Get-Item $zip).Length / 1MB))
}

$pluginOut = Join-Path $dist "chromium-dev-bridge-intellij-$version.zip"
Copy-Item $pluginZip.FullName $pluginOut -Force
Write-Host ("{0,-60} {1,8:N2} MB" -f (Split-Path $pluginOut -Leaf), ((Get-Item $pluginOut).Length / 1MB))

$extZip = Join-Path $dist "chromium-dev-bridge-extension-$version.zip"
Remove-Item -Force $extZip -ErrorAction SilentlyContinue
Push-Location (Join-Path $root "chrome-extension")
try { & tar.exe -a -c -f $extZip * } finally { Pop-Location }
Write-Host ("{0,-60} {1,8:N2} MB" -f (Split-Path $extZip -Leaf), ((Get-Item $extZip).Length / 1MB))
