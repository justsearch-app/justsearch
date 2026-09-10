# Audit 1 — Encoder consumer surface (read-only, lane-F-A HEAD 4229f1091)

Produced 2026-09-10 by an opus subagent under a read-only brief. All paths relative to
`F:/justsearch-public/.claude/worktrees/lane-F-A/`. Every `file:line` is at `4229f1091`.

## 0. Frame: six roles, six session handles, not one

`EncoderRole` (`modules/ort-common/src/main/java/io/justsearch/ort/EncoderRole.java:19-32`) declares six: `EMBEDDING("embed")`, `BGE_M3("bgem3")`, `SPLADE("splade")`, `NER("ner")`, `RERANKER("reranker")`, `CITATION("citation-scorer")`. `InferenceCompositionRoot.compose` (`modules/indexer-worker/.../server/InferenceCompositionRoot.java:122-169`) calls `compose(...)` once per role (`:194, :248, :288, :332, :380, :444`), and each call reaches `OrtSessionAssembler.buildManager` (`modules/ort-common/.../OrtSessionAssembler.java:85-114`) which returns **a fresh `NativeSessionHandle`** (`:108-113`). **There are up to six independent `NativeSessionHandle` instances, each with its own one-permit semaphore.**

`EMBEDDING` and `BGE_M3` are mutually exclusive (`InferenceSurface.java:32-35`); `RERANKER` and `CITATION` share the `RerankerAssembly` type but are separate roles/handles (`InferenceCompositionRoot.java:156-162`).

## 1. Consumer inventory (production `modules/*/src/main`, benchmarks excluded)

### 1a. Index-time — all on the single `indexing-loop` thread

`IndexingLoop.java:580-591` creates `new Thread(this::runLoop, "indexing-loop")`, daemon. It is a **bare thread, not registered in `EngineExecutorRegistry`** (`WorkerExecutorRegistrations.java:15-20` registers only `index.watcher-reconcile`, `index.extraction-timebox`, `index.extraction-sandbox-readers`, `index.stuck-job-reaper`, `index.deferred-model-init`, `index.pdf-ocr`). `BackfillScheduler.runIdleCycle()` / `runInterleavedSplade()` are called **synchronously** from `runLoop` at `IndexingLoop.java:677` and `:739`; `BackfillScheduler` owns no thread.

