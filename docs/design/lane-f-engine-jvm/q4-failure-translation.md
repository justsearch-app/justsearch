# Q4 failure translation handoff

PACKAGE: Q4 | STATUS: READY FOR BUILD

The three committed fixes were treated as a draft. This handoff distinguishes retained draft
fixes from corrections added by the new owner. Java tests have not been run; Gradle remains
the orchestrator's responsibility. Paths and line numbers below refer to the final working tree.

## Refusal map and design

Prefixes: `engine` = `modules/app-engine/src/main/java/io/justsearch/app/engine/`;
`worker` = `modules/app-services/src/main/java/io/justsearch/app/services/worker/`;
`conversation` = `modules/app-services/src/main/java/io/justsearch/app/services/conversation/`;
`handlers` = `modules/app-services/src/main/java/io/justsearch/app/services/registry/operations/handlers/`;
`http` = `modules/ui/src/main/java/io/justsearch/ui/api/`;
`worker-services` = `modules/worker-services/src/main/java/io/justsearch/indexerworker/services/`.

| Path | Sources and required surface |
| --- | --- |
| Admission | `engine/EngineAdmissionController.java:131,151,247`: aggregate/context capacity, frozen owner, stale/finished work. HTTP front door: `http/ApiSecurityFilters.java:184,201`; `http/RequestEngineWork.java:20,29,36`. Only front-door refusal permits automatic replay. |
| Executor | `engine/DefaultEngineExecutorRegistry.java:68,72,232,276,277,321,345,348,352,359`: registry/instance, task/queue and timer refusal. `engine/EngineKnowledgeClient.java:701,711,817,925,944,963,1010,1334,1463`: retained call, unary submission, stream/scan delivery and queued root walk. The real call client emits admission ENGINE_LIMIT for executor saturation. |
| Common HTTP writer | `http/ApiErrorHandler.java:89,95,101,112,120`: unwrap only CompletionException/ExecutionException, recognize both types, write typed reason, capacity HTTP 429 and Retry-After. CLOSED/finished work is SERVICE_UNAVAILABLE. Mid-action responses have retrySafe=false. Global executor fallback: `http/LocalApiServer.java:504`. |
| Knowledge HTTP | `http/KnowledgeSearchController.java:503,726,735,827,870,916`: search, nested/outer status, suggest, similar and entities. Status must not replace refusal with sparse success. |
| Indexing HTTP | `http/IndexingController.java:145,184,231,242,273,334,375,396,414,434,452,495,546,588,623,669,739,808,872,945,975,1005,1036`: all Engine-facing catches, including nested enqueue/counts, root lifecycle, status and migration cutover/rollback. Nested counts rethrow to the outer writer. |
| Operations/retrieval HTTP | `http/OperationsController.java:262,389`; `http/RetrieveContextController.java:205,274,287`: synchronous and asynchronously wrapped failures go through the writer before generic translation. |
| MCP | `http/mcp/McpToolSurface.java:2167,2176`: tool-error classification and every generic catch preserve both refusal types, including search status enrichment, prompts and resources. `http/mcp/McpProtocolHandler.java:186,191,198`: protocol boundary retains JSON-RPC id, typed code, Retry-After and retrySafe=false. |
| Document port | `worker/RemoteDocumentService.java:155,250,303,320,412,459,567,677,750`: capture/submission, fetches, both retrieval overloads, pre-search, fulltext fallback and adjacent port calls. Refusals escape before fallback; they must never become successful empty retrieval. `worker/EngineRefusals.java:15,20` preserves direct/wrapped identity. |
| RAG conversation | `conversation/spi/RAGContext.java:422,762,791,841`: producer failure reason is terminal; refusal escapes scoped/open retrieval and batch fallback. `conversation/ConversationEngine.java:556,1064,1088`: catch refusal at actual injector dispatch, emit typed SSE error with retryAfterSeconds and retrySafe=false, terminate before messages/LLM dispatch. SSE carries the delay because its HTTP stream may already be open. |
| Other document conversations | `conversation/spi/DocAccess.java:219,234`; `BatchDocAccess.java:175`; `SelectionContextInjector.java:623,638`: preserve direct and future-wrapped refusals before inline/unavailable/partial-selection fallback. `conversation/HierarchicalShapeRunner.java:122,325`: preserve refused document loading and render the same terminal error through `ConversationEngine.refusalEvent`. Ordinary non-refusal fallbacks remain available. |
| Indexing operations | `handlers/ClearFailedJobsHandler.java:42,55`; `RetryIndexingJobHandler.java:52,69`; `CancelIndexingJobHandler.java:53,69`; `IndexGcHandler.java:88,123`; `SettleIndexHandler.java:84,129`; `AddWatchedRootHandler.java:85,108`; `RemoveWatchedRootHandler.java:65,89`; `ReconcileRootHandler.java:49,65`; `ResolvePathHashHandler.java:64,91,115`; `ExcludesHandlerSupport.java:33,45`; `TriggerOfflineProcessingHandler.java:45`. Preserve refusal before ordinary OperationResult.failure translation; recovery already propagates unchanged. |
| Indexing ports/watchers | `worker/KnowledgeClient.java:709,753,783,858,1355,1420`; `worker/MigrationOps.java:95,122,151,178,202,243,298`; `worker/SyncOps.java:158,224`; `worker/RootLifecycleOps.java:293,396,413,454,480,612`: preserve capacity before optional fallback or incomplete-removal translation. Background scan refusal records a failed walk; periodic reconciliation logs failure and retries on its next tick. Synchronous watch/add/remove refusal reaches HTTP/MCP. |

