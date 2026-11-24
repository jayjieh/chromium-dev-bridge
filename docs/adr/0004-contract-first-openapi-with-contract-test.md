# ADR-0004: Hand-written OpenAPI 3.1 contract + contract test (no server code generation)

* Status: Accepted
* Date: 2026-11-24

## Context

The API must be defined in OpenAPI before controllers are written. Code generators (openapi-generator) produce
interfaces and DTOs, but their output for OpenAPI 3.1 (`oneOf`, nullable via type arrays) is uneven, and
the DTOs are also needed by the MCP server and plugin, which don't want generator runtime dependencies.

## Decision

* `docs/api/OPENAPI.yaml` (OpenAPI 3.1) is the source of truth and was written first.
* DTOs live in `shared-protocol` as Java records.
* `OpenApiContractTest` in `browser-driver` parses the YAML and asserts that the set of
  `(method, path)` operations equals the set served by Spring MVC controllers under `/api/v1`.
* The driver serves the document at `GET /api/v1/openapi.yaml`.

## Consequences

The contract can't silently drift from the implementation. Schemas are kept in sync by review; a later step
could add JSON-schema validation of sample responses.
