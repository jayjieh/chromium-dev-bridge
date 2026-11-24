# Chromium Developer Bridge — one-time setup (Windows).
# Checks Java, records where this bundle lives (so the IntelliJ plugin finds the driver), and prints next steps.
$ErrorActionPreference = "Stop"
$home_ = Resolve-Path (Join-Path $PSScriptRoot "..")
$driverJar = Join-Path $home_ "lib\cdb-driver.jar"
$mcpJar = Join-Path $home_ "lib\cdb-mcp-server.jar"

$javaVersion = $null
if (Get-Command java -ErrorAction SilentlyContinue) {
    # java -version prints to stderr; read it through cmd so PowerShell 5.1 doesn't turn it into an error.
    $javaVersion = (cmd /c "java -version 2>&1" | Select-Object -First 1)
}
if (-not $javaVersion) {
    Write-Host "Java was not found. Install Java 17 or later (e.g. https://adoptium.net) and run setup again." -ForegroundColor Red
    exit 1
}
if ($javaVersion -match '"(\d+)') {
    if ([int]$Matches[1] -lt 17) { Write-Host "Java 17 or later is required; found: $javaVersion" -ForegroundColor Red; exit 1 }
}

$dataDir = Join-Path $env:USERPROFILE ".chromium-dev-bridge"
New-Item -ItemType Directory -Force $dataDir | Out-Null
@{ home = "$home_"; driverJar = "$driverJar"; mcpJar = "$mcpJar"; version = (Get-Content (Join-Path $home_ "VERSION") -Raw).Trim() } |
    ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $dataDir "install.json")

Write-Host ""
Write-Host "Chromium Developer Bridge is set up ($javaVersion)." -ForegroundColor Green
Write-Host ""
Write-Host "Next steps:"
Write-Host "  1. Start the driver:            .\bin\start-driver.ps1        (AI tools can also start it for you)"
Write-Host "  2. IntelliJ plugin:             Settings > Plugins > gear > Install Plugin from Disk > intellij\*.zip"
Write-Host "  3. Chrome extension:            chrome://extensions > Developer mode > Load unpacked > chrome-extension folder"
Write-Host "     Pairing code:                .\bin\pairing-code.ps1 -Copy   (driver must be running)"
Write-Host "  4. Copilot / Claude / Codex:    .\bin\mcp-config.ps1 -Client copilot-intellij -Copy"
Write-Host "                                  (clients: copilot-intellij, copilot-vscode, copilot-cli, claude-code, codex)"
Write-Host ""
