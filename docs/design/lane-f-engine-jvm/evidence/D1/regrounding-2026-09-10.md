# D1 re-grounding against the C1 head (2026-09-10, read-only)

Status: investigation record, uncommitted working note. Branch `worktree-lane-F-A`, HEAD `4229f1091`
(99 commits after `stages/D1.md`'s base `be47faa40`). No code was changed; no stack was started.
Four read-only audits (index runtime, inference/encoders, readiness/config/supervision, C1
substrate plus C2/E expectations) were run by opus subagents with `file:line` citations at this
HEAD; the orchestrator spot-checked `KnowledgeServer.swapRuntime`, `ReadinessDimension` and
`LuceneExecutorRegistrations` directly. This file preserves the audits so the citations are not
lost with the session. The theorization that follows from them is in the parent session's summary
and, once written, in `stages/D1.md` §0.1 and design section 0.

Headline: every D1 mechanism still has its seed in the code, but eleven of the draft's premises no
longer hold and seven C1 decisions now bound D1's shape. The three that change the design rather
than a citation: (1) `swapRuntime` is close-then-open with an `appServices == null` window, so
D1-7 needs a new open-beside path, not a reuse; (2) `ReadinessDimension.composite` is one string
per dimension, so D1-12's `index`/`encoders` composites are not expressible and the existing
`Capability` abstraction is the seam; (3) there is no path today in which encoder set B exists
beside encoder set A: a model change is a restart plus parity refusal plus in-place rebuild, the
fingerprint model providers are process-wide statics, and `InferenceSurface` has one write site.

---

## Audit 1: index runtime (KnowledgeServer, RunningRuntime, migration ops, switch buffer, generations)

### 1. KnowledgeServer — swap, locks, close, publication

*KnowledgeServer = `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/KnowledgeServer.java` (2875 lines).*

- **Draft says** (§0.4): `swapRuntime :1290-1313`, drain-and-close `:1299-1307` then open `:1308`; sole non-test caller `POST /api/admin/runtime/reload :1215-1222`; handle-leak comment in `start()` at `:722-728`; Blue/Green open `:709-720`.
- **Code says**: `swapRuntime` is now **`:1336-1366`**, signature `public long swapRuntime(Supplier<RunningRuntime> opener, Duration drainTimeout, SwapReason reason)`. Order is unchanged and still **close-then-open**: `running.drainAndClose(drainTimeout, reason)` `:1350`, `opener.get()` `:1358`, `publishIngestLifecycle(fresh)` `:1359`, `this.searchLifecycle = fresh` `:1360`, `reconstructAppServicesAfterDeferredUpgrade()` `:1361`. Sole non-test caller is still the reload trigger, now `:1248-1254`.
- **New in C1 — the shared lock.** `runtimeSwapLock` (ReentrantLock) `:137`; `closeStarted` (volatile) `:138`; `closePrepared` `:134`; `closeLock` (intrinsic monitor object) `:136`. `swapRuntime` refuses on `closeStarted` twice — before the lock `:1343` and after acquiring it `:1346`. `close()` `:2233-…`: `synchronized (closeLock)` `:2235`, `closeStarted = true` `:2237`, `runtimeSwapLock.tryLock(5, SECONDS)` `:2240`; interrupt → `IOException("Interrupted waiting for active runtime replacement; server retained")` `:2244`; timeout → `IOException("Active runtime replacement prevented close; server retained for retry")` `:2247`.
- **What the bounded close retains.** `closePrepared` guards the pre-Lucene phase `:2250`, set at `:2416` — a retry resumes at the retained runtimes and never repeats native/producer teardown. Runtime close failures are collected (`ingestLifecycle` `:2421-2428`, `searchLifecycle` `:2430-2440`) and **rethrown** `:2442`, so the embedding service, `documentIdentityStore`, `pathResolutionStore`, the job queue, the index-root lock, the completion latch and `luceneExecutors.close()` (`:2491`) are all **skipped and retained**. `EngineRoot.close` propagates it as `IllegalStateException("In-process index close incomplete; owner retained for retry")` (`EngineRoot.java:310`, latch variant `:331`), and `EngineRoot.start` refuses `"EngineRoot cannot start while its previous server close is incomplete"` `:221`.
- **`head.lucene.nrt-close`** is registered in `modules/adapters-lucene/.../runtime/LuceneExecutorRegistrations.java:30-31` — `EngineExecutorSpec.virtual("head.lucene.nrt-close", BACKGROUND, 3)`, beside `head.lucene.commit-timer` (`:27-29`, maxInstances **3**) and the two search-fanout registrations `:33-38`. KnowledgeServer holds **one** `LuceneExecutorRegistrations` for its whole lifetime (`:335`, constructed `:347`, closed `:2491`); every runtime it opens shares it (`:1922`, `:1974`). Cap proved by `EngineLuceneGenerationCapacityTest.java:50-55` (4th open → `INSTANCE_LIMIT`).
- **`start()` Blue/Green.** Handle-leak comment is now `:724-729`; resumed-migration open `:731-741` — `blueReadOnly = buildReadOnlyRuntime(activeIndexPath).openReadOnly()` `:734`, `this.searchLifecycle = blueReadOnly` `:735`, `this.buildingIndexPath = genManager.resolveGenerationPathStrict(buildingGenId)` `:736`, `publishIngestLifecycle(buildIndexRuntime(buildingIndexPath, fpSupplier).withBuildState(BUILDING).open())` `:737-741`. Live (non-boot) migration starts open Green the same way at `:786-791`, `:842-848`, `:928-935`.
- **Publication barrier — there is none.** `searchLifecycle` `:160` and `ingestLifecycle` `:161` are **plain, non-volatile** fields. Readers reach them through the `InfraContext` suppliers `() -> this.searchLifecycle` / `() -> this.ingestLifecycle` (`:985-986`). `publishIngestLifecycle` `:370-375` only binds the terminal-writer fault source, then assigns. The only real barrier is `volatile WorkerAppServices appServices` `:164`, republished by `reconstructAppServicesAfterDeferredUpgrade` `:1289-1302`, which **sets `this.appServices = null` between closing the incumbent and starting the replacement** (`:1299`) — callers see unavailability during that window. `closePendingAppServices` is the OCR "one unpublished service slot" of design.md:423.
- **Methods D1-7 would call to open a second generation beside the serving one:** `private LuceneRuntimeBuilder buildIndexRuntime(Path indexPath, Supplier<Optional<String>> fingerprintSupplier)` `:1893` (`.withExecutorRegistrations(luceneExecutors)` `:1922`), `private LuceneRuntimeBuilder buildReadOnlyRuntime(Path indexPath)` `:1964` (`:1974`), path from `genManager.resolveGenerationPathStrict(genId)` `:736`, publish via `publishIngestLifecycle(LuceneRuntime)` `:370` + `this.searchLifecycle = …` + `reconstructAppServicesAfterDeferredUpgrade()` `:1289`. **`swapRuntime` itself is unusable for D1-7** — it closes before it opens and takes no generation path.
- **Consequence:** D1-7 needs a *new* method (open-beside + publish + drain-old), not a change to `swapRuntime`; it must publish under `runtimeSwapLock` with the same `closeStarted` refusals, or a promotion can race a shutdown that C1 just made retryable.

### 2. Drain / close on timeout — §11 question 1 answered

- **Code says**: **the close-anyway branch is gone.** `RunningRuntime.java`: `drainAndClose(Duration, SwapReason)` `:199-243`. On write-barrier timeout it logs `"…Retaining runtime resources for retry."` `:215-220`, fires `events.onDrainTimeout` `:220`, and **throws** `IllegalStateException("Lucene writes still active after drain deadline")` `:221`. Interrupt does the same `:224-228`. `close()` is reached only on the acquired path `:238`. Javadoc `:184-185`: "Resources stay owned until a later close attempt can drain them."
- `RuntimeSession.close` (`RuntimeSession.java:783-817`): `CLOSE_WAIT_NANOS = 5s` `:212`; separate `closeLock` `:213`; admission closed under the monitor `:785`; `commitOps.requestCommitTimerStop()` `:798`; generation-owner wait `:801-810` → `IllegalStateException("Lucene generation owners still active: " + taskOwners)` `:807`; only then `closeResources(deadlineNanos)` `:813`. `stopNrtUntil` `:746-779` retains the `CloseAttempt` unless the executor actually terminated `:766-772`. Constructor refusal retires its own opened resources `:558-570` (timer started before NRT `:560-561`). `retainTaskLifetime` `:728-742` is the per-fanout generation lease.
- **Answer to §11 Q1:** moot — the preservation rule already superseded close-anyway (design.md:339). During a live swap a drain timeout **already** keeps the old runtime serving and throws; D1-7 must treat that throw as "activation deferred, incumbent still published".

### 3. `runMigrationCutoverLoop`

*`modules/indexer-worker/.../server/ops/KnowledgeServerMigrationOps.java`.*

- Essentially unchanged. `runMigrationCutoverLoop(CutoverContext)` `:119-291`; promote `:267-268`; `preserveEvidenceBeforeRestart(context, promoted)` `:273` (body `:407-425`); **`context.requestedRestartAction().run()` `:276`, unconditional on the success path**, `return` `:277`. Gate `:203-214` (`maxFailedJobs >= 0 && failedJobs > maxFailedJobs`), on breach → `updateMigrationState(FAILED)` `:209-211` + `drainSwitchBufferAction().run()` `:212`; **`abandonBuildingGeneration` is never called in this file**. Unreadable count `:195-202` still WARN-and-proceed-with-0. `drainSwitchBufferBestEffort` `:427-840`; DELETE `:463-479` → `indexingCoordinator().deleteByIdAndChunks(payload)` `:466` then `jobQueue().deleteByExactPath(payload)` `:468`; DELETE_PREFIX `:483`; clear only when `allApplied` `:834-839`; VDU/embedding-drain path `:592-712`. No operation id / operation row anywhere in the file.
- **Consequence:** D1-7 deletes `:274-277`; D1-8 must add `abandonBuildingGeneration` to `:209-213` itself.

### 4. `IngestSwitchBufferOps` and the buffer schema

- `modules/worker-services/.../services/IngestSwitchBufferOps.java` — `isSwitching()` **`:62-75`**, condition `"SWITCHING".equalsIgnoreCase(s.migration_state())` `:71` only; `bufferDuringSwitchingOrThrow` `:95-106`; fail-closed `putSwitchBufferOrThrow` `:108-119`. Ops constants `:38-45` (UPSERT, DELETE, DELETE_PREFIX, PRUNE_PREFIX, VDU_UPDATE, VDU_MARK_FAILED, VDU_MARK_PROCESSING, VDU_RECOVER_PROCESSING).
- Table DDL now `SqliteSchema.java:107-114`: `key TEXT PRIMARY KEY, op TEXT NOT NULL, payload TEXT NOT NULL, last_updated INTEGER NOT NULL`; ordering index `:117-119`. **No operation-id column, no version column.** Writes are `INSERT OR REPLACE` (`SqliteQueueSwitchBufferOps.java:165`).
- **C1 provenance work changed the payloads, not the schema or the replay.** UPSERT is versioned JSON `{version:1, path, collection, originator, transport}` — `SwitchBufferUpsert.java:21-29`. SYNC_ROOT is `SwitchBufferSyncRoot(rootPath, force, provenance)` `:9`, encode `:18-26`, decode with legacy fallback `:29-60`. "Provenance" = `JobQueue.EnqueueProvenance(originator, transport)`, **not** a content hash. DELETE/DELETE_PREFIX payloads are **unversioned raw identity strings** (`:193-194`, `:183-186`).
- Coalescing is the primary key: UPSERT `:139` and DELETE `:194` share `switchBufferPathKey(...)`, so `INSERT OR REPLACE` gives last-op-wins per path.
- **Answer to §11 Q2:** the payload *is* sufficient to replay a removal by identity, and PK coalescing already resolves upsert-vs-delete ordering per path. The real gaps: (a) `isSwitching()` never opens during `MIGRATING`, so a removal in that window is applied to Blue only and is not journalled; (b) no operation id; (c) C2's operations live in a separate `operations.db`, so an operation-id key crosses store boundaries with no shared transaction.

### 5. `IndexGenerationManager`

*`modules/worker-core/.../index/IndexGenerationManager.java` (1133 lines). **C1 did not touch this file.***

- `State` record `:86-98`, 12 fields. `promoteBuildingGenerationToActive` `:508-538`, clears the brake `:533-535`. `rollbackToPreviousGeneration` `:551-579`, preserves it `:574-576`. `abandonBuildingGeneration` `:302-341` — one production caller, `KnowledgeServer.java:875` (schema-mismatch path), none in migration refusal. `pruneMarkedForDeletionBestEffort` `:1004-1040`, "not currently invoked by default" `:1000-1001`; reached only through `gcBestEffort` `:1095` ← `MigrationControlOps.java:211` (explicit `index_gc`). `writeState` `:923-951`, tmp → `.prev` → `ATOMIC_MOVE`.
- **Three directories protected**: `protectedGenerationIds` `:1126-1131` = `{active_generation, previous_generation, building_generation}`. Design's target of two has no enforcer.
- `:65` is `GENERATION_MANIFEST = ".justsearch-index-generation.json"`; the record is `GenerationManifest(format_version, generation_id, source, created_at_ms)` `:107-111`.

### 6. Served-generation reporting

*`modules/worker-services/.../services/IndexStatusOps.java` — untouched since `be47faa40`.*

- `servingSearchGenerationId` `:608-610` reads `stateSnapshot.active_generation()` — the **pointer**. `servingIngestGenerationId` `:612-620` branches on `ingestCountOps != searchCountOps` but still reports pointer strings. `activeDocCount` `:275-281` and `searchableDocCount` `:300-305` read the **open runtime**.
- `WorkerIngestService.java:176` still constructs a **second** `IndexGenerationManager` over the same base path (the other is `KnowledgeServer.java:640`); shared into `IndexStatusOps` at `:205`. **Extra constraint:** `ingestCountOps`/`searchCountOps` are captured as **values** at `WorkerIngestService` construction (`:182-185`), not suppliers — D1-9 cannot read the runtime's generation without suppliers or an appServices reconstruction on every activation.

### 7. `max_failed_jobs`

- `ResolvedConfigBuilder.java:1503` — `Math.max(-1, resolveInt("index.migration.cutover.max_failed_jobs", -1))`. `KnowledgeServer.java:210` — `private int migrationCutoverMaxFailedJobs = -1;` (draft said `:202`). Overwritten from resolved config at `:631`, passed to the cutover context at `:2588`. `EnvRegistry.java:279-281` declares key + env var + `LifecycleStage.PERMANENT`, **no default**.

### 8. The tests D1 inverts

- `EngineMigrationLifecycleTest.java` — `cutoverDoesNotChangeWhatThisProcessServesUntilItRestarts` `@Test :187`, method `:189`; precondition `:210-212`; final assertion `~:246-248`. Other tests: `:74`, `:250`, `:308`. Touched by C1 (`986962ec0`) — the fixture now carries `EngineContext`.
- `CutoverRestartEvidenceTest.java` — `:39-41` `restartFollowsVerifiedPromotionAndEvidence`, `:70-71` `thePromotedGenerationIsMarkedCleanAndTheMetricsAreFlushedBeforeTheRestart`, `:93-94` `aFlushThatThrowsDoesNotEscape`. Untouched.
- `EngineSupervisedRecoveryE2ETest.java` — `migration` still in `@ValueSource` `:24`; single parameterized test `:25`; harness exit asserted `0` at `:113`; migration branch asserts `output.contains("MIGRATION_PASS")` `:117-118`. The restart proof lives inside the harness's `MIGRATION_PASS` token, so rewriting it means rewriting the harness.
- Also present: `EngineMigrationRestartDispatchTest.java:22`, `MigrationRestartRequiredTest.java:38`, `MidMigrationCompatSurfaceTest.java:45`, `GreenCutoverEmbeddingFpVerifyTest.java:23`, `IndexGenerationManagerRestartTest.java:24`, `JobQueueMigrationTest.java:33`.

### 9. C1 constraints D1's live activation must respect that the draft never mentions

1. **Two executor registration bundles are owned per server, not per runtime.** `WorkerExecutorRegistrations` (`:334`, `:348`) and `LuceneExecutorRegistrations` (`:335`, `:347`). Every runtime shares the single Lucene bundle → **`head.lucene.commit-timer` and `head.lucene.nrt-close` cap concurrent generations at 3**. Serving Blue + writing Green already consume two. Instance slots are released only after *actual* executor exit.
2. **Generation-owner leases.** `RuntimeSession.retainTaskLifetime` `:728-742` rejects new leases once `closed` is set, and `close()` blocks on `taskOwners != 0` up to 5 s `:801-810`.
3. **Constructor refusal retires resources.** `RuntimeSession` `:558-570`. D1-7's "open beside" can fail cleanly, but it fails; the activation must have a defined refusal path.
4. **Retryable, non-atomic shutdown.** `closePrepared` `:134`/`:2416` and the retained-runtime rethrow `:2442`. `EngineRoot.start` refuses to replace an unresolved server (`EngineRoot.java:221`). The `closeStarted` checks at `:1343`/`:1346` are the existing mechanism and the new activation must use them.
5. **`activeIndexPath` is assigned once, at `:644`, and never updated after promotion.** The reload trigger's comment (`:1245-1247`) is now false post-cutover: `swapRuntime` at `:1250` would reopen the **old** active path. D1-7 must make `activeIndexPath` (and `InfraContext`'s copy, passed by value at `:991`) follow promotion.
6. **The appServices reconstruction blanks the surface.** `reconstructAppServicesAfterDeferredUpgrade` sets `appServices = null` `:1299`. D1-7's acceptance ("a search issued during the swap gets an answer from one generation or the other, never an error") is **not satisfiable through the current reconstruction path**.
7. **Test fixtures now require explicit registries.** `LuceneExecutorTestBase` (`modules/adapters-lucene/src/test/.../LuceneExecutorTestBase.java:8-23`).

