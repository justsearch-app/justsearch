---
title: "ADR-0042: Live-registry witness — consumer-presence over the live ContributionRegistry"
type: decision
status: accepted
description: "A delivered contribution must carry a consumer, checked by LiveWitnessTest over the live ContributionRegistry, covering runtime-composed MCP and plugin contributions the static snapshot cannot see; core workflow operations are included in the static snapshot."
date: 2026-06-11
probes:
  - adr-0042-live-witness-checked
last_reviewed: 2026-10-20
---


# ADR-0042: Live-registry witness — consumer-presence over the live ContributionRegistry

## Status

Accepted. Adds the **live-registry tier** of tempdoc 560 §4b/§5 (the witness), and resolves the four
§E.2.1 decisions that 564's "481 Pass-3" parked, with defensible defaults (recorded below so they are
auditable and reversible).

Current implementation corrections are recorded in the 2026-10-20 amendment below.

## Context — consumer-presence already has two static tiers

1. The **`consumer-presence`** discipline gate: a static referencer scan over
   `RegistrySnapshotExporter`'s snapshot — it proves a declaration *names* a consumer
   (`consumerCount >= 1`).
2. The **`runtime-witness`** discipline gate (already shipped, tempdoc 560 §5 "second half"): still
   static, but bidirectional for the **AGENT** channel — it cross-checks the declared agent consumers
   against `snapshot.witness.agentDelivered` (the op ids `AgentOperationEmitter` *would* deliver,
   computed from the **static** catalogs). It catches AGENT-channel over-claim / phantom.

Both tiers read **static catalogs**. Contributions composed into the live `ContributionRegistry` **at
runtime** — projected workflow ops (`core.workflow-*`, via `WorkflowOperationProjection`), MCP tools,
plugin contributions — are **absent from the static snapshot**, so *neither* static tier examines them.
This is the empirically-demonstrated "DR-D" blind spot (tempdoc 560 §11.6): `GET /api/registry/witness`
shows `core.workflow-demo-compose` live with `buildWitnessed=false` — live but invisible to the
build-time witness, **by construction** (§11.2/DR-A: the build-tier witness is structurally static and
cannot see runtime composition).

Today every runtime-composed contribution happens to carry a consumer (`Workflow` and `Prompt` make a
zero-consumer declaration unrepresentable in their constructors). The gap is that `Operation` and
`Resource` **permit** an empty consumer set (the `NonEmpty<ConsumerHook>` enforcement is deferred), so a
future runtime-composed op/resource with zero consumers would be a silent orphan no static tier catches.

## Decision

Add a **live-registry witness** (`live-witness`): a check that every **delivered** `ConsumerDeclaring`
contribution in the **live** `ContributionRegistry` (operation / resource / prompt) carries at least one
consumer. It reuses the existing consumer-presence merge — it is **not** a second/forked authority, and
it **complements** (does not replace) the static `runtime-witness` gate: same §5 keystone, the tier that
sees runtime composition. It runs at the only tier that observes runtime composition: a backend
**live-registry integration test** (`LiveWitnessTest`) over a registry composed exactly as
`SubstratePhase` composes it (`OperationCatalogComposition.installBaseCatalogs` + `installWorkflowOps`).
The check is `LiveWitness.orphanedDeliveries(live)` (in `app-services`, co-located with
`RegistrySnapshotExporter`), which reuses `RegistrySnapshotExporter.operationConsumerIds` (inline ∪
executor-derived) for operations and `SurfaceConsumerIndex` (inline ∪ surface-derived) for resources.
The authority is named in a register (`governance/live-witness.v1.json`) and its presence + wiring are
guarded by a register-integrity early-warning (`scripts/ci/check-live-witness.mjs`), mirroring the
tempdoc 575 in-flight-liveness pattern — **not** a new offline kernel gate (an offline gate cannot see
runtime composition, per DR-A).

### The four §E.2.1 decisions — resolved as defensible defaults

1. **Agent-consumer witness shape → attest at the DELIVERY SEAM, not consumption.** A contribution is
   "delivered" when it is composed into the live registry with its `ConsumerHook.Realized` consumer
   present. An agent tool's consumer is satisfied by being *bound into the live agent toolset*, **not**
   by the model invoking it. Delivery ≠ invocation; this keeps the witness deterministic and testable.

2. **Per-actor deadline policy → none; attested-at-compose, tier-keyed.** Lockstep core/FE/agent attest
   at boot; independent plugin/MCP attest on their `install(Installation)`. "Undelivered" means
   declared-but-never-composed — a live absence, not a wall-clock timeout. JustSearch has no
   uncoordinated consumers, so RFC-9745/k8s grace windows do not apply.

3. **`Promised.sliceId` / SliceCatalog referential-integrity → out of scope.** The witness governs
   **Realized** hooks only. (`ConsumerHook.Promised` was reverted — only `Realized` exists today — so
   this is forward-compatible: a future `Promised` graduation stays a separate substrate primitive.)

4. **Runtime traffic counter → delivery-PRESENCE, not a count primitive.** The witness asks "is this
   delivered contribution's consumer present in the live registry?", not "did a hook receive ≥1 delivery
   this session." It reuses the live registry (and `/api/registry/witness`), introducing **no** new
   recursive `RegistryEntry`-shaped `TrafficCounter` primitive (AHA / no-fork).

## Rationale

