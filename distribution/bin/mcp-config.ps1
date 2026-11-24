# Prints the MCP configuration for an AI client with this bundle's paths filled in.
#   .\bin\mcp-config.ps1 -Client copilot-intellij -Copy
# Clients: copilot-intellij, copilot-vscode, copilot-cli, claude-code, codex
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("copilot-intellij", "copilot-vscode", "copilot-cli", "claude-code", "codex")]
    [string]$Client,
    [switch]$Copy
)
$jar = (Resolve-Path (Join-Path $PSScriptRoot "..\lib\cdb-mcp-server.jar")).Path -replace '\\', '/'
$out = switch ($Client) {
    "copilot-intellij" { @"
{
  "servers": {
    "chromium-bridge": {
      "command": "java",
      "args": ["-jar", "$jar"]
    }
  }
}
"@ }
    "copilot-vscode" { @"
{
  "servers": {
    "chromium-bridge": {
      "type": "stdio",
      "command": "java",
      "args": ["-jar", "$jar"],
      "env": { "CDB_CLIENT_NAME": "copilot" }
    }
  }
}
"@ }
    "copilot-cli" { "copilot mcp add chromium-bridge --env CDB_CLIENT_NAME=copilot -- java -jar $jar" }
    "claude-code" { "claude mcp add chromium-bridge --env CDB_CLIENT_NAME=claude-code -- java -jar $jar" }
    "codex" { @"
[mcp_servers.chromium-bridge]
command = "java"
args = ["-jar", "$jar"]
env = { CDB_CLIENT_NAME = "codex" }
startup_timeout_sec = 90
tool_timeout_sec = 180
"@ }
}
if ($Copy) { Set-Clipboard -Value $out; Write-Host "Copied the $Client configuration to the clipboard:`n"; }
$out