### Draft claims that no longer hold (audit 1)

1. `swapRuntime :1290-1313` / reload caller `:1215-1222` / handle-leak comment `:722-728` / Blue-Green open `:709-720` → `:1336-1366`, `:1248-1254`, `:724-729`, `:731-741`.
2. §11 Q1 is not open; `drainAndClose`'s close-anyway branch is deleted (`RunningRuntime.java:215-221`).
3. `KnowledgeServer.java:202` → `:210`.
4. `requestedRestartAction` at `:274-276` → `:276`.
5. `drainSwitchBufferBestEffort :427-839` → `:427-840`.
6. `SqliteSchema.java:94-101` → `:107-114`.
7. `IngestSwitchBufferOps` gate/put/fail-closed → `:62-75`, `:95-106`, `:108-119`.
8. "the buffer's schema gains the operation id column" is understated — a real migration of a 4-column PK-coalesced table, pointing at a row in a *different* database.
9. `IndexGenerationManager.java:65` is the filename constant; the record is `:107-111`.
10. `abandonBuildingGeneration :302-341` already has one production caller (`KnowledgeServer.java:875`).
11. D1-7's plan to reuse `swapRuntime` — it closes before it opens and takes no generation argument.

### C1 decisions D1 must inherit (audit 1)

1. Preservation over completion (design.md:338-339). A failed drain is a deferred activation with the incumbent still published.
2. One shared `ReentrantLock` (`runtimeSwapLock :137`) serializes replacement and teardown; `closeStarted :138`; close has 5 s to acquire before teardown (design.md:342).
3. `closePrepared :134` marks the one-shot pre-Lucene phase; retries resume at the retained runtimes (design.md:340).
4. `head.lucene.nrt-close` is a registered virtual owner with 3 instances (design.md:341) — the hard ceiling on "open beside".
5. Only actual owner exit licenses resource release (`RuntimeSession.java:798-813`, `:746-779`).
6. Lucene constructor refusal retires its own opened resources (`RuntimeSession.java:558-570`).
7. `EngineRoot` propagates an incomplete close and refuses to start over an unresolved server (`EngineRoot.java:221`, `:310`, `:331`).
8. Native quiescence is D1 work (D1.md §0.1 2026-09-09).
9. C2 puts operations in `operations.db` while the switch buffer stays in `jobs.db` (design.md:270-271).

