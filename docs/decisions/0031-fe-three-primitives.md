---
title: "ADR-0031: Frontend three primitives — Operation, Resource, Prompt"
type: decision
status: accepted
description: "The frontend framework is built on backend-declared registry primitives — originally three, Operation (do), Resource (observe), Prompt (ask), mirroring the MCP split, and now four with DiagnosticChannel. All UI affordances project from these."
date: 2026-06-09
probes: none - the three-primitive split is the shape of the registry itself; the contribution-surface and operation-surface gates already fail a fourth primitive.
last_reviewed: 2026-09-02
---


# ADR-0031: Frontend three primitives — Operation, Resource, Prompt

> **Graduated to canonical docs on 2026-06-09** from the retired `421` frontend-rewrite kernel
> draft (authored ~2026-05; the rewrite shipped per tempdoc 563). Internal cross-references to that
> draft's now-removed planning material (`slices/`, `20-systems/`, `30-agent-workflows/`,
> `60-migration-history/`, `archive/`) are historical and were not rewritten — the decision stands on
> its own. Sibling-ADR links point to `docs/decisions/`; kernel links to
> `docs/reference/ui/frontend-kernel/kernel/`.

## Status

Accepted. Describes live shipped architecture (the Operation/Resource/Prompt registry the agent + chrome consume). See also `docs/explanation/22-agent-system-architecture.md`.

## Decision

The framework originally converged on three backend-declared primitives
and now has four:

- `Operation`
- `Resource`
- `Prompt`
- `DiagnosticChannel`

New framework shapes should be entry kinds, manifest tiers, or
product/domain contracts unless they pass shape governance.

## Rationale

The source packet converged away from a larger set of surface-specific shapes.
Three primitives reduce frontend maintenance and make capability projection
consistent across humans, plugins, and agents.

### What was tried

- **Surface-specific shapes** (`FormSurface`, `TableSurface`, `HealthEventCatalog`,
  per-area registries). Each surface declared its own metadata, validators,
  i18n contract, and renderer expectations.
- **Separate registries for the same capability** (`admin-action-registry`
  used by buttons / panels; `agent-tool-registry` used by the agent loop).
  Both pointed at the same backend behavior but with different declarations.
- **Per-subsystem primitives** (one shape per worker subsystem, one per
  inference area, one per ingestion pipeline).

### What failed

- **Sprawl**: shape count tracked backend feature count linearly. Every new
  feature added a new shape to declare, validate, schema-generate, render,
  and i18n.
- **Agent-vs-human path divergence**: the same backend action had two
  declarations and two execution paths. Risk policy / confirmation /
  observability drifted between them.
- **Renderer drift**: each shape grew its own renderer family. Generic
  reuse degraded as shape count rose; bespoke surfaces multiplied.
- **Frontend-private state machines**: surfaces re-derived backend
  readiness because there was no shared "observable truth" primitive.
  Per source 428's pre-deletion audit, this was the single largest
  category of FE-side product knowledge.

### Why these three

The space of frontend-projectable backend behavior cleanly partitions into
three classes, each mapping to an existing canonical spec:

- **Executable** (acts on the system) → `Operation`. Maps to k8s API
  conventions for action shapes; handshake/confirmation/risk per ADR-0027
  typed catalogs + LSP diagnostic patterns.
- **Observable** (truth surface that changes) → `Resource`. Maps to k8s
  `metav1.Condition` + CloudEvents 1.0 + OTel Resource semconv + etcd v3
  watch protocol.
- **Language-mediated** (produces text/structured output via a model) →
  `Prompt`. Distinct from operations because outputs need provenance,
  context windows, and model-readiness constraints; modelling them as
  operations would force operation declarations to absorb language-specific
  concerns.

Three primitives are enumerated enough that readers can understand the
system without reverse-engineering product surfaces, and generative enough
that new backend capability lands as an entry kind (not a new primitive)
unless it genuinely doesn't fit. `../reference/ui/frontend-kernel/kernel/04-shape-governance.md`
operationalizes the "entry kind before primitive" rule.

### Shape axes within a primitive

Each primitive may carry one or more typed **shape axes** orthogonal
to its identity. A shape axis is a closed-vocabulary discriminator
that constrains other primitive fields and drives per-shape
recipes/validators/renderers. Shape axes are typed values inside an
existing primitive — not separate primitives. The "entry kind before
primitive" rule extends naturally: prefer adding a value to an
existing shape axis, prefer adding a new shape axis to an existing
primitive, before proposing a new primitive.

