# Audit 2 — Session handle lifecycle, GPU arbitration, VRAM accounting, native faults (read-only, lane-F-A HEAD 4229f1091)

Produced 2026-09-10 by an opus subagent under a read-only brief. Paths relative to the worktree root. Every `file:line` is at `4229f1091`.

## 1. Session handle lifecycle (`modules/ort-common`)

**`NativeSessionHandle.java`** (847 lines) is the only `SessionHandle` implementation; `Builder` package-private (`:751`, `:773`), reachable only from `OrtSessionAssembler.buildManager` and testFixtures (`:37-42`; `@BuildContract` `:766-772`).

- Constructor calls `OrtNativeTempReaper.reapStaleOnce()` before `OrtEnvironment.getEnvironment()` (`:142-143`).
- **`arenaCapBytes > 0 ⇔ GPU`** (`:169-173`); resolver zeroes it for non-CUDA (`ModelSessionPolicyResolver.java:207-217`); `forFallback` zeroes for null config (`ModelSessionPolicy.java:83-98`). *(Citation corrected 2026-09-10 after independent review: the file is 144 lines; the original `:215-218` was drift.)*
- `private final Semaphore gpuInferenceSemaphore = new Semaphore(1)` (`:117`). CPU leases take no permit (`:338`, `:513`).
- `selectSession()` (`:211-250`): `gpuSessionReleasing` → CPU; `gpuConfig == null || !shouldUseGpu` → CPU; lazy GPU init under double-checked lock (`:223-229`); GPU retry (`:232-247`); else GPU or CPU.
- **C1 interruptible waiter** `:288-308`: interruptible `acquire()`, `InterruptedException` → restore flag, throw `CancellationException` (`:291-297`); two post-acquire re-checks (`:301-303`, `:306-308`); `acquired` bookkeeping (`:286`, `:327`, `:329-335`).
- Post-wait re-check (`:310-318`): if `gpuSessionReleasing` flipped or session swapped, release permit, return CPU lease with `GpuFallbackTaken`.
- **`releaseGpu()`** `:362-405`: `gpuSessionReleasing = true` (`:363`); `acquireUninterruptibly()` (`:367`) — blocks until in-flight `session.run()` completes, does not cancel it; lifecycle callback (`:370-376`); close run options (`:378`); `gpuSession.close()` (`:380-388`); reset incl. `gpuSessionAttempted = false` (`:390-393`). No `reacquire()`; next `acquire()` lazily rebuilds (`SessionHandle.java:37-40`).
- GPU retry `:232-247`, default `DEFAULT_GPU_RETRY_INTERVAL_MS = 60_000L` (`:81`). NER retry disabled (`ModelSessionPolicyResolver.java:138`); citation `:189`.
- `deferCpuSession` true only for EMBEDDING on CUDA (`ModelSessionPolicyResolver.java:102, 109`).
- `getCpuSession()` (`:587-627`) lazy/recreating; `createCpuSession()` (`:629-634`); `acquireCpu()` (`:511-514`).
- `reportCpuSessionFailure` (`:441-462`): deferred recreation. **Exactly one production caller: `CrossEncoderReranker.java:344`.**
- `GpuLifecycleCallback.onBeforeRelease()` invoked synchronously under the GPU semaphore (`:15-19`); bound via `setLifecycleCallback` (`:412-415`); one consumer, SPLADE pinned output.
- **`close()`** (`:552-577`): `if (closed) return; closed = true;` then closes RunOptions, GPU session, CPU session. **`closed` is read at exactly one place (`:553`).** Consequences: `close()` takes no GPU semaphore (contrast `:367`) — an in-flight `Lease.run()` can execute against the session being destroyed; a post-close `acquire()`/`acquireCpu()` sees `cpuSession == null` (`:570`) and runs `cpuSession = createCpuSession()` (`:615`), returning a live, never-closed ORT session; `getCpuSession()`'s recreate branch (`:595-611`) closes the old session while other CPU leases hold it untracked.
- `NativeSessionHandleConcurrentStressTest`: `@Tag("stress")` (`:67`), skipped without a model (`:88-97`), CPU-only (`:99-108`, `:120`), comment `:171-173` tolerates post-close `IllegalStateException`; invariants #1, #2, #4 "NOT COVERED — requires CUDA" (`:46-52`).