---

## Audit 2: inference / encoders (InferenceLifecycleManager, NativeSessionHandle, InferenceSurface, IndexFingerprint, device memory, model registry, encoder readiness, GpuSchedulingGauge)

### 1. InferenceLifecycleManager (`modules/app-inference/.../InferenceLifecycleManager.java`)

- All four §0.8 defects survive C1; only lines moved. `applyConfig(InferenceConfig, RestartPolicy, TransitionReason)` `:684-851`. Restart branch: `serverOps.stopLlamaServer()` `:783-787`, then `this.config = newConfig` `:789`, then the VRAM gate `:791-811` (`INSUFFICIENT_VRAM` at `:801-810` with **no** rollback), then `startLlamaServer()` `:817` / `waitForServerHealth()` `:818`. **`config = newConfig` precedes both the VRAM gate and the health check.** The no-restart branch assigns `this.config = newConfig` `:750` unconditionally — a second unvalidated write site.
- `applyConfigRollback` `:865-919`. Successful rollback returns `TransitionOutcome.failure(mapExceptionToFailure(cause), rollbackView)` `:903-905` (comment `:902`). Rollback's own VRAM refusal `:884-893`; catastrophic path `:910-917`. `:903` and `:916` are both `failure`, differing only in the view's phase — no distinguishable "rolled back to A" vs "left OFFLINE" signal.
- **`setStopServerOnClose` / reason-based stop is DONE (B5).** Field `:1451`, setter `:1462-1464`, `stopsServerOnClose()` `:1467`. `close()` `:1473-1499`. Sole production driver: `HeadAssembly.setStopGenerativeBackendOnClose` `:309-313` ← `HeadlessApp.java:1436`.
- **C1 registry bundle:** `InferenceExecutorRegistrations.java:13-86` — 9 registrations (`inference.http`, `.request.foreground/.background`, `.callback.foreground/.background`, `.stream-watchdog`, `.llama-health`, `.llama-recovery`, `.llama-exit`, `:36-52`), constructor rollback `:53-56`, `close()` reverse-order `:67-71`. Owned field `:103`, constructed `:155`, closed at `:1494` after `onlineOps.shutdown()`/`serverOps.shutdown()`/`httpClient.close()` `:1490-1493`, before `runner.runForceOffline` `:1495`. Construction-failure rollback `:235` + `closeAfterConstructionFailure` `:1515-1533`.
- **Reachability from the settings path: still NO.** `SettingsController.handleUpdateSettingsV2` `:104-153` → `settingsStore.save` `:137`, `rebuildConfigStore` `:138` (`ConfigStoreRebuilder.rebuild` swallows `RuntimeException` `:82-84`), and `chatEnabledChanged` `:142-145` bound at `LocalApiServer.java:174-184` to `runtimeReconciler().specChanged()` — a *spec nudge*. **The only production paths into `applyConfig`** are `OnlineAiServiceImpl:100` (`applyRuntimeOverrides` / `applyRuntimeOverridesAdmin` `:59-84`) and `:163` (`applyChatProfile`), reached from `BrainRuntimeServiceImpl.reloadInference` `:65` (op **`core.reload-inference`**, `CoreOperationCatalog.java:351,846`), `AdminInferenceReloadHandlers.java`, and `RuntimeActivationService.java:1130`.
- **B12 adoption by `declaredConfigHash`: exists and is on the restart path.** `LlamaServerOps.java:344-346` computes `ManagedLlamaConfigIdentity.declaredHash(cfg, rc, gpuLayers)` and calls `adoptManagedServerIfPresent(declaredConfigHash)` before launching; match at `:1004`; a mismatch **terminates** the child `:1008-1013`. Second reader: `ManagedChildReconciler.java:118`.
- **Consequence:** D1-4's three fixes become four (the no-restart branch `:750`). The B12 assertion is already structurally true at `:346`; the test is the only new work. `core.reload-inference` is an existing reconfigure precedent for the generative component the draft does not mention.

### 2. NativeSessionHandle (`modules/ort-common/.../NativeSessionHandle.java`, 847 lines)

- `close()` `:552-577`, `getCpuSession()` `:587-625`, `releaseGpu()` `:362-404`, `acquire()` `:272-…`, `acquireCpu()` `:511-514`.
- `releaseGpu` `:364` sets `gpuSessionReleasing = true`, `:367` `gpuInferenceSemaphore.acquireUninterruptibly()`, closes run options `:378` and the GPU session `:380-388`, releases `:395`. It **does** quiesce GPU calls.
- `close()` takes **no lock and no permit**: sets `closed = true` `:554`, then `closeGpuRunOptions()` `:556`, `gpu.close()` `:562`, `cpu.close()` `:571`. `closed` is read **nowhere else** (`:135`, `:553`, `:554` are the only occurrences). A close neither blocks in-flight native calls nor refuses new leases.
- **GPU permit acquisition is interruptible (C1).** `:289` `gpuInferenceSemaphore.acquire()` in try/catch `:291-296`; race `:298-308`; post-wait GPU-released recheck `:309-317` returns a **CPU** lease. Regression `NativeSessionHandleGpuWaiterCancellationTest.java`.
- **CPU recreation on failure:** `reportCpuSessionFailure` `:441-…` only sets flags; swap in `getCpuSession()` `:596-616` under `cpuSessionLock` — `old.close()` at `:606` **while other threads may still hold `old`**, because `acquireCpu()` `:511-514` hands out `new Lease(cpu, null, () -> {}, true, …)` with a **no-op release**.
- **Lease/active-call tracking: none.** `SessionHandle.Lease` `:179-185` is `(session, runOptions, release, isCpu, recorder)`. GPU leases carry `gpuInferenceSemaphore::release` (`:324`); CPU leases `() -> {}` (`:317`, `:513`). No counter, registry, phaser, per-lease id, or "retiring" state.
- The gap is *sanctioned* by `NativeSessionHandleConcurrentStressTest.java:250-252` ("may throw or return closed-session leases, both of which are tolerated") and `:171-173`.
- **Consequence:** `gpuInferenceSemaphore` is the only quiescence primitive, GPU-only and depth-1; any lifetime oracle must add CPU-side accounting from scratch. Tighten `:171-173,250-252`; do not delete the concurrency intent.

### 3. InferenceSurface / InferenceCompositionRoot / KnowledgeServer

- `InferenceSurface` `modules/indexer-worker/.../server/InferenceSurface.java:42-62` — record of `Optional<EmbeddingAssembly>, Optional<NerAssembly>, Optional<RerankerAssembly> reranker, Optional<RerankerAssembly> citation, Optional<SpladeAssembly>, Optional<BgeM3Assembly>, PolicySnapshot policies, List<SessionHandle> handles`, `AutoCloseable`; `close()` `:53-61` iterates `handles` swallowing per-handle `RuntimeException`.
- Six assemblies, five compose paths (embed and BGE-M3 mutually exclusive; citation CPU-only): `InferenceCompositionRoot.compose` `:117-167` (5-arg overload `:102-109`); roles `composeEmbeddingRole:171`, `composeNerRole:228`, `composeBgeM3Role:266`, `composeSpladeRole:311`, `composeRerankerRole:354`, `composeCitationRole` (`:161`). Selection `bgeM3Selected = "bge-m3".equalsIgnoreCase(cfg.ai().sparseModel())` `:129-130`.
- **Holding and close:** `KnowledgeServer.inferenceSurface` `:188` (`volatile`), assigned **once** at `:1432` inside `start()` (compose call `:1424-1431`, arbiter `() -> !signalBus.isMainGpuActive()`), read at `:1241`, closed at `:2364-2370` **after** the per-encoder closes (SPLADE `:2334-2340`, BGE-M3 `:2343-2349`, reranker `:2352-2359`), relying on close idempotency (`:2361-2363`). Per-encoder owner fields `:177-185` (`spladeEncoderInstance`, `bgeM3EncoderInstance`, `searchRerankerInstance`, `nerServiceInstance`, `citationScorerInstance`).
- **No "recompose without restart" path exists for the inference surface.** `this.inferenceSurface` has exactly one write site.
- **ArchUnit pins on `InferenceSurface`: none by name.** Covering rule is `LayeringEnforcementTest.onlyEngineAndWorkerMayDependOnWorkerInternals` `:243-263`. `@BuildContract` on `compose` names `"ClosurePropertyTest + InferenceSurfaceTest"` (`InferenceCompositionRoot.java:94-101`).
- **Consequence:** a `core`/`app-api` registry interface must not carry `InferenceSurface` or `*Assembly` types. D1-6's "recompose A on refusal" has no seam; `swapRuntime` (lock + `closeStarted` refusal + drain + re-publish + downstream reconstruct) is the shape to copy.