| # | Caller | Method | Signature | Batch / seq | Budget |
|---|---|---|---|---|---|
| 1 | `IndexingLoop.processBatchInner` `:925` | `provider.embedDocumentBatch(texts)` | `List<float[]> embedDocumentBatch(List<String>)` — `EmbeddingProvider.java:23` | poll batch 16 (`EnvRegistry.java:648`); inner ORT cap 8 (`OnnxEmbeddingEncoder.java:291`) | none; gated `migrationActiveSupplier && provider.isAvailable()` (`:915-916`) — blue/green only |
| 2 | `IndexingDocumentOps.buildDocument` `:262` | `ep.embedDocument(content)` | `float[] embedDocument(String)` | 1 | none; skipped when `signalBus.isMainGpuActive()` (`:258`) → status `PENDING` |
| 3 | `EmbeddingBackfillOps.processEmbeddingBackfill` `:110` | `embedDocumentBatch` | as above | `backfill.embedding_batch_size`=100 (`EnvRegistry.java:653-655`), capped by `MAX_PARENT_BATCH_CHARS=512_000` (`EmbeddingBackfillOps.java:24,90`) | cycle budget |
| 4 | `EmbeddingBackfillOps:246` | `embedDocument` | | 1 (per-doc retry) | — |
| 5 | `EmbeddingBackfillOps.processChunkEmbeddingBackfill` `:398` | `embedDocumentBatch` | | 100 | — |
| 6 | `EmbeddingBackfillOps:417` | `embedDocument` | | 1 (per-chunk fallback) | — |
| 7 | `CombinedEnrichmentBackfillOps:734` | `lateChunkingProvider.embedWithSpans(lcContent, NO_SPANS)` | `EmbeddingService.ChunkedEmbedding embedWithSpans(String, int[][])` — `EmbeddingProvider.java:37` | 1 doc, one forward pass | `embedShareSpent` (3 s of 5 s cycle) `:726` |
| 8 | `CombinedEnrichmentBackfillOps:806` | `windowProbe.documentWindowCount(content)` | `default int documentWindowCount(String)` — `EmbeddingProvider.java:63` | tokenizer-only | — |
| 9 | `CombinedEnrichmentBackfillOps:857` | `provider.embedDocumentWindows(content, nextWindow, EMBED_WINDOW_SLICE)` | `default WindowSlice embedDocumentWindows(String,int,int)` — `EmbeddingProvider.java:78` | `EMBED_WINDOW_SLICE=32` (`:99`) | stop/share per slice |
| 10 | `CombinedEnrichmentBackfillOps:970` | `provider.embedDocumentBatch(subList)` | | `EMBED_ENCODE_SLICE=8` (`:82`) | stop + share per slice `:956-965` |
| 11 | `CombinedEnrichmentBackfillOps:1052` | `encoder.encodeBatch(spladeContents)` | `List<Map<String,Float>> encodeBatch(List<String>)` — `SpladeEncoder.java:332` | whole stage batch; internal sub-batch ≤4 docs (`SpladeEncoder.java:300,311,350`) | checked at stage boundary only (`:1040-1047`) |
| 12 | `CombinedEnrichmentBackfillOps:1142` | `nerService.extractEntitiesBatch(List.of(content))` | `List<NerResult> extractEntitiesBatch(List<String>)` — `NerService.java:138` | 1 doc/call ("batching regressed, item 22", `:1131`); chunked 400 tok / 50 overlap; ORT batch 16 (`BertNerInference.java:291`) | stop per doc `:1133` |
| 13 | `SpladeBackfillOps:147` | `encoder.encodeBatch` | | idle 200 / interleaved 10 (`EnvRegistry.java:669,676-678`) | interleave 5000 ms |
| 14 | `SpladeBackfillOps:165` | `encoder.encode(text)` | `Map<String,Float> encode(String)` — `SpladeEncoder.java:253` | 1 | — |
| 15 | `NerBackfillOps:99` | `nerService.extractEntitiesBatch(List.of(content))` | | 1 doc | — |
| 16 | `BgeM3BackfillOps:174` | `encoder.encodeBatch(batchContents)` | `List<BgeM3Output> encodeBatch(List<String>)` — `BgeM3Encoder.java:169` | idle 50 / interleaved 10; ORT cap 4 GPU / 2 CPU (`BgeM3Encoder.java:51-52`) | — |
| 17 | `BgeM3BackfillOps:195` | `encoder.encode(text)` | `BgeM3Output encode(String)` — `:158` | 1 | — |

Cycle budgets are the scheduler's: `CYCLE_BUDGET_MS = 5_000` (`BackfillScheduler.java:66`), `EMBED_BUDGET_SHARE_MS = 3_000` (`:79`). **No index-time encoder call has a deadline of its own**; an in-flight `session.run()` is never interrupted.

### 1b. Request-time — on `engine-call-foreground` / `engine-call-background`

`WorkerSearchCalls` (`modules/app-engine/.../WorkerSearchCalls.java:26-73`) hands each `io.justsearch.ipc.*` message to `WorkerSearchService`. Dispatch executors in `EngineKnowledgeClient` (`:168-198`): `engine-knowledge-call-foreground` / `-background`, `Mode.PLATFORM`, threads `engine-call-foreground` / `engine-call-background`. Pool sizes from `governance/retained-state.v1.json:9,11` — **`foregroundThreads: 16`, `backgroundThreads: 4`**. Selection by `EngineContext.Urgency` (`EngineKnowledgeClient.java:245-251`).