**`OrtSessionAssembler.buildManager`** `:85-115`: resolves `nativePath` (`:89`), null variant → `NULL_VARIANT` telemetry (`:95-98`), nulls `gpuModelPath` when EP ≠ CUDA (`:99-102`), arbiter at `:110`. Probes `:138-148`, `:173-188`, `:203-213`; `verifyModelSession` `:231-255`.

**`SessionOptionsApplier`** package-private (`:25`): `applyBase` `:36-55`; `applyGpuSessionOptions` `:65-83`; `applyCudaProviderOptions` `:95-108` (**`gpu_mem_limit` at `:99`**); `buildGpuRunOptions` `:119-125`.

**`OnnxSessionCache`**: cache is `modelPath.resolveSibling(fileName + suffix)` — **inside the models directory next to the `.onnx`** (`:41-43`, `:156`, `:221`, `:264`). CPU `.optimized` + `.opt-meta` (`:29-30`), CUDA `.cuda.optimized` + `.cuda.opt-meta` (`:31-32`). Keyed by source mtime + size + ORT version + `ep:cuda` tag (`:263-273`, `:332-343`). **Per-machine and shared across worktrees** (dev-runner resolves `JUSTSEARCH_MODELS_DIR` from the main checkout). Confirmed on disk: `F:\justsearch-public\models\onnx\gte-multilingual-base\model_fp16.onnx.cuda.optimized`.

**`GpuArbiter`** is `boolean shouldUseGpu()` (`:246-250`); production `() -> !signalBus.isMainGpuActive()` (`KnowledgeServer.java:1430`). No test file.

## 2. GPU arbitration after lane F

**`GpuSchedulingGauge`** (`modules/core/.../scheduling/GpuSchedulingGauge.java`, 108 lines): two volatile booleans (`:56-57`), `shouldYield(a,b) = a || b` (`:68-70`), setters/getters `:73-98`. No listeners, no versioning, no lock; `synchronized(gauge)` used ad hoc by one caller (`InferenceWiring.java:54`). Defaults false (`:50-52`). Javadoc `:17-28`, `:39-43`: "a composition that hands the index half a fresh gauge gives it a pair of booleans nobody writes, and it never yields."

**Pass-by-reference path (verified):** constructed once `KnowledgeServerBootstrap.java:102-103`; `:292` `workerHost.start(gpuScheduling, ipcTelemetry)` → `WorkerHost.java:37` → `EngineRoot.java:215-224` (`requireNonNull` `:217`, `serverFactory.apply(gpuScheduling, executors)` `:224`) → `EngineRoot.java:164` `new InProcessWorkerSignalBus(gauge, ...)` → `InProcessWorkerSignalBus.java:67-69` ("the same instance ... never a fresh one"). A separate host breaks this by construction.

**Writers — exactly two:** `InferenceWiring.java:56` `gauge.setMainGpuActive(manager.isOnline())` in `refreshGpuStatus` (`:48-59`) under `synchronized(gauge)`; `EnergyStatePoller.java:127` `gauge.setEnergyReduced(state.reduced())`.

**`EnergyStatePoller`**: `POLL_INTERVAL_MS = 15_000L` (`:47`), initial delay 0 (`:116`); probe `justsearch.power.force_energy_state` else `WindowsPowerStatus.read()` (`:160-169`); `UNKNOWN` not reduced (`EnergyState.java:41-43`); no edge detection; executor `head.energy-state-poller` (`:90-104`); constructed `KnowledgeServerBootstrap.java:212`, started `:290`.

