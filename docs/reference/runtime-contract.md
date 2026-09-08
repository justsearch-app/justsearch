---
status: stable
description: "JustSearch Runtime Contract — versions, compatibility matrix, stability policy, and the public-contract / reference-client / internal surface classification. Reference companion to docs/explanation/28-runtime-contract.md. v1 is deliberately under-promised — only the runtime manifest, the health/status lifecycle subset, and the MCP endpoint + curated tools are promised."
---

# Runtime Contract — versions, policy, and classification

Reference companion to
[The JustSearch Runtime Contract](../explanation/28-runtime-contract.md) (the
positioning and definition). This page is the durable statement of *what is
promised, at what version, and how it may change*.

## Contract version and constituents

The runtime advertises a coarse **contract version** on the manifest
(`runtimeContract.version`) plus the pinned versions of its constituent
surfaces. Source of truth:
`modules/app-api/src/main/java/io/justsearch/app/api/runtime/RuntimeContract.java`
(`RuntimeContract.current()` projects each constituent from its own single
source — it invents no version except the umbrella).

| Constituent | Meaning | Single source (constant) |
|---|---|---|
| `manifestSchemaVersion` | Runtime manifest document schema | `RuntimeManifest.CURRENT_SCHEMA_VERSION` |
| `lifecycleSchemaVersion` | Health/status v1 subset schema | `LifecycleSnapshotV1.SCHEMA_VERSION` |
| `mcpProtocolVersion` | MCP spec version this build speaks | `McpContractVersions.PROTOCOL_VERSION` |
| `mcpToolSurfaceVersion` | JustSearch's own curated-tool-surface version | `McpContractVersions.TOOL_SURFACE_VERSION` |

The MCP `initialize` response reports `protocolVersion` from the **same**
`McpContractVersions` constant as the manifest, so the manifest and the MCP
handshake cannot desync. `serverInfo.version` is a different claim — the version
of the server *implementation*, i.e. the build — so it binds to the build-version
source (`EnvRegistry.APP_VERSION`, set by the packaged shell from the Tauri package
version), and the curated tool-surface version rides
`serverInfo._meta["io.justsearch/toolSurfaceVersion"]` alongside it. Reporting the
tool-surface version *as* the server version showed `0.5.0` on a `0.2.0` build
(sandbox round 10, F12).

## Compatibility matrix

The contract version is the single value an external tool targets. Its meaning
is the row of constituent versions below. The current build:

| Runtime Contract | manifest schema | lifecycle schema | MCP protocol | MCP tool surface |
|---|---|---|---|---|
| `0.3.0` | `2` | `1` | `2025-11-25` | `0.7.0` |

**Skew rule.** A client built for Runtime Contract vN works against a runtime
advertising vN. Older clients degrade gracefully: the manifest is
forward-compatible (unknown fields are ignored by tolerant readers;
`RuntimeManifestSchemaCompatibilityTest`), and MCP negotiates its protocol
version in `initialize`. There is no per-release compatibility table to
maintain — the manifest advertises the live versions, and a client reads them.

> On the MCP tool-surface version: the MCP protocol version says nothing about
> the stability of a server's tool set (MCP has no shipped tool-surface
> versioning; proposals point at per-tool SemVer). JustSearch versions its own
> tool surface here, SemVer-shaped, so the promise is explicit.

## Generated Node client

`packages/runtime-client` contains the pack-ready `@justsearch/runtime-client` reference package.
Version `0.1.0` is ESM-only, supports Node 20 and newer at runtime, and exposes six read-only JSON
operations from the public-contract surface:

- runtime manifest and its `/.well-known` mirror;
- runtime readiness and liveness;
- lifecycle health and status.

The client is generated from `packages/runtime-client/openapi/runtime-client.openapi.json`. That
document is a self-contained projection of the routes registered by the real Javalin route
registrars, filtered by `RouteContractPolicy`; it is not a second hand-authored API inventory.
The policy also derives each route's security declaration from `ApiSecurityFilters`, so the SDK
metadata cannot silently relax the local API trust boundary.