### 4. IndexFingerprint — D1-2's generation-bound set

- `modules/adapters-lucene/.../commit/IndexFingerprint.java`: `Inputs` `:213-224` — 11 fields: `catalogSchemaVersion`, `fields`, `analyzerFingerprint`, `vectorFormat`, `hnsw`, `chunking`, `contentPreviewMaxChars`, `analysis`, `embeddingModel`, `spladeModel`, `nerModel`. `COMMIT_META_KEY = "index_fingerprint"` `:101`; `COMMIT_META_INPUTS_KEY` `:110`; `MODEL_INPUT_KEYS = ["embedding_model_sha256","splade_model_sha256","ner_model_sha256"]` `:118-119`; `RENDERING_VERSION = "1"` `:124`. `compute` `:255`, `canonicalJson` `:267`, `differingInputs` `:374`.
- **There is no BGE-M3 input.** BGE-M3 reaches the fingerprint only indirectly (`installEffectiveVectorDimension` `:508`, and `spladeModel()` being `NOT_CONFIGURED`). Model digests come from **process-wide statics installed once**: `installModelFingerprintProviders` `:521-530`, accessors `:541,546,551`; sole production install site `KnowledgeServer.java:657-667` + `:674`.
- **`chunking` is not config.** `SsotCommitMetadataSource.java:191-195` builds it from `ChunkSplitter.DEFAULT_CHUNK_TOKENS(=500, :118)`, `DEFAULT_OVERLAP_TOKENS(=50, :121)`, `MIN_CHUNK_TOKENS`, `CHUNK_THRESHOLD_CHARS`, `ALGORITHM_VERSION(="v1", :100)`.
- Write: `SsotCommitMetadataSource.java:88-91` → `CommitOps.java:107`. Read: `IndexMetadataParityGuard.java:182,224`, `ParityDiagnostics.java`, `KnowledgeServer.java:2177`, `KnowledgeServerMigrationOps.java:342-343`, `IndexStatusOps.java:1156,1171,1224`.
- `IndexMetadataParityGuard.checkOnOpen()` `:140-167`: escape hatch `justsearch.index.parity.allow_mismatch` `:149-156,227-234`; `requiresRebuild(diffs)` `:163` → `throw schemaMismatch()` `:164` (`Reason.SCHEMA_MISMATCH`); else `IllegalStateException("Shard is read-only due to parity mismatch")` `:166`. Callers: `RuntimeSession.java:457`, `KnowledgeServer.java:714,759`.
- **Consequence:** (a) BGE-M3's digest is not generation-bound today; adding a 4th key bumps `RENDERING_VERSION` and invalidates every existing index. (b) Chunking cannot take an `applyScope` row without first becoming config. (c) Because providers are statics installed once, **an in-place inference recompose silently changes the expected fingerprint for a still-open index** unless re-installed under the same lock — a new hazard.

### 5. Free device memory and the `DeviceMemoryPort`

- `NvmlService.probe()` `:46`, free read `:189`; `GpuCapabilitiesService.snapshot()` `:36`/`:45`, `mergeEffective` `:108-167`; `GpuCapabilities.Effective.freeVramBytes()` `:78`.
- Direct edges: `app-inference/build.gradle.kts:5` (`implementation`), `app-services/build.gradle.kts:31` (**`api`**), `ui/build.gradle.kts:20`. None in `ort-common`, `worker-core`, `worker-services`, `indexer-worker`, `app-engine`, `app-api`, `core`.
- **The four exclusions are CONFIRMED; the word "only" is REFUTED**: `app-engine/build.gradle.kts:9` `implementation(project(":modules:app-services"))` plus the `api` edge puts `gpu-bridge` on **app-engine's compile classpath**. The composition root can already read free VRAM.
- ArchUnit: `GpuProbeAccessTest.java:46-55` (only `io.justsearch.gpu..` may depend on `NvmlService`); `VramDetectorAccessTest.java:37-49`. Both permit `GpuCapabilitiesService`.
- **Consequence:** the port can be a supplier bound at the root rather than a new `app-api` type; must go through `GpuCapabilitiesService`.

### 6. Model registry and session policy

- Registry: `modules/configuration/src/main/resources/ai/model-registry.v2.json`. `ModelPackage` record `:56-71`, `minVramBytes` at **`:63`**; `hasVramRequirement()` `:180-182`. **No runtime/footprint field.** One consumer: `InstallPlanner.java:211` (`SkipCause.HARDWARE`).
- **There is no JSON-schema file and no schema gate for the registry.** Only `ModelRegistryLoaderTest.java:12-18,20-34` (schemaVersion 2, exactly 8 packages), `InstallPlannerTest.java`, `ModelRegistryClasspathReachabilityTest.java`. Input to `scripts/codegen/gen-notices.mjs:31,152` → `regen-all --check --only notices`.
- `ModelSessionPolicy.Gpu(long arenaCapBytes, …)` at **`:112`**; `ModelSessionPolicyResolver.buildGpu` `:205-216` — `arenaCapBytes = CUDA ? gpuMemMb * BYTES_PER_MB : 0L` `:209-212`, per role `:105,119,133,147,161`. Defaults: `bgem3 3072` `:1179`, `embed 6144` `:1201`, `splade 4096` `:1273`, `ner 2048` `:1293`, `rerank 2048` `:1305`.
- `OnnxSessionCache.createCachedSession` `:118,134,153` caches an **on-disk optimized graph**, not `OrtSession` instances — a beside-compose allocates a second full set of device sessions.
- **Consequence:** D1-5's "the registry's schema test (find it)" is false. The arena-cap defaults are per-role config, not per-model registry data; the naive "declared footprint" is the sum of the candidate roles' arena caps.

### 7. The encoder start path and readiness

- Deferred init: `KnowledgeServer.startDeferredModelInitialization` `:1116-1124` opens `workerExecutors.deferredModelInit()` (`WorkerExecutorRegistrations.DEFERRED_MODEL_INIT = "index.deferred-model-init"` `:19`, accessor `:112`), future via `EngineFutures.supplyAsync` `:1123`; field `:199`.
- `initDeferredModels()` `:1374-1708`. Readiness is **`modelReadyLatch`** (`:295`, `CountDownLatch(1)`): counted down at `:1679` after **all** models are wired, `:1706` (`finally`), `:2566`. Consumers: `WorkerSearchService.awaitModelsReady` via `svc.wireModelReadyLatch(() -> modelReadyLatch)` `:1236`, migration enumerator `modelReadyLatch.await(120, SECONDS)` `:2711-2716`. Shutdown: `deferredModelExecutor.close()` then `deferredModelInit.join()` `:2268-2276`, before encoder/surface closes `:2334-2370`.
- **`evidence/pr0/after/restart-startup.txt` does not contain the 40 s figure**: it is `http_first_response_ms=2630 head_ready_ms=2630 worker_ready_ms=7631`. The ~40 s traces to `design.md:706,817`, `stages/B.md:402` (A13), `stages/C2.md:829`.
- **No readiness dimension names the encoders**; nearest `EMBEDDING` `:18` and `CHUNK_EMBEDDING` `:19`. **There is no `reloading` state anywhere in the codebase.**
- **Consequence:** `modelReadyLatch` is one-shot with three count-down sites and no reset; `ready → reloading → ready` cannot reuse it; `awaitModelsReady` would return immediately during a reload.

### 8. GpuSchedulingGauge and the late-connected-GPU fix

- `modules/core/.../scheduling/GpuSchedulingGauge.java` (108 lines): two `volatile boolean` fields `mainGpuActive` `:56`, `energyReduced` `:57`; `shouldYield` `:68-70` (OR); setters `:73-80`; getters `:83-98`. **No listener, no lock field, no event.** Producers: `InferenceWiring.java:56`, `EnergyStatePoller.java:127`.
- **The C1 late-connect fix is `InferenceWiring`**: `wireGpuStatusBroadcast(manager, Supplier<KnowledgeServerBootstrap>)` `:34-45`; `refreshGpuStatus` `:48-59` writes inside `synchronized (gauge)` `:54-58`. Registration `ServicePhase.java:187-190`; reconnect seeding `HeadAssembly.java:1348,1357-1358`.
- Consumers: `InProcessWorkerSignalBus.java:92-99`, `WorkerSignalBus.java:50,76-78`, `KnowledgeServer.java:1430,2109`, `BackfillScheduler.java:198-199,247,438,629`, `EmbeddingProviderLifecycle.java:169`, `EmbeddingBackfillOps.java:202-203`, `BgeM3BackfillOps.java:346`, `IndexingDocumentOps.java:256`, arbiter lambdas (`GpuArbiter.java:11`, `OrtSessionAssembler.java:65`, `SpladeEncoder.java:58`, `KnowledgeServer.java:1430`).
- **Consequence:** precedent for placement in `core`, not for lifecycle publication. `reloading` must be a separate `core` type; the arbiter is passed **into `compose` itself** (`:1430`), so a recompose must re-thread it.

### Draft claims that no longer hold (audit 2)

