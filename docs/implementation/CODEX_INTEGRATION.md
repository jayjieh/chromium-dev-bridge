# Codex Integration

Add the server to `~/.codex/config.toml` (template: `scripts/mcp/codex-config.toml`):

```toml
[mcp_servers.chromium-bridge]
command = "java"
args = ["-jar", "/ABS/PATH/mcp-server/target/cdb-mcp-server.jar",
        "--driver-jar", "/ABS/PATH/browser-driver/target/cdb-driver-exec.jar"]
env = { CDB_CLIENT_NAME = "codex" }
startup_timeout_sec = 60
tool_timeout_sec = 180
```

Or with the CLI: `codex mcp add chromium-bridge -- java -jar /ABS/PATH/mcp-server/target/cdb-mcp-server.jar`.

`CDB_CLIENT_NAME` gives the agent its own identity (`agent:codex`): its sessions are separate from those of other
agents. Sessions created in the IDE with agent sharing enabled are visible to every agent.

The workflow and prompts are the same as in [CLAUDE_INTEGRATION.md](CLAUDE_INTEGRATION.md). Codex's sandbox does not
affect the MCP server (it runs as a separate process started by Codex); the driver must be able to bind to
127.0.0.1 and launch Chromium.