The package deliberately excludes mutations, MCP, token bootstrap, `HEAD` probe aliases, and the
manifest SSE stream. MCP clients should use the official MCP TypeScript SDK. The async client
factory fails closed before returning a client: it rejects non-loopback base URLs, disables HTTP
redirects, reads the runtime manifest, and requires an advertised Runtime Contract version accepted
by `assertRuntimeContractCompatible` (currently exactly `0.3.0`). A new runtime-contract version
therefore requires an explicit client compatibility decision rather than an optimistic range.

Regenerate and verify from the repository root:

```text
./gradlew :modules:ui:generateRuntimeClientOpenApi -PskipWebBuild=true
npm ci --prefix packages/runtime-client --ignore-scripts
npm --prefix packages/runtime-client run generate
npm --prefix packages/runtime-client run check:regen
npm --prefix packages/runtime-client test
npm --prefix packages/runtime-client run check:pack
```

CI performs generation drift checks with the repository Node toolchain and executes the built
client at the Node 20 runtime floor. The packed README links to the runtime-manifest discovery
contract and tells native callers to pass its `head.apiBaseUrl` value to the client factory. Package
publication runs a fail-closed `prepublishOnly` lifecycle that checks generated-source coherence,
builds and tests the client, and verifies the tarball contents before npm can publish it. That
publication preflight requires the Node 22.18+ generation toolchain even though the resulting client
supports Node 20 at runtime.

npm publication remains a founder action; a green package gate or dry-run does not imply that this
repository has published the package.

## Stability policy

- **Scope.** Only the surfaces classified *public-contract* below are promised.
  Everything else may change without notice.
- **Additive within a major.** New optional manifest fields, new tools, and new
  optional response fields do not break the contract and do not bump the
  contract version. A removal or a required-shape change is a breaking change.
- **Bump-only-on-break.** The coarse contract version is bumped only on a
  backward-incompatible change to a constituent — never on an additive change
  (mirroring MCP's own dated-version rule).
- **Pre-1.0 by design.** `0.x` means the surface may still change while we
  settle it (SemVer clause 4). A scoped `1.0` — "we will not break this" — is
  declared only when it is true, and covers only the enumerated public-contract
  surfaces (SemVer clause 5).
- **Deprecation window.** Anything promised is kept for **at least 90 days**
  after a deprecation notice before removal, so a JustSearch consumer never
  faces a shorter fuse than MCP's own expedited-removal floor. While the
  contract is `0.x`, the "Pre-1.0 by design" clause above can override this
  window for a specific removal; every such override is a recorded decision in
  the changelog (the `0.2.0` row is the first). From a scoped `1.0` onward the
  window is unconditional.

## Changelog

The contract version bumps only on a backward-incompatible change to a
constituent (never on an additive change), so this log has one entry per bump —
not per release. Mirrors the internal `contracts/wire/CHANGELOG.md` convention.

| Contract | Date | Change |
|---|---|---|
| `0.1.0` | 2026-07-02 | Initial contract. Names the three public-contract surfaces (runtime manifest, health/status lifecycle subset, MCP endpoint + curated tools) and pins their constituent versions (manifest schema `1`, lifecycle schema `1`, MCP protocol `2025-11-25`, MCP tool surface `0.1.0`). Pre-1.0 — the surface may still change while it settles. |
| `0.2.0` | 2026-07-21 | **First break to a constituent** (tempdoc 770). MCP tool surface `0.4.0` → `0.5.0` removes fields from the default `structuredContent` of the curated tool set: `justsearch_search` no longer emits per-hit `trace`/`legScores` by default (recoverable by passing `detail: true`) nor a `path`-duplicating `id` (`id` now ships only when it differs from `path`), and `justsearch_answer` no longer emits `facets` at all — the second full hybrid search that sourced it was removed, and the answer path has no other facet source. **Owner decision:** the 90-day deprecation window below is *overridden here by the "Pre-1.0 by design" clause* — these fields are removed without a prior deprecation notice, which `0.x` permits and which the measured evidence supports (per-hit provenance was requested in 0 of 1,081 calls; facet affordances produced zero behavioral adoption across three campaigns). The window applies again as stated once a scoped `1.0` is declared. **Correction:** the compatibility matrix above had continued to show MCP tool surface `0.1.0` through the additive `0.2.0`/`0.3.0`/`0.3.1`/`0.4.0` bumps; the matrix is a snapshot of constituent versions, not only of breaking ones, so it was stale by four bumps and is now current. |
| `0.3.0` | 2026-09-08 | Runtime manifest schema `1` → `2`. The private filesystem manifest gains identity-safe managed-child ownership and shutdown-handoff records, while the public projection moves to its v2 schema and omits those private fields. The obsolete worker `grpcPort` field is removed. Current readers accept v1 manifests without child records; future versions and unknown fields remain fail-closed where strict admission is required. |

## Surface classification

The v1 boundary. This table defines the promise, while `RouteContractPolicy`
mechanically classifies the HTTP rows represented in the route manifest. Route-manifest schema
`2.0` projects each covered route's `stability` (`public-contract`, `reference-client`, or
`internal`) together with its schemas, SDK operation identity, and any lifecycle metadata. The
policy is intentionally a covered subset of the live router, not a claim that every internal route
is part of the Runtime Contract.

