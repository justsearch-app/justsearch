---
title: "Inference host lane: theorisation before design"
type: design
status: "THEORISATION (2026-09-10). Not a design, not a contract. Grounded at lane-F-A HEAD 4229f1091 by four read-only code audits; every code claim carries a file:line at that revision."
created: 2026-09-10
lane: inference host (the lane design.md section 5 names as the one after F)
model: fable (theorisation)
related:
  - lane-f-engine-jvm/design.md            # section 5 (the seam), section 4 (one batch bound), 3.1, 8, 10, 12, 16
  - lane-f-engine-jvm/stages/D1.md          # recompose, declared footprint, close/quiescence
  - lane-f-engine-jvm/stages/D2.md          # session gate with aging, profiles, one-producer test
  - ../tempdocs/903-non-nvidia-acceleration-reread.md   # Vulkan chat, WebGPU EP
  - ../decisions/0049-one-engine-jvm-and-the-boundaries-that-survive.md
  - ../decisions/0004-single-tenant-gpu-policy.md       # the reliable GPU-lease doc (05-ai-architecture is stale)
  - ../reference/inference-runtime-register.md
---

# Inference host lane: theorisation before design

This document records what the code says today, re-weighs the reasons the lane exists, and
proposes the shape of the design and its sequencing. It decides nothing. A design that follows
it should cite this file for facts and overrule its judgements explicitly where it disagrees.

## 0. What design.md section 5 promised, in one paragraph

Encoders behind a narrow versioned contract serving index-time batches and request-time small
calls with foreground precedence and one bounded aging exception; hosts shared within a
compatible user, runtime and model environment; a runtime-neutral contract where CUDA is one
implementation and an in-process provider stays valid; engine restarts leaving models warm in
the host; GPU probing and status behind the host. Lane F owes the target four semantic
obligations: model identity and capability set per call, cancellation, partial failure, and the
binding of a query embedding to the index generation it may search. Triggers named: encoder
faults in the field, a second GPU runtime or OS, concurrent verification stacks, stage-1
reconfigure cost.

## 1. The facts that change the case

Each item is a finding from the audits that a design must build on rather than around.

### 1.1 The consumer surface is seven operations, and one promised operation does not exist

Thirty-two production call sites reduce to: `embed.document` (batch), `embed.query` (single,
with an in-process TTL cache), `embed.spans` (late chunking, one document, multi-output),
`embed.windows` (resumable slice), `expand.sparse` (SPLADE), `encode.unified` (BGE-M3),
`rerank`, `cite`, and `tag` (NER). **There is no query-time NER.** `NerService` is reached only
from `KnowledgeServer` wiring, `DevReloadManager`, `NerBackfillOps` and
`CombinedEnrichmentBackfillOps`; design.md sections 4 and 5 name "query NER" as a request-time
consumer and it is not one. NER is index-time only.

Only `rerank` and `cite` carry a deadline (200 ms, 150 ms, and a real per-sentence deadline in
`CitationScorer`). Every index-time call is budgeted between calls by `BackfillScheduler`, never
inside one; an in-flight `session.run()` is never interrupted. One request-time site issues an
unbounded serial loop of single embeddings on a request thread
(`RagContextOps.diversifyMmr`, `:1455`).

### 1.2 The ORT seam leaks in eight places, and every leak is the same redesign whether or not a process boundary exists

`SessionHandle.Lease` carries a raw `OrtSession` and `RunOptions` by reference; every encoder
builds its own `OnnxTensor` from `sessions.environment()`; `lease.isCpu()` is control flow, not
telemetry; `acquireCpu()` is a second lease type used mid-call for the BFC-arena retry ladder;
`NativeSessionHandle.isBfcArenaFailure` is a public static imported by encoders, contradicting
`SessionHandle`'s own contract; SPLADE keeps a ~476 MB pinned direct buffer across calls via a
lifecycle callback that runs synchronously under the semaphore; `OrtCudaStatus` is read by three
request-path classes; no operation returns model identity or a truncation flag. The tokenizers
are DJL (JNI) and live on the assemblies.

