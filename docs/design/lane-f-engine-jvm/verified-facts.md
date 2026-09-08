# Lane F evidence: facts verified against the code while designing

Base `b96cd999` (#687). Each fact was read in the source by the orchestrator or an independent
reviewer and spot-checked; the implementation checklist written after the lock inherits them.
Section numbers refer to `design.md` beside this file.

## Runtime writer failure (2026-09-08, checked at 4349b28f5)

The integrated stress failure is recorded in `evidence/B/integrated-verification.md`.
`modules/app-services/src/main/java/io/justsearch/app/services/worker/KnowledgeServerHealthMonitor.java`:237-246
only selects boot recovery without a client. With a client it calls `checkHealth`.
`modules/worker-services/src/main/java/io/justsearch/indexerworker/services/WorkerHealthService.java`:200-225
checks SQLite queue access and Lucene reader count, not writer usability.
`modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/IndexCountOps.java`:81-87
returns zero on reader IO failure. Consequently, B14's boot-retry re-cut alone
does not supply runtime recovery from the closed writer observed in the stress log.

`modules/app-engine/src/test/java/io/justsearch/app/engine/EngineTestHarness.java`:100-105
starts `EngineRoot` directly; the failing test does not construct
`KnowledgeServerHealthMonitor`, whose production construction and start are in
`modules/ui/src/main/java/io/justsearch/ui/HeadlessApp.java`:597-617.
`modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/JobBatchWriter.java`:191-207
records write failures, and
`modules/indexer-worker/src/main/java/io/justsearch/indexerworker/queue/SqliteJobQueue.java`:997-1025
persists retry state. These sources do not prove automatic recovery or replay after
this particular failure; that is the next bounded experiment.

## Wire and launch

- `indexing.proto` has **49** RPCs on this base (10 Search + 38 Ingest + 1 Health): #664 added
  `SettleIndex` after 917 counted 48.
- Tauri has no crash or hang supervision today (brief v2 assumed one); the supervisor contract
  (7.1) is new work, seeded from `WorkerSpawner`'s policy constants (627: 3 restarts, 1 s to
  30 s cooldown, 300 s stability window, 3 unhealthy polls), which the sweep deletes.
- Both launch sites (`lib.rs`, `dev-runner.cjs`) carry `UseSerialGC` and `TieredStopAtLevel=1`;
  neither sets `MaxDirectMemorySize` or `ExitOnOutOfMemoryError` (8).
- `RuntimeManifest` carries Head, Worker, AI, mode and chat info and the Head's PID; no child
  inventory (7.2). `check-runtime-manifest-closure` validates sibling filenames and writers, not
  manifest fields, and exempts `lib.rs` and `dev-runner.cjs` (6).
- `ArchUnitEgressTest` covers `io.justsearch.aibackend.{local,backend}..` only (9).

## Pacing, sessions, extraction

- `ForegroundLoad` is fed only by the gRPC `ForegroundLoadInterceptor`
  (`KnowledgeServer.java:166,1825`) (8).
- `IndexingPacing` is a duty cycle with `MAX_DEBT_MS = 2_000`, not a pause; `pace()` sleeps
  before a batch is submitted (`EmbeddingBackfillOps` `checkInterrupt()`) and bounds nothing
  already queued (4).
- One thread produces encoder batches per stage: `IndexingLoop` runs on the single
  `indexing-loop` thread (`IndexingLoop.java:570-579`) and `BackfillScheduler.runIdleCycle`
  processes backfill batches sequentially (`BackfillScheduler.java:200-260`).
- `NativeSessionHandle` serializes GPU `session.run()` with `new Semaphore(1)`
  (`NativeSessionHandle.java:120`, acquired at `:230-260`, also taken by `releaseGpu()` at
  `:264-300`); it is **unfair**, so a waiting caller can lose the race to the next submit. CPU
  sessions take no semaphore. `EmbeddingService` is documented thread-safe for concurrent
  `embed()` calls; serialization happens one layer down (4).
- `RoutingExtractionSandbox.PROCESS_KINDS = {pdf, office, archive, image, binary}`; text,
  markdown, code and CSV/JSON parse in-process; `ExtractionSandboxFactory` has an in-process
  fallback (6).

## Shutdown, updater, stores

- `HeadShutdownCoordinator` already owns the Head's ordered close; `POST /api/lifecycle/shutdown`
  is its route (`LifecycleApiModule.java:25,36`), called by the shell (`lib.rs:218,235`) (7.3).
