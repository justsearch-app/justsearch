---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: codex-b6d536-deadline-load-20261015
---

# Separate semantic expiry from the request deadline under suite load

The observed plain deadline follows the existing full-request deadline contract. The old test
released its held worker at D - 300 ms and assumed capture would finish before D. Under load the
head completed first, then capture observed its cancellation before inspecting the semantic
budget. Spec D section 1.3 explicitly preserves full-request deadline precedence. Existing FP3
agreement also exempts whole-deadline expiry (b43-warm-refusal). No product expectation changes.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineKnowledgeClientInferenceQueueTest.java"
      ],
      "class": "Adaptation",
      "moved": ["instrumentation"],
      "whatMoved": "queuedVectorOnlySearchPastItsSemanticBudgetIsTheTypedDeadline retains its vector-only request, occupied inference worker, queued precondition, real worker/capture/classification, typed SEMANTIC_BUDGET_EXCEEDED assertion, zero encoder calls and timeout annotation. Instead of sleeping until 300 ms before a 2000 ms request deadline, a test-controlled policy supplies an elapsed absolute semantic deadline after queueing, and the existing held-worker latch releases it. The independently armed request deadline is 60000 ms; the unchanged 30-second test timeout bounds the check. This isolates the original semantic-only-expiry precondition from OS scheduling. Reflection instruments existing private timing inputs without product hooks. No test is removed, skipped or disabled. A direct preservation assertion also pins the real default vector-only policy to D minus its configured margin before substituting the timing policy; this keeps the old implicit policy-formula check without pinning calibration values. The additional cause and whole-deadline expectations are recorded separately below.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 user turn 2",
          "quote": "If it's test-only, fix the test's timing without weakening its assertion, and record the change as an Adaptation with the reason."
        }
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineKnowledgeClientInferenceQueueTest.java"
      ],
      "class": "New",
      "behaviour": "Preservation: the real default vector-only deadline remains D minus its configured margin (agreed Spec D section 5.3). Semantic expiry spent queued with request time remaining keeps the typed SEMANTIC_BUDGET_EXCEEDED deadline and contended cause, without invoking the encoder. A latched capture with an already elapsed positive semantic budget whose head request-deadline action wins still reports plain deadline, cancels the worker, and does not invoke the encoder. Both checks pass against unchanged production code at 0d90a3388; this is not a repaired product defect. A diagnostic overlay retaining the original unconditional typed assertion at the forced request-first ordering fails by assertion, reproducing the invalid timing assumption deterministically.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "V1a",
          "quote": "vectorOnlyBudget = D âˆ’ marginMs",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "FP3",
          "quote": "a search that starts after its semantic deadline has passed in the queue takes the existing Spec D outcome at once without running inference: hybrid or SPLADE searches answer keyword-only with SEMANTIC_BUDGET_EXCEEDED and cause contended; VECTOR-only returns its existing typed 504 with SEMANTIC_BUDGET_EXCEEDED; RAG retrieval answers BM25 with SEMANTIC_BUDGET_EXCEEDED. Use the existing producers and the cause rule (time queued before the seat counts as contended); add no new reroute to another lane.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 user turn 2",
          "quote": "Keep a genuine non-semantic deadline classified as it is today."
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-deadline-load.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineKnowledgeClientInferenceQueueTest.java#queuedVectorOnlySearchPastItsSemanticBudgetIsTheTypedDeadline",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineKnowledgeClientInferenceQueueTest.java#requestDeadlineWinningBeforeVectorCaptureStaysUntyped"
      ]
    }
  ]
}
```

## Acceptance records

Pending independent verifier review. The builder records no acceptances.

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS4c-20261015",
  "verdict": "accept",
  "digest": "sha256:a54df084f2abe242f7a29438cd36296bf9a7c06c81d777270437a2a5d0828900",
  "note": "re-bound to main; no change since b05f6387b. Reviewed the initial timing/preservation contract and reran both selected candidate checks successfully."
}
```
