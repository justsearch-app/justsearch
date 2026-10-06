---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: 886833dd-a08f-492f-a40a-cc0976b5ce5e
---

# Test-intent entries

Task t-20261005-b6d536, item 5a first half: the search budget (Spec D rev 2, steps 1-4). This covers
bulk BACKGROUND urgency, the semantic sub-budget with its reason code and per-leg cause, run release
at the deadline, and the head deadline. When query-side semantic work cannot finish inside its
sub-budget, the search answers with keyword results marked SEMANTIC_BUDGET_EXCEEDED instead of
timing out.

ADR-0050 records this decision, but it is added in this branch (not at the gate base), so it is not
cited as a source. The entries cite the owner-adopted scenarios, and the owner's delegation for the
agent's specific choices.

Evidence files (all written by run-evidence.mjs):

- `b6d536-search-budget-worker-repaired.json`, `b6d536-search-budget-head-repaired.json` and
  `b6d536-search-budget-rerank-repaired.json`: repaired behaviour, with an assertion fail-before on
  68547f75a.
- `b6d536-search-budget-admission-repaired.json`: repair round 2, with an assertion fail-before on
  4cfcc6012 (the candidate before that repair).
- `b6d536-search-budget-new.json`: checks of new API, no before-state.
- `b6d536-search-budget-preservation.json`: preservation checks that pass before and after. That run
  exits 1 by design, because none of them shows a repair.

