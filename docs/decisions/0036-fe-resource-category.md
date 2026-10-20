---
title: "ADR-0036: Resource Category axis"
type: decision
status: accepted
description: "Resource is one primitive with a typed Category axis (STATE, EVENT_STREAM, HISTORY, TABULAR, TIMESERIES) rather than a primitive per kind, so new information shapes are typed values, not new primitives."
date: 2026-06-09
probes:
  - adr-0036-observed-happening-gate
last_reviewed: 2026-09-02
---


# ADR-0036: Resource Category axis

> **Graduated to canonical docs on 2026-06-09** from the retired `421` frontend-rewrite kernel
> draft (authored ~2026-05; the rewrite shipped per tempdoc 563). Internal cross-references to that
> draft's now-removed planning material (`slices/`, `20-systems/`, `30-agent-workflows/`,
> `60-migration-history/`, `archive/`) are historical and were not rewritten — the decision stands on
> its own. Sibling-ADR links point to `docs/decisions/`; kernel links to
> `docs/reference/ui/frontend-kernel/kernel/`.

## Status

Accepted. The shape-move this records (typed Categories within a primitive) is reused by the contract substrate (ADR-0039) and shape governance (`docs/reference/ui/frontend-kernel/kernel/04-shape-governance.md`).

## Decision

A `Resource` entry declares its information shape through a typed
`Category` axis. The current vocabulary is closed at five values:

- `STATE` - current value of one thing
- `EVENT_STREAM` - live typed events with bounded recent-window retention
- `HISTORY` - durable past events; query-shaped reads
- `TABULAR` - current state of a collection of items
- `TIMESERIES` - sliding window of regular numeric samples

`Category` is orthogonal to `SubscriptionMode` (transport), but the
two axes are constrained by a matrix in `20-systems/01-resources.md`.

`Resource` remains one primitive. Categories are typed shapes inside
the primitive, not separate primitives.

## Amendment: LOG_TAIL Retired

The original Resource Category decision included `LOG_TAIL` as a sixth
value. Slice 448 retired that value and introduced `DiagnosticChannel`
as the fourth backend-declared primitive.

Reason: operator traces are not Resource truth. A log tail has a
different consumer model, schema discipline, privacy class, and
self-observation risk from Resources such as HealthEvent, operation
history, job queue, or timeseries metrics. Treating log tails as a
Resource Category created truth-class conflation (C-012).

Historical slice docs may still mention `LOG_TAIL`; those mentions are
evidence of the design path, not current vocabulary.

## Rationale

The three-primitive collapse eliminated surface-specific sprawl
(`FormSurface`, `TableSurface`, `HealthEventCatalog`) in favor of one
Resource primitive discriminated by typed shape. That collapse was
structurally correct but left a residual gap: `SubscriptionMode`
describes how the wire works, not what the data means.

Two entries with `SubscriptionMode = SSE_STREAM` can have different
consumer expectations, producer mechanics, retention semantics, and
renderer families. Category makes that distinction explicit and
validated.

## Category Classes

- **STATE**: settings, capabilities, runtime mode, current model
  availability. Snapshot semantics, replace-on-change updates, no
  retained history.
- **EVENT_STREAM**: HealthEvent, agent session events, scan progress
  per instance. Live event feed with bounded recent window.
- **HISTORY**: ingestion ledger, operation history, search history.
  Durable past; queryable; reads are typically paginated.
- **TABULAR**: job queue, library sources, active runtimes. Snapshot
  of a collection plus per-item delta updates.
- **TIMESERIES**: job-queue depth trend, indexing throughput, GPU
  utilization, search latency p50/p95. Sliding window of regular
  numeric samples; receiver gets the current N samples per frame.

A sixth Resource Category has not surfaced across the workloads. New
values require shape-governance review per
`../reference/ui/frontend-kernel/kernel/04-shape-governance.md`.

## TIMESERIES vs EVENT_STREAM

Slice 3a.1.4 considered modeling `TIMESERIES` as an `EVENT_STREAM`
variant (`samplePolicy: { intervalMs, windowMs }`). That was rejected:
receiver semantics differ, wire economy differs at sub-second cadence,
and renderers need window-aware normalization rather than per-event
accumulation.

## Rejects

- A per-category primitive (`HistoryResource`, `TimeseriesResource`,
  etc.) for a shape covered by `Resource.Category`.
- Reintroducing `LOG_TAIL` as a Resource Category.
- An untyped `shape: string` field that permits ad hoc values.
- Encoding shape implicitly through `SubscriptionMode`.
- Naming slice tempdocs by source rather than by shape and workload.

## Future Agents Must Not

- Introduce a Category value without shape governance.
- Ship a Resource entry without `category` declared.
- Declare a `category × subscriptionMode` combination outside the
  validator matrix.
- Treat Category as overlapping with SubscriptionMode.
- Use the retired `01e-add-log-tail-resource.md` recipe for new work.
  Diagnostic log-tail work belongs to `DiagnosticChannel`.

## Revisit When

- A shape class genuinely cannot be modelled as a Resource entry with
  Category.
- Operation or Prompt acquire their own shape-axis vocabularies and
  consistency arguments suggest unification across primitives.
- A future diagnostic flow seems Resource-shaped; it must first pass
  the C-012 truth-class check from slice 448.

## Affected Docs

- `../reference/ui/frontend-kernel/kernel/00-primitives.md`
- `../reference/ui/frontend-kernel/kernel/04-shape-governance.md`
- `20-systems/01-resources.md`
- `20-systems/06-error-recovery-diagnostics.md`
- `30-agent-workflows/01-add-resource.md`
- `30-agent-workflows/01e-add-log-tail-resource.md` (retired redirect)
- `40-reference-workloads/*.md`
- `slices/444-resource-category-substrate.md`
- `slices/3a-1-4-timeseries-resource-category.md`
- `slices/448-diagnostic-channel-substrate.md`
- `GLOSSARY.md`
- `TRACEABILITY.md`
- `CONFLICT-LEDGER.md`

## Source Evidence

- `slices/444-resource-category-substrate.md`
- `slices/3a-1-4-timeseries-resource-category.md`
- `slices/448-diagnostic-channel-substrate.md`
- `60-migration-history/05-workload-validation-pass-1.md`
- `60-migration-history/06-resource-category-rewrite.md`
- `60-migration-history/10-truth-class-audit.md`
- `archive/source-tempdocs/421-data-plane.md`
- `archive/source-tempdocs/430-slice-1-1-a-health-event-catalog.md`