**Consequence.** The contract cut (operation in, result out, model identity attached) is the
whole of the work that makes a process boundary possible, and it is valuable in-process on its
own: it deletes ORT and DJL from every module but one. The design should treat placement as a
second implementation of a port, not as the lane's first move.

### 1.3 The "one batch bound" is per role, not per device; one semaphore per composed role

`InferenceCompositionRoot.compose` builds one `NativeSessionHandle` per composed role, each with
its own one-permit semaphore (`NativeSessionHandle.java:117`): five handles in the SPLADE
configuration (four GPU-capable, citation CPU-only), four with BGE-M3 selected (three
GPU-capable), since `EMBEDDING` and `BGE_M3` are exclusive (`InferenceCompositionRoot.java:128-161`).
*(Corrected 2026-09-10 from "six instances" after independent review.)* Serialization holds
within a role only. Embed, SPLADE and rerank can be in `session.run()` at the same time on three handles.
CPU leases take no permit (`:511-514`), so the CPU-only citation role has no serialization at all
and up to sixteen foreground threads can run it concurrently.

**Consequence.** design.md section 4's premise that a foreground rerank "queues behind the batch
already on the GPU" is false for `RERANKER` and `CITATION`, which have no index-time producer.
A host with one device queue would serialize across roles, which today's code does not. That
could be a throughput regression (lost overlap) or a gain (the 270 s stalls seen in
`evidence/C1/gpu-scheduling-connect.md` were device contention). The host's scheduler must be
designed at the device level with role classes, and the choice between "one queue" and "bounded
concurrency" must be measured, not assumed. The D2-4 one-producer test does not exist yet;
D2 schedules it.

### 1.4 There is no VRAM model, only per-session caps that nothing sums

Caps: embed 6144, SPLADE 4096, BGE-M3 3072, NER 2048, reranker 2048 MB, citation 0. Real
co-resident maxima are 14 336 MB (SPLADE configuration) or 7 168 MB (BGE-M3 configuration)
against a 12 GB dev card. No code computes either number; `ModelSessionPolicyResolver` takes a
`HardwareProfile` and never reads it (`:61-64`). The engine composition path passes
`HardwareProfile.gpuFull(0)` when there is no install contract (`KnowledgeServer.java:1408-1411`),
so `vramBytes` is zero on the session-assembly path. NVML free memory is read in `gpu-bridge`,
which `ort-common`, `worker-core`, `worker-services` and `indexer-worker` do not depend on.
`RuntimeGpuLease.requestGrant` ignores its size argument and has zero production callers. No
model entry declares a device footprint; only the chat package carries `minVramBytes`.

On the LLM claim, **only embedding and the search reranker release VRAM**
(four `releaseGpuSession()` call sites in all of `modules/**/src/main`). SPLADE, BGE-M3 and NER
keep their open GPU sessions and their arenas while llama-server loads. Whether that is
intentional is undocumented and is the single most important open question for a VRAM model.

**Consequence.** The host is the only component that can own a real device budget, and it should
own it by measurement, not declaration: observe arena growth per (model, variant, batch profile)
the way the llama self-test already snapshots VRAM before and after a load
(`RuntimeActivationService.java:1209-1237`), persist it, and admit by observed footprint plus
margin. D1's "declared footprint" obligation (design.md 7.4) is better discharged by the host
than by a catalogue field nobody can fill honestly.

### 1.5 GPU arbitration is a shared mutable object passed by reference, with a known race

`GpuSchedulingGauge` is two volatile booleans with no listeners, constructed once
(`KnowledgeServerBootstrap.java:102-103`) and threaded by reference into `EngineRoot` and
`InProcessWorkerSignalBus` ("the same instance ... or the worker reads a gauge nobody writes").
Nobody listens; seven sites poll. Two edge detectors with different initial states act on the
rising edge. On the falling edge, `TransitionRunner` fires listeners at the start of the
transition (`:338`) and the broadcast listener re-reads `manager.isOnline()`, so the gauge says
"GPU free" before `stopLlamaServer()` and the VRAM flush sleep run
(`InferenceLifecycleManager.java:551-553`). The diagnostic log that caught C1's disabled
broadcast was removed with the fix; the null-bootstrap branch is now a silent early return.

