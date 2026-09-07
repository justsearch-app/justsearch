---
title: "Lane F stage A — spine and unplug: implementation checklist"
stage: A
created: 2026-09-07
base: fe19df0d5
status: "IN PROGRESS (stage started 2026-09-07 on worktree-lane-F-A from the PR 0 head; A1-A3 landed; corrections in section 0.1)"
---

# Lane F stage A — spine and unplug: implementation checklist

Written from the three inputs 17.6 requires: the stage's row in design.md 17.3,
`verified-facts.md`, and a fresh read of the code at this worktree's HEAD
(`fe19df0d5` = `worktree-lane-F`, which already carries PR 0's launch flags and the
design contract; `main` is `76871d924`). Section numbers are design.md's.

Every `file:line` below was opened at `fe19df0d5` during this pass. Nothing is cited
from 917 or `verified-facts.md` without re-opening the file.

---

## 0. Corrections to inherited facts (apply to `verified-facts.md` before relying on them)

17.6 requires a fact that no longer holds to be corrected in `verified-facts.md` with its
new citation *before* the checklist relies on it. Five corrections fall out of this pass:

1. **`adr-0002-grpc-present` is scoped to `.kts`, not to Java.** `governance/adr-probes.v1.json:37-48`
   is `grep-present` on `libs\.grpc|io\.grpc` with `"paths": ["modules"]` **and `"include": [".kts"]`**;
   `verified-facts.md` records only the pattern and the path, which reads as "any file under modules".
   Consequence: the probe reds only when the grpc **dependency declarations** leave the build files —
   deleting every Java `io.grpc` import while leaving `libs.grpc.stub` in `modules/*/build.gradle.kts`
   leaves it **green over a deleted wire**. 17.4's "red the moment the wire is deleted" is therefore
   conditional on item A14.
2. **The `RemoteKnowledgeClient` construction site moved.** 917 §1 cites
   `KnowledgeServerBootstrap.java:261`; at this base it is `:291`, with `MainSignalBus` at `:253` and
   `WorkerSpawner` at `:256`, inside `start()` at `:213`.
3. **`WorkerProcessManager` has 21 test consumers, not 20.** `git grep -l WorkerProcessManager --
   'modules/**/*.java'` returns 22 files; 917 §5 lists 20 and omits
   `modules/system-tests/src/systemTest/java/io/justsearch/systemtests/process/FormatCapabilityMatrixE2ETest.java`.
4. **Two distinct `GrpcCircuitBreaker` classes exist** (each with its own test):
   `modules/ipc-common/src/main/java/io/justsearch/ipc/grpc/GrpcCircuitBreaker.java` and
   `modules/app-services/src/main/java/io/justsearch/app/services/worker/GrpcCircuitBreaker.java`.
   Neither 917 nor the design names the duplication; the inventory below counts both.
5. **`check-readiness-reason-codes` has a PRODUCER direction stage A will break.**
   `scripts/ci/check-readiness-reason-codes.mjs:22-25` requires every `LifecycleReasonCode` member to
   be referenced by at least one Java source under a `modules/<module>/src/main` tree outside the
   enum's own file. `LifecycleReasonCode.java:19-69` declares 18 `WORKER_*` members, several
   referenced only from classes this stage deletes (`KnowledgeServerBootstrap`, `WorkerSpawner`,
   `KnowledgeServerHealthMonitor`, `RemoteKnowledgeClient`). Design §6 places the readiness re-cut at
   D1, so **stage A owes a holding action** (A17), not a re-cut. Neither 917 §7 nor design §6 names
   this gate for stage A.

---

### 0.1 Corrections found while implementing (appended per item)

- **A1.** Adding a module also requires its `modules/app-engine/gradle.lockfile` (every module has one;
  `resolveAndLockAll --write-locks`, zero drift elsewhere) and a regenerated
  `docs/reference/architecture/module-deps.md` (`scripts/architecture/module-deps.mjs --update-canonical`;
  `--check-canonical` is red otherwise). Dependency scope is load-bearing: `app-api` and `core` are
  `api`, the rest `implementation`, so worker internals never reach `ui`'s compile classpath
  transitively at A6. The placeholder `EngineRoot` is held in the dead-code accepted store with a
  reason, not by a fake caller; the entry self-retires once A6 gives it a caller.
- **A2.** The rule 6b falsification does not compile as written: `app-services` has no compile edge
  to `worker-services` (`modules/app-services/build.gradle.kts:14-30`), so the probe needs a temporary
  dependency plus `--write-locks`, both reverted (the compile failure is itself evidence the edge does
  not exist). Javadoc-only hits into worker internals are four, not one: `ConversationApiAssembly.java:495`,
  `inference/InferenceMetricCatalog.java:31`, `inference/InferenceTags.java:26`, `vdu/VduProcessor.java:77`;
  none is a bytecode edge. The `io.justsearch.adapters..` allowance in rule 6b carries no traffic today
  (forward-looking); the `io.justsearch.indexerworker..` allowance is load-bearing
  (`WorkerAppServices`, `DefaultWorkerAppServices`, `KnowledgeServerMigrationOps`), which is what makes
  the green non-vacuous. `LayeringEnforcementTest` imports with `DoNotIncludeTests`, so 6b reads
  production bytecode only.