OpenAiCompatController.java was not edited. The reviews found no corresponding broad local
Engine-exception catch requiring the HTTP writer change there.

## Root-removal lifecycle and invariant

Success requires durable and live removal, all removed-root watchers retired, and actual scan/
reconciliation producer exit before final deletion. A queued producer must validate membership
and its captured retirement revision, including after a remove/re-add of the same path.
Incomplete cleanup retains durable membership and an explicit retry repeats cleanup.

- `worker/WatchedRootsState.java:49`: shared lifecycle lock across client instances.
- `worker/RootLifecycleOps.java:307,331,349,364,367,315,317,319`: registration, persisted initialization
  obligation, watcher creation, walk submission, initialization completion. A refusal leaves
  ROOT_INITIALIZATION_PENDING in the existing persisted walkError field. Re-add resumes with
  the original collection; completed-root re-add stays idempotent, including during a queued walk.
- `worker/RootLifecycleOps.java:221,240,317,551,575,582`: initial (labelled/unlabelled),
  forced/excluded and restored scans capture a producer fence before queuing. Pruning, admission
  and completion updates run inside that fence. Old callbacks cannot mutate a replacement root
  or admit its predecessor's collection. Removal drains rather than cancelling; it can wait for
  a long scan.
- `worker/WatchedRootsState.java:72,88,286`: capture producer fences before submission; their
  worker bodies own the shared lifecycle lock until actual exit. Successful removal advances a
  retirement revision for the removed subtree. Queued overlapping producers reject intervening
  retirement, including a remove/re-add, rather than recreating documents from a stale submission.
- `worker/RootLifecycleOps.java:313,604`; `worker/KnowledgeClient.java:290,1681,1696`;
  `worker/IngestServiceCalls.java:172`: watch registration captures the same fence and owns it
  inside the actual Engine worker, through registry entry and worker exit. Caller deadlines cannot
  release this ownership. Add releases the caller's lifecycle monitor before invoking Engine,
  avoiding a caller/worker monitor deadlock. Queued old watch calls reject intervening retirement.
  `worker/WatchedRootsState.java:60,67,286`: shared identity tickets prevent duplicate initialization
  while the caller monitor is released; removal clears retired tickets without letting an old caller
  clear a replacement incarnation's ticket.
- `worker/SyncOps.java:126,169,173,275`; `worker/IngestServiceCalls.java:57`: reconciliation
  captures its fence before the Engine unary submission and acquires it inside the worker call.
  Caller timeout cannot release it. Periodic and legacy watched-root reindex additionally require
  current membership; explicit reconciliation retains support for unwatched paths.
- `engine/RecordedIngestionCoordinator.java:450,1031`; `engine/EngineRoot.java:578,579`:
  accepted core.reindex selects the watched producer, and bulk rebuild binds the watched captured
  producer. Both frozen watched plans reject missing membership even if submission follows removal.