- The updater's `run_install_now` (`updater.rs:369-388`) stages the download, then
  `prepare_head` (`:445`, `:965-967`) posts to `/api/upgrade/prepare` through `post_head`
  (`:1127-1151`), which fails with "Backend is unavailable" when no port or session token is
  bound. A missing child witness (`child_pid()` `None`, `lib.rs:202-208`) ends in `Cancelled` or
  `RepairRequired` (`updater.rs:488-505`); receipt validation failures do the same (`:506-556`);
  `launch_installer` (`:581-586`) is reached only after `HeadStopped`, which needs both receipts.
  There is no no-child or timeout branch that launches the installer (7.3).
- Update state is shell-owned: `appUpdateState.ts:1-7` goes through Tauri `invoke`, and
  `app_update_status` (`updater.rs:303-306`) returns in-memory coordinator state, so the Settings
  action is reachable with the backend down (7.3).
- `SqliteSchema.java:56-59`: the `jobs` table is `PRIMARY KEY (path)` with a per-path
  `attempts` counter and no operation row (7.5).
- `core.head-log` and `core.worker-log` are registered diagnostic channels
  (`CoreSurfaceCatalog.java:148-149,703`) and a surface altitude derives from the former (10).
- `TracingBootstrap.java:151` reads `OTEL_EXPORTER_OTLP_ENDPOINT` (9). `lib.rs:968-974` emits
  `justsearch://backend-restart` on an instance-id change; `backendRestart.ts` consumes it (7.1).

## Trust boundary and product claims (review five)

- ADR-0046 (`docs/decisions/0046-*.md:5,42-52,72,100-104`): the trust boundary is the
  same-user native process; the per-boot token is handed to external MCP clients through the
  bootstrap route and "any same-user native process could" read it from the runtime manifest.
  So the token distinguishes no role inside the boundary (3.4, 7.1, 8).
- `README.md:26,93,202`: "your files stay on your device; only the model's answer leaves",
  "Your documents never leave the machine - only the agent's", "Nothing leaves your machine";
  the MCP reference's primary tool returns assembled passages to an external agent (9, 18).

## Docs the sweep must correct

- `docs/explanation/19-module-architecture.md` lists `app-search` and `app-indexing` modules that
  do not exist and places the gRPC services in `indexer-worker` when they live in
  `worker-services`; the sweep rewrites it with section 3.2's rings.
- `WorkerSignalBus` lives in `worker-core/.../coordination/`, not `ipc-common`.
- `dead-code-audit` enumerates modules in its `build.gradle.kts`; `app-engine` and the platform
  module are added there or rules pass vacuously.
- "Head never touches Lucene" appears in `CLAUDE.md`, `AGENTS.md`, the subagent baseline brief,
  skills and postmortems; the superseding ADR keeps ADR-0001/0002's probes.

## Sequencing (2026-09-07, section 17)

- `WholeProgramDeadCodeTest` (`modules/dead-code-audit/src/test/java/io/justsearch/deadcode/WholeProgramDeadCodeTest.java:27-50,109`)
  is a closed-world dead-class ratchet whose committed baseline is
  `modules/dead-code-audit/archunit_store/`; a new unreferenced class fails it. So a wire left
  compiled but unused after the composition swap is red (17.4).
- `adr-0002-grpc-present` (`governance/adr-probes.v1.json:37-45`) is `kind: grep-present` on
  `libs\.grpc|io\.grpc` under `modules`; 102 Java files import `io.grpc` today. Deleting the
  wire without retiring the probe in the same change is red (17.4).
- A model change today is a parity refusal, not a generation transition:
  `IndexMetadataParityGuard` (`adapters-lucene/.../runtime/IndexMetadataParityGuard.java:150-167`)
  throws `schemaMismatch()` when `ParityDiagnostics.requiresRebuild(diffs)`; the user's remedy
  is `core.bulk-reindex` (`CoreOperationCatalog.java:73`), which is
  `IndexingService.reindexWatchedRoots(force)` (`app-api/.../IndexingService.java:177-201`), an
  in-place rescan through the normal pipeline. `IndexFingerprint`
  (`adapters-lucene/.../commit/IndexFingerprint.java:219-247`) already records embedding, SPLADE
  and NER model identity plus chunking, so it is the seed of the representation-generation
  identity (17.4). Generational directories and an atomic activation swap already exist (next
  section); no journal, no live activation and no cursor pinning do. (The first version of
  this bullet said none existed; corrected 2026-09-07.)