**Readers — nobody listens, everything polls** (via `WorkerSignalBus`, `InProcessWorkerSignalBus.java:92-98`): `KnowledgeServer.java:1430` (arbiter), `:2109` (sentinel 1 Hz rising-edge, reranker), `EmbeddingProviderLifecycle.java:169` (indexing-loop edge, embedding), `BackfillScheduler.java:198-199, 247, 438, 629`, `EmbeddingBackfillOps.java:202-203`, `IndexingDocumentOps.java:256`, `BgeM3BackfillOps.java:346`. Two composition rules: `shouldYieldGpuBackfill()` and provider-aware `LoopPacingPolicy.shouldRunBackfill` (`:53-59`: `if (energyReduced) return false; return !mainGpuActive || !embeddingProvider.isUsingGpu();`).

**C1 late-connected listener:** `ServicePhase.java:189-190` → `InferenceWiring.wireGpuStatusBroadcast` (`:34-45`): resolves bootstrap through a supplier per publication (`:39`), seeds once (`:42`), ignores `(from,to)` and re-reads `manager.isOnline()` (`:37-40`, `:54-55`). Supplier `HeadAssembly.java:467` → `currentKnowledgeServer` (`:1343-1345`); `connectKnowledgeServer` (`:1348-1358`) assigns then refreshes (`:1357-1358`). Why needed: `evidence/C1/gpu-scheduling-connect.md:22-28` (constructor bootstrap null → gauge never written; `:8-12` enrichment stuck at 5.1% embed, GPU 100%, 11.4 GiB VRAM, one unit 270 769 ms). Events before connection are dropped, not replayed (`InferenceWiring.java:50`). Tests: `InferenceWiringGpuSchedulingTest.java:19, :61`.

**`RuntimeGpuLease`** — passive mirror: `Holder { CHAT, WORKER, NONE }` (`:24-28`); `mirrorFromMode` (`:59-71`); **`requestGrant(Holder, OptionalLong)` ignores `sizeBytes` (`:44-53`, `:17-19`) and has zero production callers**; only `RuntimeReconciler.java:189, 196, 605` call `mirrorFromMode`. `stages/F.md:183-186` renames `WORKER → INDEXING` at F.

**`RuntimeStatus`** four axes `{ENGINE, ADOPTION, LEASE, PROCEDURE}` (`:31-36`); `deriveEngine` maps `INDEXING → Down / gpu-yielded-to-indexing` (`:143-150`).

**`RuntimeReconciler`** single writer: thread `runtime-reconciler` (`:202-204`), level-triggered; listener does only `lease.mirrorFromMode(to)` + `markStatusDirty()` (`:195-200`); `reconcileToSpec` (`:482-552`): `needUp = effective && down && !external` (`:505`), `needDown = !effective && healthy` (`:506`), backoff 1 s→60 s (`:63-64`, `:541-551`); `procedureRequireEngine` (`:318-330`), javadoc `:312-317` says the switch "physically resides on this class, so the single-writer ArchUnit guard is satisfied".

**Rising edge:** reconciler → `switchToOnlineMode` (`InferenceLifecycleManager.java:404-407`) → `TransitionRunner.run` fires `(prev → TRANSITIONING)` (`:334-338`) → body (VRAM precondition `:426-444`, start, health `:466-467`) → `(TRANSITIONING → ONLINE)` (`:384-387`) → listener sets gauge → two edge detectors: sentinel (`KnowledgeServer.java:2088-2095`, `lastMainGpuActive` init **true**, `:2113` → `onMainClaimedGpu` → `WorkerSearchService.java:335-341` → `RagContextOps.java:198-203` and `CrossEncoderReranker.java:116-118` → `releaseGpu()`); indexing loop (`IndexingLoop.java:642` → `EmbeddingProviderLifecycle.handleGpuStateTransition` `:168-189`, `lastMainGpuActiveState` init **false** `:73` → `releaseEmbeddingGpuSession()` `:219` → `EmbeddingService.java:691-699` → `OnnxEmbeddingEncoder.java:1037-1038`).

