---
schema: test-intent.v1
task: t-20261018-6108b0
author-role: builder
author-session: builder-t6108b0-aging-claude-subagent
---

# Request aging without wall-clock sleeps

Two worker-services classes aged a call past the semantic cap by sleeping after building it. They
now build the same call already aged: its start and its request deadline are back-dated on the
same `System.nanoTime()` clock by the time the sleep used to take. At service entry the call has
the same elapsed time and the same remaining request budget as before. No production code changes.
Each class's aged case fails when the back-dating is set to zero.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedUnavailableTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceAvailabilityTest.java"
      ],
      "class": "Adaptation",
      "moved": ["input re-encoding"],
      "whatMoved": "Only the time source of the aging changed. Before, a test built a foreground call (budget B from now) and slept Q ms before calling the service: 8500 ms of 20 s or 30 s for hybrid, AUTO, SPLADE and RAG, and 1600 ms of 2000 ms for VECTOR-only. Now the new helper queuedFor(B, Q) builds the same foreground call through the canonical CallContext constructor, with startNanos = now - Q and deadlineNanos = startNanos + B. At service entry, Q has elapsed since the call start and B - Q of the request remains, as after the sleep. queuedCall and queuedVectorCall now assert the setup preconditions that were only comments before: Q is past the 8000 ms CPU semantic cap while the remaining 11.5 s is above the lexical reserve plus margin, so the semantic cap expires and the request does not; for VECTOR-only, the remaining 400 ms is positive but under the 500 ms margin. The timely (unexpired) Availability calls use queuedFor(B, 0), the same call as foreground(B). Kept unchanged: every existing assertion (SEMANTIC_BUDGET_EXCEEDED with cause contended, the DEADLINE_EXCEEDED or INVALID_ARGUMENT vector outcome, the absent and missing-model outcomes, zero inference calls and zero model waits after queued expiry, the timely model-wait count); the real Lucene index and search execution; the provider and encoder doubles; the models-ready latches. The timelyHybrid and timelyAuto cases still wait for the real 8000 ms cap on the loading latch, which is their own observation, not aging. No test is removed, skipped or disabled. Fail-when-not-aged: with startNanos set to now (no back-dating), queuedHybridWithUnavailableProviderKeepsBudgetOutcome fails with expected contended but was loading, queuedUnavailableVectorKeepsTypedDeadline fails its contended assertion, and readyAcrossAllModesPreservesQueueExpiry fails 5 of its mode assertions (SEMANTIC_BUDGET_EXCEEDED expected, the vector failure not thrown)."
    }
  ]
}
```

## Acceptance records

Pending independent review. The builder records no acceptances.

```json
{
  "kind": "acceptance",
  "role": "orchestrator",
  "session": "orchestrator-cc6d91b2",
  "verdict": "accept",
  "digest": "sha256:7853fccea5725ca19760febb8e0680dce96870b4228d641409422278b50cc92a",
  "note": "Adaptation (input re-encoding) confirmed: before-state slept Q after building a call of budget B; candidate back-dates start by Q with deadline start+B, same elapsed and remaining budget at entry. Added asserts are setup preconditions on SearchBudgetPolicy.DEFAULT, not expected values. All assertions, real index/search, doubles and latches unchanged. Independent Codex verifier (results in task t-20261018-6108b0) reproduced: same 22 pass; a production mutation counting the budget from service entry makes 18 fail; full module 1802 pass."
}
```
