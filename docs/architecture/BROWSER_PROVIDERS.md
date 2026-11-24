# Browser Providers

The core talks to browsers through `dev.cdb.core.spi`:

* `BrowserProvider`: `info()`, `open(SessionLaunchSpec, BrowserEventListener)`, `discoveredBrowsers()`, `close()`.
* `BrowserSessionDriver`: tabs, navigation, waits, actions, snapshot, inspect, accessibility, screenshot, viewport,
  evaluate, restart, close. Methods are synchronous and thread-safe.
* `BrowserEventListener`: console, network, tab, navigation, blocked-navigation and disconnect callbacks (raw data;
  the core redacts).

## Playwright provider (default)

| Concern | Implementation |
|---|---|
| Launch | `chromium().launch()` with bundled Chromium, a configured `executable-path`, or a channel (`chrome`, `msedge`) |
| Install | Only Chromium is installed (`playwright install chromium`) on first use; `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD` keeps Firefox/WebKit out |
| Isolation | `SHARED`: contexts in one browser per launch configuration. `PROCESS`: dedicated worker + browser. Persistent profiles (`profileName`) use `launchPersistentContext` under `~/.chromium-dev-bridge/profiles/<name>` |
| Attach | `connectEndpoint` → `connectOverCDP`, admin only, loopback URLs only |
| Threading | One `PlaywrightWorker` thread per Playwright instance; idle event pump (ADR-0002) |
| Element refs | Page helper keeps `WeakRef`s in a non-enumerable `window.__cdbRefs` map; custom selector engine `cdbref=element-N` resolves them, so refs work with Playwright locators and auto-waiting, without modifying the DOM |
| Navigation guard | `context.route("**/*")` aborts main-frame navigations the URL policy rejects; the tab returns to the previous page |
| Console | `onConsoleMessage`, `onPageError` (stack parsed), unhandled rejections via an exposed function + init script (deduplicated against page errors) |
| Network | `onRequest/onResponse/onRequestFinished/onRequestFailed`, timing, redirects, WebSockets; bodies only if `cdb.capture.bodies=true` |
| Screenshots | `page/locator.screenshot` with masks over sensitive inputs |
| Discovery | Chrome, Edge, Chromium and the Playwright cache on Windows, macOS, Linux |

## Chrome extension provider (your own Chrome)

`extension-provider` turns tabs the user shares from the Chromium Developer Bridge extension into sessions
(`SessionManager.attach` with a `SessionOpener`). Each session drives one tab with raw CDP relayed by
`chrome.debugger`: navigation via `Page.*`, element location and actions via the shared page helper script
(`browser-core/src/main/resources/cdb/page-helpers.js`) plus real `Input.dispatchMouseEvent/KeyEvent` events, console
and network from `Runtime`/`Log`/`Network` events, screenshots via `Page.captureScreenshot`. Reads and captured data
are limited to allowed origins. Details: [CHROME_EXTENSION.md](../implementation/CHROME_EXTENSION.md),
[ADR-0006](../adr/0006-chrome-extension-provider.md).

Quick attach (`connectEndpoint`) uses the Playwright provider with the browser's existing default context, so the
tabs already open in that Chrome are visible; closing the session disconnects without closing them.

## JCEF (IntelliJ embedded browser)

Assessed and not used for automation; see [ADR-0001](../adr/0001-external-managed-chromium-as-primary-browser.md).
JCEF exposes CDP only through an IDE-wide, unauthenticated debug port and lacks Playwright's isolation and
capture. The plugin shows a live preview of the driver-managed Chromium (JPEG frames from
`GET /sessions/{id}/preview`, clicks forwarded as `CLICK_AT`). Headed sessions can also be watched in their own window.

## Adding a provider

1. Create a module depending on `browser-core`.
2. Implement `BrowserProvider` and `BrowserSessionDriver`; call the listener for events (never block it).
3. Expose it as a Spring bean in the driver (replacing `browserProvider` in `CoreConfiguration`).
4. Run `browser-core`'s `SessionManagerTest` scenarios against it (see `PlaywrightProviderBrowserTest` for a template).
