# Starts Chrome with remote debugging on 127.0.0.1:9222 and a dedicated profile ("quick attach").
param([int]$Port = 9222)
$profileDir = Join-Path $env:USERPROFILE ".chromium-dev-bridge\chrome-debug-profile"
$chrome = @("$env:ProgramFiles\Google\Chrome\Application\chrome.exe", "${env:ProgramFiles(x86)}\Google\Chrome\Application\chrome.exe",
    "$env:LOCALAPPDATA\Google\Chrome\Application\chrome.exe") | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $chrome) { Write-Error "Google Chrome not found"; exit 1 }
New-Item -ItemType Directory -Force $profileDir | Out-Null
Start-Process $chrome -ArgumentList "--remote-debugging-port=$Port", "--remote-debugging-address=127.0.0.1", "--user-data-dir=`"$profileDir`"", "--no-first-run"
Write-Host "Chrome started with remote debugging on http://127.0.0.1:$Port"