| # | Caller | Method | Signature | Batch / seq | Budget |
|---|---|---|---|---|---|
| 18 | `SearchInputCapture.capture` `:159` | `snap.bgeM3Encoder().encode(queryString)` | `BgeM3Output encode(String)` | 1 | none |
| 19 | `SearchInputCapture.prepareQueryVector` `:304` | `embeddingProvider.embedQuery(queryString)` | `float[] embedQuery(String)` | 1 | none |
| 20 | `SearchInputCapture.prepareSpladeWeights` `:337` | `idfEncoder.encode(...)` | `SpladeIdfQueryEncoder` — pure Java, no ORT | 1 | none |
| 21 | `SearchInputCapture.prepareSpladeWeights` `:339` | `onnxEncoder.encode(queryString)` | `Map<String,Float> encode(String)`; heap-only `runOnnxInferenceSingle` (`SpladeEncoder.java:264`, `:681-690`) | 1 | none |
| 22 | `WorkerSearchService.rerank` `:499` | `reranker.rerank(query, docTexts, deadlineMs)` | `RerankedResult rerank(String, List<String>, long)` — `CrossEncoderReranker.java:154` | topK 20 (`EnvRegistry.java:759`); bucketed {4,8,16,24,32,48,64}; maxSeqLen 512 | **200 ms** (`EnvRegistry.java:762`) |
| 23 | `RagContextOps.chunkRerank` `:1290` | `reranker.rerank(question, chunkTexts, config.deadlineBudgetMs())` | | topK 10 / `maxGpuCandidates` 50 | **150 ms** (`EnvRegistry.java:867-869`) |
| 24 | `RagContextOps.retrieveContext` `:451` | `embeddingProvider.embedQuery(question)` | | 1 | none |
| 25 | `RagContextOps.diversifyMmr` `:1442` | `embedQuery(question)` | | 1 | none |
| 26 | `RagContextOps.diversifyMmr` `:1455` | `embeddingProvider.embedDocument(content)` | | **1 per candidate, in a loop** up to `mmrMaxCandidates` | **none — unbounded serial ORT calls on a request thread** |
| 27 | `CitationMatchOps.matchCitations` `:239` | `crossEncoder.scoreAll(sentences, windows, docIds, threshold, deadlineMs)` | `ScoringResult scoreAll(...)` — `CitationScorer.java:116-121` | `SCORING_SUB_BATCH = 16` (`:46`) | real deadline, per sentence `:137` and per sub-batch `:208` |
| 28 | `CitationMatchOps:284` | `embeddingProvider.embedQuery(sentence)` | | 1 per sentence, loop | none |
| 29 | `CitationMatchOps:290` | `embeddingProvider.embedDocument(window)` | | 1 per window, loop | none |
| 30 | `KnowledgeSearchEngine:1001` (Head) | `knowledgeServer.client().rerank(...)` → #22 | `RerankResponse rerank(String, List<String>, long, EngineContext)` — `KnowledgeClient.java:487` | topK | `rerankConfig.deadlineBudgetMs()` |

### 1c. Background / boot

| # | Caller | Thread | Notes |
|---|---|---|---|
| 31 | `KnowledgeServer:1576` `searchRerankerInstance.rerank("warmup", List.of("warmup"), 30_000)` | `deferred-model-init` (registered `index.deferred-model-init`, `WorkerExecutorRegistrations:20`; `KnowledgeServer.java:1116-1124`) | 30 s; inside `initDeferredModels()` (`:1374`) |
| 32 | `GplJobCoordinator.scoreQueryDoc:843` | registered `head.gpl-job-coordinator` (`:216-224`), 1 thread | 1 doc/call, `RERANK_DEADLINE_MS = 5_000` (`:75`); routes through the knowledge client onto an `engine-call-*` thread |

## 2. Operation shape table