## Derisk B to D2 (2026-09-07, section 17.9)

Three read-only audits; every citation below was re-read by the orchestrator.

### Stage B: lifecycle

- Tauri signals backend death only before the port is bound (`lib.rs:863-877`: the stdout
  reader sets `spawn_error` when the pipe closes with no port) and has no crash or hang
  supervision; `restart_headless_backend` (`lib.rs:921`) is the requested restart. The
  supervisor contract and the restart loop are new (7.1).
- `InferenceLifecycleManager.close()` (`app-inference/.../InferenceLifecycleManager.java:1339-1342`)
  calls `serverOps.stopLlamaServer()` unconditionally; the by-reason branch of 7.3 step 6 is new.
  Adoption today is by port and HTTP shape only, with no process handle and no identity or
  config comparison (7.2).
- `check-runtime-manifest-closure.mjs:64-80` lists `scripts/dev/dev-runner.cjs` (`:73`) and
  `modules/shell/src-tauri/src/lib.rs` (`:80`) in `SKIP_PATHS`, so the supervisor file's
  writers are exempt from the check as it stands (7.1).
- The only `wal_checkpoint` is `checkpointForUpgrade()`
  (`indexer-worker/.../queue/SqliteJobQueue.java:2179-2184`); the ordinary close does not
  checkpoint (7.3 step 7).
- `scripts/dev/test-dev-runner-death-observability.mjs` exists but no runner invokes it, and it
  asserts nothing about the lease or run id (17.3 row B).
- `WorkerSpawner.java:550` adds `-XX:+UseCompactObjectHeaders` to the Worker; the dev-runner
  (`dev-runner.cjs:731,1728`) drops `TieredStopAtLevel=1` when an AOT cache is present. Three
  spawn sites today, not two (8).
- Child identity by PID plus start instant is an existing pattern (`AppInstanceLock`,
  `RuntimeManifestPublisher`); the extraction child already watches its parent (7.2).

### Stage C: context, resources, operations

- The provenance seed is `ActionEvent.originator()` and `.transport()`
  (`app-observability/.../ledger/ActionEvent.java:41-44`); `DurableGrantStore` is the only
  existing slot. The ingestion ledger and request logging carry no provenance field (3.4).
- The `IndexingService` port (`app-api/.../IndexingService.java`) has 33 methods and no
  context argument; `Query.context` is an occupied map. The engine context is a new parameter
  (17.3 row C1).
- Bare `CompletableFuture.supplyAsync`/`runAsync` on the common pool: `KnowledgeServer.java:1009`,
  `HeadlessApp.java:862`, `DocumentService.java:74`, `GplJobCoordinator.java:208`,
  `LlamaServerOps.java:1039`, `OnlineModeOps.java:214,251,395,706` and one more, ten in all;
  58 executor construction sites (`Executors.new*` / `new ThreadPoolExecutor`) with no registry
  and no queue bound (8).
- `ModelSessionPolicy.java:104` sizes the ORT arena from GPU VRAM; it bounds device memory,
  not commit charge (8). NVML is read only in `gpu-bridge` (`GpuCapabilitiesService`), which
  `indexer-worker` does not depend on today (7.4).
- `OperationInvocationRequest.idempotencyKey` (`OperationInvocationRequest.java:15-17`) is on
  the wire and read by nothing (7.5).
- `IndexingLoop.java:705-722`: commit, then `journal.drainPending()`; the journal's commit
  sequence number is the precedent for the completion-order stamp (7.5).
- `governance/store-recoverability.v1.json:575` classifies `jobs.db` as `DERIVED`; the
  operations table makes that wrong (17.3 row C2).

### Stage D: reconfigure, generations, readiness, request paths

