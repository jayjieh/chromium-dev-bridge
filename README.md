# Chromium Developer Bridge

A local, secure browser automation and debugging platform that connects your running web application, IntelliJ IDEA
and AI coding agents (Claude Code, Codex, Cursor and other MCP clients).

```text
IntelliJ plugin ──REST + WebSocket──┐
                                    ├──► Browser driver (Spring Boot, 127.0.0.1) ──Playwright──► Chromium ──► your app
AI agent ──MCP stdio──► MCP server ─┘
```

* **Browser driver**: launches Playwright-managed Chromium, isolated sessions, tabs, navigation, semantic browser
  actions, compact DOM/accessibility snapshots with stable element refs, console errors with source-map resolution,
  redacted network capture, screenshots, visual diffs, declarative test scenarios. REST + WebSocket API
  ([OpenAPI](docs/api/OPENAPI.yaml)).
* **MCP server**: 17 browser tools for AI agents, with untrusted-content markers and prompt-injection flags.
* **IntelliJ plugin**: Browser preview, Elements, Console (click to open the source), Network with a waterfall,
  Screenshots, AI Agent setup, Sessions and approvals, and Settings.

## Quick start (5 minutes)

Requirements: JDK 21, Maven 3.8+, Node 18+ (only for the sample app).

```bash
mvn install -DskipTests                       # builds all backend modules
scripts/linux/start-driver.sh                 # or scripts\windows\start-driver.ps1 / scripts/macos/start-driver.sh
node sample-applications/login-demo/server.mjs   # sample app on http://127.0.0.1:3000
```

The first session downloads Playwright's Chromium (about 150 MB). To use an installed Chrome or Edge instead, pass
`--cdb.browser.channel=chrome`.

Connect Claude Code:

```bash
claude mcp add chromium-bridge -- java -jar /absolute/path/to/mcp-server/target/cdb-mcp-server.jar
```

Then ask: *"Open http://localhost:3000/login in the browser bridge, sign in with the test credentials, and tell me
which console errors and failed requests the dashboard produces."*

The details are in [INSTALLATION.md](docs/implementation/INSTALLATION.md),
[CLAUDE_INTEGRATION.md](docs/implementation/CLAUDE_INTEGRATION.md),
[CODEX_INTEGRATION.md](docs/implementation/CODEX_INTEGRATION.md) and
[INTELLIJ_PLUGIN_GUIDE.md](docs/implementation/INTELLIJ_PLUGIN_GUIDE.md).

## Repository layout

| Path | Contents |
|---|---|
| `shared-protocol/` | DTOs, events, error codes (wire contract) |
| `browser-core/` | Provider SPI, sessions, security policy, redaction, buffers, source maps, visual diff, scenarios |
| `playwright-provider/` | Playwright Chromium provider (launch, CDP attach on loopback, discovery) |
| `browser-api/` | Java client for the driver API |
| `browser-driver/` | Spring Boot driver service |
| `mcp-server/` | MCP stdio server |
| `intellij-plugin/` | IntelliJ plugin (Gradle, Kotlin) |
| `integration-tests/` | End-to-end tests (real Chromium, sample app, MCP) |
| `sample-applications/login-demo/` | Sample app with deliberate failures |
| `scripts/` | Start scripts per OS |
| `docs/` | Architecture, API, security, implementation guides, ADRs |

## Security in one paragraph

The driver binds to 127.0.0.1, requires random bearer tokens (in a user-only `~/.chromium-dev-bridge/driver.json`),
rejects browser `Origin` headers and non-loopback `Host` headers, and enforces rate and size limits. Sessions can only
load development hosts until the human approves another origin. AI agents use a separate token that cannot grant
approvals or run JavaScript. Credentials, cookies and tokens are redacted from snapshots, logs, network data and
reports. See [SECURITY_DESIGN.md](docs/security/SECURITY_DESIGN.md) and [THREAT_MODEL.md](docs/security/THREAT_MODEL.md).

## Build and test

```bash
mvn verify                                   # unit + API + end-to-end tests (needs Node; downloads Chromium once)
mvn verify -DskipITs                         # without the end-to-end module
cd intellij-plugin && ./gradlew buildPlugin test   # plugin (run `mvn install` first)
```

## License

Apache License 2.0 — see [LICENSE](LICENSE).
