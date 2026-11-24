# WebSocket Event Protocol

Endpoint: `ws://127.0.0.1:<port>/api/v1/events`. The handshake needs `Authorization: Bearer <token>` (browsers can't
send it, which blocks web pages). Messages are JSON-RPC 2.0 text frames, at most 64 KiB from client to server.

## Client → server

| Method | Params | Result |
|---|---|---|
| `subscribe` | `{ "sessionId"?: string, "events"?: string[] }` (patterns like `browser.console.*`, `*`) | `{ "subscriptionId": "sub-1" }` |
| `unsubscribe` | `{ "subscriptionId": string }` | `{ "removed": true }` |
| `ping` | — | `"pong"` |

Errors: `-32700` parse error, `-32601` unknown method, `-32001` session not found or not accessible, `-32002` too many
subscriptions (50 per connection).

## Server → client

Events are batched every 50 ms (at most 200 per message):

```json
{
  "jsonrpc": "2.0",
  "method": "events",
  "params": {
    "dropped": 0,
    "events": [
      {
        "eventId": "5c1e…",
        "type": "browser.console.error",
        "sessionId": "session-1a2b3c4d",
        "tabId": "tab-1",
        "timestamp": "2026-11-24T10:00:00.123Z",
        "data": { "level": "ERROR", "message": "Failed to load application data", "source": { "file": "dashboard.js", "line": 22, "column": 13 },
                  "originalSource": { "file": "src/dashboard.ts", "line": 20, "column": 1 } }
      }
    ]
  }
}
```

Each connection has a bounded queue (2000 events). When a client is slow, the oldest events are dropped and `dropped`
counts them. Clients should fall back to the paginated REST endpoints (`/console?afterSeq=`, `/network?afterSeq=`).

## Event types

| Type | `data` |
|---|---|
| `browser.session.created` | `SessionInfo` |
| `browser.session.closed` | `{ reason }` |
| `browser.session.state` | `{ from, to }` |
| `browser.navigation.started` / `completed` | `{ url }` |
| `browser.navigation.failed` | `{ url, error }` |
| `browser.navigation.blocked` | `{ url, reason }` |
| `browser.console.message` / `browser.console.error` | `ConsoleEntry` |
| `browser.network.request` / `response` / `failed` | `NetworkEntry` (redacted) |
| `browser.screenshot.created` | `ScreenshotInfo` |
| `browser.tab.created` | `TabInfo` |
| `browser.tab.closed` | `{ tabId }` |

Agent-scope connections only receive events of sessions they may access.