- `engine/EngineKnowledgeClient.java:1216,1240,1263,1312`: recorded streaming and captured scans
  use the actual producer fence. Every captured root gets its fence before the first root starts.
  Explicit ingestion retains unwatched-root support and rejects queued work invalidated by removal.
- `engine/EngineKnowledgeClient.java`: scan producer runs synchronously; return
  follows its exit. `worker-services/RootWatcherRegistry.java:109,192`: unwatch invalidates the
  registration and drains issued event effects before returning.
- `worker/RootLifecycleOps.java:419,435,443,464,486`: lock removal, reject incomplete nested
  removal covered by a retained ancestor, unwatch root/nested roots, validate deletion error AND
  negative count, then publish removal. Ancestor coverage requires removing the ancestor first.
- `worker-services/WorkerIngestService.java:1177,1203,1219`:
  fenced deletion/commit or failure response, including null-message negative sentinel.
- `worker/WatchedRootsState.java:304`: persist remaining roots before dropping live metadata;
  failed persistence retains both retry owners. Removal also clears obsolete walk errors.
- `http/IndexingController.java:260`; `handlers/RemoveWatchedRootHandler.java:76`:
  negative cleanup result is incomplete failure, never successful removal.

## Finding and regression coverage

| Finding/review | Change and regression oracle |
| --- | --- |
| W13-F1; review 1 I3 | Retain the common writer and indexing catch fixes. KnowledgeSuggestSaturationTest and IndexingControllerSaturationTest saturate the actual Engine call pool after admission and assert 429/reason/delay/unsafe replay. Original code returns 500. ApiErrorHandlerResolveTest covers direct/wrapped classification. |
| Review 2 I1 | Retain document/handler/status refusal preservation. RemoteDocumentServiceRefusalTest exercises real producer overloads and fallback; EngineRefusalHandlersTest runs real handlers rather than a mocked dispatcher; WorkerPortRefusalTest covers optional port translation. Added MCP preservation and McpProtocolHandlerTest#surfaceRefusalsReachProtocolAcrossToolsAndResources; old MCP catches return tool/resource success envelopes or generic errors. |
| W13-F2; review 1 I1/I2 | Retain deletion sentinel and HTTP/operation consumer fixes. RootLifecycleOpsIdempotencyTest#incompleteRemovalRetainsPersistedRootsUntilExplicitRetry covers exception, response error, empty-error negative count, unwatch and deletion refusal, live/reloaded metadata, and successful retry. IndexingControllerFailureTest and RemoveWatchedRootHandlerTest reject negative counts. Original code loses roots or claims success. |
| Review 2 I3 | Retain watcher lifecycle locking and all three existing latch-controlled RootLifecycleOpsConcurrencyTest interleavings. They distinguish orphaned watcher creation from coherent post-removal addition. |
| Review 3 I1 | Conversation dispatch now handles refusal terminally. SubstrateDrivenEngineTest#realRagProducerRefusalsTerminateDispatchBeforeLlm uses real RemoteDocumentService/RAGContext/ConversationEngine, open/scoped retrieval, primary/fallback refusals and both direct/wrapped types; asserts typed terminal error and zero LLM calls. Draft code calls the LLM. Renamed the injector-only test accurately. |
| Review 3 I2 | RootLifecycleOpsConcurrencyTest#removalDrainsActiveAndFencesQueuedRootScans pauses an actual scan callback between batches across initial/forced/restored entry points; removal waits, last batch is deleted, queued callbacks cannot scan removed membership. Removing the membership guard fails its oracle. #removalDrainsReconciliationAndFencesStaleRootSnapshots covers reconciliation. #failedPersistenceRetainsLiveAndDurableRemovalObligation and #retainedAncestorPreventsFalseCompletedSubtreeRemoval cover the additional invariant gaps. |
| Review 3 I3 | RootLifecycleOpsIdempotencyTest#refusedInitializationResumesOnReAddIncludingAfterReload covers watch/submission refusal, recovery, original collection, live/reloaded pending state, resumed watcher/walk, and completed idempotency. Draft code performs no watcher/walk on retry. |
| W13-F3; review 2 I2 | Retain reason-field agreement and no-empty-fallback-on-failure. RAGContextTest#fallbackFailedTerminal and #openRetrievalWithUnavailableIndexIsFetchFailed use the real producer. #successfulOpenRetrievalWithNoHitsIsStillNoContent distinguishes successful empty retrieval from dependency failure, guarding against an overbroad FETCH_FAILED fix. |
| Q4n I1 | EngineRootRetirementTest#reconciliationDeadlineDoesNotReleaseActualProducerFence pauses the real Worker reconciliation during its disk walk or admission, expires the Engine caller, then removes the root. Removal must wait for worker exit and delete all resumed admissions. The prior caller-only fence permits early success and orphaned jobs. The synchronous lifecycle regression now invokes the actual fenced port instead of bypassing its function. |
| Q4n I2 | EngineRootRetirementTest#removalDrainsRealRecordedReindexBetweenBatches pauses actual SQLite admission between 2,000-file batches; parent and nested-root removal must drain it. #queuedRecordedProducersCannotAdmitAfterRemoval covers streaming ingestion, watched reindex, captured rebuild, submission after removal and new explicit unwatched ingestion. RecordedIngestionCoordinatorTest#dispatchesAcceptedWatchedReindexThroughMembershipFencedProducer exercises accepted runner dispatch. Prior code bypasses removal ownership and routes reindex through unrestricted ingestion. |
| Q4n2 I1 | SubstrateDrivenEngineTest#documentInjectorRefusalsTerminateRealSummaryDispatch exercises real summary dispatch across single/batch/hierarchical document loading and canonical/display/line ranges, items, citations and result sets. Direct and asynchronously wrapped admission/executor refusals, including the real document producer, must emit the typed error, delay and retrySafe=false with zero LLM calls. Prior catches degrade or omit refused content. #ordinaryDocumentFailuresRetainSummaryFallbacks distinguishes refusal from ordinary inline, citation and partial-result fallbacks. |
| Q4n2 I2 | EngineRootRetirementTest#watcherDeadlineCannotReleaseRemovalBeforeRegistryEntry pauses the actual Engine watch worker before the real watcher registry, expires its caller, then attempts removal across direct/add/restored entry points. Removal must wait and retire the delayed registration. #queuedWatcherCannotReplaceReaddedRoot rejects an old queued watch after remove/re-add. RootLifecycleOpsConcurrencyTest#concurrentAddDoesNotDuplicateInitializationBetweenWorkerExitAndCallerReturn preserves add idempotency with the caller monitor released. Previous code permits early removal or stale watcher registration. The Engine retirement oracle also verifies the exact Lucene deletion prefix; runtime remains mocked, so this is a deletion-boundary check rather than a real-index search assertion. |
| Q4n2 I3 | RootLifecycleOpsConcurrencyTest#queuedLegacyScansCannotModifyReplacementRoot queues initial, unlabelled, forced, excluded and restored walks, removes the root, re-adds it under collection B, then releases the old callback. It must neither prune/admit nor complete the replacement's live/reloaded state; the replacement's own callback still runs. Prior membership-only checks run the old collection and mark the replacement completed. |

