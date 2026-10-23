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
      "behaviour": "Preserve the captured serving identity and job rows while the interrupted fixtures remain paused. Round 3 separately requires and checks actual public resumption/completion in Release030ExactFileRecoveryTest; full retirement beyond those cases stays deferred. The opt-in scale probe also rejects parser-failed new work: extraction-call counts alone cannot establish successful indexing. Named JUnit evidence covers the interrupted-fixture preservation and compilation; actual opt-in scale execution, failed attempts and strengthened repeat are recorded in the builder report.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Historical round-1 preservation scope; round 3 additionally requires actual resumption/completion in a separate check.",
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
      "behaviour": "Preserve the five C1 outcomes through real boot, a held PROCESSING claim, v13 backup restore and production SQLITE_CORRUPT triage, actual public resumption/completion of both native interrupted fixtures, and excluded versus included unreadable paths. Round 3 removes parent-sampling admission and the manual IDLE trigger: unavailable exact files keep searchable content and durable debt on ordinary retry-until-available; roots and all active exact work must resolve before the checkpoint clears. The claim-isolation pause is released without changing original parser/I/O waits; asynchronous completion is awaited.",
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
          "delegationScope": "Minimal protection against retiring unread legacy jobs during native migrations; round 3 requires preparation before claims and real fixture cutover completion.",
          "choice": "Retain the durable sidecar; prepare boundaries and selective retry repair in every native migration phase before claims. Directory walks still await IDLE; exact files can proceed without root preparation or inferred parent identity.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Treat each as a claim to test: write the failing test first (on your round-1 code), then fix it, then show it passes.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "The five C1 claims, preserving round-1 behavior and avoiding queue schema changes.",
          "choice": "Keep the C1 database-generation/file-identity binding, PROCESSING/exhausted exact debt, exclusions-before-readability and durable one-minute root cooldown. Round 3 supersedes sample-based exact admission: protect exact queued files from uncertain deletion and use normal retry-until-available. No parent authority or queue schema change.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Adopt this or something better, and write the decision and its reason in the changeset.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Repair the four C2 findings while preserving agreed scenarios and round-1/round-2 behavior, without a queue schema change.",
          "choice": "A legacy exact file outside roots is admitted only when regular, present and readable under current exclusions. Negative observations use ordinary I/O retry-until-available and never delete or mark done; the durable checkpoint remains until active exact work resolves. The shared queue supplies exact-path protection to every serving runtime, including cutover successors. No parent sampling or volume identity is inferred or recorded: a queued update invalidates its only indexed stamp, and an empty sample proves no drive identity. Positive exact-file read authority needs neither inference nor parent authority. Prepare atomic boundaries/repair independently of native migration phase before claims; root reconciliation still waits for IDLE. Only a database carrying the existing 0.3.0 recovery marker or persisted header generation may exempt its unowned pre-boundary terminal rows from native cutover (U12); main-built databases retain the full configured failure budget, and admitted migration failures still count. Apply the same count at the monitor, the final producer fence, and pointer publication. A legacy unscoped UPSERT or originless DELETE superseded by a later witnessed admission for the same exact path/current generation does not re-enqueue and replace that newer revision; final replay still verifies the newer accepted effect and retains all versions until promotion. Preserve accepted ordering and keep later legacy work live. Native tests use dimension-correct deterministic vectors through real backfill/certification because the fixture helper suppresses heavyweight model initialization; they never edit migration state or waive commit verification. Keep the merged unitRevision and preserveRetryWaits enqueue arguments; a preservation test checks both.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/upgrade-compat-round3.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030ChallengerRecoveryTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030ExactFileRecoveryTest.java"
      ],
      "class": "New",
      "behaviour": "C2 findings 1–4: an isolated queued update becomes searchable across restart; a never-indexed missing exact file remains recoverable and indexes after return; the authentic MIGRATING and SWITCHING fixtures complete through public resume without manual state changes and retain the original terminal failure; nonnumeric/overflowed root cooldowns retain old search and cannot starve another root. A partial native Green missing an unavailable exact file must preserve Blue search until that exact file returns, then complete normally. Every repair case fails at its intended assertion on da5d6059d. The enqueue merge preservation case passes before and after.",
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
          "scenario": "U11",
          "quote": "Never delete on uncertainty: an unplugged drive, another volume on the same Windows letter, denied access or unreadable root evidence at the first start leaves that root's indexed rows searchable and undeleted through boot, sync, watcher and queued processing; its recovery waits until the root is confirmed, then runs. Other roots become current.",
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
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U19",
          "quote": "A genuinely locked or unreadable single file still uses the retry ladder and retry-until-available; background sync and watcher re-enqueue still revive retry-exhausted rows.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Adopt this or something better, and write the decision and its reason in the changeset.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Repair the four C2 findings while preserving agreed scenarios and round-1/round-2 behavior, without a queue schema change.",
          "choice": "A legacy exact file outside roots is admitted only when regular, present and readable under current exclusions. Negative observations use ordinary I/O retry-until-available and never delete or mark done; the durable checkpoint remains until active exact work resolves. The shared queue supplies exact-path protection to every serving runtime, including cutover successors. No parent sampling or volume identity is inferred or recorded: a queued update invalidates its only indexed stamp, and an empty sample proves no drive identity. Positive exact-file read authority needs neither inference nor parent authority. Prepare atomic boundaries/repair independently of native migration phase before claims; root reconciliation still waits for IDLE. Only a database carrying the existing 0.3.0 recovery marker or persisted header generation may exempt its unowned pre-boundary terminal rows from native cutover (U12); main-built databases retain the full configured failure budget, and admitted migration failures still count. Apply the same count at the monitor, the final producer fence, and pointer publication. A legacy unscoped UPSERT or originless DELETE superseded by a later witnessed admission for the same exact path/current generation does not re-enqueue and replace that newer revision; final replay still verifies the newer accepted effect and retains all versions until promotion. Preserve accepted ordering and keep later legacy work live. Native tests use dimension-correct deterministic vectors through real backfill/certification because the fixture helper suppresses heavyweight model initialization; they never edit migration state or waive commit verification. Keep the merged unitRevision and preserveRetryWaits enqueue arguments; a preservation test checks both.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/upgrade-compat-round3.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030ExactFileRecoveryTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030CorruptRecoveryValueTest.java"
      ],
      "class": "New",
      "behaviour": "Invalid root status, checkpoint format and malformed escaped values neither stop boot nor drop uncertain debt. An unavailable root retains searchable old content while an independent healthy root becomes current after bounded conservative recovery. Each case is an intended assertion failure on da5d6059d.",
      "sources": [
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
          "scenario": "U14b",
          "quote": "Engine killed during recovery of 0.3.0's queued work or during the upgrade repair: after restart the recovery resumes and ends in the same state as an uninterrupted run (only the transitions U7 and U18 define), nothing is deleted, and no full reindex starts.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Adopt this or something better, and write the decision and its reason in the changeset.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Repair the four C2 findings while preserving agreed scenarios and round-1/round-2 behavior, without a queue schema change.",
          "choice": "An invalid per-root cooldown/status retains that root with a persisted one-minute retry and does not abort later roots. A malformed checkpoint format or invalid Properties escape makes its database binding uncertain: repeat the existing idempotent preparation, reconstruct configured-root debt, and persist one bounded cooldown before any recovery walk. Invalid status never means done. Catch each root failure separately so one inaccessible state/write cannot starve others. This prevents both destructive assumptions and ten-second retry loops; no database schema change.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/upgrade-compat-round3.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030CorruptRecoveryValueTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceCandidateReconciliationTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Added one exact in-order verification of the new read-only legacy-exact-path query before the existing watcher journal assertion. All existing delete effects, journal ordering, counts, inputs, faults and no-more-interactions assertions remain unchanged. The original fixture passes in a direct standard JUnit Platform selection against the Gradle-compiled matched baseline; the adapted fixture passes in the candidate Gradle task. The combined before task's init/CLI filter intersection selected neither service class; that failed attempt is retained.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Keep all round 1 and round 2 behavior.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Preserve existing native cutover limits and the original mutation-order/count checks while adding the upgrade observer.",
          "choice": "Use the existing 0.3.0 database marker/header identity to scope the unowned-terminal exception. Keep the existing installer-budget test unchanged as a preservation check on da5d6059d and the candidate. In the existing delete-order fixture, verify exactly one read-only isRelease030ExactFile query before the journal and retain every existing mutation/count/order/no-more-interactions assertion. Run the original fixture before and the adapted fixture after.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerLiveInstallerMigrationTest.java"
      ],
      "class": "New",
      "behaviour": "Preserve the unchanged installer-budget test: after a live installer changes the limit, the next failed native migration on a main-built database must retain its serving generation and enter FAILED at the configured limit. Its existing settlement/model-owner preservation test also runs unchanged. Both pass before and after.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Keep all round 1 and round 2 behavior.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Preserve existing native cutover limits and the original mutation-order/count checks while adding the upgrade observer.",
          "choice": "Use the existing 0.3.0 database marker/header identity to scope the unowned-terminal exception. Keep the existing installer-budget test unchanged as a preservation check on da5d6059d and the candidate. In the existing delete-order fixture, verify exactly one read-only isRelease030ExactFile query before the journal and retain every existing mutation/count/order/no-more-interactions assertion. Run the original fixture before and the adapted fixture after.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/upgrade-compat-round3.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerLiveInstallerMigrationTest.java"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030AdmissionDebtTest.java"
      ],
      "class": "New",
      "behaviour": "C3: later admissions cannot borrow original exact-path authority, including same-path re-enqueue and replacement modern databases accompanied by an old sidecar. Original missing never-indexed exhausted debt does not block real native MIGRATING/SWITCHING cutover; an actual deadline failure can recover on restart, including when the file finishes before restart. Existing explicit content is explicitly queued in these native archives (their original rows are DONE). Indexed exhausted content stays protected. Invalid decoded root keys are durably isolated while other roots recover. Ten cases: eight repair assertions fail on eae494706; indexed-content and unavailable-admission-read preservation checks pass before and after.",
      "sources": [
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
          "scenario": "U11",
          "quote": "Never delete on uncertainty: an unplugged drive, another volume on the same Windows letter, denied access or unreadable root evidence at the first start leaves that root's indexed rows searchable and undeleted through boot, sync, watcher and queued processing; its recovery waits until the root is confirmed, then runs. Other roots become current.",
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
          "scenario": "U14b",
          "quote": "Engine killed during recovery of 0.3.0's queued work or during the upgrade repair: after restart the recovery resumes and ends in the same state as an uninterrupted run (only the transitions U7 and U18 define), nothing is deleted, and no full reindex starts.",
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
          "delegationQuote": "The exception must apply only to rows admitted by 0.3.0 (identify them durably at preparation time, for example by their unit revision, generation or a marker set in the same transaction), never to anything enqueued later.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Round4: test the three C3 claims on eae494706; preserve rounds1–3 and U19; do not change queue schema. Resume with remaining work autonomously within the delegated scope.",
          "choice": "Use the existing v17 unit_revision backfill to prefix revisions born from an actual v13 database, with its saved application_id generation. This tags the original cohort in the existing single atomic schema-migration transaction and existing revision-write pass: no schema, DDL or version change and no extra full-queue revision rewrite. Preparation selectively repairs only that original generation; a different modern database cannot acquire provenance from an old sidecar, a null boundary or a nonzero header. A normal enqueue mints a fresh revision, immediately revoking even cached exact-path authority. Validate the current saved revision; an unreadable admission SELECT throws into the per-job retry handler and never authorizes absence. Keep both unitRevision and preserveRetryWaits in the merged enqueue path. Native replay of an old unscoped buffered UPSERT preserves an already durable unfinished original admission or original exhausted debt completed on retained Green, rather than renewing away its retry state or provenance; new strong/recorded admissions retain normal behavior. Only original RETRY_EXHAUSTED exact files proven absent from Blue by a strict physical DOC_ID count become cutover-independent, durably in their revision. Read failure or indexed content retains the existing protection. Keep the real row, state, retries and checkpoint; subtract only this original debt in native monitor/drain, replay pending barrier and final publication fences, while replay still waits for every PROCESSING row. Unowned terminal failure exemptions are per original saved revision, never database-wide, so ordinary/recorded failure budgets remain intact. A FAILED native migration with its retained candidate, current database-bound checkpoint and original debt retries the same candidate on boot with existing certification/budget/publication guards. DONE debt remains identifiable when a file returned before restart. Deadline tests use the real CutoverContext duration0 and actual transitions, never manual saved-state or timestamp edits. Invalid decoded root keys are moved once to reversible UTF16-hex quarantined-root keys retaining their values; atomic save precedes one count-only warning. Preserve quarantine across preparation and never delete its checkpoint; healthy roots still finish independently. No inferred volume identity or parent authority. Keep the original private single-step migration entry point for the unchanged supported-version fixture checks; it delegates with no v13 witness, while the atomic real-v13 ladder carries its actual generation. Retain the original single Statement retry repair and all unchanged crash interception points, scoped only by a constant and the integer cohort generation. No scale regeneration because the recovery walk is unchanged. The integration task budget is60min rather than30min with unchanged assertions and method timeouts, a1GiB spawned-Engine heap cap, fixed JVM temporary directories and sequential slotted Gradle calls.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/upgrade-compat-round4.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030AdmissionDebtTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030ReplayRetentionTest.java"
      ],
      "class": "New",
      "behaviour": "C4: recorded volume evidence defeats legacy exact authority after restart, both for a later watched root and an explicit-folder record. A completed original admission with a raw UPSERT stays settled through actual MIGRATING/SWITCHING promotion, including restart between DONE and replay. Routine retention preserves completed independent debt through real deadline FAILED recovery, then resumes once recovery completes. Generic unscoped receipt checks distinguish identical and lexically aliased paths, different hashes, document IDs, paths and collections, missing and unreadable Green evidence, and applied versus unapplied terminal policy effects. Twenty-six cases include preservation negatives, inherited collection labels for written and unchanged receipts, an unchanged freshness receipt, mismatched/missing/stale freshness evidence, and a real native restart after an index commit whose queue completion failed. Native retention variants keep ordinary completed original admissions and their atomic UNCHANGED ledger evidence until replay settles, prune unrelated audit rows, and release both forms of evidence after recovery completes. Intended before failures and after outcomes are recorded by the evidence producer.",
      "sources": [
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
          "scenario": "U11",
          "quote": "Never delete on uncertainty: an unplugged drive, another volume on the same Windows letter, denied access or unreadable root evidence at the first start leaves that root's indexed rows searchable and undeleted through boot, sync, watcher and queued processing; its recovery waits until the root is confirmed, then runs. Other roots become current.",
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
          "scenario": "U14b",
          "quote": "Engine killed during recovery of 0.3.0's queued work or during the upgrade repair: after restart the recovery resumes and ends in the same state as an uninterrupted run (only the transitions U7 and U18 define), nothing is deleted, and no full reindex starts.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Keep it general: an already-applied receipt is settled, not re-admitted.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Fix the three C4 claims on b3d998095, preserve prior rounds and U19, keep native fallback until the separate obsolete-migration PR. Resume with remaining work autonomously within the delegated scope.",
          "choice": "Known recorded root or explicit-file-folder evidence and declared root coverage outrank the original exact-path exception; missing or unreadable ledger evidence never grants an override. Retain the current admission read failure behavior. For unscoped file replay, reuse the existing committed-write or atomic UNCHANGED receipt and prove matching Green document identity, path, collection and source hash or freshness using the canonical ingestion key before treating the effect as already applied, irrespective of whether its revision is legacy or modern. An existing terminal policy receipt settles only with strict zero indexed ID/chunks, never a failed or unread admission. Missing or different target effects still need normal admission; unreadable projection evidence retains the journal without renewing the row. UNCHANGED is also an applied effect: compare the existing atomic outcome/ledger freshness receipt (path hash, matching current outcome class/reason/retry/observed time, latest matching ledger row, saved source mtime and collection) to Green, plus document identity/path/collection. Reject missing or mismatched freshness evidence and never borrow an older row on a same-millisecond re-admission. No new writes in the unchanged fast path and no extra extraction; only replay reads this proof. A raw 0.3.0 path has no collection label: preserve the completed admission label proved against Green rather than invent a new one; a JSON request with an explicit collection must still match. Read and preserve an unfinished original admission before reading Green so concurrent completion cannot become a fresh admission. Keep native buffered ordering, strong-witness verification, certification and final publication fences. Preserve every prepared release030 exact-path admission from ordinary job retention while the existing recovery sidecar cannot be confirmed absent: ordinary DONE receipts also settle native replay, alongside the exhausted-debt receipts authorizing FAILED recovery. Preserve ledger rows at current completed original exact-file outcome times, because legacy stat receipts have no ledger unit revision; coincident audit rows may be kept temporarily, granting no ingestion authority. The uncorrelated time set avoids a per-ledger-row scan of jobs. Prune unrelated times normally, and release job and ledger evidence once the checkpoint clears. Both added retention counterexamples failed on the first candidate against real native MIGRATING before this extension; final checks also run on b3d998095. Two additional native cases exercise both cleanup operations, prove promotion/search, and verify normal retention resumes. Use a private one-fork 1GB heap for launcher/UI discovery after their initial 384MB OOM; change no shared or product build setting. Store no new state and change no schema. Tests hold only the real native monitor, never write migration states; cover both native archives, replay after DONE with and without restart, the real zero-duration deadline, normal retention and its release. No recovery-walk changes or scale regeneration. Use fixed JVM folders and retained caches, sequential slotted Gradle calls; packaged HTTP first boot/restart runs last.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/upgrade-compat-round5.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030ReplayRetentionTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RootRecoveryIsolationTest.java"
      ],
      "class": "New",
      "behaviour": "C5: four parent sync/upgrade recovery cases retain empty-mount and replacement-volume nested roots' indexed content across restart, preserve their child admissions unchanged, still prune the parent's conclusive deletion, and keep a denied nested folder from blocking the parent's recovery; a root removed after spool discovery admits no recreated jobs; a modified DONE source loses its eight-day-old failure history, survives one transient parser timeout, retries into search and remains searchable after restart. Tests use the authentic small 0.3.0 fixture and the real Worker boot.",
      "sources": [
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
          "scenario": "U14b",
          "quote": "Engine killed during recovery of 0.3.0's queued work or during the upgrade repair: after restart the recovery resumes and ends in the same state as an uninterrupted run (only the transitions U7 and U18 define), nothing is deleted, and no full reindex starts.",
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
          "kind": "delegated-choice",
          "delegationQuote": "Parent-root recovery must never prune data under another registered root (for example an unplugged nested root whose mount folder is empty). Each root's data is judged only by that root's own volume evidence.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Fix C5 findings 1–3 on 7245bedaf; keep all earlier behavior. Finding 4 is main issue #913 and must not be changed; finding 5 is accepted as is. Resume with remaining work autonomously within the delegated scope.",
          "choice": "Use one nested-root ownership check in the shared sync prune guard and directory walk: retain the child's indexed rows, and skip its subtree instead of admitting its files with the parent boundary. Apply the same ownership rule to failed visits: a denied child mount keeps only the child's debt, without repeatedly walking the healthy parent. Two recovery counterexamples failed at that assertion on the initial candidate before adding the failed-visit guard; both ordinary sync preservation cases passed. This applies to empty mount folders and present replacement-volume bytes, irrespective of whether the child is currently available. Only the child's own recovery may judge or admit its sources using its volume evidence. Recheck current root membership and the walk's removal generation under the watcher registry monitor used by unwatch, holding that monitor only for each bounded admission batch, including registry handover. Capture the fence before the walk; keep the existing six-argument sync helper and let SyncDirectoryOps invoke its own queue inside that fence, so original cooldown spies and real-process admission kill hooks continue to observe the actual operation without test changes. The first full attempt exposed both displaced hooks and was cancelled before Engine suites; preserve its diagnostics and rerun the corrected candidate. Re-admitting an eligible DONE row clears the completed admission's failure history; leave unfinished admissions, parser/I/O waits and finite-walk receipts intact. No schema change or new recovery checkpoint. Exercise the removal race through normal unwatch and delete routes, and a real subsequent parser retry through search and restart. Because the shared prune guard and bounded admission affect recovery cost, generate the scale fixture once, reuse it for a bounded walk/extraction measurement, then delete it. Keep fixed before/after JVM folders, sequential slotted Gradle calls and retained caches. Run packaged HTTP first boot and restart once at the end. Scale evidence is a 40.23-second idempotent root walk on the single generated corpus, with zero unchanged-file extractions and no missing-boundary retirements; it is not a full queue drain. The inherited probe required global checkpoint deletion in the same tick, although readiness is sampled before the walk. Preserve that failed assumption as evidence, reuse the same data without regeneration, invoke the normal completion tick and verify checkpoint release plus all 700042 jobs retained. The remaining 699641 queued jobs make full drain unverified. Do not edit migration states or recovery properties to force completion. Final packaged HTTP first boot and restart both retain the original writable IDLE generation and find new files; preserve retry waits and close both owned Engines.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/upgrade-compat-round6.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RootRecoveryIsolationTest.java"
      ],
      "repaired": true
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1677d-7b3b-7ee2-9d81-841a28e12a29",
  "verdict": "reject",
  "digest": "sha256:35fb974d175899ec6fc9b5bd544c7226fa68dfd26b324a01ee8e6b2289c5b970",
  "note": "Reject: a stale recovery checkpoint grants authority to a modern unowned replacement-queue job and replaces old searchable content over packaged HTTP; the required handoff preservation check also regresses."
}
```
