---
schema: test-intent.v1
task: rel-act
author-role: builder
author-session: codex-rel-act
---

# Test-intent entries

The assertions in existing tests are retained. The constructor extension only selects the new installer fixture; ordinary bulk fixtures keep their original source and target.

Round 2 repairs the help fixture's missing `bindBootRootBindings(List.of())`, matching `EngineRoot` before startup volume seeding, and uses the current `v4` marker. Every existing assertion remains. On `/home` the incomplete fixture correctly failed source admission; no volume guard or product protection was changed.

The evidence producer was rerun without a private profile or init script. A temporary copy at `/home/elias/.cache/justsearch-rel-act-round2-20261028/producer/scripts/governance/gates/test-intent/run-evidence.mjs` changes only launch transport (`js-gradle-slot`), before-checkout transport (shared clones), and its repository root. Check selection, JUnit parsing, classification, source digests and evidence writing remain the producer's. Repaired evidence now also names the attestation, empty metadata and encoder-configuration checks. Preservation evidence contains the full parameterized negative test and the existing settings, boot, asset, planner, query-owner and attestation-controller checks. Some classes contain both repaired and preserved methods; the coverage map distinguishes their individual results.

Four isolated candidate executions of existing conformance checks passed: `installer-before-marker`, `installer-before-arm`, `installer-before-pointer`, and `installer-pointer-before-settings`; their proofs, source hashes, settings witnesses and cleanup are in [live round-2 evidence](../evidence/installed-model-activation-live-round2.json). Repeated external VM launches blocked the stable exclusive slot needed for the baseline, remaining cuts, extra restarts, A1 and shutdown probes. Those remain unverified. Earlier shared-ORT-cache attempts are excluded, and resource-overlap departures are recorded. No independent acceptance is claimed.

See [the condition-by-condition coverage map](../evidence/installed-model-activation-coverage.md). A passing unit check does not certify a missing live scenario. No acceptance is authored here.

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
          "label": "agent choice",
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
          "label": "agent choice",
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
          "label": "agent choice",
          "delegationQuote": "well then proceed with researching, validating and derisking the plan. once thats passed you can proceed with implementation",
          "delegationLocation": "task t-20261027-4e4d6b user turn 2026-10-27 (the owner's grant to derisk and implement the release workflow)",
          "delegationScope": "the release-workflow work and the activation repairs its derisking found (settled scenarios A1-A5 of task t-20261028-a03e1e)",
          "choice": "Each negative condition is checked on its own; all-failed and some-succeeded candidates are checked separately."
        },
        {
          "kind": "delegated-choice",
          "label": "agent choice",
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
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/GenerationEmbeddingAttestationTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "A selected model identity or a source-generation stamp cannot stamp an unattached empty candidate.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Installer activation introduces no new stamp waiver and never manufactures or borrows success evidence; existing matching attestations, the corruption-recovery waiver and partial-success/gap acceptance behave as before.",
          "location": "task rel-act user turn 2026-10-28 (repair round 2, repeated original scenarios A2-A3)"
        }
      ],
      "evidence": "gates/test-intent/evidence/installed-model-activation-repaired.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/GenerationEmbeddingAttestationTest.java"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/InstallerEmptyMetadataTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "The installer empty exception omits only the absent stamp; ordinary migration still needs evidence.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The installer-only empty exception requires the same exact candidate to satisfy the complete captured-walk, zero-planned-unit, no-issued-claim, drained-queue, no-projection-source, removal-only-journal and strict-zero-count proof under the final fence; replay uncertainty, incomplete capture, wrong target fingerprint or non-COMPLETE commit prevents promotion; recheck after intervening writes; emptiness is never persisted as permission to stamp.",
          "location": "task rel-act user turn 2026-10-28 (repair round 2, repeated original scenarios A2-A3)"
        }
      ],
      "evidence": "gates/test-intent/evidence/installed-model-activation-repaired.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/InstallerEmptyMetadataTest.java#installerEmptyProofOmitsOnlyTheMissingStamp"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/indexerworker/server/InstallerEncoderConfigurationTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "B proves its complete captured encoder projection and observations through the real settings coordinator; these are owner-routing checks with mocked native execution.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Negative: if the derived setting changes between B's composition and transaction preparation, publication proves B's full physical-session configuration matches or refuses before the pointer moves; unknown or missing role observations never certify it; explicit settings keep precedence.",
          "location": "task rel-act user turn 2026-10-28 (repair round 2, repeated original scenarios A2-A3)"
        }
      ],
      "evidence": "gates/test-intent/evidence/installed-model-activation-repaired.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/indexerworker/server/InstallerEncoderConfigurationTest.java"
      ]
    }
  ]
}
```

## Acceptance records
