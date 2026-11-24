# ADR-0003: Merge chromium-provider and cdp-provider into playwright-provider

* Status: Accepted
* Date: 2026-11-24

## Context

The initial brief listed `chromium-provider`, `playwright-provider` and `cdp-provider` modules.

## Decision

A single `playwright-provider` module with packages:

* `dev.cdb.provider.playwright` — launch, contexts, pages, capture.
* `dev.cdb.provider.playwright.discovery` — finds installed Chrome / Edge / Chromium on Windows, macOS, Linux.
* attach mode (`SessionCreateRequest.connectEndpoint`) — `BrowserType.connectOverCDP` restricted to loopback.

`browser-core` defines the SPI, so other providers can be added as new modules.

## Consequences

Less duplication; one place for Chromium specifics. A raw CDP provider without Playwright is not offered.
