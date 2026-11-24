# Prints (or copies with -Copy) the Chrome extension pairing code of the running driver.
param([switch]$Copy)
$file = if ($env:CDB_CONNECTION_FILE) { $env:CDB_CONNECTION_FILE } else { Join-Path $env:USERPROFILE ".chromium-dev-bridge\driver.json" }
if (-not (Test-Path $file)) { Write-Error "The driver is not running. Start it with bin\start-driver.ps1"; exit 1 }
$info = Get-Content $file -Raw | ConvertFrom-Json
$code = "127.0.0.1:$($info.port)#$($info.extensionToken)"
if ($Copy) { Set-Clipboard -Value $code; Write-Host "Pairing code copied. Paste it into the extension popup in Chrome." }
else { $code }
