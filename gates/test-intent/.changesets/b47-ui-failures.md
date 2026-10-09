---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b47-ui-failures-20261009
---

# Preserve UI refusal and loading-budget checks after their fixture APIs changed

No response expectations, deadlines, tags, endpoints or checks are removed. Both failures are
fixture drift: a replaced executor opening/dispatch structure and a replaced latch-only encoding
of model presence. The before-state is 515133c11; historical bisection and exact XML are recorded
in F:/agent-sandbox/design-b6d536/codex/b47-ui-failures/report.md.

```json
{
  "entries": [
    {
      "items": ["modules/ui/src/test/java/io/justsearch/ui/api/IndexingControllerSaturationTest.java"],
      "class": "Adaptation",
      "moved": ["instrumentation"],
      "whatMoved": "Observe the real reserved-seat inference pool through openWithReservedSeats, retaining open for the text pool. Saturate its declared waiting capacity rather than its private dispatch buffer, which also stores reserved seats and legitimately exceeds the admission queue. Keep the active-worker and waiting-task observations and every endpoint's 429, Retry-After, exact errorCode, retrySafe=false and no-ingest assertion. The backing buffer's remainingCapacity==0 observation is replaced by declared-capacity saturation; it no longer represents admission fullness after 9fde46683. No product behavior oracle changes.",
      "sources": [
        {"kind": "owner-words", "location": "task t-20261005-b6d536 agreement FP1", "quote": "with admission below its limits, 9 to 16 concurrent foreground requests that may run inference (SearchRpcOps.mayRunInference) all run; none is refused at once. The pool gets a bounded queue equal to its thread count (8 by default, 1 with the minimum policy), within the policy's foregroundQueue bound (validateAgainstPolicy)."},
        {"kind": "owner-words", "location": "task t-20261005-b6d536 agreement FP4", "quote": "when the inference threads and the queue are both full, the next inference request is refused with a new code ADMISSION_INFERENCE_BUSY (HTTP 429 with Retry-After; MCP -32000 with the same errorCode), never ADMISSION_ENGINE_LIMIT."}
      ]
    },
    {
      "items": ["modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpAnswerModelsLoadingInProcessTest.java"],
      "class": "Adaptation",
      "moved": ["input re-encoding"],
      "whatMoved": "The fixture's closed models latch and cleared compatibility verdict previously implied loading even with no native model in its isolated directory. a37c6ef33 replaced that implicit encoding with explicit provider availability. Set NoOpEmbeddingProvider.starting() through the existing setter before holding the same latch. Keep both MCP/chat tests, real in-process seams, all timing bounds, BM25 keyword-evidence assertions, SEMANTIC_BUDGET_EXCEEDED and loading cause unchanged. A3's genuinely absent provider is still absent in product code and the unchanged availability suite.",
      "sources": [
        {"kind": "owner-words", "location": "task t-20261005-b6d536 agreement A1", "quote": "the search path distinguishes four embedding states: ready, starting (model installed, still loading), absent (no model configured or installed) and failed (loading failed; the original cause is kept). Each search mode's outcome follows from the state, not from which placeholder object is installed or whether a compatibility reason code happens to be present."},
        {"kind": "owner-words", "location": "task t-20261005-b6d536 agreement A2", "quote": "a search whose semantic deadline ran out in the queue while the model is ready or starting answers as FP3 requires (hybrid/AUTO/SPLADE keyword-only with SEMANTIC_BUDGET_EXCEEDED and cause contended; VECTOR typed 504; RAG BM25 with the same reason and cause)."}
      ]
    }
  ]
}
```

## Acceptance records

Pending independent verifier acceptance. Builder does not self-accept.