1. `applyConfig` `:650-824` / VRAM `:750-776` / `config = newConfig` `:755` / rollback `:867-871` → `:684-851`, `:791-811`, `:789`, `:865-919`.
2. Restart path also writes `config` before VRAM gate and health check; second write at `:750`. Three cases become four.
3. "every input of `IndexFingerprint.Inputs`" includes BGE-M3 — false.
4. Chunking parameters as generation-bound *config* — false; compile-time constants.
5. "`gpu-bridge` is a dependency of `app-inference`, `app-services` and `ui` only" — refuted for `app-engine`.
6. "the registry's schema test" — none exists.
7. `ModelPackage.java:21-22` / `ModelSessionPolicy.java:111-112` → `:63` and `:112`.
8. `swapRuntime` `:1290-1313` → `:1336-1366` with drain, lock and `closeStarted` refusal.
9. `NativeSessionHandle.close/getCpuSession` "552-605" → `:552-577`, `:587-625`.
10. Encoder 120 s sourced to `restart-startup.txt` — that file holds the index number.
11. `GpuSchedulingGauge` as a mode/state precedent — two booleans, no listener.

### C1 decisions D1 must inherit (audit 2)

1. One `InferenceLifecycleManager` per Head construction; config mutates in place; restarts never create another manager (C1.md:1316-1321). The generative backend is in-place by decision; D1-6 may not offer it `beside`.
2. The manager owns its registry bundle and closes it after collaborators; constructor failure rolls back (`InferenceExecutorRegistrations.java:53-56,67-71`, `InferenceLifecycleManager.java:1490-1495,235,1515-1533`).
3. GPU permit acquisition is interruptible; issued leases stay owned until native exit; cancellation never closes the session (C1.md:1580-1590; `:289-308`).
4. D1 acceptance must prove recompose and ordered shutdown cannot close a CPU or GPU session/run-options while an issued lease can use them, and cannot issue a new lease after retirement begins (D1.md:167-174).
5. Do not weaken the existing stress intent; tighten `NativeSessionHandleConcurrentStressTest.java:171-173,250-252`.
6. Late-connected GPU scheduling pattern: one listener over the live supplier, read-and-publish under the gauge lock, no second GPU state (`InferenceWiring.java:34-59`).
7. `index.deferred-model-init` is a dedicated singleton platform registration (`WorkerExecutorRegistrations.java:19,112`, `KnowledgeServer.java:1116-1124,2268-2276`).
8. `retained-state.v1.json:32-48`: `co-resident-encoders` cap 2 (count ceiling only), `attempted-configurations` cap 1 (none exists at C1), `representation-generations` cap 2 (rollback reconciliation owed).

---

## Audit 3: readiness, config-apply, supervision escalation

### 1. Readiness representations

- All three §0.11 claims hold, shifted +3 lines by `986962ec0`. `LifecycleSnapshotV1.java` / `ReadinessEnvelopeView.java` / `ReadinessDimension.java`: **zero commits** in the window.
- `LifecycleSnapshotV1.java:13-17` = `(schema_version, observed_at, Lifecycle, Components)`; `Components:56` = `(head, worker, inference)`, each `Component:64` = `(state, reason_code)`.
- `ReadinessEnvelopeView.java:12-16` = `(schemaVersion, observedAt, Map<String,ReadinessComponentView> components, Map<String,ReadinessCompositeView> composites)`.
- Ten dimensions, `ReadinessDimension.java`: `WORKER_CONTROL_PLANE:15`, `INDEX_SERVING:16`, `AI:17`, `EMBEDDING:18`, `CHUNK_EMBEDDING:19`, `VISUAL_TEXT_EXTRACTION:20`, `VISUAL_DOCUMENT_UNDERSTANDING:21`, `LAMBDAMART_MODEL:22`, `TELEMETRY:29`, `GPU:36`. Composites: `retrieval`, `aiFeatures`, `telemetry`.
- `StatusLifecycleHandler.java`: `computeLifecycleSnapshot()` **:1285-1356**; `buildReadinessEnvelope()` **:1395-1433**, composite assembly **:1419-1429**; from worker slot: `WORKER_CONTROL_PLANE` **:1553-1554**, `INDEX_SERVING` **:1561**; `AI` **:1629-1630** reads the inference slot. `/api/health` = `handleHealth():1140-1144`, whole body. `/api/status` flattens at `:699-701`, `readiness` at `:717`.
- **Three structural blockers for D1-12:**
  - **Composites are a one-to-one grouping**: `ReadinessDimension` carries a single `composite` string (`:14-22,29,36`, field `:39`), grouped at `:1419-1429`. `index = indexServing ∧ workerControlPlane` and `encoders = embedding ∧ chunkEmbedding` need dimensions in **two** composites at once. `INDEX_SERVING`/`WORKER_CONTROL_PLANE`/`CHUNK_EMBEDDING` are already `retrieval`.
  - **There is no `api` dimension.** The snapshot's `head` slot is a hardcoded `LIFECYCLE_STATE_READY` (`:1289-1290`).
  - **A third representation exists**: `RuntimeManifest`'s lifecycle from `LifecycleProjection.derive(workerCapability, inferenceCapability)` (`modules/app-services/.../lifecycle/LifecycleProjection.java:31`), published by `RuntimeManifestListenerWiring.java:80,107,130,154`. `computeLifecycleSnapshot` **prefers the manifest** (`:1345-1350`) so `/api/status` and `/api/runtime/manifest` cannot disagree (tempdoc 501 Phase 26).
  - **Existing component abstraction to extend, not fork**: `modules/app-api/.../lifecycle/Capability.java`, implemented by `WorkerCapability.java:27` and `InferenceCapability.java:19` — carries `required()`, `name()`, `generation()`, `transition(health, reason)`, listeners, `RecoveryContext`.

### 2. LifecycleReasonCode and the gate

- **Every claim holds verbatim; all four files have zero commits in the C1 window.** `LifecycleReasonCode.java` (321 lines): **55** members `:19-176`. 16 `WORKER_*` at `:19,20,21,22,23,27,28,29,30,34,38,44,49,55,59,63`. Already component-shaped: 8 `INDEX_*` (`:66-96`), 12 `INFERENCE_*` (`:99-131`), 2 `CHUNK_EMBEDDING_*` (`:149-150`). No `ENGINE_*`/`ENCODER*`.
- `readiness-reason-codes.v1.json`: `awaitingProducer: []` `:78`; `feDerived` 5 at `:55-76`; prose `:4` still says "all 44 codes".
- `readinessNotice.ts`: `CAUSE_ROWS` `:66-460`, 50 rows; `RETRIEVAL_IMPAIRING_CODES` `:506-535`, 13 entries.
- `check-readiness-reason-codes.mjs`: FORWARD+BACKWARD in `checkCorrespondence` `:76-105`; PRODUCER `:182-207`; EMISSION `:274-326`. FORWARD/BACKWARD additionally honour **`noWordingExempt`** (10 entries) — the draft does not mention it; D1-14's rename must carry it.
- **D1 §10 second stop rule does not trigger.** `LifecycleReasonCode` is referenced only in `app-api` / `app-services` / non-MCP `ui.api`. `McpEvidenceProjection.java:408-455` surfaces `SearchReasonCode` (different, package-private enum). No alias required.
- **Draft citation error**: `supervision-contract.v1.json:36,45,54,77` carry `worker.restart_exhausted` (lowercase dotted) plus `worker.spawn_recovery_exhausted:77`, inside a `"status": "retired"` block. The uppercase token appears only at `readiness-reason-codes.v1.json:77`.

### 3. Supervisor "essential ready", the exit table, the 300 s clock

- Untouched by C1: `engine-supervisor.cjs`, `supervisor.rs`, `engine_probe.rs`, `supervision-contract.v1.json`, `EngineExit.java`, all three drift tests.
- `dev-runner.cjs` `essentialStatusReady(status)` **:1373-1378**: `status.components.head.state === 'LIFECYCLE_STATE_READY'`, `status.indexAvailable === true`, `status.worker.core.indexHealthy === true`, `status.readiness.components.indexServing.stale === false`. `engine-supervisor.cjs` has **no readiness logic** (pure `decide(observation, policy)`).
- Rust: `lib.rs:1176-1179` → `engine_probe.rs:61-63`, `essential_status():65-77` — same four pointers. Clock consumer `supervisor.rs:744-751`.
- `supervision-contract.v1.json:240` prose confirmed. `exitCodes` `:187-193`: 4/0/1/2/3; `unknownExitClass: TRANSIENT` `:194`. **No exit code 5.**
- `EngineExit.java`: `OK:41`, `FATAL_OR_UNCAUGHT:58`, `DATA_DIR_LOCKED:66`, `OUT_OF_MEMORY:78`, `REQUESTED_RESTART:81`; `classify():89-96`, `describe():105-114`. Drift tests: `EngineSupervisionPolicyTest.java:168-206`; `contract.mjs` `checkExitTableAgreement` `:94-128`; `EngineExitTest.java:78-102`.
- §7.1 "Liveness and stability are different" `design.md:854-862`. Both hosts gate the clock on essential-ready: `dev-runner.cjs` `armHangDetection:2705-2733` (`essentialReadySince` `:2720-2723`), `supervisor.rs:744-751`.
- **Consequence:** the current definition mixes the snapshot's `head` slot, two legacy scalars (`indexAvailable`, `worker.core.indexHealthy`) and one envelope dimension's staleness. An `index` composite can subsume `indexServing.stale`, but the two scalars have no composite equivalent and §7.1 names them as required clock evidence.

### 4. `KnowledgeServerHealthMonitor` after C1

