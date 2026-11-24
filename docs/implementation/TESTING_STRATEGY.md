# Testing Strategy

| Layer | Where | Browser | Run |
|---|---|---|---|
| Protocol | `shared-protocol` | — | `mvn verify` |
| Core unit tests | `browser-core` (`FakeProvider`) | fake | `mvn verify` |
| Provider (real Chromium) | `playwright-provider` `PlaywrightProviderBrowserTest` | Chromium | `-Dcdb.browserTests=true` |
| Driver API & security | `browser-driver` (`ApiIntegrationTest`, `OpenApiContractTest`) | fake | `mvn verify` |
| Client | `browser-api` | stub HTTP server | `mvn verify` |
| MCP protocol | `mcp-server` (`McpServerTest`) | stub driver | `mvn verify` |
| End-to-end | `integration-tests` (`DriverEndToEndTest`, `McpEndToEndTest`) | Chromium + Node sample app | `mvn verify` (skip with `-DskipITs`) |
| Plugin | `intellij-plugin` (`SourceNavigatorTest`, `PluginServicesTest`) | — | `./gradlew test` |

## What is covered
* **Unit**: state machine; URL policy (dev hosts, schemes, user-info tricks, port patterns, approvals); action policy;
  redaction (headers, URLs, JWTs, keys, bodies, cards, e-mails); ring buffers and network log pagination/filters; stack
  trace parsing; source-map decoding and resolution; image diff; scenario runner (env restriction, masking, failure
  screenshots); session ownership/isolation, limits, idle expiry, crash → auto-restart, evaluate gating.
* **API**: authentication, origin and host rejection, size limit, problem+json errors, full lifecycle over HTTP,
  agent vs admin scopes, WebSocket subscription/batching/auth, OpenAPI ↔ controller contract.
* **End-to-end (real Chromium)**: launch; navigation; validation messages; login; DOM snapshots with refs; sensitive
  value removal; console runtime error mapped to `src/dashboard.ts:27` through a source map; deliberate 500; auth
  header redaction; inspect; ARIA snapshot; viewport/full/element/mobile screenshots; session isolation (storage and
  identities); URL policy and approvals; scenarios with `${TEST_*}` credentials; visual regression with baselines and
  responsive viewport; accessibility audit; tabs; WebSocket events; resource release after close.
* **MCP end-to-end**: initialize/list (17 tools), session, snapshot, type, wait, console/network, screenshot image,
  compare, blocked navigation, prompt-injection page flagged, `browser_test_flow` login validation, close.
* **Plugin**: source navigation (path candidates, best match, caret position), settings, project session selection,
  event parsing, dev-server detection, DOM tree building, agent config snippets.

## Not automated (manual checks)
* Visual inspection of plugin panels in light/dark themes and headed sessions.
* macOS/Linux installation (CI matrix runs the backend tests on all three OSes).
