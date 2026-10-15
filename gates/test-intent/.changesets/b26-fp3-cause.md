---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b26-fp3-cause-20261008
---

# FP3 queue cause and HTTP RAG metadata

Only additional expectations from the authorised repair brief; existing checks are unchanged.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedLoadingTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "U4: a real WorkerSearchService over a temporary Lucene index sees a call that spent 2300 ms queued before its 2000 ms semantic cap. With the model-ready latch closed, hybrid answers TEXT with SEMANTIC_BUDGET_EXCEEDED/contended and hits; RAG answers BM25 with chunks and the same reason/cause, even with semantic_loading_observed. Neither starts a native encoder or waits for models. Both fail by assertion at f8c4967ed (loading), pass after.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "FP3",
          "quote": "a search that starts after its semantic deadline has passed in the queue takes the existing Spec D outcome at once without running inference: hybrid or SPLADE searches answer keyword-only with SEMANTIC_BUDGET_EXCEEDED and cause contended; VECTOR-only returns its existing typed 504 with SEMANTIC_BUDGET_EXCEEDED; RAG retrieval answers BM25 with SEMANTIC_BUDGET_EXCEEDED. Use the existing producers and the cause rule (time queued before the seat counts as contended); add no new reroute to another lane.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b26-fp3-cause.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedLoadingTest.java#queuedHybridWithModelsLoadingReportsContentionWithoutAwaitingModels",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedLoadingTest.java#queuedRagWithModelsLoadingReportsContentionWithoutAwaitingModels"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedLoadingTest.java"
      ],
      "class": "New",
      "behaviour": "Preservation: queued hybrid with the model-ready latch open remains TEXT/SEMANTIC_BUDGET_EXCEEDED/contended with no native encode or model wait. Hybrid seated within its cap waits once for the closed latch and remains SEMANTIC_BUDGET_EXCEEDED/loading, with keyword hits and no encoder calls. Both pass before and after.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "FP3",
          "quote": "a search that starts after its semantic deadline has passed in the queue takes the existing Spec D outcome at once without running inference: hybrid or SPLADE searches answer keyword-only with SEMANTIC_BUDGET_EXCEEDED and cause contended; VECTOR-only returns its existing typed 504 with SEMANTIC_BUDGET_EXCEEDED; RAG retrieval answers BM25 with SEMANTIC_BUDGET_EXCEEDED. Use the existing producers and the cause rule (time queued before the seat counts as contended); add no new reroute to another lane.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Keep loading for the case FP3 does not cover: a request that reaches its seat in time and then waits for loading models.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b26-fp3-cause.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedLoadingTest.java#queuedHybridWithReadyModelsStillReportsContention",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedLoadingTest.java#seatedHybridWithModelsLoadingKeepsLoadingCause"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/RetrieveContextControllerFallbackTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "U3: POST /api/knowledge/retrieve-context through real Javalin/HTTP/JSON retains context and total_found and projects BM25 with quality.retrieval_mode_reason SEMANTIC_BUDGET_EXCEEDED and quality.retrieval_mode_cause contended. Like MCP, a HYBRID_AVAILABLE reason is present and an empty cause is omitted. Both checks fail on f8c4967ed by assertion (missing reason) and pass after. Existing quality snake_case contract style is preserved.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "FP3",
          "quote": "a search that starts after its semantic deadline has passed in the queue takes the existing Spec D outcome at once without running inference: hybrid or SPLADE searches answer keyword-only with SEMANTIC_BUDGET_EXCEEDED and cause contended; VECTOR-only returns its existing typed 504 with SEMANTIC_BUDGET_EXCEEDED; RAG retrieval answers BM25 with SEMANTIC_BUDGET_EXCEEDED. Use the existing producers and the cause rule (time queued before the seat counts as contended); add no new reroute to another lane.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Project reason and cause on HTTP the way MCP does, keeping the existing API contract style.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b26-fp3-cause.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/RetrieveContextControllerFallbackTest.java#semanticBudgetFallbackPublishesReasonAndCause",
        "modules/ui/src/test/java/io/justsearch/ui/api/RetrieveContextControllerFallbackTest.java#hybridPublishesItsReasonWithoutAnEmptyCause"
      ]
    }
  ]
}
```

## Acceptance records

Pending independent verifier acceptance; the builder supplies execution proof and does not self-accept.

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-fp2-20261008",
  "verdict": "accept",
  "digest": "sha256:a14e5cf257ab30ca9220fe1bcdeb03b5c4d1a530c344962f964b7427a2f10f00",
  "note": "The identical six checks pass on the candidate; four fail on f8c4967ed by the intended assertion and two preservation controls pass, corroborated by direct compilation and ordinary Gradle. Closed and ready available-provider probes corroborate the repaired queue cause. Test intent is accepted; implementation verdict is FAIL FP3: a transiently unavailable/loading compatible provider substitutes NO_EMBEDDING_SERVICE for explicit HYBRID and loses reason/cause for AUTO after queued expiry. Independent worker probes establish the elapsed queue; cold real HTTP reproduces the wrong-reason pattern. Evidence and limits: F:/agent-sandbox/design-b6d536/codex/verify-fp2/report.md."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS2-20261015",
  "verdict": "accept",
  "digest": "sha256:70226e9f22cb4014a6bf19f4040d87c63545a4aba230c1179be02df2b33b6499",
  "note": "Reviewed queued/loading cause and real HTTP reason projections against the intent sources. Candidate checks passed; own f8c4967ed reruns reproduced the declared assertions and preservation outcomes."
}
```