## Q4n2 build handoff

```text
./gradlew.bat :modules:app-services:test --tests '*SubstrateDrivenEngineTest' --tests '*DocAccessTest' --tests '*BatchDocAccessTest' --tests '*SelectionContextInjectorTest' --tests '*HierarchicalShapeRunnerTest' --tests '*RootLifecycleOpsConcurrencyTest' --tests '*RootLifecycleOpsIdempotencyTest'
./gradlew.bat :modules:app-engine:test --tests '*EngineRootRetirementTest' --tests '*EngineQueuedRootOwnershipTest' --tests '*EngineIndexingWorkflowTest'
./gradlew.bat :modules:app-services:test :modules:app-engine:test
./gradlew.bat :modules:app-services:spotlessCheck :modules:app-engine:spotlessCheck
```

Observed Q4n2 checks: `git diff --check` passed; Java 25 compiler parsing reported
`Parsed 12 Java files; syntax errors=0`; Node whitespace/final-newline checks passed for all 13
changed files. New test imports, method names and signatures were checked against their real
classes. Gradle/JUnit and Node unit tests remain unrun; parsing and signature inspection do not
establish compilation or passing tests. The scratch parser was removed. Changes remain uncommitted.

Q4n2 changed files:

- `modules/app-services/src/main/java/io/justsearch/app/services/conversation/`:
  ConversationEngine.java, HierarchicalShapeRunner.java, spi/DocAccess.java, spi/BatchDocAccess.java,
  spi/SelectionContextInjector.java.
