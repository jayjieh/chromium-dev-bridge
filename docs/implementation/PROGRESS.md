# Progress

Last updated: 2026-11-24. Verified on Windows 11, JDK 21.0.9, Maven 3.8.1, Node 22.12, Gradle 9.2.1, IntelliJ Platform 2025.3.

## Test status
| Suite | Result |
|---|---|
| `mvn verify` (all modules) | ✅ 78 tests pass (the opt-in real-browser provider test is skipped by default) |
| `playwright-provider` with `-Dcdb.browserTests=true` | ✅ passes against Playwright Chromium 159 |
| `integration-tests` (real Chromium + sample app + MCP) | ✅ 8 tests pass |
| `intellij-plugin` `./gradlew buildPlugin test` | ✅ builds the plugin zip; 10 platform tests pass |
| Packaged jars as processes (start script + `java -jar cdb-mcp-server.jar` over stdio) | ✅ manual smoke test |

## Phase 1 — Technical foundation
- [x] Repository, Maven multi-module build, Gradle plugin build
- [x] Architecture docs, feasibility assessment (SYSTEM_DESIGN §4), ADR-0001…0005
- [x] Shared contracts (`shared-protocol`), OpenAPI 3.1 contract + contract test
- [x] JCEF vs Playwright assessment (ADR-0001)
- [x] Chromium lifecycle: launch, channels/executable, headless/headed, contexts, persistent profiles, CDP attach (loopback), disconnect detection, auto-restart, shutdown cleanup, local browser discovery
- [x] Session management: ids, ownership, state machine, idle expiry, limits

## Phase 2 — Browser driver MVP
- [x] Navigation (navigate, relative URLs, reload, back, forward, stop, page info, waits)
- [x] Tabs (list, open, close, activate, popups)
- [x] Actions (click, double-click, hover, scroll, fill, clear, type, press, select, check/uncheck, drag-and-drop, focus, text, attribute, visibility, coordinate click)
- [x] Semantic locators + stable snapshot refs (`cdbref` selector engine, no DOM mutation)
- [x] DOM snapshots: full / compact / interactive / accessibility; filters; inspect with computed styles; sensitive values removed
- [x] Console capture: levels, page errors, unhandled rejections, stack traces, source-map resolution
- [x] Network capture: requests, responses, timing, failures, redirects, WebSockets, CORS flag, filters, opt-in redacted bodies
- [x] Screenshots: viewport, full page, element, formats, history, retention, baselines, pixel diff, viewport presets, live preview frames
- [x] REST API + RFC 9457 problems; WebSocket JSON-RPC event stream with batching and bounded queues
- [x] Security foundation: tokens (admin/agent), host/origin checks, rate/size limits, URL allowlist + approvals, action policy, redaction, audit log, retention
- [x] Observability: correlation ids, MDC, Actuator, Micrometer meters (`cdb.*`), optional Prometheus, structured logging option