**Consequence.** A host reintroduces the cross-process signal lane F deleted, but as an API on
the party that owns the device rather than as a flag two processes poll. That is a better shape
than the MMF byte was, and it is the natural place to fix the falling-edge race: the host
observes the device, it does not trust a mode callback.

### 1.6 Native fault isolation is real but has no field evidence

No Java code can catch a real CUDA fault; `Lease.run()` has no try/catch and a native abort
never becomes an exception. The blast radius is the whole Engine after lane F, and ADR-0049 says
so. But the Worker restart rate in development was zero across 183 runs
(`evidence/baseline/README.md:91-96`), and no test simulates a native fault. Recovery is a
legitimate reason for the boundary under ADR-0049's rule; it is not yet an urgent one.

### 1.7 The multi-Engine blocker is not ports or data directories

Per-worktree data dirs, ephemeral API and llama ports, and scanned JDWP ports already exist.
What blocks N Engines is: GPU exclusivity with no CPU fallback; one shared lease file by policy
(`dev-runner.cjs:53-62`); two write-side races in the shared models directory, namely
`OrtCudaHelper.copyCudaDllsToOrtTempDir`'s "most recently modified `%TEMP%` dir" heuristic
(self-documented as assuming one ORT JVM per machine, `:453-457`) and `OnnxSessionCache`
writing `.optimized` graphs beside the shared `.onnx` file (`:41-43`); the fixed Vite port; and
`cmdStop`'s port-owner `taskkill` that does not verify the PID belongs to its run
(`dev-runner.cjs:3140-3161`). jseval hard-codes port 33221 with no override.

`JUSTSEARCH_LITE_MODE` plus `IsolatedBackendFixture` already boot an encoder-less Engine to
health in 2.45 to 2.74 s p50 with a temp data dir and an OS port. Neither design.md nor D2.md
mentions it; D2-2's `verification` profile is a productisation of it.

**Consequence.** Most of the agent-throughput win the design attributes to the host is
available without one: fix the two cache races, split the lease into an Engine lease and a GPU
lease, expose the jseval port, and productise lite mode. The host is what lets the GPU lease
outlive an Engine and be shared, which is the remaining and larger part.

### 1.8 The "40 s encoder reload" has no measurement behind it

Every occurrence traces to MCP tool description strings (`server.mjs:1207,1213,1237`) or to
design.md quoting them. Measured: HTTP ready 2.56 s, worker ready 7.48 s (`evidence/pr0`).
The in-code estimate for `initDeferredModels` is 15 to 20 s (`KnowledgeServer.java:2709`).
There is no boot-time warm-up inference for encoders (only a reranker warm-up rerank at
`:1576`). The encoder-warm milestone has never been recorded.

**Consequence.** "Engine restart is a 3 s JVM start with models warm in the host" is half
measured. Before any design commits to the warm-survival benefit, one number must exist: time
from spawn to last encoder wired, on the reference machine, with and without the CUDA graph
cache warm.

### 1.9 Runtime neutrality does not need a process

903 shows llama.cpp Vulkan for chat is Engine-side install-matrix work touching no ORT path,
and the WebGPU plugin EP for encoders enters through D-007's single apply site in-process.
A process boundary gives the runtime choice a home, not a mechanism. The contract must be
EP-neutral (an EP kind beside `arenaCapBytes > 0`, since WebGPU has no BFC arena); the process
is not what makes it so.

### 1.10 The child machinery is mostly reusable and llama-shaped in four places

Reusable as-is: `ManagedChild` identity capture and tri-state comparison, the registry with
persist-then-set, manifest schema v2, the reconciler skeleton (dead, unknown, mismatch, match),
the ownership seed, the shutdown handoff, and the supervisors' terminal kill of any registered
child by identity. Llama-shaped: the two-value `Kind` enum; the reconciler's hard-coded
`healthyLlama` probe and single `declaredLlamaConfigHash`; the SHA-256 config-hash helper
duplicated privately in the extraction pool; and `ShutdownRequest.Reason.stopsGenerativeBackend()`
as the only stop-on-reason policy. Windows Job Object containment is parser-package-private.
llama-server is unauthenticated on loopback and its port is not in the manifest; there is no
egress factory; `InferenceHttpHelpers` uses `localhost` while registration uses `127.0.0.1`.

