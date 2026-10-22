---
schema: test-intent.v1
task: t-20261020-c2e24a
author-role: builder
author-session: codex-upgrade-compat-20261021
---

# Test-intent entries

The scenarios were adopted by the user on 2026-10-20 with “i agree, proceed accordingly”.
The old test explicitly pins the temporary bad behaviour of #739 and directs this fix to invert it.
The orchestrator's choices are delegated choices, not owner words.
The evidence runner uses a private copy solely to invoke js-gradle-slot, place JVM temporaries under TMPDIR, and create the before state as a shared clone; assertion/result parsing and digest generation are unchanged. It also batches the selected Gradle tasks once per tree and includes the unchanged exclusion-preservation class (adapted mock default dispatch on the candidate) and PMD tasks. The source runner is unchanged. One combined evidence run executes repaired and preservation checks on both states; its nonzero aggregate verdict for preservation checks passing before is expected and reported, while each entry validates its own check outcomes.
New isolated scenario tests retain the gate's independent verification requirement.

```json
{
  "entries": [
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030UpgradeCharacterizationTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030CompatibilityTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030ExtractionRecoveryTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030CrashRecoveryTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030UnavailableRootTest.java"
      ],
      "class": "New",
      "behaviour": "Replace the explicitly temporary #739 bad-behaviour pins with agreed U1/U2 writable/searchable in-place upgrade and U7/U8/U10/U12/U18 durable queued-work recovery, including search and a second restart. Keep future parser and I/O waits and failed rows unchanged; outside-root authority is exact-path only. Direct pre-open, writable and deferred activation checks, first commit/reopen identity, inputs-only compatibility, and real extraction sandbox counts exercise U2/U4/U9. Real process kills at uncommitted repair, committed repair and partial root admission must converge with uninterrupted boot. Across a real fixture upgrade, unavailable/denied roots retain search through sync and watcher calls while another root progresses; a denied file keeps its real one-minute retry and search until readable. Automatic non-forced system initial scans and internal sync discovery preserve non-recorded terminal failures and scheduled parser/I/O waits across restart and a deferred root's return; a copied authentic failed row under a root and its original outside roots remain terminal.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U1",
          "quote": "A 0.3.0 data folder upgraded in place (desktop on Windows and Linux, headless): no rebuild or migration starts, the old index opens writable and stays searchable, and a file added after the upgrade is searchable within one indexing cycle.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U2",
          "quote": "Every index-open path (pre-open inspection, writable open, deferred writer activation, restart) makes the same compatibility decision on the 0.3.0 index; after the first new commit and a reopen, the stored identity does not hide a real difference, and search and later writes work.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U7",
          "quote": "Jobs 0.3.0 left PENDING or PROCESSING (including a root with more than 200k indexed files and about 700k queued jobs) are not marked done unread: every never-indexed file is indexed or queued with its root after the upgrade, and an indexed file with a queued update shows the new content in search.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U8",
          "quote": "A queued explicitly added file outside any watched root is kept and indexed, without granting broader ingestion authority.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U10",
          "quote": "Files added, modified and conclusively deleted across the upgrade show correctly in search after the upgrade and after another restart.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U12",
          "quote": "0.3.0's failed and retry-exhausted rows, including one whose path is under no root, do not block the upgrade, and no cutover depends on them.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U18",
          "quote": "Upgrade repair: 0.3.0 rows waiting on long write-failure retries and retry-exhausted rows become claimable on the first start and get indexed; parser-timeout and I/O-failure rows keep their retry times. A crash during the repair leaves it idempotent.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Implementation choices for the agreed compatibility and v13 queue recovery scenarios; no queue schema change; full native interrupted-migration retirement remains deferred.",
          "choice": "Grandfather exact paths from the v13 queue under current exclusions, never parents; attach root boundaries and selectively repair write/exhausted retries before claims; retain a per-root checkpoint in data/upgrade-030-recovery.properties until durable reconciliation is complete. Automatic non-forced system initial scans and durable background internal sync discovery outside recorded/migration paths retain terminal failures and future parser/I/O waits on non-recorded jobs, including a root's return after first-boot unavailability. Explicit/forced scans, interactive sync, direct watcher upserts and sync replay keep normal admission.",
          "label": "agent choice"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U3",
          "quote": "A real shape change (vector dimension, analyzer, chunking, model, an added field with a role or not marked compatible) still triggers the configured mismatch response; scoring- and annotation-only changes stay benign.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U4",
          "quote": "A main-built index opens without a difference; an empty index, an inputs-only commit (unresolved model digest) and unreadable metadata keep their existing distinct handling: uncertainty neither authorizes a destructive rebuild nor suppresses a known incompatibility.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U9",
          "quote": "Recovering 0.3.0's queued work does not re-extract unchanged files: extraction calls for unchanged files stay near zero while new and modified files are processed.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U14b",
          "quote": "Engine killed during recovery of 0.3.0's queued work or during the upgrade repair: after restart the recovery resumes and ends in the same state as an uninterrupted run (only the transitions U7 and U18 define), nothing is deleted, and no full reindex starts.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U11",
          "quote": "Never delete on uncertainty: an unplugged drive, another volume on the same Windows letter, denied access or unreadable root evidence at the first start leaves that root's indexed rows searchable and undeleted through boot, sync, watcher and queued processing; its recovery waits until the root is confirmed, then runs. Other roots become current.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U19",
          "quote": "A genuinely locked or unreadable single file still uses the retry ladder and retry-until-available; background sync and watcher re-enqueue still revive retry-exhausted rows.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/upgrade-compat.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030UpgradeCharacterizationTest.java#issue739CompatibleReleaseIndexStaysWritableAndSearchableAcrossRestart",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030UpgradeCharacterizationTest.java#issue739LegacyJobsAreReadAndSelectiveWriteRepairSurvivesRestart",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030UpgradeCharacterizationTest.java#grandfatheredExplicitFileStillHonorsCurrentExclusions",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030CompatibilityTest.java#everyOpenPathAcceptsTheReleaseAndNewCommitStillDetectsRealChanges",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030CompatibilityTest.java#inputsOnlyAllowsOnlyMarkedAdditions",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030ExtractionRecoveryTest.java#recoveryWalkExtractsChangedFilesAndSkipsUnchangedReleaseFiles",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030CrashRecoveryTest.java#killsDuringRepairAndRecoveryConvergeLikeUninterruptedBoot",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030UnavailableRootTest.java#deniedSingleFileKeepsItsRetryAndOldSearchUntilItCanBeRead",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030UnavailableRootTest.java#deniedRootAtFirstBootKeepsItsRowsWhileOtherRootRecovers",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030UnavailableRootTest.java#unavailableThenReplacementRootKeepsOldSearchAndResumesOnlyOnConfirmedReturn"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030CompatibilityTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030CrashRecoveryTest.java"
      ],
      "class": "New",
      "behaviour": "Preserve rejection of each real physical shape change and keep empty/unreadable metadata distinct. This method is a preservation check, run on both baseline and candidate. Preserve every v13 job field when the real boot is killed at three queue-migration steps.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U3",
          "quote": "A real shape change (vector dimension, analyzer, chunking, model, an added field with a role or not marked compatible) still triggers the configured mismatch response; scoring- and annotation-only changes stay benign.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U4",
          "quote": "A main-built index opens without a difference; an empty index, an inputs-only commit (unresolved model digest) and unreadable metadata keep their existing distinct handling: uncertainty neither authorizes a destructive rebuild nor suppresses a known incompatibility.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U14",
          "quote": "Engine killed during the jobs.db migration: after restart the migration completes and every job's saved fields equal the pre-migration values; nothing is deleted.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/upgrade-compat.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030CompatibilityTest.java#knownShapeChangesRemainMismatchesAndUncertaintyStaysDistinct",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030CrashRecoveryTest.java#killedQueueMigrationPreservesEveryReleasedSavedJobField"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerUserExclusionsAdmissionTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "The JobQueue mock now executes the new maintenance-scan default forwarding method, which delegates to the existing collecting enqueue stub. Inputs, assertions, fault conditions, scan modes and exclusion outcomes are unchanged. INITIAL-only maintenance routing keeps RESCAN and FORCE_REINDEX on normal admission. The original class is run before and the adapted class after as an additional preservation task in the combined evidence commands.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Preserve existing exclusion/admission checks while wiring the automatic-startup retry-preserving queue signature required by U18.",
          "choice": "Forward the new default interface method through the existing queue double; change no expectation or exclusion membership oracle.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030UpgradeProbeTest.java"
      ],
      "class": "New",
      "behaviour": "Preserve the interrupted fixtures without repairing them. The opt-in scale probe also rejects parser-failed new work: extraction-call counts alone cannot establish successful indexing. Named JUnit evidence covers the interrupted-fixture preservation and compilation; actual opt-in scale execution, failed attempts and strengthened repeat are recorded in the builder report.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Exercise and report interrupted migration fixtures without repairing them.",
          "choice": "Boot the authentic MIGRATING and SWITCHING fixtures and preserve their serving index identity and legacy job rows; compare reported disposition with baseline.",
          "label": "agent choice"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U7",
          "quote": "Jobs 0.3.0 left PENDING or PROCESSING (including a root with more than 200k indexed files and about 700k queued jobs) are not marked done unread: every never-indexed file is indexed or queued with its root after the upgrade, and an indexed file with a queued update shows the new content in search.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U9",
          "quote": "Recovering 0.3.0's queued work does not re-extract unchanged files: extraction calls for unchanged files stay near zero while new and modified files are processed.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/upgrade-compat-probe.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030UpgradeProbeTest.java#interruptedMigrationFixturesKeepTheirServingIndexAndJobs"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030ChallengerRecoveryTest.java"
      ],
      "class": "New",
      "behaviour": "Test the five C1 findings through real boot, a held issued claim, v13 backup restore and production SQLITE_CORRUPT triage, native interrupted fixtures before/after an IDLE trigger, and excluded versus included unreadable paths. Ten parameterized cases must fail at their intended assertions on round-1 code and pass after. Excluded indexed rows are retained without blocking completion; included unreadable rows retain searchable content and durable debt with bounded full-walk retries through restart.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U7",
          "quote": "Jobs 0.3.0 left PENDING or PROCESSING (including a root with more than 200k indexed files and about 700k queued jobs) are not marked done unread: every never-indexed file is indexed or queued with its root after the upgrade, and an indexed file with a queued update shows the new content in search.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U8",
          "quote": "A queued explicitly added file outside any watched root is kept and indexed, without granting broader ingestion authority.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U18",
          "quote": "Upgrade repair: 0.3.0 rows waiting on long write-failure retries and retry-exhausted rows become claimable on the first start and get indexed; parser-timeout and I/O-failure rows keep their retry times. A crash during the repair leaves it idempotent.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U14b",
          "quote": "Engine killed during recovery of 0.3.0's queued work or during the upgrade repair: after restart the recovery resumes and ends in the same state as an uninterrupted run (only the transitions U7 and U18 define), nothing is deleted, and no full reindex starts.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U11",
          "quote": "Never delete on uncertainty: an unplugged drive, another volume on the same Windows letter, denied access or unreadable root evidence at the first start leaves that root's indexed rows searchable and undeleted through boot, sync, watcher and queued processing; its recovery waits until the root is confirmed, then runs. Other roots become current.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U19",
          "quote": "A genuinely locked or unreadable single file still uses the retry ladder and retry-until-available; background sync and watcher re-enqueue still revive retry-exhausted rows.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "this guard belongs in this PR, because it is a data-loss path that the upgrade opens.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Minimal hold on unprepared legacy claims and later preparation when native migration reaches IDLE; full native migration retirement stays deferred.",
          "choice": "Retain the durable sidecar and scheduled recovery through native MIGRATING/SWITCHING; hold null-boundary legacy claims until atomic preparation and reset the claim cursor afterward.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Treat each as a claim to test: write the failing test first (on your round-1 code), then fix it, then show it passes.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "The five C1 claims, preserving round-1 behavior and avoiding queue schema changes.",
          "choice": "Seed exact-file folders only on indexed disk evidence, and hold refused exact paths before claim even when the platform exposes no volume ID; include PROCESSING and retry-exhausted exact paths in admission debt; bind preparation to a transactional SQLite application_id generation plus database file identity; exclude paths before readability/prune uncertainty, and persist a one-minute cooldown for incomplete included-root walks. No parent ingestion authority is added.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/upgrade-compat-round2.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030ChallengerRecoveryTest.java"
      ],
      "repaired": true
    }
  ]
}
```

## Acceptance records
