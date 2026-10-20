---
schema: test-intent.v1
task: witness-reserve-flake
author-role: builder
author-session: witness-reserve-flake-builder-20261020
---

# Deterministic Windows witness reserve ordering

```json
{
  "entries": [
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/containment/WindowsJobWitnessReserveTest.java"
      ],
      "class": "Incidental",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "Preserve the Windows witness reserve oracle while replacing sleep-based ordering with a latched in-flight append released at the existing recording gate fence; keep the real Windows job object and Windows-only selection.",
          "label": "agent choice"
        }
      ],
      "assessment": {
        "purpose": "Preserve the existing contract: an append in flight at the drain deadline finishes within the fence reserve, and queued appends never start afterwards. Replace the 40 ms producer and 100 ms append sleeps with latch ordering.",
        "callersAndStateOwners": "Only WindowsJobWitnessReserveTest changes. WindowsJobWitness production code, its finish callers and persisted ledger ownership are unchanged. Reflection installs a ReentrantLock subclass on this test-owned witness, retaining the real lock behavior and real Windows job object.",
        "indirectBehaviours": "The first append enters before finish, two events are queued behind it, and a 150 ms finish allowance makes the drain deadline immediate. The existing recordingGate.tryLock seam checks for a positive reserve and releases storage before delegating to the real timed lock. The writer must become idle with an empty queue before asserting exactly one append. Cleanup always releases storage. Windows OS guard and windows tag remain.",
        "evidence": "Compile, Spotless and all three module PMD tasks remain unverified: the command could not acquire the shared Gradle lock and was stopped while still queued; tmp/gradle-checks.log records the command. Dependency preparation with --no-dist completed. tmp/repeat.patch selects 200 hosted Windows repetitions. tmp/pending-after-deadline.patch removes the admission fence and queue clear; tmp/no-fence-reserve.patch gives the fence only the expired drain budget. Native execution and both mutation kills require hosted Windows CI and are not claimed from Linux.",
        "unresolvedRisks": "Local compile, Spotless and PMD remain blocked by the shared Gradle lock; Windows execution and mutation kills remain for the orchestrator. The real 150 ms fence remains a bounded timeout, but no producer sleep or release sleep must land in a deadline window. The test depends on the existing private recordingGate, writing and pending field names."
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
  "session": "verifier-witness-reserve-20261020",
  "verdict": "accept",
  "digest": "sha256:7aa14fc8b5c0069af860af3c9881f3c3ba7df34ae4c4df3ae657af1a21d13b37",
  "note": "Same contract with a stricter oracle and latch ordering; hosted Windows logs show 200/200 idle and loaded passes, both mutations killed at the right assertions, and Linux compile/Spotless/PMD pass."
}
```
