---
schema: test-intent.v1
task: t-20261018-6108b0
author-role: builder
author-session: cc6d91b2-5314-42fd-867b-dbc2d8a68699
---

# Give returned-root replay preparation a load-tolerant budget

Only the test fixture's default preparation budget changes. Each assertion, input and order case is
unchanged.

```json
{
  "entries": [
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/ReturnedRootCurrentByteReplayTest.java"
      ],
      "class": "Incidental",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including red-main and flaky-test repairs",
          "choice": "Raise the fixture's default promotion-preparation deadline from 8 s to 30 s so a loaded machine does not turn slow settlement into a false refusal; keep the one case whose expected refusal is a settlement timeout at its original explicit 8 s.",
          "label": "agent choice"
        }
      ],
      "assessment": {
        "purpose": "preparedCurrentBytesKeepTheOriginalBroadDeleteOrder(collection-before) failed once at its isPresent assertion during a loaded full indexer-worker unit run (1818 tests, 1 failed) and passed 3/3 isolated. KnowledgeServerMigrationOps.awaitQueuedUpserts waits for the started IndexingLoop to settle queued work until the context deadline and returns false at the deadline, so prepareSwitchReplayForPromotion returns empty. The fixture's 8 s deadline is the only bound; idle, these cases take about 1.1-2.5 s.",
        "callersAndStateOwners": "Fixture.prepare() is test-only and is called by every case of this class; it builds a DrainSwitchBufferContext with that deadline. No production code, budget, or saved state changes. The production SWITCHING deadline is untouched.",
        "indirectBehaviours": "Calls that expect a present replay now tolerate up to 30 s of slow settlement and still return as soon as preparation settles. Calls that expect empty: laterIndependentPendingRevisionAndItsWaitRemainUntouched reaches empty only by spending the budget (9.1 s of its 9.2 s run), so it now passes its original 8 s explicitly and keeps its duration; unknownOrConflictingDebtLineageCannotReadmitOwnedDoneWork refuses within about 2.3 s, before any budget, so it is unaffected. The held-commit case with its own explicit 8 s retry loop is unchanged.",
        "evidence": "With the default budget shrunk to 300 ms on main c9e25fa97f, all four preparedCurrentBytesKeepTheOriginalBroadDeleteOrder cases fail with the reported AssertionFailedError 'current-byte prep must not move an admission after a final delete', showing that deadline expiry produces exactly the reported failure. On the candidate the class passes 35/35 with no skips; per-case times are unchanged (longest 9.1 s, the explicit 8 s case). The loaded failure itself was reported by another session and is not deterministically reproducible here.",
        "unresolvedRisks": "No deterministic reproduction of the loaded-machine failure; a machine more than about twelve times slower than idle could still expire 30 s. A real regression that never settles now fails after 30 s instead of 8 s."
      }
    }
  ]
}
```

## Acceptance records
