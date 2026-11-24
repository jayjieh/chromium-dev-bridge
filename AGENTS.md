# AGENTS.md — guidance for AI coding agents working on this repository

## Build & test
* Backend: `mvn verify` from the repository root (JDK 21). `-DskipITs` skips `integration-tests` (needs Node + Chromium).
* Single module: `mvn -q install -pl browser-core` (install upstream modules first).
* Real-browser provider test: `mvn -q test -pl playwright-provider -Dcdb.browserTests=true`.
* Plugin: `mvn install -DskipTests` first, then `cd intellij-plugin && ./gradlew buildPlugin test`.

## Architecture rules
* `browser-core` stays free of Spring and Playwright. The page helper script used by both providers lives in
  `browser-core/src/main/resources/cdb/page-helpers.js` (run `mvn clean install` after moving resources).
* The Chrome extension (`chrome-extension/`) only relays CDP commands listed in `common.js` `ALLOWED_COMMANDS`; add a
  command there only with a security review. Providers implement `dev.cdb.core.spi.*`.
* Playwright objects are only touched on their `PlaywrightWorker` thread (ADR-0002). Use `worker.call(...)`.
* Every API change starts in `docs/api/OPENAPI.yaml`. `OpenApiContractTest` fails if controllers and the document differ.
* DTOs live in `shared-protocol` as records. Keep them backward compatible (add fields, don't rename).
* Security checks (URL policy, approvals, redaction, ownership) belong in `SessionManager`, not in controllers or the
  MCP server.
* Never log or return form values, cookies, tokens or passwords. Use `Redactor`.
* MCP tools must wrap page-derived text with `UntrustedContent.wrap` and never expose JavaScript evaluation.

## Conventions
* Java 21, records for data, `BridgeException(ErrorCode, message)` for domain errors.
* Tests: JUnit 5 + AssertJ; fast tests use `FakeProvider` (browser-core test-jar).
* Update `docs/implementation/PROGRESS.md` when you complete a roadmap item, and add an ADR for significant decisions.
