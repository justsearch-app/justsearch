# `modules/indexer-worker` — the index half of the Engine

This module is the **index half**: Lucene, the job queue, the indexing loop, the encoders that feed
them, and the `KnowledgeServer` that owns their lifecycle. It is a **library**, not a process.
Since lane F stage A (tempdoc 936) it is composed *inside* the Engine JVM by
`io.justsearch.app.engine.EngineRoot`, and the application half reaches it through **ports** rather
than a wire.

The governing decision is [ADR-0049](../../docs/decisions/0049-one-engine-jvm-and-the-boundaries-that-survive.md),
which supersedes ADR-0001 (three OS processes) and ADR-0002 (gRPC + MMF hybrid IPC). Read it before
changing anything structural here.

## 1. What is *not* here any more

`modules/indexer-worker/build.gradle.kts` says it plainly: the `application` plugin, `mainClass`,
the `distributions` block and the `runWorkerStandalone` task all went with `IndexerWorker.main`, and
the jar carries no `Main-Class`. There is no second process to spawn and no second distribution to
ship. So the following, which older docs and tempdocs describe at length, are **gone** — do not
reintroduce them and do not reason from them:

- **The worker child process and `WorkerSpawner`** (item A11).
- **The gRPC server, its interceptors and the ephemeral port handoff** (item A9); then the rest of
  gRPC — the `service` blocks, the infra-health service, and the `protoc-gen-grpc-java` generator
  (item A14). What survives of `indexing.proto` is its *message* half, used as DTOs at the ports.
- **The memory-mapped signal bus** and both its ends (item A10).
- **The suicide pact / heartbeat.** In one JVM there is no heartbeat to miss, so self-termination on
  a stale beat could only ever be a false positive
  (`server/KnowledgeServer.java`, `startSentinelThread`).
- **The Head→Worker config-snapshot tier** at ordinal 450 (item A19). One `ResolvedConfig` in one
  JVM cannot disagree with itself.
- **The module's own `logback.xml` and the second `worker.log`** (items A13/A16).

The one way to run the index half is to run the Engine: `./gradlew.bat :modules:ui:runHeadless`, or
the dev-runner.

## 2. Where the code actually lives

The name `indexer-worker` covers three Gradle modules; only the first is this one.

| Module | Packages | What it holds |
|---|---|---|
| `modules/indexer-worker` | `server`, `server/ops`, `queue`, `embed`, `recovery`, `util` | `KnowledgeServer` (lifecycle + composition), `SqliteJobQueue`, index recovery, `IndexRootLock` |
| `modules/worker-services` | `services`, `loop`, `loop/pacing`, `extract`, `server` | `WorkerSearchService`, `WorkerIngestService`, `WorkerHealthService`, `IndexingLoop`, `IndexingPacing`, `ContentExtractor` + the extraction sandbox, `WorkerAppServices` / `DefaultWorkerAppServices` |
| `modules/worker-core` | `embed`, `embed/onnx`, `splade`, `bgem3`, `ner`, `ort`, `coordination`, `ingest`, `identity`, `index`, `liveness`, `queue`, and more | `EmbeddingService`, the ONNX encoders, `WorkerSignalBus`, `WorkerConfig` |

Lucene itself is only ever touched through `modules/adapters-lucene`.

## 3. Critical invariants (the safety rails)

**Application code never touches Lucene — index I/O goes through a port.** This is hard invariant 1,
and it survived the merge verbatim. Sharing an address space does not license a controller to open
an index. It is pinned from two sides, both in `modules/app-launcher/src/test/`:

- `IndexWriterOwnershipTest` keeps Lucene imports inside the owner packages.
- `LayeringEnforcementTest` rule 6b (`onlyEngineAndWorkerMayDependOnWorkerInternals`) lets only
  `io.justsearch.app.engine..`, `io.justsearch.indexerworker..` and `io.justsearch.adapters..`
  depend on `io.justsearch.indexerworker.{server,services,loop}..`. Everything else — the API front,
  orchestration, the launcher — reaches the index through a port.

