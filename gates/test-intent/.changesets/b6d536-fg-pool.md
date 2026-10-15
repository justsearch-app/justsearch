---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: 886833dd-a08f-492f-a40a-cc0976b5ce5e
---

# Test-intent entries

Task t-20261005-b6d536, the foreground inference pool (scenarios FP1 to FP11, agreed; "queue within the deadline and give the refusal its own code" is the orchestrator's
recorded choice under the owner's end-to-end delegation, not the owner's words). The Engine's foreground inference
lane now waits for a thread in a queue as long as its thread count, within the request's deadline
and the search's semantic deadline, which both count from the start of the call; a full lane
refuses with ADMISSION_INFERENCE_BUSY, and a closed call pool is reported as closed, not as a
capacity refusal. This changeset covers only this task's files.

Evidence regenerated on b599d4d7e. The fail-before of b6d536-fg-pool-before.json runs on f648453b5,
the parent of f8c4967ed, which added the checks. It applies
`gates/test-intent/evidence/b6d536-fg-pool-before-compat.patch`, because the test provider in
EngineKnowledgeClientInferenceQueueTest now uses the later provider-availability API and the checks
name `Reason.INFERENCE_BUSY`. The patch makes these changes:

- It adds `EmbeddingProvider.Availability` and a default `availability()` that returns null.
- It turns `isAvailable()` into a default that reads `availability()`. Every production provider
  at f648453b5 implements `isAvailable()` itself.
- It appends `INFERENCE_BUSY` to `EngineAdmissionException.Reason`.
- It gives the three exhaustive switches over that enum (ConversationEngine, RequestEngineWork and
  ApiErrorHandler) an arm that throws, because nothing there raises it.

The repaired check closedExecutorsAreReportedAsClosedNotAsCapacity fails there by assertion: the
closed pool reads as ENGINE_LIMIT, not CLOSED.

