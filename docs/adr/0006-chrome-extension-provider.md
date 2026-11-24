# ADR-0006: Chrome extension provider for the user's own browser

* Status: Accepted
* Date: 2026-11-24

## Context

Developers want the bridge to inspect and drive the Chrome they already use (their tabs, their logged-in dev
environment) instead of a separate Playwright-managed browser, the same way Claude in Chrome works.

Options:

1. **Remote debugging port** (`--remote-debugging-port`). Since Chrome 136 the flag is ignored for the default
   profile (anti-cookie-theft measure), so it can't reach the user's normal profile. It also exposes every tab.
2. **Chrome extension using `chrome.debugger`.** Supported way to get CDP access to tabs in the normal profile.
   Chrome shows a "is debugging this browser" infobar on attached tabs. Requires the `debugger` permission.

## Decision

Option 2: a Manifest V3 extension (`chrome-extension/`) plus an `extension-provider` module.

* **Per-tab opt-in.** Nothing is attached until the user clicks *Share this tab* in the extension popup. Sharing a
  tab creates a driver session (owner `admin`, i.e. the human). The user decides whether AI agents may use it.
* **Transport.** The extension's service worker opens a WebSocket to `ws://127.0.0.1:<port>/api/v1/extension`.
  JSON-RPC 2.0 both ways: the driver sends `cdp.send` requests; the extension forwards CDP events (`cdp.event`) and
  tab lifecycle notifications.
* **Pairing.** A dedicated *extension token* (persisted in `~/.chromium-dev-bridge/extension-token`, owner-only, so
  pairing survives driver restarts). The user pastes a pairing code (`127.0.0.1:<port>#<token>`) into the popup once.
  The token travels as a WebSocket sub-protocol (browsers can't set `Authorization` on WebSockets). Only
  `chrome-extension://` origins may use this endpoint; specific extension ids can be pinned with
  `cdb.security.extension-ids`.
* **Same SPI.** `ExtensionSessionDriver` implements `BrowserSessionDriver` with raw CDP, so the REST API, WebSocket
  events, IntelliJ plugin and MCP tools work unchanged. Element location and actions reuse the page helper script
  (moved to `browser-core`) and real CDP input events.
* **Reading other sites.** The user's tab can go anywhere. Snapshots, inspection, actions, screenshots and captured
  console/network data are only available while the tab's page is on an allowed origin (development hosts, or an
  origin the user approved). The popup can approve the current site when sharing, with a warning.

## Consequences

* Works with the user's real Chrome, extensions, accounts and dev tools open side by side.
* One session per shared tab; opening new tabs from the API is not supported for extension sessions.
* If the extension disconnects (service worker restart, driver restart), shared tabs are re-announced and get new
  session ids.
* Screenshots of tabs that are not visible may time out (Chrome throttles hidden tabs).
* The `debugger` permission is powerful; the extension is installed unpacked by the developer and only attaches to
  tabs the user explicitly shares.
