# MCP Tools

Server name `chromium-dev-bridge`, stdio transport, protocol versions `2025-11-25`, `2025-06-18`, `2025-03-26`,
`2024-11-05`. Every tool returns a text block (for all clients) and `structuredContent` matching its `outputSchema`
(for 2025-06-18+). Tool failures come back as results with `isError: true` and an error code plus a hint.

| Tool | Purpose | Key inputs |
|---|---|---|
| `browser_create_session` | Create an isolated session, optionally open a URL | `url`, `headless`, `viewportPreset`, `projectId` |
| `browser_list_sessions` | Sessions this agent can use | — |
| `browser_close_session` | Close and release resources | `sessionId` |
| `browser_navigate` | Navigate to an approved URL or relative path; or `direction` back/forward/reload | `sessionId`, `url`, `waitUntil`, `timeoutMs` |
| `browser_snapshot` | Compact DOM snapshot with refs (`compact`, `interactive`, `full`, `accessibility`) | `sessionId`, `mode`, `root`, `role`, `text`, `maxElements` |
| `browser_inspect_element` | Attributes, computed styles, bounds, form state | `sessionId`, `ref` or `locator`, `styles` |
| `browser_click` | Click / double-click | `sessionId`, `ref` or `locator`, `doubleClick` |
| `browser_type` | Fill a field (or type slowly), optional key press | `sessionId`, target, `text`, `slowly`, `pressKey` |
| `browser_select` | Select options by value or label | `sessionId`, target, `values` |
| `browser_scroll` | Scroll page or element | `sessionId`, target?, `deltaX`, `deltaY` |
| `browser_wait` | Wait for element state, text, URL glob or load state | `sessionId`, target?, `state`, `text`, `urlPattern`, `loadState` |
| `browser_screenshot` | Screenshot (image content + `screenshotId`) | `sessionId`, `fullPage`, target?, `viewportPreset`, `format` |
| `browser_console_logs` | Console, page errors, rejections with original source locations | `sessionId`, `level`, `text`, `afterSeq`, `limit` |
| `browser_network_logs` | Redacted requests | `sessionId`, `failedOnly`, `fetchXhrOnly`, `status`, `urlPattern`, `resourceType` |
| `browser_accessibility` | ARIA snapshot + audit findings | `sessionId`, `root` |
| `browser_test_flow` | Run a declarative scenario; temporary session if none given | `scenario`, `sessionId?` |
| `browser_compare_screenshots` | Pixel diff vs. screenshot or named baseline; save baselines | `candidateScreenshotId`, `baselineScreenshotId`/`baselineName`, `saveAsBaseline` |

Locator object: `{ role, name, exact, testId, label, placeholder, text, css, xpath, nth }`. Prefer snapshot `ref`s,
then role + name, test id, label, text; CSS and XPath are last resorts.

## Safety properties

* The MCP server authenticates with the **agent token**: it can't grant approvals, can't evaluate JavaScript, and
  only sees its own sessions and sessions the user shared (`agentAccess`).
* No arbitrary JavaScript tool is exposed.
* All page-derived text is wrapped in `<untrusted-page-content>` markers with a notice, and common prompt-injection
  phrasing raises a visible `WARNING` plus `promptInjectionWarnings` in structured output.
* Navigation outside development hosts fails with `URL_NOT_ALLOWED` until the human approves the origin in the IDE.
* Each tool call is time-limited (driver operation timeouts; client timeout 150 s) and recorded in the audit log.
* Tool invocation durations are recorded by the driver as `cdb.mcp.tool` (tag `tool`).

## Example exchange

```json
{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"browser_click","arguments":{"sessionId":"session-1a2b3c4d","ref":"element-12"}}}
{"jsonrpc":"2.0","id":7,"result":{"content":[{"type":"text","text":"CLICK ok (41 ms), navigated to http://localhost:3000/dashboard"}],
 "structuredContent":{"success":true,"type":"CLICK","durationMs":41,"pageUrl":"http://localhost:3000/dashboard","navigated":true},"isError":false}}
```