- `IndexGenerationManager.java:86-99`: generational index directories under `state.json` with
  promote and rollback. `KnowledgeServerBootstrap.java:764`: `BLUE_GREEN_MIGRATE` is the
  default strategy. `ops/KnowledgeServerMigrationOps.java:258-266`: the cutover promotes the
  building generation, then restarts the Worker ("Restarting worker to open new active
  generation"). No journal, no replay, no live activation (7.4, 17.4).
- `EnvRegistry.java:278-280` and `ResolvedConfigBuilder.java:1561`:
  `index.migration.cutover.max_failed_jobs` defaults to -1 (unlimited) (7.4, 17.7).
- `KnowledgeServer.java:645-651`: a second runtime over the same directory leaked a
  `Directory` plus `SearcherManager` holding Windows handles for the Worker's lifetime
  (tempdoc 915 B5); `swapRuntime` (`KnowledgeServer.java:1236`) is close-then-open. Beside-mode
  compose for the index runtime means a second generation, never a second reader (7.4).
- `SearcherManager` is acquired and released per call and `searchAfter` (`ReadPathOps.java:343`)
  is stateless; no reader outlives a request, so cursor pinning is new (7.4, 4).
- Two readiness representations: `LifecycleSnapshotV1` (`app-api/.../lifecycle/LifecycleSnapshotV1.java:13`,
  three slots) and `ReadinessEnvelopeView` (`app-api/.../status/ReadinessEnvelopeView.java:12`,
  ten dimensions). No per-component start deadline exists (7.6, 17.7).
- `InferenceLifecycleManager.applyConfig` restarts with rollback; the `InferenceSurface` is
  `AutoCloseable` and has `releaseGpu` (7.4, 5).
- `IndexSchema.ephemeral()` (`IndexSchema.java:100-101`) builds at an auto-temp path that
  `RuntimeSession.java:646-688` deletes on close; the three SQLite stores
  (`SqliteJobQueue.java:244`, `SqliteDocumentIdentityStore.java:50`,
  `SqlitePathResolutionStore.java:68`) open a file path only (10, 17.3 row D2).
- `/api/debug/state`, `/infra/capabilities` and `RegistrySnapshotExporter` each carry a slice
  of runtime state; none lists components (10).

## Re-verification at `76871d924` (2026-09-07, PR 0 start)

Every citation above was re-opened at `main` `76871d924` (the PR 0 base) by a read-only audit whose
load-bearing rows the orchestrator spot-checked. Facts hold unless listed here.

**Moved citations (fact unchanged):**

- `NativeSessionHandle.java`: `new Semaphore(1)` is at `:116`; `acquire()` takes it at `:285`
  (method at `:271`); `releaseGpu()` at `:326` takes it at `:331`. These were off at `b96cd999`
  too (the file did not change).
- `SqliteJobQueue.java`: `checkpointForUpgrade()` is at `:2200-2213` (`PRAGMA wal_checkpoint(FULL)`
  at `:2205`), still the only checkpoint.
- `DocumentService.java`: the bare `supplyAsync` is at `:85` (a default method was inserted above).
- `IndexingLoop.java`: `commitOps.commitAndTrack` at `:712`, `journal.drainPending()` at `:723`.
- `contracts/wire/indexing.proto` is `modules/ipc-common/src/main/proto/indexing.proto`; the count
  is unchanged (10 + 38 + 1 = 49).

**Changed facts:**

- `CoreSurfaceCatalog.java:148-149` registers only `core.head-log`
  (`new DiagnosticChannelRef("core.head-log")`). No `core.worker-log` channel is registered
  anywhere; the name occurs only as a Javadoc example (`DiagnosticChannelRef.java:11`) and in FE
  test fixtures. The section 6 collapse row is therefore one registered channel renamed to
  `core.engine-log` plus the Worker log stream folded into it, and the surface altitude derives
  from `core.head-log` alone. Wrong at `b96cd999` as well.
- 91 Java files under `modules` import `io.grpc` (`git grep -l "import io.grpc" -- "modules/**/*.java"`),
  not 102; 90 at `b96cd999`. The `adr-0002-grpc-present` probe conclusion (17.4) is unaffected.
- Launch flags after PR 0 (17.2): both Head spawn sites carry
  `-XX:+UseSerialGC -XX:MetaspaceSize=128m -XX:-UsePerfData`, no `TieredStopAtLevel`, the same set
  with or without the AOT cache (`lib.rs` spawn block; `dev-runner.cjs` `buildHeadJavaOpts`, pinned
  by `scripts/dev/test-dev-runner-head-java-opts.mjs`). `WorkerSpawner.java:550` still adds
  `-XX:+UseCompactObjectHeaders` to the Worker; three spawn sites remain until stage A.

**Not re-derived** (no line citation; stage C1/D1 re-verify at their start): the 33-method count of
`IndexingService`, the ten bare common-pool sites and 58 executor construction sites, the ten
dimensions of `ReadinessEnvelopeView`.

**Found while drafting the stage A checklist (`stages/A.md`, 2026-09-07):**

- `adr-0002-grpc-present` (`governance/adr-probes.v1.json:37-48`) carries `"include": [".kts"]`: it greps
  `libs.grpc|io.grpc` in build files only, so it reds when the grpc dependency declarations
  leave `modules/*/build.gradle.kts` (checklist item A14), not when Java imports go. 17.4 holds
  under that condition.
- The `RemoteKnowledgeClient` construction site is `KnowledgeServerBootstrap.java:291` (`MainSignalBus`
  at `:253`, `WorkerSpawner` at `:256`, inside `start()` at `:213`); 917 cited `:261`.
- `WorkerProcessManager` has 21 test consumers, not 20: 917 omitted
  `system-tests/src/systemTest/.../process/FormatCapabilityMatrixE2ETest.java`.
- Two distinct `GrpcCircuitBreaker` classes exist, `ipc-common/.../ipc/grpc/GrpcCircuitBreaker.java`
  and `app-services/.../worker/GrpcCircuitBreaker.java`, each with its own test.
- `check-readiness-reason-codes` (`scripts/ci/check-readiness-reason-codes.mjs:22-25`) also checks the
  producer direction: every `LifecycleReasonCode` member needs a `src/main` referencer outside the
  enum. Several `WORKER_*` members are referenced only from classes stage A deletes, so stage A owes
  a holding action (A17) until the D1 re-cut.
- `main_gpu_active` / `energy_reduced` have 7 read sites, not 6 (`BgeM3BackfillOps.java:346` added).

**Writer-recovery correction (2026-09-08, candidate above `6f38df7e5`):**

- `RuntimeSession.java:255` owns terminal-writer notification and retirement;
  `:283` routes the initial and resumed NRT thread's known closed-writer failures.
  `KnowledgeServer.publishIngestLifecycle` (`:346`) binds before publishing each
  writable runtime, including boot replay before app-services construction.
- `HeadlessApp.java:925,1159,1462` binds the fatal action through the complete
  shutdown sequence. `EngineShutdownSequence.java:183,198,237` arbitrates fatal
  exit with cooperative shutdown and finalizes the code before the upgrade receipt.
- `KnowledgeServer.close` (`:2202`) awaits the existing deferred-init future's
  completion; the former five-second abandonment was not native quiescence.
  These are source facts. Execution evidence and remaining limitations are in
  `evidence/B/writer-recovery-investigation.md`.

## Stage C re-grounding (2026-09-09, at `be47faa40`)

Corrections to the bullets above found by the code-verified pass that re-grounded `stages/C1.md`
and `stages/C2.md` at the stage-B head. Each replaces the earlier sentence it names; the earlier
text is left in place as history.

- **Common-pool sites (Stage C bullet): twelve, not ten, and a different set.** Bare
  `CompletableFuture.supplyAsync/runAsync` (no executor argument) in `modules/*/src/main`:
  `DocumentService.java:85`, `LlamaServerOps.java:1276`, `OnlineModeOps.java:214`,
  `GplJobCoordinator.java:209`, `RemoteDocumentService.java:89,137,181,232,296,552`,
  `KnowledgeServer.java:1070`, `HeadlessApp.java:59`. `OnlineModeOps.java:251,395,712` pass
  `vduExecutor` and are not bare. `ForkJoinPool.commonPool()`, `parallelStream()`, `.parallel()`,
  `delayedExecutor` and every executor-less `*Async` combinator have zero occurrences, so a rule
  keyed on the symbol passes vacuously (C1-6 keys on arity).
- **Executors: 56 construction sites, not 58**, all through `Executors.new*`: 41
  `newSingleThreadScheduledExecutor`, 8 `newSingleThreadExecutor`, 4 virtual-thread, 2 cached
  (`OnlineModeOps.java:96`, `PersistentExtractionSandbox.java:172`), 1 fixed (`PdfOcrEngine.java:221`).
  Forty-nine already have a thread count of one; none has a bounded queue (8).
- **`ForegroundLoad`'s one producer** is `ForegroundLoadGate` (`app-engine`) at
  `EngineKnowledgeClient.executeSearchRpc` (`:340-354`, wrap `:351`), built at `EngineRoot.java:210`,
  keyed on a ten-label set (`ForegroundLoadGate.java:85-96`); the gauge is owned by
  `KnowledgeServer.java:166-167`; rule 6b (`LayeringEnforcementTest.java:227-247`) lets only
  `io.justsearch.app.engine..`, `io.justsearch.indexerworker..` and `io.justsearch.adapters..`
  name the type. The single-wrap guard (`:48-53`) is prose (8, C1-10).
- **The admission filter sees the search family.** `POST /api/knowledge/search` and the chat,
  retrieve-context, match-citations, folders, folder-files and ingest routes
  (`KnowledgeRoutes.java:27,32-46`) and `POST /mcp` (`LocalApiServer.java:649`) pass
  `ApiSecurityFilters.setupOperationAdmission` (`:158-190`); it exempts GET/OPTIONS (`:163`) and
  `/api/upgrade/*` (`:164`, B6). The foreground family's only GET is `/api/knowledge/suggest`
  (`:33`). An earlier draft said searches were GETs; they are not (8).
- **No "child's module" exists for the parser confinement**: child, router, in-process sandbox
  and Tika parsers share `modules/worker-services/.../indexerworker/extract/`, and
  `app-services/vdu/PdfImageRenderer.java:5-8` (`VduProcessor.java:201`) parses PDF in the Engine
  with a first-class `pdfbox` dependency (`app-services/build.gradle.kts:41`) (6).
- **The in-process fallback is three things**: the operator mode `Mode.IN_PROCESS`
  (`ExtractionSandboxFactory.java:24-28,126`, env at `modules/worker-services/.../server/DefaultWorkerAppServices.java:513,518`),
  the silent startup-probe fallback (`:539-556`, probe `ExtractionSandboxFactory.java:180-220`),
  and the `IndexingLoop` constructor default (`:350-352`, a test seam). Only the probe fallback is
  what 6 deletes (6, C1-14).
- **`DurableGrantStore` has no provenance field**; its keys carry `SourceTier` (`:71,74`).
  `InvocationProvenance` (`app-agent-api/.../registry/InvocationProvenance.java:54-60`) is a live
  invocation-side provenance record with 25 referencing files (3.4, C1-1).
- **`IndexingService`**: 33 instance methods (4 abstract, 29 `default` throwing), the Null Object
  at `:527-609`, one implementor (`KnowledgeClient.java:100`), 40 referencing files.
  `SearchPort.search(Query)` is single-argument (`SearchPort.java:13`); `Query.context`
  (`Query.java:20`) and `SearchRequest.Context` (`SearchRequest.java:76`) are both translator
  metadata with one reader (`SearchServiceImpl.java:68`) (3.4, C1-2/C1-3).
- **`ApiErrorCode` parity exists under another name**: `ErrorMessagePropertiesContractTest.java:24-31`
  and `ErrorCatalogJsonArtifactTest.java:28-38`; the enum has 130 members; the doc at
  `ApiErrorCode.java:12-17` cites a renamed test and a deleted file (C1-8).
- **The only `wal_checkpoint` is now `SqliteJobQueue.checkpointWal()` (`:2224`, pragma `:2229`),
  called from `close()` at `:2137` and by the upgrade barrier** (B5 renamed `checkpointForUpgrade`;
  the two earlier bullets naming it are history) (7.3 step 7).
- **The journal has no commit sequence number.** `CommitOps.commit()` discards
  `IndexWriter.commit()`'s `long` (`:110`), commit user data is a random UUID (`:89-90`),
  `commitAndTrack` returns void (`:166`); the only sequence number is the in-memory NRT watermark
  (`NrtReopenStats.java:34`), reset per session. `IndexingLoop`'s three commit-then-`drainPending()`
  sites (`:655/:660`, `:713/:723`, `:812/:816`) are effect-before-record; the fail-closed
  precedent is `SqliteJobQueue.putSwitchBuffer` (`:330-333`). The earlier bullet calling the
  sequence number "the precedent for the completion-order stamp" is withdrawn (7.5, C2-2).
- **`OperationInvocationRequest.idempotencyKey`** (`:28`, javadoc `:15-17`) is an HTTP body field,
  not a proto field, dropped at `OperationsController.java:136` before dispatch (`:177`); zero
  `.idempotencyKey()` call sites in code (7.6, C2-3).
- **`jobs.db` in the recoverability register**: row `:601-657`, `owner: WORKER` `:609`,
  `recoverability: DERIVED` `:617`, `currentVersion: 12` `:620` while `SqliteSchema.TARGET_VERSION`
  is 13 (`:37`) — a drift no gate compares (C1-4 corrects it). The register already carries
  `MIXED` (`entity-clusters`, `:747`) and six `EPHEMERAL` rows; only the six `catalogDirName` rows
  are bound to the two-value Java enum (`check-store-recoverability.mjs:29,94-103`).
- **The installed updater's closed-set rule** (`updater.rs:1003-1059`, register embedded at
  `:31-32`): all 44 rows compared, count equality (`:1031-1033`), identity equality of
  `owner`/`role`/`reconciliationStrategy` (`:1041-1049`), installed `currentVersion` must be
  readable (`:1050-1058`); one Rust test (`:2281-2300`). The release table is emitted by
  `scripts/release/app-release-assets.mjs:77-102`. Consequence recorded in design section 0
  (2026-09-09): no store identity changes in lane F (C2-1).