The port catalogue is `governance/engine-ports.v1.json`; the composition root that binds every port
is `modules/app-engine/.../EngineRoot.java`. Adding a port is a catalogue entry, an interface in a
contract module (`core` or `app-api`), and a binding in the root.

**The four operation contracts survived the channel.** Deadlines, per-call result-size bounds,
streaming flow control and cancellation were requirements of the *work*, not of the network, so they
are re-homed onto the port calls (`KnowledgeClient` / `EngineKnowledgeClient`) rather than deleted
with the transport. ADR-0049 names this the part of the merge most likely to be got wrong, because a
direct method call *looks* like it needs none of them.

**Lucene write access.** The `IndexWriter` is not safe for concurrent commits from multiple
components; the `IndexingLoop` owns it. No other service writes to the index directly.

**Job queue locking.** `SqliteJobQueue` holds a `ReentrantLock`
(`queue/SqliteJobQueue.java:86`) to serialize access to the SQLite database, preventing
"database locked" errors when ingestion (writes) and polling (reads) contend.

**Native memory lifecycle.** `EmbeddingService` and the other ORT-backed encoders hold off-heap
memory that must be closed explicitly (try-with-resources or the shutdown hook).

**GPU is shared, not partitioned.** The chat runtime (`llama-server`) and this half's ONNX encoders
both want VRAM, and they now sit in one JVM's field of view rather than two processes'. The
coordination is `io.justsearch.core.scheduling.GpuSchedulingGauge` — two volatile booleans,
`mainGpuActive` and `energyReduced`, plus `shouldYield`. When the gauge flips to "main claimed the
GPU", the sentinel calls `WorkerAppServices.onMainClaimedGpu()`, which reaches
`WorkerSearchService` → `RagContextOps` and releases the chunk reranker's VRAM.

## 4. Lifecycle: what `KnowledgeServer.start()` does

`start()` is a phased sequence, timed and logged (`Startup phases (ms): telemetry=…, signalBus=…,
jobQueue=…, lucene=…, init=…, loop=…, total=…`). In order:

0. **Telemetry** — a `LocalTelemetry` for the index half's metric catalogs, writing
   `metrics-worker.ndjson`. Then tracing, before service classes load.
1. **Signal bus** — `InProcessWorkerSignalBus` over a `GpuSchedulingGauge`. The Engine's composition
   root **injects** the bus so the Head-side writers and this reader share ONE gauge. The no-arg
   fallback builds a gauge nobody else writes, which is the honest answer for an index half composed
   on its own (tests, a standalone boot): no GPU claim, no energy signal, no yielding.
2. **Job queue** — `SqliteJobQueue` over `jobs.db`, with corruption triage.
3. **Index path** — the generation-scoped path is resolved *before* Lucene opens.
4. **Lucene runtimes** — `LuceneRuntimeBuilder`; may be `RunningRuntime`, `ReadOnlyRuntime` or
   `DeferredRuntime`.
5. **Application services** — `WorkerAppServices`, composed via the registry. Models are wired later.
6. **Embedding compatibility** — resolved before anything can commit.
7. **Indexing loop** — started immediately, null-gating embedding/SPLADE until they exist. Skipped
   when the rebuild brake is exhausted: the loop's job is to write into a runtime that is READ-ONLY
   in that state, so starting it would only turn every queued job into an exception. Search still
   serves, and `/api/status` reports the reason as `indexSchemaCompatState = BLOCKED_REBUILD_BRAKE`
   (`IndexStatusOps.java:1212` produces it, `StatusLifecycleHandler.java:1459` reads it). Recovery is
   an operator-initiated rebuild, which clears the brake at promotion.
8. **Sentinel thread** — see below.

Then the log line to look for: **`KnowledgeServer started successfully (in-process; no port)`**.

