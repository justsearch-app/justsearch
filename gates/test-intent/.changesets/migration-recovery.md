---
schema: test-intent.v1
task: t-20261020-a5fbcb
author-role: builder
author-session: cc6d91b2-5314-42fd-867b-dbc2d8a68699
---

# Test-intent entries: a recorded migration interrupted by a restart ends cleanly

Sources are the agreed scenarios M1-M12 of this task (v2 wins where it differs from v1) and the
orchestrator's decisions on the first verifier rejection (round 2). The round-1 repaired checks
carry an assertion fail-before on the baseline 542eea0d6 (run-evidence.mjs --before 542eea0d6);
the round-1 preservation checks pass on 542eea0d6 and after. The round-2 checks are judged
against the rejected candidate 02d503739 (run-evidence.mjs --before 02d503739): the repaired ones
fail there by assertion, the preserved ones pass there and after. The round-3 repaired checks,
on the second verifier rejection, are judged against c44f281a0 (run-evidence.mjs --before
c44f281a0) and fail there by assertion; the round-3 preserved one passes there and after. Round 4 (decision (b): cleanup queues an A job) is judged against the round-3 state, c44f281a0 with its uncommitted main-code diff applied as the before-patch gates/test-intent/evidence/migration-recovery-round3.patch; it changed the expected outcome of three earlier repaired checks, which were renamed and re-run against their own before states. Round 5 (only A's committed effect hands over; a pending A job means wait) is judged against a26eaf3e3 (run-evidence.mjs --before a26eaf3e3); it changed the expected outcome of seven earlier checks, which were renamed in their entries and re-run against those entries' before states, and moved one preserved check into its repaired entry. The queue-ownership check
exercises a query this change adds, so it has no before state. Real-Engine evidence is outside
these files. D4 files are written by run-evidence.mjs. No acceptance is recorded by the
builder.

```json
{
  "entries": [
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "A recorded candidate the Engine refused, in a FAILED pointer, with one accepted file journal UPSERT: with the producer's watcher-uncertainty latch set (as a restart's root re-registration sets it), or, with 40 PENDING queue rows from a FENCED boot's root walk whose revision superseded the journal's, once the producer's writer (started by that FENCED boot over A after the refused walk sealed) has committed the file on A, refusal cleanup settles the journal on A, acknowledges it, abandons and physically deletes B, and replaces the producer: the pointer is IDLE with A active, no building or previous generation, and only A's directory under indices/ (before: the pointer stays FAILED and B is kept). Cleanup stopped by a harness cut after A's source commit, after the journal acknowledgement, after abandonment or after physical deletion leaves the restart undone; a later cleanup attempt by a fresh producer reaches the same IDLE, A-only state (before: none of the four seams exists). In SWITCHING, the real cutover monitor fails the pointer (FAILED, A still active) within 5 s when the producer's latch is set, or when the embedding model is configured and the candidate is provably empty: walk COMPLETE and captured with 0 planned units, enumeration done, no projection sources, no pending or claimed rows, no journal obligation for B other than removals, and 0 documents (before: it stays SWITCHING). Round 5 (A's committed effect only) changed this check's expected outcome; it was renamed and re-run against this entry's before state.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-a5fbcb",
          "scenario": "M1",
          "quote": "Engine killed after MIGRATING publication with a watched root; on restart the migration ends. Initial delivery is a clean failure",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-a5fbcb",
          "scenario": "M1",
          "quote": "A active, pointer IDLE, B physically absent, and no capacity reservation or previous-generation alias left",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-a5fbcb",
          "scenario": "M2",
          "quote": "Precommit cuts (bulk-partial-capture, bulk-before-building-checkpoint, the Green-open and SWITCHING barriers) change to the clean-failure outcome: D where they stall today, K where they already recover.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-a5fbcb",
          "scenario": "M5",
          "quote": "Then release and restart: today it defers until the 30-minute deadline. After: a terminal refusal within 60 s of readiness.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-a5fbcb",
          "scenario": "M8",
          "quote": "Disks already in the stuck states, FAILED pointer with a RUNNING operation, and SWITCHING pointer with a RUNNING operation, recover on the first start of the new version into the M1 outcome.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-a5fbcb",
          "scenario": "M9",
          "quote": "The refusal cleanup's source commit, journal acknowledgement, abandonment and physical deletion each get a deterministic seam where absent, plus a second restart at each.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-a5fbcb",
          "scenario": "M1",
          "quote": "Before journal versions are acknowledged, their effects are committed and verified on the surviving generation.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/migration-recovery-repaired.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#latchedWatcherUncertaintyDoesNotBlockRollback",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#pendingFencedRootWalkHandsOverOnceAsWriterCommitsIt",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#aCutAfterTheSourceCommitResumesToCleanFailure",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#aCutAfterTheJournalAcknowledgementResumesToCleanFailure",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#aCutAfterAbandonmentResumesToCleanFailure",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#aCutAfterPhysicalRetirementResumesToCleanFailure",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#latchedSwitchingCandidateFailsPromptly",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#provablyEmptyCandidateFailsPromptly"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java"
      ],
      "class": "New",
      "behaviour": "Preservation, passing on the baseline 542eea0d6 and after. A refused candidate keeps Green (no abandonment, producer open, journal kept, no restart) while a queue row is claimed, and while an accepted admission is neither verified on A nor still owned by the queue. In SWITCHING with an embedding model, a candidate with one document, one planned unit, one journal obligation, one pending row, a running enumerator or an unreadable walk is not refused and stays SWITCHING for 1.5 s; a keyword-only empty candidate is not refused either.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-a5fbcb",
          "scenario": "M5",
          "quote": "A zero-document candidate with a pending projection or UPSERT is not empty and is never discarded.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-a5fbcb",
          "scenario": "M7",
          "quote": "keyword-only and corruption-recovery empty paths behave as today",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-a5fbcb",
          "scenario": "M10",
          "quote": "Unreadable journals or counts, missing or replaced volumes, and gap acceptance keep failing closed and explicit, as today.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-a5fbcb",
          "scenario": "M1",
          "quote": "Before journal versions are acknowledged, their effects are committed and verified on the surviving generation.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/migration-recovery-preserve.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#claimedRowStillRetainsGreen",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#unprovedAdmissionStillRetainsGreen",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#aCandidateWithADocumentKeepsWaiting",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#aCandidateWithAPlannedUnitKeepsWaiting",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#aCandidateWithAJournalObligationKeepsWaiting",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#aCandidateWithAPendingRowKeepsWaiting",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#aCandidateWhoseEnumeratorRunsKeepsWaiting",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#aCandidateWithAnUnreadableWalkKeepsWaiting",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#keywordOnlyEmptyCandidateIsNotRefused"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/QueueAdmissionOwnershipTest.java"
      ],
      "class": "New",
      "behaviour": "On the real SQLite queue, a refused candidate's accepted file admission is handed to A only by A's committed effect: a finished (DONE) row of the file, of the accepted admission or a later one, whose writer committed a content SHA-256 equal to the SOURCE_SHA256 A holds, even when a later admission's captured plan names other bytes (COMMITTED). A PENDING, PROCESSING or FAILED row is outstanding work, not an effect (OUTSTANDING). No row, a DONE row while A holds other bytes or lacks the file, and a DONE row that wrote nothing (no committed hash, as retirement or refusal leaves it) while its plan names A's bytes hand nothing over (NONE). This is a query the change adds, so there is no before state. Round 5 replaced the boolean query, under which a PENDING row handed over, with this three-way one.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-a5fbcb",
          "scenario": "M1",
          "quote": "Before journal versions are acknowledged, their effects are committed and verified on the surviving generation.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-a5fbcb",
          "scenario": "M1",
          "quote": "judge hand-over by A's committed effect, as M1+ requires (\"effects, not rows\")",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/migration-recovery-ownership.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/QueueAdmissionOwnershipTest.java#onlyACommittedWriteHandsTheFileToTheSource"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Round 2, judged against 02d503739. (1) In SWITCHING with an embedding model, an empty candidate whose only journal obligation is an already-applied DELETE_COLLECTION of mcp-ingest is refused by the real cutover monitor within 5 s, with A still active (before: it stays SWITCHING). (2) On the real SQLite queue, when a later admission of the file is DONE but A still holds other bytes and nothing is pending, refusal cleanup queues one ordinary PENDING A job for the file (new revision, no scan, no planned hash; none more on a re-entered attempt) and keeps Green and the journal, then acknowledges the UPSERT, requests the restart and reaches IDLE once A's writer committed it (before: the UPSERT is acknowledged while the row stays DONE and nothing will bring the update to A). Decision (b) of round 4 replaced round 2's 'keeps Green'. (3) A latched candidate is refused at the cutover monitor's first pass, without waiting for its queue or enumeration: in SWITCHING with 5,000 queued rows and a claimed row, and in MIGRATING with the enumerator still running and 5,000 queued rows, the pointer is FAILED within 5 s with A active (before: SWITCHING or MIGRATING holds). A latched SWITCHING candidate with 5,000 queued rows and an in-flight mutation lease stays SWITCHING while the lease is held, the runtime swap lock stays acquirable within 1 s throughout, and it is FAILED within 5 s of the lease's release (before: it stays SWITCHING). (4) When the Engine records the refusal only on its sixth query, the monitor's settle loop completes the cleanup within 10 s, IDLE with only A, without the reaper (before: the pointer stays FAILED). Round 5 (A's committed effect only) changed this check's expected outcome; it was renamed and re-run against this entry's before state.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-a5fbcb",
          "scenario": "M5",
          "quote": "\"provably empty\" requires every accepted journal obligation to be reconciled, so entries that are already reconciled (an applied delete, or an obligation whose effect is already committed) don't make it non-empty",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-a5fbcb",
          "scenario": "M1",
          "quote": "judge hand-over by A's committed effect, as M1+ requires (\"effects, not rows\"). That means A's index holds that revision or later, or a pending A job will produce it. Never judge it by a queue row's status alone.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-a5fbcb",
          "scenario": "M1",
          "quote": "give it a bound counted from readiness, and on expiry end in the same clean failure while keeping any obligations",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/migration-recovery-r2-repaired.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#anEmptyCandidateWithOnlyACollectionDeleteFailsPromptly",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#aLaterFinishedAdmissionWaitsForTheAJobItQueues",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#aLatchedSwitchingCandidateWithQueuedWorkFailsPromptly",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#aLatchedMigratingCandidateFailsBeforeItsEnumerationEnds",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#anInFlightMutationDelaysTheRefusalWithoutHoldingTheSwapLock",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#cleanupRetriesUntilTheEngineRecordsTheRefusal"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java"
      ],
      "class": "New",
      "behaviour": "Round 2 preservation, passing on 02d503739 and after. In SWITCHING with an embedding model, a zero-document candidate with one PROJECTION journal obligation is not refused and stays SWITCHING for 1.5 s. An unlatched MIGRATING candidate whose enumerator still runs stays MIGRATING for 1.5 s. On the real SQLite queue, refusal cleanup hands the journal UPSERT to A and completes (IDLE, journal empty, restart requested) when a later admission of the file is DONE and A holds exactly its bytes. Round 5 moved the PENDING-later-admission case, which now waits for A's commit, to its own entry.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-a5fbcb",
          "scenario": "M5",
          "quote": "A pending UPSERT or projection still does.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-a5fbcb",
          "scenario": "M8",
          "quote": "It must still let M8 complete (where the boot rescan replaced revisions)",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/migration-recovery-r2-preserve.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#aCandidateWithAProjectionObligationKeepsWaiting",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#anUnlatchedMigratingCandidateKeepsBuilding",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#aLaterAdmissionCommittedOnAHandsTheUpdateToA"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Round 3, judged against c44f281a0, on the real SQLite queue. (1) When the file's later row is DONE without a committed content hash (retired or refused without writing) and its captured plan names the bytes A still holds, with nothing pending, refusal cleanup queues one ordinary PENDING A job for the file (new revision, no scan, no planned hash), keeps Green and the journal, and acknowledges the UPSERT, requests the restart and reaches IDLE once A's writer committed it (before: the UPSERT is acknowledged while the retired row stays DONE). Decision (b) of round 4 replaced round 3's 'keeps Green'. (2) Refusal cleanup hands the journal UPSERT to A and completes (IDLE, journal empty, restart requested) when the file's later row is DONE with a committed content hash equal to A's SOURCE_SHA256 while its captured plan names other, earlier bytes (before: Green and the UPSERT are kept). Round 5 (A's committed effect only) changed this check's expected outcome; it was renamed and re-run against this entry's before state.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-a5fbcb",
          "scenario": "M1",
          "quote": "A retired or refused row is not an applied effect: only A's committed bytes, or a pending A job, count.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-a5fbcb",
          "scenario": "M1",
          "quote": "when A has committed a revision later than the accepted one, from a later admission of the same file, the accepted update is superseded and counts as handed over",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/migration-recovery-r3-repaired.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#aRetiredLaterAdmissionWaitsForTheAJobItQueues",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#aLaterRevisionCommittedOnAWithChangedBytesHandsTheUpdateToA"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RefusedRecoveryRetiredAdmissionTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Round 3, judged against c44f281a0, on the real SQLite queue and a real Lucene A. A captured walk enqueued the file with the bytes A holds; the file then changed and a watcher update was accepted on Green (its journal witness is the captured plan's hash); the Engine retired the refused walk's rows without writing. Refusal cleanup queues an ordinary PENDING A job for the file (no scan, no walk epoch, no planned hash), keeps the journal UPSERT while that job is pending, and acknowledges it once A's writer committed the file's bytes, also when a later enumeration re-admitted the changed file before retirement (before: the UPSERT is acknowledged while the retired row stays DONE, so nothing will write the new bytes to A). Round 4's decision (b) replaced round 3's 'keeps the journal'. Round 5 (A's committed effect only) changed this check's expected outcome; it was renamed and re-run against this entry's before state.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-a5fbcb",
          "scenario": "M1",
          "quote": "A retired or refused row is not an applied effect: only A's committed bytes, or a pending A job, count.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/migration-recovery-r3-retired-repaired.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RefusedRecoveryRetiredAdmissionTest.java#aRetiredWatcherUpdateHandsOverOnceAsJobCommitsIt",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RefusedRecoveryRetiredAdmissionTest.java#aRetiredReadmissionOfAChangedFileHandsOverOnceAsJobCommitsIt"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RefusedRecoveryRetiredAdmissionTest.java"
      ],
      "class": "New",
      "behaviour": "Round 3 preservation, passing on c44f281a0 and after. When the file did not change after its capture and the Engine retired a later enumeration's row without writing, refusal cleanup acknowledges the journal UPSERT, because A already holds the file's current bytes.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-a5fbcb",
          "scenario": "M1",
          "quote": "It completes forward if the new generation committed; otherwise it fails cleanly (old index active and searchable, nothing to clean up, a new migration can run).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/migration-recovery-r3-preserve.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RefusedRecoveryRetiredAdmissionTest.java#aRetiredReadmissionOfAnUnchangedFileIsHandedToA"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RefusedRecoveryRetiredAdmissionTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Round 4, judged against the round-3 state (c44f281a0 plus gates/test-intent/evidence/migration-recovery-round3.patch, its uncommitted main-code diff), with round 5's stricter rule. With an accepted file journal UPSERT that A does not hold and no pending row (the Engine retired the walk's row unwritten, or a later finished row left A stale), refusal cleanup queues one ordinary PENDING A job for the file (a new revision, no scan, no walk epoch, no planned hash), also when a later enumeration re-admitted the changed file, keeps Green and the journal while it is pending, and acknowledges, requests the restart and reaches IDLE once A's writer committed it. A re-entered cleanup, and one cut before the acknowledgement, queue no second job. A file deleted from disk gets the same ordinary A job; cleanup settles once A's writer removed it (before, every case: cleanup fails, the journal is kept and nothing is queued for A). Round 5 (A's committed effect only) changed this check's expected outcome; it was renamed and re-run against this entry's before state.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-a5fbcb",
          "scenario": "M1",
          "quote": "refusal cleanup queues an A job for a file whose accepted update A never got, then hands over by the existing pending rule",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-a5fbcb",
          "scenario": "M1",
          "quote": "Queuing must be idempotent: no duplicate if an A job for that file is already pending, and none on a re-entered cleanup or after a restart mid-cleanup.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-a5fbcb",
          "scenario": "M1",
          "quote": "A file that is gone from disk gets A's normal removal path",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/migration-recovery-r4-repaired.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#aRetiredLaterAdmissionWaitsForTheAJobItQueues",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#aLaterFinishedAdmissionWaitsForTheAJobItQueues",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RefusedRecoveryRetiredAdmissionTest.java#aRetiredWatcherUpdateHandsOverOnceAsJobCommitsIt",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RefusedRecoveryRetiredAdmissionTest.java#aRetiredReadmissionOfAChangedFileHandsOverOnceAsJobCommitsIt",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RefusedRecoveryRetiredAdmissionTest.java#aReenteredCleanupQueuesNoDuplicate",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RefusedRecoveryRetiredAdmissionTest.java#aRetiredUpdateOfADeletedFileHandsOverOnceARemovesIt"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RefusedRecoveryRetiredAdmissionTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Round 5, judged against a26eaf3e3. During refusal cleanup a pending A job no longer authorizes acknowledgement; only A's committed effect does. (1) A watcher update accepted on Green whose file is unreadable, with its own A row PENDING: cleanup reports the source unproved and keeps the journal UPSERT (before: acknowledged on the pending row). (2) An A job that cleanup queued and that then fails with IO_FAILED / SOURCE_UNREADABLE keeps the journal, also once the file is readable again while the row waits for its retry; cleanup acknowledges only after a later attempt committed the bytes (before: acknowledged when queued). (3) For a retired watcher update, a retired re-admission of a changed file, a later finished admission that left A stale, a retired later admission, and a pending later admission, cleanup keeps Green and the journal (pointer FAILED, no restart) until A's writer committed the file, then acknowledges, requests the restart and reaches IDLE; a re-entered or cut attempt queues no second job. (4) A retired update of a file deleted from disk keeps the journal until A's writer removed the file. (5) M8: a FENCED recovery boot whose refused walk is sealed starts the producer's writer, which writes A, so its pending rows can commit; cleanup keeps Green while they are outstanding and rolls back to A once committed (before: the writer never starts and the rows' pending state alone handed over).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-a5fbcb",
          "scenario": "M1",
          "quote": "during refusal cleanup, hand-over of an accepted update or delete requires A's committed effect: A's index holds that revision, a later revision from a later admission, or the removal. A pending A job, the one cleanup queues or any other, no longer authorizes acknowledgement. It only means \"wait\".",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-a5fbcb",
          "scenario": "M1",
          "quote": "An A job that fails (unreadable, I/O error) keeps Green and the journal. Cleanup settles once a later A attempt commits, or once the file is deleted and A's removal commits.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-a5fbcb",
          "scenario": "M1",
          "quote": "One restart after a crash must still reach IDLE when A's jobs succeed. Cleanup must therefore re-check while the Engine runs",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-a5fbcb",
          "scenario": "M1",
          "quote": "Rule out a deadlock: if A's worker can't process jobs while refusal cleanup is in progress (writer pause, admission held, a monitor), waiting for A's commit would never finish.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/migration-recovery-r5-repaired.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RefusedRecoveryRetiredAdmissionTest.java#aPendingUpdateOfAnUnreadableFileKeepsTheJournal",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RefusedRecoveryRetiredAdmissionTest.java#aFailedAJobKeepsTheJournalUntilALaterAttemptCommits",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RefusedRecoveryRetiredAdmissionTest.java#aRetiredWatcherUpdateHandsOverOnceAsJobCommitsIt",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RefusedRecoveryRetiredAdmissionTest.java#aRetiredReadmissionOfAChangedFileHandsOverOnceAsJobCommitsIt",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RefusedRecoveryRetiredAdmissionTest.java#aReenteredCleanupQueuesNoDuplicate",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RefusedRecoveryRetiredAdmissionTest.java#aRetiredUpdateOfADeletedFileHandsOverOnceARemovesIt",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#pendingFencedRootWalkHandsOverOnceAsWriterCommitsIt",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#aLaterFinishedAdmissionWaitsForTheAJobItQueues",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#aPendingLaterAdmissionHandsOverOnceACommitsIt",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#aRetiredLaterAdmissionWaitsForTheAJobItQueues"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RefusedRecoveryRetiredAdmissionTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java"
      ],
      "class": "New",
      "behaviour": "Round 5 preservation, passing on a26eaf3e3 and after. A retired re-admission of an unchanged file whose bytes A already holds is handed to A at once. M8: an accepted update whose bytes A holds, and which are the file's current bytes, is handed to A at once also after a later admission of the file moved the row's revision on and A's writer found the file unchanged (DONE, UNCHANGED, nothing written); an intermediate round-5 state that required the row's exact revision re-queued such a file forever on the real Engine. A FENCED recovery boot whose refused walk is still open (unsealed) does not start the producer's writer and keeps Green. Refusal cleanup hands the journal UPSERT to A and completes (IDLE, journal empty, restart requested) when a later admission's writer committed exactly the bytes A holds, also when that later captured revision's bytes changed after its plan.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-a5fbcb",
          "scenario": "M1",
          "quote": "during refusal cleanup, hand-over of an accepted update or delete requires A's committed effect: A's index holds that revision, a later revision from a later admission, or the removal. A pending A job, the one cleanup queues or any other, no longer authorizes acknowledgement. It only means \"wait\".",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-a5fbcb",
          "scenario": "M1",
          "quote": "Everything else stays: queue A's missed update idempotently, the 60 s bound, fail-closed (keep Green and the journal) when the bound expires, and nothing deleted on uncertainty.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/migration-recovery-r5-preserve.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RefusedRecoveryRetiredAdmissionTest.java#aRetiredReadmissionOfAnUnchangedFileIsHandedToA",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RefusedRecoveryRetiredAdmissionTest.java#anAcceptedUpdateAHoldsSettlesAfterALaterAdmissionFoundItUnchanged",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#aFencedBootKeepsItsWriterStoppedWhileTheRefusedWalkIsOpen",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#aLaterAdmissionCommittedOnAHandsTheUpdateToA",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#aLaterRevisionCommittedOnAWithChangedBytesHandsTheUpdateToA"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RefusedRecoveryRetiredAdmissionTest.java"
      ],
      "class": "New",
      "behaviour": "Round 6, failing on 5bb3ff9ec and passing after. Refusal cleanup judges A's committed effect from A's index: an accepted update settles when A holds the file's current bytes, from the accepted revision or a later one, also when a rescan left the row DONE/UNCHANGED with no content hash (M8: the saved FAILED/RUNNING store after one restart, and a later revision A durably holds). A terminal outcome of A's normal policy for the file's current state settles it the way it would outside a migration: a file replaced by a link (NON_REGULAR_SOURCE) and a file too large (BUDGET_EXCEEDED) are admitted once and settle. Cleanup admits a path at most once per file state: an outcome that settles nothing is not re-admitted until the file changes or the process restarts.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-a5fbcb",
          "scenario": "M1",
          "quote": "A's committed effect is judged from A's index, not from the queue row. The obligation is settled when A's index holds content for the path that matches the file's current bytes on disk, from the accepted revision or any later one. A missing queue hash or an UNCHANGED outcome must not stop that check. If A holds the current bytes, the obligation is settled.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-a5fbcb",
          "scenario": "M1",
          "quote": "A terminal outcome by A's normal policy settles the obligation the same way it would outside a migration. Examples: non-regular source, unsupported or excluded type, too large.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-a5fbcb",
          "scenario": "M1",
          "quote": "At most one cleanup admission per path and current file state. Never admit again for the same state. Re-admit only after the file changes (a watcher event or a changed stat or hash) or after a restart.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/migration-recovery-r6-repaired.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RefusedRecoveryRetiredAdmissionTest.java#aLaterRevisionAHoldsSettlesThoughARescanFoundItUnchanged",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RefusedRecoveryRetiredAdmissionTest.java#aSavedFailedRunningStoreSettlesWhereAHoldsTheCurrentBytes",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RefusedRecoveryRetiredAdmissionTest.java#aSymlinkReplacementAdmitsOnceAndSettlesByAsPolicy",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RefusedRecoveryRetiredAdmissionTest.java#aTooLargeFileSettlesByAsPolicy",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RefusedRecoveryRetiredAdmissionTest.java#anUnsettledOutcomeIsAdmittedOncePerFileState"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RefusedRecoveryRetiredAdmissionTest.java"
      ],
      "class": "New",
      "behaviour": "Round 6 preservation, passing on 5bb3ff9ec and after. A's job for the accepted file ends in an I/O failure: that is no effect, so refusal cleanup keeps Green and the journal over repeated attempts and queues nothing more.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-a5fbcb",
          "scenario": "M1",
          "quote": "Failure outcomes (I/O error, unreadable, a crash) never settle; they wait, as now.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/migration-recovery-r6-preserve.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RefusedRecoveryRetiredAdmissionTest.java#anIoFailedFileStillWaits"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RefusedRecoveryRetiredAdmissionTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopTest.java"
      ],
      "class": "New",
      "behaviour": "Round 7, failing on 18a242439 and passing after. Refusal cleanup settles an ordinary file only when A's index holds the file's current bytes: a DONE row for earlier bytes never settles changed bytes that cannot be read (the file gets its one admission, which fails, and cleanup stays fail-closed); likewise a retired update of a file made unreadable gets that one admission and keeps its journal, replacing the round-4 expectation that nothing is queued for it). A path that is no longer a regular file settles on A's policy-terminal outcome for that state only once A no longer serves the replaced text; an outcome that leaves A serving it does not settle. While a migration projects writes to A, a source the loop retires as missing or non-regular (folder, link) is deleted from A as well as from Green, as the normal product deletes it.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-a5fbcb",
          "scenario": "M1",
          "quote": "Only a comparison of A's index with the current bytes can settle an ordinary file. If the current bytes can't be read, nothing settles it. A DONE row for earlier bytes never does. The file gets its one admission for the current state, and that fails I/O, so it stays fail-closed until the file is readable.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-a5fbcb",
          "scenario": "M1",
          "quote": "The file's state includes its kind. For a path that is no longer a regular file, A's policy-terminal outcome for that current state settles it, with no bytes needed. A must not keep serving the replaced file's old content, unless the normal product (outside migration) also keeps it for a path that became a directory.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-a5fbcb",
          "scenario": "M1",
          "quote": "A delete settles once A no longer holds the file. The deleted state is a file state too, so give it its one admission (A's delete); it settles once A's index no longer holds the path.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/migration-recovery-r7-repaired.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RefusedRecoveryRetiredAdmissionTest.java#anEarlierCommitNeverSettlesChangedBytesThatCannotBeRead",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RefusedRecoveryRetiredAdmissionTest.java#aRetiredUpdateOfAnUnreadableFileStaysUnproved",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RefusedRecoveryRetiredAdmissionTest.java#aFolderReplacementSettlesOnceAsPolicyRetiresTheOldText",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RefusedRecoveryRetiredAdmissionTest.java#aPolicyOutcomeThatLeavesAServingTheReplacedTextDoesNotSettle",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopTest.java#missingSourceRetiresTheActiveGenerationsTextToo",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopTest.java#nonRegularSourceRetiresTheActiveGenerationsTextToo"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerRecordedIngestionTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/SwitchBufferStrictReplayTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-a5fbcb",
          "scenario": "M1",
          "quote": "Only a comparison of A's index with the current bytes can settle an ordinary file. If the current bytes can't be read, nothing settles it. A DONE row for earlier bytes never does.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "conflictSearch": "Task t-20261020-a5fbcb decisions searched: round 6 decision 1 (A's effect is judged from A's index) and the round-7 restatement agree; none keeps the queue-row witness. The removed expectation is that refusal cleanup consults the queue's accepted-file projection (matchesAcceptedFileProjection) and settles on that captured witness in refusalTransfersAcceptedMutationBeforeAbandoningCandidate, refusalRetiresOnlySettledAcceptedUpsertAfterSourceProjectionIsProved and capturedReplayRefreshesBeforeCertifyingPlannedH1AndIndexedH2. Their other expectations (fence and producer order, refresh before certification, removal only after the proof, the planned H1 never rewritten) are kept."
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerRecordedIngestionTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/SwitchBufferStrictReplayTest.java"
      ],
      "class": "New",
      "behaviour": "Round 7. Refusal cleanup retires an accepted upsert only after reading A's committed SOURCE_SHA256 for the path and finding it equal to the file's current bytes; a captured replay whose file holds the indexed H2 bytes is retired after the refresh and that read, and queues no A job. The KnowledgeServerRecordedIngestionTest check names the whole class because its changed test (refusalTransfersAcceptedMutationBeforeAbandoningCandidate) carries a @DisplayName, which the JUnit report uses in place of the method name.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-a5fbcb",
          "scenario": "M1",
          "quote": "Only a comparison of A's index with the current bytes can settle an ordinary file. If the current bytes can't be read, nothing settles it. A DONE row for earlier bytes never does.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-a5fbcb",
          "scenario": "M1",
          "quote": "A's committed effect is judged from A's index, not from the queue row. The obligation is settled when A's index holds content for the path that matches the file's current bytes on disk, from the accepted revision or any later one.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/migration-recovery-r7-changed.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerRecordedIngestionTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/SwitchBufferStrictReplayTest.java#refusalRetiresOnlySettledAcceptedUpsertAfterSourceProjectionIsProved",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/SwitchBufferStrictReplayTest.java#capturedReplayRefreshesBeforeCertifyingPlannedH1AndIndexedH2"
      ]
    }
  ]
}
```
