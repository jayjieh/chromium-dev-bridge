# Using your own Chrome

Two ways to work with Chrome instead of the driver's own Playwright browser:

| | Chrome extension (recommended) | Quick attach |
|---|---|---|
| Works with | Your everyday Chrome profile, its tabs and logins | A Chrome you start with a separate debug profile |
| Setup | Load the unpacked extension once, paste a pairing code once | Start Chrome with `scripts/<os>/start-chrome-debug` |
| What is shared | Only tabs you click *Share this tab* on | The whole debug browser (all its tabs) |
| Who can start it | You, from the popup | Admin token only (IDE or CLI), never agents |
| Sessions | One session per shared tab | One session with all tabs |
| Why not the normal profile | — | Chrome 136+ ignores `--remote-debugging-port` on the default profile |

Both appear as ordinary sessions: the IntelliJ tool window, the REST/WebSocket API and the MCP tools work unchanged.

## Chrome extension

### Install (once)
1. `chrome://extensions` → enable **Developer mode** → **Load unpacked** → select the `chrome-extension/` folder.
   The extension id is fixed by the key in `manifest.json`: `cccfpifbammfpjegnpoehanofanhalaj`.
2. Start the driver (`scripts/<os>/start-driver`).
3. Get the pairing code:
   * IntelliJ: *Chromium Bridge › Settings › Copy Chrome pairing code*, or
   * `scripts/windows/pairing-code.ps1` / `scripts/linux/pairing-code.sh`.
4. Click the extension icon, paste the code, **Pair**. The dot turns green.

The pairing token is stored in `~/.chromium-dev-bridge/extension-token` and survives driver restarts, so you only
pair once. Delete that file to rotate it (then pair again).

### Share a tab
1. Open your app, e.g. `http://localhost:3000`.
2. Click the extension icon → **Share this tab**.
   * *Let AI agents (MCP) use this tab* (on by default): whether MCP clients see the session.
   * *Allow tools to read this site*: shown only for sites that aren't local development hosts. It approves that
     origin for this session. Leave it off for sites with personal data.
3. Chrome shows *"Chromium Developer Bridge started debugging this browser"*. That bar stays while any tab is shared.

The tab appears in the IDE's Sessions tab (Browser: *Chrome (shared tab)*) and in `browser_list_sessions` for agents.

Stop with **Stop sharing** in the popup, **Cancel** on Chrome's bar, closing the tab, or closing the session in the
IDE. Your tab is never closed by the bridge.

### What tools can and can't do with a shared tab
* Navigate (development hosts or approved origins), click, type, select, press keys, scroll, drag, wait, snapshot,
  inspect, accessibility audit, screenshots (sensitive fields masked), console and network capture.
* **Reads are refused while the tab is on a site that isn't allowed.** If you browse to your mail in a shared tab,
  snapshots, screenshots, actions and page titles return `URL_NOT_ALLOWED`, and console/network events from that page
  are not captured.
* The extension relays only an allowlist of CDP commands (page, runtime evaluation for the helper script, input,
  network/log enable, viewport). Cookie and storage commands such as `Network.getAllCookies` are refused, so cookies
  can't be exported even by a compromised driver.
* Opening or closing tabs through the API isn't supported for shared tabs: share another tab instead.
* Screenshots and previews of tabs hidden behind others may time out (Chrome throttles hidden tabs); keep the tab
  visible in its window, or use the IDE's *Activate* (tab) action.

### Pinning the extension id
To accept only this extension, set in the driver config:
```yaml
cdb.security.extension-ids: [cccfpifbammfpjegnpoehanofanhalaj]
```
`cdb.security.extension-can-approve: false` disables origin approval from the popup (approve in the IDE instead).

### Protocol (for contributors)
WebSocket `ws://127.0.0.1:<port>/api/v1/extension`, sub-protocols `["cdb-ext", "<token>"]`, origin
`chrome-extension://<id>`. JSON-RPC 2.0:

| Direction | Message | Meaning |
|---|---|---|
| ext → driver | `hello {version, userAgent}` (request) | Handshake |
| ext → driver | `ping` (request) | Keep-alive (keeps the MV3 service worker alive) |
| ext → driver | `tab.shared {tabId, url, title, agentAccess, approveOrigin}` (request → `{sessionId}`) | User shared a tab |
| ext → driver | `tab.unshared {tabId, reason}` | Sharing ended (popup, Chrome bar, tab closed, debugger detached) |
| ext → driver | `tab.updated {tabId, url?, title?}` | Tab changed |
| ext → driver | `cdp.event {tabId, method, params}` | CDP event from `chrome.debugger.onEvent` |
| driver → ext | `cdp.send {tabId, method, params}` (request) | CDP command (allowlisted in the extension) |
| driver → ext | `tab.reattach`, `tab.activate` (requests) | Restart / bring to front |
| driver → ext | `tab.unshare`, `session.attached` | Session closed / session id for the popup |

If the extension disconnects, its sessions are closed; on reconnect, still-shared tabs are announced again and get new
session ids.

## Quick attach

1. Start Chrome with a dedicated profile and remote debugging on 127.0.0.1:
   ```powershell
   .\scripts\windows\start-chrome-debug.ps1
   ```
   (`scripts/linux/start-chrome-debug.sh`, `scripts/macos/start-chrome-debug.sh`; profile in
   `~/.chromium-dev-bridge/chrome-debug-profile`, default port 9222.)
2. IntelliJ: *Sessions › Attach to Chrome…* (endpoint `http://127.0.0.1:9222`), or
   `POST /api/v1/sessions {"connectEndpoint": "http://127.0.0.1:9222", "agentAccess": true}` with the admin token.

The session uses the browser's existing tabs. Closing the session only disconnects; Chrome and its tabs stay open.
The URL policy still applies: top-level navigation to origins that aren't development hosts or approved is blocked
in that browser while it is attached.
