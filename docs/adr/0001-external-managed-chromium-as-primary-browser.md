# ADR-0001: Playwright-managed Chromium is the primary automation browser

* Status: Accepted
* Date: 2026-11-24

## Context

The plugin is expected to embed a browser (JCEF) and the driver must automate a browser (Playwright). These
are different Chromium runtimes. They share no cookies, contexts or DOM.

Options considered:

1. **Automate JCEF through CDP.** JCEF exposes CDP only through the IDE-wide registry key
   `ide.browser.jcef.debug.port`. That port reaches every JCEF browser in the IDE, including IDE-internal UI,
   and has no authentication. Not acceptable for an agent-facing tool.
2. **Automate JCEF through `JBCefBrowser`/`CefBrowser` Java APIs.** These offer navigation and JS execution
   but no network capture, no structured console with stack traces, no accessibility tree and no isolated
   contexts. We would rebuild a weaker Playwright.
3. **Automate an external Playwright-managed Chromium; show it inside the IDE as a preview.**

## Decision

Option 3. The driver owns the browser. The plugin shows a live, low-frame-rate preview of the selected session
(viewport screenshots) and can open headed sessions. The UI always names the session being inspected.

## Consequences

* One browser runtime for automation, inspection and AI agents → consistent state.
* The in-IDE view is a preview, not a fully interactive embedded browser. Clicking in the preview is forwarded
  as a coordinate click to the driver (best-effort).
* A future `jcef-provider` can implement the same SPI if JetBrains offers a scoped, authenticated CDP channel.
