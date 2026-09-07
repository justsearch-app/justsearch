---
title: API Evolution Strategy
type: reference
status: stable
description: "Backward-compatible evolution policy for HTTP APIs and protobuf messages."
---

# API Evolution Strategy

## Policy

JustSearch uses **backward-compatible evolution** for all APIs. There is no `/api/v1/` path versioning. A version suffix is added only when a breaking change is genuinely needed (as done with `/api/settings/v2`).

## Why No Path Versioning

Frontend and backend are co-shipped in the Tauri bundle. There is no version skew between client and server — the UI always matches the API it was built against. Path-based versioning (`/api/v1/`, `/api/v2/`) adds routing complexity and maintenance burden without benefit for a loopback-only API.

## HTTP API Rules

| Change type | Safe? | Action required |
|-------------|-------|-----------------|
| Add new field to response body | Safe | None |
| Add new endpoint | Safe | None |
| Add new query parameter (optional) | Safe | None |
| Remove a response field | **Breaking** | Keep the old field during the applicable deprecation window, document its replacement, then remove |
| Rename a response field | **Breaking** | Add the new field alongside the old one, deprecate the old one, then remove it after the applicable window |
| Change a field's type | **Breaking** | Version the endpoint with a suffix (e.g., `/api/settings/v2`) |
| Remove an endpoint | **Breaking** | Add lifecycle metadata to `RouteContractPolicy`, keep the route through the applicable window, then remove it |

**Endpoint removal policy:** The agent guide states "No legacy endpoints — Don't resurrect removed APIs." Once removed, an endpoint stays removed. Public-contract routes have a minimum 90-day window from `deprecatedSince` to `sunsetAt`. A shorter pre-1.0 window requires an explicit rationale and an absolute decision-document URI in the same policy row. Reference-client and internal routes may use a shorter project decision, but still require explicit lifecycle metadata while the route exists.

### HTTP lifecycle authority and projections

`modules/ui/src/main/java/io/justsearch/ui/api/RouteContractPolicy.java` is the single authority for
per-route stability, schemas, SDK exposure, and lifecycle metadata. Lifecycle rows are immutable,
validated at startup/tests, and must resolve to exactly one registered `METHOD + route pattern`.
The production lifecycle catalog is currently empty: no live HTTP route is deprecated merely to
demonstrate the mechanism.

For a deprecated route, every response—including an exception-mapped response—carries:

- `Deprecation: @<unix-seconds>` (RFC 9745);
- optional `Sunset: <HTTP-date>` (RFC 8594); and
- `Link: <documentation-uri>; rel="deprecation"`.

The route-manifest schema `2.0` projects `stability`, schemas, SDK operation identity, and lifecycle
metadata. The full and SDK OpenAPI documents project `deprecated`, `externalDocs`,
`x-deprecated-since`, optional `x-sunset`, and `x-justsearch-replacement` from the same row. Allowed
browser origins can read `Deprecation`, `Sunset`, and `Link` through CORS; this changes response
visibility only and does not change Host, Origin, loopback-bind, or mutation-token admission.

## Proto message rules

Lane F stage A item A14 removed the last `service` blocks from `indexing.proto` and deleted the
`io/justsearch/ipc/v1/` protos, so there is no RPC surface to evolve and the "Add new RPC method"
row below is history. The **message** rules still bind: the generated classes are the DTOs at the
Engine's in-process ports, and a field-number change still breaks every persisted or cached
encoding of them.

| Change type | Safe? | Action required |
|-------------|-------|-----------------|
| Add new field | Safe | Use the next available field number |
| ~~Add new RPC method~~ | *n/a* | No `service` block exists — add a port method instead (see `governance/engine-ports.v1.json`) |
| Deprecate a field | Safe | Add `deprecated = true` option to the field |
| Remove a deprecated field | **After one release** | Replace field definition with `reserved` keyword (both number and name) |
| Reuse a field number | **Never** | Field numbers are permanent identifiers |

**Deprecation lifecycle:** `deprecated = true` option (one release minimum) then `reserved` keyword.

**Currently deprecated items** (add `reserved` when these are removed):
- `vdu_status` (field 3 in `UpdateVduResultRequest`) — replaced by `outcome` (field 6)
- `pruneMissing` port call — replaced by `syncDirectory` (was the `PruneMissing` RPC)

**Package naming:** `indexing.proto` uses `package io.justsearch.ipc;` (no `v1` suffix) — tracked as API1 in tempdoc 179. It is now the only proto file in the module; the `io.justsearch.ipc.v1` protos that carried the suffix were deleted at item A14.

### Compile-time safety

Buf is configured in `modules/ipc-common/src/main/proto/buf.yaml` with `WIRE` breaking change detection. This prevents accidental binary-incompatible changes (field number reuse, type changes, message removal) at build time. The `WIRE` rule is appropriate because Head and Body are co-shipped — there is no multi-version deployment.

Runtime compatibility currently relies on co-shipping, compile-time schema checks, contract tests, and explicit status/degradation signals. Do not document a runtime handshake client unless that client exists in the current codebase.

**Buf lint level:** Currently `MINIMAL` to avoid forcing naming changes on legacy `indexing.proto`, which is now the module's only proto file.

## Source of Truth

Contract tests are the authoritative source for API schema expectations:

| Contract test | What it verifies |
|---------------|------------------|
| `LifecycleContractTest` | `/api/status` response shape, field presence, HTTP semantics |
| `TelemetryHealthContractTest` | `/api/telemetry/health` response shape and field types |
| `SchemaMismatchStatusContractTest` | Schema mismatch status reporting contract |
| `WorkerSearchServiceReasonCodeContractTest` | Search reason-code allowlist on the response messages |

Proto files: `modules/ipc-common/src/main/proto/`
Route definitions: `modules/ui/src/main/java/.../routes/*.java`

**Java API surface comparison is deliberately not performed.** A Revapi baseline was scaffolded
here for that purpose and has been retired: the promised surfaces are the three wire-level
constituents of the Runtime Contract (runtime manifest schema, health/status lifecycle subset, MCP
endpoint + curated tools — see `docs/reference/runtime-contract.md`), and the Java API of
`modules/app-api` is not among them. Breakage on the promised surfaces is caught by the contract
tests above, the manifest schema-compatibility test, and constituent version pinning — the mechanism
that in practice detected and versioned the Runtime Contract `0.2.0` break. Adding a Java-API
comparator would guard an unpromised surface, and would additionally require publishing release
artifacts for a baseline to compare against, which this project does not do.

## See Also

- `docs/reference/contracts/search-and-rag-reason-codes.md` — degradation signaling contracts
- `CLAUDE.md` Hard Invariants — architectural invariants (includes "No legacy endpoints")
