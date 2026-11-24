# IntelliJ Plugin Guide

The **Chromium Bridge** tool window (right side by default; drag it to the bottom if you prefer) has eight tabs.

| Tab | What it does |
|---|---|
| Browser | Address bar (pre-filled from detected dev servers: Vite, Next.js, Angular, CRA, Spring Boot), back/forward/refresh/stop, open/close tab, viewport presets, screenshot, open externally, new session, connection status. The central area is a live preview of the inspected session; clicks are forwarded to the page. The footer always names the session being inspected. |
| Elements | Collapsible DOM tree from a compact/interactive/full snapshot, with a filter. Selecting a node shows attributes, computed styles, bounds and form-field state (sensitive values never shown). |
| Console | Real-time console messages, uncaught errors and unhandled rejections with stack frames. Double-click an entry or frame to open the source file at the line (source-map paths resolved against the project). |
| Network | Request table with filters (failed, fetch/XHR, URL), details pane with redacted headers, waterfall column. |
| Screenshots | History, viewer, capture viewport/full page, compare two screenshots (diff image shown). |
| AI Agent | Ready-to-copy MCP setup for Claude Code (`claude mcp add …`, `.mcp.json`) and Codex (`config.toml`). |
| Sessions | All sessions with state/owner/project; create, inspect, restart, close; **Approve origin** and **Allow sensitive actions** (the approvals agents cannot grant themselves). |
| Settings | Connect/start or disconnect the driver; link to *Settings | Tools | Chromium Developer Bridge*. |

## How it connects
* `DriverService` (application service) reads `~/.chromium-dev-bridge/driver.json` and uses the **admin token**.
  If no driver is running and *Start the driver automatically* is on, it starts the configured jar and waits for health.
* `EventStream` keeps a WebSocket open to `/api/v1/events` with automatic reconnect; `ProjectBridge` (project service)
  forwards the selected session's events to the panels.
* Sessions created in the IDE carry `projectId = <project name>` and are shared with agents if *Let AI agents use
  sessions created in the IDE* is enabled.

## Threading & lifecycle
* Every driver call runs on a pooled thread through `background { }`; the UI is updated on the EDT.
* Panels register listeners with the tool window's `Disposable`; `ProjectBridge` unsubscribes from the event stream when
  the project closes; `DriverService` closes the WebSocket when the IDE exits. The driver itself keeps running (other
  projects or agents may use it); close it from the terminal, or let idle sessions expire.

## Themes
All colours come from `JBColor`/`SimpleTextAttributes`, so light and dark themes work without extra configuration.
