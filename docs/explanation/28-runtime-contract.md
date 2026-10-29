---
status: stable
description: "The JustSearch Runtime Contract: the small, versioned local surface external apps and AI agents target; the desktop app is its first-party client. Defines the v1 stable core (health/status lifecycle subset + MCP endpoint/tools + runtime manifest), discovered through the manifest's runtimeContract descriptor. Matrix + policy + surface classification live in docs/reference/runtime-contract.md."
---

# The JustSearch Runtime Contract

JustSearch exposes a **local runtime** for apps and AI agents. The desktop app uses that runtime as its first-party client.

External tools need *one stable object* to build against, not a bundle of
strong-but-scattered subsystems. The **Runtime Contract** is that object: a
small, deliberately under-promised surface, versioned as a whole, that an agent
or integration can rely on.

## What is in the contract (v1)

The contract promises exactly three surfaces — the minimum we are confident
holds stable. Everything else is reference-client or internal (see
[Surface classification](../reference/runtime-contract.md#surface-classification)).

1. **The runtime manifest** — the one discovery, liveness, and lifecycle
   object. An external tool reads it first and learns everything else from it,
   including where to reach the other surfaces. See
   [Runtime manifest](23-runtime-manifest.md).
2. **The health/status lifecycle subset** — `GET /api/health` (the
   `200`/`503` lifecycle gate) and the schema-v1 minimum fields of
   `GET /api/status` (`schema_version`, `observed_at`, `lifecycle.state`,
   `components.*`). The *extended* `/api/status` fields are **not** in the
   contract. Guarded by `LifecycleContractTest`.
3. **The MCP endpoint + curated tool surface** — `POST /mcp` (loopback-only,
   in-process Streamable HTTP) at the MCP protocol version this build speaks,
   plus the enumerated curated tool set. See
   [Production MCP server](../reference/mcp-production-server.md).

## How the contract is discovered

The runtime manifest carries a `runtimeContract` descriptor: the coarse
**contract version** plus the pinned versions of the contract's constituent
surfaces (manifest schema, lifecycle subset, MCP protocol, MCP tool surface).
The MCP `initialize` response reports the same MCP versions by construction —
both read the same single-source constants (`RuntimeContract`,
`McpContractVersions`), so they cannot drift. The manifest's `reachability`
list also advertises the `/mcp` ingress, so "how do I reach the contract's MCP
surface?" is answered by the one discovery object, not a separate mechanism.

The exact version-pinning rules, the compatibility matrix, and the stability
policy live in the reference companion:
[Runtime Contract — versions, policy, and classification](../reference/runtime-contract.md).

## Reference client, not contract boundary

The desktop shell uses far more of the local API than the contract promises —
the full `/api/knowledge/*` search surface, health-event streams, boot-phase
traces, and more. Those surfaces **demonstrate** the runtime; they do **not**
become part of the contract because the reference client happens to call them.
Only the three surfaces above are promised. This boundary is deliberate: a
promise is cheap to add later and expensive to retract, so v1 under-promises.

## Product shapes

For integrations targeting the runtime, the ways to run it are official product
shapes rather than loose launch modes: a shape is a supported way to run the
Runtime Contract, distinguished by which reference-client and optional
capabilities it bundles — **not** by a different contract. All shapes advertise
the same manifest, contract version, and stable core. Packaging and model-pack
decomposition for those shapes are designed separately (tempdoc 657); the
developer onramp that introduces the object is tempdoc 656.

## What the contract does not claim

The Runtime Contract is a **defined, versioned local surface** — not a
certification. JustSearch makes no "certified", "compliant", or "conformant"
claim about its MCP surface: there is no MCP server certification program, and
MCP conformance tooling is spec/SDK-facing and still maturing. Externally
verifiable trust evidence (security, supply-chain, provenance) is a separate,
ongoing effort (tempdoc 659). The contract is also loopback-only by
construction — "external" means agents on the same machine, not remote access.
