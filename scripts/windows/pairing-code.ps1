# Prints the Chrome extension pairing code of the running driver (host:port#token).
$file = if ($env:CDB_CONNECTION_FILE) { $env:CDB_CONNECTION_FILE } else { Join-Path $env:USERPROFILE ".chromium-dev-bridge\driver.json" }
if (-not (Test-Path $file)) { Write-Error "Driver is not running (no $file)"; exit 1 }
$info = Get-Content $file -Raw | ConvertFrom-Json
"127.0.0.1:$($info.port)#$($info.extensionToken)"
