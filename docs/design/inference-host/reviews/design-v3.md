---
title: "Inference host lane: one port, an in-process adapter, an unshared child, and the device ledger (v3)"
type: design
status: "DRAFT v3 FOR THIRD INDEPENDENT REVIEW (2026-09-10). Not authoritative. Revised against round 2 (review-2-findings.md; every finding answered in review-2-response.md). Docs at lane-F-A 1465acb7a; code unchanged since 4229f1091, so every file:line is at 4229f1091. Sources are in context/src-tree/ with repo-relative paths; context/inventory.md lists them."
created: 2026-09-10
updated: 2026-09-10
lane: inference host (lane F design.md section 5 names it as the lane after F)
model: fable (design)
related:
  - review-1-findings.md, review-1-response.md, review-2-findings.md, review-2-response.md
  - context/theorisation.md
  - context/design/design.md; context/design/stages/D1.md (D1-1, D1-9, D1-12, D1-13, D1-14), D2.md (D2-2, D2-4), C1.md (1376-1387, 1582-1589)
  - context/evidence/C2-operations-store-design.md
  - context/docs/0049-*, 0004-*, 0046-*, 903-*
---

# Inference host lane: one port, an in-process adapter, an unshared child, and the device ledger (v3)

## 0. What v3 changed, and why

Round 2 found eight High and eight Medium gaps, all at transitions: a reservation becoming
residency, a launch surviving a crash, one attachment replacing another, remote completion
releasing local ownership, a supervisor dying under a live shared host. Five of the eight Highs
and three Mediums (findings 2, 4, 5, 11, 12 and half of 3) exist only because the host was
**shared** by several Engines. The theorisation had already found that most of the developer-
throughput benefit comes from H0 without any host. v3 therefore:

1. **Descopes the shared host.** H2 is the *unshared* managed child: one Engine, one host,
   ordinary child ownership under lane F's rules. The shared host becomes **H2b**, a separate
   stage with its own ownership protocol, listed in section 13 as open work with the questions
   round 2 raised, not as decided mechanism. H0's encoders-off coexistence carries the
   multi-Engine developer case until H2b is justified by a measured need.
2. **Specifies the ledger as a state machine** with allocation classes and conservation rules,
   a persisted launch intent that precedes any spawn, and one accounting authority for grant,
   materialisation, reconciliation, observation and release (findings 1, 2, 9).
3. **Makes the submission the completion unit.** The request UUID is the per-call identity;
   terminal records are retrievable and retired by acknowledgement; `cancel(workId)` is a group
   fence, not the completion identity (findings 3, 12).
4. **Fixes the passed-rule unit** to one native wait, states the resulting per-request bound,
   and applies the fairness cursor to aged candidates (finding 10).
5. **Defines the representation descriptor** canonically (finding 7), **binds derived writes
   to the source revision** and names the two acknowledgements apart (finding 8), makes the
   mismatch fallback CPU-only (finding 6), keeps set identity out of the process hash and gives
   sets an acquire/release contract (finding 11), fixes the exact-comparison boundary
   (finding 14), and adopts round 2's cross-lane wording verbatim (finding 15).

Everything the two rounds verified sound is kept: the operation seam, D1-12 as substrate, the
representation/execution split, reserve-before-allocation, release-after-confirmed-stop,
D1-13 owning native retirement, the two ownership models, attach-versus-drain, the shared
token, layered exactness.

## 1. Scope

**Decides:** one operation-shaped port; an in-process adapter over D1's encoder sets (H1); an
unshared managed child (H2); the device ledger with tickets, launch intents and allocation
classes; llama-server as a reserving tenant; per-submission completion; set acquisition and
release; the exact-comparison boundary; the sequence and gates.

**Leaves alone:** search semantics; catalogue and install matrix; llama-server's lifecycle
beyond the reservation step; the extraction pool; lane F's stages as re-grounded, including D1
batch 4 and D2.

**Defers, explicitly:** the shared host (H2b, section 13); a remote host; a non-JVM host; a
host owning llama-server (reassess trigger); the BGE-M3 fingerprint migration (D1 section 9 row
4, a named follow-up with its own gate, section 6.2).

## 2. Principle

ADR-0049's rule justifies the boundary (`0049:70`, `:164-165`). The seam that exists,
`SessionHandle.Lease`, carries raw ORT objects (`SessionHandle.java:179-207`) and cannot cross a
process; closing that leak is the same work in either placement. Port-first is a
**risk-reduction order**, not a cost claim. The host is the only component that can own the
device ledger, because it is the only place that sees every reservation and allocation.