Three pre-existing files only follow a signature change (Adaptation entries). Two others gain a
vocabulary row that S2 requires (see their entries). Repair round 1 adds the rerank stage to the head
deadline with a new cross-encoder drop reason, SEARCH_DEADLINE_EXCEEDED; the pre-existing
CrossEncoderSkipReasonTest and searchTraceExplain.test.ts gain its row. Repair round 2 (verify d14b,
finding F1, scenario P2) stops a rerank the head cut from holding the search's engine admission: the
worker's rerank call answers by its deadline, the native run it leaves behind continues on the
reranker's own run threads, and a reranker whose run threads all hold such runs skips at once.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSemanticBudgetTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceReasonCodeContractTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Through the real WorkerSearchService over a real Lucene index, a hybrid or dense search whose query encoders cannot answer inside the semantic sub-budget returns keyword results instead of failing or running past its deadline. B1a: an explicit hybrid search while models are still loading answers with keyword results inside the test timeout. B1b: an auto search with dense while loading reports hybrid_fallback_reason SEMANTIC_BUDGET_EXCEEDED. B2: a held embedding device gives keyword results, not 'inference deadline exceeded'. B3: a held SPLADE device skips only the SPLADE leg, with the SPLADE skip reason SEMANTIC_BUDGET_EXCEEDED, and the dense leg still runs. B4: a seated dense run that does not return releases the search within the semantic deadline plus margin, with keyword results. C1 (WorkerSearchServiceReasonCodeContractTest): the reason-code allowlists equal the SearchReasonCode wire vocabulary in both directions. The allowlist gains the row SEMANTIC_BUDGET_EXCEEDED, which S2 requires on the wire.",
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
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "completing the agreed design for item 5a (search budget)",
          "choice": "One reason code, SEMANTIC_BUDGET_EXCEEDED, set per leg (hybrid_fallback_reason for dense, splade_skip_reason for SPLADE). The semantic deadline is min(start + cap, D - lexical reserve - margin), with a GPU cap of 2000 ms, a CPU cap of 8000 ms, a lexical reserve of 250 ms and a margin of 500 ms, each a named configuration value. A run past the deadline releases its caller, and the search answers within the semantic deadline plus margin.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-search-budget-worker-repaired.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSemanticBudgetTest.java#explicitHybridWithModelsLoadingAnswersWithKeywordResults",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSemanticBudgetTest.java#autoDenseWithModelsLoadingReportsTheBudgetReason",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSemanticBudgetTest.java#contendedEmbeddingHandleGivesKeywordResults",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSemanticBudgetTest.java#contendedSpladeHandleSkipsOnlyThatLeg",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSemanticBudgetTest.java#stalledDenseRunGivesKeywordResultsInTime",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceReasonCodeContractTest.java#allowlistsMatchTheReasonCodeEnum"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BulkEncodeUrgencyTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Bulk encode work (the parent and chunk embedding backfill per-doc fallbacks, inline document embedding, the SPLADE and BGE-M3 backfill fallbacks, and the NER backfill) acquires its encoder session with BACKGROUND urgency, so it never competes with query encodes as FOREGROUND. Before the change each of these acquired FOREGROUND.",
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
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "completing the agreed design for item 5a (search budget)",
          "choice": "Every bulk (indexing and backfill) encoder acquisition, including the per-document fallbacks, carries BACKGROUND urgency. Only query-side encodes are FOREGROUND.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-search-budget-worker-repaired.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BulkEncodeUrgencyTest.java#parentEmbeddingBackfillFallbackAcquiresBackground",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BulkEncodeUrgencyTest.java#chunkEmbeddingBackfillFallbackAcquiresBackground",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BulkEncodeUrgencyTest.java#inlineDocumentEmbeddingAcquiresBackground",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BulkEncodeUrgencyTest.java#spladeBackfillFallbackAcquiresBackground",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BulkEncodeUrgencyTest.java#bgeM3BackfillFallbackAcquiresBackground",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BulkEncodeUrgencyTest.java#nerBackfillAcquiresBackground"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSemanticBudgetTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineExpansionBudgetTest.java"
      ],
      "class": "New",
      "behaviour": "Preservation, passing on 68547f75a and on the change. B5: a request deadline too short to leave a semantic sub-budget is still honoured as a deadline. P6a: a lexical-only search does not wait for models that are still loading. P6b: a lexical-only search never consults the semantic budget, and its response carries no SEMANTIC_BUDGET_EXCEEDED. Expansion: a re-search that keeps its semantic legs is applied as before.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "P6",
          "quote": "Idle benchmark (E1) quality unchanged; lexical-only requests stay off the model latch and the budget.",
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
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S1",
          "quote": "During the scripted agent on a quiet 12 GB machine, no search returns a 504 or times out; every search answers within its deadline.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-search-budget-preservation.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSemanticBudgetTest.java#deadlineTooShortForASemanticBudgetIsStillADeadline",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSemanticBudgetTest.java#lexicalOnlySearchDoesNotWaitForModels",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSemanticBudgetTest.java#lexicalOnlySearchNeverTouchesTheSemanticBudget",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineExpansionBudgetTest.java#aReSearchThatKeepsItsSemanticLegsIsApplied"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/HeadSearchDeadlineInProcessTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineExpansionBudgetTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpSemanticBudgetNoteTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "H1a (in-process engine with a 5000 ms request deadline): when models stall, the base search plus its expansion re-search answers within D, the re-search runs, and results are returned. Before the change it took longer than D. H1b (KnowledgeSearchEngine): if the expansion re-search loses a semantic leg that the base search kept (it newly reports SEMANTIC_BUDGET_EXCEEDED), the base results are returned and the expansion is skipped. M1 (MCP): a SPLADE-leg budget miss is named in the MCP search note and the summary.",
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
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "completing the agreed design for item 5a (search budget)",
          "choice": "One head deadline D per search, fixed when the search opens (open time + the client's request deadline, or a caller's narrower deadline). The expansion re-search gets min(now + 1500 ms, D). If the re-search newly misses a semantic leg the base kept, the base results stay and the expansion is skipped with reason TIMEOUT.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-search-budget-head-repaired.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/HeadSearchDeadlineInProcessTest.java#expansionReSearchWithStalledModelsStaysWithinTheDeadline",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineExpansionBudgetTest.java#aReSearchThatLosesASemanticLegKeepsTheBaseResults",
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpSemanticBudgetNoteTest.java#spladeLegBudgetMissIsNamedInTheNoteAndTheSummary"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SearchLegCauseContractTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/SearchPerSourceCauseMergeTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpSemanticBudgetCauseTest.java",
        "modules/ui-web/src/shell-v0/aggregate-substrate/strategies/searchTraceExplain.test.ts"
      ],
      "class": "New",
      "behaviour": "The per-leg cause of a semantic budget miss. SearchLegCauseContractTest (real WorkerSearchService): the cause vocabulary matches the enum both ways, and a cause appears exactly when its leg reports SEMANTIC_BUDGET_EXCEEDED. The cause is 'loading' while models load, 'contended' on the leg whose device was held, and 'stuck' for a seated run that does not return. A held SPLADE device sets the cause on the SPLADE leg only. SearchPerSourceCauseMergeTest: across sources the strongest dense cause wins with its reason; a budget miss is not overwritten by a later reason without a cause; SPLADE merges independently of dense; without causes the last reason still wins. McpSemanticBudgetCauseTest: each leg's cause appears in the MCP text, a SPLADE-only miss does not claim keyword-only ranking, and no cause adds no cause text. searchTraceExplain.test.ts: the existing '602 R6' exact-vocabulary list gains SEMANTIC_BUDGET_EXCEEDED, because S2 requires the UI to word it. New 'Spec D U1' tests check that LEG_CAUSE_WORDING covers contended, loading and stuck exactly, that a budget miss is worded with its cause on each leg without raw codes, and that a fallback without a cause keeps its wording. The cross-encoder mirror list CROSS_ENCODER_WORDED_CODES gains SEARCH_DEADLINE_EXCEEDED, the new drop member of CrossEncoderSkipReason, and a new 'Spec D' test checks that a rerank cut short by the search deadline is worded on the user line without the raw code.",
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
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "completing the agreed design for item 5a (search budget)",
          "choice": "The cause vocabulary is contended, loading and stuck, carried per leg (hybrid_fallback_cause, splade_skip_cause) next to SEMANTIC_BUDGET_EXCEEDED. Across sources the strongest cause wins (stuck over loading over contended). The UI wording is 'semantic search did not answer in time' plus the worded cause. A rerank cut short by the search deadline is worded 'Relevance re-ranking did not fit in the time this search had — results are ranked without it'.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-search-budget-new.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SearchLegCauseContractTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/SearchPerSourceCauseMergeTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpSemanticBudgetCauseTest.java",
        "modules/ui-web/src/shell-v0/aggregate-substrate/strategies/searchTraceExplain.test.ts"
      ]
    },
    {
      "items": [
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleDetachedRunTest.java",
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleRunThreadCapTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineHeadDeadlineTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineKnowledgeClientHeadBudgetTest.java"
      ],
      "class": "New",
      "behaviour": "The mechanisms that keep a search within its deadline. NativeSessionHandleDetachedRunTest: a run past its semantic deadline releases the caller, and its run thread releases the lease exactly once. A run that returns in time gives its result to the caller. A caller without a semantic deadline runs on its own thread as before. A failed detached run is still released once. Detached runs that fill the device (one after another) make the next run fail at once with RUN, and the device count returns to zero when they end. The run reads its own copy of the inputs after the caller closed them. NativeSessionHandleRunThreadCapTest: eight callers start at once on two CPU handles (one device), each a query run with a 300 ms deadline whose native call stalls. At most two native calls are ever entered on the device, and at most two are in flight together. Every caller, started or not, gets a SessionAcquireDeadlineExceededException within its deadline plus 1 s. This check uses the release-at-deadline acquisition added in this change, so it has no before-state at the gate base; on the candidate before this repair (3d181a70c) it fails with '8 stalled native runs were started for 8 concurrent callers'. KnowledgeSearchEngineHeadDeadlineTest: every worker search of a request runs inside one head deadline, a caller's narrower deadline is the search deadline, and each source of a fan-out runs inside the same head deadline. EngineKnowledgeClientHeadBudgetTest: an RPC budget is the category budget without a head deadline, and otherwise the smaller of the category budget and what remains of D, with at least 1 ms.",
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
          "scenario": "P2",
          "quote": "Without a usable NVIDIA GPU, keyword and CPU semantic search keep working.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "completing the agreed design for item 5a (search budget)",
          "choice": "A query run with a semantic deadline runs on a separate platform thread that owns the lease and inputs it was given (copies) and closes them exactly once. The caller is released at the deadline. Each device (the CPU, or one CUDA device) has at most two run threads in flight, live or detached, counted across all handles under one lock before a thread starts, so at most two runs can outlive their callers on a device. A caller that finds both run threads held by detached runs fails at once with RUN (cause stuck). A caller that finds a run thread held by a live run waits for one until its own deadline, then fails with GATE (cause contended). The head deadline is a per-thread binding that worker RPCs and per-source fan-out threads read, bounding each RPC at min(category budget, D - now), at least 1 ms.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-search-budget-new.json",
      "newChecks": [
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleDetachedRunTest.java",
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleRunThreadCapTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineHeadDeadlineTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineKnowledgeClientHeadBudgetTest.java"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/HeadRerankDeadlineInProcessTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "R1 (in-process engine with a 5000 ms request deadline, the production KnowledgeHttpApiAdapter head over the in-process client, the real WorkerSearchService and a real CrossEncoderReranker whose ONNX session stalls in run): a hybrid search with the cross-encoder enabled answers within D, returns its fused keyword result, and its cross-encoder trace stage is SKIPPED with reason SEARCH_DEADLINE_EXCEEDED. The native call was entered once. R2 (same harness, reranker answering at once): the worker's rerank RPC finds the models still loading (its models-ready wait was up to 120 s; since repair round 2 it ends at the call's deadline) while the base search finds them ready; the search answers within D with its fused result and SEARCH_DEADLINE_EXCEEDED, and no rerank ran. Before the change both searches waited for the rerank RPC's own category budget (about 60 s on 68547f75a).",
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
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "completing the agreed design for item 5a (search budget)",
          "choice": "The rerank stage runs inside the head deadline D, ending a 500 ms respond margin before it (rerank deadline = D - 500 ms), through the same per-thread head binding the worker searches use. If less than min(rerank budget, 200 ms) is left before the rerank deadline, the head makes no rerank RPC. If the rerank RPC fails after the rerank deadline has passed, the head stops waiting. In both cases the results keep their fused order and the cross-encoder stage carries the new drop reason SEARCH_DEADLINE_EXCEEDED, produced by the head (and, since repair round 2, sent by the worker when its rerank call reaches its own deadline). DEADLINE_EXCEEDED keeps its meaning (the reranker's own budget pre-check). A rerank that fails while time is left stays RPC_FAILED.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-search-budget-rerank-repaired.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/HeadRerankDeadlineInProcessTest.java#hybridSearchWithAStalledRerankerAnswersWithinTheDeadlineWithFusedResults",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/HeadRerankDeadlineInProcessTest.java#hybridSearchWhoseRerankWaitsForLoadingModelsAnswersWithinTheDeadline"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/HeadRerankAdmissionInProcessTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceRerankCallDeadlineTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Repair round 2 (verify d14b, F1: on a CPU-only stack a rerank the head cut kept running natively and kept its engine admission slot, so keyword searches were refused with 429 ENGINE_LIMIT). A1 (in-process engine with a 5000 ms request deadline, the real EngineAdmissionController, the production head, the real WorkerSearchService and a real CrossEncoderReranker whose ONNX session stalls in run): with all but one admission slot held by other clients, a hybrid search whose rerank is cut answers within D with SEARCH_DEADLINE_EXCEEDED, and a keyword search made right after it is admitted and finds its document while the cut native run is still running. On 4cfcc6012 the keyword search is refused with ENGINE_LIMIT (live engine work 64 of 64). A2 (same harness): once the reranker's run threads all hold abandoned runs, the next hybrid search skips the rerank with SEARCH_DEADLINE_EXCEEDED within 2000 ms and starts no native run. On 4cfcc6012 a fifth native run is started (expected 4, was 5). W1 (real WorkerSearchService): a rerank call whose models are still loading answers by its 400 ms call deadline, skipped with SEARCH_DEADLINE_EXCEEDED; on 4cfcc6012 it waited past the 5400 ms preemptive limit. W2: the rerank call asks the reranker to release it at the call's own deadline; on 4cfcc6012 releaseCallerAtDeadline was false.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "P2",
          "quote": "Without a usable NVIDIA GPU, keyword and CPU semantic search keep working.",
          "label": "agent-drafted, owner-adopted"
        },
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
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "completing the agreed design for item 5a (search budget)",
          "choice": "The worker's rerank call answers by its call deadline (less a margin of up to 100 ms), so its engine admission is released before the head moves on. Its models-ready wait ends at that time, and its native run is released there too: the run continues on the reranker's own run threads (two per device, separate from the query encoders'), which bound the native time of abandoned reranks. When those threads all hold abandoned runs, the rerank is skipped at once instead of queueing. Each of these answers is a skipped rerank with skip_reason SEARCH_DEADLINE_EXCEEDED, which the Worker now also sends.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-search-budget-admission-repaired.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/HeadRerankAdmissionInProcessTest.java#aSearchWhoseRerankWasCutLeavesNoEngineAdmissionBehind",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/HeadRerankAdmissionInProcessTest.java#aRerankerWhoseRunThreadsAllHoldAbandonedRunsSkipsTheRerankAtOnce",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceRerankCallDeadlineTest.java#aRerankWaitingForLoadingModelsAnswersByItsCallDeadline",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceRerankCallDeadlineTest.java#theRerankAsksTheRerankerToReleaseItAtTheCallDeadline"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineRerankDeadlineTest.java"
      ],
      "class": "New",
      "behaviour": "The rerank stage at the head seam (KnowledgeSearchEngine over a mocked KnowledgeClient, reranker configured, one hit). The rerank RPC runs inside a head binding equal to the search's start + requestDeadlineMs - 500 ms, and an answered rerank is EXECUTED. With 1000 ms of D and a 400 ms base search, the head makes no rerank RPC, returns the fused result and names SEARCH_DEADLINE_EXCEEDED. A rerank that is released at its deadline (the client throws DEADLINE_EXCEEDED once the binding has passed) gives the fused result within D, named SEARCH_DEADLINE_EXCEEDED, not RPC_FAILED. A rerank that fails with time left stays RPC_FAILED. The binding it reads is added in this change, so there is no before-state at the gate base; on the candidate before this repair (3d181a70c) the first three fail by assertion and the last passes.",
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
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "completing the agreed design for item 5a (search budget)",
          "choice": "The rerank stage runs inside the head deadline D, ending a 500 ms respond margin before it (rerank deadline = D - 500 ms), through the same per-thread head binding the worker searches use. If less than min(rerank budget, 200 ms) is left before the rerank deadline, the head makes no rerank RPC. If the rerank RPC fails after the rerank deadline has passed, the head stops waiting. In both cases the results keep their fused order and the cross-encoder stage carries the new drop reason SEARCH_DEADLINE_EXCEEDED, produced by the head (and, since repair round 2, sent by the worker when its rerank call reaches its own deadline). DEADLINE_EXCEEDED keeps its meaning (the reranker's own budget pre-check). A rerank that fails while time is left stays RPC_FAILED.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-search-budget-new.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineRerankDeadlineTest.java"
      ]
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/knowledge/CrossEncoderSkipReasonTest.java"
      ],
      "class": "Structural rule kept",
      "rule": "dropClassIsDeclared: for every CrossEncoderSkipReason member, isDrop() equals the drop class declared by the test's own exhaustive switch (drops are worded at the user tier, by-design skips are not).",
      "whySurvives": "The predicate and the loop over values() are unchanged. The declared drop list gains one row, SEARCH_DEADLINE_EXCEEDED, the new member this change adds with its head producer; the brief for this repair names updating this contract test. The switch is exhaustive, so a member missing from it does not compile, and every existing row keeps its class."
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BgeM3BackfillOpsTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EmbeddingBackfillOpsTest.java"
      ],
      "class": "Adaptation",
      "moved": ["signature"],
      "whatMoved": "The per-document fallback now calls the request overloads encode(text, InferenceRequest) and embedDocument(text, InferenceRequest), so the call carries BACKGROUND urgency instead of the one-argument forms. Each stub and verify that named encode(text) or embedDocument(text) now names the same text with any(InferenceRequest.class). The inputs, the thrown failures, the returned vectors, the verified calls and counts, the never() checks and every assertion are unchanged."
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpEvidenceProjectionTest.java"
      ],
      "class": "Adaptation",
      "moved": ["signature"],
      "whatMoved": "The SearchTrace Degradation record gained two trailing components: the dense and SPLADE per-leg causes. The fixture constructs it with the 8-argument form, passing the placeholder causes \"C1\" and \"C2\" after the six unchanged values. This is the maximal fixture of the reflective totality guard, which needs every per-hit component non-null. So the new slots are filled; the guard itself now also covers them. The observation, the guard's predicate and every expected value are unchanged."
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "847f76f6-9f0e-435f-9570-b41b3d664998",
  "verdict": "reject",
  "digest": "sha256:9b6122672b5e5245ac843be6bc7e6d9ceee92ecf25523800e55cf6898195f21c",
  "note": "1) Entry 'per-leg cause' (SearchPerSourceCauseMergeTest et al.): its only source for the merge order, the delegated choice, says 'stuck over contended over loading', but theSpladeLegMergesIndependentlyOfTheDenseLeg expects contended+loading -> 'loading' (code and contract doc use stuck > loading > contended). The expectation contradicts its cited source; fix the choice text or the test. 2) Entry 'mechanisms' (NativeSessionHandleDetachedRunTest): the choice states 'At most two detached runs per handle-device; a further run fails at once', but the checks only fill the slots one run after another; a verifier case with 8 concurrent stalled CPU query runs ends with 8 detached at once (cap 2). The cited invariant is not what the code does, so the entry claims more than its checks establish. 3) Note, not blocking alone: HeadSearchDeadlineInProcessTest fail-before is real but thin (baseline 5016/5019 ms vs D 5000 ms)."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "dc57709a-2c0e-4c05-adbc-5ac2a4510415",
  "verdict": "accept",
  "digest": "sha256:8b7a74dd2bc4b557e057ebd508dcf7a32ddb17f162bc372bf02c6e06aded7ac4",
  "note": "Verified on 1392fdfbc: 18 repaired checks fail on 68547f75a by assertion for the reason under test (BACKGROUND urgency, timeouts/deadline exceptions, 60 s rerank waits, H1 5012 ms vs D 5000 ms - thin but structural, expansion base results, missing MCP note); preservation checks pass before and after; all new checks pass in the full touched-module suite (8442 pass, 0 fail). Prior reject points fixed: merge order text now stuck>loading>contended; concurrent cap test added (8 callers, at most 2 native runs). Adaptation diffs only change matchers to eq()/any(InferenceRequest); oracles kept. Structural rule kept: predicate unchanged, one drop row added. Product finding outside test intent (CPU-only rerank orphan holds admission slot) reported separately."
}
```