| Op | Input (today) | Output (today) | Batch/single | Metadata needed back | ORT/session leaks at the call site |
|---|---|---|---|---|---|
| **embed.document** | `List<String>` (provider prepends `documentPrefix`, `EmbeddingService.java:239,404`) | `List<float[]>`, nulls for failures | batch (8 ORT cap) | dimension — discovered, not declared: `this.dimension = detectedDim` at `EmbeddingService.java:418`; `chunkCount` via `events.onChunked` `:416` | `isUsingGpu()` read by `GpuSchedulingGauge` (`modules/core/.../GpuSchedulingGauge.java:35`) and `IndexingLoop:918` |
| **embed.query** | `String` | `float[]` | single | none | in-process TTL cache keyed on stripped text (`EmbeddingService.java:77,303-309,359`) |
| **embed.spans** | `String content`, `int[][] charSpans` | `ChunkedEmbedding(float[] primaryVector, List<float[]> chunkVectors, int chunkCount)` (`EmbeddingService.java:583`) | single-doc, multi-output | `chunkCount`, `isChunked()` | prefix length shift caller-visible (`EmbeddingService.java:504-505`) |
| **embed.windows** | `String`, `int fromWindow`, `int maxWindows` | `WindowSlice(List<float[]>, int fromWindow, int totalWindows)` (`EmbeddingProvider.java:47`) | resumable slice | `totalWindows` | `documentWindowCount` returning `1` is the "provider doesn't expose windows" sentinel (`:63-66`) |
| **expand.sparse** (SPLADE) | `List<String>` | `List<Map<String,Float>>` | batch (≤4 docs) | none; truncation goes to a file (`SpladeEncoder.java:256-257`) | `sessions.setLifecycleCallback(this::closePinnedOutput)` (`:165`) — pinned GPU output buffer ~476 MB (`:294-296`), `pinnedOutputsSupported` flips on BFC failure (`:617`); `getMaxBatchSize()` branches on `sessions.status().configured()` (`:311`) |
| **encode.unified** (BGE-M3) | `List<String>` | `List<BgeM3Output(float[] denseVector, Map<String,Float> sparseWeights)>` | batch (4 GPU / 2 CPU) | dense dim 1024 documented, not returned | `sessions.isGpuAvailable()` selects the batch cap (`BgeM3Encoder.java:230`) |
| **tag/NER** | `List<String>` | `List<NerResult(persons, organizations, locations)>` | batch of chunks (16) | none | `inference.isGpuAvailable()` routes the whole algorithm — GPU→batched, CPU→per-doc (`NerService.java:147-149`) |
| **rerank** | query, documents, deadlineMs | `RerankedResult(sortedIndices, scores, RerankSkipCause skipCause, latencyMs)` (`CrossEncoderReranker.java:460-461`) | batch, bucket-padded | `skipCause`, `latencyMs`; truncation NOT returned (WARN-once `:210-227`) | `reranker.isGpuAvailable()` sizes the candidate list (`RagContextOps.java:1265`); `sessions.reportCpuSessionFailure(cause)` from catch (`CrossEncoderReranker.java:344`) |
| **cite** | sentences, chunkTexts, chunkDocIds, threshold, deadlineMs | `ScoringResult(matches, sentencesTotal, sentencesMatched, latencyMs, sentencesScored)` (`CitationScorer.java:353-358`) | sub-batched 16 | `sentencesScored < sentencesTotal` is the partial-failure signal | CPU-only by role but still `sessions.acquire()` (`:273`) |