- **The cutover restarts the Engine, not the Worker** (B15): `CutoverContext.requestedRestartAction`
  (`KnowledgeServerMigrationOps.java:74`, fired `:274-276`) → `EngineRoot.requestRestart`
  `:219-224` → `HeadlessApp.localRestartAction` `:1486-1531` → exit 4. The Stage D bullet
  "restarts the Worker" is history (7.4, C2-10).
- **`OperationHistoryStore`** (`app-observability/.../operations/OperationHistoryStore.java:28`,
  cap 200 `:31`, swap javadoc `:23-26`), routes `ResourceApiModule.java:475-476`, resource
  `core.operation-history` (`OperationHistoryResourceCatalog.java:50-90`), proto
  `contracts/wire/operation_history.proto` whose `OperationOutcome` lacks `UNDONE` (`:85-89`);
  no row in `governance/operation-surfaces.v1.json` covers it (7.6, C2-4).
- **`POST /api/settings/v2`** has a failure error-code channel (`SettingsController.java:106-109,133,150`)
  and no revision on success (`:146`; `SettingsV2.java:11-16`); `UiModeIntent(clientId, sequence)`
  (`:261`) orders `ui.mode` only (7.4, C2-6).
- **Shutdown has ten ordered steps** (`HeadlessApp.orderedShutdownSteps` `:1332-1333`, names at
  `:1347,1353,1359,1367,1373,1379,1390,1396,1402,1408`); step 1 is `"operation-admission"`
  (`:1352-1357`, `freezeAdmission` `:1355`); `INDEX_HALF_STEP` at `:1390` closes the queue; no
  checkpoint step exists (7.3, C2-7).
