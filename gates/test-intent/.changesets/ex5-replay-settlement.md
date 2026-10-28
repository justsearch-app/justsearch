---
schema: test-intent.v1
task: t-20261018-6108b0
author-role: builder
author-session: cc6d91b2-5314-42fd-867b-dbc2d8a68699
---

# Wait for EX5's replay receipts to be removed after promotion

Only the test waits for the cutover's own receipt removal before asserting it. Each assertion and input
is unchanged.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineExclusionReplayPolicyTest.java"
      ],
      "class": "Incidental",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including red-main and flaky-test repairs",
          "choice": "After observing Green promoted, poll for a 30 s budget for the strict switch-buffer receipt list to empty before asserting it is empty and that the depth is 0, because the cutover publishes Green before it removes the replayed receipts; keep both zero assertions so receipts that are never removed still fail.",
          "label": "agent choice"
        }
      ],
      "assessment": {
        "purpose": "EX5LegacySyncReplayKeepsServingPolicyAfterLiveCutover failed on hosted CI at assertEquals(0, switchBufferDepth()) with 3, on PR 958 (passed on retry) and PR 979 (failed both attempts, blocking auto-merge about 3.5 h), with no related source change. KnowledgeServer's promotion publishes the new serving view (servingView = next, then releases the publication lock) and only afterwards calls KnowledgeServerMigrationOps.finishPromotedSwitchReplay and checks strict emptiness. The test's promotion loop ends on the published view, so it can read the depth inside that window.",
        "callersAndStateOwners": "Test-only change in one method. The queue is the Engine-owned SwitchBufferCapableQueue the test already reads; listSwitchBufferOpsStrict is an existing read that throws instead of reporting empty on a SQL failure, unlike switchBufferDepth, which returns 0 on error. No production code, timing, saved state or schema changes. The class keeps its @Timeout(300).",
        "indirectBehaviours": "The test now polls for a 30 s budget between promotion becoming visible and receipt removal (a strict read in flight at the deadline can still return empty later), and returns as soon as the list is empty. A failure now prints the retained receipts (generation, key, op, revision) instead of a bare count. The later EX5 policy assertions are reached in runs that previously stopped at the depth check.",
        "evidence": "Unchanged test at a5defe3731: 1 normal run failed with the CI assertion (expected 0, was 3); 8 runs with the test JVM pinned to one CPU next to a busy loop gave 1 CI assertion, 2 JUnit 300 s timeouts (starvation artifact) and 5 passes. A probe sleeping 3 s after publication and before receipt removal: unchanged test fails with the CI assertion; candidate passes. A probe that makes the acknowledgement report success without removing anything: candidate fails after the wait with the three retained receipts (the two enumerated file UPSERTs and the legacy SYNC_ROOT). Candidate without probes: 4/4 normal runs pass.",
        "unresolvedRisks": "The cause of the CI failures is inferred from source plus the local reproduction; the CI receipt contents were not captured. Receipts still present when the 30 s wait ends fail the test, but the bound is a wait budget, not a timing assertion: a strict read blocked on the queue lock while a slow removal commits can return empty after 30 s and pass (the class's 300 s timeout bounds the whole test). EX5 guards that the replay is removed, not how fast. A separate, unverified recovery question (a crash inside this window during native migration) is outside this change and recorded for the migration area."
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
  "session": "01a18032-2638-7a21-8631-def678fd8ebb",
  "verdict": "accept",
  "digest": "sha256:89a733af2fbf928fe1bb0eaebd3b4f7df91098c87966b1ddceac7dee0a16824b"
}
```
