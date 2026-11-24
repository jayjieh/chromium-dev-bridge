# Runs the MCP server over stdio (normally started by your MCP client).
$root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
& java -jar (Join-Path $root "mcp-server\target\cdb-mcp-server.jar") --driver-jar (Join-Path $root "browser-driver\target\cdb-driver-exec.jar") @args