## Phase 3 — IntelliJ plugin
- [x] Tool window with 8 tabs (Browser, Elements, Console, Network, Screenshots, AI Agent, Sessions, Settings)
- [x] Driver lifecycle (connect via connection file, optional auto-start), WebSocket events with reconnect
- [x] Browser controls, live preview with click forwarding, viewport presets, external browser
- [x] Console with source navigation (source-map aware), network table with waterfall, DOM tree + inspector, screenshot viewer/compare, sessions + approvals
- [x] Dev-server detection (Vite, Next.js, Angular, CRA, Nuxt, SvelteKit, Spring Boot)
- [x] Background threading, Disposable-based cleanup, JBColor theming
- [x] Manual check in a running IDE (`./gradlew runIde`, IntelliJ IDEA 2025.3, dark theme) against a live driver: all 8 tabs, live preview with click forwarding, console streaming with source-mapped navigation to `src/dashboard.ts:27`, network waterfall, DOM tree + inspector, screenshots + compare, session restart, settings page
- [ ] Light theme check (theme switching couldn't be driven during the manual session; colours come from JBColor)
- [ ] `verifyPlugin` against recommended IDEs (configured; runs in CI, not run locally)

## Phase 4 — AI agent integration
- [x] MCP stdio server, protocol 2025-11-25 (+ 3 older versions), 17 tools with input/output schemas
- [x] Session authorization via the agent token, timeouts, validation, redaction, untrusted-content envelopes, injection flags
- [x] Claude Code, Codex and Cursor configurations; integration guides
- [x] AI-driven login validation proven by `McpEndToEndTest`

## Your own Chrome (added 2026-11-24)
- [x] Chrome extension (MV3, `chrome-extension/`): pairing, per-tab sharing, agent-access choice, origin approval, CDP allowlist, badge, reconnect
- [x] `extension-provider`: hub + raw-CDP session driver (navigation, waits, actions with real input events, snapshots, inspect, a11y, screenshots, console, network, source maps via core)
- [x] Driver endpoint `/api/v1/extension` (origin + persisted pairing token), `GET /api/v1/extensions`
- [x] Read gating on non-allowed origins for shared tabs
- [x] Quick attach reuses the running Chrome's tabs; `start-chrome-debug` scripts; IDE *Attach to Chrome…*
- [x] IDE: *Copy Chrome pairing code*, Browser column in Sessions
- [x] Tests: unit (keys, CDP translation, hub protocol), CDP driver vs real Chromium, real extension e2e (pair → share → drive → unshare), quick attach
- [ ] Manual check with the extension in a headed, everyday Chrome (automated tests use Playwright's Chromium in headless mode)
- [ ] Response bodies for shared tabs (`cdb.capture.bodies` is ignored by the extension provider)

## Phase 5 — Advanced (partially done)
- [x] Visual regression (baselines, thresholds, diff images)
- [x] Declarative JSON scenarios (assertions, `${TEST_*}` substitution, fixtures, per-step timeouts, failure screenshots, reports)
- [x] Basic accessibility audit (alt text, labels, control names, heading order, landmarks, duplicate ids, lang, title)
- [x] Responsive testing via viewport presets
- [x] Browser session recovery (auto-restart)
- [ ] Performance profiling (CDP tracing / Web Vitals)
- [ ] Richer source-map support (`names`, sections/index maps)
- [ ] Streamable HTTP MCP transport

## Phase 6 — Distribution (partially done)
- [x] Release bundles (`scripts/package-release.ps1|sh`): per-platform zips (~70–81 MB, driver slimmed to one Playwright runtime) and an all-platforms zip; plugin zip, unpacked Chrome extension, `bin/` setup + helper scripts, setup guide (`distribution/README.md`)
- [x] `bin/setup` records the install (`~/.chromium-dev-bridge/install.json`); the IDE plugin and MCP server start the bundled driver with no settings
- [x] `bin/mcp-config` prints Copilot (IntelliJ, VS Code, CLI), Claude Code and Codex configs with the bundle's paths
- [x] Release workflow (`.github/workflows/release.yml`, on `v*` tags)
- [ ] Marketplace / Chrome Web Store publishing (signing, listings) and an upgrade mechanism
- [ ] Hands-on verification on macOS and Linux (the CI matrix covers backend tests on all three OSes)

## MVP acceptance criteria
| # | Criterion | Evidence |
|---|---|---|
| 1 | Driver launches Chromium | `PlaywrightProviderBrowserTest`, `DriverEndToEndTest` |
| 2 | Navigates to a local app | `DriverEndToEndTest.loginFlow…` |
| 3 | Multiple isolated sessions | `sessionsAreIsolated`, `SessionManagerTest.agentsAreIsolatedFromEachOther` |
| 4 | Plugin connects to driver | Verified manually in `runIde` ("● Connected", status in Settings tab) |
| 5 | Navigation controlled from IntelliJ | Verified manually: Go / address bar, preview click forwarding |
| 6 | Console errors appear in IntelliJ | Console tab via WebSocket `browser.console.*` (event delivery tested in `tabsAndWebSocketEvents`) |
| 7 | Network requests appear in IntelliJ | Network tab via `browser.network.*` events |
| 8 | DOM elements can be inspected | `/dom`, `/elements/inspect`; Elements tab |
| 9 | Screenshots captured | e2e screenshots tests |
| 10 | Actions via API | `ApiIntegrationTest`, e2e |
| 11 | Agents invoke tools via MCP | `McpEndToEndTest`, process smoke test |
| 12 | Agent validates the sample login workflow | `McpEndToEndTest` (`browser_test_flow` PASSED) |
| 13 | Sensitive data redacted | `RedactorTest`, snapshot/inspect/network assertions in e2e |
| 14 | Resources released on close | `closeReleasesDriverAndRemovesSession`, e2e `SESSION_NOT_FOUND` after close |
| 15 | Automated tests pass | See test status above |
| 16 | Installation works on one OS | Windows verified; macOS/Linux procedures documented |

## Fixed after the manual IDE session (2026-11-24)
* Client sent `Accept: application/json` to the JPEG preview endpoint (HTTP 406), so the IDE preview never rendered; e2e test now covers `preview`.
* Operations on a session that is still STARTING failed with `SESSION_NOT_RUNNING`; the driver now waits up to 30 s for start-up.
* Source-map resolution finished after the live console event was sent, so the IDE showed compiled locations; the driver now re-sends the enriched entry and the Console tab refreshes it.
* Source lookup ran on the EDT (IntelliJ "slow operation" error); now a non-blocking read action.
* Browser toolbar was clipped in narrow tool windows and some glyphs did not render; now wrapping IntelliJ icon buttons.
* Elements tree went stale after navigation and only expanded a few rows; Settings tab lost its status lines; Sessions tab didn't mark the inspected session; screenshot viewer didn't scale; sensitive-field mask was magenta (now grey).

## Architectural deviations
* Provider modules merged into `playwright-provider` (ADR-0003). `browser-api` is the Java client SDK, not generated code (ADR-0004).
* MCP protocol implemented directly instead of using the SDK (ADR-0005).
* Spring AI is not used: the driver does not call an LLM (SYSTEM_DESIGN §4.5).
* JCEF is not used for automation; the plugin shows a live preview of the managed Chromium (ADR-0001).
* Added `GET /sessions/{id}/preview` (not in the original endpoint list) so IDE previews don't fill the screenshot history.
