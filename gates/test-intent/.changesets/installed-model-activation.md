---
schema: test-intent.v1
task: rel-act
author-role: builder
author-session: codex-rel-act
---

# Test-intent entries

The assertions in existing tests are retained. The constructor extension only selects the new installer fixture; ordinary bulk fixtures keep their original source and target.

The evidence producer ran in an isolated shared candidate clone. Its transport was adapted there to invoke `js-gradle-slot ./gradlew` and create/remove shared before clones rather than register worktrees in the source repository. Assertion selection, result parsing and evidence generation were unchanged. The adaptation is retained in the temporary evidence clone for inspection. No acceptance is authored here. The preservation evidence selects the complete test class because Gradle writes parameterized cases as individual numbered display names; it retains evidence for every negative and every original assertion.

```json
{
  "entries": [
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerLiveInstallerMigrationTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Installer generation enumeration retains bundled help, including its internal collection, despite a current startup help marker; existing migration policy/recovery assertions remain unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "well then proceed with researching, validating and derisking the plan. once thats passed you can proceed with implementation",
          "delegationLocation": "task t-20261027-4e4d6b user turn 2026-10-27 (the owner's grant to derisk and implement the release workflow)",
          "delegationScope": "the release-workflow work and the activation repairs its derisking found (settled scenarios A1-A5 of task t-20261028-a03e1e)",
          "choice": "Help documents stay searchable after activation and restart (real help embeddings may earn the first stamp); only the genuinely empty candidate stays unstamped."
        }
      ],
      "evidence": "gates/test-intent/evidence/installed-model-activation-repaired.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerLiveInstallerMigrationTest.java#installerReplaysBundledHelpDespiteTheStartupMarker"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Only a strictly proven installer-empty candidate can finalize structurally without earning or writing a stamp; the proof is repeated after an intervening write. The existing ordinary empty-failure tests and their inputs are preserved through the default fixture constructor.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "well then proceed with researching, validating and derisking the plan. once thats passed you can proceed with implementation",
          "delegationLocation": "task t-20261027-4e4d6b user turn 2026-10-27 (the owner's grant to derisk and implement the release workflow)",
          "delegationScope": "the release-workflow work and the activation repairs its derisking found (settled scenarios A1-A5 of task t-20261028-a03e1e)",
          "choice": "The installer-only empty exception requires the same exact candidate to satisfy the complete captured-walk, zero-planned-unit, no-issued-claim, drained-queue, no-projection-source, removal-only-journal and strict-zero-count proof under the final fence; replay uncertainty, incomplete capture, wrong target fingerprint or non-COMPLETE commit prevents promotion; recheck after intervening writes; emptiness is never persisted as permission to stamp."
        }
      ],
      "evidence": "gates/test-intent/evidence/installed-model-activation-repaired.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#installerEmptyCandidateCanFinalizeWithoutEarningAStamp",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#installerEmptyProofIsNotReusedAfterAnInterveningWrite"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java"
      ],
      "class": "New",
      "behaviour": "Independently withhold each captured-walk, claim, queue, enumeration, strict-count, journal and replay prerequisite; the installer does not take the empty exception when any single prerequisite is missing.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "well then proceed with researching, validating and derisking the plan. once thats passed you can proceed with implementation",
          "delegationLocation": "task t-20261027-4e4d6b user turn 2026-10-27 (the owner's grant to derisk and implement the release workflow)",
          "delegationScope": "the release-workflow work and the activation repairs its derisking found (settled scenarios A1-A5 of task t-20261028-a03e1e)",
          "choice": "Each negative condition is checked on its own; all-failed and some-succeeded candidates are checked separately."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "well then proceed with researching, validating and derisking the plan. once thats passed you can proceed with implementation",
          "delegationLocation": "task t-20261027-4e4d6b user turn 2026-10-27 (the owner's grant to derisk and implement the release workflow)",
          "delegationScope": "the release-workflow work and the activation repairs its derisking found (settled scenarios A1-A5 of task t-20261028-a03e1e)",
          "choice": "The installer-only empty exception requires the same exact candidate to satisfy the complete captured-walk, zero-planned-unit, no-issued-claim, drained-queue, no-projection-source, removal-only-journal and strict-zero-count proof under the final fence; replay uncertainty, incomplete capture, wrong target fingerprint or non-COMPLETE commit prevents promotion; recheck after intervening writes; emptiness is never persisted as permission to stamp."
        }
      ],
      "evidence": "gates/test-intent/evidence/installed-model-activation-preservation.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java"
      ]
    }
  ]
}
```

## Acceptance records
