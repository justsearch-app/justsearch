---
title: "ADR-0029: TelemetryEvents Bridge vs Direct-Emit Façade for MetricCatalog Adoption"
type: decision
status: accepted
description: "Each MetricCatalog adoption picks between two consumption idioms — a TelemetryEvents bridge interface (decouples domain code from telemetry types; required when the domain module should not depend on the catalog package) or a direct-emit façade that wraps catalog instruments (cheaper when the domain module already depends on the catalog package). The choice is module-dep-driven, not preference-driven. Five shipped instances codify the criteria."
date: 2026-04-27
probes:
  - adr-0029-bridge-idiom-present
  - adr-0029-direct-emit-idiom-present
last_reviewed: 2026-10-20
---

# ADR-0029: TelemetryEvents Bridge vs Direct-Emit Façade for MetricCatalog Adoption

## Status

**Accepted (2026-04-27).** Codifies the per-instance judgement that
emerged across five MetricCatalog adoptions: tempdoc 406 (LuceneRuntime),
tempdoc 412 (InferenceLifecycleManager — designed), tempdoc 413
(EmbeddingService), tempdoc 414 (NativeSessionHandle), tempdoc 415
(AgentSession). The criteria below were applied implicitly during each
shipment; this ADR makes them explicit so the next adopter doesn't
re-derive.

Current implementation corrections are recorded in the 2026-10-20 amendment below.

## Context

ADR-0027 establishes that every metric flows through a typed
`MetricCatalog`. It does not specify *how* the domain code that produces
the events should reach the catalog. Two idioms exist in the shipped
codebase:

**Bridge (events-interface intermediary):** Domain code calls
`events.onCommit(...)` against a small interface defined in the
domain's own module. A separate adapter class — placed in a module that
depends on `modules/telemetry`'s catalog package — implements the
interface and translates each event call into typed catalog instrument
calls. Domain types never import telemetry types.

> Examples on `main`: `LuceneRuntimeTypes.TelemetryEvents` →
> `WorkerLuceneTelemetryAdapter`; `OrtSessionTelemetryEvents` →
> `OrtSessionTelemetryAdapter`; `EmbeddingTelemetryEvents` →
> `EmbeddingTelemetry` façade.

**Direct-emit façade:** Domain code calls
`telemetry.recordSessionStart(...)` against a thin façade that holds
the catalog instruments directly. The façade lives in the same module
as the domain code, or in a sibling module the domain already depends
on.

> Examples on `main`: `AgentTelemetry` over `AgentMetricCatalog` (called
> directly from `AgentLoopService`); `IpcTelemetry` over its catalog.

Both idioms are correct. They are not interchangeable: each has a
different module-dependency profile, and choosing the wrong one for the
context creates either a circular dependency or unnecessary layer
inflation.

## Decision

**Pick the idiom by module-dependency structure, not preference.**

### Use a bridge (events-interface intermediary) when *any* of these holds

1. **The domain module does not depend on `modules/telemetry`'s catalog
   package today, and adding that dependency is undesirable.** Examples:
   - `modules/adapters-lucene` (would couple the Lucene runtime to the
     telemetry surface; tempdoc 406 chose the bridge)
   - `modules/ort-common` (deliberately lean — only depends on
     `opentelemetry.api`; tempdoc 414 chose the bridge)
   - `modules/app-inference` (no telemetry dep today; tempdoc 412 chose
     the bridge for the same reason)
   - `modules/worker-core` (would create a worker-core ↔ worker-services
     cycle if it imported the catalog adapter directly; tempdoc 413
     chose the bridge)
2. **Multiple emit-sites are scattered across files in the domain
   module.** A bridge interface keeps every site uniform; without it,
   each site duplicates the catalog access pattern.
3. **Domain types must remain testable with a no-op telemetry stub.**
   The bridge interface ships a `NoopXxxTelemetryEvents.INSTANCE` static
   field; tests construct the domain object with the no-op and skip
   telemetry assertions entirely.

### Use a direct-emit façade when *all* of these hold

1. **The domain module already depends on the catalog package**
   (typically because the catalog also lives in that module).
2. **Emit-sites are few and concentrated** — a thin façade with
   ~3–7 named methods is more readable than an events interface plus
   adapter.