- `modules/app-services/.../worker/KnowledgeServerHealthMonitor.java`, 739 lines, 3 C1 commits. Boot arm shifted: `attemptBootRecovery` **:407-501**, `narrateGiveUp` **:522-568**, `runBootRecoveryArm:356-370`, `currentRecoveryInput:374-406`, `handOverRecoveredWorker:503-520`, `settleAfterFailedAttempt:590-597`, `latchGaveUp:610-613`, `requestRecoveryNow:624-673`. `BootRecoveryDecision.java` `Veto:30-51`, `decide():102-129`, `backoffMs:137-153`; `BootRecoveryPolicy.java` 4/10 s/60 s (`:22,29,32`).
- **C1's new mechanism**: field `executorRegistration` `:91`, constructor requires `EngineExecutorRegistry` `:160-199`, spec `head.knowledge-server-health-monitor`; `START_ADMISSION_BUDGET_MS=5_000` `:81`, `startClaimed` `:131`, `nextTickAtNanos` `:132`; `start():204-238` retries `EngineExecutorRejectedException` for 5 s; one retained fixed-delay timer + monotonic due check (`tickIfDue:251-258`, `nextTickDelayMs:261-273`); `requestRecoveryNow:663-666` maps capacity refusal to `EngineAdmissionException(ENGINE_LIMIT, retryAfterSeconds())` → 429. Pinned by `KnowledgeServerHealthMonitorCapacityTest.java`.
- Lifecycle: constructed at `HeadlessApp.java:616` in `startHealthMonitor():614-643`, from `:543` and `:568`; `start()` `:636`; `apiServer.bindWorkerRecovery(monitor)` `:637`; **rollback** `:634-643`. Teardown step `"worker-health-monitor"` at `HeadlessApp.java:1425-1429`.
- **Consequence:** replace the boot arm methods listed above and both `BootRecovery*.java`; retire guards at `supervision-contract.v1.json:80-81`. Keep the registration, the admission-retry `start()`, the single timer, `executorRegistration.close()` `:723-739`, the `HeadlessApp:634-643` transaction, and the `ENGINE_LIMIT` translation `:663-666`.

### 5. Requested restart

- `HeadlessApp.localRestartAction` **:1538-1547** (3-arg) and **:1549-1583** (4-arg); bound at `:1025`.
- `EngineShutdownSequence.java`: `runAndExit:188-196`, exit selection `:194-195`; `execute():143-176`; `run(Reason):129-140`. 11-step order at `HeadlessApp.orderedShutdownSteps:1378-1465`; C1 (`c14e8ba9d`) added `operation-admission` and `interactive-work`.
- **Migration dispatch is two paths.** Path 1 (outcome flag): `MigrationControlOps.java:62,92,186` → `MigrationOps.java:58,84,112` → `KnowledgeClient.java:1023,1028,1033` → `EngineKnowledgeClient.java:261,268` (`startMigration`, `rollbackMigration` only); **`requestCutover` deliberately not overridden**, pinned by `EngineMigrationRestartDispatchTest.java:23-45`. Path 2 (fires at promotion): `KnowledgeServerMigrationOps.java:276`, threaded via `EngineRoot.java:93,132,154,194,205-206` into `KnowledgeServerMigrationOps.Context:74`; `EngineRoot.requestRestart:257-262`.
- `RestartRequiredException` — one throw `WorkerServiceImpl.java:53-58`; catch `RestartWorkerHandler.java:63-76`; dead success branch `:59-62`.
- `RestartWorkerHandler.java:29-84`, `execute:47-83` (takes `EngineContext`). Catalog `CoreOperationCatalog.java:72`, `:382-399`. Bound at `OperationHandlerRegistrations.java:88-175`. **Second consumer**: `CoreSurfaceCatalog.java:285` declares `OP_RESTART_WORKER` on the Health surface (touches `surface-altitude` / `interaction-surface`). `structuredData.port` produced only at unreachable `:62`; referenced only by `RestartWorkerHandlerTest.java:105`, `OperationClient.test.ts:42,52`.
- **`POST /api/worker/restart` is not only a 409.** `InferenceHandlers.handleRestartWorker:643-687`: no client bound → `routeToRecoveryAuthority:698-734` (202 ACCEPTED/ALREADY_RUNNING, 503 EXHAUSTED, fall-through), else 503 "still initializing" `:662-669`, else 409 at `:685-686`. Never calls `WorkerService.restart()`.
- **Consequence:** the 410 deletes the only operator route into manual boot recovery (`WorkerRecoveryAuthority.requestRecoveryNow`, wired at `HeadlessApp.java:637`, surfaced via `InferenceHandlers.java:44,93` and `LocalApiServer.java:879`). D1-11 and D1-13 must land together. D1-7 deletes Path 2, not the outcome flag.

### 6. Settings apply

- **Every claim exact; zero C1 commits.** `SettingsController.java:104-153` (`synchronized(settingsStore):114`, `mergeV2Into:129`, `validateIndexPath:131`, `save:137`, `rebuildConfigStore:138`, `chatEnabledChanged.run():142-145`, blanket catch `:148-151`). `ConfigStore.java`: `AtomicReference current :90`, notifier `:92`, `addListener:135`. One production listener `RuntimeContextConfigBridge.java:58` (`onConfigChanged:61-70`). `ConfigStoreRebuilder.rebuild:72-85`, swallow `:82-84`. `UiSettingsStore.java`: `ui/settings.json` `:31`, `CURRENT_SCHEMA_VERSION = 2 :51`. `ConfigChangedEvent.keyChanged` `:29` — zero callers.
- **C1 added nothing config-revision-like.** `appliedVersion`, `configVersion`, `settingsRevision`: zero hits. `configHash`: only `declaredConfigHash` (B12). `ConfigStore.java`'s single C1 commit (`9625d1711`) added `synchronized` to `setGlobal`/`clearGlobal` and `restoreGlobal`.

### 7. Config register and the `config-surface` gate

- `EnvRegistry.java`: `LifecycleStage` enum **:1458-1462**, field `:1418`; attributes `sysProp:1415`, `envVar:1416`, `defaultValue:1417`, `lifecycleStage:1418` — **no apply-scope**. `ConfigKey.java:98-99`.
- `config-lifecycle.v1.json` (384 lines): 29 entries; shape `{declaration, owner, rationale, introducedOn, lastReviewedOn, reviewBy, exitCriteria:{promoteWhen, removeWhen}, evidenceLink}`.
- Matrix `docs/reference/configuration/runtime-config-ownership-matrix.md` — **304** rows (`e71b512a6` added `ENGINE_ADMISSION_AGGREGATE_LIMIT`). Generator `scripts/docs/generate-runtime-config-matrix.mjs`.
- **The gate does not read the matrix's rows.** `scripts/governance/gates/config-surface/enforcer.mjs` (349 lines) reads a **gitignored** JSON at `tmp/agent-evidence/_summaries/runtime-config-ownership-matrix.generated.json` (`:113`) and ratchets three scalars — `yamlKeyCount:110`, `envSyspropPairCount:248`, `configKeyCount:56` — against `gates/config-surface/baseline.txt`. Honest limit self-declared at `:14-19`.
- No `config-apply*.json` exists.
- **Consequence:** D1-2 must add a fourth ratcheted scalar or make the enforcer regenerate.

### 8. Operation surfaces and `EngineContext`

- `operation-surfaces.v1.json` (464 lines, 41 surfaces). Entry `{id, kind, lang, path, guard, consumesProjection, note}`; `kind ∈ {source, producer, projection, consumer, store}`. `canonicalRecord` is `IndexingJobLifecycle`, sibling `ActionEvent`. `consumesProjection` must be `"canonical-record"`, `"self"` or another declared `id` (`:104,109-116`). **No `reconfigure`, settings-apply, migration or cutover entry.**
- Gate `enforcer.mjs` (254 lines): vacuous floor `:66-74`; undeclared surfaces by Java import `:76,82`; orphan paths `:83`; dangling guards `:97`; lineage `:113-116`; forbidden reintroduction `:138`. Says nothing about `OperationHandler` registration.
- `OperationDispatcher.dispatch(Operation, String, EngineContext)` `:37`; `OperationHandler.execute(String, EngineContext)` `:27` — required. `OperationExecutorImpl.java:326,579` validate; one dispatch site `dispatchCore:619-630`. Registration: `HandlerRegistry.java:22-26`, `CoreOperationCatalog.java`, `OperationHandlerRegistrations.java:88-175`, `OperationSubstrateInit.java`.
- **No client key and no ordering invariant exist.** Nearest: `RetryPolicy.java:20-32` `Optional<String> idempotencyKey` scoped to auto-retry.
- **Consequence:** a `reconfigure` operation is a three-site addition, but `--gate operation-surface` cannot accept a row for it as written.

### Draft claims that no longer hold (audit 3)

1. `POST /api/worker/restart` is not a dead 409.
2. `structuredData.port` has no consumers to re-point.
3. `core.restart-worker` has a second declaration site: `CoreSurfaceCatalog.java:285`.
4. The matrix is 304 rows; the gate's floor is three ratcheted scalars read from a gitignored JSON.
5. `ReadinessDimension.composite` is a single string; no `api` dimension; `head` slot hardcoded READY.
6. Three readiness representations, not two (the runtime manifest lifecycle).
7. `--gate operation-surface` is not a general operations registry.
8. The two essential-ready definitions are `dev-runner.cjs:1373-1378` and `engine_probe.rs:61-77`; both read four fields, two with no composite equivalent.
9. D1 §10's second stop rule does not fire.
10. `WORKER_RESTART_EXHAUSTED` residue is `worker.restart_exhausted` inside a retired block.
11. Line drift: `localRestartAction :1538-1583`; `attemptBootRecovery :407-501`; `narrateGiveUp :522-568`; `computeLifecycleSnapshot :1285-1356`; envelope `:1395-1433`; composites `:1419-1429`; `LifecycleStage :1458-1462`; `EngineExit` `:41,58,66,78,81`.

