---
schema: test-intent.v1
task: t-20261018-6108b0
author-role: orchestrator
author-session: cc6d91b2-5314-42fd-867b-dbc2d8a68699
---

# Wait for the held composition's witness latch

The existing recovery oracle is preserved. This author records no acceptance.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/indexerworker/server/HeldInferenceCompositionFixture.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Only HeldInferenceCompositionFixture.witnessPublished changes: it waits up to 5 seconds for the fixture's own witness latch instead of reading it with a zero wait. The latch is still counted down only after the real query-witness callback and the plan callback return, and the caller's assertTrue(fixture.witnessPublished()) in EngineRootIndexRecoveryTest.readyPublicationUsesQueryWitnessWhileNativeCompositionRemainsFenced is unchanged, so a composition that never reaches the callback still fails. All other assertions, inputs, preconditions, expected outcomes and timeouts remain. Source: CI run 40457890425 attempt 1, job \"Unit tests (platform-contracts-1)\", AssertionFailedError at EngineRootIndexRecoveryTest.java:540. The product opens its own witness latch inside the query callback (KnowledgeServer queryWitnessReadyLatch), which lets recovery publish READY; the fixture counts down its latch on the deferred executor thread after that callback returns, so recoverIndex could return before the fixture latch opened."
    }
  ]
}
```
