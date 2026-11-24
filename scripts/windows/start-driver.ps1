# Starts the Chromium Developer Bridge driver on 127.0.0.1:7878 (override with $env:CDB_PORT).
# Extra arguments are passed to Spring Boot, e.g. --cdb.browser.channel=msedge --cdb.browser.headless=false
$root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$jar = if ($env:CDB_DRIVER_JAR) { $env:CDB_DRIVER_JAR } else { Join-Path $root "browser-driver\target\cdb-driver-exec.jar" }
if (-not (Test-Path $jar)) {
    Write-Error "Driver jar not found at $jar - run 'mvn install -DskipTests' in $root first."
    exit 1
}
& java -jar $jar @args