**Deferred model init** runs on a background thread *after* `start()` returns, so the ports answer
while models load. Callers are null-safe: search degrades to BM25, the loop skips embedding/SPLADE,
ingest queues normally. Query handlers await `modelReadyLatch` (120 s) so a query arriving mid-init
does not silently miss reranker + citation wiring.

**Failure surfacing.** A corrupt index or a schema mismatch stamps a `WorkerFatalReasonMarker` so
the API front can offer "Rebuild index" instead of blind-restarting.

## 5. The sentinel thread (what it still does)

`startSentinelThread()` runs a 1-second daemon loop, `knowledge-server-sentinel`. It has **no
liveness duty** — the suicide-pact arm went with the memory-mapped bus, and shutdown is now
exclusively the ordered in-process sequence the composition root drives. What remains:

- **Dev hot-reload** — polls `signalBus.isReloadRequested()` (a request *file* under
  `<dataDir>/runtime/`, not an MMF byte) and calls `DevReloadManager.performReload()`.
- **GPU lifecycle** — on a rising edge of `isMainGpuActive()`, releases reranker VRAM.
- **Periodic job-queue cleanup** — removes old DONE/FAILED rows and old ledger events.

## 6. Threads, pacing and blocking

- **Indexing loop** runs on a low-priority daemon thread, `indexing-loop`.
- **Foreground duty cycle** — after each batch (and each extracted file) the loop calls
  `IndexingPacing.pace()`. While user-waiting calls are in flight it yields enough wall time to hold
  indexing at `justsearch.indexing.foreground_duty_pct` (default 20%), reported as PAUSED for the
  duration of the yield only. Indexing is throttled, never stopped.
- **Who feeds the gauge.** `ForegroundLoad` counts user-waiting calls. Its producer used to be a
  gRPC `ServerInterceptor`; since item A9 deleted that, the sole producer is
  `io.justsearch.app.engine.ForegroundLoadGate`, wired in the composition root. It must feed the
  *same* instance `KnowledgeServer` owns — read it via `KnowledgeServer.foregroundLoad()`, never via
  `IndexingPacing.foregroundLoad()`, which before `start()` answers from the `unthrottled()`
  placeholder and hands back a fresh gauge nothing paces off.
- **Blocking.** There is no event loop to protect any more, but the shape still matters: ingest
  offloads persistence to the job queue and returns an accepted count immediately; search delegates
  to the Lucene runtime and the encoders. A port call is a direct call on the *caller's* thread, so
  a long block here is a block in the caller.
- **Trace context** is current on the callee's thread by construction rather than by header
  propagation — with one exception worth naming: a thread hand-off still loses it, so an executor
  that fans out must wrap its tasks.

## 7. Hot paths

**Ingestion (batch).** A caller reaches `IndexingService` (the port) → `WorkerIngestService`.
Paths are sanitized (traversal + existence checks), `JobQueue.enqueue()` takes the lock and writes
PENDING rows, and the accepted count returns immediately.

**The indexing pipeline.** `IndexingLoop` wakes (IDLE → RUNNING) and:
1. `JobQueue.pollPending()` returns a batch.
2. `ContentExtractor.extract()` parses text (Tika, PDF/Office/OCR). **This runs in a child
   process**, not here — the extraction sandbox pool
   ([ADR-0048](../../docs/decisions/0048-extraction-isolation-and-indexing-pacing.md)), which is
   one of the two process boundaries ADR-0049 keeps, and which the merge makes *more* valuable, not
   less: parsing untrusted files is the one place a native fault is expected, and a crash in the
   Engine is now a crash of everything.
3. Embedding: tokenize → overlapping chunks (sliding window) → vectors.
4. The Lucene runtime writes the document. Vectors use `JustSearchCodec`
   (`modules/adapters-lucene/.../runtime/JustSearchCodec.java`), an Int8 scalar-quantized HNSW
   format that cuts vector footprint ~75% against Float32.
5. `JobQueue.markDone()` updates SQLite; commits are periodic.