b6d536-fg-pool-candidate.json (entries 11 and 12) now also has a before run on f648453b5, without a
compat patch. admissionFetch.test.ts compiles there. The ADMISSION_INFERENCE_BUSY retry case fails
by assertion: send is called once, not twice. The never-replays case passes there. The three Java
checks of entry 11 do not compile on f648453b5, so the before-state is not reproducible for them:
they name `EngineAdmissionException.Reason.INFERENCE_BUSY` and
`ApiErrorCode.ADMISSION_INFERENCE_BUSY`, both introduced by f8c4967ed. Walking that reason through
ApiErrorHandler and the MCP handler needs a mapping for it. Any mapping a compat patch supplied
would be the behaviour these checks test, which an add-only patch may not carry. The thrown arm in
b6d536-fg-pool-before-compat.patch makes the walk end in an exception, not an assertion. The
candidate check queuedVectorOnlySearchPastItsSemanticBudgetIsTheTypedDeadline passed in five further
broad candidate runs of all 29 b6d536-fg-pool-before.json checks on 3166f2506. The verifier saw it
fail once with an untyped deadline in a broader run, and that failure was not reproduced here.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineKnowledgeClientInferenceQueueTest.java"
      ],
      "class": "New",
      "behaviour": "Through the production EngineKnowledgeClient, executor registry and admission controller, with a worker whose \"hold\" searches block like a native run that holds its thread. FP1: with the packaged shape (16 foreground threads, queue 48) 9 and 16 concurrent dense searches from distinct clients give 8 running and N-8 queued, none ends at once, and all complete once released; the inference lane has as many queue places as threads within the foregroundQueue bound (16/48 -> 8+8, 1/1 -> 1+1, 2/1 -> 1+1, 16/4 -> 8+4) and the text lane keeps the policy queue. FP4: with the inference threads and queue full the next inference search is refused with reason INFERENCE_BUSY, Retry-After 1, its admission released, and a text search still runs. FP2: for every kind of search that may run inference (dense, AUTO dense, SPLADE, rerank-only, HYBRID, VECTOR and SPLADE modes) a request that waited 600 ms runs with the CallContext deadline set at submission plus its budget, and the same deadline in its InferenceRequest. FP3: for the same kinds a request whose whole 800 ms deadline passes in the queue ends DEADLINE_EXCEEDED within 1.5 s, leaves the queue, never runs, and has released its admission slot and closed its serving view by the time its caller sees the outcome; with the real WorkerSearchService a hybrid search queued past its 2 000 ms GPU semantic cap answers keyword-only with SEMANTIC_BUDGET_EXCEEDED and cause contended without calling the encoder, and a VECTOR-only search queued past D - 500 ms is the typed deadline naming SEMANTIC_BUDGET_EXCEEDED without calling the encoder. FP7: shutdown of a client with one request queued on either lane gives it cancellation engine_shutdown, releases its slot and awaitDrained returns; client cancellation of a queued request on either lane removes it and its body never runs. FP8: with the inference lane full a text search, a document fetch and an ingest deleteById run within 2 s, and an extra inference request is refused by admission with ENGINE_LIMIT using the slot reserved for text. FP9: a queued request is not counted by ForegroundLoad until it starts. FP10: behind a native run that outlived its deadline a queued search ends by its own 600 ms deadline within 1.6 s and never runs. FP5: a per-client inference limit refuses with CONTEXT_LIMIT and a lowered aggregate limit of three with ENGINE_LIMIT while the inference lane has room, and a text search still runs. The FP5, FP8, FP9 and FP10 checks are preservation checks and pass before and after; the FP7 checks name the preserved shutdown and cancellation outcome but need a queue to hold a request, which the inference lane did not have before, so the inference-lane cases fail before by assertion.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "FP1",
          "quote": "with admission below its limits, 9 to 16 concurrent foreground requests that may run inference (SearchRpcOps.mayRunInference) all run; none is refused at once. The pool gets a bounded queue equal to its thread count (8 by default, 1 with the minimum policy), within the policy's foregroundQueue bound (validateAgainstPolicy).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "FP2",
          "quote": "time spent queued counts against the request's own deadline (Budget.deadlineNanos and the CallContext deadline given to the worker) and against the search's semantic deadline (Spec D, already in this code); neither restarts when the task leaves the queue.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "FP3",
          "quote": "a search that starts after its semantic deadline has passed in the queue takes the existing Spec D outcome at once without running inference: hybrid or SPLADE searches answer keyword-only with SEMANTIC_BUDGET_EXCEEDED and cause contended; VECTOR-only returns its existing typed 504 with SEMANTIC_BUDGET_EXCEEDED; RAG retrieval answers BM25 with SEMANTIC_BUDGET_EXCEEDED. Use the existing producers and the cause rule (time queued before the seat counts as contended); add no new reroute to another lane. A request whose whole deadline passes while queued gets DEADLINE_EXCEEDED as today: the task never runs and its admission slot and serving view are released at once (OwnedCallTask.cancelBeforeStart). AUTO-mode and rerank-only searches follow the same rules for whichever legs they have.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "FP4",
          "quote": "when the inference threads and the queue are both full, the next inference request is refused with a new code ADMISSION_INFERENCE_BUSY (HTTP 429 with Retry-After; MCP -32000 with the same errorCode), never ADMISSION_ENGINE_LIMIT. If you add an EngineAdmissionException.Reason, name it so that BackgroundRunService (~:199, which builds \"ADMISSION_\" + reason.name()) yields exactly ADMISSION_INFERENCE_BUSY.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "FP5",
          "quote": "the admission controller's own limits keep their codes: aggregate and inference limits give ADMISSION_ENGINE_LIMIT, per-client limits ADMISSION_CONTEXT_LIMIT, including a lowered aggregate-limit override.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "FP7",
          "quote": "shutdown with requests queued gives them the engine-shutdown cancellation, releases their slots, and drain (awaitDrained) does not hang. Client cancellation while queued removes the task without running its body.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "FP8",
          "quote": "with inference threads and queue full, a text-only search and an ingest request are still admitted; the reserved text slot holds; text-only searches never wait behind queued inference.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "FP9",
          "quote": "a queued request does not count as foreground load until it starts.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "FP10",
          "quote": "with all inference threads held by workers whose deadlines have passed, a queued search still ends by its own deadline, never hangs.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-fg-pool-before.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineKnowledgeClientInferenceQueueTest.java#concurrentInferenceRequestsBeyondTheThreadsWaitAndAllRun",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineKnowledgeClientInferenceQueueTest.java#inferenceLaneQueuesAsManyRequestsAsItHasThreadsWithinTheForegroundQueueBound",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineKnowledgeClientInferenceQueueTest.java#fullInferenceThreadsAndQueueRefuseTheNextRequestWithInferenceBusy",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineKnowledgeClientInferenceQueueTest.java#queuedRequestRunsUnderTheDeadlineItWasSubmittedWith",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineKnowledgeClientInferenceQueueTest.java#requestWhoseDeadlinePassesInTheQueueNeverRunsAndReleasesItsOwnersAtOnce",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineKnowledgeClientInferenceQueueTest.java#queuedSearchPastItsSemanticCapAnswersKeywordOnlyWithoutStartingItsEncoder",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineKnowledgeClientInferenceQueueTest.java#queuedVectorOnlySearchPastItsSemanticBudgetIsTheTypedDeadline",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineKnowledgeClientInferenceQueueTest.java#shutdownCancelsQueuedRequestsReleasesTheirSlotsAndDrains",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineKnowledgeClientInferenceQueueTest.java#clientCancellationRemovesAQueuedRequestWithoutRunningIt",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineKnowledgeClientInferenceQueueTest.java#fullInferenceLaneLeavesTextSearchAndIngestAdmittedAndTheTextSlotReserved",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineKnowledgeClientInferenceQueueTest.java#queuedRequestIsNotForegroundLoadUntilItStarts",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineKnowledgeClientInferenceQueueTest.java#queuedSearchBehindExpiredNativeWorkEndsByItsOwnDeadline",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineKnowledgeClientInferenceQueueTest.java#admissionLimitsKeepTheirCodesWhileTheInferenceLaneHasRoom"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineKnowledgeClientInferenceQueueTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "closedExecutorsAreReportedAsClosedNotAsCapacity: with the inference lane pool or the text lane pool shut down, as the client close does before it stops its deadline timer, a search is answered with EngineExecutorRejectedException reason CLOSED (which ApiErrorHandler reports as SERVICE_UNAVAILABLE), never an EngineAdmissionException; no admission slot stays held and the body never runs. Before, withBudget answered every rejection with ENGINE_LIMIT. Fail-before: f648453b5 with b6d536-fg-pool-before-compat.patch, by assertion.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "FP6",
          "quote": "a rejection from a closed pool on the withBudget path (EngineExecutorRejectedException.Reason.CLOSED) is reported as service unavailable, as elsewhere (ApiErrorHandler ~:282), never as a capacity refusal.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-fg-pool-before.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineKnowledgeClientInferenceQueueTest.java#closedExecutorsAreReportedAsClosedNotAsCapacity"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineKnowledgeClientExecutorTest.java"
      ],
      "class": "Obsolete",
      "removedChecks": "inferencePartitionUsesExistingForegroundThreadBudgetAndRefusesWithoutQueueing: it pinned the inference lane queue capacity at 0, the refusal without queueing that FP1 replaces. Its other assertions are kept unchanged in the renamed check of the next entry.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "FP1",
          "quote": "with admission below its limits, 9 to 16 concurrent foreground requests that may run inference (SearchRpcOps.mayRunInference) all run; none is refused at once. The pool gets a bounded queue equal to its thread count (8 by default, 1 with the minimum policy), within the policy's foregroundQueue bound (validateAgainstPolicy).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none (docs/decisions searched for inference pool, foreground inference, SynchronousQueue, ADMISSION_ENGINE_LIMIT and inference lane; no record decides that the inference lane refuses without queueing)"
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineKnowledgeClientExecutorTest.java"
      ],
      "class": "New",
      "behaviour": "Changed intent of the inference partition check (renamed inferencePartitionUsesExistingForegroundThreadBudgetAndQueuesUpToItsThreadCount): with 16 foreground threads and queue 32 the inference lane keeps 8 threads and now has 8 queue places (was 0); thread split, text queue 32, kind and instance count unchanged. expiredNativeCallsLeaveTextSearchAndFetchAvailableWithoutReleasingOwnership keeps every assertion (text search and fetch run while the expired native run holds the one inference thread, the native work keeps its admission, and search, rerank, retrieveContext and matchCitations are refused with EngineAdmissionException releasing only never-issued work); because the one-thread inference lane now has one queue place, one waiting search fills it first and is then seen to end with DEADLINE_EXCEEDED by its own deadline (FP10), so the four refusals still meet a full lane. It passes before and after.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "FP1",
          "quote": "with admission below its limits, 9 to 16 concurrent foreground requests that may run inference (SearchRpcOps.mayRunInference) all run; none is refused at once. The pool gets a bounded queue equal to its thread count (8 by default, 1 with the minimum policy), within the policy's foregroundQueue bound (validateAgainstPolicy).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "FP10",
          "quote": "with all inference threads held by workers whose deadlines have passed, a queued search still ends by its own deadline, never hangs.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-fg-pool-before.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineKnowledgeClientExecutorTest.java#inferencePartitionUsesExistingForegroundThreadBudgetAndQueuesUpToItsThreadCount",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineKnowledgeClientExecutorTest.java#expiredNativeCallsLeaveTextSearchAndFetchAvailableWithoutReleasingOwnership"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineSearchAdmissionUnderStallTest.java"
      ],
      "class": "Obsolete",
      "removedChecks": "theNinthConcurrentHybridSearchIsRefusedByTheInferenceLaneNotByAdmission: it pinned the defect FP1 repairs, the ninth concurrent hybrid search refused at once with ENGINE_LIMIT by the inference lane while admission was below its limits. Replaced by the changed-intent check of the next entry on the same real stack.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "FP1",
          "quote": "with admission below its limits, 9 to 16 concurrent foreground requests that may run inference (SearchRpcOps.mayRunInference) all run; none is refused at once. The pool gets a bounded queue equal to its thread count (8 by default, 1 with the minimum policy), within the policy's foregroundQueue bound (validateAgainstPolicy).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none (docs/decisions searched for inference pool, foreground inference, SynchronousQueue, ADMISSION_ENGINE_LIMIT and inference lane; no record decides that the inference lane refuses without queueing)"
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineSearchAdmissionUnderStallTest.java"
      ],
      "class": "New",
      "behaviour": "searchesBeyondTheInferenceThreadsWaitAndOnlyAFullQueueRefusesAsInferenceBusy: through the real Engine client, worker search service and query encoder over GPU runs that stall until the test ends, eight hybrid searches hold the eight inference threads; the ninth to sixteenth wait (none ends at once, eight queued), the seventeenth is refused with reason INFERENCE_BUSY with at most 16 of 64 admission slots in use, every waiting search ends with DEADLINE_EXCEEDED by its own 4 s deadline, and none of the first eight is refused.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "FP1",
          "quote": "with admission below its limits, 9 to 16 concurrent foreground requests that may run inference (SearchRpcOps.mayRunInference) all run; none is refused at once. The pool gets a bounded queue equal to its thread count (8 by default, 1 with the minimum policy), within the policy's foregroundQueue bound (validateAgainstPolicy).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "FP4",
          "quote": "when the inference threads and the queue are both full, the next inference request is refused with a new code ADMISSION_INFERENCE_BUSY (HTTP 429 with Retry-After; MCP -32000 with the same errorCode), never ADMISSION_ENGINE_LIMIT. If you add an EngineAdmissionException.Reason, name it so that BackgroundRunService (~:199, which builds \"ADMISSION_\" + reason.name()) yields exactly ADMISSION_INFERENCE_BUSY.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "FP10",
          "quote": "with all inference threads held by workers whose deadlines have passed, a queued search still ends by its own deadline, never hangs.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-fg-pool-before.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineSearchAdmissionUnderStallTest.java#searchesBeyondTheInferenceThreadsWaitAndOnlyAFullQueueRefusesAsInferenceBusy"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedStartTest.java"
      ],
      "class": "New",
      "behaviour": "Real WorkerSearchService over a real Lucene index with a GPU query embedder (semantic cap 2 000 ms) that counts its query embeds; the call is built, then waits as if queued, then reaches the service. A hybrid search and an AUTO dense search that waited 2 300 ms of a 10 s budget answer keyword-only with hybrid fallback SEMANTIC_BUDGET_EXCEEDED, cause contended, and no query embed; a SPLADE search that waited as long skips SPLADE with SEMANTIC_BUDGET_EXCEEDED, cause contended, without encoding; a VECTOR-only search with D 1 500 ms that waited 1 100 ms (past D - 500 ms) throws the typed DEADLINE_EXCEEDED naming SEMANTIC_BUDGET_EXCEEDED and contended without an embed; a RAG retrieval that waited past its cap answers BM25 with retrieval mode reason SEMANTIC_BUDGET_EXCEEDED, cause contended, without an embed. A hybrid search that did not wait runs its dense leg (one query embed); that preservation check passes before and after.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "FP2",
          "quote": "time spent queued counts against the request's own deadline (Budget.deadlineNanos and the CallContext deadline given to the worker) and against the search's semantic deadline (Spec D, already in this code); neither restarts when the task leaves the queue.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "FP3",
          "quote": "a search that starts after its semantic deadline has passed in the queue takes the existing Spec D outcome at once without running inference: hybrid or SPLADE searches answer keyword-only with SEMANTIC_BUDGET_EXCEEDED and cause contended; VECTOR-only returns its existing typed 504 with SEMANTIC_BUDGET_EXCEEDED; RAG retrieval answers BM25 with SEMANTIC_BUDGET_EXCEEDED. Use the existing producers and the cause rule (time queued before the seat counts as contended); add no new reroute to another lane. A request whose whole deadline passes while queued gets DEADLINE_EXCEEDED as today: the task never runs and its admission slot and serving view are released at once (OwnedCallTask.cancelBeforeStart). AUTO-mode and rerank-only searches follow the same rules for whichever legs they have.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-fg-pool-before.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedStartTest.java#hybridSearchThatWaitedPastItsSemanticCapAnswersKeywordOnlyWithoutEncoding",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedStartTest.java#autoDenseSearchThatWaitedPastItsSemanticCapAnswersKeywordOnlyWithoutEncoding",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedStartTest.java#spladeSearchThatWaitedPastItsSemanticCapSkipsSpladeWithoutEncoding",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedStartTest.java#vectorOnlySearchThatWaitedPastItsSemanticBudgetIsTheTypedDeadline",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedStartTest.java#ragRetrievalThatWaitedPastItsSemanticCapAnswersWithBm25WithoutEncoding",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedStartTest.java#hybridSearchThatDidNotWaitRunsItsDenseLeg"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/IndexingControllerSaturationTest.java"
      ],
      "class": "New",
      "behaviour": "admittedRequestPreservesActualSubmissionRefusal: the saturation step now fills whatever queue the selected pool has (the text lane one place as before; the inference lane now one place under the one-thread test policy), and the open-retrieval variant, which runs on the inference lane, expects 429 with Retry-After, errorCode ADMISSION_INFERENCE_BUSY and retrySafe false; every text-lane variant keeps ADMISSION_ENGINE_LIMIT.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "FP1",
          "quote": "with admission below its limits, 9 to 16 concurrent foreground requests that may run inference (SearchRpcOps.mayRunInference) all run; none is refused at once. The pool gets a bounded queue equal to its thread count (8 by default, 1 with the minimum policy), within the policy's foregroundQueue bound (validateAgainstPolicy).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "FP4",
          "quote": "when the inference threads and the queue are both full, the next inference request is refused with a new code ADMISSION_INFERENCE_BUSY (HTTP 429 with Retry-After; MCP -32000 with the same errorCode), never ADMISSION_ENGINE_LIMIT. If you add an EngineAdmissionException.Reason, name it so that BackgroundRunService (~:199, which builds \"ADMISSION_\" + reason.name()) yields exactly ADMISSION_INFERENCE_BUSY.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-fg-pool-before.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/IndexingControllerSaturationTest.java#admittedRequestPreservesActualSubmissionRefusal"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/InferenceBusyRefusalTransportTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpInferenceBusyRefusalTest.java"
      ],
      "class": "New",
      "behaviour": "For an EngineAdmissionException with reason INFERENCE_BUSY (looked up by name): RequestEngineWork.errorCode is ADMISSION_INFERENCE_BUSY and its status is 429 with Retry-After; ApiErrorHandler.resolve of the wrapped refusal is ADMISSION_INFERENCE_BUSY, httpStatusFor is 429, the response has retrySafe false, and writeExecutorRefusal writes 429, Retry-After and that errorCode with retrySafe false. An MCP tools/call justsearch_search refused that way answers -32000 with data.errorCode ADMISSION_INFERENCE_BUSY, retrySafe false, status 429 and Retry-After.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "FP4",
          "quote": "when the inference threads and the queue are both full, the next inference request is refused with a new code ADMISSION_INFERENCE_BUSY (HTTP 429 with Retry-After; MCP -32000 with the same errorCode), never ADMISSION_ENGINE_LIMIT. If you add an EngineAdmissionException.Reason, name it so that BackgroundRunService (~:199, which builds \"ADMISSION_\" + reason.name()) yields exactly ADMISSION_INFERENCE_BUSY.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "FP11",
          "quote": "every place that maps the code handles ADMISSION_INFERENCE_BUSY: RequestEngineWork status and errorCode, ApiErrorHandler resolve and httpStatusFor (429 group), ConversationEngine refusal events (chat SSE), BackgroundRunService, McpProtocolHandler, errors.en.properties and SSOT/messages/errors.en.json, the web client's capacity sets (admissionFetch.ts CAPACITY_CODES, http.ts ~:434; a refusal raised mid-request keeps retrySafe false and is not replayed), scripts/jseval/lane-f/admission-loop.mjs and e-run.mjs, and docs/reference/api-contract-map.md.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-fg-pool-before.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/InferenceBusyRefusalTransportTest.java#inferenceBusyIsItsOwnCapacityCodeWithRetryAfterAndNoSafeReplay",
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpInferenceBusyRefusalTest.java#inferenceBusyRefusalKeepsItsOwnCodeOverMcp"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/ConversationInferenceBusyRefusalTest.java",
        "modules/app-agent/src/test/java/io/justsearch/agent/BackgroundRunServiceTest.java",
        "modules/app-api/src/test/java/io/justsearch/app/api/InferenceBusyErrorCodeTest.java"
      ],
      "class": "New",
      "behaviour": "With reason INFERENCE_BUSY looked up by name: the chat refusal event for a wrapped refusal is an error event with errorCode ADMISSION_INFERENCE_BUSY, i18nKey errors.ADMISSION_INFERENCE_BUSY, its retryAfterSeconds and retrySafe false; a background agent run refused that way records the durable rejection reason ADMISSION_INFERENCE_BUSY and never enters the agent body; \"ADMISSION_\" + the reason name is a registered TRANSIENT ApiErrorCode with a non-blank errors.en.properties message.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "FP4",
          "quote": "when the inference threads and the queue are both full, the next inference request is refused with a new code ADMISSION_INFERENCE_BUSY (HTTP 429 with Retry-After; MCP -32000 with the same errorCode), never ADMISSION_ENGINE_LIMIT. If you add an EngineAdmissionException.Reason, name it so that BackgroundRunService (~:199, which builds \"ADMISSION_\" + reason.name()) yields exactly ADMISSION_INFERENCE_BUSY.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "FP11",
          "quote": "every place that maps the code handles ADMISSION_INFERENCE_BUSY: RequestEngineWork status and errorCode, ApiErrorHandler resolve and httpStatusFor (429 group), ConversationEngine refusal events (chat SSE), BackgroundRunService, McpProtocolHandler, errors.en.properties and SSOT/messages/errors.en.json, the web client's capacity sets (admissionFetch.ts CAPACITY_CODES, http.ts ~:434; a refusal raised mid-request keeps retrySafe false and is not replayed), scripts/jseval/lane-f/admission-loop.mjs and e-run.mjs, and docs/reference/api-contract-map.md.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-fg-pool-before.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/ConversationInferenceBusyRefusalTest.java#inferenceBusyRefusalStreamsItsOwnCode",
        "modules/app-agent/src/test/java/io/justsearch/agent/BackgroundRunServiceTest.java#inferenceBusyRefusalIsRecordedWithItsOwnCode",
        "modules/app-api/src/test/java/io/justsearch/app/api/InferenceBusyErrorCodeTest.java#inferenceBusyIsATransientRegisteredCodeWithAMessage"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ApiErrorHandlerResolveTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/EngineAdmissionTransportTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpProtocolHandlerTest.java"
      ],
      "class": "New",
      "behaviour": "The three checks that walk every EngineAdmissionException reason now cover INFERENCE_BUSY: its front-door code is ADMISSION_INFERENCE_BUSY (HTTP and MCP), it is in the capacity group with 429 and Retry-After, and retrySafe stays false. Every other reason keeps its expected code, status and header. Before-state not reproducible: the checks do not compile on f648453b5 because Reason.INFERENCE_BUSY and ApiErrorCode.ADMISSION_INFERENCE_BUSY arrive with f8c4967ed, and a compat mapping for the reason would be the behaviour under test.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "FP4",
          "quote": "when the inference threads and the queue are both full, the next inference request is refused with a new code ADMISSION_INFERENCE_BUSY (HTTP 429 with Retry-After; MCP -32000 with the same errorCode), never ADMISSION_ENGINE_LIMIT. If you add an EngineAdmissionException.Reason, name it so that BackgroundRunService (~:199, which builds \"ADMISSION_\" + reason.name()) yields exactly ADMISSION_INFERENCE_BUSY.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "FP11",
          "quote": "every place that maps the code handles ADMISSION_INFERENCE_BUSY: RequestEngineWork status and errorCode, ApiErrorHandler resolve and httpStatusFor (429 group), ConversationEngine refusal events (chat SSE), BackgroundRunService, McpProtocolHandler, errors.en.properties and SSOT/messages/errors.en.json, the web client's capacity sets (admissionFetch.ts CAPACITY_CODES, http.ts ~:434; a refusal raised mid-request keeps retrySafe false and is not replayed), scripts/jseval/lane-f/admission-loop.mjs and e-run.mjs, and docs/reference/api-contract-map.md.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-fg-pool-candidate.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ApiErrorHandlerResolveTest.java#wrappedAdmissionRefusalsUseFrontDoorCodesAndNeverPromiseSafeReplay",
        "modules/ui/src/test/java/io/justsearch/ui/api/EngineAdmissionTransportTest.java#mcpHandlerThrownAdmissionRefusalsExplicitlyForbidRetry",
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpProtocolHandlerTest.java#downstreamAdmissionRefusalKeepsRpcIdentityAndDisallowsAutomaticReplay"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/api/admissionFetch.test.ts"
      ],
      "class": "New",
      "behaviour": "A 429 ADMISSION_INFERENCE_BUSY refusal that promises a safe replay waits the named 2 s delay and is sent once more; one raised mid-request (retrySafe false) is returned to the caller and never replayed. Before run on f648453b5 (no patch): the retry case fails by assertion (send called once, not twice); the never-replays case passes there.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "FP11",
          "quote": "every place that maps the code handles ADMISSION_INFERENCE_BUSY: RequestEngineWork status and errorCode, ApiErrorHandler resolve and httpStatusFor (429 group), ConversationEngine refusal events (chat SSE), BackgroundRunService, McpProtocolHandler, errors.en.properties and SSOT/messages/errors.en.json, the web client's capacity sets (admissionFetch.ts CAPACITY_CODES, http.ts ~:434; a refusal raised mid-request keeps retrySafe false and is not replayed), scripts/jseval/lane-f/admission-loop.mjs and e-run.mjs, and docs/reference/api-contract-map.md.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-fg-pool-candidate.json",
      "newChecks": [
        "modules/ui-web/src/api/admissionFetch.test.ts#ADMISSION_INFERENCE_BUSY refused before execution waits the named delay and retries",
        "modules/ui-web/src/api/admissionFetch.test.ts#never replays ADMISSION_INFERENCE_BUSY raised mid-request"
      ]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS2-20261015",
  "verdict": "accept",
  "digest": "sha256:a774b476a2dc8b5571dfd59512a9682761dc0d815c694573454d3727f7c65128",
  "note": "Reviewed admission/deadline/typed refusal obligations and allowed compatibility additions. Own candidate checks passed; exact-source independent reruns match the declared before assertions/preservation and corrected entries 11-12, while compile/exception outcomes remain unverified behavior proof."
}
```
