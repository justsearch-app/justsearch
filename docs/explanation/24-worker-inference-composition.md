---
title: Worker Inference Composition
type: explanation
status: stable
description: "How the index half builds ORT sessions and wires the six encoder roles onto a single typed composition pipeline."
---

# Worker Inference Composition

The Worker process hosts six ORT-backed encoders — embedding, SPLADE,
NER, BGE-M3, cross-encoder reranker, and citation scorer. Each one needs
an ONNX Runtime session with carefully tuned provider / session / run
options, a typed GPU arbiter that yields VRAM to `llama-server`, a lease
pattern for concurrent access, and a CPU-failure recovery path. Before
tempdoc 397, six construction paths implemented this shape independently;
a single per-session-policy change (e.g. `disableArenaShrinkage`) could
silently apply on one path and not on another. Tempdoc 397 (landed
2026-04-21) collapsed those paths onto one composition root and one
apply site. This page describes the resulting shape.

> **Decision register:** For the settled rationale, baselines, and
> ArchUnit enforcement points, see
> [`docs/reference/inference-runtime-register.md`](../reference/inference-runtime-register.md)
> entry **D-007**.

For related subsystems:

- [05-ai-architecture.md](05-ai-architecture.md) — Embedding backend,
  reranker GPU coordination, llama-server lifecycle.
- [03-knowledge-server.md](03-knowledge-server.md) — How the Worker
  boots, including where `initDeferredModels()` fits in startup.
- [06-configuration-ssot.md](06-configuration-ssot.md) —
  `ResolvedConfig` + ordinal chain (the upstream input to the policy
  resolvers below).

---

## The closure property

The design is organised around a single invariant, lifted from tempdoc
368 RC3 / T2 (*every capability has exactly one authority*):

> The set of decisions that affect a session's observable behavior
> equals the set of fields on its resolved policy records. Nothing else.

If this holds — and the codebase now enforces that it does — then:

- Auditing current behavior is reading a record.
- Diffing two runs is diffing two `PolicySnapshot` JSONs.
- Testing equivalence between call paths is comparing records.
- Adding a new behavior means declaring a new field on the record; no
  other channel exists.

The bug class that motivated 397 — *divergent session construction
across call paths under equal inputs* — becomes
type-unrepresentable, not just structurally unlikely.

---

## The pipeline

One production path, four stages:

```text
ResolvedConfig  ─┐
HardwareProfile ─┤  (pure functions)
Environment     ─┼──► RuntimePolicyResolver       → RuntimePolicy       (once per boot)
ModelRegistry   ─┘    ModelSessionPolicyResolver  → ModelSessionPolicy  (per encoder)
                                    │
                                    ▼
                    InferenceCompositionRoot.compose(...)
                                    │
                                    ▼
                    OrtSessionAssembler.buildManager(Composition, arbiter)
                                    │
                                    ▼
                    SessionOptionsApplier  ← the only caller of ORT setters
                                    │
                                    ▼
                    NativeSessionHandle (impl of SessionHandle)
                                    │
                                    ▼
                    InferenceSurface {
                      embedding:  Optional<EmbeddingAssembly>
                      ner:        Optional<NerAssembly>
                      reranker:   Optional<RerankerAssembly>
                      citation:   Optional<RerankerAssembly>    // shared shape
                      splade:     Optional<SpladeAssembly>
                      bgeM3:      Optional<BgeM3Assembly>
                      policies:   PolicySnapshot
                      handles:    List<SessionHandle>
                    }
```

### 1. Resolvers — pure functions over typed config

Two resolvers, each a pure function bound to the ordinal chain from
tempdocs 300 / 301 / 314 / 331:

- `RuntimePolicyResolver.resolve(cfg, hardware) → RuntimePolicy`.
  JVM-wide settings: arena strategy, CUDA provider options, session
  options, profiling.
- `ModelSessionPolicyResolver.resolve(role, cfg, hardware, variant) →
  ModelSessionPolicy`. Per-encoder settings: GPU (arena cap, CUDA
  device, stream binding), CPU (optimisation level), lifecycle (defer,
  retry), RunOptions (arena shrinkage).

Neither resolver calls `System.getenv` or consults parallel config
sources — the ordinal chain is already applied upstream by
`ResolvedConfigBuilder`. Resolvers are consumers, not second resolvers.

### 2. Composition root — single production entry point

```java
InferenceSurface InferenceCompositionRoot.compose(
    ResolvedConfig cfg,
    HardwareProfile hardware,
    InstallContract contract,     // null in dev mode
    Path modelsDir,
    GpuArbiter arbiter)
```

For each encoder role, `compose(...)`:

1. Resolves a `VariantSelection` via `VariantSelector.select(...)` when
   the install contract is present, or `DevModeVariantProbe.probe(...)`
   when it is absent. Both paths are *resolvers* — the composition root
   sees only the resulting `VariantSelection`.
2. Calls `ModelSessionPolicyResolver` to produce `ModelSessionPolicy`.
3. Wraps the policy triple in a `Composition` value and calls
   `OrtSessionAssembler.buildManager(Composition, arbiter)`, which
   returns a `SessionHandle`.