- **A3.** Line counts at this base are 1143 / 2284 / 329 (not 1017 / 2198 / 329). Five more files
  carried `StreamObserver` than the item names (`IndexSettleOps`, `SyncDirectoryOps`,
  `MigrationControlOps`, `IngestSwitchBufferOps`, and `WorkerScanOps` javadoc); all converted. The
  status vocabulary actually emitted is seven codes (INVALID_ARGUMENT 22 sites, INTERNAL 18,
  ABORTED 2, FAILED_PRECONDITION 2, UNAVAILABLE 2, RESOURCE_EXHAUSTED 1, UNIMPLEMENTED 1); NOT_FOUND,
  DEADLINE_EXCEEDED and CANCELLED have no producer and were not seeded. **Eleven** refusal sites
  needed a `catch (WorkerServiceException e) { throw e; }` ahead of a broad catch, or the conversion
  would have re-labelled them silently — `WorkerSearchService.java:793,980`;
  `WorkerIngestService.java:578,927,979,1042,1102,1650,2026,2083`; `IngestSwitchBufferOps.java:100`
  (the sharpest: `bufferDuringSwitchingOrThrow`, where a buffer-write failure would have been
  re-labelled from "Switch buffer write failed during migration" to the different "Migration is
  switching"). Two further counts, both grep-verified against the A3 commit rather than estimated:
  **43** `completed`-flag assertions were dropped (a returning method IS the completion; the
  adapter's `onNext`-then-`onCompleted` ordering is asserted once, in `WorkerServiceCallsTest`), and
  **26** `asException()` sites became `asRuntimeException()` through the adapters (search 18, ingest
  6, switch-buffer 2) — indistinguishable on the wire, code and description preserved.
  Interim streaming signatures: `scanRoot(Req, Consumer<ScanRootProgress>, CallContext)`
  and `subscribeIndexingJobs(Req, Consumer<IndexingJobsFrame>, CallContext)`, bridged by the
  `Delegating*Service` adapters through `WorkerServiceCalls`. **Carried to A9:**
  `SearchExecutor.java:117,266,568` reads `TracingServerInterceptor.currentOtelContext()` (a
  worker-core class); A9 deletes that interceptor and must thread the OTel parent context through
  `SearchOrchestrator` instead. `WorkerSearchServiceDocumentSliceWireTest` moved to `indexer-worker`
  (it needs a real Netty server, now the adapter's). `scripts/resilience/contracts/rpc-retry-ownership-matrix.v1.json:199`
  had an evidence path under the wrong module; corrected.
  **Carried to A6 — the deadline still has no in-process owner.** `CallContext` deliberately carries
  no deadline: no worker-side code reads one today, because the transport enforces it (the Head's
  `RpcDeadlineCategory` sets it and gRPC cancels the call, which the services observe only as the
  cancellation signal). A9 deletes that transport, and with it the deadline-to-cancel path — after
  which nothing converts an exceeded budget into a cancellation. Every in-process caller today
  supplies `CancelSignal.NEVER` (`WorkerIngestService.java:1172` in `reconcileRoot`, and
  `KnowledgeServerMigrationOps.java:558,735` replaying buffered switch-buffer ops), so the port has
  no live cancellation source at all off the wire. **A6 owes the deadline/work-budget re-home that
  design §6 requires** ("none may vanish with the channel"); seeding an unread field at A3 would
  have been residue, not re-homing. Related: `WorkerServiceCalls.callContext` probes
  `instanceof ServerCallStreamObserver` and falls back to `CancelSignal.NEVER`, where the
  pre-conversion `subscribeIndexingJobs` cast hard — production always supplies a
  `ServerCallStreamObserver`, so the live path is unchanged, but the failure mode moved from
  `ClassCastException` to a silently non-cancellable call.

- **A4.** The item's "re-homed" reads as a move; it is not. `ForegroundLoad` stays in
  `worker-services` — what A4 adds is a **second producer** in `app-engine` (`ForegroundLoadGate`)
  for the nine calls the interceptor covers, wired at A6. The interceptor's set is nine **full gRPC
  method names** (`io.justsearch.ipc.SearchService/Search`, …), which do not survive the wire, so the
  gate mirrors them as bare method names and the drift test compares the two after stripping the
  service prefix. The pin needs a **test-only** `app-engine -> indexer-worker` dependency (the
  interceptor lives in `indexer-worker`, which `app-engine` does not otherwise depend on);
  `LayeringEnforcementTest` imports with `DoNotIncludeTests`, so rule 6b is unaffected, and the line
  goes with the interceptor at A9. Adding it changed `modules/app-engine/gradle.lockfile` by 6 lines
  (`resolveAndLockAll --write-locks`, no drift elsewhere). The shared-instance requirement is met by
  constructor injection only: `KnowledgeServer.foregroundLoad` (`:166`) is **private with no
  accessor**, so A6's real handle is `IndexingPacing.foregroundLoad()`
  (`.../loop/pacing/IndexingPacing.java:130`), which `app-engine` can already see — or the root
  constructs the gauge and passes it down. The gate is unreferenced by main code until A6 and joins
  `EngineRoot` in the dead-code accepted store; new violations are **not** auto-added by
  `allowStoreUpdate=true` (verified: the run failed and left the store untouched), so the entry is a
  deliberate edit, and the store's shrink direction retires both once A6 gives them callers.
- **A5.** The item says "replace `WorkerSignalBus`'s two gauge methods"; they are **kept**, so the
  seven read sites compile unchanged, and only the *composition rule* moves into the gauge
  (`GpuSchedulingGauge.shouldYield`) with the interface default delegating to it — one rule, so A10
  cannot leave a drifting copy. Home: `modules/core` (`io.justsearch.core.scheduling`), the lowest
  module with no project dependency of its own that both halves already see — `app-services`
  `api(project(":modules:core"))` (`modules/app-services/build.gradle.kts:23`) and, on the worker
  side, `worker-core`'s `api(":modules:adapters-lucene")` (`modules/worker-core/build.gradle.kts:9`)
  re-exporting `api(":modules:core")` (`modules/adapters-lucene/build.gradle.kts:13`). **No new
  module edge in either direction**; the `app-services -> worker-core` candidate was rejected because
  §1's "`app-services` has no `worker-*` edge" is load-bearing. `WorkerSpawner.pollEnergyState` is at
  `:709-729` as the item says, but **there was no existing test of it, nor of
  `MainSignalBus.writeEnergyReduced`, to retarget** — the poll's only pins were the MMF offset
  (`MmfWorkerSignalLayoutV1Test`) and the yield composition (`WorkerSignalBusEnergyTest`, extended
  here). The new `EnergyStatePoller` (`io.justsearch.app.services.power`, owned and started by
  `KnowledgeServerBootstrap`) is covered for the first time, and it must be **restartable**: the
  bootstrap resets `started` in `closeForUpgrade()` (`KnowledgeServerBootstrap.java:1015`) and boot
  recovery restarts the same instance. One behaviour note carried to A6: the poll now starts after
  `spawner.start()` returns rather than inside it (step 5b), i.e. after port discovery instead of
  before, and the gauge is written before the MMF sink so a bus failure can no longer
  lose the in-process signal. **Corrected at A6 (review #13): "sub-second" was wrong.**
  `spawner.start()` blocks on port discovery for up to `DEFAULT_PORT_DISCOVERY_TIMEOUT_MS = 15_000`
  (`KnowledgeServerConfig.java:67`), so behind it the first poll could be delayed by a full budget —
  and a `spawner.start()` that *threw* meant the poll never started at all. A6 moves it to step 2c,
  right after the signal bus is created and opened, which also required making `MainSignalBus.open()`
  idempotent (`WorkerSpawner.start()` still calls it as its step 1, and a second mapping would leak
  the first arena). `InferenceWiring` now reads the signal bus per event instead of
  capturing it once, so a null bus no longer disables the broadcast entirely; the MMF byte is still
  written under exactly the same condition, so live behaviour is unchanged. `energyState()`'s
  `WorkerSpawner` mention in `PowerStatusView.java:9` was stale and was corrected in the same commit.

## 1. Dependency graph established (the shape `app-engine` must fit)

Read from each module's `build.gradle.kts` `dependencies` block and `settings.gradle.kts:113-147`:

| module | main-source project edges (the load-bearing ones) |
|---|---|
| `ui` | app-services, app-agent, app-api, app-inference, **adapters-lucene**, **ipc-common**, core, configuration, telemetry, gpu-bridge, app-observability, … (`modules/ui/build.gradle.kts:2-40`) |
| `app-services` | app-api, app-agent, app-inference, app-observability, core, **ipc-common**, telemetry, infra-core, gpu-bridge, reranker, indexing, ai-backend, … — **no `worker-*`** (`modules/app-services/build.gradle.kts:2-54`) |
| `worker-services` | worker-core, adapters-lucene, **ipc-common**, indexing, telemetry, configuration, ort-common, reranker, core-contracts, extension-substrate — **no `app-services`, no `ui`** (`modules/worker-services/build.gradle.kts:2-16`) |
| `worker-core` | adapters-lucene, indexing, configuration, ort-common, telemetry, ai-backend |
| `indexer-worker` | worker-core, worker-services, **ipc-common**, adapters-lucene, indexing, configuration, core-contracts, ort-common, telemetry, reranker, ai-backend (runtimeOnly) |
| `app-agent` | app-agent-api, app-api, core, configuration, telemetry |
| `adapters-lucene` | configuration, indexing, core |
| `ipc-common` | app-api (`api`) |
| `app-launcher` | configuration, app-api, telemetry, app-services, app-agent, app-config, app-util; **runtimeOnly** indexer-worker and ui |

**917's claim confirmed:** `worker-services` has **no** main-source edge to `app-services` or `ui`,
and `app-services` has none to `worker-*`. The only cross-half compile edge today is through
`ipc-common` (proto DTOs + MMF layout), which both halves depend on. So
`ui -> app-engine -> {app-services, worker-services, worker-core}` is acyclic by construction.

**One caveat the design does not name:** `ui` already declares
`implementation(project(":modules:adapters-lucene"))` (`modules/ui/build.gradle.kts:17`).
Invariant 1 ("Head never touches Lucene") is enforced by *class*-level ArchUnit
(`IndexWriterOwnershipTest`), not by the module graph. Stage A does not make that worse, but the
superseding ADR (item A15) must state it, since "Lucene lands on the Head classpath" is listed as
a stage-A risk in 917 Derisk 4 and is **already true at compile scope today**.

### Composition path today, both halves

**Head.** `HeadlessApp.main` builds `HeadAssembly` (`modules/ui/.../HeadlessApp.java:435-436`),
then `connectWorker(...)` produces a `KnowledgeServerBootstrap` (`:513`), binds it into the
assembly and the API server (`:587-588`) and starts `KnowledgeServerHealthMonitor` (`:598`).
`KnowledgeServerBootstrap.start()` (`.../worker/KnowledgeServerBootstrap.java:213`) does, in order:
`AppInstanceLock` (`:237-243`), `IpcTelemetry` (`:246-250`), `new MainSignalBus` (`:253`),
`new WorkerSpawner(...)` + `spawner.start()` (`:256`, `:286`),
`new GrpcCircuitBreaker(ipcTelemetry)` (`:289`),
`new RemoteKnowledgeClient(signalBus, deadlineMs, maxRetries, batchSize, circuitBreaker, ipcTelemetry)`
(`:291`), `client.connect(port)` (`:292`), PID validation (`:295`), config-divergence check (`:298`).

**Worker.** `IndexerWorker.main` (`modules/indexer-worker/.../IndexerWorker.java:48`) builds a
`KnowledgeServer` through `DEFAULT_SERVER_FACTORY = KnowledgeServer::new` (`:42`, invoked `:111`).
`KnowledgeServer` (`.../server/KnowledgeServer.java:107`, ctor `:293`) owns `ForegroundLoad`
(`:166-167`), builds the interceptor list including `ForegroundLoadInterceptor` (`:961`), calls
`createGrpcServer` (`:964`, delegating to
`KnowledgeServerGrpcWiring.createGrpcServer` at `.../server/ops/KnowledgeServerGrpcWiring.java:26-51`),
starts it (`:965`) and publishes the bound port to the MMF (`:967-971`). The three concrete
services come from `WorkerAppServices`
(`modules/worker-services/.../server/WorkerAppServices.java:34-38`, implemented by
`DefaultWorkerAppServices:279-291`), wrapped in `Delegating{Search,Ingest,Health}Service` purely so
`DevReloadManager` can swap them (`.../server/DevReloadManager.java:76-78`).

**Therefore the root's two calls are:**
`app-engine` composes `KnowledgeServer` (or a de-gRPC'd successor) directly instead of
`WorkerSpawner`, and hands `HeadAssembly` an in-process `SearchPort` + `IndexingService` backed by
`WorkerAppServices` instead of `RemoteKnowledgeClient`.

---

## 2. `RemoteKnowledgeClient` — every non-forwarding property classified

`modules/app-services/src/main/java/io/justsearch/app/services/worker/RemoteKnowledgeClient.java`
(1824 lines; `public final class ... implements Closeable, SearchPort, IndexingService` at `:99`).

| property | citation | verdict | where it lands in stage A |
|---|---|---|---|
| `RpcDeadlineCategory` (6 categories, multipliers on a base deadline) | `:104-140`, applied `:466-470` | **port property — keep** | a work-budget argument/annotation on the port call; §6's "deadline categories re-homed" |
| `FetchDocuments` byte budget | `BoundedDocumentFetch.java:35,48,53-62,70-91` (a *caller-side pager*, not on the client) | **port property — keep the bound, retire the pager** | in-process there is no message ceiling; the bound becomes a per-call result-size cap on the port. See open question Q4 |
| Streaming flow control (`ScanRoot`, `SubscribeIndexingJobs` back-pressure) | `RemoteIndexingJobsBridge.java:269`; `RootLifecycleOps.java:113-119,235` | **port property — keep** | in-process flow: a bounded queue/`Flow.Subscriber`, not an unbounded `Consumer` |
| Cancellation via `CancelToken` | `RemoteKnowledgeClient.java:1604-1680`; `.../worker/CancelToken.java` | **port property — keep**, but the class today is gRPC-flavoured (it propagates a gRPC `CANCELLED`) | re-home `CancelToken` free of `io.grpc`, or replace with the JDK primitive already in use |
| Circuit breaker (3 failures / 10 s) | `:148`, `:434-449`; two impls (`ipc-common/.../grpc/GrpcCircuitBreaker.java`, `app-services/.../worker/GrpcCircuitBreaker.java`) | **wire property — delete** | nothing to break in-process |
| gRPC retry service config (`maxRetries`, backoff 100 ms → 2 s ×2) | `:101-103`, `:239`, `:359-363` | **wire property — delete** | a retry over a direct call re-runs work; the design's operation contracts, not a transport retry |
| Port re-discovery / `reconnect(long expectedPid)` | `:387-419`, over `MainSignalBus.readPort()` | **wire property — delete** | |
| `ManagedChannel` + `maxInboundMessageSize` | `:317`, `:325` (`GrpcMessageLimits.MAX_INBOUND_MESSAGE_BYTES`) | **wire property — delete** | |
| `IpcTelemetry` / `IpcMetricCatalog` / `IpcTags` | `:230-241`; `.../worker/IpcTelemetry.java` | **wire property — delete**, but audit its metric names first | any metric a `/api/health` or status consumer reads must survive under a non-IPC name |
| `KnowledgeServerNotConnectedException` / `CircuitBreakerOpenException` | `modules/ipc-common/.../{KnowledgeServerNotConnectedException,CircuitBreakerOpenException}.java` | **wire property — delete**, but they are *reason codes to users* today | callers that map them to HTTP responses need a replacement or a removal, per item A17 |
| `batchSize` clamp against Worker `MAX_BATCH_SIZE` | `:226`, `:235-238` | **port property — keep** | a per-call bounded-work property |

**Consequence for §6's "operation contracts are kept":** exactly four things must survive the
channel — deadline/work budget, per-call result-size bound, streaming flow control, cancellation.
Everything else in the table is a property *of the channel* and dies with it.

---

## 3. Checklist items

Ordering keeps the branch compiling as long as possible. **It cannot be compiling throughout**:
17.4 fixes compose-and-delete as one checkpoint, and the two gates that force it
(`WholeProgramDeadCodeTest`, `adr-0002-grpc-present`) make a "compiled but unreferenced" wire red.
The named non-compiling window is **A6 through A13** — from the first port that stops going
through `RemoteKnowledgeClient` until the wire is gone. Items A1-A5 and A14-A20 each leave the
branch green.

### A1 — `modules/app-engine` skeleton (green)

Create `modules/app-engine`, package `io.justsearch.app.engine`, added to
`settings.gradle.kts:113-147` between `:modules:app-services` and `:modules:ui`. Deps:
`app-services`, `worker-services`, `worker-core`, `app-api`, `core`, `configuration`, `telemetry`.
Add `runtimeOnly(project(":modules:app-engine"))` to `modules/app-launcher/build.gradle.kts`
(so `LayeringEnforcementTest`'s `@AnalyzeClasses(packages = "io.justsearch")` sees it) and to
`modules/dead-code-audit/build.gradle.kts:17-49`'s `auditedModules` list.
**Acceptance:** `./gradlew.bat build -x test` green; `:modules:dead-code-audit:test` green;
a deliberately-injected `io.justsearch.app.engine` class importing `io.justsearch.ui.HeadlessApp`
fails `onlyAppLauncherMayDependOnUi` (`LayeringEnforcementTest.java:95`), then reverted.

### A2 — ArchUnit rule 6b, added green (green)

Add to `LayeringEnforcementTest`: no class outside `io.justsearch.app.engine..`,
`io.justsearch.indexerworker..`, `io.justsearch.adapters..` may depend on
`io.justsearch.indexerworker.{server,services,loop}..`. Re-word (not re-predicate) the `as()` of
`indexerWorkerMustNotDependOnUi` (`:138`) and `indexerWorkerMustNotDependOnAppServices` (`:185`) —
both say "separate process" today.
**Acceptance:** green at A1's HEAD (verified: no `ui`/`app-services` main class has a bytecode edge
into worker code — the only hits are javadoc, e.g.
`modules/ui/.../api/ConversationApiAssembly.java:495`, and a string literal in
`modules/app-launcher/src/test/.../LibraryResolveHashOnlyCallerPin.java:66`). Inject an
`app.engine -> ui` edge, see red, revert; inject an `app.services -> indexerworker.services` edge,
see 6b red, revert.

### A3 — de-gRPC the three worker services in place (green)

`GrpcSearchService` (1017 lines), `GrpcIngestService` (2198), `GrpcHealthService` (329) are
**converted, not deleted** — the design row's "gRPC services … deleted" means the service
*registration and `ImplBase` inheritance*, not the 3.5 k lines of logic behind them. Drop
`extends *Grpc.*ImplBase`; turn each `public void m(Req, StreamObserver<Resp>)` into
`public Resp m(Req)`; proto DTOs stay at the signature (§6, transitional). Rename the classes off
the `Grpc` prefix in the same commit or the §6 sweep at F inherits 3 500 lines of misleading names.
**Acceptance:** `:modules:worker-services:test` green; no `io.grpc` import remains in
`modules/worker-services/src/main`; `KnowledgeServerGrpcWiring` still compiles against the
converted types until A9.

### A4 — `ForegroundLoad` re-homed and fed at the search entry points (green)

`ForegroundLoad` is `worker-services`-local
(`modules/worker-services/.../loop/pacing/ForegroundLoad.java:26`) with exactly one producer,
`ForegroundLoadInterceptor` (`modules/indexer-worker/.../server/ops/ForegroundLoadInterceptor.java:36`),
whose own javadoc says it "is thrown away" under lane F (`:18-21`). The nine foreground methods are
enumerated at `:38-48`.
**A rule-6b trap to avoid:** `ForegroundLoad` lives under `io.justsearch.indexerworker.loop..`, so
if `ui` or `app-services` calls it directly, rule 6b (A2) goes red. Feed it **in the `app-engine`
port adapter** that replaces those nine `RemoteKnowledgeClient` methods — allowed by 6b, and it is
the exact set the interceptor covers.
**Acceptance:** a unit test in `app-engine` asserts each of the nine port calls increments and
decrements the gauge, including on exception and cancellation; and that `IndexStatus` and
`ListAllDocumentIds` do **not** (the two deliberate exclusions, `:28-33`).

### A5 — `main_gpu_active` / `energy_reduced` as one in-process gauge (green)

Writers today: `MainSignalBus.writeGpuActive` (`.../worker/MainSignalBus.java:164`) called from
`InferenceWiring.java:42,53`; `MainSignalBus.writeEnergyReduced` (`:191`) called from
`WorkerSpawner.pollEnergyState()` (`WorkerSpawner.java:725`).
Readers: `WorkerSignalBus.isMainGpuActive()` / `isEnergyReduced()` / `shouldYieldGpuBackfill()`
(`modules/worker-core/.../coordination/WorkerSignalBus.java:79,88,101`), consumed at
`KnowledgeServer.java:1323,2003`, `BackfillScheduler.java:198-199`,
`EmbeddingProviderLifecycle.java:169`, `EmbeddingBackfillOps.java:202-203`,
`IndexingDocumentOps.java:256`, `BgeM3BackfillOps.java:346` — **seven** production read sites, not
the six 917 §9 counted (917 missed `BgeM3BackfillOps`).
Replace `WorkerSignalBus`'s two gauge methods with a plain in-process holder the root binds and
`InferenceWiring` writes; move the energy poll off `WorkerSpawner` (it dies at A11).
**Acceptance:** all seven read sites compile against the new holder; a test flips the gauge and
asserts `LoopPacingPolicy.shouldRunBackfill` (`.../loop/ops/LoopPacingPolicy.java:54-55`) responds;
`git grep -c OFFSET_MAIN_GPU_ACTIVE modules` and `OFFSET_ENERGY_REDUCED` reach 0 after A10.

### A6 — the ports as direct calls (**compile-red window opens**)

`app-engine` binds a `SearchPort` + `IndexingService` implementation over `WorkerAppServices`.
`KnowledgeServerBootstrap.client()` keeps its accessor shape so its ~27 consumers (917 §1) do not
each need editing, but its type changes from the concrete `RemoteKnowledgeClient` to the two
interfaces. The consumers typed on the **concrete class** are the edit set; the one already typed
on the interface (`modules/ui/.../api/IndexingController.java`) is the model.
**Acceptance:** every consumer in 917 §1's table compiles against `SearchPort`/`IndexingService`
only; `git grep -c RemoteKnowledgeClient -- 'modules/**/src/main/**'` is 0.

### A7 — `SubscribeIndexingJobs` as an in-process flow

Head side: `RemoteIndexingJobsBridge.java:269` (`asyncStub.subscribeIndexingJobs`), wired at
`.../bootstrap/phases/IndexingJobsBridgeWiring.java:51`. Worker side: the change stream named as
the canonical record in `governance/operation-surfaces.v1.json`
(`modules/indexer-worker/.../queue/IndexingJobsChangeStream.java`).
**Acceptance:** the SSE fan-out still emits; the flow is bounded (a cap + a drop-or-block policy
stated in the code), not an unbounded `Consumer`; a test drives more events than the bound and
asserts the declared policy.

### A8 — `ScanRoot` as an in-process flow

`RootLifecycleOps.ScanRootFn` (`.../worker/RootLifecycleOps.java:113-119`, dispatched `:235`),
SSE fan-out via `ScanProgressRegistry` + `ScanProgressController`
(`modules/ui/.../api/ScanProgressController.java:36-42`).
**Acceptance:** the registry keeps its shape; a scan cancelled mid-flight through `CancelToken`
stops producing within one progress tick; the flow is bounded as in A7.

### A9 — delete the gRPC server and interceptors

Files: `modules/indexer-worker/.../server/ops/KnowledgeServerGrpcWiring.java`,
`.../server/ops/ForegroundLoadInterceptor.java`, `.../grpc/Delegating{Search,Ingest,Health}Service.java`,
`modules/worker-core/.../grpc/{GrpcContextKeys,RequestMetadataInterceptor,TracingServerInterceptor}.java`.
`KnowledgeServer.java:961-971,1212-1219,2095-2126,2266-2273` loses its `Server` field and port
publication. **Depends on Q1** (hot reload): the `Delegating*` wrappers exist only for
`DevReloadManager.java:76-78`.
**Acceptance:** `git grep -l 'io\.grpc' -- 'modules/indexer-worker/src/main/**' 'modules/worker-core/src/main/**' 'modules/worker-services/src/main/**'` is empty.

### A10 — delete `RemoteKnowledgeClient`, the wire client stack and the MMF bus

See the deletion inventory (§7) groups W and M.
**Acceptance:** the two `GrpcCircuitBreaker` classes, `IpcTelemetry`, `MainSignalBus`,
`MmfWorkerSignalBus`, `MmfWorkerSignalLayoutV1`, `MmfWorkerSignalHeaderV1` and their tests are gone;
`git grep -c 'MmfWorkerSignal' modules` is 0.

### A11 — delete `WorkerSpawner`, `SupervisionPolicy` and the argv builders

Group S. `WorkerSpawner.buildCommand()` (`.../worker/WorkerSpawner.java:433`, invoked `:368`),
`worker.log` redirection + rotation (`:379-404`), forwarded props (`:71`, `:497-499`),
`-XX:+UseCompactObjectHeaders` (`:550`), `pollEnergyState` (`:709-729` — moved at A5).
Chaos-tier argv builders (`WorkerProcessManager` `createJarProcessBuilder` / `createScriptProcessBuilder`
/ `createJavaWithArgfileProcessBuilder`) go with group C.
**Acceptance:** `git grep -l WorkerSpawner -- 'modules/**/*.java'` is 0 (27 files today).

### A12 — replace the 21 `WorkerProcessManager` consumers with JVM-level tests

Group C. Each test's *intent* is a property, not a process: crash isolation, config propagation,
migration control, corruption rebuild, torture. Re-express each as an in-JVM test against the
`app-engine` root (the "engine as a library" affordance of §10) or, where the property genuinely
needs a process, against the whole Engine JVM. **Do not delete a test without a named replacement
assertion** (`fix-root-causes-not-symptoms`).
**Acceptance:** a table in the commit body mapping each of the 21 classes to its replacement; the
`systemTest`/`soakTest`/`integrationTest` suites green.

### A13 — one spawn path, one AOT cache (**compile-red window closes**)

`modules/shell/src-tauri/src/lib.rs` spawns only the Head today
(`spawn_headless_backend` at `:480`, flag block `:734-760`, `headless-backend.log` at `:499-508`,
`:1193`) and contains **zero** occurrences of `worker`, `grpc`, `mmf` or `WorkerSpawner` — Tauri has
never known the Worker. So "one spawn path in `lib.rs`" is a **rename plus a flag decision**, not a
merge: the surviving spawn is the existing one; the Engine's flag set is PR 0's Head set
(`-XX:+UseSerialGC -XX:MetaspaceSize=128m -XX:-UsePerfData`) plus a first cut of the Worker's
`-XX:+UseCompactObjectHeaders` (owner: gate run, 17.7) and `-Xmx` re-sized for one JVM.
`scripts/dev/dev-runner.cjs` keeps `buildHeadJavaOpts` and loses the Worker spawn;
`scripts/dev/test-dev-runner-head-java-opts.mjs` pins the set and must be updated with it.
One AOT cache: `aot/head.aot` is the Engine's (`lib.rs:736`); the Worker's touch list
(`modules/indexer-worker/.../AotTraining.java:46`, `touch("io.grpc.ManagedChannelBuilder")`) folds
into `modules/ui/.../AotTraining.java` minus the gRPC touch.
**Acceptance:** `./gradlew.bat build -x test` green; `node scripts/dev/test-dev-runner-head-java-opts.mjs`
green; the dev stack starts and exactly one `java.exe` under the run's tree.

### A14 — proto: drop the service blocks and the grpc codegen (green)

`modules/ipc-common/src/main/proto/indexing.proto` keeps its **messages** (§6: proto DTOs at the
ports, transitional) and loses its three `service` blocks (`SearchService` 10 RPCs at `:299-317`,
`IngestService` 38 at `:1242-1371`, `HealthService` 1 at `:1700-1701`).
`modules/ipc-common/build.gradle.kts:17-21,58` drops `libs.grpc.protobuf`, `libs.grpc.stub`,
`libs.grpc.core` and the `protoc-gen-grpc-java` plugin; the same removal follows in
`app-services`, `indexer-worker`, `worker-core`, `worker-services`, `ui`, `app-services`
integrationTest, `system-tests` (`git grep -n 'libs\.grpc\|io\.grpc' -- 'modules/**/*.kts'`, 23
lines across 9 files).
**Decision needed:** `io/justsearch/ipc/v1/infra_diagnostics.proto` (2 RPCs) and
`modules/app-observability/.../InfraHealthGrpcService.java` are a *separate* service with **no
production registration site** (`git grep InfraHealthGrpcService -- modules` finds only itself and
two tests). See Q5.
**Acceptance:** `--gate wire` green; `adr-0002-grpc-present` **red** (proving A15 is required, not
optional) — then green after A15 retires it.

### A15 — one superseding ADR, `docs/decisions/0049-*.md`

Next free number under `docs/decisions/` is **0049** (highest today: `0048-extraction-isolation-and-indexing-pacing.md`).
Supersedes ADR-0001 (three-process architecture) and ADR-0002 (gRPC/MMF hybrid IPC) with one
decision: the Engine JVM, the ports, and the process boundaries that survive (llama-server,
extraction child pool). Per the register's own amendment note
(`governance/adr-probes.v1.json:5`, "never to edit the probe until it passes … re-examine and amend
the ADR"), the four probes are **retargeted or retired in the same change**:

| probe | citation | action |
|---|---|---|
| `adr-0001-lucene-owners-pinned` | `:10-17`, backs `IndexWriterOwnershipTest#onlyLuceneOwnersMayDependOnLuceneClasses` | **retarget to ADR-0049.** The class-level Lucene-owner pin survives verbatim (917 Derisk 4: "`IndexWriterOwnershipTest` stays at two Lucene-owner packages"); the *process* half of invariant 1 becomes rule 6b, which gets its own probe row |
| `adr-0002-mmf-layout-pinned` | `:18-25`, backs `MmfWorkerSignalLayoutV1Test#reserved1EndsAtMmfSize` | **retire** — the test file is deleted at A10 |
| `adr-0002-mmf-constants-pinned` | `:26-36`, grep-present on `MmfWorkerSignalLayoutV1.java` | **retire** — the file is deleted at A10 |
| `adr-0002-grpc-present` | `:37-48`, grep-present `libs\.grpc\|io\.grpc`, `paths:["modules"]`, `include:[".kts"]` | **retire.** Replace with an ADR-0049 **absence** probe (the register's stated preference order puts absence above counts): `grep-absent` for `libs\.grpc` under `modules/**/*.kts` |
| *new* | — | `adr-0049-engine-port-boundary`, `kind: test`, backing `LayeringEnforcementTest#onlyEngineAndWorkerMayDependOnWorkerInternals` (rule 6b) |

**Acceptance:** `node scripts/governance/run.mjs --gate adr-coverage --mode gate` green;
`node scripts/governance/run.mjs --gate register-guard-resolution --mode gate` green.

### A16 — one `engine.log` and the channel collapse

Two logback configs exist and would collide on one classpath:
`modules/ui/src/main/resources/logback.xml:29,31` (`headless-backend.log`) and
`modules/indexer-worker/src/main/resources/logback.xml:26-27,37`
(`LOG_DIR=${JUSTSEARCH_DATA_DIR}/logs`, `LOG_FILE=worker`). **Delete the indexer-worker one** and
retarget the ui one to `engine.log`, merging the Worker's appenders (async, OTel MDC) where they
differ.
Diagnostic channel: `CoreSurfaceCatalog.java:148-149` registers **only** `core.head-log`
(`verified-facts.md` re-verification is right; `core.worker-log` is registered nowhere — it appears
only as a javadoc example at
`modules/app-agent-api/.../registry/DiagnosticChannelRef.java:11` and in FE test fixtures).
So the collapse is: rename `core.head-log` -> `core.engine-log`, fold the Worker stream into it,
recompute the derived surface altitude (`CoreSurfaceCatalog.java:703`, and
`CoreSurfaceAltitudeDerivationTest.java:129,162`), and update
`modules/app-api/src/main/resources/messages/registry-diagnostic.en.properties:7-8`
plus `governance/observed-happening.v1.json:35-38` (`id: head-log`, `canonicalSource`,
`contributors`) and `governance/live-channels.v1.json:90`.
Consumers of the **name** `worker.log` that must follow: `scripts/jseval/jseval/backend.py:38,813-820`,
`scripts/jseval/jseval/commands/ops.py:52,67,234`,
`scripts/jseval/jseval/projections/contract_violations.py:88`,
`scripts/dev/dev-runner.cjs:574-657,708,751,1869-1916,2189,2399`,
`modules/ui/.../api/DebugStateController.java:138-140,249,258`,
`modules/app-services/.../worker/WorkerStartFailures.java:33-40` (dies with group S),
`scripts/jseval/tests/test_index_cache.py:46`,
`modules/system-tests/.../harness/IsolatedBackendFixture.java:501-503`,
`modules/system-tests/.../ExtractionSandboxChaosTest.java:301,308`,
`modules/ui/src/integrationTest/.../SchemaMismatchStatusContractTest.java:91`,
`modules/indexer-worker/src/test/.../WorkerLogbackConfigurationTest.java:23,59-60`,
`governance/store-recoverability.v1.json:1229` (a *note* that cites WorkerSpawner's policy as the
precedent for llama-server.log retention — the prose must be corrected, not the classification).
**Acceptance:** one log file under `<dataDir>/logs/`; `operation-surface` and `surface-altitude`
gates green; `jseval ops` prints the engine log; `node scripts/ci/check-store-recoverability.mjs` green.

### A17 — holding action for the `worker.*` readiness vocabulary and `/api/debug/worker-log`

Two consequences of deleting the Worker process that §6's table does not list:

1. **`check-readiness-reason-codes` PRODUCER direction.** 18 `WORKER_*` members in
   `modules/app-api/.../lifecycle/LifecycleReasonCode.java:19-69`; several lose their only
   `src/main` reference. D1 owns the re-cut (17.7: the envelope becomes the authority), so stage A
   must **not** re-cut them. The holding action is one of Q3's options; pick it before A11 lands.
2. **`GET /api/debug/worker-log`** exists as a route
   (`modules/ui/.../api/DebugStateController.java:249`) and is baked into three generated FE
   artifacts (`modules/ui-web/src/api/generated/apiRoutes.ts:72`,
   `reference-client-openapi.snapshot.json:1732-1739`, `route-manifest.snapshot.json:1276`) and the
   dev-MCP allowlist (`scripts/dev/justsearch-dev-mcp/server.mjs:780`). Renaming or retiring it is
   an **enumerated contract change stage A makes that design §6 does not list** — see the added row
   in §5 below.

### A18 — the dev MCP follows (`start`, `stop`, `reload`) and hot reload is re-homed

`check-dev-mcp-doc-sync` (`scripts/ci/check-dev-mcp-doc-sync.mjs:29-31`) diffs
`docs/reference/contributing/mcp-dev-tools.md` against the **running** server's `tools/list` plus
`FETCH_API_ENDPOINT_MAP` / `API_CALL_ALLOWLIST`. The doc's Worker-shaped rows are `:76`, `:97`,
`:112`, `:125-139`, `:196`, `:220`, `:246`, `:288`, `:302`, `:313-315`, `:324-361`.
Hot reload concretely: JDWP port selection already happens once in the dev-runner
(`scripts/dev/dev-runner.cjs:823-833,942-985,1670,1722-1729`, forwarded as
`JUSTSEARCH_DEV_DEBUG_PORT`) — it now targets the Engine. The **trigger** must change: today
`scripts/dev/justsearch-dev-mcp/server.mjs:2840` writes byte 29 of the MMF by literal offset
(a duplication of `MmfWorkerSignalLayoutV1.OFFSET_RELOAD_SIGNAL` that is not imported from it), and
the MMF is deleted at A10. The **reconstruction step** is `DevReloadManager`
(`modules/indexer-worker/.../server/DevReloadManager.java:41-98`), which is package-private to
`indexer-worker`, reads the signal at `:52` and swaps the three gRPC delegates at `:76-78`.
See Q1 — this is the single largest unresolved mechanism in stage A.
**Acceptance:** `node scripts/ci/check-dev-mcp-doc-sync.mjs` green; a live `reload` on the dev stack
compiles, pushes and reconstructs services with the ONNX encoders still loaded (or Q1's decided
fallback is documented in the tool's own refusal message, not left as a silent no-op).

### A19 — config-snapshot tier retired

`ORDINAL_WORKER_SNAPSHOT = 450` (`modules/configuration/.../resolved/ResolvedConfigBuilder.java:59`),
`contributeWorkerSnapshot` (`:265-283`), `loadWorkerSnapshotFromSysprop` (`:124-161`),
`ResolvedConfig.toWorkerSnapshot` (`.../resolved/ResolvedConfig.java:118`) /
`loadWorkerSnapshot` (`:153`), `WorkerSpawner.WORKER_FORWARDED_PROPS`
(`.../worker/WorkerSpawner.java:71`, applied `:497-499`), and the three tests that pin them
(`WorkerSpawnerConfigForwardingTest`, `WorkerSnapshotAutoDetectedTest`,
`ForegroundPacingConfigForwardingTest`). Docs to correct: `docs/explanation/01-system-overview.md:76,78`.
Note `AiInstallService.java:1840,1911` and `RuntimeActivationService.java:1742` reference the tier
in prose/logic and must be re-read.
**Acceptance:** `ORDINAL_WORKER_SNAPSHOT` and `WORKER_FORWARDED_PROPS` return 0 hits repo-wide
outside `docs/tempdocs/`; the resolved-config ordinal table has no gap left undocumented.

### A20 — surface registers and the regen set

Per §8 below. **Acceptance:** `node scripts/governance/run.mjs --gate execution-surface --mode gate`,
`--gate operation-surface`, `--gate surface-altitude`, `--gate wire`, `--gate adr-coverage`,
`node scripts/ci/regen-all.mjs --check`, `node scripts/ci/run-ui-web-gates.mjs` all green.

---

## 4. Commit plan (one commit per item, 17.6)

`A1 … A20` in the order above, each `feat(936):` / `refactor(936):` / `chore(936):` with the item
letter in the subject so the review range is legible. Two commits are deliberately large and are
reviewed by the compiler and the ratchet rather than line by line (17.4): **A10** (wire deletion)
and **A12** (chaos-test replacement). Both carry a mapping table in the commit body. A6-A13 do not
individually compile; the branch is asserted green at A13 and at A20.

---

## 5. Section 6 contract changes this stage makes, and the gate for each

| change | gate | stage-A status |
|---|---|---|
| `core.head-log` -> `core.engine-log`; the Worker log stream folds in; the derived surface altitude recomputed | `operation-surface`, `surface-altitude` | **made at A16** |
| `diagnostic-channel` producer enum loses the gRPC stream | contract-surfaces register | **made at A16** |
| debug/state shapes registered (§10) | contract-surfaces register | **not stage A** — D2 owns the component map |
| `core.restart-worker` retired; `structuredData.port` consumers re-pointed | `operation-surface` | **not stage A** — D1. A keeps it registered; see §9 |
| runtime manifest gains a child registry | manifest schema test | **not stage A** — B |
| `supervisor.v1.json` + `justsearch://supervisor-state` | closure check sibling allowlist; shell event tests | **not stage A** — B |
| `/api/lifecycle/shutdown` kept; `commit-shutdown` becomes its front half | updater state-machine tests | **not stage A** — B |
| readiness reason codes re-cut by component | `check-readiness-reason-codes` + a component-key test | **not stage A** — D1. A owes only the holding action (A17.1, Q3) |
| admission rejections / `cursor expired` | contract diff | **not stage A** — C1/D2 |
| operation outcome contract | contract diff | **not stage A** — C2 |
| **`GET /api/debug/worker-log` renamed or retired** *(not in §6's table; found this pass)* | route manifest + OpenAPI snapshot regen (`regen-all --check`), `check-dev-mcp-doc-sync` | **made at A17.2** — raise as a §0 design amendment if the owner wants it in §6's enumerated list |
| **`WorkerSpawner`-flavoured metric names under `IpcTelemetry`** disappear | none today | **A10** — audit before deleting; a `/api/health` or status consumer reading one is a silent contract change |

---

## 6. Section 16 rows stage A must leave exercisable

Stage A produces no measurement (E does). It must not make a row *unmeasurable*:

- **positive benefit** — stage A produces the deletion count itself (§7 totals). Record it in
  `docs/design/lane-f-engine-jvm/evidence/A/`.
- **semantic non-regression** — the `SearchTrace` shape per query must be byte-identical across the
  port change. A6/A3 are where it could silently move; the `execution-surface` register (§8) is the
  structural half and a fixture diff is the behavioural half.
- **indexing progress** and **request-time encoders** — both read `ForegroundLoad` and
  `main_gpu_active`/`energy_reduced`. A4 and A5 must keep the gauges live and the pacing policy's
  inputs unchanged, or E has nothing to measure against the split baseline.
- **memory** — A13's flag set is the first cut of the §8 budget lines (17.7: "first cut at A").
- **durability**, **recovery/process**, **recovery/workflow**, **stuck component**,
  **generation transition**, **aggregate bound**, **reconfigure**, **hang** — these are B/C/D
  mechanisms; stage A only must not delete a *store* or *lifecycle hook* they will need. The
  concrete risk is A12: a chaos test replaced by a weaker in-JVM assertion removes the only existing
  exercise of a property B and D will be measured on.

---

## 7. Deletion inventory (grep-verified at `fe19df0d5`)

Counts are **files deleted**. Every group's membership was produced with `git grep -l` / `git ls-files`.

### Group W — wire (gRPC transport and client stack): **22 files**

Main (13): `app-services/.../worker/{RemoteKnowledgeClient,GrpcCircuitBreaker,CircuitBreakerState,IpcTelemetry,IpcMetricCatalog,IpcTags}.java`;
`ipc-common/.../grpc/{GrpcCircuitBreaker,GrpcMessageLimits,GrpcRetryServiceConfig,RequestIdClientInterceptor,TraceClientInterceptor}.java`;
`ipc-common/.../{CircuitBreakerOpenException,KnowledgeServerNotConnectedException}.java`.
Main, worker side (6): `worker-core/.../grpc/{GrpcContextKeys,RequestMetadataInterceptor,TracingServerInterceptor}.java`;
`indexer-worker/.../grpc/Delegating{Search,Ingest,Health}Service.java`;
plus `indexer-worker/.../server/ops/{KnowledgeServerGrpcWiring,ForegroundLoadInterceptor}.java` (2).
Also deleted with the interceptor at A9: `app-engine/src/test/.../ForegroundLoadGateTest.java` (the
A4 drift pin, which exists only to compare the gate against the interceptor). The A4 test-only
`app-engine -> indexer-worker` dependency does NOT go with it — A6 promoted that edge to a
main-source `implementation` (EngineRoot composes `KnowledgeServer`), so A9 removes the pin test
and leaves the edge.
Test (5 of the 7 under `ipc-common/src/test`): `grpc/{GrpcCircuitBreakerTest,GrpcRetryServiceConfigTest,RequestIdClientInterceptorTest,TraceClientInterceptorTest}.java`
and `IndexingProtoDeprecationsTest.java` (re-scope, since the proto's services go).
Chaos (1): `system-tests/src/main/.../chaos/GrpcTestClient.java`.
**Converted, not deleted (3):** `worker-services/.../services/Grpc{Search,Ingest,Health}Service.java`
(3 544 lines total) — item A3.
**Blast radius, for the reviewer's ratio:** 39 main-source Java files import `io.grpc` today
(91 across all source sets; `verified-facts.md` re-verification records 91, which matches).
Distribution: `worker-services` 31 test + 7 main, `app-services` 11 test + 8 main + 1 integrationTest,
`indexer-worker` 9 test + 7 main, `worker-core` 3 main, `ipc-common` 3 main + 2 test,
`ui` 2 test + 2 main, `system-tests` 2 systemTest + 1 main, `app-observability` 1 main + 1 test.

### Group M — MMF bus: **8 files**

`app-services/.../worker/MainSignalBus.java`;
`indexer-worker/.../coordination/MmfWorkerSignalBus.java`;
`ipc-common/.../mmf/{MmfWorkerSignalLayoutV1,MmfWorkerSignalHeaderV1}.java`;
`ipc-common/src/test/.../mmf/MmfWorkerSignalLayoutV1Test.java`;
`ipc-common/.../WorkerFatalReasonMarker.java` + `WorkerFatalReasonMarkerTest.java`;
and `worker-core/.../coordination/WorkerSignalBus.java` **shrinks** (the gauge methods move at A5)
rather than being deleted — it has 46 referencing files.
Residual grep targets: `OFFSET_` (15 module files), `MmfWorkerSignalLayoutV1` (10),
`MmfWorkerSignalBus` (8), `MainSignalBus` (16), `heartbeat`, `suicide`, `breath` — the last three
are the §F sweep's, not A's.

### Group S — spawner / supervision: **9 files**

`app-services/.../worker/{WorkerSpawner,SupervisionPolicy,SupervisionDecision,SupervisionEvents,RecoveryContext,WorkerLivenessDecision,KnowledgeServerHealthMonitor,WorkerStartFailures,PidValidationTimeoutException}.java`
(re-verify `WorkerLivenessDecision`'s package at stage start — 5 referencing files).
Plus their tests under `app-services/src/test/.../worker/` (64 files in that directory today; the
subset to delete is whatever names a deleted class — enumerate at stage start, do not delete by
directory).
Reference counts today: `WorkerSpawner` 27 files, `SupervisionPolicy` 11,
`KnowledgeServerHealthMonitor` 11, `WorkerLivenessDecision` 5.

### Group C — chaos / system tests: **22 files** (1 manager + 21 consumers)

`system-tests/src/main/.../chaos/WorkerProcessManager.java` and the 21 consumers listed by
`git grep -l WorkerProcessManager -- 'modules/**/*.java'`:
`SoakSuiteTest`, `ChaosSuiteTest`, `ExtractionSandboxChaosTest`, `ai/SummarizationPipelineE2ETest`,
`process/{CompleteIndexingWorkflowE2ETest,ConfigPropagationTest,CorruptionRebuildE2ETest,FormatCapabilityMatrixE2ETest,GrpcCommunicationTest,GrpcDataIntegrationTest,IndexBasePathLockE2ETest,MigrationControlE2ETest,PauseResumeMigrationE2ETest,RollbackE2ETest,SwitchingFenceBufferingE2ETest,SyncDirectoryIntegrationTest,WorkerSpawnTest}`,
`torture/{ReadWhileWriteTest,WindowsTortureTest}`, `vdu/{VduBatchProcessorE2ETest,VduRecoverySystemTest}`.
Two of these (`GrpcCommunicationTest`, `GrpcDataIntegrationTest`) test the wire itself and are
**deleted outright**; the other 19 need replacement assertions (A12).

### Group T — config-snapshot tier: **3 test files deleted, 4 main files edited**

Deleted: `configuration/src/test/.../resolved/{WorkerSnapshotAutoDetectedTest,ForegroundPacingConfigForwardingTest}.java`,
`app-services/src/test/.../worker/WorkerSpawnerConfigForwardingTest.java`.
Edited: `ResolvedConfigBuilder.java` (`:59`, `:124-161`, `:265-283`), `ResolvedConfig.java`
(`:118`, `:153`), `WorkerSpawner.java:71` (deleted with group S), `HeadlessApp.java:98` (javadoc).

### Group D — dev tooling: **1 file moved, 5 files edited**

Moved/re-homed: `indexer-worker/.../server/DevReloadManager.java` -> `app-engine` (Q1).
Edited: `scripts/dev/dev-runner.cjs` (worker.log block `:574-657`, JDWP `:823-985,1670,1722-1729`,
per-run stamps `:1869-1916,2189,2399`), `scripts/dev/justsearch-dev-mcp/server.mjs` (reload byte
`:2840`, endpoint map `:780`), `docs/reference/contributing/mcp-dev-tools.md`,
`scripts/dev/test-dev-runner-head-java-opts.mjs`, `modules/indexer-worker/src/main/resources/logback.xml`
(**deleted**, A16).

### Totals

| group | files deleted |
|---|---|
| W — wire | 22 (+3 converted, 3 544 lines) |
| M — MMF | 8 |
| S — spawner/supervision | 9 main (+ their tests, enumerated at stage start) |
| C — chaos tests | 22 |
| T — config tier | 3 |
| D — dev tooling | 1 (moved) + 1 (logback) |
| **total, files** | **65 deleted outright**, before the group-S test tail |
| **contracts removed** | 49 RPCs (10 Search + 38 Ingest + 1 Health), 3 gRPC service registrations, 1 Netty server, 9 MMF fields / 64-byte region, 2 argv builders (1 production + 1 chaos class with 3 methods), 1 config ordinal, 1 log file |

Record this table in `evidence/A/` as the input to 16's positive-benefit row.

---

## 8. ArchUnit changes

**Rule 6b (new, `LayeringEnforcementTest`).** Text (917 Derisk 4, adopted verbatim as the design's
3.3 pin):

> no classes that reside outside `io.justsearch.app.engine..`, `io.justsearch.indexerworker..` and
> `io.justsearch.adapters..` should depend on classes that reside in
> `io.justsearch.indexerworker.server..`, `io.justsearch.indexerworker.services..` or
> `io.justsearch.indexerworker.loop..` — "sharing a JVM does not license application code to reach
> past a port into Lucene" (ADR-0049; the retargeted invariant-1 pin).

**Edits.**
- `LayeringEnforcementTest.java:138` `indexerWorkerMustNotDependOnUi` — predicate unchanged, `as()`
  loses "(runs in separate process)".
- `LayeringEnforcementTest.java:185` `indexerWorkerMustNotDependOnAppServices` — predicate unchanged,
  `as()` loses "(isolated worker)".
- `LayeringEnforcementTest.java:95` `onlyAppLauncherMayDependOnUi` — **unchanged**; `app-engine`
  sits *below* `ui`, so nothing about it needs an exemption. This is the rule 917 Derisk 4
  identified as the real blocker for the rejected "reuse `modules/ui`" shape.
- `LayeringEnforcementTest.java:162` `ipcCommonMustNotDependOnHigherLayers` — unchanged, but
  `ipc-common` shrinks to proto DTOs only; re-check it is not left vacuous.
- `BoundaryRulesTest.java:12-22` `launcherMayOnlyDependOnAppApi` — **`io.justsearch.app.engine..`
  must be added to its forbidden list**, or the launcher gains a compile path into the engine root
  that the rule's own intent ("the launcher CLI remains isolated from UI surfaces and application
  services") forbids. Note `app-launcher` takes `app-engine` as `runtimeOnly`, which is a classpath
  edge, not a bytecode dependency, so the rule stays green.
- `modules/dead-code-audit/build.gradle.kts:17-49` — add `"app-engine"` to `auditedModules`
  (alphabetically after `"app-config"`), or every rule 6b class is invisible to the closed-world
  analysis and both the dead-code ratchet and the coverage floor pass vacuously.
- `modules/app-launcher/build.gradle.kts` — add `runtimeOnly(project(":modules:app-engine"))` so
  `@AnalyzeClasses(packages = "io.justsearch")` (`LayeringEnforcementTest.java:23`,
  `BoundaryRulesTest.java:9`) sees the new module at all.

**Acceptance (both directions, per 917 Derisk 4).** Inject an `io.justsearch.app.engine -> io.justsearch.ui`
edge, run `:modules:app-launcher:test --tests '*LayeringEnforcementTest*'`, see red, revert.
Inject an `io.justsearch.app.services -> io.justsearch.indexerworker.services` edge, see rule 6b
red, revert. Record both outputs under `evidence/A/`.

**`WholeProgramDeadCodeTest` baseline.** The store is
`modules/dead-code-audit/archunit_store/80a1aacf-0440-4387-81e6-b7636caaf0bf` (20 accepted entries
today) plus `stored.rules`. `archunit.properties` sets `allowStoreUpdate=true` (shrink direction)
and `allowStoreCreation=false`, so removing dead classes rewrites the store automatically — the
**rewritten store must be committed with the deletion commit**, or the next run shows an unexplained
diff. `COVERAGE_SENTINELS` (`WholeProgramDeadCodeTest.java:65-69`) names
`io.justsearch.ui.HeadlessApp` and `io.justsearch.indexerworker.loop.IndexingLoop`; both survive
stage A, but if `HeadlessApp` is renamed (design 3.1 says "renamed later"), the sentinel follows in
the same commit. `MIN_EXPECTED_CLASSES = 1000` (`:62`) has headroom for a ~65-file deletion.

---

## 9. Governance changes

| register / check | what stage A does |
|---|---|
| `governance/adr-probes.v1.json` | retarget `adr-0001-lucene-owners-pinned` to ADR-0049; retire `adr-0002-mmf-layout-pinned` (`:18-25`), `adr-0002-mmf-constants-pinned` (`:26-36`), `adr-0002-grpc-present` (`:37-48`); add an absence probe on `libs\.grpc` under `modules/**/*.kts` and a `test` probe backing rule 6b. Gate: `--gate adr-coverage` |
| `docs/decisions/0049-*.md` | the one superseding ADR; ADR-0001 and ADR-0002 get `superseded_by: 0049` frontmatter. Next free number confirmed by `ls docs/decisions/` (highest = 0048) |
| `governance/execution-surfaces.v1.json` | **yes, if `app-engine` imports `SearchTrace`.** The gate auto-scans `javaMainRoots: ["modules"]`, `javaInclude: "/src/main/java/"` for `io.justsearch.app.api.knowledge.SearchTrace` and `io.justsearch.ipc.SearchTrace` (register `scan` block). 12 main-source files import one of them today (5 in `app-services`, 4 in `ui`, 3 in `worker-services`). The A6 port adapter almost certainly carries the trace through — register it as a `carrier` with an `exempt:opaque carrier` guard if it only passes the record, or a real guard if it projects. **Design a way not to touch it:** if the adapter is typed on the port interfaces only, no import is added and no entry is needed. Decide at A6, not after the gate reds |
| `governance/operation-surfaces.v1.json` | **same test for `io.justsearch.app.api.indexing.IndexingJobView`** — 6 main-source importers today, including `IndexingJobsBridgeWiring` and `RemoteIndexingJobsBridge`, both of which A7 rewrites. If the in-process flow lands in `app-engine`, register it |
| `governance/observed-happening.v1.json:35-38` | `head-log` -> `engine-log`; `canonicalSource` and `contributors` follow |
| `governance/live-channels.v1.json:90` | note text names `diagnostic-channels/head-log` |
| `check-dev-mcp-doc-sync` | `docs/reference/contributing/mcp-dev-tools.md` rows `:76,97,112,125-139,196,220,246,288,302,313-315,324-361` |
| `check-runtime-manifest-closure` | **`SKIP_PATHS` is unchanged at stage A.** `scripts/ci/check-runtime-manifest-closure.mjs:64-83` exempts `dev-runner.cjs` (`:73`) and `lib.rs` (`:80`); design §6 narrows that for `supervisor.v1.json` at **stage B**, not A. Stage A adds no new `<dataDir>/runtime/` file — verify that claim at A13 by running the check |
| `check-store-recoverability` | `governance/store-recoverability.v1.json:1229`'s prose cites `WorkerSpawner`'s `worker.log` policy as the precedent for `llama-server.log` retention. Correct the prose; the classification is unaffected. Confirm at stage start whether `worker.log` itself has an entry (this pass found none) |
| `check-readiness-reason-codes` | holding action only (A17.1 / Q3) |
| `check-language-agnostic-analysis`, `check-lockfile-completeness`, `check-pmd-ruleset-sync` | not touched by stage A |
| `regen-all --check` | run after A17.2 (route manifest + OpenAPI snapshot) and after any `SSOT/catalogs` touch |

**`core.restart-worker` — what stage A does with it.** The operation stays registered.
`CoreOperationCatalog.RESTART_WORKER` (`modules/app-services/.../registry/operations/CoreOperationCatalog.java:72`,
declared `:381-395`, listed `:338`), its catalog entry (`CoreSurfaceCatalog.java:285,663`), its
handler registration (`.../bootstrap/phases/OperationHandlerRegistrations.java:89`), the HTTP route
`POST /api/worker/restart` (`modules/ui/.../api/routes/InferenceRoutes.java:26`, handler
`InferenceHandlers.java:628`) and the FE client (`modules/ui-web/src/api/domains/inference.ts:242-243`)
all survive; the **handler's body** changes to answer `restart required`, matching the stage-A row's
"config-apply, AI install and pack import exit with a `restart required` code". D1 retires the
operation.
The `operation-surface` gate tolerates this: the register scans for referencers of
`IndexingJobView`/`ActionEvent`, not for the *reachability* of a registered operation, and its
`forbiddenReintroduction` list names interaction-log types, not operations. **Verify by running the
gate at A17**, not by this reasoning alone.

---

## 10. What is allowed to be red after stage A, and nothing else

From 17.3's "branch state after" column, read strictly:

**Allowed red / deliberately lost until B:**
1. **Supervision.** No crash detection, no restart budget, no cooldown, no stability window. A
   crashed Engine stays down until the user restarts it. `WORKER_RECOVERING` /
   `WORKER_RESTART_EXHAUSTED` paths have no producer.
2. **Restart-as-reload.** Config-apply, AI install and pack import **exit the process with a
   `restart required` code**; the dev-runner observes the exit and **does not** restart. Any test
   asserting "the setting applied without a restart" is expected red and must be marked with the
   stage-B item that restores it.
3. **`core.restart-worker` returns `restart required`** rather than restarting anything.
4. The chaos/system tests of group C, for exactly as long as A12 is open — green by A13.

**Not allowed red (a defect of the stage, not a deliberate loss):**
- the full unit suite, `spotlessCheck`, `pmdAll`, `./gradlew.bat build -x test`
- `WholeProgramDeadCodeTest` (17.4 makes it the reason A is one stage)
- every gate in §9's table
- search, ingestion, the dev stack, `jseval run --start-backend`
- `check-readiness-reason-codes` — see A17.1; if it cannot be kept green without a D1-shaped re-cut,
  that is a design amendment under 17.6 ("a change to a gate row waits for the owner's word"), not a
  red to accept.

---

## 11. Stop rule (17.8)

> *Stage A's checklist outgrows the 917 consumer audit → the stage stops and the design is re-read at
> 3 and 6 before more code is written.*

**Verdict: the inventory does not outgrow 917. Proceed.**

What this pass found that 917 did not, and why none of it re-opens §3 or §6:

| finding | size | why it does not trip the rule |
|---|---|---|
| 21 `WorkerProcessManager` consumers, not 20 (`FormatCapabilityMatrixE2ETest`) | +1 file | a counting slip, not a new consumer class |
| 7 `main_gpu_active`/`energy_reduced` read sites, not 6 (`BgeM3BackfillOps.java:346`) | +1 site | same |
| two distinct `GrpcCircuitBreaker` classes | +1 file | duplication inside an already-named deletion group |
| `adr-0002-grpc-present` is `.kts`-scoped | 0 files | *narrows* the work — but it also means 17.4's "the probe is red the moment the wire is deleted" needs item A14 to be true. Recorded in §0.1; a citation correction, which 17.6 lets proceed |
| `check-readiness-reason-codes` PRODUCER direction goes red at A11 | 0 new files; 1 open question | a **gate 917 §7 did not enumerate**. It is answerable inside stage A (Q3) without changing §3 or §6, but if the owner prefers option (c) it becomes a §6 row and needs the owner's word |
| `GET /api/debug/worker-log` + 3 generated FE artifacts + the MCP allowlist | 5 files | an enumerated contract change §6 omits. Small, mechanical, gated by `regen-all --check` |
| two logback configs would collide on one classpath | 2 files | a mechanical consequence of one JVM |
| `ui` already has a compile edge to `adapters-lucene` (`build.gradle.kts:17`) | 0 files | pre-existing; the ADR must state it, it does not change the design |
| the three `Grpc*Service` classes are **converted**, not deleted (3 544 lines) | 3 files | design §17.3's wording compresses this; §17.4 already says deletions are verified by the compiler and the ratchet. Recorded here so the reviewer does not read a 3 544-line conversion as an unreviewed deletion |

Nothing above is a *new consumer category*. 917's nine categories still cover the stage. The two
things that would trip the rule and did **not** appear: a consumer of the wire outside
`app-services`/`indexer-worker`/`worker-*`/`system-tests` (there is none — `ui` reaches
`io.justsearch.ipc.*` from exactly 2 main-source files), and a second in-process transport
requirement (917 Derisk 4's `grpc-inprocess` shape is **superseded** by design 17.3's "ports as
direct calls" — see Q2).

---

## 12. Open questions the implementer must resolve on first contact

| # | question and evidence | candidates | recommendation |
|---|---|---|---|
| **Q1** (largest; blocks A18) | What replaces `DevReloadManager`'s trigger and delegate swap? It reads the MMF byte at `DevReloadManager.java:52` (written by `server.mjs:2840`) and swaps three gRPC delegates at `:76-78`; the `Delegating*Service` wrappers exist for nothing else. A10 deletes both halves. | (a) mutable `WorkerAppServices` holder in `app-engine` that reload rebuilds, triggered by a file under `<dataDir>/runtime/` or a dev HTTP route; (b) JDWP HotSwap only, structural changes reported unappliable; (c) `hotReload:false` in single mode, defer to B (917 Derisk 4's position) | **(a)**, file trigger — an HTTP route is a new API surface stage A does not otherwise add, and the file is a one-line `check-runtime-manifest-closure` sibling-allowlist entry. (c) contradicts the stage-A row's "hot reload re-homed"; (b) silently drops the capability `mcp-dev-tools.md:313-315` advertises |
| **Q2** | Direct calls, or an in-process gRPC channel? 917 Derisk 4 chose `grpc-inprocess`; design 17.3 says "ports as direct calls" and deletes `RemoteKnowledgeClient` in the same change. | (a) direct calls (design); (b) `grpc-inprocess` as a one-stage bridge deleted at B | **(a)** — the design supersedes 917 here. (b) keeps `libs.grpc` in the `.kts` files, which keeps `adr-0002-grpc-present` green over a "deleted" wire (§0.1). If (a) proves infeasible at A6 that is a §0 amendment needing the owner's word, not a quiet fallback |
| **Q3** | What holds `check-readiness-reason-codes` green from A11 to D1? (§0.5) | (a) delete the orphaned `WORKER_*` members and their `readinessNotice.ts` rows — pre-empts D1 and pulls `run-ui-web-gates.mjs` into stage A; (b) an `awaitingRecut` allowlist in `governance/readiness-reason-codes.v1.json`; (c) re-cut now (D1's item) | **(b)**, each entry carrying `"retiredBy": "lane-F/D1"` so F has a grep target. Predictable evasion to pre-empt in review: satisfying the grep with a comment — the gate's own note says a reference is not an emission, so that is the vacuous green it warns about |
| **Q4** | In-process form of the `FetchDocuments` byte budget? `BoundedDocumentFetch.java:11-30` exists only for the 32 MiB gRPC ceiling. | (a) delete it (loses a bound §6 says must not vanish); (b) keep the class, re-express as a per-call result-size cap on the port (`:52-62`); (c) defer to C1 admission | **(b)** — smallest change that keeps §6's promise, and `GplFetchDocumentsByteBudgetTest` retargets rather than dies |
| **Q5** | `infra_diagnostics.proto` + `InfraHealthGrpcService`: 2 RPCs with **no production registration** (only `app-observability`'s unit test and `InfraHealthGrpcServiceIntegrationTest.java:64`); its sibling `InfraHealthController` is already in the dead-code accepted set. | (a) delete both with the wire; (b) leave — §6 names only Search/Ingest/Health; (c) leave, record in F's residue list | **(a)**, verified by the ratchet shrinking. If the owner wants minimal scope, (c) — not (b), which is how residue becomes false authority |
| **Q6** | Does the A6 port adapter carry `SearchTrace` / `IndexingJobView` **by type**? Both registers auto-scan `modules/**/src/main/java/` for the imports (§9). | (a) type the adapter on `SearchPort`/`IndexingService` only, so no import is added; (b) register `app-engine` as a `carrier` with `exempt:opaque carrier` in both | **(a) as the target, (b) as the honest fallback** — decide by grepping the adapter's imports at A6 and running `--gate execution-surface` *before* the commit, not after |
