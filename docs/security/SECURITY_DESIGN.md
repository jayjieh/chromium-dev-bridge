# Security Design

## Authentication & authorisation
* Two random 256-bit tokens are generated at start-up (or configured, ≥ 24 chars, e.g. for CI):
  * **admin token**: the human user (IntelliJ plugin, CLI). All operations.
  * **agent token**: AI agents (MCP server). Can't grant approvals, can't evaluate JavaScript, can't attach to
    browsers over CDP, can't set `agentAccess`.
* Tokens are written to `~/.chromium-dev-bridge/driver.json` (owner-only permissions) and deleted on shutdown.
* Comparison is constant-time. `X-CDB-Client` gives agent principals a name (`agent:claude-code`), which scopes session
  ownership.
* `GET /api/v1/health` and `/actuator/health` are the only unauthenticated endpoints.

## Request filtering (`SecurityFilter`, first in the chain)
1. Correlation id (`X-Correlation-Id`, generated if absent) → MDC and response header.
2. Host header must be in `cdb.security.allowed-host-headers` (DNS rebinding protection).
3. Requests with an `Origin` header are rejected unless listed in `cdb.security.allowed-origins` (empty by default).
4. Body size limit (`max-request-bytes`, default 1 MiB), including chunked bodies.
5. Bearer authentication → principal.
6. Token-bucket rate limit per principal (default 50 req/s, burst 200).
7. `nosniff` and `no-store` headers.

## Browser-side controls
* **URL policy**: development hosts (`localhost`, `127.0.0.1`, `[::1]`, `*.localhost`, `*.test`, plus configured
  patterns with optional ports) are allowed. Other origins need an `ORIGIN` approval (admin). Enforced before navigation
  and inside the browser for main-frame navigations (link clicks, redirects, `location.href`).
* **Action policy**: on approved non-development origins, state-changing actions need `SENSITIVE_ACTIONS` approval.
* **Isolation**: each session is its own browser context (cookies, storage, cache); `PROCESS` isolation gives a
  separate browser process.
* **Downloads** off by default; **JS evaluation** off by default (`allow-evaluate`), admin + `EVALUATE` approval.
* The user's personal browser profile is never used.

## Data protection
| Data | Handling |
|---|---|
| Password / OTP / card / hidden inputs | Never serialised in snapshots or inspect results (`[REDACTED]`); masked in screenshots |
| Request/response headers | `Authorization`, `Cookie`, `Set-Cookie`, API keys, session/token headers → `[REDACTED]` |
| URLs | Sensitive query parameters and user-info redacted |
| Bodies | Off by default; when on, size-limited, JSON/form secrets, JWTs, keys, e-mails and card numbers redacted |
| Console messages | Tokens, JWTs, bearer values, key=value secrets redacted |
| Action values | Never logged; audit records the value length only |
| Scenario reports | All substituted variable values replaced with `***` |
| Metrics | Counters and timers tagged with action type/outcome only |

## Prompt-injection safeguards (MCP)
* All page-derived text in tool results is wrapped in `<untrusted-page-content>` markers with an explicit notice;
  attempts to close the marker are neutralised.
* Heuristics flag common injection phrasing; results start with a WARNING and include `promptInjectionWarnings`.
* The server instructions tell agents not to follow page instructions.
* Capabilities an injected instruction would need (real sites, JS execution, approvals, env secrets) are not
  available to the agent token.

## Audit & retention
* `~/.chromium-dev-bridge/audit/audit-YYYY-MM-DD.jsonl`: session create/close/restart, navigations (redacted URL),
  actions (type, locator description, value length), approvals, evaluations, baselines. Kept `cdb.audit.retention-days` (14).
* Screenshots: `per-session` (100) and `max-age` (7 days).

## Configuration reference (security)
```yaml
cdb.security:
  allowed-hosts: [localhost, 127.0.0.1, "[::1]", "*.localhost", "*.test"]
  allow-file-urls: false
  allow-evaluate: false
  accept-downloads: false
  enforce-navigation-guard: true
  allowed-origins: []
  allowed-host-headers: [localhost, 127.0.0.1, "[::1]"]
  rate-limit-per-second: 50
  rate-limit-burst: 200
  max-request-bytes: 1048576
  max-websocket-message-bytes: 65536
```