**Leaks a process boundary must redesign:**
1. `SessionHandle.Lease` is ORT-typed: `Lease(OrtSession, OrtSession.RunOptions, Runnable, boolean isCpu, OrtRunRecorder)` (`SessionHandle.java:179-185`); `run(Map<String,OnnxTensor>) → OrtSession.Result` (`:202`), `runPinned(...)` (`:219`).
2. Every encoder builds its own `OnnxTensor` from `sessions.environment()` — `CrossEncoderReranker.java:281-289`, `SpladeEncoder.java:520`.
3. `lease.isCpu()` is control flow — `OnnxEmbeddingEncoder.java:427`, `SpladeEncoder.java:603,625,730,895`, `CrossEncoderReranker.java:304`.
4. `acquireCpu()` is a second lease type (`NativeSessionHandle.java:511-514`) used mid-call for BFCArena fallback (`OnnxEmbeddingEncoder.java:444`, `SpladeEncoder.java:611,633,735,903`).
5. `NativeSessionHandle.isBfcArenaFailure(OrtException)` is a public static consumed cross-module (`:535`; imported at `OnnxEmbeddingEncoder.java:12`, `SpladeEncoder.java:20`), contradicting `SessionHandle.java:19-21`.
6. Pinned-memory lifecycle callback — `SpladeEncoder.java:165`; `releaseGpu()` invokes it while holding the semaphore (`NativeSessionHandle.java:369-377`).
7. `OrtCudaStatus` read by three request-path classes (`RagContextOps`, `NerService`, `WorkerHealthService` via `DefaultWorkerAppServices.java:252`).
8. No operation returns model identity or a truncation flag.

## 3. Producer cardinality — verdict on design §4

**Confirmed, with four corrections.** `IndexingLoop.java:580` is the only index-time producer thread; `BackfillScheduler` runs synchronously on it. The semaphore exists: `Semaphore gpuInferenceSemaphore = new Semaphore(1)` (`NativeSessionHandle.java:117`), acquired in `acquire()` (`:289`), released via the `Lease` (`:324`).