**Per-role release asymmetry.** Only four `releaseGpuSession()` call sites in `modules/**/src/main`: `EmbeddingService.java:697`, `EmbeddingProviderLifecycle.java:219`, `RagContextOps.java:201`, `WorkerSearchService.java:339`.

| Role | `releaseGpuSession()` | caller on claim | effect when LLM online |
|---|---|---|---|
| Embedding | `OnnxEmbeddingEncoder:1037` | yes | VRAM freed |
| Search reranker | `CrossEncoderReranker:116` | yes | VRAM freed |
| SPLADE | `SpladeEncoder:1192-1194` | **none** | GPU session keeps VRAM |
| BGE-M3 | `BgeM3Encoder:382-384` | **none** | VRAM not freed |
| NER | no such method | — | VRAM not freed |
| Citation | CPU-only | — | n/a |

**CPU query embedding continues during Online — deliberately** (`EmbeddingProviderLifecycle.java:191-205`, 598 PART I). Bulk backfill and primary-indexing document embedding stop (`LoopPacingPolicy:53-59`; `IndexingDocumentOps.java:256-258`).

**In-flight batch:** completes; `releaseGpu()`'s caller blocks uninterruptibly for one ORT sub-batch (`OnnxEmbeddingEncoder:406/871`, `SpladeEncoder:538/692`, `BgeM3Encoder:321`, `CrossEncoderReranker:303`, `BertNerInference:207/402`).

**Falling edge: no reload path** (`EmbeddingProviderLifecycle:183-186` "Worker will reclaim VRAM on next embed acquire"). **Defect (verified):** `TransitionRunner.java:338` fires listeners at the start of the transition; the listener re-reads `manager.isOnline()` (false during `TRANSITIONING`), so on `ONLINE → INDEXING` the gauge says free before `stopLlamaServer()` and `VRAM_FLUSH_DELAY_MS` run (`InferenceLifecycleManager.java:551-553`). No observed incident.

**Removed diagnostic:** `grep "GPU status broadcast disabled" modules/` → nothing; `InferenceWiring.java:50` is a silent early return; `:43` logs "wired" unconditionally.

**ArchUnit guardrail:** `RuntimeReconcilerGuardrailsTest.java` scope `io.justsearch.app.services` (`:37-40`); rule 1 `:42-53` (`noClasses()...callMethod(OnlineAiLifecycleControl.class, "switchToOnlineMode"/"switchToIndexingMode")` except `RuntimeReconciler`); rule 2 `:61-76` allowlisting `AiInstallService`. Limits: `modules/ui`, `app-inference`, `app-engine` not covered; `callMethod` does not match method references (`ServicePhase.java:226-227` passes `manager::switchToOnlineMode`); not a governance gate.

## 3. VRAM accounting

**Reads:** `NvmlService.java` — loads `nvml.dll` by absolute path (`:296-306`), `nvmlDeviceGetMemoryInfo` (`:178-191`), **device index 0 hardcoded** (`:180`), full init/shutdown per `probe()` (`:113`, `:228`). `VramDetector.java` nvidia-smi: total 60 s cache (`:45-47`, `:68-71`), available uncached (`:130-133`). `GpuCapabilitiesService.mergeEffective` `:108-167`. `HardwareProfile.vramBytes` is total (`:21`), `MINIMUM_VRAM_FOR_GGUF = 7_500_000_000L` (`:24`); real value only at `AiInstallService.java:1710-1732`; **`KnowledgeServer.java:1408-1411` falls back to `HardwareProfile.gpuFull(0)`** on the composition path. `GpuSaturationSampler` uses only utilization (`:97-101`). Contradiction: `GpuCapabilitiesService.java:22-23` vs `snapshot():68-75`. **`gpu-bridge` is depended on only by `app-inference`, `app-services`, `ui`** — not `ort-common`/`worker-core`/`worker-services`/`indexer-worker`/`configuration`.