Neither shipped transport fits a batch encoder protocol: the extraction frames have no
per-request cancel and die with the parent; the llama HTTP path is JSON-only with cancellation
bound on one of two stream families. Only the HTTP precedent supports adoption after an Engine
restart, which is the warm-survival feature.

### 1.11 Generation binding is not implemented and there is a candidate live defect

`EmbeddingCompatibilityController` is a process-wide boolean latched at boot from the ingest
runtime's commit metadata. No model id, hash, dimension or generation check runs per query; a
dimension mismatch surfaces as an uncaught Lucene exception on the read path. During a Blue/Green
migration the controller reads `ingestLifecycle` (fresh, empty Green) while queries serve Blue,
so `allowQueryEmbeddings()` can be true against old-encoder vectors
(`KnowledgeServer.java:1788-1841`, `:1019` ordering). This is a call-ordering read, not an
observed failure, and should be probed live.

**Consequence.** Model identity per result is the host contract's cheapest and most valuable
field. The Engine binds it at the port against the active generation's `IndexFingerprint`
model hashes, which already exist as commit metadata.

## 2. Re-weighing the triggers

| trigger (design.md section 5) | evidence today | weight |
|---|---|---|
| encoder faults in the field | none recorded; zero dev restarts in 183 runs; no fault test exists | real under ADR-0049, not urgent |
| second GPU runtime or OS | 903: Vulkan chat needs no host; WebGPU EP is an in-process EP kind | needs the contract, not the process |
| concurrent verification stacks | blockers are lease policy, two cache races, GPU exclusivity; lite mode already boots in 2.5 s | the host owns the GPU-lease half; the rest is cheaper without it |
| stage-1 reconfigure cost | compose-beside needs VRAM for two either way; a host cannot create headroom | neutral; the host makes the beside-or-in-place decision measurable |
| warm models across Engine restarts (section 12) | unmeasured baseline; likely 15 to 20 s, not 40 | real, must be measured first |
| four semantic obligations (identity, cancellation, partial failure, generation binding) | none implemented | needed regardless of placement |

The honest ranking: the contract cut first, because every trigger needs it and it pays in-process;
GPU ownership as a shareable, longer-lived lease second, because it is the one benefit only a
process can deliver; fault isolation third, because it comes free with the second; runtime
neutrality fourth, as an EP kind inside whichever host exists.

## 3. Proposed shape

### 3.1 The port

One port in a contract module (`core` for the search half, following `GpuSchedulingGauge`'s
precedent; or `app-api`), catalogued in `governance/engine-ports.v1.json`:

- `embed(EmbedRequest) -> EmbedResult` covering document batch, query, spans and windows as
  request variants, not four methods; `expand(SparseRequest) -> SparseResult`;
  `encode(UnifiedRequest) -> UnifiedResult`; `rerank(RerankRequest) -> RerankResult`;
  `cite(CiteRequest) -> CiteResult`; `tag(TagRequest) -> TagResult`.
- Every request carries the `EngineContext` (C1) for urgency, a work id for cancellation, and a
  deadline. Every result carries `ModelIdentity` (role, model sha256, variant, EP, dimension where
  applicable), `truncation` per item, `partial` per item with a reason, and timing.
- Inputs are text. Tokenization moves behind the port; the Engine loses DJL and ORT.
- `capabilities() -> CapabilitySet` per role (dimension, max sequence, pooling, prefixes, EP),
  replacing the `documentWindowCount() == 1` sentinel and the `isGpuAvailable()` control flow.
- `status() -> HostStatus`: per-role readiness as absent, loading, ready, degraded with reason;
  device budget observed versus committed; queue depths. This becomes the producer of the
  component map's encoder rows (D2-1) and of `/api/inference/encoders`.
