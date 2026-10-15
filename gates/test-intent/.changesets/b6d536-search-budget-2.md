---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: 886833dd-a08f-492f-a40a-cc0976b5ce5e
---

# Test-intent entries

Task t-20261005-b6d536, item 5a second slice: Spec D rev 2 steps 5 (RAG) and 6 (VECTOR-only),
plus the test the orchestrator asked for on the accepted run-thread deviation. A RAG retrieval
whose query embedding misses its semantic sub-budget falls back to BM25 context and says so, with
the cause, in rag.meta and the MCP evidence. A VECTOR-only search, which has no keyword fallback,
waits within D minus the margin, and a miss is a typed DEADLINE_EXCEEDED that names the reason.

ADR-0050 records this decision, but it is added on this branch (not at the gate base), so it is not
cited as a source. The entries cite the owner-adopted scenarios, and the owner's delegation for the
agent's specific choices.

Evidence files (all written by run-evidence.mjs):

- `b6d536-search-budget-2-rag-repaired.json`: repaired RAG behaviour, with an assertion fail-before
  on 3d181a70c (the steps 1-4 commit this slice builds on). Records phase B moved it to 900bdf4a0;
  see below.
- `b6d536-search-budget-2-vector-repaired.json`: repaired VECTOR-only behaviour, with an assertion
  fail-before on 68547f75a. The VECTOR-only production change landed in 3d181a70c (steps 1-4), so
  on 3d181a70c this check already passes; its fail-before is shown on the commit before that change,
  with the steps 1-4 fixture file `WorkerSearchServiceSemanticBudgetTest.java` overlaid (that file
  uses only API present on 68547f75a).
- `b6d536-search-budget-2-new.json`: checks of new API, no before-state.
- `b6d536-search-budget-2-preservation.json`: preservation checks that pass before (3d181a70c) and
  after. That run exits 1 by design, because none of them shows a repair.
- `b6d536-search-budget-2-answer-repaired.json`: the repair for the verifier's R1/MCP finding (an
  open answer asked while the models load), with an assertion fail-before on fae202403 (the stack
  head the repair builds on).
- `b6d536-search-budget-2-answer-new.json`: checks of the new request field
  `semantic_loading_observed`, no before-state.
- `b6d536-search-budget-2-answer-preservation.json`: a preservation check that passes before
  (fae202403) and after. That run exits 1 by design.

Records phase B (batch 3) re-ran six files on b599d4d7e because their check files changed after the
first runs:

- `b6d536-search-budget-2-new.json` and `b6d536-search-budget-2-answer-new.json` pass (no
  before-state).
- `b6d536-search-budget-2-answer-repaired.json`: McpAnswerModelsLoadingInProcessTest now sets
  NoOpEmbeddingProvider.starting() (0c5159476), which fae202403 lacks. The fail-before applies the
  compat before-patch `gates/test-intent/evidence/b6d536-search-budget-2-answer-compat.patch`. It
  adds only the declaration starting(), which returns the existing NoOpEmbeddingProvider.INSTANCE at
  once: the provider such a service already has when none is set, as the test had before 0c5159476.
  No fae202403 code reads it. All three checks again fail by assertion there.
- `b6d536-search-budget-2-contract-repaired.json`: its check calls the same starting(), so its
  fail-before on 3d181a70c applies the same patch, and it again fails by the same assertion.