- `modules/app-services/src/main/java/io/justsearch/app/services/worker/`:
  IngestServiceCalls.java, KnowledgeClient.java, RootLifecycleOps.java, WatchedRootsState.java.
- `modules/app-services/src/test/java/io/justsearch/app/services/`:
  conversation/SubstrateDrivenEngineTest.java, worker/RootLifecycleOpsConcurrencyTest.java.
- `modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRootRetirementTest.java`.
- `docs/design/lane-f-engine-jvm/q4-failure-translation.md`.

## Q4n build handoff

```text
./gradlew.bat :modules:app-engine:test --tests '*EngineRootRetirementTest' --tests '*RecordedIngestionCoordinatorTest' --tests '*EngineRecordedIngestionProducerTest' --tests '*EngineRecordedProducerExitTest' --tests '*EngineKnowledgeClientServingViewLifetimeTest' --tests '*EngineSyncDirectoryTest'
./gradlew.bat :modules:app-services:test --tests '*RootLifecycleOpsConcurrencyTest' --tests '*RootLifecycleOpsIdempotencyTest' --tests '*SyncOps*'
./gradlew.bat :modules:app-engine:test :modules:app-services:test
./gradlew.bat :modules:app-engine:spotlessCheck :modules:app-services:spotlessCheck
```

Gradle/JUnit execution remains with the orchestrator. Signature inspection and compiler parsing
are static checks, not compilation or evidence that these regressions passed.
Observed Q4n checks: `git diff --check` passed; the temporary Java 25 compiler parser reported
`Parsed 10 Java files; syntax errors=0`; Node whitespace/final-newline checks passed for all 11
changed files. The parser scratch directory was removed. No Gradle, JUnit or Node unit tests ran.

## Files from the earlier correction

`modules/app-services/src/main/java/io/justsearch/app/services/`:
conversation/ConversationEngine.java; worker/EngineRefusals.java; worker/KnowledgeClient.java;
worker/RootLifecycleOps.java; worker/SyncOps.java; worker/WatchedRootsState.java.

`modules/app-services/src/test/java/io/justsearch/app/services/`:
conversation/SubstrateDrivenEngineTest.java; conversation/spi/RAGContextTest.java;
worker/RootLifecycleOpsConcurrencyTest.java; worker/RootLifecycleOpsIdempotencyTest.java.

`modules/ui/src/`: main/java/io/justsearch/ui/api/mcp/McpToolSurface.java;
test/java/io/justsearch/ui/api/mcp/McpProtocolHandlerTest.java; this handoff document.

## READY FOR BUILD

Focused checks first:

```powershell
./gradlew.bat :modules:app-services:test --tests '*RootLifecycleOps*Test' --tests '*RootCompletionMembershipTest' --tests '*WatchedRootsStateTest' --tests '*SyncOpsReconcileVerificationTest' --tests '*WatchedRootScanCollectionTest' --tests '*RemoteDocumentServiceRefusalTest' --tests '*WorkerPortRefusalTest' --tests '*EngineRefusalHandlersTest' --tests '*RemoveWatchedRootHandlerTest' --tests '*ExcludePatternsResolutionTest' --tests '*RAGContextTest' --tests '*SubstrateDrivenEngineTest'
./gradlew.bat :modules:ui:test --tests '*KnowledgeSuggestSaturationTest' --tests '*IndexingControllerSaturationTest' --tests '*IndexingControllerFailureTest' --tests '*KnowledgeSearchControllerExecutorRefusalTest' --tests '*ApiErrorHandlerResolveTest' --tests '*OperationsControllerTest' --tests '*McpProtocolHandlerTest'
./gradlew.bat :modules:app-services:test :modules:ui:test
./gradlew.bat :modules:app-services:spotlessCheck :modules:ui:spotlessCheck
```

Observed checks: `git diff --check 409b1926b` passed; temporary JDK compiler parse
(`java --enable-preview --source 25 tmp/q4-parse/ParseChangedJava.java`) reported
`Parsed 12 Java source files; syntax errors=0`. This was parsing, not compilation or JUnit.
The scratch parser was removed after use. No Node unit tests were applicable or run.
All file writes succeeded. No commit, stack operation or Gradle invocation was performed.