**Search.** A caller reaches `SearchPort` → `WorkerSearchService`, whose pipeline modes are TEXT
(BM25), VECTOR (KNN), HYBRID (BM25 + KNN fused with RRF) and SPLADE. Query embedding, Lucene
retrieval and fusion all happen on the calling thread's budget.

## 8. Developer task map

| Goal | Edit |
|---|---|
| Add support for a new file extension | `worker-services/.../extract/ContentExtractor.java` — register a parser or adjust Tika config |
| Change embedding model / dimensions | `SSOT/catalogs/fields.v1.json` (`vector.dimension`). Loaded via `JustSearchConfigurationLoader.loadFieldCatalog().vectorDimension()` and injected. Never hardcode dimensions — see `modules/configuration/README.md` |
| Tune the foreground duty cycle | Prefer configuration: `justsearch.indexing.foreground_duty_pct` / `justsearch.indexing.foreground_cooldown_ms`. Policy lives in `worker-services/.../loop/pacing/IndexingPacing.java` |
| Modify SQLite schema / queue logic | `queue/SqliteJobQueue.java` — `initSchema()` and the SQL |
| Add a new indexed field | `worker-services/.../loop/IndexingLoop.java` (document building) **and** the SSOT schema definition |
| Expose a new capability to the application half | A **port**, not a method on a concrete service: interface in `core` or `app-api`, entry in `governance/engine-ports.v1.json`, binding in `EngineRoot` |
| Debug inference / native-runtime crashes | `modules/app-inference` and `modules/gpu-bridge` first. Check `llama-server` lifecycle logs, runtime-mode transitions, GPU/VRAM detection |

## 9. Verification and diagnostics

**Health.** `WorkerHealthService` (`worker-services/.../services/WorkerHealthService.java`) provides
deep health — serving state, queue depth, discovered/active models. It is reached through
`WorkerAppServices.healthService()`, which the composition root wraps as `WorkerHealthCalls` for
`EngineKnowledgeClient` (`modules/app-engine/.../EngineKnowledgeClient.java:348`); the HTTP surface
over it is `GET /api/knowledge/status`. There is no gRPC `Health/Check` to call.

**Logs.** One file: `<justsearch.data.dir>/logs/engine.log` (`modules/ui/src/main/resources/logback.xml`
— item A13 deleted this module's own logback config). Everything goes to the file appender; only
`io.justsearch.ui.automation` also goes to stdout, for the smoke harness.

| Pattern | Meaning |
|---|---|
| `KnowledgeServer started successfully (in-process; no port)` | Index half is up |
| `Startup phases (ms): …` | Per-phase boot timing — the first place to look at a slow boot |
| `Ingestion is STOPPED: the automatic-rebuild budget …` | Rebuild brake exhausted; search still serves read-only |
| `Failed to start KnowledgeServer` | Boot failed; a `WorkerFatalReasonMarker` may name the cause |
| `Sentinel detected reload signal` | Dev hot-reload is about to run |

Per-file ingestion outcomes are not a log grep — they are reason codes on the ingestion outcome
journal (`worker-services/.../loop/IngestionOutcomeJournal.java`, e.g. `"Indexed successfully"`),
surfaced through the indexing-jobs API.

**Metrics.** `OperationalMetrics` plus the typed catalogs registered at boot
(`IndexRuntimeMetricCatalog`, `IndexingPipelineMetricCatalog`, `ExtractionMetricCatalog`,
`OcrMetricCatalog`, `WorkerOpsMetricCatalog`, `IngestionOutcomeMetricCatalog`,
`WorkerWatcherMetricCatalog`, `ort.session.*`) → `metrics-worker.ndjson`.

**Durability.** Kill the Engine while jobs are PROCESSING and restart:
`JobQueue.recoverStuckJobs()` moves them back to PENDING. Note the honest cost ADR-0049 records —
there is no longer a second address space, so an OOM in indexing takes the API with it. Supervision
is stage B's work, and until it lands the mitigation is admission control and per-operation budgets,
not a restart budget.