4. Calls the encoder's `buildAssembly(...)` static factory to load
   metadata (tokenizer, vocabulary, label mapping, input/output-name
   probe) from the model directory. The factory packages the
   `SessionHandle`, the loaded metadata, and the `<Role>Shape` into a
   `<Role>Assembly` record.
5. Stores the `Optional<<Role>Assembly>` on the returned
   `InferenceSurface`.

Encoder objects (e.g., `OnnxEmbeddingEncoder`, `SpladeEncoder`) are
**not** constructed inside `compose(...)`. `KnowledgeServer.initDeferredModels()`
destructures each Assembly from the surface and instantiates the
encoder from `(sessions, shape, tokenizer, …)`. This keeps the
composition root free of encoder-class dependencies — any code with
access to a model directory and a `SessionHandle` can construct the
encoder without going through the composition root.

Per-encoder failures surface as `Optional.empty()` on their slot —
a failed SPLADE load does not abort the whole Worker.

### 3. Assembler — single apply site for ORT setters

`OrtSessionAssembler` has these external entry points:

| Entry | Caller | Purpose |
|---|---|---|
| `buildManager(Composition, GpuArbiter) → SessionHandle` | `InferenceCompositionRoot.compose` (only) | Variant-driven production path |
| `verifyModelSession(env, modelPath, gpuConfig) → OrtSession` | `ModelVerifier` Gradle task | Dev tool (`./gradlew :modules:worker-core:verifyModel`) |
| `probeModelNames(env, modelPath) → ProbedNames` | Each encoder's `buildAssembly` static factory | Short-lived probe for input/output names |
| `probeOutputTensorInfo(env, modelPath, outputName) → Optional<ProbedTensorInfo>` | `ModelCapabilityResolver` | Boot-time graph probe for the embedding-dimension capability fact (no inference run) |
| `probeCustomMetadata(env, modelPath) → Map<String, String>` | `ModelCapabilityResolver` | Reads ONNX `metadata_props` embedded at model-build time (tempdoc 711 Item 3) — one probe session per `resolve` call, shared across every capability fact checked against embedded metadata |

Both `buildManager` and `verifyModelSession` route setter calls through
the same package-private helper:

```java
SessionOptionsApplier.apply(runtimePolicy, modelSessionPolicy, sessionOptions, cudaOpts)
```

`SessionOptionsApplier` walks the policy records field-by-field and
calls the corresponding ORT setter. Every CUDA provider option, GPU
session option, CPU session option, and run option derives its value
from a policy-record field. There are no hardcoded option values
outside the applier. The **`ClosurePropertyTest`** ArchUnit rule
forbids any encoder primary constructor from calling tokenizer-load,
`Files.read*`, `ObjectMapper`, or manifest-load APIs — metadata I/O
happens in `buildAssembly` static factories, not in constructors.

### 4. Handle + surface — encoders consume a typed interface

`SessionHandle extends AutoCloseable`; encoder-facing surface:

```java
Lease acquire()                   // GPU or CPU, semaphore-gated
Lease acquireCpu()                // CPU-only, fully concurrent
OrtEnvironment environment()      // shared JVM OrtEnvironment for tensors
boolean isGpuAvailable()
OrtCudaStatus status()
void releaseGpu()                 // GpuArbiter signals Head claimed GPU
void reportCpuSessionFailure()    // NaN-on-CPU-OOM recovery (F-009)
void setLifecycleCallback(...)    // pinned-memory cleanup hook (SPLADE)
@Override void close()            // idempotent; also iterated by InferenceSurface.close()
```

`NativeSessionHandle` is the concrete implementation (renamed from
`OrtSessionManager` in §14.23 of the tempdoc). Its `Builder` is
package-private; external callers reach sessions only through the
assembler. Encoders never see raw `OrtSession` — sessions flow through
a `SessionHandle.Lease` record with an `isCpu()` flag for fallback gating.
`InferenceSurface.close()` iterates `handles` and calls `close()` on each,
swallowing per-handle exceptions so shutdown continues.

---

## Fallback paths (dev mode / tests)

Two scenarios need sessions without a resolved `VariantSelection`:

- **Dev mode** — no `InstallContract` at AI Home. Resolved by
  `DevModeVariantProbe`, which produces a `VariantSelection` from a
  filesystem probe. The composition root calls it symmetrically with
  `VariantSelector.select`; no second code path exists downstream.

- **Tests + benchmarks** — need a `SessionHandle` from a model
  directory without constructing a full `ResolvedConfig`.
  `InferenceCompositionRootTestHelper.sessionFor(...)` lives in
  `modules/ort-common`'s `testFixtures` source set. The testFixtures
  Gradle scope keeps it off production runtime classpaths, so
  production code cannot reach it regardless of visibility.

No other fallback. The `EmbeddingProvider` SPI, `ModelSessionFactory`
customiser lambdas, every encoder's `buildSessionManager` private
helper, and the three post-§14.27 `@VisibleForTesting`
`OrtSessionAssembler` fallback entries (`buildFallback`,
`composeRerankFallback`, `composeCitationFallback`) were all deleted
by tempdoc 397's §14.28 U1.

