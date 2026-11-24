# Chromium Developer Bridge

A local, secure browser automation and debugging platform that connects your running web application, IntelliJ IDEA
and AI coding agents (GitHub Copilot, Claude Code, Codex, Cursor and other MCP clients).

**[Download the latest release](https://github.com/jayjieh/chromium-dev-bridge/releases/latest)** · [Install](#install-no-build-needed) ·
[Connect GitHub Copilot](#2-connect-github-copilot) · [IntelliJ plugin](#3-install-the-intellij-plugin) ·
[Chrome extension](#4-install-the-chrome-extension)

## Install (no build needed)

You need **Java 17 or later** ([Adoptium](https://adoptium.net); check with `java -version`), **Google Chrome**, and
**IntelliJ IDEA 2025.3+** for the plugin.

### 1. Download and set up

Download the bundle for your computer. It contains everything: the browser driver, the MCP server for AI agents, the
IntelliJ plugin, the Chrome extension and setup scripts.

| Your computer | Download |
|---|---|
| Windows | [chromium-dev-bridge-0.1.1-windows-x64.zip](https://github.com/jayjieh/chromium-dev-bridge/releases/download/v0.1.1/chromium-dev-bridge-0.1.1-windows-x64.zip) (70 MB) |
| Mac, Apple silicon (M1–M4) | [chromium-dev-bridge-0.1.1-macos-arm64.zip](https://github.com/jayjieh/chromium-dev-bridge/releases/download/v0.1.1/chromium-dev-bridge-0.1.1-macos-arm64.zip) (75 MB) |
| Mac, Intel | [chromium-dev-bridge-0.1.1-macos-x64.zip](https://github.com/jayjieh/chromium-dev-bridge/releases/download/v0.1.1/chromium-dev-bridge-0.1.1-macos-x64.zip) (77 MB) |
| Linux x64 | [chromium-dev-bridge-0.1.1-linux-x64.zip](https://github.com/jayjieh/chromium-dev-bridge/releases/download/v0.1.1/chromium-dev-bridge-0.1.1-linux-x64.zip) (80 MB) |
| Linux ARM64 | [chromium-dev-bridge-0.1.1-linux-arm64.zip](https://github.com/jayjieh/chromium-dev-bridge/releases/download/v0.1.1/chromium-dev-bridge-0.1.1-linux-arm64.zip) (80 MB) |
| Any OS, one file | [chromium-dev-bridge-0.1.1-all-platforms.zip](https://github.com/jayjieh/chromium-dev-bridge/releases/download/v0.1.1/chromium-dev-bridge-0.1.1-all-platforms.zip) (249 MB) |

Unzip it somewhere permanent (e.g. `C:\Tools\chromium-dev-bridge` or `~/tools/chromium-dev-bridge`), then run setup
once from that folder. On Windows, right-click the zip > **Properties** > **Unblock** before unzipping.

```powershell
powershell -ExecutionPolicy Bypass -File .\bin\setup.ps1     # Windows
```
```bash
bash bin/setup.sh                                            # macOS / Linux
```

Setup checks Java and records where the bundle lives, so the IntelliJ plugin and AI tools find the driver without
any configuration. To start the driver yourself (needed for the IntelliJ plugin and the Chrome extension):
`.\bin\start-driver.ps1`, double-click `bin\start-driver.cmd`, or `bin/start-driver.sh`. It listens on
`127.0.0.1:7878` only and downloads Playwright's Chromium (about 150 MB) on first use.

### 2. Connect GitHub Copilot

Copilot uses the bridge's 17 `browser_*` tools in **Agent mode**. A script prints the configuration with your
install path filled in; the MCP server starts the driver by itself when Copilot calls it. On Copilot Business or
Enterprise, an organization admin must enable the **MCP servers in Copilot** policy.

**Copilot in IntelliJ IDEA**
1. Copy the config: `.\bin\mcp-config.ps1 -Client copilot-intellij -Copy` (macOS/Linux: `bin/mcp-config.sh copilot-intellij`).
2. Click the Copilot icon in the status bar > **Open Chat** > switch the chat to **Agent**.
3. Click the tools icon at the bottom of the chat > **Add MCP Tools**, paste into the `mcp.json` that opens, save.
4. `chromium-bridge` appears in the tools list.

The config looks like this (with your own path):
```json
{
  "servers": {
    "chromium-bridge": {
      "command": "java",
      "args": ["-jar", "C:/Tools/chromium-dev-bridge/lib/cdb-mcp-server.jar"]
    }
  }
}
```

**Copilot in VS Code (1.99+):** `.\bin\mcp-config.ps1 -Client copilot-vscode -Copy`, paste into `.vscode/mcp.json`,
click **Start** above the server entry, then use Copilot Chat in **Agent** mode.

**Copilot CLI:** run the command printed by `.\bin\mcp-config.ps1 -Client copilot-cli`, for example:
```bash
copilot mcp add chromium-bridge --env CDB_CLIENT_NAME=copilot -- java -jar /path/to/chromium-dev-bridge/lib/cdb-mcp-server.jar
```
then check with `copilot mcp list`.

**Claude Code / Codex:** `-Client claude-code` prints the `claude mcp add …` command, `-Client codex` the
`~/.codex/config.toml` entry.

Try it in Agent mode:
> Use the chromium-bridge tools: open http://localhost:3000 in a browser session, take an interactive snapshot, then
> check the console errors and failed network requests and tell me where in the code they come from.

### 3. Install the IntelliJ plugin

The plugin is in the bundle's `intellij/` folder, or download it on its own:
[chromium-dev-bridge-intellij-0.1.1.zip](https://github.com/jayjieh/chromium-dev-bridge/releases/download/v0.1.1/chromium-dev-bridge-intellij-0.1.1.zip).
It talks to the driver from the bundle, so set up the bundle first.

1. **Settings** > **Plugins** > ⚙ (gear) > **Install Plugin from Disk…**
2. Choose `chromium-dev-bridge-intellij-0.1.1.zip` (don't unzip it) and restart the IDE when asked.
3. Open the **Chromium Bridge** tool window on the right edge. It connects to the driver, or starts it from the
   location setup recorded; the footer shows **● Connected**.

Tabs: Browser (live preview, address bar, viewport presets), Elements, Console (double-click to open the source
line), Network (waterfall), Screenshots, AI Agent (copy-ready configs for Copilot, Claude Code, Codex), Sessions
(approve sites, *Attach to Chrome…*), Settings (*Copy Chrome pairing code*).

### 4. Install the Chrome extension

The extension lets the bridge use tabs from your everyday Chrome, one tab at a time, only when you click
**Share this tab**. It isn't in the Chrome Web Store yet; you load it from a folder.

1. Use the bundle's `chrome-extension` folder, or download
   [chromium-dev-bridge-extension-0.1.1.zip](https://github.com/jayjieh/chromium-dev-bridge/releases/download/v0.1.1/chromium-dev-bridge-extension-0.1.1.zip)
   and unzip it into a folder you'll keep (Chrome loads the extension from that folder).
2. Open `chrome://extensions`, turn on **Developer mode** (top right), click **Load unpacked**, and select that folder.
3. Pin it: puzzle icon in the toolbar > pin **Chromium Developer Bridge**.
4. With the driver running, copy the pairing code: IntelliJ > Chromium Bridge > **Settings** > *Copy Chrome pairing
   code*, or `.\bin\pairing-code.ps1 -Copy` (macOS/Linux: `bin/pairing-code.sh`).
5. Click the extension icon, paste the code, click **Pair**. The dot turns green; you only pair once.
6. Open your app in a tab, click the extension icon > **Share this tab**. Leave *Let AI agents use this tab* ticked
   if Copilot should be able to use it.

Chrome shows "Chromium Developer Bridge started debugging this browser" while a tab is shared; stop with **Stop
sharing** or **Cancel** on that bar. Pages that aren't local development hosts can't be read unless you approve
them. Details: [CHROME_EXTENSION.md](docs/implementation/CHROME_EXTENSION.md). Troubleshooting:
[the bundle README](distribution/README.md#troubleshooting).

## How it works

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
* **Your own Chrome**: share individual tabs from your everyday Chrome with the bundled extension, or attach to a
  debug-profile Chrome ([CHROME_EXTENSION.md](docs/implementation/CHROME_EXTENSION.md)).
* **IntelliJ plugin**: Browser preview, Elements, Console (click to open the source), Network with a waterfall,
  Screenshots, AI Agent setup, Sessions and approvals, and Settings.

## Build from sources

Build the release bundles yourself with `scripts/package-release.ps1` (or `.sh`); they land in `dist/`.
To publish a release, update `VERSION` and push a `v*` tag: the Release workflow builds and attaches the zips.

### Quick start from sources (5 minutes)

Requirements: JDK 17+ (the IntelliJ plugin build needs 21), Maven 3.8+, Node 18+ (only for the sample app).

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
| `extension-provider/` | Sessions for tabs shared from your own Chrome (CDP via the extension) |
| `chrome-extension/` | Chrome extension (Manifest V3) |
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