**Caps** (`ResolvedConfigBuilder`): embed 6144 (`:1201`), SPLADE 4096 (`:1273`), BGE-M3 3072 (`:1179`), NER 2048 (`:1293`), reranker 2048 (`:1305`, also `EnvRegistry.java:241`), citation 0 (`ModelSessionPolicyResolver.java:181-184`). Co-resident maxima: 14 336 MB (SPLADE config) or 7 168 MB (BGE-M3 config) vs 12 GB. Path: `ModelSessionPolicyResolver.buildGpu:207-217` → `ModelSessionPolicy.Gpu.arenaCapBytes` → `SessionOptionsApplier.java:99`.

**Nothing sums or checks headroom:** `ModelSessionPolicyResolver.java:61-64` (hardware never read); `NativeSessionHandle.tryCreateGpuSession:636-708` no free-VRAM read; `RuntimeGpuLease.requestGrant` ignores size, zero callers; every `gpuMemMb` read is single-role.

**llama-server side:** `-ngl` from config (`LlamaServerOps.java:499-502`, `:330-335`); boot-time all-or-nothing `gpu.layers = 99` when total ≥ 7.5 GB (`HeadlessApp.java:173-212`); `ContextWindowPolicy.auto` (`:126-129`) stores `freeVramBytes` and never reads it; rungs `32768, 16384, 8192, 4096` (`:81-82`); step-down failure-triggered (`LlamaServerOps.java:800, 819-845`); `-fit off` (`:518-525`). Post-hoc VRAM delta only in the self-test (`RuntimeActivationService.java:1209-1237`).

**`evidence/C1/memory-budget.md`**: host memory only; `MaxDirectMemorySize=256m` both launchers; `-Xmx2g` packaged only; no `MaxMetaspaceSize`; ORT host allocations unbounded; no device total or per-role enumeration.

**D1 obligations absent:** no declared per-model footprint anywhere (`ModelPackage.minVramBytes` is install-eligibility; only `chat` nonzero, `model-registry.v2.json:15,65,122,167,210,247,278,309`); in-process free-memory read exists in `gpu-bridge` but is unreachable from the session path; no CUDA-API memory read anywhere.

## 4. Composition, recomposition, close/quiescence

`InferenceCompositionRoot.compose` (`:117-167`) — exactly one production call site `KnowledgeServer.initDeferredModels()` `:1425-1431`. **No recomposition path.** `IndexingLoop.reloadEmbeddingService` does not exist (removed tempdoc 397 §14.11; `EmbeddingProviderLifecycle.java:161-166`). `DevReloadManager` rewires from `ModelContext` (`:110`, `:157-173`). Deferred init registration `WorkerExecutorRegistrations.DEFERRED_MODEL_INIT` (`:19`), `KnowledgeServer.startDeferredModelInitialization:1116-1124`. `InferenceLifecycleManager.applyConfig` (`:684-…`) governs llama-server only.

Close path: `EngineRoot.close()` (`:295-311`) → `KnowledgeServer.close()` (`:2234`): 5 s `runtimeSwapLock.tryLock` (`:2240`) → reaper (`:2259-2263`) → `deferredModelExecutor.close()` + `join()` (`:2268-2276`) → `appServices.close()` (`:2285`) → `IndexingLoop.close()` `loopThread.join(5000)` (`:1109-1116`) → SPLADE/BGE-M3/reranker closes (`:2334, 2343, 2352`) → `InferenceSurface.close()` (`:2364-2370`).

**Quiescence defect:** evidence `evidence/C1/native-waiter-cancellation.md` (`:5-8` incident; `:11-12` scope; `:51-52` CPU stress does not establish quiescence); obligation `stages/D1.md:149-176`; `handoff.md:158`. `releaseGpu` waits on the semaphore, `close()` does not; CPU recreation closes while leases untracked; every enclosing wait is timed; post-close `acquire()` creates an unclosed session. D1 acceptance `D1.md:168-176`.