---

## Policy record shape

The two records consumed by the applier (fields current as of
tempdoc 397 §14.25):

**`RuntimePolicy`** — lifetime: the process.

```text
RuntimePolicy {
  arena:        { extendStrategy, memoryPatternOptimization }
  cudaProvider: { cudaGraphsEnabled, tunableOpEnabled,
                  tunableOpTuningEnabled, cudnnMaxWorkspace,
                  epLevelUnifiedStream }
  session:      { interOpThreads, allowSpinning, forceSpinningStop,
                  useDeviceAllocatorForInitializers }
  profiling:    { ortProfilingDir, verboseLogging }
}
```

**`ModelSessionPolicy`** — lifetime: one session.

```text
ModelSessionPolicy {
  variant:    VariantSelection       // modelFile, precision, EP, degraded
  gpu:        { arenaCapBytes, cudaDeviceId,
                arenaExtendStrategyOverride }
  cpu:        { optLevel }
  lifecycle:  { deferCpuSession, gpuRetryEnabled, gpuRetryIntervalMs }
  runOptions: { arenaShrinkage }
}
```

Fields are added only when a current consumer exists — future work
(395 A1/A4/A7 adaptive policy, 394 P3 scheduler) adds the fields it
consumes without reshaping the pipeline.

---

## Diagnostics

`GET /api/debug/session-policies` returns the resolved `RuntimePolicy`
and every `ModelSessionPolicy` as JSON. The Head proxies to the
index half's live `InferenceSurface` via the `getSessionPolicies` port call
(tempdoc 397 §14.28 U4) — Head does not re-resolve. Response shape:

```json
{
  "configStatus": "ok" | "config-unavailable" | "surface-unavailable" | "worker-unreachable",
  "runtime": { ... },
  "models": { "embedding": { ... }, "splade": { ... }, ... }
}
```

`config-unavailable` = Head has no `ResolvedConfig` (e.g., boot hasn't
loaded settings yet); `surface-unavailable` = Worker hasn't composed yet;
`worker-unreachable` = the port call failed or Head has no client.

Because the applier reads the same record the endpoint serialises,
diffing two runs' snapshots is equivalent to diffing the applied
session options. No log archaeology.

---

## Shared resources during boot

Worker boot uses one `CountDownLatch modelReadyLatch` shared by two
consumers:

- The **migration enumerator** (tempdoc 332) awaits it before enqueuing
  files so the `IndexingLoop` does not process docs before SPLADE /
  embedding exist.
- The **query handlers** (tempdoc 397 §14.28 U3) —
  `WorkerSearchService.awaitModelsReady(...)` — block `search` /
  `retrieveContext` / `rerank` / `matchCitations` until the latch
  releases, closing a boot-race where queries arriving during init
  silently missed the reranker + citation wiring.

Both release at the same moment: after
`KnowledgeServer.initDeferredModels()` has wired every model (success
or failure). Both fall through to a degraded path on 120 s timeout.

---

## Key files

| File | Role |
|---|---|
| `modules/ort-common/.../RuntimePolicy.java` | Boot-time policy record |
| `modules/ort-common/.../ModelSessionPolicy.java` | Per-session policy record |
| `modules/ort-common/.../RuntimePolicyResolver.java` | Pure function over `ResolvedConfig` |
| `modules/ort-common/.../ModelSessionPolicyResolver.java` | Pure function over `(role, cfg, hw, variant)` |
| `modules/ort-common/.../OrtSessionAssembler.java` | Three external entry points |
| `modules/ort-common/.../SessionOptionsApplier.java` | The only ORT-setter site |
| `modules/ort-common/.../SessionHandle.java` | Encoder-facing interface |
| `modules/ort-common/.../NativeSessionHandle.java` | Concrete `SessionHandle` impl (package-private `Builder`) |
| `modules/ort-common/.../GpuArbiter.java` | Typed replacement for `BooleanSupplier shouldUseGpu` |
| `modules/indexer-worker/.../InferenceCompositionRoot.java` | `compose(...) → InferenceSurface` |
| `modules/indexer-worker/.../InferenceSurface.java` | Typed bundle of encoders + policies + handles |
| `modules/ui/.../SessionPoliciesController.java` | Head's `/api/debug/session-policies` endpoint (proxies to Worker) |
| `modules/ort-common/src/testFixtures/.../InferenceCompositionRootTestHelper.java` | Test-only session construction |

---

## See also

- Tempdoc 397 (session-policy centralization) — design + landing
  record, §7 architecture thesis, §14 phase-by-phase landing history.
- [`docs/reference/inference-runtime-register.md`](../reference/inference-runtime-register.md)
  D-007 entry — settled decision record with ArchUnit enforcement
  points.
- Tempdoc 381 (model-distribution architecture) — parent work that
  defined `VariantSelection`, `HardwareProfile`, `InstallContract`.
- Tempdoc 368 RC3 / T2 — closure-property grounding.