3. **The domain code does not need a swappable no-op for testing**, or
   the façade itself is trivially substitutable (e.g.,
   `AgentTelemetry.noop()` factory).

### Resolving ambiguity

If the criteria split (e.g., the domain module does depend on the
catalog package, but emit-sites are scattered across many files),
**choose the bridge.** The cost of an extra interface is small; the
cost of refactoring out direct-emit calls when a future module-dep
constraint emerges is large. Asymmetric reversibility favours the
bridge.

## Concrete shape (bridge variant)

```text
modules/<domain>/.../telemetry/
  XxxTelemetryEvents.java          # interface — no telemetry dep
  NoopXxxTelemetryEvents.java      # static INSTANCE singleton

modules/<consumer-of-domain>/.../<scope>/
  XxxMetricCatalog.java            # public final class implements MetricCatalog
  XxxTags.java                     # typed tag schemas (one per metric tag set)
  XxxTelemetryAdapter.java         # implements XxxTelemetryEvents,
                                   # holds catalog instruments,
                                   # wired at construction
```text

The domain class accepts an `XxxTelemetryEvents events` field, defaulted
to `NoopXxxTelemetryEvents.INSTANCE`. Its construction site (typically
in a `*Bootstrap` or `*Factory`) constructs the catalog + adapter and
passes the adapter as the `events` parameter.

## Concrete shape (direct-emit variant)

```text
modules/<domain>/.../
  XxxMetricCatalog.java            # public final class implements MetricCatalog
  XxxTags.java                     # typed tag schemas
  XxxTelemetry.java                # thin façade — holds catalog instruments,
                                   # exposes 3–7 named recordXxx() methods
```

The domain class accepts an `XxxTelemetry telemetry` field. Tests
either pass a real one with a `NoopMetricRegistry`, or pass an
`XxxTelemetry.noop()` factory variant.

## Consequences

**Positive:**
- The next MetricCatalog adopter has a deterministic answer to "which
  idiom?" instead of inventing a third pattern.
- Module-dependency constraints become a *driver* of the choice rather
  than a *blocker* discovered mid-implementation.
- Both idioms compose with `MetricCatalog` substrate — adopter never
  needs to fight the substrate.
- Test ergonomics are predictable: bridge variants get a static no-op;
  direct-emit variants get a `noop()` factory.

**Negative:**
- Two idioms is more cognitive load than one. Mitigated by the
  module-dep criterion being mechanically checkable (`grep` the gradle
  file).
- The choice is locked in at adopter time. Switching later (e.g., if
  `app-inference` later depends on the catalog package for unrelated
  reasons) means refactoring; tolerated because the bridge form
  composes forward — a direct-emit can wrap a bridge but the converse
  is awkward.

## Alternatives considered

**Force a single idiom across the codebase.** Picked one or the other,
mandate it everywhere. Rejected: the module-dep constraint is real;
forcing direct-emit on `ort-common` would have either added a telemetry
dep (architectural regression) or pushed the catalog into a wrapper
module just to satisfy the rule. Forcing bridge everywhere
unnecessarily inflates `app-agent`'s consumer surface where the
existing `AgentTelemetry` is already four lines.

**Wrap every catalog in a bridge as a default and let direct-emit be
the exception.** Cleaner conceptually but adds a per-catalog
boilerplate cost (interface + noop + adapter) when most adopters that
satisfy the direct-emit criteria don't need it.

**Generate the bridge automatically from catalog definitions** (e.g.,
annotation-processor over `MetricDefinition`). Tempting but premature
— the criteria are stable enough now to not warrant a code generator.
Worth revisiting if catalog count exceeds ~20.

## References

- ADR-0027 — MetricCatalog as the Telemetry Contract
- Tempdoc 406 — LuceneRuntime adoption (first bridge instance, set the
  pattern)
- Tempdoc 412 — InferenceLifecycleManager adoption (second bridge
  instance, designed)
- Tempdoc 413 — EmbeddingService adoption (bridge variant, structurally
  forced by the worker-core ↔ worker-services split)
- Tempdoc 414 — NativeSessionHandle adoption (bridge instance, also
  structurally forced — `ort-common` deliberately lean)
