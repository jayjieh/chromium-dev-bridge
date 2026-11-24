# Starts the Chromium Developer Bridge driver on 127.0.0.1:7878 (override with $env:CDB_PORT).
# Extra arguments go to the driver, e.g. --cdb.browser.channel=chrome --cdb.browser.headless=false
$jar = Join-Path $PSScriptRoot "..\lib\cdb-driver.jar"
& java -jar $jar @args