- `b6d536-search-budget-2-rag-repaired.json`: every check passes on the change. Its check files now
  use the embedding availability API (EmbeddingProvider.Availability, availability() in place of
  isAvailable(), from a37c6ef33), NoOpEmbeddingProvider.starting() and
  RetrieveContextResponse.getRetrievalModeCause(). So the fail-before moved from 3d181a70c to
  900bdf4a0, the parent of the lane commit 7df6a0a7d that introduced the behaviour, and applies the
  compat before-patch `gates/test-intent/evidence/b6d536-search-budget-2-rag-compat.patch`. It makes
  isAvailable() a default returning true. Every implementation at 900bdf4a0 overrides it, and the
  checks' fixture reports READY. It also adds availability() as a default returning READY, the
  Availability record, starting() returning the existing INSTANCE (which this lane's checks used),
  and the proto field retrieval_mode_cause. No 900bdf4a0 code reads any of these. All four checks
  fail there by assertion on the budget behaviour: two RAG retrievals throw "inference deadline
  exceeded", one with models loading times out, and the in-process RAG turn emits no rag.meta.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceRagBudgetTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RagRetrievalBudgetInProcessTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "A chat turn's RAG retrieval whose query embedding cannot answer inside its semantic sub-budget still returns BM25 context, inside the request deadline, with retrieval_mode BM25 and retrieval_mode_reason SEMANTIC_BUDGET_EXCEEDED, instead of failing. R1 (real WorkerSearchService.retrieveContext over a real Lucene index, rag.retrieve.mode auto): a held embedding device gives BM25 context with the reason, not 'inference deadline exceeded'; models still loading give BM25 context within the deadline, not a 120 s wait; MMR diversification whose document embeddings are held answers within the deadline by position diversification instead of failing. R2 (the production RAGContext injector over the production RemoteDocumentService over the in-process engine and worker, 5000 ms base deadline, models latch held closed): the turn has no terminal error and its rag.meta event carries retrieval_mode BM25, retrieval_mode_reason SEMANTIC_BUDGET_EXCEEDED and the BM25 chunks used. Before the change R2's injector emitted no rag.meta at all.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S1",
          "quote": "During the scripted agent on a quiet 12 GB machine, no search returns a 504 or times out; every search answers within its deadline.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S2",
          "quote": "When semantic search misses its sub-budget, the search returns keyword results with a fallback reason (per leg) that the UI, searchTrace, RAG metadata and MCP responses carry.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for item 5a (search budget)",
          "choice": "A RAG retrieval that embeds the question (rag.retrieve.mode hybrid or auto) gets the same semantic sub-budget as a search, min(start + device cap, D - lexical reserve - margin), and waits for models at most until it. On a miss the retrieval answers with BM25 context and retrieval_mode_reason SEMANTIC_BUDGET_EXCEEDED. MMR whose document embeddings miss the remaining budget falls back to position diversification, recorded on the span and log only. A BM25-only retrieval keeps today's models wait and has no sub-budget. RETRIEVAL_TIMEOUT now means only that D itself passed.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-search-budget-2-rag-repaired.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceRagBudgetTest.java#contendedQueryEmbeddingGivesBm25ContextWithTheReason",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceRagBudgetTest.java#modelsLoadingGivesBm25ContextWithinTheDeadline",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceRagBudgetTest.java#mmrWithHeldDocumentEmbeddingsAnswersWithinTheDeadline",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RagRetrievalBudgetInProcessTest.java#ragTurnWithModelsLoadingGetsBm25ContextAndTheReason"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceVectorOnlyBudgetTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "V1a (real WorkerSearchService over a real Lucene index; the query embedder acquires a real NativeSessionHandle held by a BACKGROUND lease): a VECTOR-only search whose embedding device stays held past D - margin ends, inside D, in a typed WorkerServiceException DEADLINE_EXCEEDED whose detail names SEMANTIC_BUDGET_EXCEEDED and the cause 'contended'. It never answers with keyword results or a silent empty result. Before the change the detail was only 'inference deadline exceeded'.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S2",
          "quote": "When semantic search misses its sub-budget, the search returns keyword results with a fallback reason (per leg) that the UI, searchTrace, RAG metadata and MCP responses carry.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for item 5a (search budget)",
          "choice": "VECTOR-only has no keyword fallback, so its semantic budget is D - margin (margin 500 ms) and a miss is a typed DEADLINE_EXCEEDED (HTTP 504, MCP DEADLINE_EXCEEDED) with the detail 'SEMANTIC_BUDGET_EXCEEDED (cause: <cause>)'.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-search-budget-2-vector-repaired.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceVectorOnlyBudgetTest.java#vectorOnlyBlockedPastItsBudgetIsADeadlineWithTheReason"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/RagRetrievalBeforeGenerationTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceVectorOnlyBudgetTest.java"
      ],
      "class": "New",
      "behaviour": "Preservation, passing on 3d181a70c and on the change. R3 (the production ConversationEngine running a one-shot shape with the production RAGContext injector; only the document service and the generation client are recorded): the turn's retrieval, which carries its query encodes, starts once and completes before the turn's first generation call, even when it answers late on another thread. V1b (real WorkerSearchService; the embedding device is held by a BACKGROUND lease for 3 s, inside D - margin with D = 8 s): a VECTOR-only search waits, runs its dense leg once, and returns the vector results with effective mode VECTOR and no keyword fallback.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S1",
          "quote": "During the scripted agent on a quiet 12 GB machine, no search returns a 504 or times out; every search answers within its deadline.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "P1",
          "quote": "With chat idle on 12 GB, hybrid search stays as fast (p95 about 0.26 s) and indexing as fast as today.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for item 5a (search budget)",
          "choice": "RAG query encodes stay inside RAGContext.inject, before generation, so a turn's query encode never overlaps its own generation. VECTOR-only requests wait for their encoder within D - margin rather than failing early.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-search-budget-2-preservation.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/RagRetrievalBeforeGenerationTest.java#everyRetrievalCompletesBeforeTheFirstGenerationCall",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceVectorOnlyBudgetTest.java#vectorOnlyBlockedInsideItsBudgetWaitsAndReturnsVectors"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RagRetrievalCauseTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/RagMetaCauseTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpRagCauseTest.java"
      ],
      "class": "New",
      "behaviour": "The cause of a RAG budget miss, carried next to the reason. RagRetrievalCauseTest (real WorkerSearchService.retrieveContext): the wire field retrieval_mode_cause is 'contended' when the embedding device is held, 'loading' while models load, and empty for any other reason (NO_EMBEDDING_SERVICE). RagMetaCauseTest (production RAGContext injector): rag.meta carries retrieval_mode_cause next to SEMANTIC_BUDGET_EXCEEDED with no terminal error, carries no cause key for another reason, and drops a cause that arrives with another reason. McpRagCauseTest (production McpEvidenceProjection): the MCP answer evidence quality block carries retrievalModeCause next to retrievalModeReason, and no key when there is no cause.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S2",
          "quote": "When semantic search misses its sub-budget, the search returns keyword results with a fallback reason (per leg) that the UI, searchTrace, RAG metadata and MCP responses carry.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for item 5a (search budget)",
          "choice": "The RAG retrieval carries its cause in a new RetrieveContextResponse field retrieval_mode_cause (contended, loading or stuck; empty otherwise), projected as rag.meta retrieval_mode_cause and MCP retrievalModeCause only alongside SEMANTIC_BUDGET_EXCEEDED.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-search-budget-2-new.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RagRetrievalCauseTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/RagMetaCauseTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpRagCauseTest.java"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceReasonCodeContractTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "The RAG retrieval-mode reason allowlist gains the row SEMANTIC_BUDGET_EXCEEDED, because the RAG path now emits it; every existing row and check is unchanged. The new check (real WorkerSearchService.retrieveContext over a real Lucene index, rag.retrieve.mode auto, models latch held closed, 3 s request deadline) gets an answer within 5 s whose retrieval_mode_reason is SEMANTIC_BUDGET_EXCEEDED and is in the RAG allowlist. Before the change the retrieval waited on the models latch past 5 s.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S2",
          "quote": "When semantic search misses its sub-budget, the search returns keyword results with a fallback reason (per leg) that the UI, searchTrace, RAG metadata and MCP responses carry.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for item 5a (search budget)",
          "choice": "One reason code, SEMANTIC_BUDGET_EXCEEDED, is also the RAG retrieval_mode_reason for a semantic budget miss, so the RAG contract allowlist lists it.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-search-budget-2-contract-repaired.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceReasonCodeContractTest.java#retrieveContextSemanticBudgetMissEmitsAnAllowlistedReason"
      ]
    },
    {
      "items": [
        "modules/ort-common/src/test/java/io/justsearch/ort/DetachedRunThreadLifetimeTest.java"
      ],
      "class": "New",
      "behaviour": "The run thread of a detached native run (a real NativeSessionHandle whose native session never returns, run through the production Lease.run) is a platform daemon thread named ort-query-run-<n>. A child JVM whose main thread leaves such a run detached and still running, closes nothing and returns, exits on its own with status 0 inside 20 s: a stuck native run cannot keep the process alive after the Engine shuts down.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S1",
          "quote": "During the scripted agent on a quiet 12 GB machine, no search returns a 504 or times out; every search answers within its deadline.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for item 5a (search budget)",
          "choice": "Detached native runs use one bare platform thread each, from a daemon thread factory named ort-query-run-, rather than an engine-registered executor, because ort-common does not reach the engine's executor registry. A run that never returns keeps its thread, seat and GPU memory until the process exits (named limitation).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-search-budget-2-new.json",
      "newChecks": [
        "modules/ort-common/src/test/java/io/justsearch/ort/DetachedRunThreadLifetimeTest.java"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpAnswerModelsLoadingInProcessTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RemoteDocumentServiceOpenRetrievalDeadlineTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "An open answer (no document ids) asked while the search models are loading returns inside its limit with keyword context, the reason and the cause. In-process checks (the production justsearch_answer tool, McpToolSurface.callTool, and the production chat RAGContext injector, each over the production RemoteDocumentService over the in-process engine and worker that EngineRoot composes, production request deadline 15 000 ms, models latch held closed): the MCP answer is not an error, answers in under 15 s, and its structured quality carries retrievalMode BM25, retrievalModeReason SEMANTIC_BUDGET_EXCEEDED, retrievalModeCause loading and at least one chunk used. The chat turn without documents has no terminal error, answers in under 15 s, and its rag.meta carries the same reason, cause, mode and chunks. Both answer in under 11 s (one 8 s models wait plus 3 s), so the retrieval step did not wait for the models again. Before the change the MCP answer failed with TIMEOUT after 15.0 s and the chat retrieval took 16.1 s. Head check (production RemoteDocumentService over the production KnowledgeClient request builders): the open retrieval's pre-search and retrieval both run inside one head deadline, equal for both and equal to the client's request deadline from the start of the answer; before the change neither ran inside one.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S1",
          "quote": "During the scripted agent on a quiet 12 GB machine, no search returns a 504 or times out; every search answers within its deadline.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S2",
          "quote": "When semantic search misses its sub-budget, the search returns keyword results with a fallback reason (per leg) that the UI, searchTrace, RAG metadata and MCP responses carry.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for item 5a (search budget)",
          "choice": "The open retrieval (RemoteDocumentService.retrieveContext with no document ids, reached by the MCP answer, /api/retrieve-context and a chat turn without documents) fixes one head deadline D when it starts: the client's request deadline from now, or the caller's own D when earlier. Its pre-search and its retrieval both run inside D (HeadSearchDeadline), so each call's budget and each step's semantic sub-budget are carved from what is left of D. A scoped retrieval (document ids given) is one step and keeps its own category budget.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-search-budget-2-answer-repaired.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpAnswerModelsLoadingInProcessTest.java#anMcpAnswerWhileModelsLoadReturnsKeywordEvidenceWithTheReasonInsideItsLimit",
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpAnswerModelsLoadingInProcessTest.java#aChatTurnWithoutDocumentsWhileModelsLoadAnswersInsideTheRequestDeadline",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RemoteDocumentServiceOpenRetrievalDeadlineTest.java#bothStepsOfAnOpenRetrievalRunInsideOneHeadDeadline"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceRagLoadingObservedTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RemoteDocumentServicePreSearchLoadingTest.java"
      ],
      "class": "New",
      "behaviour": "A step that already observed the cause loading lets the next step skip its models wait. Worker (real WorkerSearchService.retrieveContext over a real Lucene index, rag.retrieve.mode auto, 3 s request deadline): a retrieval request with semantic_loading_observed while the models latch is closed answers in under 1 s (without the field it waits until D - 750 ms) with BM25 context, SEMANTIC_BUDGET_EXCEEDED and cause loading; with the latch open, the same request still embeds the question once and reports no budget miss and no cause. Head (production RemoteDocumentService over the production KnowledgeClient request builders): a pre-search whose trace reports hybrid_fallback_cause loading sends the retrieval with semantic_loading_observed set; a pre-search that missed with cause contended, or did not miss, leaves it unset.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S1",
          "quote": "During the scripted agent on a quiet 12 GB machine, no search returns a 504 or times out; every search answers within its deadline.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for item 5a (search budget)",
          "choice": "A new RetrieveContextRequest field, bool semantic_loading_observed = 26, set by the head only when the open retrieval's pre-search reports a semantic leg (dense or SPLADE) that missed with cause loading. The worker then checks the models latch without waiting: still closed, the retrieval answers with BM25, SEMANTIC_BUDGET_EXCEEDED and cause loading at once; open, the field changes nothing. A contended miss does not set it, because the device may be free for the retrieval.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-search-budget-2-answer-new.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceRagLoadingObservedTest.java#loadingObservedByThePreSearchSkipsTheModelsWait",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceRagLoadingObservedTest.java#loadingObservedAfterTheModelsBecameReadyStillEmbedsTheQuestion",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RemoteDocumentServicePreSearchLoadingTest.java#aPreSearchThatMissedOnLoadingModelsTellsTheRetrievalNotToWaitAgain",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RemoteDocumentServicePreSearchLoadingTest.java#aPreSearchThatMissedForAnotherCauseLetsTheRetrievalWait",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RemoteDocumentServicePreSearchLoadingTest.java#aPreSearchWithoutAMissLetsTheRetrievalWait"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RemoteDocumentServiceOpenRetrievalDeadlineTest.java"
      ],
      "class": "New",
      "behaviour": "Preservation, passing on fae202403 and on the change: a scoped retrieval (document ids given) runs no pre-search and its retrieval call runs outside any head deadline, so it keeps its own category budget.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "P1",
          "quote": "With chat idle on 12 GB, hybrid search stays as fast (p95 about 0.26 s) and indexing as fast as today.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for item 5a (search budget)",
          "choice": "Only the two-step open retrieval shares one head deadline; a single-step scoped retrieval keeps today's RERANK category budget.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-search-budget-2-answer-preservation.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RemoteDocumentServiceOpenRetrievalDeadlineTest.java#aScopedRetrievalIsOneStepAndKeepsItsOwnBudget"
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
  "session": "68d3797d-dcdf-4a36-aa86-f4641178057c",
  "verdict": "accept",
  "digest": "sha256:1009c824eb709173e9fd4d42580d2eaf584113c6bbab7c282d6d9d0afebbb4a9",
  "note": "verify-d57: fail-before re-run on 1392fdfbc (assertion, reason under test), mutation run on 370d82b33 (cause plumbing, readiness one-way/bound, cold->DEGRADED, daemon), behaviour claims match test code"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS2-20261015",
  "verdict": "reject",
  "digest": "sha256:01d629fc14661c8fda4e2c66bc30054b13989fc948da62e8db98250c08ce5efe",
  "note": "Entry 6's DetachedRunThreadLifetimeTest has candidate-only evidence without an applicable reason: the shared new-API label does not describe it. The acquisition/handle/lease APIs and daemon ort-query-run thread construction already exist at 3d181a70c, so their absence cannot explain omitting this before run."
}
```
