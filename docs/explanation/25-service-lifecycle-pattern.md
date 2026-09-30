---
title: Service Lifecycle Pattern
type: explanation
status: stable
description: "Canonical pattern for long-lived resource owners in JustSearch. Identity is held by the consumer; each open period is a single-shot phase value with a clean AutoCloseable lifecycle. Restart is a consumer pattern (build new value, swap holder field, close old) — not a method on the value. Two forms: phase-typed (Form A) when multiple operating modes warrant compile-time distinction, single-shot single-class (Form B) when there's only one mode."
---

# Service Lifecycle Pattern

JustSearch has roughly ten long-lived resource owners — Lucene runtime,
ORT GPU sessions, embedding service, the inference manager, agent
sessions, the worker server itself, the SQLite job queue, and a few
others. Their lifecycle shapes used to be inconsistent: most were
single-shot (terminal `close()`), one was explicitly cyclable
(`SqliteJobQueue.open()`), one used session re-acquisition
(`SessionHandle.acquire()` lazily recreates after `releaseGpu()`), one
had an explicit reset workaround (`IndexingLoop.resetForProfiling`).

Each owner reinventing the wheel produced the same recurring problems:
same-instance restart hazards (state preservation, latch exhaustion,
downstream reference invalidation); hot-reload features blocked because
owners couldn't be cycled in place; audit-style refactors producing the
same wrong-conclusion class because owners conflated *service identity*
with *open-period value*.

This document specifies the canonical pattern that resolves these. It
has shipped on `main` for `LuceneRuntime` (tempdoc 406), with
observability-substrate adoption shipped for `NativeSessionHandle`
(tempdoc 414), `EmbeddingService` (tempdoc 413), `InferenceLifecycleManager`
(tempdoc 412), and `AgentSession` (tempdoc 415). The full holder-rewrite
adoption for those four owners is staged work; their telemetry and
admin substrate already follows this pattern.

## The pattern (five elements)

The identity of the service is held by the *consumer* (the class that
long-lives the resource). Each open period is a *single-shot phase
value* with a clean `AutoCloseable` lifecycle. Restart is a consumer
pattern (build new value, swap holder field, close old) — never a
method on the value.

1. **Schema / config value.** Immutable record. No lifecycle, no
   resources. Holds identity-defining configuration (paths, validators,
   schema, model pointers). Sharable across runtimes.
2. **Builder.** Captures intent (config + optional sinks) before
   opening. Returns a typed phase value per opening method. Reusable;
   multiple opens produce independent phase values (Blue/Green pattern).
3. **Phase value(s).** Each open period is a distinct concrete `final`
   class. Phase values are `AutoCloseable`; `close()` is terminal *for
   that value*. **State is type** in Form A; in Form B there is one
   phase. Phase-specific operations exist only on the relevant phase
   type.
4. **Session bundle.** Package-private record/value internal to each
   phase. *All* per-phase resources constructed in one place (the
   session ctor) and released in one place (the session `close()`).
   This is the structural property that prevents the
   audit-conflation bug class — anyone tracing "what does this phase
   own?" reads exactly two lines.
5. **Consumer-as-holder.** The long-lived consumer (e.g.
   `KnowledgeServer`) holds a `volatile` field of the phase type.
   Restart is the consumer pattern: build a new phase value via the
   same builder, atomically swap the field, close the old value. The
   consumer *is* the service identity. No dedicated `Service` wrapper
   class — that would be redundant.

## Two forms — pick by phase distinctions

Two forms are acceptable. Pick per-instance:

### Form A — Phase-typed (preferred when phase distinctions are meaningful)

Use when the resource has ≥ 2 meaningful operating modes that callers
should distinguish at the type level (e.g. read-only vs read-write —
a caller that holds a read-only handle shouldn't even *be able* to
call write methods).

Each phase becomes a distinct concrete `final` class implementing a
sealed interface. Phase-specific operations exist only on the relevant
phase type.

