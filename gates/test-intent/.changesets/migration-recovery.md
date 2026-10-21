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
fail there by assertion, the preserved ones pass there and after. The queue-ownership check
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
      "behaviour": "On the real SQLite queue, a refused candidate's accepted file admission is handed to A only by A's committed effect: a PENDING row of the file, or a later finished (DONE) admission whose planned and committed source SHA-256 equal the SOURCE_SHA256 A holds. No row, a DONE row of the accepted revision itself, a PROCESSING later row, a DONE later row while A holds other bytes, and a DONE later row while A lacks the file are not handed over. This is a query the change adds, so there is no before state.",
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
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/QueueAdmissionOwnershipTest.java#onlyAPendingRowOrACommittedLaterAdmissionHandsTheFileToTheSource"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Round 2, judged against 02d503739. (1) In SWITCHING with an embedding model, an empty candidate whose only journal obligation is an already-applied DELETE_COLLECTION of mcp-ingest is refused by the real cutover monitor within 5 s, with A still active (before: it stays SWITCHING). (2) On the real SQLite queue, refusal cleanup keeps Green and the journal UPSERT when a later admission of the file is DONE but A still holds other bytes and nothing is pending (before: the UPSERT is acknowledged, the journal empties). (3) A latched candidate is refused at the cutover monitor's first pass, without waiting for its queue or enumeration: in SWITCHING with 5,000 queued rows and a claimed row, and in MIGRATING with the enumerator still running and 5,000 queued rows, the pointer is FAILED within 5 s with A active (before: SWITCHING or MIGRATING holds). A latched SWITCHING candidate with 5,000 queued rows and an in-flight mutation lease stays SWITCHING while the lease is held, the runtime swap lock stays acquirable within 1 s throughout, and it is FAILED within 5 s of the lease's release (before: it stays SWITCHING). (4) When the Engine records the refusal only on its sixth query, the monitor's settle loop completes the cleanup within 10 s, IDLE with only A, without the reaper (before: the pointer stays FAILED).",
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
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java#aLaterFinishedAdmissionDoesNotHandAnUnappliedUpdateToA",
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
    }
  ]
}
```