- **Resource** carries a `Category` axis with five values
  (`STATE`, `EVENT_STREAM`, `HISTORY`, `TABULAR`, `TIMESERIES`). Per
  `0036-fe-resource-category.md`. The Category × `SubscriptionMode`
  constraint matrix lives in `20-systems/01-resources.md`.
- **DiagnosticChannel** carries diagnostic-flow shape and permission
  axes outside Resource. It was introduced by slice 448 when LOG_TAIL
  was retired from `Resource.Category`.
- **Prompt** has a latent shape distinction (MCP-template vs
  language-workflow) that should land as a typed axis when the Prompt
  enrichment slice (currently tempdoc 437) ships. Sibling work to the
  Resource Category substrate.
- **Operation** has latent shape distinctions (sync, async, long-
  running with progress). The existing `policy.concurrency` and
  `policy.cancellation` axes do partial work. Whether to formalize
  Operation's shape axis is deferred until a concrete workload pressure
  surfaces; see `CONFLICT-LEDGER.md` C-010.

Shape-axis values follow the same governance protocol as primitive
proposals (per `../reference/ui/frontend-kernel/kernel/04-shape-governance.md` §"Vocabulary
Governance"). Adding a new value, deprecating an existing one, and
the review authority are all unified.

## Rejects

- One primitive per backend subsystem.
- Separate admin-action and agent-tool models for the same executable
  capability.
- Treating product domain objects as framework primitives by default.

## Future Agents Must Not

- Add a fifth primitive without passing `../reference/ui/frontend-kernel/kernel/04-shape-governance.md`.
- Reintroduce old pre-collapse shapes as normative language.
- Create agent-only execution paths when an `Operation` can model the action.

## Revisit When

- Multiple unrelated surfaces need a shape that cannot be represented as an
  entry kind under `Operation`, `Resource`, or `Prompt`.
- Generic consumption is cheaper and clearer than bespoke product contracts.
- The conflict is recorded in `CONFLICT-LEDGER.md`.

## Affected Docs

- `../reference/ui/frontend-kernel/kernel/00-primitives.md`
- `../reference/ui/frontend-kernel/kernel/04-shape-governance.md`
- `20-systems/00-operations.md`
- `20-systems/01-resources.md`
- `20-systems/02-prompts-agents.md`

## Source Evidence

- `archive/source-tempdocs/429-slice-1-2-admin-action-registry.md`
- `archive/source-tempdocs/435-operation-substrate-doc-alignment.md`
- `archive/source-tempdocs/421-data-plane.md`

## Clarification (slice 449, refined by slice 481): primitive count vs Composition modifier

**Refined framing per slice 481 §C.1.F + §3.7 (post-Pass-8, 2026-05-08)**:
the slice 449 "Manifest tier" framing has been reframed as a
**Composition modifier orthogonal to the primary Category axis**, not a
parallel governance tier. Surface and Plugin sit *as* primitive Category
instances with `composition.kind = COMPOSED`, not in a separate tier
above the primitives. The original tier framing obscured that the
distinction is structurally a typed modifier.

The "three primitives" framing is historical shorthand for the
Operation / Resource / Prompt convergence. Slice 448 added a fourth
primitive, DiagnosticChannel, after the truth-class audit proved
LOG_TAIL did not belong inside Resource. Slice 481 §C.1.F further
proposes that the count of *primary* Categories is honestly three
(EXECUTABLE = Operation, OBSERVABLE = Resource ∪ DiagnosticChannel,
LANGUAGE_MEDIATED = Prompt), with `Composition` and `audience` as
orthogonal modifiers — but the four-primitive framing remains the
shipped Java vocabulary.

**Counts to remember**:
  - **4 primitives** (current Java vocabulary): Operation, Resource, Prompt, DiagnosticChannel.
  - **3 primary Categories** (slice 481 theoretical refinement, not yet shipped): EXECUTABLE, OBSERVABLE, LANGUAGE_MEDIATED.
  - **Composition modifier** (slice 481 reframe of "Manifest tier"): COMPOSED entries (Surface, Plugin) are catalog-shaped like primitives but discriminated by `composition.kind` per slice 481 §3.7's sealed `Composition`.

The framing "three primitives + one fourth + Composition modifier"
preserves the Pass-4 / Pass-5 verification — composed entries were
Pass-checked not to be fifth primitives (concern-bundling /
primitive-proliferation risks documented in slice 449 §2/§3 and
re-affirmed in slice 481 §A.1 / §A.2).

The reframe does not invalidate slice 449's catalog endpoint pattern
(`/api/registry/{primitive-name}s` for primitives;
`/api/registry/surfaces` for composed entries) — the catalog shape is
the same; the *vocabulary* describing what those entries are has shifted
from "Manifest tier" to "Composition modifier."