- **`WORKER_RESTART_EXHAUSTED` was retired by B14** (`03c4e513b`); `engine.restart_exhausted` is
  host-derived (`readiness-reason-codes.v1.json:55-57`, `readinessNotice.ts:77`) (7.6).
- **`EngineExit`** (`app-engine/.../EngineExit.java`): 0 OK, 1 fatal-or-uncaught (transient),
  2 data-dir locked, 3 OOM, 4 `REQUESTED_RESTART`; drift tests `EngineSupervisionPolicyTest.exitTableMatchesEngineExit`
  `:168`, `contract.mjs:88-124`, `EngineExitTest.everyExitSiteUsesTheTable` `:78`; runtime
  readers `supervisor.rs:33,195,262-263` and `engine-supervisor.cjs:95,115,125` (7.1).
- **Stage E instruments (2026-09-09 inventory):** `analyze-head-run.cjs` filters roles `head`/`worker` (`:67,71,74`) while `head-rss-sampler.ps1` emits `engine` (`:18`), so its working-set section is blank for a merged run; `head-flag-run.sh:76` pins `JUSTSEARCH_HEAD_HEAP=512m` while the packaged Engine runs `-Xmx2g` (`lib.rs:784`) and the dev-runner sets no `-Xmx`; the safepoint extractor at `analyze-head-run.cjs:46-48` is the only one in `scripts/`; `encoder-latency-probe.sh` records no `ms` for `query-understanding` and `dense-retrieval`; the `engine` supervision policy (`supervision-contract.v1.json:162-172`) is read by both supervisors from the file (`engine-supervisor.cjs:82-111`, `supervisor.rs:239-291`) and its hang fields are declared placeholders for E; `head-rss-sampler.ps1:13-18` samples only `java.exe` with `HeadlessApp`; `scifact/perf-gate.json` pins no perf baseline; `sandbox-coverage.v1.json:65` registers `upgrade-dead-engine-recovery` (16).
- **Stage D1 facts (2026-09-09 inventory):** `IndexGenerationManager` (`worker-core/.../index/IndexGenerationManager.java`) keeps the active/building/previous pointers as strings in `state.json` v2 (`:86-98`); promote `:508-538`, rollback `:551-579`, atomic `writeState` `:923-953`; deletion only through `pruneMarkedForDeletionBestEffort` `:1004-1040`, whose sole caller is the `index_gc` operation (`MigrationControlOps.java:211`). The cutover (`KnowledgeServerMigrationOps.java:119-291`) promotes at `:268`, preserves evidence at `:273` and fires the requested restart at `:274-276` (B15); the failed-unit gate `:195-214` is disabled at the shipped `-1` (`ResolvedConfigBuilder.java:1503`, second hard-coded default `KnowledgeServer.java:202`) and proceeds with 0 on an unreadable count (`:198-202`). `swapRuntime` is `KnowledgeServer.java:1290-1313` (close-then-open; sole caller the admin reload); the same-directory handle-leak comment is at `:722-728` in `start()`. `IndexStatusOps.servingSearchGenerationId` `:608-611` reads the pointer. The switch buffer is written only in `SWITCHING` (`IngestSwitchBufferOps.java:63-76,96-107`) and drained by `drainSwitchBufferBestEffort` `:427-839`. No applied revision, no per-key apply attribute, no `governance/config-surface*.json` (the gate reads `config-lifecycle.v1.json`, 29 entries, plus a generated 303-row matrix). `ConfigStoreRebuilder.rebuild` swallows a failure and returns 200 (`:82-84`). `RestartRequiredException` has one throw site (`WorkerServiceImpl.java:57`). `model-registry.v2.json` declares `minVramBytes` only (install gate); `NvmlService` reads free VRAM in `gpu-bridge`, which the worker modules and `ort-common` cannot depend on. `LifecycleSnapshotV1` has three slots (`:56`); `ReadinessDimension` has ten (`:15-36`) and three composites (`StatusLifecycleHandler.java:1416-1427`); "essential ready" is implemented twice (`dev-runner.cjs:1343-1348`, `lib.rs:1175-1179`); no per-component start deadline exists (`EngineSupervisionPolicy.java:81`). `LifecycleReasonCode` has 55 members, 16 `WORKER_*`; the register's `:4` still says 44 (7.4, 7.6).
- **Stage D2 facts (2026-09-09 inventory):** no component map or profile exists; `EngineRoot` separates embedded (`:84`) from process (`forProcess` `:89,95,103`); `NativeSessionHandle.java:116` is an unfair `Semaphore(1)`; `BackfillScheduler.runIdleCycle()` runs on the `indexing-loop` thread (`IndexingLoop.java:572,669,731`) so there is one producer thread; the submit surface is `KnowledgeClient.submitBatch` `:488-524` and Lucene's sequence numbers are dropped at `WritePathOps.java:89,98-103,124-131` and `CommitOps.java:110`; `searchAfter` is `ReadPathOps.java:426`; readers are per call (`SearcherBridge.withSearcher` `:141-156`); `SearcherLifetimeManager` is absent; `CURSOR_INVALID` says "expired" (`errors.en.properties:43`); four SQLite stores take a file path (`SqliteJobQueue`, `SqliteDocumentIdentityStore`, `SqlitePathResolutionStore`, `EntityClusterStore`); `IndexSchema.ephemeral()` `:101` has no production caller; `InferenceSurface` (`:39,50-58`) and `SessionHandle.releaseGpu` (`:95`) are two types; `InferenceLifecycleManager.close()` is conditional on the reason since B5 (`:1379-1390`) (4, 10).
- **Stage F facts (2026-09-09 grep, excluding tempdocs and the design directory):** the ADRs, `01-system-overview.md`, `CLAUDE.md`/`AGENTS.md` invariant 1 and the ADR probes are already rewritten; the unlabelled residue is a live wire vocabulary (`LifecycleSnapshotV1.Components(head, worker, inference)` `:55`, `ReadinessDimension.WORKER_CONTROL_PLANE` `:15-21`, the 16 `WORKER_*` codes, `api-contract-map.md:53-54,65,94-165,200,241`, `duplicate_prevalence_production.py:446-451`, `runtime-state.v1.json:21`, 21 generated `requiredCapabilities: ["WORKER"]`) plus the module names; `02-process-coordination.md:29-394` is the largest remaining rewrite; `24-worker-inference-composition.md` is omitted from the design's list; `scripts/agent-analytics` has no Worker-log parser; `.claude/skills` are regenerated by `skills-sync.mjs` while `.agents/skills` are hand copies whose parity check proves only that they are committed; `00-program-overview.md` does not exist in the repo (17.3 row F, 19).