- No lease, no session, no tensor, no `isCpu` crosses the port. CPU fallback is the host's
  internal ladder and appears in the result as `executedOn` plus a degradation reason.

The `SearchPort` and `IndexingService` consumers listed in 1.1 become the port's catalogued
consumers; `KnowledgeServer.initDeferredModels` becomes a host client connecting, not a composer
of sessions.

### 3.2 The host

The host owns: model resolution and manifests, tokenizers, ORT environment and native path
setup, sessions and their caches (the `.optimized` graphs and the ORT temp dir, so the two
shared-directory races become one owner), the device scheduler, the CPU fallback ladders, the
SPLADE pinned buffer, the observed-footprint register, and device probing (NVML). It has two
implementations of the same port: **in-process** (stage 1 of this lane) and **managed child**
(stage 2). ArchUnit pins that only the host module imports `ai.onnxruntime..` and `ai.djl..`;
the design.md section 5 claim "ArchUnit-pinned" becomes true (today the one candidate rule
allowlists `io.justsearch.indexerworker..`).

### 3.3 The scheduler

Device-level admission with role classes, generalising D2-4's gate: foreground precedence with
one bounded aging exception, per the amended design.md section 4, but keyed on the device, not on
one role's session. Bounded concurrency across roles is a parameter (default: today's behaviour,
concurrent roles, one batch per role) so that stage E's request-time-encoder row measures the
same policy before and after placement. The producer token per stage (D2-4) is issued by the
host. Cancellation: a work id maps to a queued or running item; queued items cancel immediately,
running items complete their current `run()` (never preempted) and drop the remainder of a
batch.

### 3.4 The device budget

Observed, not declared. On first load of (model, variant, EP, batch profile) the host records
the arena and device delta after warm-up and after the first saturating batch, persists it in
its own store, and admits later loads against free device memory minus committed footprints
minus a margin. The llama-server footprint enters the same budget as an external tenant
reported by the Engine (or, in the reassess case, owned by the host). Compose-beside versus
in-place (design.md 7.4) is then a query to the budget, which is the mechanism D1 lacks.

### 3.5 Arbitration with llama-server

Stage 1 (in-process host): unchanged mechanism, with the falling-edge race fixed by having the
host observe device free memory before re-acquiring rather than trusting the mode callback, and
with per-role release made explicit and uniform (a policy per role: release on claim, or keep
with a bounded arena).

Stage 2 (child host): the gauge write becomes a host API call (`claim(tenant, footprint)`,
`release(tenant)`), the host being the device owner. Two options for llama-server:

- **(a) the host owns llama-server too** (a "GPU host"): one process owns every VRAM tenant,
  arbitration is local again, and the Engine talks to one child for all inference. This is the
  cleanest end state under ADR-0049's scarce-resource criterion, and the largest scope: it moves
  `InferenceLifecycleManager`, the ladder, adoption and the reconciler's llama branch.
- **(b) the Engine keeps llama-server and informs the host**: smaller, keeps lane F's shipped
  lifecycle intact, leaves the Engine as arbiter between two children.

Recommendation: (b) for stage 2, with (a) recorded as the reassess trigger once the host is
proven and D-010's co-residency question (Q-002 half b) has a measurement.

### 3.6 Transport and trust for the child

Loopback HTTP for control (health, status, capabilities, claim and release, cancel) so adoption
across an Engine restart reuses the reconciler's probe shape; a binary body for tensors
(length-prefixed float32 and int32 arrays or Arrow IPC), never JSON floats, on the same
connection. Per-request id echoed in the response and validated, as the extraction protocol does.
The child is registered as `Kind.ENCODER_HOST` with a declared config hash lifted to a shared
`app-api` helper, authenticates with a per-boot token published through the child registry
(not the public manifest projection), binds `127.0.0.1` consistently, and gets Job Object
containment lifted out of the parser package. Stop-on-reason: like llama-server, survive
`RESTART` and `HANG`, stop on `QUIT` and `UPGRADE`; `stopsGenerativeBackend()` becomes a
per-kind predicate.

