# ADR-0005: Implement the MCP stdio protocol directly

* Status: Accepted
* Date: 2026-11-24

## Context

The official MCP Java SDK (`io.modelcontextprotocol.sdk:mcp`) was at 2.2.0 when checked (2026-11-24). Between
0.x, 1.x and 2.x it changed packages, builders and JSON bindings (2.x defaults to Jackson 3 and adds an
`io.modelcontextprotocol.modern` package). The server needs only stdio transport with `initialize`,
`notifications/initialized`, `ping`, `tools/list` and `tools/call`.

## Decision

Implement JSON-RPC 2.0 framing (newline-delimited JSON on stdin/stdout) and the MCP methods above with
Jackson 2 in `mcp-server`. Supported protocol versions: `2025-11-25` (preferred), `2025-06-18`,
`2025-03-26`, `2024-11-05`. Tools declare `inputSchema`, `outputSchema` and return both `structuredContent` and a
text block (for older clients). Logs go to stderr only.

## Consequences

* ~400 lines of protocol code under test, no SDK churn.
* Streamable HTTP transport is future work; switching to the SDK later is possible because tool handlers
  are transport-independent (`McpTool` interface).
