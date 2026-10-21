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
c44f281a0) and fail there by assertion; the round-3 preserved one passes there and after. Round 4 (decision (b): cleanup queues an A job) is judged against the round-3 state, c44f281a0 with its uncommitted main-code diff applied as the before-patch gates/test-intent/evidence/migration-recovery-round3.patch; it changed the expected outcome of three earlier repaired checks, which were renamed and re-run against their own before states. The queue-ownership check
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
      "behaviour": "A recorded candidate the Engine refused, in a FAILED pointer, with one accepted file journal UPSERT: with the producer's watcher-uncertainty latch set (as a restart's root re-registration sets it), or with 40 PENDING queue rows from a FENCED boot's root walk whose revision superseded the journal's, refusal cleanup settles the journal on A, acknowledges it, abandons and physically deletes B, and replaces the producer: the pointer is IDLE with A active, no building or previous generation, and only A's directory under indices/ (before: the pointer stays FAILED and B is kept). Cleanup stopped by a harness cut after A's source commit, after the journal acknowledgement, after abandonment or after physical deletion leaves the restart undone; a later cleanup attempt by a fresh producer reaches the same IDLE, A-only state (before: none of the four seams exists). In SWITCHING, the real cutover monitor fails the pointer (FAILED, A still active) within 5 s when the producer's latch is set, or when the embedding model is configured and the candidate is provably empty: walk COMPLETE and captured with 0 planned units, enumeration done, no projection sources, no pending or claimed rows, no journal obligation for B other than removals, and 0 documents (before: it stays SWITCHING).",
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
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#pendingFencedRootWalkDoesNotBlockRollback",
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
      "behaviour": "On the real SQLite queue, a refused candidate's accepted file admission is handed to A only by A's committed effect: a PENDING row of the file, or a finished (DONE) row, of the accepted admission or a later one, whose writer committed a content SHA-256 equal to the SOURCE_SHA256 A holds, even when a later admission's captured plan names other bytes. No row, a PROCESSING later row, a DONE row while A holds other bytes, a DONE row while A lacks the file, and a DONE row that wrote nothing (no committed hash, as retirement or refusal leaves it, for the accepted revision or a later one) while its plan names A's bytes are not handed over. This is a query the change adds, so there is no before state.",
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
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/QueueAdmissionOwnershipTest.java#onlyAPendingRowOrACommittedWriteHandsTheFileToTheSource"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Round 2, judged against 02d503739. (1) In SWITCHING with an embedding model, an empty candidate whose only journal obligation is an already-applied DELETE_COLLECTION of mcp-ingest is refused by the real cutover monitor within 5 s, with A still active (before: it stays SWITCHING). (2) On the real SQLite queue, when a later admission of the file is DONE but A still holds other bytes and nothing is pending, refusal cleanup acknowledges the journal UPSERT, requests the restart and reaches IDLE only with an ordinary PENDING A job for the file (new revision, no scan, no planned hash) that refusal cleanup queued (before: the UPSERT is acknowledged while the row stays DONE and nothing will bring the update to A). Decision (b) of round 4 replaced round 2's 'keeps Green'. (3) A latched candidate is refused at the cutover monitor's first pass, without waiting for its queue or enumeration: in SWITCHING with 5,000 queued rows and a claimed row, and in MIGRATING with the enumerator still running and 5,000 queued rows, the pointer is FAILED within 5 s with A active (before: SWITCHING or MIGRATING holds). A latched SWITCHING candidate with 5,000 queued rows and an in-flight mutation lease stays SWITCHING while the lease is held, the runtime swap lock stays acquirable within 1 s throughout, and it is FAILED within 5 s of the lease's release (before: it stays SWITCHING). (4) When the Engine records the refusal only on its sixth query, the monitor's settle loop completes the cleanup within 10 s, IDLE with only A, without the reaper (before: the pointer stays FAILED).",
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
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#aLaterFinishedAdmissionQueuesTheUnappliedUpdateOnA",
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
      "behaviour": "Round 2 preservation, passing on 02d503739 and after. In SWITCHING with an embedding model, a zero-document candidate with one PROJECTION journal obligation is not refused and stays SWITCHING for 1.5 s. An unlatched MIGRATING candidate whose enumerator still runs stays MIGRATING for 1.5 s. On the real SQLite queue, refusal cleanup hands the journal UPSERT to A and completes (IDLE, journal empty, restart requested) when a later admission of the file is PENDING, or when it is DONE and A holds exactly its bytes.",
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
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#aPendingLaterAdmissionHandsTheUpdateToA",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#aLaterAdmissionCommittedOnAHandsTheUpdateToA"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Round 3, judged against c44f281a0, on the real SQLite queue. (1) When the file's later row is DONE without a committed content hash (retired or refused without writing) and its captured plan names the bytes A still holds, with nothing pending, refusal cleanup acknowledges the journal UPSERT, requests the restart and reaches IDLE only with an ordinary PENDING A job for the file (new revision, no scan, no planned hash) that refusal cleanup queued (before: the UPSERT is acknowledged while the retired row stays DONE). Decision (b) of round 4 replaced round 3's 'keeps Green'. (2) Refusal cleanup hands the journal UPSERT to A and completes (IDLE, journal empty, restart requested) when the file's later row is DONE with a committed content hash equal to A's SOURCE_SHA256 while its captured plan names other, earlier bytes (before: Green and the UPSERT are kept).",
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
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#aRetiredLaterAdmissionQueuesTheUnappliedUpdateOnA",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#aLaterRevisionCommittedOnAWithChangedBytesHandsTheUpdateToA"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RefusedRecoveryRetiredAdmissionTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Round 3, judged against c44f281a0, on the real SQLite queue and a real Lucene A. A captured walk enqueued the file with the bytes A holds; the file then changed and a watcher update was accepted on Green (its journal witness is the captured plan's hash); the Engine retired the refused walk's rows without writing. Refusal cleanup acknowledges the journal UPSERT only with an ordinary PENDING A job for the file that it queued (no scan, no walk epoch, no planned hash), also when a later enumeration re-admitted the changed file before retirement (before: the UPSERT is acknowledged while the retired row stays DONE, so nothing will write the new bytes to A). Round 4's decision (b) replaced round 3's 'keeps the journal'.",
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
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RefusedRecoveryRetiredAdmissionTest.java#aRetiredWatcherUpdateIsQueuedOnA",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RefusedRecoveryRetiredAdmissionTest.java#aRetiredReadmissionOfAChangedFileIsQueuedOnA"
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
      "behaviour": "Round 4, judged against the round-3 state (c44f281a0 plus gates/test-intent/evidence/migration-recovery-round3.patch, its uncommitted main-code diff). With an accepted file journal UPSERT that A does not hold and no pending row (the Engine retired the walk's row unwritten), one refusal cleanup acknowledges the journal, requests the restart and reaches IDLE, leaving one ordinary PENDING A job for the file (a new revision, no scan, no walk epoch, no planned hash) that A's writer runs after that restart; also when a later enumeration re-admitted the changed file. A cleanup cut after queuing and before the journal acknowledgement keeps the journal; re-entered, it acknowledges and leaves the same revision (no second job). A file deleted from disk gets the same ordinary A job, which A's writer turns into the missing-source removal (before, every case: cleanup fails, the journal is kept and Green retained until a later restart).",
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
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#aRetiredLaterAdmissionQueuesTheUnappliedUpdateOnA",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#aLaterFinishedAdmissionQueuesTheUnappliedUpdateOnA",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RefusedRecoveryRetiredAdmissionTest.java#aRetiredWatcherUpdateIsQueuedOnA",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RefusedRecoveryRetiredAdmissionTest.java#aRetiredReadmissionOfAChangedFileIsQueuedOnA",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RefusedRecoveryRetiredAdmissionTest.java#aReenteredCleanupQueuesNoDuplicate",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RefusedRecoveryRetiredAdmissionTest.java#aRetiredUpdateOfADeletedFileQueuesItsRemovalOnA"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RefusedRecoveryRetiredAdmissionTest.java"
      ],
      "class": "New",
      "behaviour": "Round 4 preservation, passing on the round-3 state and after. A file made unreadable before refusal cleanup keeps the safe state: cleanup reports the source unproved, the journal UPSERT is kept and no job is queued.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-a5fbcb",
          "scenario": "M1",
          "quote": "A file unreadable at queue time keeps the operation in its safe state (old index active, journal kept), never acknowledged.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/migration-recovery-r4-preserve.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RefusedRecoveryRetiredAdmissionTest.java#aRetiredUpdateOfAnUnreadableFileStaysUnproved"
      ]
    }
  ]
}
```