- **Right tier.** DR-A established the build-tier witness is structurally static. The only tier that
  observes runtime composition is a live-registry test; per `static-green ≠ live-working`, that is
  exactly where a runtime invariant must live.
- **No fork.** The verdict semantics (the consumer merge) are the *same* as the static
  `consumer-presence` gate; the live witness applies them over the live set. The authority is registered
  (575-style) so it cannot be silently deleted, and the register cross-references the static
  `runtime-witness` gate so the two tiers stay legible as one concern.
- **Closes DR-D at the root.** A runtime-composed contribution is now examined for consumer-presence
  even though it never enters the static snapshot.

## Rejects

- **An offline kernel discipline-gate for the live check.** It would read the tree, not a running
  registry, so it physically cannot see runtime-composed contributions (DR-A). The register-integrity
  early-warning + the live-registry test is the correct split.
- **Folding the live check into the existing static `runtime-witness` gate.** That gate is offline
  (reads a snapshot file) and AGENT-channel specific; the live check is a different tier (a backend
  test). Two tiers, one concern — kept legible via the register's `complements` cross-reference, not
  merged.
- **A `TrafficCounter` / delivery-count primitive** (decision 4 alt). Heavier, recursive, unneeded —
  presence, not count, is what the invariant requires.
- **Wall-clock delivery deadlines** (decision 2 alt). Calibrated for uncoordinated consumers JustSearch
  does not have.
- **Synthetic consumer hooks to make orphans "pass."** A consumer-less delivered op/resource is a defect
  to fix or explicitly exempt, never to paper over.

## What this changes in the substrate

- New: `LiveWitness` (app-services) + `LiveWitnessTest` (the teeth); `governance/live-witness.v1.json`
  (the authority register, with a `complements: runtime-witness` cross-reference + the four decisions);
  `scripts/ci/check-live-witness.mjs` (register-integrity early-warning). `RegistrySnapshotExporter`
  gains a public `operationConsumerIds(Operation)` (extracted from `buildOperationEntries`, reused by
  the live witness — behavior-preserving).
- The static `consumer-presence` and `runtime-witness` gates and the `contribution-surface` gate are
  unchanged.

## Future Agents Must Not

- **Re-implement the live consumer-presence check per-surface.** Derive from `LiveWitness`; it is the
  single live-registry authority (the register guards this).
- **Confuse `live-witness` with the static `runtime-witness` gate.** The latter is the static
  AGENT-channel delivery-consistency check; `live-witness` is the live-registry consumer-presence tier.
- **Promote the live witness to an offline kernel gate.** It must run at the live-registry tier (DR-A).
- **Add a `Promised`-hook witness here.** Promised graduation is a separate primitive (decision 3).
- **Silence an orphaned delivery by adding a synthetic consumer hook.** Fix the missing consumer, or add
  an explicit, reasoned exemption — never a no-op hook.

## Revisit When

- `ConsumerHook.Promised` is reintroduced (decision 3 reopens — SliceCatalog integrity).
- A real plugin/MCP ecosystem ships uncoordinated consumers (decision 2's deadline model reopens).
- The §4b uniform-all-kinds witness (unioning the core substrate catalogs into one live witness surface)
  is scoped — model it as a tempdoc 575 projection, not a fork.

## Amendment 2026-09-05: the register and the offline check are retired; the test is the sole authority

Tempdoc 930 deleted `governance/live-witness.v1.json` and `scripts/ci/check-live-witness.mjs`. The
script ran in no workflow, and it asserted only *wiring*: that the register's three paths existed and
each contained a named symbol. It never evaluated consumer presence, which is the invariant — the
Decision's "registered so it cannot be silently deleted" reasoning bought a copy of the file layout,
not a guarantee about behaviour.

What replaces them is the tier this ADR already named as the teeth. `LiveWitnessTest`
(`modules/app-services/src/test/java/io/justsearch/app/services/registry/snapshot/LiveWitnessTest.java`)
is now the sole authority, and the `adr-0042-live-witness-checked` probe is a `kind: test` probe
pointing at it. The one property the register held that the test's existence did not imply — the
**no-fork** reuse of `RegistrySnapshotExporter.operationConsumerIds`, previously checked by scraping
the source for the symbol — is now asserted behaviourally by
`LiveWitnessTest#witnessReusesTheBuildTierConsumerMergeForOperations`: over a fixture exercising both
merge inputs (inline `ConsumerHook`s and executor-derived consumers), the witness must classify every
delivered operation exactly as the build-tier merge does. A forked merge fails it.

Unchanged: the Decision itself, the four §E.2.1 resolutions, and every "Future Agents Must Not" item —
except that "the register guards this" now reads "the enforcing test guards this".

## Amendment 2026-10-20: workflow operations are in the static snapshot

The Context's workflow example describes the former build-tier blind spot.
`RegistrySnapshotExporter.composedOperations` now installs the base catalogs
and projects `CoreWorkflowCatalog` through `WorkflowOperationProjection`, using
`OperationCatalogComposition.installWorkflowOps`. Both snapshot operation
entries and the static agent witness consume that same composition. Core
workflow operations are therefore no longer missing from the static snapshot.
The live tier still covers configured MCP-host tools and plugin contributions
that the static composition does not include.
Evidence: `modules/app-services/src/main/java/io/justsearch/app/services/registry/snapshot/RegistrySnapshotExporter.java`
(`composedOperations`, `buildOperationEntries`, `agentWitnessDeliveredIds`).