```java
// 1. Schema/config value — immutable, sharable, no lifecycle
public record FooSchema(/* ... */) {
  public FooBuilder atPath(Path p) { return new FooBuilder(this, p); }
}

// 2. Builder — intent capture, reusable, returns typed phase
public final class FooBuilder {
  public RunningFoo open()          throws IOException { /* read+write */ }
  public ReadOnlyFoo openReadOnly() throws IOException { /* read only */ }
  public DeferredFoo openDeferred() throws IOException { /* deferred upgrade */ }
}

// 3a. Sealed interface — common operations across phases
public sealed interface Foo extends AutoCloseable
    permits RunningFoo, ReadOnlyFoo, DeferredFoo {
  FooSchema schema();
  ReadOps readOps();
  @Override void close();
}

// 3b. Phase-typed concrete classes
public final class RunningFoo implements Foo {
  private final FooSession session;     // wraps per-phase resources
  public WriteOps writeOps() { return session.writeOps; }   // phase-only
  @Override public ReadOps readOps() { return session.readOps; }
  @Override public void close() { session.close(); }
}

public final class ReadOnlyFoo implements Foo {
  // Same as RunningFoo but no writeOps() — compile-time prevention
}

public final class DeferredFoo implements Foo {
  private final AtomicBoolean consumed = new AtomicBoolean(false);
  /** One-shot transition: consumes this; returns a new RunningFoo. */
  public RunningFoo upgrade() throws IOException {
    if (!consumed.compareAndSet(false, true)) throw new IllegalStateException("already consumed");
    // Build new session with read-write resources; return new value.
  }
}

// 4. Session bundle — package-private, ALL per-phase fields here
final class FooSession implements AutoCloseable {
  final ReadOps readOps;
  final WriteOps writeOps;     // null in read-only phase

  FooSession(FooSchema schema, FooMode mode, /* ... */) throws IOException {
    // Single construction site. Adding a new field requires editing here.
  }

  @Override public void close() {
    // Single release site. Strict order documented and enforced.
  }
}

// 5. Consumer-as-holder pattern
public final class FooConsumer {
  private volatile RunningFoo runtime;

  public void start(FooBuilder builder)   { this.runtime = builder.open(); }
  public void restart(FooBuilder builder) {
    RunningFoo old = this.runtime;
    this.runtime = builder.open();        // atomic swap
    if (old != null) old.close();
  }

  // Downstream services receive Suppliers, not direct refs:
  public Supplier<RunningFoo> runtimeSupplier() { return () -> this.runtime; }
}
```

### Form B — Single-class single-shot

Use when the resource has only one operating mode, or when the modes
don't correspond to caller-visible operation surfaces (e.g. internal
mode flags that don't change the public API).

Single `final` class implementing `AutoCloseable`, single-shot, restart
via holder-swap pattern in the consumer. Same construction/release
symmetry, same schema/builder split, same consumer-as-holder restart.
No `restart()` method on the value.

```java
public record BarSchema(/* ... */) {
  public BarBuilder configure() { return new BarBuilder(this); }
}

public final class BarBuilder {
  public Bar open() throws IOException { /* construct + start */ }
}

public final class Bar implements AutoCloseable {
  private final BarSession session;
  // ... operations ...
  @Override public void close() { session.close(); }
}

// Consumer-as-holder identical in shape to Form A.
```

### Choice criterion

**Choose Form A when** the resource has ≥ 2 meaningful operating modes
that callers should distinguish at the type level.

**Choose Form B when** there's only one mode, or when modes are
internal flags that don't change the public API.

## Why consumer-as-holder, not a `restart()` method on the value

This is the **Elasticsearch `AtomicReference<Engine>` model**, not the
Solr `SolrCore` model. An earlier draft of this pattern proposed a
Solr-shaped "single cyclable class with `restart()`" pattern; tempdoc
406's design discussion converged on the ES pattern after concluding
that:

