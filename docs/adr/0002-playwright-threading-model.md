# ADR-0002: One owning thread per Playwright instance ("worker") with an event pump

* Status: Accepted
* Date: 2026-11-24

## Context

Playwright for Java is not thread-safe. All calls on a `Playwright` instance and the objects it creates must
happen on one thread (or be externally synchronised). Events (`onConsoleMessage`, `onRequest`, …) are
dispatched only while that thread is inside a Playwright call.

## Decision

* `PlaywrightWorker` owns one thread, one `Playwright` instance and lazily launched `Browser`s.
* Every operation is submitted as a task to the worker's queue and awaited with a timeout.
* When the queue is idle, the worker calls `waitForTimeout(pumpInterval)` on any open page (default 25 ms)
  so events keep flowing even when no client is calling.
* Sessions are `BrowserContext`s. By default sessions with the same launch configuration share one worker
  (one Node driver + one Chromium process) → minimum resource use; their operations are serialised.
* `isolation=PROCESS` gives a session its own worker (own Chromium process), so it runs in parallel with
  others. Persistent-profile sessions always get their own worker.

## Consequences

* No Playwright object ever crosses threads; event callbacks run on the worker thread and only copy data into
  thread-safe buffers.
* A slow operation in one shared-worker session delays others; users who need parallelism choose `PROCESS`.
* Crash detection: `Browser.onDisconnected` moves affected sessions to `DISCONNECTED`; restart relaunches the
  browser and recreates the context.