### C1 decisions D1 must inherit (audit 3)

1. Bounded-executor admission is the substrate for every timer (`KnowledgeServerHealthMonitor.java:91,160-199,204-238,251-273,723-739`; design.md:347).
2. Start-then-rollback is the composition contract (`HeadlessApp.java:634-643`).
3. Typed capacity refusal maps to `ENGINE_LIMIT` with the registered retry delay (`:663-666`).
4. `EngineContext` is a required parameter on every operation handler.
5. Standalone admission: exactly one `EngineAdmissionService` provider owning `OperationLeaseService` (C1.md:1354-1360).
6. `retained-state.v1.json` names D1 as producer for three kinds with caveats (`:34-46`).
7. Two new ordered-shutdown steps exist (`operation-admission`, `interactive-work`).
8. The native-lifetime obligation is unchanged by C1.
9. Cutover restart flows through Path 2 only (`KnowledgeServerMigrationOps.java:276`).

---

## Audit 4: C1 substrate and C2/E expectations

### 1. Executor registry substrate — the precedent D1-1 copies

- `modules/core/.../execution/EngineExecutorRegistry.java:9` (`extends AutoCloseable`): `register(EngineExecutorSpec)` `:13`, `limits(Kind)` `:15`, `maxConcurrentWork()` `:18`, `retryAfterSeconds()` `:21`, `snapshot()` `:23`, `close()` `:26`. `Limits(maxThreads,maxQueue)` `:11`; `Registration extends AutoCloseable` `:28` — `spec()`, `open(ThreadFactory)`, `openScheduled`, `openVirtual`, `close()`.
- **No `ExecutorKind` type.** `EngineExecutorSpec.Kind{FOREGROUND,BACKGROUND}` `:15`, `Mode{PLATFORM,SCHEDULED,VIRTUAL}` `:17`. Spec `:7` = `(name, Kind, Mode, Integer threadCount, Integer queueCapacity, int maxInstances)`.
- `EngineExecutorRejectedException:8` extends `RejectedExecutionException`; `Reason{QUEUE_LIMIT,TIMER_LIMIT,INSTANCE_LIMIT,CLOSED}` `:9`; `retryAfterSeconds` `:12`.
- `EngineExecutorSnapshot:7` = `(registrations, timerRegistrations)`; `snapshot()` `DefaultEngineExecutorRegistry.java:116-125`. **No production consumer today.**
- Lifecycle: `register()` `:64-81`; `Registration.close()` = `RegisteredExecutor.close()` `:296-305` → `markClosedLocked()` `:307-310` → `shutdownAndCancelQueued` `:158-174` → `awaitWithinCloseDeadline` (2 s, `:35`) → `pruneInstancesAfterClose()`. Name freed only when `registrationClosed && instances.isEmpty()` `:192`; closed-but-live predecessor refuses with `INSTANCE_LIMIT` `:72`.
- 256 timer registrations: `policy.execution().get("timerRegistrations")` `:350` ← `retained-state.v1.json:13`. `TIMER_LIMIT` `:352`; `QUEUE_LIMIT` `:347`. One-shot permits release before callback `:456-462`.
- **Resource-policy JSON is the same file as retained-state**: staged to `/engine/retained-state.v1.json` by `modules/app-engine/build.gradle.kts:57-60`. Loader `EngineResourcePolicy.load() :23-30`, validator `parse() :54-89` (`POLICY_KEYS` `:17-19,:69-71`, `RETAINED_KINDS` `:20-21,:87`). Drift test `EngineResourcePolicyTest.java:56-69`.
- ServiceLoader: provider interface is `EngineExecutorRegistry`; `LauncherEnvironment.java:161-162` → `loadExecutors :245-253`, exactly one required. **`EngineRoot` does NOT use ServiceLoader** — constructs directly `EngineRoot.java:54-55`. `LauncherEnvironment` reached only by `SmokeDriver.java:38`.
- **Copy:** interface-in-`core` + impl-in-`app-engine`; `Reason` enum on one typed exception; spec → Registration → terminal close lifecycle; sorted immutable `snapshot()`; classpath-staged JSON with set-equality validation plus byte-identity test. **Do not duplicate:** `EngineResourcePolicy` (extend), the ServiceLoader path, `RETAINED_KINDS` (extend).

### 2. Retained-state substrate

- `retained-state.v1.json` (50 lines): `policy` `:5-15` (perContextLimit 16, aggregateLimit 64, retryAfterSeconds 1, foregroundThreads 16, foregroundQueue 48, backgroundThreads 4, backgroundQueue 64, timerRegistrations 256, directMemoryMiB 256), `retained` `:16-49`: search-cursors 32/4 D2 `:17-24`; pinned-readers 34 D2 `:25-30`; **representation-generations 2 D1** `:31-36`; **co-resident-encoders 2 D1** `:37-42`; **attempted-configurations 1 D1** `:43-48`. `:35`: "D1 must reconcile rollback retention with this target before claiming enforcement."
- `RetainedStateBudget.java`: `declare` `:14/:19`; `activate(kind)` `:32` (second call throws `:34`); `tryAcquire(kind)` `:38` returns **`Optional<Permit>`, empty at cap** `:41`; unactivated kind throws `:40`; `snapshot()` `:46` tri-state; `Permit` `:75`, idempotent `close()` `:84-91`. Kinds are Strings `:12`. Wired `EngineResourcePolicy.java:72,:83-85` → `EngineRoot.retainedState()` `:66-69`.
- **No production producer for any kind.** Drift check is JUnit only (`EngineResourcePolicyTest.java:25-38`). **D1 must edit `:33-34` when it activates its three kinds.**

### 3. Admission and work handles

- `EngineAdmissionService.java`: `admit(EngineContext, boolean allowWhileFrozen)` `:9`, `attach(EngineContext)` `:12`, `cancelInteractive(String)` `:14`, `retryAfterSeconds()` `:16`, `limits()` `:19`, `activeWorkCount()` `:22`. **`freezeAdmission` is on `OperationLeaseService.java:70`**; `EngineAdmissionController` implements both (`:84-91`).
- Work ids minted by the Engine (`EngineAdmissionController.java:115,123-124`); `attach` re-finds by id `:136`. First-wins is cancellation-reason (`EngineWorkHandle.java:17`, `:201`). `EngineWorkHandle:9`: `context()`, `retain()`, `cancellationReason()`, `cancel(String)`, `waitingClientGone()`, `onCancel/onBackground/onCompletion`, `Registration` `:31-34`. Cancellation is poll-or-callback, never interrupt. Tracking `:39-40`, refcount `:230`, freed `:323-331`.
- Ordered shutdown `HeadlessApp.orderedShutdownSteps :1378-1473`: step 1 `"operation-admission"` `:1400-1405` → `freezeAdmission` `:1403`; step 2 `"interactive-work"` `:1406-1411` → `cancelInteractive` `:1409`. `cancelInteractive` filters `survival()==INTERACTIVE` `:167-168` — **DURABLE work is never cancelled by shutdown.** Later: `local-api :1420`, `head-assembly :1432`, `index-half :1443-1448`, `telemetry :1455`, `executor-registry :1461`, `app-instance-lock :1467`.
- `EngineContext.java:23-32`: `clientKind, clientId, sessionId, grantReference, sourceTier, transport, survival, urgency, workId (Optional<UUID>)`. `ClientKind{WEBVIEW,MCP_CLIENT,CLI,PAIRED_DEVICE,SUPERVISOR,INTERNAL}` `:74-76`, `Survival{INTERACTIVE,DURABLE}` `:78-80`, `Urgency{FOREGROUND,BACKGROUND}` `:82-84`.
- **Gaps D1-3/D1-7 must close:** (a) no `cancel(UUID workId, reason)`; (b) no `awaitQuiescent(timeout)`; (c) freeze does not block `attach` `:132-143`; (d) `allowWhileFrozen` reserved to `/api/upgrade/` (`ApiSecurityFilters.java:176-177`); (e) `onCompletion` is a bare `Runnable`. Only route to cancel a named long op: `OperationLeaseService.requestCancellation(preparationId)` `:79`.

### 4. EngineRoot composition and close

- `EngineRoot.java:51 implements WorkerHost`. Field order: `resources = EngineResourcePolicy.load()` `:52` → `admission = new EngineAdmissionController(resources)` `:53` → `executors = new DefaultEngineExecutorRegistry(resources)` `:54-55`. Accessors `:58,61,64,67,72`.
- Index half in `start(GpuSchedulingGauge, IpcTelemetry) :215-255`: `serverFactory.apply(gpuScheduling, executors) :224` → `onTerminalWriterFailure :231` → `onMigrationRestart :233` → `started.start() :235`. Front half `EngineKnowledgeClient :249-252`. **Objects both halves see: `executors` and `gpuScheduling`.** `admission` front only `:251`. **No inference half in EngineRoot** — wired in `InferenceWiring.java:51`.
- `close() :294-342`, `synchronized`, `void`: client `:300-304` → server `:306-311` → `awaitClosed(2000ms)` `:323-332` → clear `:338-340`. Failure aborts the rest (`:310,:331,:336`). **`WorkerHost.close` IS `EngineRoot.close`** — index half only; does not close `executors` or `admission`. Retain-for-retry: `EngineRoot.java:310,:331`; `KnowledgeServer.java:2243,:2247`; `RunningRuntime.java:216-221,:225-227`.
- `HeadlessApp` composition: `EngineRoot.forProcess(...)` `:1035-1037`; `processExecutors = engineRoot.executors() :1038`; index half `new KnowledgeServerBootstrap(engineRoot.executors(), …, engineRoot) :1490-1496`; front `LocalApiServer.builder(...).engineAdmission(engineRoot.admission()) :474-476`.
- **Where D1's registry goes:** a field beside `:52-55`, accessor beside `:58/:61/:64`; widen `serverFactory` BiFunction `:88-89,:199-201` into `new KnowledgeServer(...) :162-165`; add to `:249-251`; close step beside `"executor-registry" :1461-1466`. **`GpuSchedulingGauge` is the wrong precedent** — created in `KnowledgeServerBootstrap.java:102-103`; the true precedent is `EngineExecutorRegistry`.