| Tier | Surfaces | Promise |
|---|---|---|
| **Public-contract** | Runtime manifest + its standard transports (`GET /api/runtime/manifest`, the `/.well-known/justsearch/manifest.json` mirror, the manifest SSE stream, the `GET /api/runtime/ready`/`live` probes); the health/status **lifecycle subset** (`GET /api/health`, and the schema-v1 minimum fields of `GET /api/status`); the **MCP** endpoint (`POST /mcp`) + the curated tool set. | Versioned + deprecation-clocked (above). |
| **Reference-client** | Surfaces the desktop shell (and the manifest's `full` audience) use but that are **not** promised to third parties: the *extended* `/api/status` fields, `/api/knowledge/*` (search/suggest/status/ingest), boot-phase traces, health-event streams, governance state, the operation/agent-action substrate, retrieve-context and chat/conversation APIs, folder-browse, the OpenAI-compatible `/v1/*` shim, and `GET /api/meta/openapi.json`. The committed `reference-client-openapi.snapshot.json` is a deterministic derivative of the captured route manifest, explicitly classified `runtimeContract: false`; only a paired live capture proves that snapshot matches the current router. | May change; not promised. Demonstrated by the reference client, not defined by it. |
| **Internal** | Not for external callers: `/api/debug/*`, the Head↔index-half in-process ports and their `indexing.proto` DTOs, the `contracts/wire` protos, filesystem-only manifest fields (`head.sessionToken`, `children`, `shutdownHandoff`), and the `/infra/capabilities` FE↔Head capability handshake. | No stability, no external audience. |

## What the contract does not claim

The Runtime Contract is a **defined, versioned local surface** — not a
certification. There is no "certified", "compliant", or "conformant" claim:
there is no MCP server certification program, and MCP conformance tooling is
spec/SDK-facing and still maturing. The official MCP registry offers
**discovery + namespace/provenance verification** (it authenticates *who
published* a server, not its quality) — so the honest, checkable status
JustSearch can pursue is *"listed, namespace-verified"*, never *"certified."*
Externally verifiable trust evidence is a separate, ongoing effort (tempdoc
659). The contract is loopback-only by construction — "external" means agents
on the same machine, not remote access.

## See also

- [The JustSearch Runtime Contract](../explanation/28-runtime-contract.md) — positioning + definition.
- [Runtime manifest](../explanation/23-runtime-manifest.md) — the discovery object the contract rides on.
- [API contract map](api-contract-map.md) — the full surface inventory.
- [Production MCP server](mcp-production-server.md) — MCP client setup.