## 5. Native fault surface

- GPU session construction: `catch (OrtException | UnsatisfiedLinkError e)` (`:694`) → `gpuAvailable=false`, `GpuInitFailed`, `OrtCudaStatus.providerFailed` (`:695-707`). FP16→FP32 retry `:663-673`.
- `isBfcArenaFailure` (`:535-541`) pure string match. Callers: `CrossEncoderReranker:326`, `OnnxEmbeddingEncoder:427,511`, `SpladeEncoder:603,625,730,896`, `CombinedEnrichmentBackfillOps:1412`.
- `Lease.run()` has no try/catch (`SessionHandle.java:202-208`).
- Per-encoder: embedding BFC ladder (`OnnxEmbeddingEncoder:411-453, 503-524`); SPLADE same; reranker `:325-349` (`reportCpuSessionFailure` only if `wasCpu` `:339-345`, `INFERENCE_FAILED` `:347-348`); citation `:170-174,276`; NER propagates (`BertNerInference:187,325` → `NerService:119, :177`), no BFC classification; BGE-M3 propagates.
- Composition-time per-role `catch (Exception)`. `AssemblerFailureKind.MODEL_MISSING`/`CUDA_UNAVAILABLE` never fired.
- NaN: only `SpladeEncoder.java:1085` (silent skip); reranker prevents via padding anchor (`:411-438`). **F-009's NaN-triggers-recovery path does not exist.**
- Metrics: `OrtSessionMetricCatalog` `ort.session.*` (`:39-47`); `FailureCause.classifyGpuInitException` (`:25-41`).
- Recoverable: GPU init failure → CPU; BFC → per-doc ladder; reranker CPU failure → recreation; role compose failure → absent. **JVM-killing:** any native fault during `run()` or construction not converted by ORT; no `-XX:OnError`. Blast radius is the whole Engine (design.md 707-714; `HeadlessApp.java:752-754`).
- Tests: `FailureCauseClassifierTest` (7), `NativeSessionHandleTest`, `OnnxEmbeddingEncoderOomFallbackLadderTest` (5, fake), `OrtCudaSelfCheckTest`, `NativeSessionHandleGpuWaiterCancellationTest` (3, mocked), stress test. **No test simulates a CUDA driver fault, segfault, or `UnsatisfiedLinkError` mid-run.**

## 6. Moving ORT out of process — loses/needs