### 3.7 Development shape

Two leases: an Engine lease per worktree (cheap, already mostly in place) and a GPU lease held by
the host (long-lived, `leaseDurationSec` semantics as today). The dev-runner starts the host once
per machine and any number of Engines against it; `quick_health` reports the host as a
registered child kind rather than a bare inference-port probe. Prerequisites that need no host:
the two cache races, the jseval port override, the Vite port, the `cmdStop` PID guard, the RSS
sampler's process attribution, and `verification` profile over lite mode.

## 4. Sequencing proposal

- **H0, measure and unblock (no host, no contract change).** Record the encoder-warm milestone;
  measure co-resident VRAM with llama-server resident and the effect of cross-role concurrency;
  probe the migration-window gap of 1.11; fix the two shared-models races by giving each ORT
  process its own temp dir and pre-warming or per-process-ing the optimized cache; expose the
  jseval port; guard `cmdStop`; productise lite mode as `verification`. Most of the concurrent-
  stack benefit lands here.
- **H1, the contract cut, in-process.** The port of 3.1, the host module of 3.2, the device
  scheduler of 3.3 (defaulting to today's concurrency), model identity per result bound at the
  search port against `IndexFingerprint`, observed footprints of 3.4, uniform per-role release,
  the falling-edge fix, ORT and DJL confined by ArchUnit. Stage E's rows run against this.
- **H2, the managed child.** Second implementation of the port over 3.6; adoption after Engine
  restart; the GPU lease as a host API; N Engines against one host in the dev-runner.
- **H3, runtime neutrality inside the host.** EP kind in the policy record; WebGPU plugin EP
  (903 option c) behind the same port; Vulkan chat (903 option b) proceeds independently on the
  Engine side and is not this lane's.
- **Reassess triggers.** A measured co-residency budget that makes 3.5(a) cheaper than two
  arbiters; a real encoder fault in the field; a second OS.

## 5. Where this touches lane F's remaining stages

D1 and D2 build in-process mechanisms the host would re-home: D1's recompose-beside-or-in-place,
declared footprint and the close/quiescence obligation; D2-4's gate inside `NativeSessionHandle`
and the one-producer test; D2-8's request-time timings. If H1 lands after D1 and D2 as
written, those mechanisms are built twice. The cheaper order builds D1 and D2's mechanisms
behind H1's port from the start, which is design.md 17.8's fifth bullet ("a stage needs a
mechanism the table places later") applied across lanes. That is the lane-F orchestrator's
decision, not this document's; it is named here so it is decided rather than discovered.

Defects found by the audits that belong to lane F regardless of this lane: the falling-edge
race (1.5); `NativeSessionHandle.close()` taking no semaphore and a post-close `acquire()`
recreating an unclosed CPU session (D1 quiescence, `:552-577`, `:587-627`); the three
`*_enabled` status flags defaulting to true until the second-to-last line of
`initDeferredModels` (`IndexStatusOps.java:140-142`); the removed "broadcast disabled"
diagnostic; `verified-facts.md:165` and design.md section 8 saying the arena cap is "set from
GPU VRAM" when it is a static per-role default; F-009's NaN-triggers-recovery path not existing.

## 6. Open questions the design must answer

1. Is it intentional that SPLADE, BGE-M3 and NER keep their GPU sessions while llama-server
   loads? (Resolve by runtime `VariantSelection` on the shipped default profile.)
2. Does cross-role concurrency on one device help or hurt under the reference corpus? (H0.)
3. What is the encoder-warm milestone, with and without a warm optimized-graph cache? (H0.)
4. Does the migration-window `allowQueryEmbeddings` gap fire live? (H0 probe.)
5. Should the query-embedding TTL cache live in the Engine or the host? (Engine, tentatively:
   it is keyed on text and independent of placement; the host result carries identity so the
   cache can be invalidated on model change.)
6. Which `EngineContext` fields cross the child boundary? (Urgency, work id, deadline, and an
   opaque correlation id; never client identity or grants.)
