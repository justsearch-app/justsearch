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
The Round 7 review follow-up restores real lookup by default and uses explicit, per-test volume injection. Its producer reruns changed behavioral checks on the warm cached baseline, and carries forward earlier executed before receipts only after verifying byte equivalence of the original check source apart from the injection annotation and raw child initialization; every reused receipt retains its original baseline and evidence digest. All selected candidate checks, compile and PMD run in combined two-mode Gradle calls. The initial PMD violations are retained as failures; after removing redundant annotation qualifiers, a final compile/PMD plus fresh two-mode admission run corrects them and re-executes the cached baseline. The early configuration failure and cancelled queued attempt are retained and are not passes. The final readmission producer runs both-mode compile/PMD/tests and verifies fail-before through direct JUnit against hash-frozen compiled pre-fix queue classes; its execution context records that distinction, without rebuilding or claiming a committed baseline.

The Round 8 producer batches the requested candidate compile/PMD and both UUID profiles in one slotted Gradle call. Its fresh before receipts come from test-first execution on unchanged committed bee392629 product in this checkout, not a rebuilt before checkout. Only the added C6 method runs before; all unchanged C5 cases run after. The signature-only cooldown observer retains its earlier before receipt and baseline after verifying byte equivalence apart from the observed argument count. No expectation or assertion is changed in that observer.

