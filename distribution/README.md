# Chromium Developer Bridge — setup

Gives GitHub Copilot, Claude Code and other AI agents, plus IntelliJ IDEA, a browser they can inspect and drive:
your local web app, your own Chrome tabs, console errors, network requests, screenshots.

**You need:** Java 17 or later (`java -version`), Chrome, and for the IDE part IntelliJ IDEA 2025.3 or later.
No source code or build tools.

```
chromium-dev-bridge-<version>/
├── bin/               setup, start-driver, pairing-code, mcp-config, start-chrome-debug (.ps1 / .cmd / .sh)
├── lib/               cdb-driver.jar (browser driver), cdb-mcp-server.jar (MCP server for AI agents)
├── intellij/          chromium-dev-bridge-intellij-<version>.zip (IntelliJ plugin)
└── chrome-extension/  Chrome extension (load unpacked)
```

## 1. Unzip and run setup (once)

Unzip somewhere permanent, e.g. `C:\Tools\chromium-dev-bridge` or `~/tools/chromium-dev-bridge`, then:

```powershell
.\bin\setup.ps1          # Windows (PowerShell)
```
```bash
bash bin/setup.sh        # macOS / Linux
```

Setup checks Java and records the install location so the IntelliJ plugin and AI tools find the driver.
If PowerShell blocks the script, run `powershell -ExecutionPolicy Bypass -File .\bin\setup.ps1` or right-click the
zip > *Properties* > *Unblock* before unzipping.

## 2. Start the driver

```powershell
.\bin\start-driver.ps1   # or double-click bin\start-driver.cmd; macOS/Linux: bin/start-driver.sh
```

It listens on `127.0.0.1:7878` only. The first time it opens a browser session it downloads Playwright's Chromium
(about 150 MB). You can skip this step if you only use AI tools: the MCP server starts the driver when needed.

## 3. Connect GitHub Copilot

Copilot uses MCP tools in **Agent mode**. On Copilot Business/Enterprise, your organization must enable the
*MCP servers in Copilot* policy.

**IntelliJ IDEA (GitHub Copilot plugin)**
1. Copy the config: `.\bin\mcp-config.ps1 -Client copilot-intellij -Copy` (macOS/Linux: `bin/mcp-config.sh copilot-intellij`).
2. Copilot icon in the status bar > **Open Chat** > switch to **Agent**.
3. Tools icon at the bottom of the chat > **Add MCP Tools** > paste into `mcp.json`, save.
4. The tools list shows `chromium-bridge` with 17 `browser_*` tools.

The config looks like this (your path is filled in by the script):
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

**VS Code (1.99+)**: `.\bin\mcp-config.ps1 -Client copilot-vscode -Copy`, paste into `.vscode/mcp.json`, click
**Start** above the server, then use Copilot Chat in **Agent** mode.

**Copilot CLI**: run the command printed by `.\bin\mcp-config.ps1 -Client copilot-cli`
(`copilot mcp add chromium-bridge ... -- java -jar .../cdb-mcp-server.jar`), then check with `copilot mcp list`.

**Claude Code / Codex**: `-Client claude-code` or `-Client codex` print the matching command or `config.toml` entry.

Try it (Agent mode):
> Use the chromium-bridge tools: open http://localhost:3000 in a browser session, take an interactive snapshot,
> then check console errors and failed network requests and tell me where in the code they come from.

## 4. Install the IntelliJ plugin

1. **Settings > Plugins >** ⚙ **> Install Plugin from Disk…** > choose `intellij/chromium-dev-bridge-intellij-<version>.zip`.
2. Restart the IDE when asked.
3. Open the **Chromium Bridge** tool window (right edge). It connects to the driver, or starts it using the
   location recorded by setup.

Tabs: Browser (live preview, address bar, viewport presets), Elements, Console (double-click to open the source
line), Network (with waterfall), Screenshots, AI Agent (copyable MCP configs), Sessions (approve sites, attach to
Chrome), Settings (Chrome pairing code).

## 5. Use your own Chrome (extension)

1. Open `chrome://extensions`, turn on **Developer mode** (top right), click **Load unpacked**, and select the
   `chrome-extension` folder from this bundle. Keep the folder where it is: Chrome loads it from there.
2. Pin the extension (puzzle icon > pin *Chromium Developer Bridge*).
3. Get the pairing code while the driver runs: IntelliJ > Chromium Bridge > **Settings** > *Copy Chrome pairing code*,
   or `.\bin\pairing-code.ps1 -Copy` (macOS/Linux: `bin/pairing-code.sh`).
4. Click the extension icon, paste the code, **Pair**. The dot turns green. You only pair once.
5. Open your app in a tab, click the extension icon > **Share this tab**. Chrome shows a "started debugging this
   browser" bar while a tab is shared. Stop with *Stop sharing* or *Cancel* on that bar.

Shared tabs appear in IntelliJ's Sessions tab and to AI agents (if you ticked *Let AI agents use this tab*).
Pages that aren't local development hosts (`localhost`, `127.0.0.1`, `*.localhost`, `*.test`) can't be read unless
you approve them.

**Alternative, quick attach:** `.\bin\start-chrome-debug.ps1` starts a separate Chrome profile with remote
debugging; attach from IntelliJ > Sessions > *Attach to Chrome…*.

## Security in short
* Everything runs on your machine and listens on 127.0.0.1 only; tokens are generated per start and stored in
  `~/.chromium-dev-bridge/` (readable only by you).
* AI agents can only reach development hosts, can't approve other sites, can't run arbitrary JavaScript, and see
  page content marked as untrusted. Approvals are yours (IntelliJ Sessions tab or extension popup).
* Passwords, cookies, tokens and similar values are removed from snapshots, logs and network data.

## Troubleshooting
| Problem | Fix |
|---|---|
| `java` not found / wrong version | Install Java 17+ (Temurin), reopen the terminal |
| Copilot doesn't list the tools | Agent mode; check the jar path in `mcp.json`; org policy for Business/Enterprise |
| Extension dot stays red | Start the driver; pair again with a fresh code |
| "This page can't be shared" | Chrome's own pages (New Tab, `chrome://`) can't be shared; open your app first |
| `URL_NOT_ALLOWED` | The page isn't a development host: approve it in IntelliJ (Sessions > Approve origin) or tick *Allow tools to read this site* when sharing |
| Chromium download fails (proxy/offline) | `.\bin\start-driver.ps1 --cdb.browser.channel=chrome` uses your installed Chrome |
| Port 7878 in use | `$env:CDB_PORT=7879; .\bin\start-driver.ps1` |

Logs: the driver prints to its console; when started by an AI tool, see `~/.chromium-dev-bridge/logs/driver.log`.