- Shared `OrtEnvironment` uses all confined to `ort-common`/`worker-core`/`reranker`/`benchmarks`; none in `ui`/`app-services`. `ModelCapabilityResolver:353,396`; assembler probes; `ModelVerifier:81,95`; `OnnxSessionCache`. `OrtCudaHelper.java:352`: first `getEnvironment()` caller governs CUDA-path resolution.
- Cannot survive IPC: `SessionHandle` raw ORT types (`:81`, `:179-185`, `:202-208`, `:219-224`); SPLADE pinned buffer (`SpladeEncoder.java:102-103`, `:517`); inline tensors in every encoder; synchronous lifecycle callback under semaphore (`SessionHandle.java:120-129`); `reportCpuSessionFailure` "handle absorbs the race" (`:111-116`).
- Favourable: `NativeSessionHandle.Builder` package-private; `SessionHandle` is the right seam for a client stub.
- **JAR-bundled CUDA:** `OrtCudaHelper` `prepareCudaDependencies` (`:86-89`) `System.load()`s 15 DLLs (`:46-70`, `:433-447`) and copies into the most-recently-modified ORT temp dir (`copyCudaDllsToOrtTempDir` `:464-522`; TOCTOU heuristic `:453-457` "single ORT-using JVM per machine"). `resolveOrtNativePath` (`:178-224`). `applyOrtNativePackProperty` (`:368-409`), `EXPECTED_ORT_NATIVE_VERSION = "1.24.3"` (`:244`), must run before first `getEnvironment()` (`:352`); live caller `HeadlessApp.java:761-773` (javadoc names stale `IndexerWorker.main`). `OrtNativeTempReaper` (`:51-61`, 5 min age `:42`; observed 977 dirs / ~682 GB `:24-25`).
- **ORT jar trimming:** `modules/ui/build.gradle.kts:1496-1518` `stageTrimmedOnnxRuntimeGpu` excludes linux natives and relocates `onnxruntime_providers_cuda.dll` (~164 MB) to the `cuda-runtime` pack (`:1509-1512`, `:1456-1494`); pre-A13 two copies precedent (`:1488-1494`). Wiring: `ort-common:10-11` `compileOnlyApi`, `worker-core:18`, `indexer-worker:24`, `benchmarks:33` `runtimeOnly(libs.onnxruntime.gpu)`; version `gradle/libs.versions.toml:27`.
- **ORT ↔ CUDA pack coupling:** `scripts/release/check-ort-native-asset.mjs` (147 lines) parses `ORT_NATIVE_DLL_SET` from `OrtCudaHelper.java` (`:17-20`, `:37-46`); pin doubled in `libs.versions.toml:27` and `OrtCudaHelper:244`, no automated cross-check.
- Other: arbitration crosses a process boundary again (what A10 deleted); write access to models dir for caches; model-directory resolution is a Java class hierarchy (`EmbeddingOnnxModelDiscovery.java:1-30`, `modules/reranker/.../OnnxModelDiscovery.java`); CUDA toolkit pin `CUDA_TOOLKIT_MAJOR = "12"` (`:40`) shared with llama-server's variant dir; `ClosurePropertyTest` already isolates filesystem loading in the composition root.

## Contradictions

1. ADR-0004 (`:20-34`) is the reliable GPU-lease doc; `05-ai-architecture.md` is partly stale (`:55`, `:57-61`, `:14/:68`).
2. `RuntimeGpuLease.java:10-15` javadoc still references the MMF boolean.
3. `KnowledgeServerBootstrap.java:96-100` present-tense MMF claim.
4. `OrtCudaHelper.java:353-355` names `IndexerWorker.main`.
5. `GpuCapabilitiesService.java:22-23` no-op claim false.
6. **`verified-facts.md:165` and design.md §8 say the arena cap is "set from GPU VRAM" — it is a static per-role default** (`ModelSessionPolicyResolver.java:209-212`, `:61-62`).
7. F-009 NaN recovery path does not exist.
8. `IndexingLoop.reloadEmbeddingService` does not exist.
9. `AssemblerFailureKind.MODEL_MISSING`/`CUDA_UNAVAILABLE` unreachable.
10. Line drift: `stages/C1.md:403` / `verified-facts.md:165` cite `ModelSessionPolicy.java:107-108` for the `Gpu` record (it is `:111-112` at `4229f1091`; this audit's own earlier `:237-238` was wrong and is corrected above); `D1.md:160` `NativeSessionHandle` `552-605` (now `:552-577`, `:587-627`).

## Could not establish

1. Whether SPLADE/BGE-M3/NER retaining GPU sessions across the claim is intentional (**most important open question for the VRAM model**).
2. Whether a real CUDA illegal-memory-access on ORT 1.24.3 is ever a catchable `OrtException`.
3. JNI binding crash recovery.
4. Whether the falling-edge window has produced an observed collision.
5. Actual co-resident VRAM commitment.
6. Multi-GPU behaviour (device 0 hardcoded vs per-role `gpu_device_id`).
7. Whether the two edge detectors' asymmetric initial states matter.
8. Whether `RagContextOps.searchReranker` and `WorkerSearchService.searchReranker` are the same instance.
9. LEASE axis wire/FE projection.
10. Test-only callers of `requestGrant`.
11. Non-JVM reach into ORT metadata via `/api/debug/session-policies`.
12. Out-of-repo JVM crash-dump flags.
13. Whether `05-ai-architecture.md` staleness is tracked.
14. Test outcomes — present and named, not verified green.