Round 9 records fresh X4/X5 before checks on 1e5742df9 (the subsequent committed change is the verifier record only), plus two-profile preservation. Historical C6 before evidence retains its original baseline; no fresh C6 failure is claimed on 1e5742df9.

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
      "evidence": "gates/test-intent/evidence/upgrade-compat-round7-review.json",
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
      "evidence": "gates/test-intent/evidence/upgrade-compat-round7-review.json",
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
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Fix: capture the removal mark before (or atomically with) the authority check, and carry that mark into the sync, so that any removal after the decision rejects the walk and its admissions. Keep it small.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Narrow C6 repair on bee392629; preserve rounds 6\u20137, #910 and U19 in both UUID modes. Resume with remaining work autonomously within this scope; no commit or issue actions.",
          "choice": "Signature-only cooldown observation adaptation: count the seven-argument execute carrying the early-captured fence instead of the six-argument entry. Preserve the same logical root walk, inputs, conditions, expected counts and restart/search assertions. The evidence retains the original executed before receipt with its original baseline and verifies byte equivalence apart from this one selector argument-count change; no new fail-before against bee392629 is claimed for the already-fixed earlier findings.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/upgrade-compat-round8.json",
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
      "behaviour": "C2 findings 1\u20134: an isolated queued update becomes searchable across restart; a never-indexed missing exact file remains recoverable and indexes after return; the authentic MIGRATING and SWITCHING fixtures complete through public resume without manual state changes and retain the original terminal failure; nonnumeric/overflowed root cooldowns retain old search and cannot starve another root. A partial native Green missing an unavailable exact file must preserve Blue search until that exact file returns, then complete normally. Every repair case fails at its intended assertion on da5d6059d. The enqueue merge preservation case passes before and after.",
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
      "evidence": "gates/test-intent/evidence/upgrade-compat-round7-review.json",
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
      "evidence": "gates/test-intent/evidence/upgrade-compat-round7-review.json",
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
          "delegationScope": "Round4: test the three C3 claims on eae494706; preserve rounds1\u20133 and U19; do not change queue schema. Resume with remaining work autonomously within the delegated scope.",
          "choice": "Use the existing v17 unit_revision backfill to prefix revisions born from an actual v13 database, with its saved application_id generation. This tags the original cohort in the existing single atomic schema-migration transaction and existing revision-write pass: no schema, DDL or version change and no extra full-queue revision rewrite. Preparation selectively repairs only that original generation; a different modern database cannot acquire provenance from an old sidecar, a null boundary or a nonzero header. A normal enqueue mints a fresh revision, immediately revoking even cached exact-path authority. Validate the current saved revision; an unreadable admission SELECT throws into the per-job retry handler and never authorizes absence. Keep both unitRevision and preserveRetryWaits in the merged enqueue path. Native replay of an old unscoped buffered UPSERT preserves an already durable unfinished original admission or original exhausted debt completed on retained Green, rather than renewing away its retry state or provenance; new strong/recorded admissions retain normal behavior. Only original RETRY_EXHAUSTED exact files proven absent from Blue by a strict physical DOC_ID count become cutover-independent, durably in their revision. Read failure or indexed content retains the existing protection. Keep the real row, state, retries and checkpoint; subtract only this original debt in native monitor/drain, replay pending barrier and final publication fences, while replay still waits for every PROCESSING row. Unowned terminal failure exemptions are per original saved revision, never database-wide, so ordinary/recorded failure budgets remain intact. A FAILED native migration with its retained candidate, current database-bound checkpoint and original debt retries the same candidate on boot with existing certification/budget/publication guards. DONE debt remains identifiable when a file returned before restart. Deadline tests use the real CutoverContext duration0 and actual transitions, never manual saved-state or timestamp edits. Invalid decoded root keys are moved once to reversible UTF16-hex quarantined-root keys retaining their values; atomic save precedes one count-only warning. Preserve quarantine across preparation and never delete its checkpoint; healthy roots still finish independently. No inferred volume identity or parent authority. Keep the original private single-step migration entry point for the unchanged supported-version fixture checks; it delegates with no v13 witness, while the atomic real-v13 ladder carries its actual generation. Retain the original single Statement retry repair and all unchanged crash interception points, scoped only by a constant and the integer cohort generation. No scale regeneration because the recovery walk is unchanged. The integration task budget is60min rather than30min with unchanged assertions and method timeouts, a1GiB spawned-Engine heap cap, fixed JVM temporary directories and sequential slotted Gradle calls.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/upgrade-compat-round7-admission-quality.json",
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
      "evidence": "gates/test-intent/evidence/upgrade-compat-round7-review.json",
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
      "behaviour": "C5: four parent sync/upgrade recovery cases retain empty-mount and replacement-volume nested roots' indexed content across restart, preserve their child admissions unchanged, still prune the parent's conclusive deletion, and keep a denied nested folder from blocking the parent's recovery; a root removed after spool discovery admits no recreated jobs; a modified DONE source loses its eight-day-old failure history, survives one transient parser timeout, retries into search and remains searchable after restart. Tests use the authentic small 0.3.0 fixture and the real Worker boot. C6 additionally rejects a removal between authority proof and sync-entry fence capture while Head membership remains; jobs stay absent after restart. X5: a later recovery call after Worker unwatch/deletion also rejects stale Head membership; no recreated jobs or alpha search content remain after normal Head membership cleanup and restart.",
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
          "delegationScope": "Fix C5 findings 1\u20133 on 7245bedaf; keep all earlier behavior. Finding 4 is main issue #913 and must not be changed; finding 5 is accepted as is. Resume with remaining work autonomously within the delegated scope.",
          "choice": "Use one nested-root ownership check in the shared sync prune guard and directory walk: retain the child's indexed rows, and skip its subtree instead of admitting its files with the parent boundary. Apply the same ownership rule to failed visits: a denied child mount keeps only the child's debt, without repeatedly walking the healthy parent. Two recovery counterexamples failed at that assertion on the initial candidate before adding the failed-visit guard; both ordinary sync preservation cases passed. This applies to empty mount folders and present replacement-volume bytes, irrespective of whether the child is currently available. Only the child's own recovery may judge or admit its sources using its volume evidence. Recheck current root membership and the walk's removal generation under the watcher registry monitor used by unwatch, holding that monitor only for each bounded admission batch, including registry handover. Capture the fence before the walk; keep the existing six-argument sync helper and let SyncDirectoryOps invoke its own queue inside that fence, so original cooldown spies and real-process admission kill hooks continue to observe the actual operation without test changes. The first full attempt exposed both displaced hooks and was cancelled before Engine suites; preserve its diagnostics and rerun the corrected candidate. Re-admitting an eligible DONE row clears the completed admission's failure history; leave unfinished admissions, parser/I/O waits and finite-walk receipts intact. No schema change or new recovery checkpoint. Exercise the removal race through normal unwatch and delete routes, and a real subsequent parser retry through search and restart. Because the shared prune guard and bounded admission affect recovery cost, generate the scale fixture once, reuse it for a bounded walk/extraction measurement, then delete it. Keep fixed before/after JVM folders, sequential slotted Gradle calls and retained caches. Run packaged HTTP first boot and restart once at the end. Scale evidence is a 40.23-second idempotent root walk on the single generated corpus, with zero unchanged-file extractions and no missing-boundary retirements; it is not a full queue drain. The inherited probe required global checkpoint deletion in the same tick, although readiness is sampled before the walk. Preserve that failed assumption as evidence, reuse the same data without regeneration, invoke the normal completion tick and verify checkpoint release plus all 700042 jobs retained. The remaining 699641 queued jobs make full drain unverified. Do not edit migration states or recovery properties to force completion. Final packaged HTTP first boot and restart both retain the original writable IDLE generation and find new files; preserve retry waits and close both owned Engines.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Fix: capture the removal mark before (or atomically with) the authority check, and carry that mark into the sync, so that any removal after the decision rejects the walk and its admissions. Keep it small.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Narrow C6 repair on bee392629; preserve rounds 6\u20137, #910 and U19 in both UUID modes. Resume with remaining work autonomously within this scope; no commit or issue actions.",
          "choice": "Capture the same registry instance and its removal mark before root-volume/authority proof. Pass the resulting per-call admission closure explicitly to sync; check it before pruning/walking and again under the existing unwatch monitor for every bounded admission batch, retaining registry-handover checks. Remove the late-capture factory field and setter. No new saved state, schema or walk throughput change; no scale generation. The new test pauses at the first sync-entry cancellation callback, keeps Head membership while Worker unwatch/deletion complete, then resumes recovery, requires zero jobs and checks zero again after Head membership cleanup and a real restart. Both profiles fail before at 18 recreated jobs rather than zero, on unchanged bee392629 product. The unchanged cooldown observer is adapted only from the six-argument execute signature to the seven-argument signature; its inputs, expected call counts, fault injection, conditions and search/restart assertions remain identical. The unchanged original C5 expectations retain their earlier executed fail-before receipts in gates/test-intent/evidence/upgrade-compat-round7-review.json; only C6 is asserted to fail on the later bee392629 baseline.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "a root removal the Worker has carried out must block recovery of that root until the root is added again, not only removals after the call's mark.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Round 9 X4/X5 narrow repairs on 1e5742df9; preserve rounds 6\u20138, #910 and U19 in both UUID modes. Resume with remaining work autonomously; no commit, schema or GitHub issue actions.",
          "choice": "Retain an in-memory removal tombstone through producer handover, separate from the historical removal generation that still rejects old in-flight work. Only an explicit accepted add restores authority. Normal Head removal durably removes membership before the following restart; recovery then excludes that root. Invoke two later recovery passes with stale Head membership after real unwatch/deletion, then restart with beta only and check zero alpha jobs and no alpha search content. Preserve the earlier executed C6 before receipt on bee392629 for the unchanged prior oracle; fresh X5 before uses 1e5742df9.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/upgrade-compat-merge921.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RootRecoveryIsolationTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RootRecoveryIsolationTest.java#rootRemovedAfterAuthorityBeforeFenceCannotRecreateJobs",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RootRecoveryIsolationTest.java#laterRecoveryDuringHeadRemovalLagCannotRecreateJobs"
      ],
      "repaired": true,
      "historicalEvidence": "gates/test-intent/evidence/upgrade-compat-round8.json",
      "precedingEvidence": "gates/test-intent/evidence/upgrade-compat-round9.json",
      "sourceAdaptationEvidence": "gates/test-intent/evidence/upgrade-compat-merge921-preservation.json"
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/WorkerBootFixture.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/NoVolumeIdentityExtension.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/NoVolumeIdentityExtension.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/LinuxRootUnsupportedArchitectureTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/LinuxRootVolumeSafetyTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/server/DefaultWorkerAppServicesProducerTransferTest.java",
        "modules/indexer-worker/src/test/resources/META-INF/services/org.junit.jupiter.api.extension.Extension",
        "modules/worker-services/src/test/resources/META-INF/services/org.junit.jupiter.api.extension.Extension",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/NoVolumeIdentitySeamTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/NoVolumeIdentitySeamTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation",
        "signature"
      ],
      "whatMoved": "Review follow-up: restore both real Linux disk UUID and tmpfs tests with their original assertions and a /dev/disk/by-uuid JUnit assumption; retain the separate injected variants. Restore the real unsupported-ABI portable-witness test and retain its injected companion. Also restore the original real-host prune replay missing-record check with unchanged assertions and a native-comparable-evidence assumption; retain its injected companion separately. Remove module-wide autodetection resources/service registration and the injection hidden in WorkerBootFixture. Register controlled volume evidence explicitly on only the affected upgrade tests and the deferred-writer handoff method, restore the previous lookup after each test, and install it explicitly in the two raw crash drivers. All remaining tests use real lookup by default. The exact opt-in list and reasons are in tmp/builder/round7-review-injection-manifest.json. Re-run both evidence modes; separately prove native checks abort, rather than pass, in a /dev without UUID mappings.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Restore real-interface checks and make host-independent injection explicit, keeping the default real.",
          "choice": "Review follow-up: restore both real Linux disk UUID and tmpfs tests with their original assertions and a /dev/disk/by-uuid JUnit assumption; retain the separate injected variants. Restore the real unsupported-ABI portable-witness test and retain its injected companion. Also restore the original real-host prune replay missing-record check with unchanged assertions and a native-comparable-evidence assumption; retain its injected companion separately. Remove module-wide autodetection resources/service registration and the injection hidden in WorkerBootFixture. Register controlled volume evidence explicitly on only the affected upgrade tests and the deferred-writer handoff method, restore the previous lookup after each test, and install it explicitly in the two raw crash drivers. All remaining tests use real lookup by default. The exact opt-in list and reasons are in tmp/builder/round7-review-injection-manifest.json. Re-run both evidence modes; separately prove native checks abort, rather than pass, in a /dev without UUID mappings.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/WorkerIngestionAuthorityTest.java"
      ],
      "class": "New",
      "behaviour": "Ordinary unscoped watcher/scan/replay admissions retain main policy retirement without source reads. Only original v13 upgrade admissions and durably quarantined unscoped admissions encountered while invalidating an upgrade checkpoint use bounded retry; they acquire no ingestion authority.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U11",
          "quote": "Never delete on uncertainty: an unplugged drive, another volume on the same Windows letter, denied access or unreadable root evidence at the first start leaves that root's indexed rows searchable and undeleted through boot, sync, watcher and queued processing; its recovery waits until the root is confirmed, then runs. Other roots become current.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Keep ordinary main retirement and wait only for upgrade cases where admission evidence may still arrive, with a bound.",
          "choice": "Keep SKIP_DONE/SKIPPED_POLICY/NONE for ordinary missing-boundary admissions, including legacy VDU refresh and unscoped retries/replay. Use existing RETRY_WITH_BACKOFF, bounded at seven days from first failure, for release030/ identities or release030-unscoped/ quarantine. The latter is stamped only inside the existing upgrade-preparation transaction when a recovery checkpoint describes unscoped modern work. It preserves the opaque original revision as a suffix, survives checkpoint completion/restart, and never matches the release030/% legacy authority selector. A fresh real scoped admission replaces the quarantine. Pending backoff contributes to queue depth and WAITING; after the bound, RETRY_EXHAUSTED is visible as a failure and leaves the active queue. Real unavailable/locked sources with valid scope retain retry-until-available. No schema/checkpoint-format change.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/upgrade-compat-round7-review.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/WorkerIngestionAuthorityTest.java#onlyUpgradeUnscopedDebtUsesTheBoundedRetryLadder"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030AdmissionDebtTest.java"
      ],
      "class": "New",
      "behaviour": "Preservation: an intentional modern exact-file add outside roots works with injected UUID evidence both present and absent, never becomes a v13 admission and never ingests its neighbor. The stale-database test separately holds an unowned modern job through restart, then demonstrates that a fresh deliberate exact request resolves its durable debt.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Keep all earlier behavior while fixing unowned modern admissions and verify both UUID evidence states.",
          "choice": "Use the actual RootAdded marker that makes a request a deliberate add. Retain the failed unmarked positive setup as a diagnostic rather than claiming it is a product regression: a marked request records an available parent volume, while an unmarked one deliberately records nothing. Parameterized names include their methods for unambiguous matched evidence. This preservation check passes before and after; the negative database-binding and missing-boundary checks fail at their intended assertions before.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/upgrade-compat-round7-admission-quality.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030AdmissionDebtTest.java#deliberateModernExactAdmissionWorksWithoutBorrowingLegacyAuthority"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/LegacyIngestionBoundaryTest.java"
      ],
      "class": "New",
      "behaviour": "Preserve main retirement for ordinary v21 watcher/scan and historical replay rows with no boundary, along with existing scoped replay and exclusion checks. New recovery quarantine persists after checkpoint cleanup and another restart, never grants a boundary, reaches RETRY_EXHAUSTED at the existing seven-day deadline with zero DONE rows, and is revived by a deliberate fresh exact-path admission.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Keep ordinary main retirement and bounded upgrade-only uncertainty without granting ingestion authority.",
          "choice": "Preserve main retirement for ordinary v21 watcher/scan and historical replay rows with no boundary, along with existing scoped replay and exclusion checks. New recovery quarantine persists after checkpoint cleanup and another restart, never grants a boundary, reaches RETRY_EXHAUSTED at the existing seven-day deadline with zero DONE rows, and is revived by a deliberate fresh exact-path admission. Exercise the real SQLite queue and persisted deadline; restore the four ordinary main preservation methods from HEAD unchanged.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/upgrade-compat-round7-review.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/LegacyIngestionBoundaryTest.java#replacementCheckpointQuarantinesOnlyUpgradeDebtAndStopsRetryingAfterSevenDays"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherDeleteCrashRecoveryTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "Register the volume extension explicitly on this class, restore real lookup after each test, and initialize the same selected lookup in its raw crash-worker JVM. Keep every crash cut, halt code, queue revision, atomic parent/chunk assertion, reload debt and cleanup oracle unchanged. The default full suite exposed four unreached cuts because child real UUID disagreed with parent synthetic UUID; the absent matrix is audited separately. A focused two-mode follow-up replaces only this superseded harness result; all other full-suite results remain valid on identical product bytes.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Make volume-dependent module checks deterministic with UUID evidence present and absent; preserve every earlier crash and queue check.",
          "choice": "Use explicit per-test registration and the existing test extension initialization at this raw child entry point, as for the upgrade process-kill fixture. No product, crash boundary, assertion or model changes. Preserve failing full-run XML, prove all seven cases with each lookup result, and do not run a second heavy Gradle call while Engine suites are active.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceDocumentIdentityTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/PruneWriterFenceTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/PrunePrefixReplayFenceTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation",
        "split"
      ],
      "whatMoved": "Retain every original real-host prune replay assertion, adding only explicit native-evidence assumptions. Keep the injected comparable-volume variant as an additional method. Document identity and prune fence classes use real lookup by default. Rename fixtures retain the physical destination/move precondition fixes and all existing UID/rekey/row/replay/fence assertions. No rename, prune or volume product policy changes.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Make host-volume-dependent module fixtures deterministic and keep all earlier behavior; missing job admission evidence must fail closed.",
          "choice": "Retain every original real-host prune replay assertion, adding only explicit native-evidence assumptions. Keep the injected comparable-volume variant as an additional method. Document identity and prune fence classes use real lookup by default. Rename fixtures retain the physical destination/move precondition fixes and all existing UID/rekey/row/replay/fence assertions. No rename, prune or volume product policy changes.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/UpgradeUnscopedDebtReadmissionTest.java"
      ],
      "class": "New",
      "behaviour": "Ordinary enqueue, explicit retry and maintenance re-admission without new scope preserve non-authorizing upgrade quarantine with a fresh revision; they cannot retire unresolved upgrade debt. A fresh scoped admission clears the quarantine and works normally.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Restore ordinary main retirement and bound only upgrade missing-boundary debt, without losing that classification on a legitimate unscoped retry.",
          "choice": "Preserve the distinct release030-unscoped/ classification in the common enqueue conflict update only while new scope is absent; always mint a new revision and never grant legacy exact-file authority. Reuse existing revision and retry bound; no schema change.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/upgrade-compat-round7-readmission.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/UpgradeUnscopedDebtReadmissionTest.java"
      ],
      "repaired": true
    },
    {
      "class": "New",
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030ConfiguredHeadlessRecoveryTest.java",
        "modules/indexer-worker/build.gradle.kts"
      ],
      "behaviour": "X4: authentic v13 queue through real HeadlessApp/YAML recovers an offline-added unqueued configured file, finishes configured and bundled-help recovery, and retains search across restart without rebuilding. The Gradle argument provider and isolated runtime configuration execute that new real-entry-point check without adding UI resources to the parent test classpath.",
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
          "delegationQuote": "Find the real cause and fix it in one place.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Round 9 X4/X5 narrow repairs on 1e5742df9; preserve rounds 6\u20138, #910 and U19 in both UUID modes. Resume with remaining work autonomously; no commit, schema or GitHub issue actions.",
          "choice": "Shared post-construction wiring restores configured recovery membership from each producer snapshot, including the deferred writer, via existing seeding. Keep declared-root boot seeding and volume checks. Use a separately resolved, locked UI runtime only in the HeadlessApp child JVM: the initial testRuntimeOnly dependency also added UI resources and JARs to every parent test; 1 GiB executors exhausted their heap and their incomplete suites are not passes. A cache-safe classpath argument provider supplies that isolated runtime without changing the parent test classpath or ordinary UUID lookup. The read-only queue trace confirms search publication precedes queue acknowledgement (PROCESSING then DONE/SUCCESS_FULL); retain the DONE requirement with the existing bounded await. Use the established var-models/try(models) resource style to resolve PMD without changing resource ownership. Re-execute the literal finalized X4/X5 checks on the reusable sparse 1e5742df9 checkout and the repaired candidate, with compile/PMD and the complete requested indexer matrix. This supersedes the initial harness receipts, which remain diagnostic evidence. HOME/DATA stay under fixed JVM temp folders; stop each child and reuse each small data folder across its first boot and restart. No schema or scale change. The isolated parent also exceeded the private 1 GiB executor limit in a full serial matrix; retain that cancelled failure and run the final unchanged assertions with a 2 GiB indexer heap, sequential batches of 20 classes, one fork and one Gradle worker. Available machine memory was checked before this runner-only capacity adjustment; production heap settings are unchanged.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Fix in production, not in the tests: a missing configuration or collection list seeds no configured roots.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Round 10 R1: restore five unchanged preservation classes on d7e0a43bc, retain real configured-root recovery, run both full module suites and compile/PMD. No test edits or commit.",
          "choice": "Snapshot producer.resolvedConfig once and pass an absent configuration to the existing configuredRoots(null) empty-root guard. Never borrow global configuration to authorize roots. Keep the five test sources byte-identical: fresh before has 34 failures of 42; full candidate indexer suite restores all 42 and the headless/restart and root-isolation checks. Full worker suite has only the argfile stdout failure also recorded by the independent 95b6cd2db baseline; retain it and its baseline receipt rather than alter the test or unrelated extraction code. Attach the new preservation evidence while preserving the rejection history; independent acceptance remains required.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/upgrade-compat-round9.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030ConfiguredHeadlessRecoveryTest.java"
      ],
      "repaired": true,
      "preservationEvidence": "gates/test-intent/evidence/upgrade-compat-round10.json"
    },
    {
      "class": "New",
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RootWatcherRegistryTest.java"
      ],
      "behaviour": "X5: removal blocks later recovery through handover; a deliberate re-add restores new recovery while rejecting the old in-flight mark.",
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
          "delegationQuote": "Keep it small and in-memory if that suffices for the restart case.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Round 9 X4/X5 narrow repairs on 1e5742df9; preserve rounds 6\u20138, #910 and U19 in both UUID modes. Resume with remaining work autonomously; no commit, schema or GitHub issue actions.",
          "choice": "Retain removal authority across registry handover. Normal Head removal persists membership cleanup; the subsequent boot does not declare that root and completes its checkpoint without walking. Preserve historical removal counts across re-add for already-issued calls. This additional check covers rejection, handover, explicit re-add and the old-call fence.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/upgrade-compat-round9.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RootWatcherRegistryTest.java#removalBlocksLaterRecoveryThroughHandoverUntilAnExplicitAdd"
      ],
      "repaired": true
    },
    {
      "class": "Adaptation",
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/WatcherReloadRetryTest.java"
      ],
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Supply the real immutable fixture configuration from both producer doubles when post-construction wiring now reads resolvedConfig(). A deep-stubbed configuration otherwise invents a mock bundled-help Path whose getParent() never terminates. Preserve every original source path, root, collection, opener/reconstruction failure, mutation, count and assertion. The adapted check is executed on 1e5742df9 as preservation and on the repaired candidate in both UUID modes; its blank skipped/resource-exhausted earlier attempts are not passes. Receipt: gates/test-intent/evidence/upgrade-compat-round9-watcher-preservation.json.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Re-run the round 6-8 checks, #910's tests and U19 in both UUID modes, plus compile and PMD, in as few Gradle calls as possible.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Round 9 preservation of the existing failed-opener/failed-reconstruction watcher obligation while fixing configured-root wiring. No changed oracle or production workaround for mock Paths.",
          "choice": "Bind only the two producer-double configuration accessors to ConfigStore.global().get(), the same snapshot the fixture already published. Keep all fault injection and final deletion/debt assertions byte-identical. Run this adaptation before and after rather than count a blank skip or executor OOM as success.",
          "label": "agent choice"
        }
      ]
    },
    {
      "class": "Adaptation",
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RootRecoveryIsolationTest.java"
      ],
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Encode the existing explicit Head boot root list in the fixture watched_roots.json as well. The authentic archive originally declares alpha and beta; the C18 setup intentionally supplies parent and alpha instead. The shared #921 prefix scope now reads authored declarations, so stale beta correctly prevents the original parent-own-deletion assertion from passing. No original root, operation, fault, membership transition, expected count, search or restart assertion changes. Keep the eight failed original merge cases as diagnostic failures; fresh both-mode execution supersedes only this one eight-case class per profile. Receipt: gates/test-intent/evidence/upgrade-compat-merge921-preservation.json.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Resolve the conflicts so there is one code path for nested-root protection.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Preserve #921 removal and branch recovery/prune/fence/removal-block semantics; stage resolution without committing; run the named both-UUID checks.",
          "choice": "Use PathPrefixScope for every nested-root decision, including configured roots. Retain existing recovery admission modes and the registry removal fence/block. Adapt only the test root-declaration encoding to the canonical authored source now consumed by that shared authority, preserving every original oracle. Reuse the recorded passing Round9 source-identical eight-case receipt as historical preservation evidence, not a new before run; execute the adapted class freshly in both modes after the combined merge matrix.",
          "label": "agent choice"
        }
      ]
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

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a16b3c-afd6-7230-b6f7-88d209469cb6",
  "verdict": "reject",
  "digest": "sha256:050851d2509c404fe15874edb4cb06887c74e629e8473ef13cef4187c1bc4c54",
  "note": "Configured roots miss files and removed roots regain jobs across restart."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a16c87-440d-7273-9ecc-fb5233765e87",
  "verdict": "reject",
  "digest": "sha256:1c5d5fffb88f85d9b07ec2f569580f296668a26f5b1de51181569a57bfc776f4",
  "note": "Reject: 34 unchanged settings/query preservation checks pass on main and fail at the new configured-root wiring, despite successful upgrade and scale recovery checks."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a16d32-190d-76c0-a76a-f1d3f72788cd",
  "verdict": "reject",
  "digest": "sha256:ffad9dd4dedd07b876d9b7c78a4bc790297f38945d2cb3fe7fd7b5e1afd0cad4",
  "note": "Reject the configured-root/headless entry (Release030ConfiguredHeadlessRecoveryTest.java and modules/indexer-worker/build.gradle.kts), specifically its full-suite preservation claim: R2 ExtractionHelperWindowRecordsTest.markdownAndTextParsesWriteNoHelperWindowRecords passes the c29fcddc1e main full suite in 1.431 seconds but times out at its 30-second limit on snapshot 2af5e30fc8 (31.747 seconds elapsed). A focused retry passes in 1.603 seconds; the original full-suite failure remains unexplained. No product-code cause is asserted. R1's 42 unchanged checks and all 84 Release030 cases in each UUID profile pass, including the corrected uniform-child-profile crash rerun. Recommend a quiet --clean-checkout worker-suite rerun, retaining these results. Initial temp-path/configuration deviations are retained in verifier evidence."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-8_vhx2u-r2",
  "verdict": "accept",
  "digest": "sha256:ffad9dd4dedd07b876d9b7c78a4bc790297f38945d2cb3fe7fd7b5e1afd0cad4",
  "note": "Scoped R2 acceptance under the requested full-suite-success criterion on 01af31f1230fa75f74f7e33c0cb848c4189bcede: candidate worker-services full suite passed 2036 tests with zero failures/errors and 18 skipped; merge base c29fcddc1e passed 2031 with zero failures/errors and 18 skipped. R2 passed in 2.573s and 2.009s respectively. Test-start load averages were candidate 17.59/13.09/11.58 and baseline 5.65/7.39/9.22. Extraction/R2 bytecode and normalized runtime classpath are identical, with no UI/indexer-worker leak; no changed code is on the direct standalone R2 path. Five class repetitions on each tree remain UNVERIFIED: prolonged shared-slot waits and a cancelled driver leaving its queued wrapper without an accessible tool handle blocked execution. Candidate first full suite used the machine-default checkout-local JVM temp folder; baseline used the corrected fixed before folder; corrected candidate rerun never started. This does not prove the original timeout was load. No product code changed, and no broad upgrade scenario was reverified. The existing rejection is retained; this record does not itself settle the gate tie-break requirement."
}
```
