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
        {"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "with admission below its limits, 9 to 16 concurrent foreground requests that may run inference (SearchRpcOps.mayRunInference) all run; none is refused at once. The pool gets a bounded queue equal to its thread count (8 by default, 1 with the minimum policy), within the policy's foregroundQueue bound (validateAgainstPolicy).", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 agreement FP1'; the text does not appear in the owner's turns of session 886833dd"},
        {"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "when the inference threads and the queue are both full, the next inference request is refused with a new code ADMISSION_INFERENCE_BUSY (HTTP 429 with Retry-After; MCP -32000 with the same errorCode), never ADMISSION_ENGINE_LIMIT.", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 agreement FP4'; the text does not appear in the owner's turns of session 886833dd"}
      ]
    },
    {
      "items": ["modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpAnswerModelsLoadingInProcessTest.java"],
      "class": "Adaptation",
      "moved": ["input re-encoding"],
      "whatMoved": "The fixture's closed models latch and cleared compatibility verdict previously implied loading even with no native model in its isolated directory. a37c6ef33 replaced that implicit encoding with explicit provider availability. Set NoOpEmbeddingProvider.starting() through the existing setter before holding the same latch. Keep both MCP/chat tests, real in-process seams, all timing bounds, BM25 keyword-evidence assertions, SEMANTIC_BUDGET_EXCEEDED and loading cause unchanged. A3's genuinely absent provider is still absent in product code and the unchanged availability suite.",
      "sources": [
        {"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "the search path distinguishes four embedding states: ready, starting (model installed, still loading), absent (no model configured or installed) and failed (loading failed; the original cause is kept). Each search mode's outcome follows from the state, not from which placeholder object is installed or whether a compatibility reason code happens to be present.", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 agreement A1'; the text does not appear in the owner's turns of session 886833dd"},
        {"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "a search whose semantic deadline ran out in the queue while the model is ready or starting answers as FP3 requires (hybrid/AUTO/SPLADE keyword-only with SEMANTIC_BUDGET_EXCEEDED and cause contended; VECTOR typed 504; RAG BM25 with the same reason and cause).", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 agreement A2'; the text does not appear in the owner's turns of session 886833dd"}
      ]
    }
  ]
}
```

## Acceptance records

Independent verifier acceptance is recorded below for the fixture adaptations. The overall native cold-answer claim remains inconclusive; see the verifier note and report.

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-fixtures-20261010",
  "verdict": "accept",
  "digest": "sha256:a6a813b2c061cc177cb3fbadae886af44def38a740f8f0e52732efdc102d7028",
  "note": "Accept the two fixture adaptations only. Independent baseline UI reproduces exactly the three reported failures; candidate filtered UI 1584 tests and worker-services 1613 tests pass (one and three skips respectively), architecture 82 passes and lane F 160 passes on both revisions. All response/deadline assertions are unchanged. One remainingCapacity assertion was removed; declared-capacity/active-worker checks and a real extra-admission mutation prove the retained saturation refusal oracle catches the boundary. The isolated RAG reason mutation fails both MCP/chat tests with NO_EMBEDDING_SERVICE instead of SEMANTIC_BUDGET_EXCEEDED while BM25 chunks remain; MCP quality retains loading cause. Real KnowledgeServer.start publishes STARTING for an installed model, FAILED with the original cause, and ABSENT when unconfigured, on both revisions (native assembler mocked). The opening sentence claiming no checks removed is too broad; the entry correctly discloses the removed buffer assertion. Overall product claim remains inconclusive for native cold MCP/RAG answers: archived compatible cold evidence is search-only, and mixed RAG raw calls are GRPC_FAILED. No dev stack; exclusions and shared-machine overlap are named in F:/agent-sandbox/design-b6d536/codex/verify-fixtures/report.md. This acceptance does not certify all item 5/GPU/E2E scenarios."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS3-20261015",
  "verdict": "accept",
  "digest": "sha256:e225e9a291e55f38b5e45c6a82625704e8974de7edcf0330ba4d9d9a8457bf28",
  "note": "Reviewed both source corrections and the unchanged UI failure oracles/evidence. Both selected current candidate checks pass; the new-API limits remain declared."
}
```