- Phase typing gives compile-time correctness on phase-valid operations
  (e.g. `indexingCoordinator()` doesn't exist on `ReadOnlyRuntime`),
  whereas single-cyclable-class only gives runtime guards.
- A `restart()` method on a value violates the `AutoCloseable` contract
  and re-introduces "lifecycle in one class" at finer grain.
- Consumer-as-holder honestly separates *identity* from *open period*.

JustSearch is single-node desktop; either model is operationally
defensible. The choice between them is a *correctness asymptote*
question (phase typing wins) rather than an *operational fit* question
(both fit).

## Shipped instances (current state)

| Owner | Form | Adoption status (`main`) | Reference tempdoc |
|---|---|---|---|
| `LuceneRuntime` | A | **Holder + phase types + telemetry — fully shipped** | 406 |
| `NativeSessionHandle` | A or B (deferred decision) | Telemetry substrate shipped; phase-typed holder rewrite deferred | 414 |
| `EmbeddingService` | B | Telemetry substrate shipped (invoke/cache/unload); holder rewrite deferred | 413 |
| `InferenceLifecycleManager` | A | Telemetry + admin endpoint + sealed `InferenceFailure` shipped; holder rewrite deferred (`Mode`/`ModeStateMachine` retained internally) | 412 |
| `AgentSession` | B | Typed termination + lifecycle metrics shipped; full structural decomposition deferred | 415 |
| `KnowledgeServer` | B | Pattern not yet adopted; cutover = process restart | — |
| `SqliteJobQueue` | B | Already cyclable (re-callable `open()`); pattern adoption pending | — |
| `IndexingLoop` | B | Pattern not yet adopted; `resetForProfiling` workaround exists | — |

Speculative per-owner target designs are intentionally outside this
canonical document. This doc captures the shared pattern those owners
would adopt if that future work is resumed.

## Related design heuristics

Two general design heuristics emerged from this pattern's adoption.
They are not part of the pattern itself, but they were learned during
its rollout and inform future structural decisions:

### Prefer substrate over per-feature fixes

When a "fix" problem looks like one instance of an N-instance pattern,
build the substrate first. Substrate work is slower upfront and faster
downstream. Per-feature fixes are faster to first-ship but compound
poorly.

Concrete instances on `main`:
- ADR-0028 (scoped reverse path-hash lookup) substrate-fixed an
  HTTP→worker-call cancel gap (then gRPC) that had four candidate
  per-endpoint fixes.
- ADR-0027 (MetricCatalog) substrate-fixed inconsistent telemetry
  patterns across ten modules that each had a per-module `XxxTelemetry`
  thin façade.
- Tempdoc 410's typed `IngestionOutcome` substrate-fixed silent
  placeholder behaviour across N extraction failure modes that each
  had bespoke handling.

The criterion: if you can name two more instances of the same shape
nearby, the substrate is justified.

### Prefer refined contracts over absolute prohibitions

Privacy/security/safety contracts often start absolute ("X never
crosses boundary Y"), which prevents useful product behaviour. The
right contract distinguishes *when/who* can violate the absolute, with
explicit exemptions and structural enforcement.

Concrete instances on `main`:
- ADR-0028 refined the absolute "raw paths never cross worker→head"
  prohibition into "raw paths cross only via a specific resolver RPC,
  only when authorized by current scope, only invoked from explicit
  user actions, never from export endpoints."
- Tempdoc 410's `IngestionEventView` export contract was structurally
  pinned (`ingestionEventViewExportContractIsPinned`) so future field
  additions force explicit consent rather than leaking by default.

The criterion: if the absolute prohibition prevents a real product
question from being answerable, refine the contract instead of accepting
the prohibition or quietly violating it.

## Adopting the pattern in a new owner

When applying this pattern to a previously-unstructured owner:

1. Extract the immutable identity-defining config into a record.
2. Extract intent capture into a builder; the builder's open methods
   return phase values.
3. Move all per-phase resource construction into one session ctor and
   all release into one session close.
4. Replace consumer fields like `volatile boolean running` /
   `AtomicReference<XxxState>` with a `volatile` field of the phase
   type.
5. Replace `restart()` / `reset()` / `start()` workarounds with the
   holder-swap pattern: build new, atomic swap, close old.
6. Downstream services receive `Supplier<PhaseType>`, not direct refs,
   so they automatically see post-swap state without re-binding.

For observability adoption alongside the pattern, see [ADR-0027
(MetricCatalog)](../decisions/0027-metric-catalog-as-telemetry-contract.md)
and [ADR-0029 (TelemetryEvents bridge vs direct-emit)](../decisions/0029-telemetry-events-bridge-vs-direct-emit.md).

## See also

- [ADR-0027 — MetricCatalog as the Telemetry Contract](../decisions/0027-metric-catalog-as-telemetry-contract.md)
- [ADR-0028 — Scoped Reverse Path-Hash Lookup](../decisions/0028-scoped-reverse-path-lookup.md)
- [ADR-0029 — TelemetryEvents Bridge vs Direct-Emit](../decisions/0029-telemetry-events-bridge-vs-direct-emit.md)
- Speculative per-owner lifecycle notes are noncanonical and not part of
  the current public reference corpus.
