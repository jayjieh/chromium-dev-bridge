# Threat Model

## Assets
* The developer's machine and accounts reachable from it (browser state, cookies, credentials).
* Application test data and secrets in the driver's environment (`TEST_*` credentials, API keys).
* Integrity of the developer's source code (an agent acting on false browser findings).

## Trust boundaries
1. **Web pages ↔ local driver**: any site the developer visits (in their own browser) can try to reach `127.0.0.1`.
2. **Page content ↔ AI agent**: DOM text, console messages and network responses flow into an LLM's context.
3. **AI agent ↔ driver**: the agent is semi-trusted: it acts for the user but can be steered by page content.
4. **Other local processes/users ↔ driver**: other OS users or local malware.
5. **Driver ↔ external sites**: the automated browser may be pointed at real websites.

## Threats and mitigations (STRIDE-style)

| # | Threat | Mitigation |
|---|---|---|
| T1 | A malicious web page sends requests to the driver (CSRF) | Bearer token required; requests with an `Origin` header are rejected unless allowlisted; WebSocket handshake needs `Authorization`, which browsers can't set |
| T2 | DNS rebinding (attacker domain resolves to 127.0.0.1) | `Host` header allowlist (`localhost`, `127.0.0.1`, `[::1]`) |
| T3 | Remote network access | Binds to `127.0.0.1` only; CDP never exposed (Playwright uses a pipe); CDP attach limited to loopback and admin |
| T4 | Another local user reads the tokens | Connection file and directory restricted to the owner (POSIX `rw-------` / Windows owner-only ACL); tokens regenerated each start |
| T5 | Indirect prompt injection steers the agent to exfiltrate data or act on real sites | Page text wrapped as untrusted with a notice; injection heuristics flagged; URL allowlist (dev hosts only); approvals for other origins and sensitive actions only via the admin token (human); no JS evaluation over MCP; scenario env vars limited to `TEST_*` |
| T6 | Agent uses one session to read another's data | Sessions are separate browser contexts; agent principals only access their own sessions or explicitly shared ones; same 404 for "missing" and "not yours" |
| T7 | Secrets leak via snapshots, logs, reports, metrics | Sensitive fields never serialised (password, `autocomplete=cc-*`, OTP, names matching secret patterns); headers/cookies/query tokens/JWTs/keys redacted; fill values never logged (only length); scenario substitutions masked; metrics carry no page data |
| T8 | Navigation to `file://`, `chrome://`, `javascript:` URLs | Scheme allowlist (http/https/ws/wss; `file` only if configured) |
| T9 | Resource exhaustion (DoS) by a buggy agent | Rate limiting, request/WebSocket size limits, max sessions, bounded buffers and queues, operation timeouts, idle expiry, screenshot retention |
| T10 | Arbitrary code execution through the API | No executable path in requests (config only); profile names validated; evaluate disabled by default and admin + approval only |
| T11 | Downloads drop files on disk | Downloads disabled by default (`acceptDownloads=false`) |
| T12 | Repudiation: who did what | Local JSONL audit log with principal, operation, session, outcome, correlation id; retention configurable |
| T13 | Attaching to the user's personal browser profile | Never automatic: persistent profiles live under the driver's data dir; attach requires an explicit loopback endpoint and the admin token |

| T14 | A shared tab of the user's real browser exposes personal data to agents | Sharing is per tab and explicit (popup click); agent access is a separate choice; reads, screenshots, actions and captured console/network data are refused while the tab is on a non-allowed origin; the extension relays only allowlisted CDP commands (no cookie/storage APIs); Chrome's debugging infobar is always visible |
| T15 | A web page or another extension talks to the driver's extension endpoint | Endpoint requires a `chrome-extension://` origin (optionally pinned ids) and the pairing token as WebSocket sub-protocol; pairing codes are only accepted for loopback hosts |

## Residual risks
* An agent with shell access can read the connection file and use the admin token. The agent token separation
  protects against prompt-injected *tool use*, not against a fully compromised agent with shell access.
* Heuristic injection detection can be bypassed; the URL allowlist and approvals are the primary controls.
* On a development origin all actions are allowed: a local app wired to real third-party services (payments, email)
  can still cause real effects. Use test credentials and sandboxes.
* A user who approves a personal site from the extension popup gives tools (and agents, if allowed) access to that
  page while it is shared. The popup warns about this; approvals are per session.
* The extension token is long-lived (persisted for convenience); anyone who can read the user's home directory can
  pair. Delete `~/.chromium-dev-bridge/extension-token` to rotate.
* Service workers can bypass Playwright request routing for subresources (top-level navigation is still checked).
