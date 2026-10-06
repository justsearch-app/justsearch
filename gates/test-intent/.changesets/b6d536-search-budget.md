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

- `b6d536-search-budget-worker-repaired.json` and `b6d536-search-budget-head-repaired.json`: repaired
  behaviour, with an assertion fail-before on 68547f75a.
- `b6d536-search-budget-new.json`: checks of new API, no before-state.
- `b6d536-search-budget-preservation.json`: preservation checks that pass before and after. That run
  exits 1 by design, because none of them shows a repair.

Three pre-existing files only follow a signature change (Adaptation entries). Two others gain a
vocabulary row that S2 requires (see their entries).

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
      "behaviour": "The per-leg cause of a semantic budget miss. SearchLegCauseContractTest (real WorkerSearchService): the cause vocabulary matches the enum both ways, and a cause appears exactly when its leg reports SEMANTIC_BUDGET_EXCEEDED. The cause is 'loading' while models load, 'contended' on the leg whose device was held, and 'stuck' for a seated run that does not return. A held SPLADE device sets the cause on the SPLADE leg only. SearchPerSourceCauseMergeTest: across sources the strongest dense cause wins with its reason; a budget miss is not overwritten by a later reason without a cause; SPLADE merges independently of dense; without causes the last reason still wins. McpSemanticBudgetCauseTest: each leg's cause appears in the MCP text, a SPLADE-only miss does not claim keyword-only ranking, and no cause adds no cause text. searchTraceExplain.test.ts: the existing '602 R6' exact-vocabulary list gains SEMANTIC_BUDGET_EXCEEDED, because S2 requires the UI to word it. New 'Spec D U1' tests check that LEG_CAUSE_WORDING covers contended, loading and stuck exactly, that a budget miss is worded with its cause on each leg without raw codes, and that a fallback without a cause keeps its wording.",
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
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "completing the agreed design for item 5a (search budget)",
          "choice": "The cause vocabulary is contended, loading and stuck, carried per leg (hybrid_fallback_cause, splade_skip_cause) next to SEMANTIC_BUDGET_EXCEEDED. Across sources the strongest cause wins (stuck over contended over loading). The UI wording is 'semantic search did not answer in time' plus the worded cause.",
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
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineHeadDeadlineTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineKnowledgeClientHeadBudgetTest.java"
      ],
      "class": "New",
      "behaviour": "The mechanisms that keep a search within its deadline. NativeSessionHandleDetachedRunTest: a run past its semantic deadline releases the caller, and its run thread releases the lease exactly once. A run that returns in time gives its result to the caller. A caller without a semantic deadline runs on its own thread as before. A failed detached run is still released once. Detached runs that fill the device make the next run fail at once. The run reads its own copy of the inputs after the caller closed them. KnowledgeSearchEngineHeadDeadlineTest: every worker search of a request runs inside one head deadline, a caller's narrower deadline is the search deadline, and each source of a fan-out runs inside the same head deadline. EngineKnowledgeClientHeadBudgetTest: an RPC budget is the category budget without a head deadline, and otherwise the smaller of the category budget and what remains of D, with at least 1 ms.",
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
          "choice": "A query run with a semantic deadline runs on a separate platform thread that owns the lease and inputs it was given (copies) and closes them exactly once. The caller is released at the deadline. At most two detached runs per handle-device; a further run fails at once. The head deadline is a per-thread binding that worker RPCs and per-source fan-out threads read, bounding each RPC at min(category budget, D - now), at least 1 ms.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-search-budget-new.json",
      "newChecks": [
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleDetachedRunTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineHeadDeadlineTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineKnowledgeClientHeadBudgetTest.java"
      ]
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