## 3. Architecture

```text
Tauri / dev-runner  supervises ->  Engine JVM ──HTTP+MCP──> webview, MCP clients
                                     │
                                     ├─ InferencePort ──> [H1] InProcessInferenceAdapter over D1's EncoderSets
                                     │                    [H2] EncoderHostClient ──HTTP──> encoder-host JVM (unshared, Engine-owned child)
                                     ├─ generative backend ──> llama-server.exe (reserves a ticket before every launch)
                                     └─ ExtractionSandbox ──> parser child pool (unchanged)
```

**Ownership.** Host: encoder sets (D1-12 relocated), models and manifests, tokenizers, ORT
environment and native path, sessions and caches, scheduler, CPU ladders, pinned buffers,
warm-up, device probing, the ledger, launch intents, footprint profiles, submission records.
Engine: component registry and readiness (D1-1/2), admission and work ids (C1), set resolution
per call (serving or candidate), read and write binding, llama-server's lifecycle plus one
reservation step, what results mean. ORT and DJL are imported only by `modules/inference-host`
(ArchUnit; today `IndexWriterOwnershipTest.java:25-28` allowlists the assemblies' package).

The port lives in `modules/core`, `io.justsearch.core.inference`, catalogued in
`governance/engine-ports.v1.json`.

## 4. The port

### 4.1 Handles

- `EncoderSetHandle`: opaque, immutable, names one composed set and its representation
  descriptor. Acquired and released (4.6). Unknown or retired handles are refused `SET_RETIRED`.
- `InferenceContext(urgency, workId, budgetMs, correlationId)`. `workId` is the Engine-minted C1
  work id and identifies the **group**; nothing else from `EngineContext` crosses. (The
  attachment field of v2 is H2b's, section 13.)
- `SubmissionId`: the request UUID, minted by the client per call, echoed by the host, scoped by
  host boot id. It is the **per-call identity** for completion, cancellation of one call, and
  terminal-record retrieval (finding 3).
- `ProducerToken`: per `(clientBootId, role)` for background producers.

### 4.2 Operations

Text in, arrays out. `embed(set, texts, mode, ctx)` with `mode ∈ DOCUMENT, QUERY, SPANS(spans),
WINDOWS(from, max)`; `WINDOWS(0, 0)` is tokenizer-only, returns per-text window counts, takes no
device seat, and preserves the document-prefix and window semantics `EmbeddingService` uses for
both counting and embedding (`:530-554`). `expand`, `rerank`, `score`, `tag` as v2. `tag` is
index-time only (no query-time NER, audit 1 section 1).

Results carry the set's `RepresentationDescriptor` (4.3), per item or homogeneous group the
`ExecutionIdentity` that produced it, per item `outcome ∈ OK, TRUNCATED(tokensDropped),
FAILED(reason), SKIPPED_DEADLINE`, `executedOn`, and timings. Timings and transport ids are
**metadata outside the exact-comparison boundary** (section 11).

### 4.3 Identity

- **`RepresentationDescriptor`** (versioned, canonical). Per role: the digest of the model file
  family (all declared compatible execution variants, from the manifest), the digest of the
  model directory's manifest and tokenizer artifacts (`model_manifest.json`, tokenizer files),
  resolved preprocessing (document and query prefixes, pooling, window geometry, max sequence),
  output shape (dense dimension; sparse vocabulary id; NER label set id), and the descriptor
  version. It is what a persisted representation is compatible with. Today the persisted
  fingerprint has three model keys and no BGE-M3 digest (`IndexFingerprint.java:118-119`), and
  prefixes and pooling live on the manifest (`ModelManifest.java:32-46, 78-85`,
  `EmbeddingService.java:125-131, 238-249`), so the descriptor is strictly more than the
  fingerprint records. **Policy for generations lacking descriptor fields:** `unknown` is
  distinguished from `known-absent`; a generation whose stored metadata cannot verify a field
  is served only when every field it *does* record matches, is reported `binding: partial`,
  and any operation that would *change* a representation-bound setting against it is refused
  until the migration lands. The H1 binding gate is green only for generations that record the
  full descriptor; `partial` is never counted as pass.
- **`ExecutionIdentity`**: realised file, precision, execution provider (it changes under the
  FP16-to-FP32 fallback, `NativeSessionHandle.java:655-680`, and under CPU fallback). Reported,
  never used for compatibility. Compatibility across execution variants is what the descriptor's
  family digest declares; filenames alone are not a proof.

### 4.4 Submissions, completion, cancellation

Three facts, independent, not a sequence: *transport detached* (the client is gone), *cancel
accepted* (the host acknowledged a cancel), *execution terminated* (the host's accepted work for
a submission has ended and every native lease, result buffer and post-processing resource it
owned is released). Normal completion is execution terminated with a result; it passes through
neither of the others.

- **Per-submission terminal record.** The host keeps, per `SubmissionId`, a record
  `{state ∈ QUEUED, RUNNING, TERMINATED(outcome), REFUSED(reason)}`. `outcome` is the result or
  `CANCELLED` or `FAILED`. `TERMINATED` means execution terminated as defined above; returning
  from `run()` is not enough while results are still owned (`SessionHandle.java:191-207`).
- **Retrieval and acknowledgement.** `outcome(submissionId)` returns the record idempotently; a
  lost response is recovered by retrieval; the client acknowledges (`ack(submissionId)`) and the
  host retires the record. Unacknowledged records are retained under a per-client count and byte
  bound; when the bound is reached the host refuses new submissions from that client with
  `SUBMISSION_BACKLOG` until acknowledgements arrive. A client boot id change retires its
  predecessor's records once their execution has terminated.
- **Engine retention** (C1 `stages/C1.md:1376-1387`, `EngineFutures.java:95-129`): each
  submission is a retained child of the C1 task group; it is released on `TERMINATED` or
  `REFUSED`, or on confirmed host death; never on transport detach, never on a timeout. A group
  seals when its submissions have all terminated. Two submissions sharing a work id release
  independently.
- **Cancellation.** `cancel(submissionId)` cancels one call; `cancel(workId)` is a **group
  fence**: every current and future submission carrying that work id is refused `CANCELLED` and
  queued ones are cancelled. Fences are bounded: a client carries a monotonically increasing
  `fenceWatermark` per work id; a submission with a sequence below the watermark is refused
  without the host retaining per-UUID history (finding 12). Enqueue and cancel are linearised
  per work id under one lock. A running `run()` is never preempted; a cancelled batch drops its
  remaining sub-batches and then terminates.
- **Refusals.** Pre-admission refusals are first-class: `SET_RETIRED`, `CANCELLED` (fence),
  `SUBMISSION_BACKLOG`, `QUEUE_FULL(retryAfter)`, `PRODUCER_HELD`, `HOST_UNAVAILABLE(reason)`;
  none is folded into a whole-request failure rule.

### 4.5 Bounds independent of client count

Queued submissions and bytes, unacknowledged records and bytes, outstanding submissions per
producer (one by default), CPU-lane concurrency, and retained result frames awaiting delivery
(released on delivery or on client boot change, never extending a device seat). Each has a
policy cap and a refusal with retry-after.

### 4.6 Sets: acquire, release, reload

`acquireSet(spec) -> EncoderSetHandle` composes or returns the existing immutable set matching
the spec and adds a reference; `releaseSet(handle)` drops it. A set retires only when its
references are zero **and** D1-13's lease accounting reports no outstanding leases, or by the
Engine's explicit retirement of a candidate. `reload(spec)` never mutates a set: it acquires a
new set (beside if the ledger grants its ticket, else in place after retiring the old set
under D1-14's rules) and returns the **new** handle; the Engine swaps its serving pointer. The
host's process identity (7.1) does not include set specs, so an ordinary reconfigure never
manufactures a process mismatch (finding 11).

### 4.7 `capabilities()` and `status()`

`capabilities(set, role)`: descriptor, execution variants, `maxSequenceTokens`, pooling,
prefixes, `supportsSpans`, `supportsWindows`, `unifiedSparse`. `status()`: per set and role
`ABSENT(reason) | LOADING | READY(exec) | DEGRADED(reason, exec)`; the ledger (5.3); queue and
seating counters; submission backlog per client. The Engine's `encoders` component (D1-1)
projects it; the `*_enabled` defaults (`IndexStatusOps.java:140-142`) retire.

## 5. The host

### 5.1 Composition

As v2: `modules/inference-host` holds the encoders, the D-007 pipeline, the caches and native
helpers, and, relocated from D1, `EncoderSet` (D1-12) and per-handle lease accounting (D1-13).
The scheduler sits at the `Lease#run` choke point (`OrtRunChokePointTest.java:78-106`).

### 5.2 Scheduler

Facts as corrected: five composed handles in the SPLADE configuration (four GPU-capable), four
with BGE-M3 (three), each with its own one-permit semaphore (`NativeSessionHandle.java:117`,
`InferenceCompositionRoot.java:128-161`); CPU leases take no permit (`:310-338`).

**Grants are one atomic decision at device scope.** `K` seats per device; one lane per composed
GPU role; a seat is one **native wait** (one `run()`), and a lane releases eligibility between
sub-batches.

1. Foreground precedence at device scope.
2. **One aging exception per native wait.** A background head past the aging threshold is seated
   once; every foreground *native wait* pending at that instant is marked `passed`; aging is
   suspended host-wide while any marked wait is still pending. **Unit of the bound:** a
   foreground native wait is overtaken by at most one aged background sub-batch. A foreground
   request of `n` sub-batches is therefore overtaken by at most `n` aged sub-batches over its
   life, and by at most one during any contiguous wait. This is lane F design 4's bound, which
   is stated per waiting call at the acquire (`context/design/design.md:672-686`), restated at
   the device; the per-request figure is reported, not promised tighter (finding 10).
3. **Fairness cursor.** One persistent round-robin cursor over lanes, and within a lane over
   clients, applies to **every** selection class: foreground candidates, aged background
   candidates when the exception fires, and background candidates when no foreground is
   pending. Two continuously aged background lanes therefore alternate exceptions.
4. Background service follows from finite admission (4.5) draining the passed set.
5. `K` defaults to the number of composed GPU roles; `K = 1` is a measured switch. A seat grant
   and its growth ticket (5.3) succeed atomically or the candidate is not seated; refusal at the
   ledger degrades that sub-batch to the CPU lane if the role has a CPU session, else it waits.
6. Cancellation of a queued sub-batch is immediate; a running one completes.

Required deterministic traces: `K = 1` and `K = 2` with three runnable lanes, saturating
foreground in two lanes, **two** aged background lanes (fairness), a foreground request with
two sub-batches re-entering the queue (the per-request figure), the passed set draining and
aging resuming, cancellation mid-queue, producer refusal.

### 5.3 The device ledger

**Allocation classes** per session: `weights` (bypass the arena, `SessionOptionsApplier.java:73`),
`arena` (bounded by `gpu_mem_limit`, `:99`; shrinks between calls under `arenaShrinkage`, `:119-123`),
`workspace` (cuDNN, outside both), `growth` (arena expansion during a run). NVML reports only
device totals (`NvmlService.java:177-190`); per-class figures are attributed by the host from
before-and-after readings inside a controlled window and are labelled `attributed`, never
`exact`. Anything the host cannot attribute is `unknown` and is charged conservatively.

**Ticket state machine.** Every allocation is preceded by a ticket:

```
RESERVED ──spawn/create authorised──> LAUNCH_PENDING ──OS identity bound──> BOUND
   │                                        │                                 │
   └── refused/withdrawn ──> RELEASED       └── outcome unknown: RETAINED      └──> RESIDENT ──retire──> RETIRING ──confirmed freed──> RELEASED
```

- `RESERVED`: bytes charged against `available`; no allocation may begin without one.
- `LAUNCH_PENDING`: the ticket is **persisted** with a launch-intent id before any spawn or
  session creation (finding 2). For llama-server the Engine writes the intent into its manifest's
  private projection (`launchIntents: [{intentId, ticketId, kind, declaredConfigHash}]`) before
  `pb.start()` (`LlamaServerOps.java:1255-1275` starts before registering today); the child
  record written at registration links back to the intent id.
- `BOUND`: the allocation is attributed to an OS identity (child PID plus start time, or the
  host's own session id). A `LAUNCH_PENDING` ticket whose owner crashes before binding is
  `RETAINED`: it keeps its charge and blocks conflicting admission until the next reconciliation
  finds either a live process matching the intent (then `BOUND`) or confirmed absence by a fresh
  device read and a registry scan (then `RELEASED`). Absence is never inferred from a missing
  registration alone.
- `RESIDENT`: reconciled against observed usage (below).
- `RETIRING`/`RELEASED`: released only after a fresh read confirms the memory is gone.

**Conservation rules** (finding 1). A session's entitlement is `weights + arena + workspace`. On
first load the whole entitlement is `RESERVED`. After creation and warm-up, `weights` and the
observed steady arena move to `RESIDENT`; the *remaining* arena entitlement (cap minus steady)
and the `workspace` allowance are retained as the session's **run entitlement**, not dropped.
Seating a sub-batch transfers the run entitlement into a per-seat growth ticket **atomically with
the seat grant**; the same bytes are never charged twice (the session's retained entitlement is
zero while its seat holds it) and never dropped because warm-up ended. At run end the seat's
growth ticket returns to the session's run entitlement; observed arena growth that persists is
moved to `RESIDENT` and the run entitlement shrinks accordingly. Two seats at `K = 2` therefore
each carry arena remainder **and** workspace.

**Admission.** `available = total − external − Σ resident − Σ reserved(all non-released tickets) − margin`.
`external` is NVML `used` minus everything the ledger attributes to itself **including
in-flight materialisation**: a `LAUNCH_PENDING` or `BOUND`-but-unreconciled ticket's observed
partial usage is attributed to that ticket, not to `external` (finding 9). One authority (one
lock) covers grant, materialisation, reconciliation, observation epoch and release; a device
reading is taken inside the lock and tagged with an epoch, and attribution uses only readings of
the same epoch. Required traces: two grants against one reading; a grant **during** another
load; a failed launch's ticket released only after confirmation.

**Observation tightens; one enforced use.** The footprint store (`<dataDir>/inference-host/
footprints.v1.json`) keys on `(descriptor digest, execution variant, EP, ORT version, device id,
allocator policy, batch profile)` and records attributed weights and steady and peak arena over
a controlled window. Observed weights replace the estimate; the observed arena peak is advisory,
except that policy may set the role's `gpu_mem_limit` to the observed peak plus a growth
allowance on later loads, at which point the entitlement shrinks because the enforced bound did.
The design claims a conservative reservation discipline, not a bound on native usage; under-
estimation still surfaces as ORT failure and CPU fallback, reported.

### 5.4 Tenants and eviction

**Reserve before launch.** Every GPU-capable llama activation, including the install self-test
(`app-services/.../ai/runtime/RuntimeActivationService.java`, the implementation, not the
`app-api` interface of the same name), crash-recovery restarts and context-rung relaunches,
takes a `LAUNCH_PENDING` ticket before spawn; the host evicts or refuses to make it grantable;
the Engine launches; the ticket binds to the child's OS identity at registration and is
reconciled when `/health` answers. A retry or rung relaunch first settles its predecessor
attempt (`RELEASED` or `RETAINED`) before a new authorisation. `release` follows
`stopLlamaServer` and the flush (`InferenceLifecycleManager.java:551-553`), which closes the
falling-edge race. A registered live llama child that survives `RESTART`/`HANG` keeps its ticket
across the Engine restart; the successor Engine adopts both the child (lane F 7.2) and the
ticket. **Single generative tenancy** is an explicit rule independent of bytes.

**Eviction order** (finding 13). Candidates are classified by **serving-set obligation**: a role
belongs to a serving set and is *query-serving* if the serving generation's search plan uses it
(embedding or BGE-M3 for the dense leg, SPLADE or BGE-M3 for the sparse leg, reranker, citation:
`SearchInputCapture.java:151-184, 321-339`), *index-only* otherwise (NER; SPLADE or the embedder
only for a candidate set with no serving obligation). Busy versus idle is read from D1-13's lease
count, never from producer tokens. Order: idle index-only sessions, then idle query-serving
sessions largest first, then leased sessions drained (D1-13) before release. Foreground eviction
is a stated degradation gated by a **retrieval-coverage row** (dense leg, sparse leg and rerank
availability, candidate coverage, skipped results and deadline success during chat).

### 5.5 Mismatch fallback (finding 6)

An Engine whose host is incompatible (H2: a config mismatch it cannot resolve by respawn under
its own ownership; H2b: a shared host of another version) falls back to the in-process adapter
**in CPU-only mode**: GPU providers disabled, lazy GPU retry disabled, the ledger not consulted;
or reports `encoders: UNAVAILABLE`. The same rule binds `bench` and `verification` compositions
that could coexist with a device owned by another process. No second GPU allocator ever runs
outside the ledger's authority.

### 5.6 Fault surface and caches

As v2: H1 keeps the Engine's fault domain; H2 makes a native fault a child crash under a
three-crash budget with text search serving; the host-crash gate row is a process-loss test.
H0 moves the `.optimized` caches (EP-namespaced, `OnnxSessionCache.java:29-39`) and the ORT temp
directory under host ownership and makes shared model files read-only for other processes.

## 6. Readiness, binding, reconfigure

### 6.1 Readiness

The `encoders` component projects `status()`; D1-12's per-set latch replaces the six-role latch;
`EncoderBindings` (`:24-27`) stays as the Engine-side null-tolerant projection in H1. The MMR
loop (`RagContextOps.java:1455`) is batched as its own gated change.

### 6.2 Binding: routing, reads, writes, replay

- **Routing.** The Engine resolves the set per call from D1-12's serving or candidate set;
  replay resolves its fixed `building_generation_id` (D1-9) to that generation's set.
- **Reads.** Compare the result's descriptor against the **pinned searcher's** generation
  metadata (D1-3 makes identity per runtime); mismatch refuses `GENERATION_MODEL_MISMATCH`;
  dimension mismatch is a reasoned refusal, not an uncaught Lucene exception
  (`ReadPathOps.java:285-298`). Legacy-metadata policy per 4.3.
- **Writes** (finding 8). Every derived-representation write is conditional at the boundary that
  applies the field and its completion marker on the triple `(destination generation,
  descriptor, source revision)`, where the source revision is the content hash the encoding was
  selected against (`jobs.content_hash`, C2 V15, and the indexed source hash D1-9 compares at
  activation). The combined RMW (`CombinedEnrichmentBackfillOps.java:969-987, 1049-1061,
  1200-1211`) carries per-field descriptors rather than stamping one current set over mixed
  outputs, and refuses a field whose source revision no longer matches the document's current
  accepted revision. This is a conditional write, not an inference from producer cardinality.
- **Partial accumulators.** `WindowedEmbedProgress` state (`CombinedEnrichmentBackfillOps.java:
  854-899`, `BackfillScheduler.java:121-127`) is bound to `(destination generation, descriptor,
  source revision, window geometry)` and discarded at any incompatible transition; a final vector
  is written only if every contributing window carried the same binding.
- **Two acknowledgements, named apart.** *Source-command accepted durably*: an UPSERT replay is
  enqueued into the job queue and the switch buffer cleared (`KnowledgeServerMigrationOps.java`,
  the drain at `:427`; enqueue then `clearSwitchBuffer`), which is D1-9's replay semantics and is
  unchanged. *Derived representation applied*: the conditional write above, at the eventual
  target-generation job. "Before acknowledging application" in v2 meant the second; the first is
  never a statement about encoding.
- The query-embedding cache is keyed by descriptor digest plus normalised text.

### 6.3 Reconfigure

D1-14's outward contract (beside iff it fits, in place otherwise, A recomposed on refusal,
result and reasons reported) is preserved. H1 **replaces its admission implementation**: the
free-byte comparison becomes a ticket request to the ledger (with eviction under 5.4's order),
and the mode is the ticket's outcome. D1 implements D1-14 against its own free/total supplier as
written; H1 owns the replacement.

## 7. The unshared managed child (H2)

### 7.1 Kind, launch, identity

`Kind.ENCODER_HOST`; a JVM with `modules/inference-host`'s main; flags pinned by the exact-set
test. The declared config hash covers **host version, protocol version, models directory, device
policy and fixed flags only**, never set specs (finding 11); computed by a hash helper lifted
into `app-api` (retiring `PersistentExtractionSandbox.hashArgv`, `:601-610`). The launch follows
5.3: intent persisted, spawn, register with the intent link.

### 7.2 Ownership and lifecycle

Ordinary owned child under lane F 7.2: registered in the Engine's manifest, adopted across
`RESTART` and `HANG` by a per-kind probe (`/status` with token, config hash, identity, second
re-check as `LlamaServerOps.java:1002-1026`), stopped on `QUIT` and `UPGRADE`
(`stopsGenerativeBackend()` becomes `stopsChild(kind)`), killed by the supervisors' terminal
path by identity. Self-assigned Job Object lifted from the parser child. **Parent-watch is
replaced by idle exit**: with no client for ten minutes the host enters `DRAINING` under its
client lock; an adoption probe that finds `DRAINING` is refused `HOST_DRAINING` and the Engine
spawns afresh. The client is a lease with heartbeats; an Engine restart is a client boot-id
change: predecessor submissions terminate on their own, predecessor records retire after
termination, and the successor is admitted immediately because in the unshared case there is
one Engine and its predecessor is dead by construction (the supervisor restarted it). The
replacement barrier of the shared case is H2b's.

### 7.3 Transport and trust

As v2: loopback HTTP/1.1 on an ephemeral `127.0.0.1` port; JSON control with UTF-8 texts;
binary response frames on the `SandboxFrames` precedent (`:23-75`) with a JSON header then
little-endian arrays; request UUID echoed and validated; schema version refused when unknown;
64 MiB frame ceiling; endpoints for sets, operations, `outcome`, `ack`, `cancel`, producer,
reserve, release, capabilities, status, `/health`. A per-boot host token minted by the Engine
with the API token's generator (`LocalApiServer.java:974-978`), passed through the environment,
stored in the private manifest projection (`RuntimeManifest.java:324-347`), with projection tests
for the new fields.

## 8. Development shape

H0 gives every developer Engine its own data directory, manifest and ports for jseval
(`backend.py:22`, `_paths.py:131`), identity-scoped cleanup (`dev-runner.cjs:3140-3161`,
`fixture-pair.sh`), read-only shared model files, host-owned caches and temp directories, RSS
attribution, lite mode as D2-2's `verification` profile, and an **encoders-off coexistence
profile**: any number of Engines with `inference: none` beside at most one Engine that owns the
device. Until H2b, that is the multi-Engine shape. The GPU lease stays with the dev-runner's
stack as today.

## 9. What is lost

| loss | when | mitigation |
|---|---|---|
| SPLADE's zero-copy pinned output across the boundary | H2 | the buffer stays in the host |
| one loopback hop per call | H2 | budget 1 ms p50, measured |
| a shared host for N Engines | deferred to H2b | encoders-off coexistence; the measured need decides H2b |
| a hard bound on native memory | not claimed | conservative reservation with enforced arena caps; under-estimation reported |
| cross-role concurrency at `K = 1` | measured choice | default `K` reproduces today |
| a second JVM entry point | H2 | same runtime image; the Engine drops `onnxruntime_gpu` |

## 10. Decisions

1. One port with `EncoderSetHandle`, `SubmissionId`, `ProducerToken`; `WINDOWS(0,0)` tokenizer-
   only; descriptor and execution identity on results; timings outside the exact boundary.
2. Two implementations in order: in-process adapter (H1) then the **unshared** child (H2);
   port-first is a risk-reduction order. **H2 is a decision gate, not a committed stage:** with
   the shared host deferred, the child buys only native-fault containment (no fault observed in
   183 development runs) and warm restart (baseline unmeasured). H2 proceeds only if H0's
   encoder-warm milestone or soak evidence shows a cost the in-process adapter cannot carry;
   the decision is recorded with those numbers. H1 stands on its own: the ledger, the binding
   and the contract cut fix defects the current Engine has today.
3. `modules/inference-host` is the only ORT and DJL importer.
4. Device grants atomic; the passed bound is per native wait, with the per-request figure
   reported; one fairness cursor across all selection classes; `K` defaults to today.
5. The ledger: allocation classes, a ticket state machine with a persisted launch intent before
   any spawn, `RETAINED` for unknown outcomes, conservation of the run entitlement into per-seat
   growth tickets, one authority with epoch-tagged readings, in-flight materialisation attributed
   to its ticket.
6. llama-server reserves before every launch including the self-test and rung relaunches;
   tickets survive Engine restart with the adopted child; single generative tenancy is a rule.
7. Eviction by serving-set obligation using lease counts, gated by a retrieval-coverage row.
8. Mismatch fallback is CPU-only or `UNAVAILABLE`.
9. Sets are immutable, reference-counted, acquired and released; `reload` returns a new handle;
   set specs are never part of the process hash.
10. Per-submission terminal records retrieved idempotently and retired by acknowledgement under
    bounds; `cancel(workId)` is a watermark fence; Engine retention releases only on
    `TERMINATED`, `REFUSED` or confirmed host death.
11. Representation descriptor as in 4.3 with `unknown` versus `known-absent` and a refusal rule
    for representation-bound changes against unverifiable generations.
12. Derived writes conditional on `(generation, descriptor, source revision)`; accumulators
    bound and discarded on transition; the two acknowledgements named apart.
13. The exact-comparison boundary excludes placement-dependent metadata (section 11).
14. Cross-lane: *D1 proceeds against its documented local `EncoderSet`, runtime identity and
    retirement contracts, without new inference-port types. H1 adapts those completed mechanisms
    after batch 4 and owns any operation-port or device-admission changes. D1 is not blocked by
    agreement or implementation of the H1/H2 protocol.* D2 is likewise not blocked by H0 or H1.
15. The shared host is H2b, open (section 13); a host owning llama-server is the reassess trigger.

## 11. Gate and measurement

**Exactness boundary.** *Contract exactness*: a captured result payload (identities, shapes,
outcomes, item order, numeric bytes, integer ids) plus fixed contract metadata serialises and
round-trips byte-identically through both placements; timings and transport ids are excluded or
supplied identically by the fixture. *Numeric*: per-role acceptance rules (epsilon for dense,
discrete equality for sparse ids with epsilon on weights, tie rules for rerank order) declared
before capture under pinned execution shape. *Workflow*: the existing fixture, classes and noise
mask, whose masked hit lists remain outside the verdict. *Policy changes* (MMR batching, `K`,
eviction) measured as separate rows.

**H1 rows** (against the current head, paired, same corpus and machine): index-time throughput;
request-time encoders with new per-call query-embed timing; the three exactness layers;
memory; encoder-warm milestone per set; ledger traces (two grants against one reading; a grant
during another load; failed launch released only after confirmation; entitlement conservation
across load, seat and release at `K = 2`; a `RETAINED` ticket after a simulated crash between
intent and registration, then reconciled both ways); retrieval coverage during chat; binding
(read refusal; write refusal on wrong set; write refusal on a stale source revision; accumulator
discarded on transition; Flow B: search on A while B writes Green; legacy generation served
`partial` and its representation-bound change refused); scheduler traces of 5.2; submissions
(two calls sharing a work id released independently; lost response recovered by `outcome`;
backlog refusal; fence watermark; cancel-before-enqueue; disconnect-during-run; host death).

**H2 rows** (against H1): per-call overhead; warm restart with adoption of both the child and
the llama ticket; host crash under budget; adoption versus `DRAINING`; `QUIT`/`UPGRADE` stop and
`RESTART`/`HANG` leave the host; token absent from the public projection; mismatch fallback is
CPU-only (no GPU allocation observed from the Engine while the host holds the device).

## 12. Sequencing

| stage | contents | proof |
|---|---|---|
| **H0** | encoder-warm milestone; co-resident VRAM with llama resident; concurrency at `K` = roles and 1; migration-window gap probe; host-owned caches and temp dirs; read-only shared models; jseval per-run isolation; identity-scoped cleanup; RSS attribution; lite mode as `verification`; encoders-off coexistence | numbers under `evidence/H0/`; two encoders-off Engines beside one device owner |
| **H1** (after D1 batch 4) | port; host module with D1-12/13 relocated; scheduler; ledger, intents, footprints; llama reserve-before-launch; eviction; readiness; descriptor and binding (reads, writes, accumulators); submissions and fences; consumers migrated; MMR batched separately; ArchUnit pins | the H1 rows; full suite; `engine-port` gate |
| **H2** (decision gate after H0 and H1; decision 2) | unshared child: kind, main, transport, token, per-kind probe, `stopsChild`, Job Object, idle exit, crash budget, adoption of child and ticket | a recorded go decision citing H0's encoder-warm and fault evidence; then the H2 rows; supervisor conformance; hosted CI |
| **H2b** (open) | shared host: section 13 | not scheduled until its ownership protocol is designed and a measured need exists |
| **H3** | WebGPU EP inside the host; ORT bump on its own PR | 903 section 2.3 on non-NVIDIA hardware |

## 13. H2b, the shared host: what is open

Round 2's findings 2, 4, 5, 11 and 12 are the specification H2b must meet before it is designed:
a host-lifetime device exclusion (a host-held per-user, per-device lock, not the supervisor's
lease); one persisted supervisor ownership record with boot and process identity, recovered by a
successor before it may admit launches, with unknown identity failing closed; a stable logical
Engine identity with process incarnation so a replacement attachment is admitted but held
non-runnable until predecessor execution exits or host death is confirmed; renewal, expiry and
fencing linearised under one authority with revision-validated expiry; llama tickets transferred
across attachment epochs; attachment-scoped record and fence bounds. The trigger for scheduling
H2b is a measured need that encoders-off coexistence does not meet.

## 14. Alternatives not chosen

As v2, plus: a shared host in H2 (its ownership protocol is larger than the rest of the lane and
its benefit is mostly available in H0); a work-id-scoped completion (rejected by C1); a
full-GPU fallback on mismatch (a second allocator); per-UUID fence history (unbounded); set specs
in the process hash; a per-request passed bound tighter than per native wait (not provable
without preemption).

## 15. Open questions

1. The `K` default and cross-role concurrency effect (H0).
2. The encoder-warm milestone (H0).
3. Whether the migration-window gap fires live (H0).
4. The observation window that makes an arena-peak profile representative.
5. Whether a real CUDA fault is ever catchable on ORT 1.24.3.
6. Multi-GPU (the ledger is per device; `NvmlService` reads device 0, `:180`).
7. H2b's ownership protocol (section 13).
