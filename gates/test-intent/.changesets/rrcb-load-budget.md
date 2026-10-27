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
          "choice": "Raise the fixture's default promotion-preparation deadline from 8 s to 20 s, inside JUnit's 30 s default test timeout, so a loaded machine does not turn slow settlement into a false refusal; keep the one case whose expected refusal is a settlement timeout at its original explicit 8 s; report the preparation time in the reported case's failure message so a recurrence shows whether the budget was spent.",
          "label": "agent choice"
        }
      ],
      "assessment": {
        "purpose": "preparedCurrentBytesKeepTheOriginalBroadDeleteOrder(collection-before) failed once at its isPresent assertion during a loaded full indexer-worker unit run (1818 tests, 1 failed) and passed 3/3 isolated. KnowledgeServerMigrationOps.awaitQueuedUpserts waits for the started IndexingLoop to settle queued work until the context deadline and returns false at the deadline, so prepareSwitchReplayForPromotion returns empty. The fixture's 8 s deadline is the only bound; idle, these cases take about 1.1-2.5 s.",
        "callersAndStateOwners": "Fixture.prepare() is test-only and is called by every case of this class; it builds a DrainSwitchBufferContext with that deadline. No production code, budget, or saved state changes. The production SWITCHING deadline is untouched. The build's JUnit default timeout (junit.jupiter.execution.timeout.default=30s, JvmBaseConventionsPlugin) still bounds every case.",
        "indirectBehaviours": "Calls that expect a present replay now tolerate up to 20 s of slow settlement and still return as soon as preparation settles. Calls that expect empty: laterIndependentPendingRevisionAndItsWaitRemainUntouched reaches empty only by spending the budget (9.1 s of its run), so it now passes its original 8 s explicitly and keeps its duration; unknownOrConflictingDebtLineageCannotReadmitOwnedDoneWork refuses within about 2.3 s, before any budget, so it is unaffected. The held-commit case with its own explicit 8 s retry loop is unchanged. The reported case's assertion message gains the measured preparation time; its condition is unchanged.",
        "evidence": "With the default budget shrunk to 300 ms on main c9e25fa97f, all four preparedCurrentBytesKeepTheOriginalBroadDeleteOrder cases fail with the reported AssertionFailedError, so deadline expiry produces exactly the reported failure. Under CPU starvation (the test JVM's threads pinned to one CPU shared with a busy loop), three runs of the four cases on c9e25fa97f gave 3 failures in 12: prefix-after failed with the reported assertion after 16.8 s, and the first case twice exceeded the 30 s JUnit timeout. The same three runs on the candidate passed 12/12, the first case taking 12.9-18.7 s, beyond the old 8 s. Unsqueezed, the class passes 35/35 with no skips and unchanged per-case times (longest 9.1 s, the explicit 8 s case); spotlessCheck and pmdTest pass. The class needs native volume identity (/dev/disk/by-uuid); without it most cases fail on both versions.",
        "unresolvedRisks": "Starvation runs are few and machine load varied between them; they show the mechanism, not a rate. A machine slow enough that settlement exceeds 20 s, or setup plus settlement exceeds 30 s, still fails. A real regression that never settles now fails after 20 s instead of 8 s. The original loaded failure's duration was not recorded, so its cause is inferred from source, the shrunk-budget probe and the starvation reproduction."
      }
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a17d9a-893b-7180-a874-aa41bb16ac08",
  "verdict": "accept",
  "digest": "sha256:32f8ce7aada8d0cfaf35297d8a03fa7073154c05d6727697ccba09db313d47c6"
}
```

```json
{
  "kind": "audit",
  "role": "reviewer",
  "session": "01a17d9e-dc0b-7db0-929f-ce2c247bea82",
  "verdict": "accept",
  "digest": "sha256:32f8ce7aada8d0cfaf35297d8a03fa7073154c05d6727697ccba09db313d47c6",
  "note": "The change preserves every case's oracle and the timeout-dependent refusal budget while allowing successful settlement more time under load."
}
```
