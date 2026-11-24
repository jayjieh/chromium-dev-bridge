# Implementation Plan

Toolchain verified on 2026-11-24 against Maven Central / Gradle services:

| Component | Version | Notes |
|---|---|---|
| JDK | 17+ (LTS) runtime/target for driver and MCP server; 21 for the IntelliJ plugin | Lowered from 21 to 17 on 2026-11-24; CI tests the backend on 17 |
| Spring Boot | 3.5.16 | Latest 3.x line (4.x exists; the brief requires 3.x / Spring Framework 6.x) |
| Playwright for Java | 1.65.1 | Bundled Chromium downloaded on first use |
| Jackson | managed by Spring Boot BOM (2.x) | |
| MCP protocol | 2025-11-25 (also 2025-06-18, 2025-03-26, 2024-11-05) | Hand-rolled, see ADR-0005 |
| Maven | ≥ 3.8 | Backend modules |
| Gradle | 9.x via wrapper | IntelliJ plugin only |
| IntelliJ Platform Gradle Plugin | 2.x | Target IntelliJ IDEA 2025.2+ |
| JUnit 5 / Mockito / AssertJ | via Spring Boot BOM | |

## Phases and exit criteria

### Phase 1 — Foundation
1. Architecture docs, ADRs, feasibility (SYSTEM_DESIGN §4), OpenAPI contract. ✔ exit: docs committed.
2. Maven multi-module build; `shared-protocol` DTOs; `browser-core` SPI.
3. Session manager with lifecycle state machine, idle expiry, ownership rules. Exit: unit tests.
4. Playwright provider: workers, launch, contexts, tabs, disconnect detection, restart. Exit: integration test launches Chromium.

### Phase 2 — Driver MVP
5. Navigation, waits, actions with semantic locators and snapshot refs.
6. DOM snapshot script (full/compact/interactive/a11y), element inspection, sensitive value removal.
7. Console + page-error capture with stack parsing and source maps; network capture with redaction.
8. Screenshots, history, retention, baselines, pixel diff.
9. REST controllers + Problem Details; WebSocket JSON-RPC event stream with batching.
10. Security: tokens (admin/agent), host/origin validation, rate limit, size limits, URL policy, approvals, audit log.
11. Scenario runner (JSON format). Exit: integration tests against `sample-applications/login-demo`.

### Phase 3 — IntelliJ plugin
12. Gradle build, tool window with tabs, driver connection (connection file / start driver), preview panel,
    console/network/DOM/screenshots/sessions tabs, source navigation from stack frames, disposal.

### Phase 4 — MCP
13. stdio MCP server with 17 tools, structured output, untrusted-content envelopes.
14. Client configs for Claude Code and Codex.

### Phase 5/6 — Advanced & distribution (post-MVP)
Accessibility auditing beyond basic rules, performance profiling, plugin signing/Marketplace, installers,
upgrade mechanism, release automation.

## Working rules
* Each milestone: `mvn -q verify` must pass before moving on.
* Integration tests needing Chromium are in `integration-tests` and run with `-Pit` (they download Chromium).
* PROGRESS.md is updated at the end of each milestone.