### 5. Operations

- `OperationExecutorImpl.java:316-321` `dispatch(Operation, String, InvocationProvenance, Optional<String>, EngineContext)`. Order: null checks `:322-325` → `validateEngineContext` `:326` → `validateProvenance` `:332` → trust lattice `:337-339` / `enforceTrustLattice :669-736` → capability check `:342-352` → `startTime :354` → input-schema `:361-368` → dispatch `:371-387` → history `:393`. **No admission step, no idempotency key.** Unknown ids rejected at `OperationsController.java:132,:237`.
- `OperationDispatcher.java:31`: `dispatch(Operation,String,EngineContext) :37`, canonical 5-arg `:71-75`, `undo :84,:102-106`. `EngineContext` is the last parameter of every method, never a field.
- `OperationHistoryStore` — `app-observability`, `.../operations/OperationHistoryStore.java:28`; class is the ring; `DEFAULT_CAPACITY = 200 :31`; listeners `:38-39` notified outside the lock `:74-83`. Executor couples via nullable `Consumer<OperationHistoryEntry> historyEmitter :72`.
- `CoreOperationCatalog.java:46`; `core.bulk-reindex` `:402-423`. `Operation.java:38-49` (11 components); `OperationPolicy.java:28-37`. **No `core.reconfigure` exists.** `core.reload-inference` at `:351,846`.
- `OperationResult.java:32-39` = `(success, message, executionId, structuredData, errorCode, errorDetails, retryable)` — **no state enum**. `OperationOutcome.java:11-15` = `SUCCESS, FAILURE, UNDONE`; javadoc `:7-9` notes `CANCELLED`/`PARTIAL` absent.

### 6. C2's contract as D1 sees it

- **C2-6** `stages/C2.md:592-613`: "builds the **global accepted-settings revision only** — a monotonic counter persisted beside the settings and bumped by `SettingsController.handleUpdateSettingsV2`" `:595-598`; success body gains `revision`; `VERSION_CONFLICT` new `ApiErrorCode` (class `CONFLICT`) `:600-603`; `UiModeIntent(clientId, sequence)` neither reused nor forked `:604-605`. Q4 `:964-965`: D1 adds the per-component versions.
- **C2-10** `stages/C2.md:667-694`: "(1) C2 owns the *row*, the *plan capture*, the *resume* and the *two lists*; **D1 owns the journal, the replay, live activation and the gap refusal**" `:674-676`. `core.bulk-reindex` (`BulkReindexHandler.java:72`→`startMigration`, Blue/Green) vs `core.reindex` (`ReindexHandler.java:52`→`reindexWatchedRoots`); plan capture on the second is "materially harder" (`RootLifecycleOps.reindexWatchedRoots:430` fire-and-forget). `:693-694`: "Activation refusal on a gap is **not** asserted here — it is D1's." Fallback `:975-977`: C2-10 may shrink to `core.bulk-reindex` and name the other as a D1 follow-up.
- Other: `:759` readiness codes are D1's; `:781,:790` component-map half of the delayed-retry row is D1's, unmeasurable; `:889` `core.restart-worker` still answers `restart_required`.

### 7. E's expectations of D1

- Rows D1-16 must feed (`E.md:65` E0.5, `:121`, `:161` R6): **6 stuck component** `:202-205` · **7 generation transition** `:206-213` · **8 semantic availability** `:214-217` · **9 combined low-memory reindex** `:218-222` · **10 combined delayed retry** `:223-228` · **19 reconfigure** `:245-255`. Instrumentally: **4 recovery-process** `:119` needs "the readiness envelope's per-component ready instants (D1)"; **5 recovery-workflow** `:120,:196` needs per-component time-to-ready.
- Semantic-availability: D1-16 `D1.md:543-545` (wall-clock between first `reloading` and first semantic answer); E row 8 `:214-217` (refused fraction **and** wall-clock; paired first, absolute second); E `:355-362` Q2 amendment; `:341` "not an allowed proof gap". D1's harness must emit the refused fraction too.
- Floor simulation E0.4 `E.md:63-72`: cap "the existing per-model `*_GPU_MEM_MB` keys" — but those are ORT arena limits, not a device-wide free-VRAM cap; no override key exists for `VramDetector`/`NvmlService`; the cited `fixture-pair.sh` precedent is wrong (pins live in `scripts/jseval/jseval/run_config.py:19-36`).
- Bounds `E.md:29-38`: no run over 55 minutes; R6 is 2 × 45 min `:161`; `:167-168` over-cap exits `TIMED_OUT`.

### 8. Conformance harness and installed-process tier

- `scripts/supervisor-conformance/`: `contract.mjs` (244) · `run.mjs` (488) · `fake-engine.mjs` · `real-writer-recovery.mjs` (311) · `essential-stability.mjs` · `hostile-lock-scenario.mjs` · `migration-restart-scenario.mjs` · `processing-replay-scenario.mjs` · `adapters/dev-runner.mjs` · `adapters/tauri.mjs`.
- `contract.mjs` holds no cases `:4-8`; `checkExitTableAgreement :92`; `allCases :136` throws on empty `:139`. Cases are data: `supervision-contract.v1.json` `conformanceCases` `:262`, shape `:263-288` = `{id, faultMode, drives, engine:{mode,exitCode,exitAfterMs}, observation, expect:{action,cooldownMs,counted}, why}`. `supervisor.rs` embeds the same file. `exitCodesNote :195` names the four readers.
- Adapter contract (`run.mjs:420-468`): export `name`, optional `available({io})`, `runCase({testCase, policy, io})` → `{problems}`. Scenario shape `export async function exercise*(c)`, throws on failure; registered in `real-writer-recovery.mjs` `:181-191`.
- `EngineSupervisedRecoveryE2ETest.java` (197 lines, `@Timeout(7*60) :20`): one `@ParameterizedTest :23`, `@ValueSource {"writer","migration","lock-boot","lock-ingest","processing"} :24`; drives `node scripts/supervisor-conformance/real-writer-recovery.mjs` `:39-46`; env `JUSTSEARCH_WRITER_RECOVERY_WORK :47`, `JUSTSEARCH_REAL_RECOVERY_SCENARIO :48`; Java poll 330 s `:74,:87-89`; asserts on stdout markers `:115-129`.
- Real Engine: `real-writer-recovery.mjs:74-78` spawns `dev-runner.cjs start --json --skip-build --clean none --api-port 0 …`, installed dist `modules/ui/build/install/ui/bin/ui.bat` (`dev-runner.cjs:1979-1982`, `:2051-2055`). Env `:29-58` (`JUSTSEARCH_SUPERVISOR_HARNESS:'1'`, private state root, AI kill-switches, `AI_OFFLINE:'true'`), escapes `:60-73` (the `migration` scenario re-enables embeddings because the fingerprint is a cutover precondition).
- Readiness two-stage `:165-170` + `:175-180`. Kill never `taskkill`. Budgets: JUnit `@Timeout` > 330 s poll > per-`waitFor`. Assertions `requireThat`. Logs `console.log('<SCREAMING_SNAKE>', JSON)` with one terminal `*_PASS`.
- Gradle: source set `:102-108`; task `dependsOn(":modules:ui:installDist") :179`, four `.mjs` as `inputs.file` `:180-183` — **new scenario must be added there**; `excludeTags("ai")` `:194-198`; **task timeout 30 min `:220-226`**; `beforeall.method.default 420s :261`.
- `EngineLifecycleE2ETest` does not exist; belongs beside `EngineSupervisedRecoveryE2ETest.java`. Templates: `api/ConsentCapsuleRecoveryE2ETest.java:22-27`, `api/IndexingLedgerCoherenceTest.java:26-31`.

### Assumptions C2.md or E.md make about D1 that D1.md does not satisfy

1. **E needs per-component ready instants; D1-12 adds no timestamp.** `ReadinessComponentView.java:12-14` = `(state, reasonCode, source, observedAt, stale, stalenessMs)` — `observedAt` is the observation instant. D1 needs a `readyAt`/`stateSince` field.
2. **E row 19 samples "D1's component map"; D1 §9 defers the map to D2.** `E.md:249`, `:227` vs `D1.md:604-605`.
3. **E0.4's floor mechanism cannot force the in-place path.** `*_GPU_MEM_MB` are arena limits. D1-5 must add an explicit device-memory ceiling override.
4. **D1 owns three retained-state kinds; D1.md §8 names two.** `representation-generations` needs the three-vs-two reconciliation.
5. **D1-16's time budget exceeds its Gradle ceiling.** 30-minute task cap; tier already ~8.5 min.
6. **No `OperationOutcome` state for long-running or cancelled operations.**
7. **The operations executor has no admission seam.**
8. **`swapRuntime` is close-then-open and must be inverted, not reused.**
9. D1-1 names `GpuSchedulingGauge` as the precedent; `EngineExecutorRegistry` is the actual one.