1. **Per-role, not process-wide.** Six roles → six handles. Index-time embed (#1–#10) and request-time query embed (#19, #24–#26, #28–#29) contend on the same `EMBEDDING` handle. Index-time work never uses `RERANKER` or `CITATION`. Embed + SPLADE + rerank can be in `session.run()` simultaneously on three handles.
2. **CPU leases bypass the semaphore.** `acquireCpu()` returns a no-op release (`:511-514`); `acquire()` also returns unsemaphored when the selected session is not GPU (`:337-338`) and on the post-wait re-check (`:311-317`). `CITATION` is CPU-only by policy (`InferenceSurface.java:29`) — no serialization; up to 16 foreground threads.
3. **Request-time overlap is up to 20 threads**, not one: `foregroundThreads: 16`, `backgroundThreads: 4`, plus `gpl-job-coordinator` (1) and `deferred-model-init` (1).
4. **The one-producer test does not exist.** `stages/D2.md:18, :187` (D2-4) schedule it. `NativeSessionHandleConcurrentStressTest` (`:83-86`) marks the semaphore re-check path NOT COVERED (`:47`).

| Role | Index-time producer | Request-time overlapper | Serialized together? |
|---|---|---|---|
| `EMBEDDING` | `indexing-loop` (#1–#10) | `engine-call-*` ×20 (#19, #24, #25, #26, #28, #29) | yes |
| `SPLADE` | `indexing-loop` (#11, #13, #14) | `engine-call-*` (#21), only when `justsearch.splade.query_mode != "idf"` (default `"onnx"`, `ResolvedConfigBuilder.java:1276`) | yes |
| `BGE_M3` | `indexing-loop` (#16, #17) | `engine-call-*` (#18) | yes |
| `NER` | `indexing-loop` (#12, #15) | none | n/a |
| `RERANKER` | none | `engine-call-*` (#22, #23, #30), `gpl-job-coordinator` (#32), warmup (#31) | among themselves |
| `CITATION` | none | `engine-call-*` (#27) | no — CPU-only, unsemaphored |

C1 interruptible waiter: commit `3b6a9914e`, `NativeSessionHandle.java:286-334`: interruptible `acquire()` plus three interrupt re-checks (`:299-307`) throwing `CancellationException`; `acquired` flag `finally` (`:329-334`). `releaseGpu()` still uses `acquireUninterruptibly()` (`:367`).

Chunk reranker is the same instance: `RagContextOps.getChunkReranker()` returns `searchReranker` (`:1332-1334`); `ChunkRerankerConfig`'s `modelPath`, `gpuEnabled`, `gpuDeviceId`, `maxSequenceLength` (`RerankerConfig.java:179-189`) cannot take effect.

## 4. Model-ready gating and degradation

`modelReadyLatch` `CountDownLatch(1)` at `KnowledgeServer.java:295`; javadoc `:269-294`. Counted down at `:1679` (success), `:1706` (`finally`), `:2566` (test seam). `initDeferredModels()` launched unconditionally from `start()` at `:1072` on `deferred-model-init` (`:1116-1124`).

Consumer 1 — migration enumerator `:2711`, 120 s await, proceeds without inline embedding on timeout. Consumer 2 — `WorkerSearchService.awaitModelsReady` (`:310-330`), `MODEL_READY_TIMEOUT_MS = 120_000L` (`:129`), degraded path on timeout. Gated: `search :422`, `rerank :486`, `retrieveContext :842`, `matchCitations :888`. Ungated: `warmUpSearchPath()` (`:417-419`, runs before the latch releases, `KnowledgeServer.java:1596` vs `:1679`), `suggest :542`, `fetchDocuments :592`, `listFolders :922`, `getSessionPolicies :1615`, `WorkerHealthService.check :180`, every backfill op.

A timed-out query sees a partially wired surface (`wireX` publishes at `:1458, 1492, 1512, 1531, 1542, 1572, 1617`). One latch gates six encoders; javadoc `:290-293` declines to split it.

`InferenceSurface.close()` at `:53-61`. Single unwrap site `KnowledgeServer.initDeferredModels()` (`:1374`, composition `:1424-1431`). Absence is an unwired null: `EncoderBindings` (`:24-27`), `ModelContext` (`:11-12`).

Degradation: embedding → `NoOpEmbeddingProvider` (`EmbeddingProviderLifecycle.java:61,119`); AUTO dense resolved off (`WorkerSearchService.java:436-437`); vector-only throws `IllegalArgumentException` → `INVALID_ARGUMENT` (`SearchPlanner.java:99-107`); hybrid degrades to `LegSet.Bm25Only` (`SearchPlanner.java:275`) with `hybrid_fallback_reason`. Reranker absent → `RerankResponse{skipped=true, skipReason="MODEL_NOT_LOADED"}` (`WorkerSearchService.java:487-493`). Citation absent → cosine fallback or `emptyResponse("EMBEDDING_UNAVAILABLE")` (`CitationMatchOps.java:208-213, 274-333`).

Composition failure: all six `buildAssembly` throw; conversion to `Optional.empty()` only in `InferenceCompositionRoot`'s per-role helpers (config-not-ready / no-variant / `catch (Exception)`). A failed BGE-M3 silently falls back to composing SPLADE (`:149`); `assertCitationIsCpuOnly` (`:530-542`) throws inside the try (`:443`) so degrades rather than fails loud.

Status surfaces: `/api/inference/encoders` (`EncoderRuntimeController.java:57-92`, `EncoderRuntimeView` six components `EncoderRuntimeView.java:24-38`; `EncoderRuntimeExplainer.explainAll` iterates the policy snapshot's keys so an absent role never appears). `/api/debug/session-policies` (`SessionPoliciesController.java:74-91`; `"surface-unavailable"` pre-init). `embedding_enabled`/`splade_enabled`/`ner_enabled` default `true` (`IndexStatusOps.java:140-142`), set only at `KnowledgeServer.java:1667-1670`. `/api/health` and `/api/debug/state` report no encoder readiness. Only `ReadinessDimension.EMBEDDING` exists (`ReadinessDimension.java:18`). `AI_OFFLINE` is an LLM error code (`ApiErrorCode.java:97`), not an encoder flag.

## 5. Dependencies of the assembly types

Zero Lucene / protobuf / gRPC / `io.justsearch.app.*` imports across `InferenceSurface`, all `*Assembly`, all `*Shape`, `SessionHandle`, `NativeSessionHandle`. **design.md's "(ArchUnit-pinned)" is false**: `IndexWriterOwnershipTest.onlyLuceneOwnersMayDependOnLuceneClasses` (`IndexWriterOwnershipTest.java:52-66`) allowlists `io.justsearch.indexerworker..` (`:25-28`), the package holding `InferenceSurface` and eight of ten assembly/shape types. Rule 6b (`LayeringEnforcementTest.java:242-262`) holds. `modules/worker-core/build.gradle.kts:9` `api(project(":modules:adapters-lucene"))` re-exports Lucene (latent). `OrtRunChokePointTest` (`:99-106`) guarantees every production inference call funnels through `Lease#run`/`#runPinned`. `ClosurePropertyTest.encoderPrimaryCtorsMustBePure` (`:204-208`) forbids filesystem I/O in encoder constructors. ORT leaks in public signatures: every `*Assembly` exposes `SessionHandle`; `PolicySnapshot.models() → ModelSessionPolicy.cpu() → Cpu(OptLevel)` where `OptLevel` is `ai.onnxruntime.OrtSession.SessionOptions.OptLevel` (`ModelSessionPolicy.java:4, :121`). Non-ORT blockers: DJL `HuggingFaceTokenizer`/`Vocabulary` on four assemblies; `BgeM3Shape.vocabulary` ~250K `String[]`; `SpladeAssembly.truncationEvidencePath` is a `Path`; `SpladeShape.OutputFormat` package-private (`SpladeEncoder.java:76`).

## 6. Binding an encoder call to an index generation — not implemented

`EmbeddingCompatibilityController` (`modules/worker-core/.../embed/EmbeddingCompatibilityController.java:73-84`); `allowQueryEmbeddings()` is `state == COMPATIBLE` (`:516-518`), a process-wide boolean. `SearchInputCapture.prepareQueryVector` (`:276-319`) checks only the boolean and `vec.length == 0`. No dimension guard on the read path (`ReadPathOps.java:285-298, 328-354`). Persisted identity is Lucene commit user data `index_fingerprint` (`IndexFingerprint.COMMIT_META_KEY:101`, `MODEL_INPUT_KEYS:118-119`) plus `embedding_model_sha256` via `EmbeddingMetadataOverlay` (`:56-70`). `IndexMetadataParityGuard` mismatch never refuses a query. Nothing re-binds mid-process.

**Candidate gap (runtime probe needed):** ECC reads `ingestLifecycle` (`KnowledgeServer.java:1788-1792, 1832-1841`) while queries serve `searchLifecycle` (`:985`; during migration `= blue`); `initEmbeddingCompatibilityController()` at `:1019` runs after migration branches (`:815-850, 906-935`) point ingest at a fresh Green, so `refresh()` sees `docCount == 0` → `COMPATIBLE` (`EmbeddingCompatibilityController.java:219-226`) while Blue holds old-encoder vectors.

## Design-vs-code contradictions

1. "query NER" does not exist (design.md §4, §5).
2. "(ArchUnit-pinned)" at design.md §5 is false.
3. The semaphore is per-role and GPU-only; §4's "queues behind the batch already on the GPU" does not hold for `RERANKER`/`CITATION`.
4. "lane F pins that with a test (16)" — no such test; D2-4.
5. `SearchInputCapture.java:37-41` javadoc overclaims its ArchUnit guard (`IndexerWorkerGuardrailsTest.java:65-106` constrains only `services.plan..`/`services.respond..`).
6. `EncoderBindings.java:21` still describes "the gRPC incoming-RPC thread".
7. `AI_OFFLINE` is not an encoder gate.
8. `ChunkRerankerConfig` declares four dead fields.
9. `*_enabled` status flags default true until `KnowledgeServer.java:1667`.

## Could not establish
1. Whether HTTP accepts traffic before `initDeferredModels()` starts.
2. Whether `EMBEDDING`, `SPLADE`, `RERANKER` GPU sessions run concurrently in practice.
3. Observed batch sizes / sequence lengths (only configured bounds).
4. Whether the §6 migration-window gap fires.
5. The default of `mmrMaxCandidates`.