- Tempdoc 415 — AgentSession adoption (direct-emit instance, extends
  existing `AgentMetricCatalog` + `AgentTelemetry`)

## Amendment 2026-09-02: kept as a criteria ADR, now probed

Re-examined under decision-review lane B (tempdoc 884), outcome **still true, now probed**.

Two questions were open: (a) fold this ADR into ADR-0027, since both are about `MetricCatalog`;
(b) whether a criteria ADR can carry a mechanical probe at all. Both are decided here.

### Kept separate from ADR-0027

ADR-0027 decides the *contract*: every metric flows through a typed `MetricCatalog`. This ADR
decides an *adoption idiom* selected by module-dependency structure. They have different subjects
and different reasons to change — 0027 changes when the telemetry contract changes; 0029 changes
when the module graph or the idiom population changes. Folding ~190 lines of adoption criteria
into 0027 would inflate a differently-scoped ADR and bury the criteria a future adopter is
looking for. Cross-referenced from 0027 instead.

The tempdoc-884 finding that this ADR has **zero tempdoc citations** is an argument that it is
*under-referenced*, not that it is wrong. The five instances it codifies are all still on `main`
(see the probes below). The remedy for under-reference is the cross-reference added to ADR-0027,
not deletion.

### Now probed: both idioms must still exist

A criteria ADR's load-bearing premise is that **there is still a choice to make**. If one idiom
disappears from the codebase, "pick by module-dependency structure" is answering a question that
no longer has two answers, and this ADR should be folded or retired. Two probes pin exactly that:

- `adr-0029-bridge-idiom-present` — the three bridge instances named in Context still implement a
  `*TelemetryEvents` interface:
  `modules/worker-services/src/main/java/io/justsearch/indexerworker/services/WorkerLuceneTelemetryAdapter.java:25`
  (`implements LuceneRuntimeTypes.TelemetryEvents`),
  `modules/worker-services/src/main/java/io/justsearch/indexerworker/observability/OrtSessionTelemetryAdapter.java:33`
  (`implements OrtSessionTelemetryEvents`), and
  `modules/worker-services/src/main/java/io/justsearch/indexerworker/embed/EmbeddingTelemetry.java:16`
  (`implements EmbeddingTelemetryEvents`).
- `adr-0029-direct-emit-idiom-present` — the two direct-emit instances still hold catalog
  instruments directly and implement no events interface:
  `modules/app-agent/src/main/java/io/justsearch/agent/AgentTelemetry.java:24`
  (`final class AgentTelemetry {`, over `AgentMetricCatalog` + `GenAiMetricCatalog`) and
  `modules/app-services/src/main/java/io/justsearch/app/services/worker/IpcTelemetry.java:27`
  (`public final class IpcTelemetry {`, over `IpcMetricCatalog`).

Each probe's `paths` list is exactly the named instances, so neither is a growth ratchet: a
sixth adoption anywhere in the tree does not move either count. They fail when a *named* instance
changes idiom — which is the only event that bears on whether the criteria still apply.

### Reopening trigger

**If either idiom's population goes to zero, the probe fails and ADR-0029 should be folded into
ADR-0027 or retired.** A one-idiom codebase does not need a criteria ADR telling adopters which
of two idioms to pick; at that point the surviving idiom is simply how `MetricCatalog` is adopted,
and that sentence belongs in ADR-0027.

## Amendment 2026-10-20: the inference bridge is shipped

The Status and References entries calling tempdoc 412's inference bridge
"designed" are historical. The interface is now
`modules/app-inference/src/main/java/io/justsearch/app/inference/telemetry/InferenceTelemetryEvents.java`;
`modules/app-services/src/main/java/io/justsearch/app/services/inference/InferenceTelemetryAdapter.java`
implements it over `InferenceMetricCatalog`. `BootstrapInferenceFactory.buildEvents`
constructs the adapter for `LocalTelemetry` and supplies a no-op otherwise.
`OnlineModeOps`, `LlamaServerOps` and `TransitionRunner` consume the interface.
The direct-emit example remains `IpcTelemetry` in
`modules/app-services/src/main/java/io/justsearch/app/services/worker/IpcTelemetry.java`
(the declaration is at line 26 in this revision). Both adoption idioms remain.
