---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: codex-forget-round9
---

# Forget through release-0.3.0 retirement

Agent-adopted D-T32 checks under the round 9 builder assignment. Execution evidence is deliberately left for the orchestrator: the builder may not run the producer’s Git-index/init-script operations. No acceptance record is authored. Raw before/after results and the exact evidence regeneration plan are in tmp/verify/forget-round9-report.md and tmp/verify/forget-round9/evidence-plan.json.

```json
{
  "entries": [
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/Release030ForgetSourceObservationTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/Release030ForgetRootReplayTest.java"
      ],
      "class": "New",
      "behaviour": "D-T32: retirement observes the live owner\u2019s exact exclusions and supplied-ancestor globs before reading, hashing, correcting or readmitting. Excluded serving absence settles without fresh work; excluded indexed parents/chunks, including absent disk sources and orphan chunks, remain pending with no deletion. Policy unreadable or malformed remains pending; indexed exclusion logs once per attempt. Covers differing-path source observation and both ROOT replay and enumeration. Fourteen actual forbidden-behaviour invocations fail before, all pass after. The same no-hash Mockito verification is wrapped by JUnit only so the official XML classifier recognizes its assertion type. These are component checks; installed Engine/restart and real-volume execution remain separate.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "Finish the retirement exclusion guard, preserve main\u2019s correction, ordering and budgets, and declare the new checks; no omission proof or deletion path.",
          "choice": "D-T32 is agent-adopted in the round 8/9 builder briefs, within the recorded standing owner delegation. Agent-adopted component fixtures use authentic release-0.3.0 preparation, real SQLite creator/collection ownership and Lucene serving state; SHA calls remain real and are only observed. Final test bytes run before and after the guard, with ordinary preservation also checked on main.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-forget-retirement-repairs.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/Release030ForgetSourceObservationTest.java#retirementSourceObservationNeverHashesAForgottenFile",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/Release030ForgetSourceObservationTest.java#excludedAbsentPathIsReconciledWithoutReadmission",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/Release030ForgetSourceObservationTest.java#excludedIndexedPathRemainsPendingWithoutReadOrDeletion",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/Release030ForgetSourceObservationTest.java#unreadablePolicyKeepsIndexedContentAndAdmissionPending",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/Release030ForgetSourceObservationTest.java#malformedPolicyCannotBecomePermissionToRead",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/Release030ForgetSourceObservationTest.java#suppliedAncestorGlobAlsoSettlesExcludedAbsenceWithoutReading",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/Release030ForgetRootReplayTest.java#excludedAbsentRootSettlesWithoutReadmission",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/Release030ForgetRootReplayTest.java#excludedIndexedRootStaysPendingWithoutReadOrDeletion",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/Release030ForgetRootReplayTest.java#missingExcludedIndexedRootStaysPendingWithoutDeletion",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/Release030ForgetRootReplayTest.java#excludedOrphanChunksStillKeepRootPending"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/Release030ForgetSourceObservationTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/Release030ForgetRootReplayTest.java"
      ],
      "class": "New",
      "behaviour": "Ordinary matching source reconciliation still reads and settles with unchanged Q/journal/content. ROOT enumeration and replay still read changed bytes and install the actual source correction with named collection and ROOT ownership, including forced replay with tiny discovery path limits. Three invocations pass before and after and on main. The fixture adds the real stored source_sha256 catalog field instead of changing the preservation assertion or mocking the outcome. These are component preservation checks, not native-volume acceptance.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "Finish the retirement exclusion guard, preserve main\u2019s correction, ordering and budgets, and declare the new checks; no omission proof or deletion path.",
          "choice": "D-T32 is agent-adopted in the round 8/9 builder briefs, within the recorded standing owner delegation. Agent-adopted component fixtures use authentic release-0.3.0 preparation, real SQLite creator/collection ownership and Lucene serving state; SHA calls remain real and are only observed. Final test bytes run before and after the guard, with ordinary preservation also checked on main.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-forget-retirement-preservation.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/Release030ForgetSourceObservationTest.java#ordinaryNonExcludedPathStillReadsAndReconciles",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/Release030ForgetRootReplayTest.java#ordinaryRootStillReadsAndCorrectsWithoutANewPathBudget"
      ],
      "repaired": false
    }
  ]
}
```
