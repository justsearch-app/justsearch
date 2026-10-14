---
schema: test-intent.v1
task: t-20261013-ef3c41
author-role: builder
author-session: aae08035fcc266bae
---

# Test-intent entries

Task t-20261013-ef3c41: JustSearch never deletes indexed content, and never approves an index as compatible,
when it cannot tell. Repaired checks fail on cf3f14655 by assertion (evidence
`gates/test-intent/evidence/ef3c41-uncertain-deletes-repaired.json`). Must-not-break checks pass before and after (`gates/test-intent/evidence/ef3c41-uncertain-deletes-preserved.json`, run with
`--before cf3f14655`, where each records `passed` on the before-state by design). Checks of code
new in this change run on the candidate only (`gates/test-intent/evidence/ef3c41-uncertain-deletes-after-only.json`).

Round 2 (repairs on top of bf41272f9): the unknown document count (S5) and the unreadable rebuild brake.
Its repaired checks fail on bf41272f9 by assertion (`gates/test-intent/evidence/ef3c41-uncertain-deletes-r2-repaired.json`;
the UI ones in `gates/test-intent/evidence/ef3c41-uncertain-deletes-r2-ui-repaired.json`), and its must-not-break checks pass on
bf41272f9 and on the candidate (`gates/test-intent/evidence/ef3c41-uncertain-deletes-r2-preserved.json`,
`gates/test-intent/evidence/ef3c41-uncertain-deletes-r2-ui-preserved.json`).

Round 3 (on top of 9f9c1da16): a lost brake state, a held boot that stays shown, and every automatic
rebuild consulting the brake. Its repaired checks fail on 9f9c1da16 by assertion
(`gates/test-intent/evidence/ef3c41-uncertain-deletes-r3-repaired.json`); its must-not-break checks pass on 9f9c1da16 and
on the candidate (`gates/test-intent/evidence/ef3c41-uncertain-deletes-r3-preserved.json`). IndexRebuildBrakeTest was
edited in round 3, so its round 2 checks were re-run against bf41272f9
(`gates/test-intent/evidence/ef3c41-uncertain-deletes-r2b-repaired.json`, `gates/test-intent/evidence/ef3c41-uncertain-deletes-r2b-preserved.json`).

Round 4 (on top of 6b15a8153): one root-identity check for every deletion path and the root's walk (S8), a prune that keeps what it cannot judge and goes on (P1), and S7/S9 checks through entry points that exist on cf3f14655. Its repaired checks fail on 6b15a8153 by assertion (`gates/test-intent/evidence/ef3c41-uncertain-deletes-r4-repaired.json`); its must-not-break checks pass on 6b15a8153 and on the candidate (`gates/test-intent/evidence/ef3c41-uncertain-deletes-r4-preserved.json`). The S7/S9 checks run against cf3f14655 (`gates/test-intent/evidence/ef3c41-uncertain-deletes-r4-s7s9-repaired.json`) and against 6b15a8153 (`gates/test-intent/evidence/ef3c41-uncertain-deletes-r4-s7s9-on-r3.json`).

Round 5 (on top of 09a19e033): every path that adds or updates documents under a root asks the same root check as the deleting paths, so another volume at a root's drive letter never puts its files in the place of the original's, and the refused root reads unavailable at once (S8). Its repaired checks fail on 09a19e033 by assertion (`gates/test-intent/evidence/ef3c41-uncertain-deletes-r5-repaired.json`); its must-not-break check passes on 09a19e033 and on the candidate (`gates/test-intent/evidence/ef3c41-uncertain-deletes-r5-preserved.json`).

Round 6 (on top of 5a7e266a5): the reindex button's walk records a refused root at once, every periodic cycle asks each root's volume, the boot-root reconcile of a migration cutover goes through the same volume check as every other watch registration, and a restart while a root is unavailable keeps its last-indexed time (S8). Its repaired checks fail on 5a7e266a5 by assertion (`gates/test-intent/evidence/ef3c41-uncertain-deletes-r6-repaired.json`); the check of the Worker's new volume check runs on the candidate only (`gates/test-intent/evidence/ef3c41-uncertain-deletes-r6-after-only.json`). EngineRootVolumeSwapTest was edited in round 6, so its round 5 checks were re-run against 09a19e033 (`gates/test-intent/evidence/ef3c41-uncertain-deletes-r5b-repaired.json`, `gates/test-intent/evidence/ef3c41-uncertain-deletes-r5b-preserved.json`).

Round 7 (on top of 5c975db88): a boot that still has the previous generation defers a root on another volume instead of failing start-up, keeps the previous generation until that root is reconciled on its returned volume, and finishes the removal without a restart (S8). Its repaired checks fail on 5c975db88 by assertion (`gates/test-intent/evidence/ef3c41-uncertain-deletes-r7-repaired.json`). Every SUBST test now holds a machine-wide per-letter lock from mapping to unmapping, and PruneMissingDifferentVolumeTest finds its second volume like the others, so their earlier checks were re-run against the same refs as before: cf3f14655 (`gates/test-intent/evidence/ef3c41-uncertain-deletes-r7-relock-cf3f-repaired.json`, `gates/test-intent/evidence/ef3c41-uncertain-deletes-r7-relock-cf3f-preserved.json`), 6b15a8153 (`gates/test-intent/evidence/ef3c41-uncertain-deletes-r7-relock-r4-repaired.json`, `gates/test-intent/evidence/ef3c41-uncertain-deletes-r7-relock-r4-preserved.json`), 09a19e033 (`gates/test-intent/evidence/ef3c41-uncertain-deletes-r7-relock-r5-repaired.json`, `gates/test-intent/evidence/ef3c41-uncertain-deletes-r7-relock-r5-preserved.json`), 5a7e266a5 (`gates/test-intent/evidence/ef3c41-uncertain-deletes-r7-relock-r6-repaired.json`), and on the candidate only (`gates/test-intent/evidence/ef3c41-uncertain-deletes-r7-relock-after-only.json`).

Round 8 (on top of ef205d0e9): a deferred root the person removes is dropped from the deferred-root check before anything else, is never reconciled, watched or indexed, and counts as resolved for removing the previous generation; the deferred-root check never records a volume; and removal clears what else kept the root: the watcher's not-yet-run reconcile, the periodic cycle's volume ask and the Head's availability answer (S8). Its repaired checks fail on ef205d0e9 by assertion (`gates/test-intent/evidence/ef3c41-uncertain-deletes-r8-repaired.json`). Refined decision 2 of round 8: only the person's add records a root's volume, every other path refuses a root with none recorded, an upgrade from a version without the record confirms each root once on evidence, and a profiling reset removes each root as the person's removal does. Its repaired checks fail on ef205d0e9 by assertion (`gates/test-intent/evidence/ef3c41-uncertain-deletes-r8-volume-repaired.json`). The tests that recorded a root's volume through its first walk, prune or registration now add the root first (Adaptation entries), so their earlier evidence was re-run against the same refs, written to the same files.

Round 8b (on top of ef205d0e9, decisions under the owner's delegation): only a deliberate add records a volume (the person's add of a root, or an explicit ingest request of a folder or of files, carrying the add marker), no walk ever records one, so a walk before the roots are registered again after a restart is deferred and deletes nothing, and a configured collection is seeded once by the upgrade rule and otherwise reads unavailable with nothing deleted (S8). Its repaired checks fail on ef205d0e9 by assertion (`gates/test-intent/evidence/ef3c41-uncertain-deletes-r8b-repaired.json`); its must-not-break check passes on ef205d0e9 and on the candidate (`gates/test-intent/evidence/ef3c41-uncertain-deletes-r8b-preserved.json`). Tests that queued an explicit ingest or deleted through the watcher without the record a deliberate add makes now make it first (Adaptation entries); evidence naming an edited test was re-run against the same refs, written to the same files.

```json
{
  "entries": [
    {
      "items": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/PruneUncertainAbsenceTest.java"
      ],
      "class": "New",
      "behaviour": "A prune removes a document only when its file is reported not found while the same root is present, checked per batch: a root renamed away before the prune or between batches, or a SUBST drive letter unmapped like a pulled stick, removes nothing. A stored path this system cannot parse is kept and no longer throws out of the prune, so the genuine deletions under the same root still happen in that run (P1; before the change one such path halted every deletion under the root, run after run).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S2",
          "quote": "Pruning, from every caller (periodic sync, prune-first reindex with exclude rules or force, migration replay of a buffered prune), removes a document only when its file is reported not found while the same root is present, re-checked during the prune; if the root or a subtree becomes unreadable mid-prune, nothing more is removed and the run reports itself incomplete.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S8",
          "quote": "A root that becomes unreadable or unavailable (expired share credentials, VPN drop, unplugged drive, root folder renamed or moved) keeps its documents and shows one root-level unavailable or unreadable state rather than thousands of per-file failures, until the person removes the root; a drive returning under another letter, or a different volume at the same letter, does not remove the original's documents.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "P1",
          "quote": "A genuinely deleted file is removed with its chunks: immediately on a confirmed watcher delete, and within one sync cycle once its root is reachable (including files deleted while their root was offline).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r7-relock-cf3f-repaired.json",
      "newChecks": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/PruneUncertainAbsenceTest.java#aRootRenamedAwayBeforeThePruneLosesNoDocuments",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/PruneUncertainAbsenceTest.java#aRootRenamedAwayMidPruneStopsTheRemovals",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/PruneUncertainAbsenceTest.java#anUnmappedDriveLetterLosesNoDocuments",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/PruneUncertainAbsenceTest.java#aStoredPathThisSystemCannotParseDoesNotHaltGenuineDeletions"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/PruneUncertainAbsenceTest.java"
      ],
      "class": "New",
      "behaviour": "Must not break: a genuinely deleted file under a present root is pruned in one run.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "P1",
          "quote": "A genuinely deleted file is removed with its chunks: immediately on a confirmed watcher delete, and within one sync cycle once its root is reachable (including files deleted while their root was offline).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r7-relock-cf3f-preserved.json",
      "newChecks": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/PruneUncertainAbsenceTest.java#aGenuinelyDeletedFileIsStillPrunedInOneRun"
      ]
    },
    {
      "items": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/UnreadableFingerprintParityTest.java"
      ],
      "class": "New",
      "behaviour": "An unparseable stored index fingerprint is an unanswered question, not a difference that triggers a rebuild of an index that holds documents.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S6",
          "quote": "An unparseable stored index fingerprint makes status say unknown; the index opens, is never reported compatible, and nothing rebuilds automatically.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-repaired.json",
      "newChecks": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/UnreadableFingerprintParityTest.java#anUnparseableStoredFingerprintIsNotARebuildingDifference"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/UnreadableFingerprintParityTest.java"
      ],
      "class": "New",
      "behaviour": "Must not break: a readable, different fingerprint still requires migration.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "P2",
          "quote": "Genuine index corruption still auto-recovers as before; a genuine schema mismatch still migrates; the identity grace clock still starts only on confirmed absence; candidate and boot reconciliation behave as before.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-preserved.json",
      "newChecks": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/UnreadableFingerprintParityTest.java#aReadableDifferentFingerprintStillRequiresMigration"
      ]
    },
    {
      "items": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/RecoveryIntegrationTest.java"
      ],
      "class": "New",
      "behaviour": "An index segment held open without sharing at startup (a real sharing violation, as an antivirus or backup tool causes) is not classified as corruption: the index is not backed up and replaced, and it opens once the lock is gone. The file's existing tests are unchanged; two of them (corruption auto-recovery and backup-first schema-mismatch rebuild) are re-run before and after as P2 evidence in the preserved evidence file.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S4",
          "quote": "A transient read error on the index folder at startup never discards the index: the app reports the index unavailable and retries instead of backing it up and re-indexing from scratch.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "P2",
          "quote": "Genuine index corruption still auto-recovers as before; a genuine schema mismatch still migrates; the identity grace clock still starts only on confirmed absence; candidate and boot reconciliation behave as before.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-repaired.json",
      "newChecks": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/RecoveryIntegrationTest.java#aSharingViolationOnTheIndexAtStartupNeverDiscardsTheIndex"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/PruneByPathPrefixTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S2",
          "quote": "Pruning, from every caller (periodic sync, prune-first reindex with exclude rules or force, migration replay of a buffered prune), removes a document only when its file is reported not found while the same root is present, re-checked during the prune; if the root or a subtree becomes unreadable mid-prune, nothing more is removed and the run reports itself incomplete.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. docs/decisions/*.md searched for prune, orphan, root, unreadable, watcher delete and fingerprint; ADR-0028 (scoped reverse path lookup) governs path_resolution retention, not document pruning, and no record states that a prune of a missing folder removes its documents.",
      "whatChanged": "Two tests (pruningDivergentOrphanChunk... and pruningUnmarkedOrphan...) relied, without asserting it, on a prune of a folder that did not exist on disk removing the documents under it. S2 makes that removal wrong: absence is evidence only while the root is present. The tests now create the pruned folder, so their own expectations (which chunk is and is not pruned) are judged as before; their assertions are unchanged."
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/UnreadableSourceRetryTest.java"
      ],
      "class": "New",
      "behaviour": "An unreadable source has a retry policy that never runs out: its back-off is counted and grows (never a fixed 1 s re-queue), keeps growing past the ordinary retry window without being marked exhausted, and the failed-files surface lists the item as \"indexed, source unreadable\" while it stays queued, including after the queue is reopened (a restart).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S7",
          "quote": "A file that stays unreadable is retried with counted, growing back-off (never re-extracted every second; the existing defer re-queues after a fixed 1 s without counting); its last indexed content stays searchable without a time limit; it is shown as \"indexed, source unreadable\" (an additive reason, distinct from missing from index) in the failed-files list and status; a restart keeps it queued and searchable.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-repaired.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/UnreadableSourceRetryTest.java#backOffIsCountedAndGrowsRatherThanRetryingEverySecond",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/UnreadableSourceRetryTest.java#anUnreadableSourceIsNeverExhaustedAndKeepsGrowingPastTheWindow",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/UnreadableSourceRetryTest.java#theFailedFilesSurfaceListsItAsIndexedSourceUnreadableAcrossARestart"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/PrunePrefixReplayUncertainRootTest.java"
      ],
      "class": "New",
      "behaviour": "The migration replay of a buffered prune removes nothing when the folder is not there (renamed away) and keeps the prune buffered for a later replay.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S2",
          "quote": "Pruning, from every caller (periodic sync, prune-first reindex with exclude rules or force, migration replay of a buffered prune), removes a document only when its file is reported not found while the same root is present, re-checked during the prune; if the root or a subtree becomes unreadable mid-prune, nothing more is removed and the run reports itself incomplete.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-repaired.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/PrunePrefixReplayUncertainRootTest.java#aBufferedPruneOfAMissingFolderRemovesNothingAndStaysBuffered"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/PrunePrefixReplayUncertainRootTest.java"
      ],
      "class": "New",
      "behaviour": "Must not break: the replay of a buffered prune of a present folder removes the genuinely deleted file and drains the buffer.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "P1",
          "quote": "A genuinely deleted file is removed with its chunks: immediately on a confirmed watcher delete, and within one sync cycle once its root is reachable (including files deleted while their root was offline).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-preserved.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/PrunePrefixReplayUncertainRootTest.java#aBufferedPruneOfAPresentFolderRemovesTheDeletedFile"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/PruneMissingDifferentVolumeTest.java"
      ],
      "class": "New",
      "behaviour": "A different volume mounted at the same drive letter while the app was not running (real SUBST mappings on two volumes, a new service over the same index as the restart) does not remove the original's documents, and the prune reports itself incomplete.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S8",
          "quote": "A root that becomes unreadable or unavailable (expired share credentials, VPN drop, unplugged drive, root folder renamed or moved) keeps its documents and shows one root-level unavailable or unreadable state rather than thousands of per-file failures, until the person removes the root; a drive returning under another letter, or a different volume at the same letter, does not remove the original's documents.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "The volume a root was first pruned from is recorded in a small file under the index base folder (root-volumes.properties) and checked by every later prune of that root, so a different volume at the same path after a restart removes nothing; a ledger that exists but cannot be read vouches for nothing; the record is dropped when the person removes the root, after which a prune on the new volume judges deletions there again.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r7-relock-cf3f-repaired.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/PruneMissingDifferentVolumeTest.java#aDifferentVolumeAtTheSameLetterAfterARestartPrunesNothing"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/PruneMissingDifferentVolumeTest.java"
      ],
      "class": "New",
      "behaviour": "Must not break: on the same volume after a restart a genuinely deleted file is still pruned; once the person removes the root, the same path on another volume is judged there, so a genuine deletion on the new volume is pruned.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "P1",
          "quote": "A genuinely deleted file is removed with its chunks: immediately on a confirmed watcher delete, and within one sync cycle once its root is reachable (including files deleted while their root was offline).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S8",
          "quote": "A root that becomes unreadable or unavailable (expired share credentials, VPN drop, unplugged drive, root folder renamed or moved) keeps its documents and shows one root-level unavailable or unreadable state rather than thousands of per-file failures, until the person removes the root; a drive returning under another letter, or a different volume at the same letter, does not remove the original's documents.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "The volume a root was first pruned from is recorded in a small file under the index base folder (root-volumes.properties) and checked by every later prune of that root, so a different volume at the same path after a restart removes nothing; a ledger that exists but cannot be read vouches for nothing; the record is dropped when the person removes the root, after which a prune on the new volume judges deletions there again.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r7-relock-cf3f-preserved.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/PruneMissingDifferentVolumeTest.java#theSameVolumeAfterARestartStillPrunesAGenuineDelete",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/PruneMissingDifferentVolumeTest.java#aRootRemovedAndAddedAgainOnAnotherVolumeIsPrunedThere"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/PruneMissingUncertainRootTest.java"
      ],
      "class": "New",
      "behaviour": "The prune-first reindex RPC (pruneMissing) removes nothing under a root that was renamed away after the walk was queued.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S2",
          "quote": "Pruning, from every caller (periodic sync, prune-first reindex with exclude rules or force, migration replay of a buffered prune), removes a document only when its file is reported not found while the same root is present, re-checked during the prune; if the root or a subtree becomes unreadable mid-prune, nothing more is removed and the run reports itself incomplete.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S8",
          "quote": "A root that becomes unreadable or unavailable (expired share credentials, VPN drop, unplugged drive, root folder renamed or moved) keeps its documents and shows one root-level unavailable or unreadable state rather than thousands of per-file failures, until the person removes the root; a drive returning under another letter, or a different volume at the same letter, does not remove the original's documents.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-repaired.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/PruneMissingUncertainRootTest.java#aRootRenamedAwayIsNotPruned"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/PruneMissingUncertainRootTest.java"
      ],
      "class": "New",
      "behaviour": "Must not break: a genuinely deleted file under a present root is pruned by the same RPC.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "P1",
          "quote": "A genuinely deleted file is removed with its chunks: immediately on a confirmed watcher delete, and within one sync cycle once its root is reachable (including files deleted while their root was offline).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-preserved.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/PruneMissingUncertainRootTest.java#aGenuinelyDeletedFileUnderAPresentRootIsPruned"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexedSourceUnreadableTest.java"
      ],
      "class": "New",
      "behaviour": "A source held open without sharing (a real no-share lock) whose content is indexed is reported with the additive reason \"indexed, source unreadable\", not as missing from the index. No fail-before: the check reads the outcome through the reason code this change adds, so it cannot compile at the base; the repaired part of S7 is pinned by UnreadableSourceRetryTest.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S7",
          "quote": "A file that stays unreadable is retried with counted, growing back-off (never re-extracted every second; the existing defer re-queues after a fixed 1 s without counting); its last indexed content stays searchable without a time limit; it is shown as \"indexed, source unreadable\" (an additive reason, distinct from missing from index) in the failed-files list and status; a restart keeps it queued and searchable.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-after-only.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexedSourceUnreadableTest.java#aLockedSourceWithIndexedContentIsReportedAsIndexedSourceUnreadable"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/UncertainAbsenceGatesTest.java"
      ],
      "class": "New",
      "behaviour": "Every check that can end in a deletion keeps the source when its absence is not confirmed: admission keeps a source whose drive letter is unmapped or whose root was renamed away; the pre-publish freshness check neither deletes the document nor ends the queue item when the root was renamed away, and retries it; a source that is access-denied (real deny ACL) or locked (real no-share open) is retried with a policy that has no time limit. A file replaced by a directory or by a dangling link is retired instead of being kept searchable or retried forever.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S1",
          "quote": "A transient read failure of a source file (access denied, sharing violation such as an antivirus lock, share or drive briefly unavailable) at any check that can end in a deletion (admission gate, pre-publish freshness check, post-extraction re-hash, writer pre-write check) never removes its indexed document or chunks and never marks its queue item done; the item is retried later.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S7",
          "quote": "A file that stays unreadable is retried with counted, growing back-off (never re-extracted every second; the existing defer re-queues after a fixed 1 s without counting); its last indexed content stays searchable without a time limit; it is shown as \"indexed, source unreadable\" (an additive reason, distinct from missing from index) in the failed-files list and status; a restart keeps it queued and searchable.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S8",
          "quote": "A root that becomes unreadable or unavailable (expired share credentials, VPN drop, unplugged drive, root folder renamed or moved) keeps its documents and shows one root-level unavailable or unreadable state rather than thousands of per-file failures, until the person removes the root; a drive returning under another letter, or a different volume at the same letter, does not remove the original's documents.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S9",
          "quote": "A source file replaced by a directory or a symlink (including a dangling one) no longer leaves its old text searchable and is not retried forever.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "The dangling-link case of S9 is reproduced with a directory junction whose target was removed, because creating a symbolic link on Windows needs a privilege the test machine does not grant; the walks never follow either kind of link.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r7-relock-cf3f-repaired.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/UncertainAbsenceGatesTest.java#admissionKeepsTheSourceWhenItsDriveLetterIsUnmapped",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/UncertainAbsenceGatesTest.java#admissionKeepsTheSourceWhenItsRootWasRenamedAway",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/UncertainAbsenceGatesTest.java#anAccessDeniedSourceIsRetriedWithoutATimeLimit",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/UncertainAbsenceGatesTest.java#aLockedSourceIsRetriedWithoutATimeLimit",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/UncertainAbsenceGatesTest.java#theFreshnessCheckKeepsTheDocumentWhenTheRootWasRenamedAway",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/UncertainAbsenceGatesTest.java#aFileReplacedByADirectoryIsRetired",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/UncertainAbsenceGatesTest.java#aFileReplacedByADanglingJunctionIsRetired"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/UncertainAbsenceGatesTest.java"
      ],
      "class": "New",
      "behaviour": "Must not break: admission and the freshness check still retire a genuinely deleted source under a present root.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "P1",
          "quote": "A genuinely deleted file is removed with its chunks: immediately on a confirmed watcher delete, and within one sync cycle once its root is reachable (including files deleted while their root was offline).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r7-relock-cf3f-preserved.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/UncertainAbsenceGatesTest.java#admissionStillRetiresAGenuinelyDeletedSource",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/UncertainAbsenceGatesTest.java#theFreshnessCheckStillRetiresAGenuinelyDeletedSource"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RootPresenceGuardTest.java"
      ],
      "class": "New",
      "behaviour": "The prune guard vouches for absence only while the root is the same present, listable folder: it stops vouching when the root is renamed away, never vouches for a root missing at capture or for a different folder mapped at the same letter, vouches for nothing when the volume record cannot be read, and forgets the recorded volume when the root is removed. No fail-before: the guard is new in this change; the repaired prune behaviour it serves is pinned by the prune checks above.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S2",
          "quote": "Pruning, from every caller (periodic sync, prune-first reindex with exclude rules or force, migration replay of a buffered prune), removes a document only when its file is reported not found while the same root is present, re-checked during the prune; if the root or a subtree becomes unreadable mid-prune, nothing more is removed and the run reports itself incomplete.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S8",
          "quote": "A root that becomes unreadable or unavailable (expired share credentials, VPN drop, unplugged drive, root folder renamed or moved) keeps its documents and shows one root-level unavailable or unreadable state rather than thousands of per-file failures, until the person removes the root; a drive returning under another letter, or a different volume at the same letter, does not remove the original's documents.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "The volume a root was first pruned from is recorded in a small file under the index base folder (root-volumes.properties) and checked by every later prune of that root, so a different volume at the same path after a restart removes nothing; a ledger that exists but cannot be read vouches for nothing; the record is dropped when the person removes the root, after which a prune on the new volume judges deletions there again.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r7-relock-after-only.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RootPresenceGuardTest.java#aPresentRootVouchesUntilItIsRenamedAway",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RootPresenceGuardTest.java#aRootMissingAtCaptureNeverVouches",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RootPresenceGuardTest.java#aDifferentFolderMappedAtTheSameLetterDoesNotVouch",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RootPresenceGuardTest.java#anUnreadableVolumeRecordVouchesForNothing",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RootPresenceGuardTest.java#aRecordedVolumeIsForgottenWhenTheRootIsRemoved"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SchemaCompatUnknownTest.java"
      ],
      "class": "New",
      "behaviour": "Status never reports compatible, 0 documents, legacy index or rebuild required on an unanswered question: an unreadable count with no recorded fingerprint, or with a different fingerprint, is unknown (not compatible); an unreadable count does not show 0 documents once a count has been read; an unparseable stored fingerprint is unknown, not rebuild required; stored metadata whose read fails is unknown, not a legacy index that needs a rebuild. The count and metadata failures are injected (a count source and a metadata source whose reads throw), because a real Lucene reader cannot be made to fail those reads on demand without damaging the index.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S5",
          "quote": "When the document count or stored index metadata cannot be read (including the startup window), status says checking or unknown; it never says compatible, never shows 0 documents, and never says legacy index or rebuild required. An index holding documents with a different fingerprint is never reported compatible.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S6",
          "quote": "An unparseable stored index fingerprint makes status say unknown; the index opens, is never reported compatible, and nothing rebuilds automatically.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-repaired.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SchemaCompatUnknownTest.java#anUnreadableCountWithNoRecordedFingerprintIsUnknownNotCompatible",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SchemaCompatUnknownTest.java#anUnparseableStoredFingerprintIsUnknownNotRebuildRequired",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SchemaCompatUnknownTest.java#anUnreadableCountWithADifferentFingerprintIsNeverCompatible",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SchemaCompatUnknownTest.java#anUnreadableCountNeverShowsZeroDocuments",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SchemaCompatUnknownTest.java#unreadableStoredMetadataIsUnknownNotLegacy"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SchemaCompatUnknownTest.java"
      ],
      "class": "New",
      "behaviour": "Must not break: an index holding documents with a readable, different fingerprint is still a mismatch (never compatible).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S5",
          "quote": "When the document count or stored index metadata cannot be read (including the startup window), status says checking or unknown; it never says compatible, never shows 0 documents, and never says legacy index or rebuild required. An index holding documents with a different fingerprint is never reported compatible.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "P2",
          "quote": "Genuine index corruption still auto-recovers as before; a genuine schema mismatch still migrates; the identity grace clock still starts only on confirmed absence; candidate and boot reconciliation behave as before.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-preserved.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SchemaCompatUnknownTest.java#aReadableDifferentFingerprintWithDocumentsIsStillAMismatch"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SearchableDocCountReaderSelectionTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "Status now reads the parent document count through the throwing count methods (docCountOrThrow, countByFieldOrThrow), so a failed read is unknown rather than 0. Both mocks answer those methods with the same values (12 and 2) they already gave the non-throwing forms. Inputs, the injected countQueryOrThrow failure, the observation and every expected value are unchanged."
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WatcherTransitionMatrixGuardTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The two DELETE cells (a present root forwards one delete of doc.txt; a cascade under a gone root adds none) are read after the watcher's delete settle window instead of immediately: both events are dispatched, the test waits up to 6 s for the forwarded delete plus 500 ms, then asserts exactly one delete ending in doc.txt. The test timeout rises from 10 s to 20 s for the wait. Inputs, the cells and the expected outcomes are unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S3",
          "quote": "A live watcher delete removes a document only after confirming the file is absent; an atomic save (Word) or a case-only rename never makes the document drop out of search; a file deleted and recreated within seconds shows the new content and is never missing.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "A watcher DELETE is applied only after a settle window of about 3 s in which the file must still be confirmed absent (not found while its watched root is present); a file that reappears within the window, as in an atomic save, a case-only rename or a delete-and-recreate, is never removed. Tests that observe a watcher delete therefore wait for it instead of reading the sink at once.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerMethvinWatcherTest.java"
      ],
      "class": "New",
      "behaviour": "A live watcher delete is applied only after the file is confirmed absent under a present root: an atomic save (real ATOMIC_MOVE replace), a case-only rename (real rename) or a delete followed by a recreate within seconds never removes the document, and a delete whose root vanished before confirmation is not applied.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S3",
          "quote": "A live watcher delete removes a document only after confirming the file is absent; an atomic save (Word) or a case-only rename never makes the document drop out of search; a file deleted and recreated within seconds shows the new content and is never missing.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S8",
          "quote": "A root that becomes unreadable or unavailable (expired share credentials, VPN drop, unplugged drive, root folder renamed or moved) keeps its documents and shows one root-level unavailable or unreadable state rather than thousands of per-file failures, until the person removes the root; a drive returning under another letter, or a different volume at the same letter, does not remove the original's documents.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "A watcher DELETE is applied only after a settle window of about 3 s in which the file must still be confirmed absent (not found while its watched root is present); a file that reappears within the window, as in an atomic save, a case-only rename or a delete-and-recreate, is never removed. Tests that observe a watcher delete therefore wait for it instead of reading the sink at once.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-repaired.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerMethvinWatcherTest.java#atomicSaveDeleteNotificationKeepsTheDocument",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerMethvinWatcherTest.java#caseOnlyRenameDeleteNotificationKeepsTheDocument",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerMethvinWatcherTest.java#deleteAndRecreateWithinSecondsIsNeverRemoved",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerMethvinWatcherTest.java#deleteIsKeptWhenTheRootVanishesBeforeConfirmation"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerMethvinWatcherTest.java"
      ],
      "class": "New",
      "behaviour": "Must not break: a genuine delete reaches the delete sink without any further event (after the settle window) and is not re-admitted.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "P1",
          "quote": "A genuinely deleted file is removed with its chunks: immediately on a confirmed watcher delete, and within one sync cycle once its root is reachable (including files deleted while their root was offline).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "A watcher DELETE is applied only after a settle window of about 3 s in which the file must still be confirmed absent (not found while its watched root is present); a file that reappears within the window, as in an atomic save, a case-only rename or a delete-and-recreate, is never removed. Tests that observe a watcher delete therefore wait for it instead of reading the sink at once.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-preserved.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerMethvinWatcherTest.java#genuineDeleteIsAppliedWithoutAnyFurtherEvent"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerMethvinWatcherTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "deleteForChildIsForwardedWhenRootExists waits (up to 10 s) for the forwarded delete instead of reading the sink at once, and still asserts exactly one delete of the child; deleteCascadeIsSkippedWhenWatchedRootIsGone waits out the settle window before asserting that no delete was forwarded. Inputs and expected outcomes are unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S3",
          "quote": "A live watcher delete removes a document only after confirming the file is absent; an atomic save (Word) or a case-only rename never makes the document drop out of search; a file deleted and recreated within seconds shows the new content and is never missing.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "A watcher DELETE is applied only after a settle window of about 3 s in which the file must still be confirmed absent (not found while its watched root is present); a file that reappears within the window, as in an atomic save, a case-only rename or a delete-and-recreate, is never removed. Tests that observe a watcher delete therefore wait for it instead of reading the sink at once.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/FailedJobsDrawer.test.ts"
      ],
      "class": "New",
      "behaviour": "In the failed-files list a row whose source cannot be read but whose content is indexed reads \"Indexed, source unreadable\" with a note that it stays searchable, never as missing or deleted and without an exhausted line; a source-unreadable row without indexed content reads \"Source unreadable\" without the searchable note. No fail-before: a vitest fail-before needs node_modules linked into the before worktree, which this task's rules forbid; the reason codes are new in this change.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S7",
          "quote": "A file that stays unreadable is retried with counted, growing back-off (never re-extracted every second; the existing defer re-queues after a fixed 1 s without counting); its last indexed content stays searchable without a time limit; it is shown as \"indexed, source unreadable\" (an additive reason, distinct from missing from index) in the failed-files list and status; a restart keeps it queued and searchable.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-after-only.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/FailedJobsDrawer.test.ts#an indexed file whose source cannot be read reads as \"indexed, source unreadable\", not missing (t-20261013-ef3c41 S7)"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/UnreadableRootAvailabilityTest.java"
      ],
      "class": "New",
      "behaviour": "A watched folder that exists but cannot be listed (a real deny ACE for listing on a folder the test creates) shows the one root-level unavailable state a missing folder shows, instead of reading as available while its files fail one by one.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S8",
          "quote": "A root that becomes unreadable or unavailable (expired share credentials, VPN drop, unplugged drive, root folder renamed or moved) keeps its documents and shows one root-level unavailable or unreadable state rather than thousands of per-file failures, until the person removes the root; a drive returning under another letter, or a different volume at the same letter, does not remove the original's documents.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-repaired.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/UnreadableRootAvailabilityTest.java#aRootThatCannotBeListedShowsTheRootLevelUnavailableState"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/UnreadableRootAvailabilityTest.java"
      ],
      "class": "New",
      "behaviour": "Must not break: a listable folder carries no root-level error.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S8",
          "quote": "A root that becomes unreadable or unavailable (expired share credentials, VPN drop, unplugged drive, root folder renamed or moved) keeps its documents and shows one root-level unavailable or unreadable state rather than thousands of per-file failures, until the person removes the root; a drive returning under another letter, or a different volume at the same letter, does not remove the original's documents.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-preserved.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/UnreadableRootAvailabilityTest.java#aListableRootStaysAvailable"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/UnknownCountAndUnreadableBrakeStatusTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/WorkerStatusMapperTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpResourcesReadTest.java"
      ],
      "class": "New",
      "behaviour": "Round 2: a document count that fails from the very first read (no earlier count to fall back on) is reported as unknown: the Worker sets doc_count_unknown, /api/status carries indexedDocumentsUnknown, and MCP's index summary says \"documents: unknown\" instead of \"documents: 0\". Fail-before against bf41272f9.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S5",
          "quote": "When the document count or stored index metadata cannot be read (including the startup window), status says checking or unknown; it never says compatible, never shows 0 documents, and never says legacy index or rebuild required. An index holding documents with a different fingerprint is never reported compatible.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "When the backend reports the document count as unknown (indexedDocumentsUnknown), the UI shows \"Checking…\" while the server's uptime is under 120 s and \"Unknown\" after, never 0; the count is UNKNOWN in the store, so nothing treats the index as empty (no \"no documents\" gap, document search stays offered, the chat preview names no number). The flag is additive: indexedDocuments keeps carrying 0 for older readers.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r2-repaired.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/UnknownCountAndUnreadableBrakeStatusTest.java#aCountThatFailsFromTheFirstReadIsReportedUnknown",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/WorkerStatusMapperTest.java#anUncountedIndexReachesStatusAsUnknown",
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpResourcesReadTest.java#anUncountedIndexIsSummarisedAsUnknownNotZeroDocuments"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/UnknownCountAndUnreadableBrakeStatusTest.java"
      ],
      "class": "New",
      "behaviour": "Round 2, must not break: a counted index keeps its count and is not marked unknown.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S5",
          "quote": "When the document count or stored index metadata cannot be read (including the startup window), status says checking or unknown; it never says compatible, never shows 0 documents, and never says legacy index or rebuild required. An index holding documents with a different fingerprint is never reported compatible.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r2-preserved.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/UnknownCountAndUnreadableBrakeStatusTest.java#aReadableCountIsNotReportedUnknown"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/JobBatchExtractorForcedPathTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusLifecycleHandlerTest.java"
      ],
      "class": "New",
      "behaviour": "Round 2: the emptiness decisions do not treat an unknown count as empty. When the index cannot be counted, the extractor keeps the unchanged-file check instead of treating the batch as the first fill of an empty index (so an unchanged file is not extracted and written again), and the Head's chunk-embedding readiness does not take the uncounted index for the empty corpus that has nothing to embed. Fail-before against bf41272f9.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S5",
          "quote": "When the document count or stored index metadata cannot be read (including the startup window), status says checking or unknown; it never says compatible, never shows 0 documents, and never says legacy index or rebuild required. An index holding documents with a different fingerprint is never reported compatible.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "When the backend reports the document count as unknown (indexedDocumentsUnknown), the UI shows \"Checking…\" while the server's uptime is under 120 s and \"Unknown\" after, never 0; the count is UNKNOWN in the store, so nothing treats the index as empty (no \"no documents\" gap, document search stays offered, the chat preview names no number). The flag is additive: indexedDocuments keeps carrying 0 for older readers.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r2-repaired.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/JobBatchExtractorForcedPathTest.java#anUnreadableCountKeepsTheUnchangedCheck",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusLifecycleHandlerTest.java#anUncountedIndexIsNotTreatedAsAnEmptyCorpus"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/uncountedIndex.test.ts",
        "modules/ui-web/src/shell-v0/views/UnifiedChatView.test.ts"
      ],
      "class": "New",
      "behaviour": "Round 2, UI: when /api/status reports indexedDocumentsUnknown, the files fact reads \"Checking…\" in the start-up window and \"Unknown\" after it, never 0; the store's counts are unknown, document search is not reported as \"no documents\", and the chat preview reads \"Searching your documents\" instead of \"Searching 0 documents\". Fail-before against bf41272f9 (vitest only; the before worktree is linked to this worktree's private node_modules and that run starts no Gradle).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S5",
          "quote": "When the document count or stored index metadata cannot be read (including the startup window), status says checking or unknown; it never says compatible, never shows 0 documents, and never says legacy index or rebuild required. An index holding documents with a different fingerprint is never reported compatible.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "When the backend reports the document count as unknown (indexedDocumentsUnknown), the UI shows \"Checking…\" while the server's uptime is under 120 s and \"Unknown\" after, never 0; the count is UNKNOWN in the store, so nothing treats the index as empty (no \"no documents\" gap, document search stays offered, the chat preview names no number). The flag is additive: indexedDocuments keeps carrying 0 for older readers.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r2-ui-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/uncountedIndex.test.ts#shows \"Checking…\" while the server is starting, never 0",
        "modules/ui-web/src/shell-v0/state/uncountedIndex.test.ts#shows \"Unknown\" once the start-up window has passed, never 0",
        "modules/ui-web/src/shell-v0/state/uncountedIndex.test.ts#is not treated as empty: the counts are unknown and document search is not \"no documents\"",
        "modules/ui-web/src/shell-v0/views/UnifiedChatView.test.ts#an index the backend could not count shows no number, never \"Searching 0 documents\" (t-20261013-ef3c41)"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/uncountedIndex.test.ts"
      ],
      "class": "New",
      "behaviour": "Round 2, UI, must not break: a known 0 is still the empty index and a known count is still shown.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S5",
          "quote": "When the document count or stored index metadata cannot be read (including the startup window), status says checking or unknown; it never says compatible, never shows 0 documents, and never says legacy index or rebuild required. An index holding documents with a different fingerprint is never reported compatible.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r2-ui-preserved.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/uncountedIndex.test.ts#a counted index is unchanged: a known 0 is still empty, a known count is still shown"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/UnknownCountAndUnreadableBrakeStatusTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/BrakeExhaustedWorkerServesReadOnlyTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusLifecycleHandlerTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/observability/health/WorkerSnapshotTapTest.java"
      ],
      "class": "New",
      "behaviour": "Round 2, rebuild brake: a brake that cannot be read counts as braked. A state record whose files do not parse, or a brake record for the current target without an attempt count, refuses to count an automatic attempt; a real Worker booting over such a record holds (read-only search, no automatic rebuild generation), status reports BLOCKED_REBUILD_BRAKE with reindexRequiredReason rebuild_brake_unreadable, the Head maps it to index.rebuild_brake_unreadable with the index-rebuild recovery, and a person's rebuild is accepted. Fail-before against bf41272f9.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S6",
          "quote": "An unparseable stored index fingerprint makes status say unknown; the index opens, is never reported compatible, and nothing rebuilds automatically.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "An automatic-rebuild brake that cannot be read counts as braked (orchestrator decision, round 2): no automatic rebuild or index replacement starts, search keeps serving the existing index read-only, status carries index.rebuild_brake_unreadable (reindexRequiredReason rebuild_brake_unreadable), and a person's explicit rebuild still runs. A brake record that names the current target but has no usable attempt count is unreadable in the same way.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r2-repaired.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/UnknownCountAndUnreadableBrakeStatusTest.java#aMismatchWithAnUnreadableBrakeIsReportedBrakedNamingTheUnreadableBrake",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/BrakeExhaustedWorkerServesReadOnlyTest.java#anUnreadableBrakeHoldsLikeASpentOneAndAPersonsRebuildStillRuns",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusLifecycleHandlerTest.java#compatBlockedReasonNamesAnUnreadableBrake",
        "modules/app-services/src/test/java/io/justsearch/app/services/observability/health/WorkerSnapshotTapTest.java#anUnreadableRebuildBrakeRoutesToTheIndexRebuild"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/UnknownCountAndUnreadableBrakeStatusTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusLifecycleHandlerTest.java"
      ],
      "class": "New",
      "behaviour": "Round 2, rebuild brake, must not break: a readable brake for another target still counts from one, a mismatch with no brake recorded is still a plain mismatch, and a spent brake still maps to index.rebuild_brake_exhausted.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "P2",
          "quote": "Genuine index corruption still auto-recovers as before; a genuine schema mismatch still migrates; the identity grace clock still starts only on confirmed absence; candidate and boot reconciliation behave as before.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "An automatic-rebuild brake that cannot be read counts as braked (orchestrator decision, round 2): no automatic rebuild or index replacement starts, search keeps serving the existing index read-only, status carries index.rebuild_brake_unreadable (reindexRequiredReason rebuild_brake_unreadable), and a person's explicit rebuild still runs. A brake record that names the current target but has no usable attempt count is unreadable in the same way.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r2-preserved.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/UnknownCountAndUnreadableBrakeStatusTest.java#aMismatchWithNoBrakeRecordedIsStillAPlainMismatch",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusLifecycleHandlerTest.java#compatBlockedReasonStillNamesASpentBrake"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/JobBatchExtractorForcedPathTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/OriginBoundaryAdmissionTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "The extractor now decides \"empty index\" from the strict count (docCountOrThrow) so that a failed read is not 0. The IndexCountOps mocks in the existing harnesses answer docCountOrThrow with the same 7 they already gave docCount. Inputs, observations and every expected value of the existing tests are unchanged."
    },
    {
      "items": [
        "modules/ui-web/src/api/__fixtures__/status-response-live.json"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "The live status fixture is the Java serialization of a default status, regenerated by StatusRecordSchemaTest (updateSchemas). The additive core field indexedDocumentsUnknown now appears in it with its default, false; no other value changed. contract.test.ts and status-response.test.ts parse the same fixture against the regenerated schema with their expectations unchanged."
    },
    {
      "items": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexRebuildBrakeTest.java"
      ],
      "class": "New",
      "behaviour": "Round 2, rebuild brake: a brake that cannot be read counts as braked. A state record whose files do not parse, or a brake record for the current target without an attempt count, refuses to count an automatic attempt; a real Worker booting over such a record holds (read-only search, no automatic rebuild generation), status reports BLOCKED_REBUILD_BRAKE with reindexRequiredReason rebuild_brake_unreadable, the Head maps it to index.rebuild_brake_unreadable with the index-rebuild recovery, and a person's rebuild is accepted. Fail-before against bf41272f9. (IndexRebuildBrakeTest re-run against bf41272f9 after its round 3 edit.)",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S6",
          "quote": "An unparseable stored index fingerprint makes status say unknown; the index opens, is never reported compatible, and nothing rebuilds automatically.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "An automatic-rebuild brake that cannot be read counts as braked (orchestrator decision, round 2): no automatic rebuild or index replacement starts, search keeps serving the existing index read-only, status carries index.rebuild_brake_unreadable (reindexRequiredReason rebuild_brake_unreadable), and a person's explicit rebuild still runs. A brake record that names the current target but has no usable attempt count is unreadable in the same way.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r2b-repaired.json",
      "newChecks": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexRebuildBrakeTest.java#anUnreadableBrakeRefusesToCountAnAttempt",
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexRebuildBrakeTest.java#aBrakeRecordWithoutACountRefusesToCountAnAttempt"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexRebuildBrakeTest.java"
      ],
      "class": "New",
      "behaviour": "Round 2, rebuild brake, must not break: a readable brake for another target still counts from one (IndexRebuildBrakeTest re-run against bf41272f9 after its round 3 edit).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "P2",
          "quote": "Genuine index corruption still auto-recovers as before; a genuine schema mismatch still migrates; the identity grace clock still starts only on confirmed absence; candidate and boot reconciliation behave as before.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "An automatic-rebuild brake that cannot be read counts as braked (orchestrator decision, round 2): no automatic rebuild or index replacement starts, search keeps serving the existing index read-only, status carries index.rebuild_brake_unreadable (reindexRequiredReason rebuild_brake_unreadable), and a person's explicit rebuild still runs. A brake record that names the current target but has no usable attempt count is unreadable in the same way.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r2b-preserved.json",
      "newChecks": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexRebuildBrakeTest.java#aReadableBrakeForAnotherTargetStillCountsFromOne"
      ]
    },
    {
      "items": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexRebuildBrakeTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "When neither state.json nor its backup can be read, whether a rebuild brake was set cannot be told, so it is treated as braked (orchestrator decision, round 3, under the owner's rule never to act on uncertainty; it reverses the tempdoc 915 rule that lost bookkeeping counts as no attempts). The layout is re-established as before, so a compatible index boots and serves normally, but the new record carries a lost-brake marker (auto_rebuild_key \"<state-lost>\") until a completed cutover: no automatic rebuild starts when the index would need one, status reports BLOCKED_REBUILD_BRAKE with reindexRequiredReason rebuild_brake_state_lost, which the Head shows as index.rebuild_brake_unreadable with the index-rebuild recovery, and a person's explicit rebuild still runs.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. docs/decisions/*.md searched for brake, auto_rebuild, automatic rebuild and rebuild; no record covers the repeat-rebuild brake. The rule being reversed lives in tempdoc 915 (not a decision record): lost bookkeeping counts as no attempts.",
      "whatChanged": "aTotallyLostStateFileDoesNotBlockRecovery asserted that a brake whose state file and backup are both unreadable reads as zero attempts. That expectation is withdrawn by the round 3 decision: lost bookkeeping is no longer a fresh budget. Its other assertions (nothing parseable reads as absent; the manager re-establishes a layout with an active generation, so the app boots and serves) are kept unchanged, and its new expectation is recorded as a New entry with fail-before evidence."
    },
    {
      "items": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexRebuildBrakeTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/LostBrakeStateBootTest.java"
      ],
      "class": "New",
      "behaviour": "Round 3, lost brake state: when neither state.json nor its backup can be read, the re-established record refuses to count an automatic rebuild, and a real Worker booting over a mismatched index in that state (state.json gone, backup unparseable) starts no automatic rebuild, keeps serving search read-only, reports BLOCKED_REBUILD_BRAKE with reindexRequiredReason rebuild_brake_state_lost, and accepts a person's rebuild. Fail-before against 9f9c1da16.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "When neither state.json nor its backup can be read, whether a rebuild brake was set cannot be told, so it is treated as braked (orchestrator decision, round 3, under the owner's rule never to act on uncertainty; it reverses the tempdoc 915 rule that lost bookkeeping counts as no attempts). The layout is re-established as before, so a compatible index boots and serves normally, but the new record carries a lost-brake marker (auto_rebuild_key \"<state-lost>\") until a completed cutover: no automatic rebuild starts when the index would need one, status reports BLOCKED_REBUILD_BRAKE with reindexRequiredReason rebuild_brake_state_lost, which the Head shows as index.rebuild_brake_unreadable with the index-rebuild recovery, and a person's explicit rebuild still runs.",
          "label": "agent choice"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S6",
          "quote": "An unparseable stored index fingerprint makes status say unknown; the index opens, is never reported compatible, and nothing rebuilds automatically.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r3-repaired.json",
      "newChecks": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/index/IndexRebuildBrakeTest.java#aTotallyLostStateFileDoesNotBlockRecovery",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/LostBrakeStateBootTest.java#aLostStateOverAMismatchedIndexStartsNoAutomaticRebuild"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusLifecycleHandlerLostBrakeTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/observability/health/WorkerSnapshotTapLostBrakeTest.java"
      ],
      "class": "New",
      "behaviour": "Round 3, lost brake state at the Head: reindexRequiredReason rebuild_brake_state_lost is shown as index.rebuild_brake_unreadable (not as a spent budget) and routes to the index-rebuild recovery (core.rebuild-index), not the plain reindex. Fail-before against 9f9c1da16.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "When neither state.json nor its backup can be read, whether a rebuild brake was set cannot be told, so it is treated as braked (orchestrator decision, round 3, under the owner's rule never to act on uncertainty; it reverses the tempdoc 915 rule that lost bookkeeping counts as no attempts). The layout is re-established as before, so a compatible index boots and serves normally, but the new record carries a lost-brake marker (auto_rebuild_key \"<state-lost>\") until a completed cutover: no automatic rebuild starts when the index would need one, status reports BLOCKED_REBUILD_BRAKE with reindexRequiredReason rebuild_brake_state_lost, which the Head shows as index.rebuild_brake_unreadable with the index-rebuild recovery, and a person's explicit rebuild still runs.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r3-repaired.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusLifecycleHandlerLostBrakeTest.java#compatBlockedReasonNamesALostBrakeAsUnreadable",
        "modules/app-services/src/test/java/io/justsearch/app/services/observability/health/WorkerSnapshotTapLostBrakeTest.java#aLostRebuildBrakeRoutesToTheIndexRebuild"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/LostBrakeStateBootTest.java"
      ],
      "class": "New",
      "behaviour": "Round 3, a held boot stays shown: a Worker that held an automatic rebuild back (a mismatched in-flight Green over a compatible Blue, brake record without a readable count) still reports BLOCKED_REBUILD_BRAKE with reindexRequiredReason rebuild_brake_unreadable after the brake record is repaired on disk to read as released, because ingestion stays stopped for that boot. Fail-before against 9f9c1da16.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "A boot that held an automatic rebuild back keeps ingestion stopped for the rest of that boot, so its status stays BLOCKED_REBUILD_BRAKE, naming what held it, for the whole boot, even when the brake record later reads as released or the serving index is compatible (orchestrator decision, round 3: status and behaviour must agree).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r3-repaired.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/LostBrakeStateBootTest.java#aHeldBootStaysBrakedAfterTheBrakeRecordBecomesReadable"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/AutomaticRebuildPathsConsultTheBrakeTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerBrakeSentinelTest.java"
      ],
      "class": "New",
      "behaviour": "Round 3, every automatic rebuild consults the brake: with the budget for the current target spent, a real Worker boot starts neither the embedding-model-change re-embed, nor the rebuild from source of an index recovered to empty (its recovery marker is kept), nor the backup-first rebuild (the mismatched index is neither backed up nor replaced); each serves read-only and reports BLOCKED_REBUILD_BRAKE with reindexRequiredReason rebuild_brake_exhausted. A rebuild whose target fingerprint cannot be computed is held instead of answering attempt 1, and charges nothing. Fail-before against 9f9c1da16.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "Every automatic rebuild consults the repeat-rebuild brake before it starts (orchestrator decision, round 3): the schema-mismatch migration, the embedding-model-change re-embed, the rebuild from source of an index recovered to empty, and the backup-first rebuild (asked before the writable open that would replace the index). Held, the existing index serves read-only and ingestion stops for the boot. A rebuild whose target fingerprint cannot be computed has no brake to consult and is treated as braked (it used to answer attempt 1 and rebuild unasked); nothing is charged to any budget.",
          "label": "agent choice"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S6",
          "quote": "An unparseable stored index fingerprint makes status say unknown; the index opens, is never reported compatible, and nothing rebuilds automatically.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r3-repaired.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/AutomaticRebuildPathsConsultTheBrakeTest.java#aSpentBrakeStopsTheEmbeddingModelChangeReembed",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/AutomaticRebuildPathsConsultTheBrakeTest.java#aSpentBrakeStopsTheRebuildOfAnIndexRecoveredToEmpty",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/AutomaticRebuildPathsConsultTheBrakeTest.java#aSpentBrakeStopsTheBackupFirstRebuild",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/AutomaticRebuildPathsConsultTheBrakeTest.java#anUncomputableTargetHoldsInsteadOfStartingARebuild",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerBrakeSentinelTest.java#anUncomputableTargetNeverConsumesBudget"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerBrakeSentinelTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "Every automatic rebuild consults the repeat-rebuild brake before it starts (orchestrator decision, round 3): the schema-mismatch migration, the embedding-model-change re-embed, the rebuild from source of an index recovered to empty, and the backup-first rebuild (asked before the writable open that would replace the index). Held, the existing index serves read-only and ingestion stops for the boot. A rebuild whose target fingerprint cannot be computed has no brake to consult and is treated as braked (it used to answer attempt 1 and rebuild unasked); nothing is charged to any budget.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. docs/decisions/*.md searched for brake, auto_rebuild, automatic rebuild and rebuild; no record covers the repeat-rebuild brake. The rule being reversed lives in tempdoc 915 (not a decision record): an unattributable boot reports attempt 1 and rebuilds without consulting the brake.",
      "whatChanged": "anUncomputableTargetNeverConsumesBudget asserted that recordAutoRebuildAttemptOrSkip answers 1 for a target that cannot be computed, which is what let that rebuild start without consulting the brake. That expectation is withdrawn by the round 3 decision: such a rebuild is held (the call throws AutoRebuildBrakeUnreadableException). Its other assertions (nothing is charged to a real target or to the literal \"null\") are kept unchanged; the new expectation is a New entry with fail-before evidence."
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/LostBrakeStateBootTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/AutomaticRebuildPathsConsultTheBrakeTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerBrakeSentinelTest.java"
      ],
      "class": "New",
      "behaviour": "Round 3, must not break: a lost state over a compatible index boots, is not held, reports COMPATIBLE and serves; with the brake released the embedding-model-change re-embed and the rebuild from source of an index recovered to empty still start at boot (generation sources embedding_model_change and corrupt_index_rebuild); a target that can be named still spends its budget normally. Pass on 9f9c1da16 and on the candidate.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "P2",
          "quote": "Genuine index corruption still auto-recovers as before; a genuine schema mismatch still migrates; the identity grace clock still starts only on confirmed absence; candidate and boot reconciliation behave as before.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "Every automatic rebuild consults the repeat-rebuild brake before it starts (orchestrator decision, round 3): the schema-mismatch migration, the embedding-model-change re-embed, the rebuild from source of an index recovered to empty, and the backup-first rebuild (asked before the writable open that would replace the index). Held, the existing index serves read-only and ingestion stops for the boot. A rebuild whose target fingerprint cannot be computed has no brake to consult and is treated as braked (it used to answer attempt 1 and rebuild unasked); nothing is charged to any budget.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r3-preserved.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/LostBrakeStateBootTest.java#aLostStateOverACompatibleIndexBootsAndServesNormally",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/AutomaticRebuildPathsConsultTheBrakeTest.java#aReleasedBrakeStillStartsTheEmbeddingModelChangeReembed",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/AutomaticRebuildPathsConsultTheBrakeTest.java#aReleasedBrakeStillRebuildsAnIndexRecoveredToEmpty",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerBrakeSentinelTest.java#aRealTargetStillConsumesBudgetNormally"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/RootVolumeSwapGatesTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherDeleteDifferentVolumeTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/DifferentVolumeRootStateTest.java"
      ],
      "class": "New",
      "behaviour": "Round 4, S8 beyond the prune: a SUBST drive letter remapped from a folder on one volume to a folder on another volume (real volumes, real mappings) makes admission keep the source as a ROOT_UNAVAILABLE retry instead of retiring it as deleted, makes the pre-publish freshness check neither delete the document nor end the queue item, makes a witnessed watcher DELETE after a restart remove nothing, and makes the root's walk end with ROOT_NOT_DIRECTORY (the root-level unavailable state) without admitting the other volume's files. Fail-before against 6b15a8153 by assertion.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S8",
          "quote": "A root that becomes unreadable or unavailable (expired share credentials, VPN drop, unplugged drive, root folder renamed or moved) keeps its documents and shows one root-level unavailable or unreadable state rather than thousands of per-file failures, until the person removes the root; a drive returning under another letter, or a different volume at the same letter, does not remove the original's documents.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "Every path that can end in a deletion asks the same root-identity question the prune asks before a file counts as confirmed absent: admission, the pre-publish freshness check, the post-extraction re-hash, the writer's pre-write check and the watcher's delete confirmation share one check (RootWitness, cached about one second per root, asked only for a source that was not found). A root whose volume differs from the one recorded for it, or whose volume cannot be read, is \"cannot tell\", and the root shows its root-level state: its walk ends with ROOT_NOT_DIRECTORY, the state the Head shows as unavailable (orchestrator decision, round 4).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r7-relock-r4-repaired.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/RootVolumeSwapGatesTest.java#admissionKeepsTheSourceWhenItsDriveLetterNowHoldsAnotherVolume",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/RootVolumeSwapGatesTest.java#theFreshnessCheckKeepsTheDocumentWhenItsDriveLetterNowHoldsAnotherVolume",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherDeleteDifferentVolumeTest.java#aWatcherDeleteOnAnotherVolumeAtTheSameLetterRemovesNothing",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/DifferentVolumeRootStateTest.java#aRootWhoseLetterNowHoldsAnotherVolumeShowsTheRootLevelState"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/PruneDeniedSubfolderTest.java"
      ],
      "class": "New",
      "behaviour": "Round 4, P1 under a denied subfolder: with folders a, b and c under a root, a real deny-read ACL on b and a/file-0 and c/file-0 deleted, the prune removes those two documents, keeps every document under b (including one whose file was deleted before b was denied) and reports itself incomplete with the count of kept documents. Before, the first document under b stopped the run and discarded the batch's confirmed deletions, so nothing was removed on any run. Fail-before against 6b15a8153 by assertion.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "P1",
          "quote": "A genuinely deleted file is removed with its chunks: immediately on a confirmed watcher delete, and within one sync cycle once its root is reachable (including files deleted while their root was offline).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S2",
          "quote": "Pruning, from every caller (periodic sync, prune-first reindex with exclude rules or force, migration replay of a buffered prune), removes a document only when its file is reported not found while the same root is present, re-checked during the prune; if the root or a subtree becomes unreadable mid-prune, nothing more is removed and the run reports itself incomplete.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "A prune keeps a document whose presence cannot be told and goes on judging the rest; a \"not found\" beneath a folder that could not be listed is \"cannot tell\". A document reported not found is removed only while the same root is present with the same identity, re-checked at the first undecidable document of a batch and immediately before each batch's deletions are applied. If the root itself becomes unreadable, unavailable or another volume, the run stops, removes nothing more and reports itself incomplete; a run that kept documents also reports itself incomplete, with the count of kept documents (orchestrator decision, round 4; it narrows S2's \"a subtree becomes unreadable mid-prune, nothing more is removed\" to the root, so one unreadable subfolder no longer stops the genuine deletions beside it, P1).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r4-repaired.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/PruneDeniedSubfolderTest.java#aDeniedSubfolderDoesNotStopTheDeletionsBesideIt"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/RootVolumeSwapGatesTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherDeleteDifferentVolumeTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/DifferentVolumeRootStateTest.java"
      ],
      "class": "New",
      "behaviour": "Round 4, must not break: on the same volume, admission still retires a source that was deleted, a confirmed watcher DELETE still removes the document, and the root still walks normally and admits its files after its volume was recorded. Pass on 6b15a8153 and on the candidate (the evidence also re-runs the earlier mid-prune root-loss checks, which still pass).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "P1",
          "quote": "A genuinely deleted file is removed with its chunks: immediately on a confirmed watcher delete, and within one sync cycle once its root is reachable (including files deleted while their root was offline).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "Every path that can end in a deletion asks the same root-identity question the prune asks before a file counts as confirmed absent: admission, the pre-publish freshness check, the post-extraction re-hash, the writer's pre-write check and the watcher's delete confirmation share one check (RootWitness, cached about one second per root, asked only for a source that was not found). A root whose volume differs from the one recorded for it, or whose volume cannot be read, is \"cannot tell\", and the root shows its root-level state: its walk ends with ROOT_NOT_DIRECTORY, the state the Head shows as unavailable (orchestrator decision, round 4).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r7-relock-r4-preserved.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/RootVolumeSwapGatesTest.java#admissionStillRetiresASourceDeletedFromTheSameVolume",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherDeleteDifferentVolumeTest.java#aWatcherDeleteOnTheSameVolumeStillRemovesTheDocument",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/DifferentVolumeRootStateTest.java#aRootOnTheSameVolumeStillWalksNormally"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/HeldBootPersonsRebuildTest.java"
      ],
      "class": "New",
      "behaviour": "Round 4, a person's rebuild in a held boot: with the brake spent and the boot held, the person's rebuild is accepted; the held boot cannot open the new generation live, so the Worker asks for an Engine restart; after that restart (and nothing else) a file added after the rebuild becomes searchable and the serving boot is no longer held. Passes on 6b15a8153 and on the candidate: the behaviour already held, and the documentation that said ingestion stays stopped until a restart was corrected.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "After a person's explicit rebuild in a boot the rebuild brake held, new and changed files are indexed again without any further action by the person (orchestrator decision, round 4).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r4-preserved.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/HeldBootPersonsRebuildTest.java#aPersonsRebuildInAHeldBootIndexesNewFilesWithoutFurtherAction"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/UnreadableAndReplacedSourceQueueTest.java"
      ],
      "class": "New",
      "behaviour": "Round 4, S7 and S9 through entry points that exist on cf3f14655 (a real scan, the real durable queue, the real batch extractor and admission): a file held open without sharing for a month is still retried with a growing back-off instead of being marked RETRY_EXHAUSTED; a file replaced by a directory, or by a dangling junction, has its old text removed and is not left pending. Fail-before against cf3f14655 by assertion; all three pass on 6b15a8153 (evidence ef3c41-uncertain-deletes-r4-s7s9-on-r3.json).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S7",
          "quote": "A file that stays unreadable is retried with counted, growing back-off (never re-extracted every second; the existing defer re-queues after a fixed 1 s without counting); its last indexed content stays searchable without a time limit; it is shown as \"indexed, source unreadable\" (an additive reason, distinct from missing from index) in the failed-files list and status; a restart keeps it queued and searchable.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S9",
          "quote": "A source file replaced by a directory or a symlink (including a dangling one) no longer leaves its old text searchable and is not retried forever.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r4-s7s9-repaired.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/UnreadableAndReplacedSourceQueueTest.java#aLockedSourceIsStillRetriedPastTheSevenDayWindow",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/UnreadableAndReplacedSourceQueueTest.java#aFileReplacedByADirectoryLeavesTheIndex",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/UnreadableAndReplacedSourceQueueTest.java#aFileReplacedByADanglingJunctionLeavesTheIndex"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/UnreadableAndReplacedSourceQueueTest.java"
      ],
      "class": "New",
      "behaviour": "Round 4, S7 must not break: a file held open without sharing is re-queued at least 60 s out with its attempt counted, not after about one second. This passes on cf3f14655 as well: through these entry points a locked file already took the counted 60 s retry ladder there, so the fixed 1 s re-queue named in S7 is not what a locked file met on cf3f14655; the check is kept as a must-not-break check.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S7",
          "quote": "A file that stays unreadable is retried with counted, growing back-off (never re-extracted every second; the existing defer re-queues after a fixed 1 s without counting); its last indexed content stays searchable without a time limit; it is shown as \"indexed, source unreadable\" (an additive reason, distinct from missing from index) in the failed-files list and status; a restart keeps it queued and searchable.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r4-s7s9-repaired.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/UnreadableAndReplacedSourceQueueTest.java#aLockedSourceIsNotRequeuedWithinSeconds"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRootVolumeSwapTest.java"
      ],
      "class": "New",
      "behaviour": "Round 5, S8 on the whole running Engine (its own add, walk, watcher, sync and admission) with a real SUBST drive letter moved between a folder on one volume and a folder on another: after a restart onto the other volume, the boot walk, the periodic sync's call and a re-walk leave the original's text searchable and index neither the other volume's same-named file nor its own files; a swap while running makes the periodic sync's call add nothing and the root read ROOT_NOT_DIRECTORY at once; create and change events on the other volume index nothing; and when the original volume returns after a boot on the other one, a sync re-registers the watcher so a change there is indexed without a restart, and the root reads indexed again. A control root on the temp volume must index its new file first, so a refused marker is refused, not slow. Fail-before against 09a19e033 by assertion. (EngineRootVolumeSwapTest re-run against 09a19e033 after its round 6 edit.)",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S8",
          "quote": "A root that becomes unreadable or unavailable (expired share credentials, VPN drop, unplugged drive, root folder renamed or moved) keeps its documents and shows one root-level unavailable or unreadable state rather than thousands of per-file failures, until the person removes the root; a drive returning under another letter, or a different volume at the same letter, does not remove the original's documents.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "Every path that adds or updates documents under a root asks the same root check as the deleting paths: admission of a present file, the scan walk, a re-walk or reindex, the sync (the periodic one included) and the watcher. While the folder at the root's path is another volume or cannot be read, nothing under it is added, updated or removed; a queued file is deferred with the root-level back-off (ROOT_UNAVAILABLE), never done, and the original's documents stay searchable with their original text. No watcher is registered on another volume at the root's path; the watch is held and registered when a walk or sync finds the recorded volume back, without a restart. A refused walk or sync records ROOT_NOT_DIRECTORY as the root's walk error at once, the periodic sync re-asks such roots every cycle, and the first accepted sync clears it and the root reads indexed again (orchestrator decisions, round 5).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r7-relock-r5-repaired.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRootVolumeSwapTest.java#aRestartOntoAnotherVolumeKeepsTheOriginalText",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRootVolumeSwapTest.java#aSwapWhileRunningAddsNothingAndShowsTheRootUnavailableAtOnce",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRootVolumeSwapTest.java#watcherEventsOnAnotherVolumeIndexNothing",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRootVolumeSwapTest.java#watchingResumesWithoutARestartWhenTheOriginalVolumeReturnsAfterABoot"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRootVolumeSwapTest.java"
      ],
      "class": "New",
      "behaviour": "Round 5, must not break: when the original volume returns to the drive letter after another volume was there while the Engine ran, a real change on the original is indexed, a file genuinely deleted there is pruned, and the root reads indexed. Passes on 09a19e033 and on the candidate. (EngineRootVolumeSwapTest re-run against 09a19e033 after its round 6 edit.)",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "P1",
          "quote": "A genuinely deleted file is removed with its chunks: immediately on a confirmed watcher delete, and within one sync cycle once its root is reachable (including files deleted while their root was offline).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "Every path that adds or updates documents under a root asks the same root check as the deleting paths: admission of a present file, the scan walk, a re-walk or reindex, the sync (the periodic one included) and the watcher. While the folder at the root's path is another volume or cannot be read, nothing under it is added, updated or removed; a queued file is deferred with the root-level back-off (ROOT_UNAVAILABLE), never done, and the original's documents stay searchable with their original text. No watcher is registered on another volume at the root's path; the watch is held and registered when a walk or sync finds the recorded volume back, without a restart. A refused walk or sync records ROOT_NOT_DIRECTORY as the root's walk error at once, the periodic sync re-asks such roots every cycle, and the first accepted sync clears it and the root reads indexed again (orchestrator decisions, round 5).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r7-relock-r5-preserved.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRootVolumeSwapTest.java#theOriginalVolumeReturningIsIndexedAndPrunedAsBefore"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRootVolumeSwapTest.java"
      ],
      "class": "New",
      "behaviour": "Round 6, S8 on the whole running Engine with a real SUBST drive letter moved to a folder on another volume: the walk the reindex button's recorded operation runs for the watched root is refused, and the root reads ROOT_NOT_DIRECTORY as soon as that walk returns, with its last-indexed time kept and nothing of the other volume indexed (a control root on the temp volume indexes first); and after a restart onto the other volume the boot walk's refusal keeps the root's last-indexed time. Fail-before against 5a7e266a5 by assertion.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S8",
          "quote": "A root that becomes unreadable or unavailable (expired share credentials, VPN drop, unplugged drive, root folder renamed or moved) keeps its documents and shows one root-level unavailable or unreadable state rather than thousands of per-file failures, until the person removes the root; a drive returning under another letter, or a different volume at the same letter, does not remove the original's documents.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "A walk the Worker refuses because the folder at a root's path is not on the volume first seen there makes the root read unavailable at once whichever walk it is: the boot walk, a re-walk, a sync, and the reindex button's recorded walk (core.reindex), whose refusal is now recorded as the root's walk error as the boot walk's is; a complete recorded walk of such a root clears it. The root's last-indexed time is kept, across a restart too. Each periodic cycle asks every root's volume with one volume identity read and no walk, and marks each refused root unavailable in that cycle; walking stays one root per cycle. The boot-root reconcile a migration cutover runs registers no watcher on another volume at a root's path: the watch is held, the reconcile of that root stops, and the watch resumes when a walk or sync finds the recorded volume back (orchestrator decisions, round 6).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r7-relock-r6-repaired.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRootVolumeSwapTest.java#aReindexRefusedOnAnotherVolumeShowsTheRootUnavailableAtOnce",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRootVolumeSwapTest.java#aRestartWhileUnavailableKeepsTheLastIndexedTime"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/PeriodicRootVolumeCheckTest.java"
      ],
      "class": "New",
      "behaviour": "Round 6, S8: with three watched roots, the product's own periodic cycle (captured from its scheduler and run by the test) walks one root; when the folder at that root's path then becomes another volume, the next cycle still walks exactly one other root and the swapped root reads ROOT_NOT_DIRECTORY within that cycle, its last-indexed time kept. The Worker is a fake that answers the sync and the volume check as the real one does; the real answer on a real other volume is RootVolumeCheckTest's. Fail-before against 5a7e266a5 by assertion.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S8",
          "quote": "A root that becomes unreadable or unavailable (expired share credentials, VPN drop, unplugged drive, root folder renamed or moved) keeps its documents and shows one root-level unavailable or unreadable state rather than thousands of per-file failures, until the person removes the root; a drive returning under another letter, or a different volume at the same letter, does not remove the original's documents.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "A walk the Worker refuses because the folder at a root's path is not on the volume first seen there makes the root read unavailable at once whichever walk it is: the boot walk, a re-walk, a sync, and the reindex button's recorded walk (core.reindex), whose refusal is now recorded as the root's walk error as the boot walk's is; a complete recorded walk of such a root clears it. The root's last-indexed time is kept, across a restart too. Each periodic cycle asks every root's volume with one volume identity read and no walk, and marks each refused root unavailable in that cycle; walking stays one root per cycle. The boot-root reconcile a migration cutover runs registers no watcher on another volume at a root's path: the watch is held, the reconcile of that root stops, and the watch resumes when a walk or sync finds the recorded volume back (orchestrator decisions, round 6).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r6-repaired.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/PeriodicRootVolumeCheckTest.java#aSwapOnARootWhoseTurnJustPassedReadsUnavailableWithinOneCycle"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/CommittedBootRootVolumeTest.java"
      ],
      "class": "New",
      "behaviour": "Rounds 6 and 7, S8 with a real SUBST drive letter: after a root was walked on one volume and the letter now holds another, the committed boot-root reconcile a migration cutover runs defers the root without throwing and without making the cutover's replay uncertain: it registers no watcher, holds the watch, queues nothing of the other volume, and the root reads ROOT_NOT_DIRECTORY to the Head; when the recorded volume is back, a walk of the root registers the held watch. Fail-before against 5c975db88 by assertion (the round 6 reconcile threw).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S8",
          "quote": "A root that becomes unreadable or unavailable (expired share credentials, VPN drop, unplugged drive, root folder renamed or moved) keeps its documents and shows one root-level unavailable or unreadable state rather than thousands of per-file failures, until the person removes the root; a drive returning under another letter, or a different volume at the same letter, does not remove the original's documents.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "A boot that still has the previous generation reconciles every declared root before it serves; a root whose folder is not on the volume first seen at its path is deferred, never a start-up failure: its watch is held, it reads unavailable with ROOT_NOT_DIRECTORY, it is left out of the convergence check so start-up completes and every other root is served, nothing under it is pruned, and the previous generation is kept until the root has been reconciled on its recorded volume. The server checks a deferred root's volume every 10 s; when the volume is back, without a restart, it reconciles the root, the watch resumes, and once the index matches the root's disk the pending removal of the previous generation completes. Any other boot-root failure fails the boot as before (orchestrator decision, round 7).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r7-repaired.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/CommittedBootRootVolumeTest.java#theCommittedBootReconcileDefersARootOnAnotherVolumeUntilItReturns"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/RootVolumeCheckTest.java"
      ],
      "class": "New",
      "behaviour": "Round 6, S8 with a real SUBST drive letter: the Worker's volume check the periodic sync asks of every root each cycle says the root's own volume is not refused, another volume at its path is, without walking or queuing anything, and the recorded volume back at its path is not. No fail-before: the check calls the volume check this round adds, so it cannot compile at the base; the repaired periodic behaviour is pinned by PeriodicRootVolumeCheckTest.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S8",
          "quote": "A root that becomes unreadable or unavailable (expired share credentials, VPN drop, unplugged drive, root folder renamed or moved) keeps its documents and shows one root-level unavailable or unreadable state rather than thousands of per-file failures, until the person removes the root; a drive returning under another letter, or a different volume at the same letter, does not remove the original's documents.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "A walk the Worker refuses because the folder at a root's path is not on the volume first seen there makes the root read unavailable at once whichever walk it is: the boot walk, a re-walk, a sync, and the reindex button's recorded walk (core.reindex), whose refusal is now recorded as the root's walk error as the boot walk's is; a complete recorded walk of such a root clears it. The root's last-indexed time is kept, across a restart too. Each periodic cycle asks every root's volume with one volume identity read and no walk, and marks each refused root unavailable in that cycle; walking stays one root per cycle. The boot-root reconcile a migration cutover runs registers no watcher on another volume at a root's path: the watch is held, the reconcile of that root stops, and the watch resumes when a walk or sync finds the recorded volume back (orchestrator decisions, round 6).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r7-relock-after-only.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/RootVolumeCheckTest.java#theVolumeCheckTheHeadAsksEachCycleTellsAnotherVolumeWithoutAWalk"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/CommittedBootDeferredRootTest.java"
      ],
      "class": "New",
      "behaviour": "Round 7, S8 through KnowledgeServer.start with a real SUBST drive letter: a boot that still has the previous generation, with a declared root whose letter now holds another volume, completes start-up; the control root on the temp volume is reconciled and searchable, the root reads ROOT_NOT_DIRECTORY, its document in the new generation is kept and searchable, nothing of the other volume is indexed, and the previous generation and its folder remain past one deferred-root check. When the original volume returns without a restart, the root is reconciled against it, the previous generation's removal completes, and a file written afterwards is indexed through the resumed watch. Fail-before against 5c975db88 by assertion (start-up failed).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S8",
          "quote": "A root that becomes unreadable or unavailable (expired share credentials, VPN drop, unplugged drive, root folder renamed or moved) keeps its documents and shows one root-level unavailable or unreadable state rather than thousands of per-file failures, until the person removes the root; a drive returning under another letter, or a different volume at the same letter, does not remove the original's documents.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "A boot that still has the previous generation reconciles every declared root before it serves; a root whose folder is not on the volume first seen at its path is deferred, never a start-up failure: its watch is held, it reads unavailable with ROOT_NOT_DIRECTORY, it is left out of the convergence check so start-up completes and every other root is served, nothing under it is pruned, and the previous generation is kept until the root has been reconciled on its recorded volume. The server checks a deferred root's volume every 10 s; when the volume is back, without a restart, it reconciles the root, the watch resumes, and once the index matches the root's disk the pending removal of the previous generation completes. Any other boot-root failure fails the boot as before (orchestrator decision, round 7).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r7-repaired.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/CommittedBootDeferredRootTest.java#aBootWithThePreviousGenerationServesTheOtherRootsWhileARootIsOnAnotherVolume",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/CommittedBootDeferredRootTest.java#theOriginalVolumeReturningReconcilesTheRootAndRemovesThePreviousGeneration"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/CommittedBootRemovedDeferredRootTest.java"
      ],
      "class": "New",
      "behaviour": "Round 8, S8 with a real SUBST drive letter through KnowledgeServer.start: a root the committed boot deferred (stick B at its letter, the previous generation kept) is removed by the person through the Worker calls the Head's removal makes while stick B is still mapped. Nothing of stick B becomes searchable, a file added to stick B afterwards is not indexed (no watcher), no volume is recorded for the root, and the removal resolves the deferral so the previous generation is removed. Fails on ef205d0e9: the deferred-root check recorded stick B, reconciled the removed root and indexed stick B's text.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S8",
          "quote": "A root that becomes unreadable or unavailable (expired share credentials, VPN drop, unplugged drive, root folder renamed or moved) keeps its documents and shows one root-level unavailable or unreadable state rather than thousands of per-file failures, until the person removes the root; a drive returning under another letter, or a different volume at the same letter, does not remove the original's documents.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "A deferred root counts as removed when the Worker's watcher registry recorded its unwatch, the Worker side of the person's removal, after the boot took its removal count; the deferred-root check drops such roots first, and the re-registration of a returned root's watch is refused under the same monitor as the unwatch. The deferred-root check asks a volume proof that never records. Removal also drops the Worker watcher's not-yet-run reconciles of the root (by a per-root removal count), runs the periodic cycle's volume ask of each root under that root's producer fence, and forgets the Head's availability answer for the root and every root under it (orchestrator decisions 1, 3 and 4, round 8).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r8-repaired.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/CommittedBootRemovedDeferredRootTest.java#aDeferredRootRemovedWhileAnotherVolumeIsMappedIsNeverIndexedWatchedOrRecorded"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/CommittedBootRemovedDeferredRootTest.java"
      ],
      "class": "New",
      "behaviour": "Round 8, S8 with a real SUBST drive letter: after that removal, the root added again through the Worker calls the Head's add makes (WatchRoot, then ScanRoot) once stick A is back is watched and walked, stick A's text is indexed and searchable, and nothing of stick B is. Fails on ef205d0e9: stick B had been recorded as the root's volume, so the root added again with stick A was refused as unavailable.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S8",
          "quote": "A root that becomes unreadable or unavailable (expired share credentials, VPN drop, unplugged drive, root folder renamed or moved) keeps its documents and shows one root-level unavailable or unreadable state rather than thousands of per-file failures, until the person removes the root; a drive returning under another letter, or a different volume at the same letter, does not remove the original's documents.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "A deferred root counts as removed when the Worker's watcher registry recorded its unwatch, the Worker side of the person's removal, after the boot took its removal count; the deferred-root check drops such roots first, and the re-registration of a returned root's watch is refused under the same monitor as the unwatch. The deferred-root check asks a volume proof that never records. Removal also drops the Worker watcher's not-yet-run reconciles of the root (by a per-root removal count), runs the periodic cycle's volume ask of each root under that root's producer fence, and forgets the Head's availability answer for the root and every root under it (orchestrator decisions 1, 3 and 4, round 8).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r8-repaired.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/CommittedBootRemovedDeferredRootTest.java#theRemovedRootAddedAgainWithTheOriginalVolumeIndexesItAndNothingOfTheOther"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RemovedRootWatcherReconcileTest.java"
      ],
      "class": "New",
      "behaviour": "Round 8, S8 (removal keeps nothing of a root): an event-overflow reconcile the Worker's watcher asked for and had not yet run when the root was unwatched is dropped, while reconciles of other roots run as before. Fails on ef205d0e9: the removed root's reconcile ran, which syncs the removed root.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S8",
          "quote": "A root that becomes unreadable or unavailable (expired share credentials, VPN drop, unplugged drive, root folder renamed or moved) keeps its documents and shows one root-level unavailable or unreadable state rather than thousands of per-file failures, until the person removes the root; a drive returning under another letter, or a different volume at the same letter, does not remove the original's documents.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "A deferred root counts as removed when the Worker's watcher registry recorded its unwatch, the Worker side of the person's removal, after the boot took its removal count; the deferred-root check drops such roots first, and the re-registration of a returned root's watch is refused under the same monitor as the unwatch. The deferred-root check asks a volume proof that never records. Removal also drops the Worker watcher's not-yet-run reconciles of the root (by a per-root removal count), runs the periodic cycle's volume ask of each root under that root's producer fence, and forgets the Head's availability answer for the root and every root under it (orchestrator decisions 1, 3 and 4, round 8).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r8-repaired.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RemovedRootWatcherReconcileTest.java#aReconcileAskedForBeforeTheRootWasRemovedNeverRuns"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/PeriodicCycleRemovedRootTest.java"
      ],
      "class": "New",
      "behaviour": "Round 8, S8 (removal keeps nothing of a root): a root the person removes while the periodic cycle's walk is with the Worker is not asked for its volume by that cycle, and the root still watched is asked as before. Fails on ef205d0e9: the cycle asked the removed root, which records the volume at its path.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S8",
          "quote": "A root that becomes unreadable or unavailable (expired share credentials, VPN drop, unplugged drive, root folder renamed or moved) keeps its documents and shows one root-level unavailable or unreadable state rather than thousands of per-file failures, until the person removes the root; a drive returning under another letter, or a different volume at the same letter, does not remove the original's documents.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "A deferred root counts as removed when the Worker's watcher registry recorded its unwatch, the Worker side of the person's removal, after the boot took its removal count; the deferred-root check drops such roots first, and the re-registration of a returned root's watch is refused under the same monitor as the unwatch. The deferred-root check asks a volume proof that never records. Removal also drops the Worker watcher's not-yet-run reconciles of the root (by a per-root removal count), runs the periodic cycle's volume ask of each root under that root's producer fence, and forgets the Head's availability answer for the root and every root under it (orchestrator decisions 1, 3 and 4, round 8).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r8-repaired.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/PeriodicCycleRemovedRootTest.java#aRootRemovedDuringTheCycleIsNotAskedForItsVolume"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RemovedRootAvailabilityTest.java"
      ],
      "class": "New",
      "behaviour": "Round 8, S8 (removal keeps nothing of a root): a root removed while its folder was gone and added again once the folder is back does not show the removed root's cached unavailable answer. Fails on ef205d0e9: the Head kept the removed root's availability answer and showed the root added again as unavailable.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S8",
          "quote": "A root that becomes unreadable or unavailable (expired share credentials, VPN drop, unplugged drive, root folder renamed or moved) keeps its documents and shows one root-level unavailable or unreadable state rather than thousands of per-file failures, until the person removes the root; a drive returning under another letter, or a different volume at the same letter, does not remove the original's documents.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "A deferred root counts as removed when the Worker's watcher registry recorded its unwatch, the Worker side of the person's removal, after the boot took its removal count; the deferred-root check drops such roots first, and the re-registration of a returned root's watch is refused under the same monitor as the unwatch. The deferred-root check asks a volume proof that never records. Removal also drops the Worker watcher's not-yet-run reconciles of the root (by a per-root removal count), runs the periodic cycle's volume ask of each root under that root's producer fence, and forgets the Head's availability answer for the root and every root under it (orchestrator decisions 1, 3 and 4, round 8).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r8-repaired.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RemovedRootAvailabilityTest.java#aRootAddedAgainDoesNotShowTheRemovedRootsUnavailableAnswer"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/UnprovenRootRefusedTest.java"
      ],
      "class": "New",
      "behaviour": "Round 8, refined decision 2, S8: once the upgrade seeding is done (the volume record exists), a declared root with no recorded volume (declared as after a restart: the Head re-registers its watch without the add marker) is unproven on every background path. A walk without the add marker, the periodic sync, the periodic volume check and the prune all refuse it with the root-level reason: nothing is recorded for it, nothing is queued, and an indexed document whose file is gone is kept. Fails on ef205d0e9: the re-registration and the first walk recorded whatever volume was at the root's path and walked it.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S8",
          "quote": "A root that becomes unreadable or unavailable (expired share credentials, VPN drop, unplugged drive, root folder renamed or moved) keeps its documents and shows one root-level unavailable or unreadable state rather than thousands of per-file failures, until the person removes the root; a drive returning under another letter, or a different volume at the same letter, does not remove the original's documents.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "Round 8, refined decision 2 (orchestrator, under the owner's delegation): only the person's add, carrying an explicit add marker (root_added) through WatchRoot and ScanRoot, records a root's volume. Every other path (recovery, the periodic sync and its volume check, walks, the watcher, the boot reconcile, the deferred check, the prune) refuses a declared root with no recorded volume as unproven and records and deletes nothing. When neither the volume record nor its pending file exists (an upgrade from a version without the record), each declared root is seeded once: a root with indexed documents is recorded only if at least half of up to 50 sampled indexed files are on the drive at its path with their indexed size and modification time; a root with none is recorded like an add; otherwise nothing is recorded, the root reads unavailable with the reason 'drive not confirmed after upgrade', nothing is deleted, and the periodic cycle retries the same rule. A profiling reset that removes roots removes each one as the person's removal does.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r8-volume-repaired.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/UnprovenRootRefusedTest.java#aBackgroundPathMeetingARootWithNoRecordedVolumeRefusesItAndRecordsNothing"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/UpgradeRootVolumeSeedingTest.java"
      ],
      "class": "New",
      "behaviour": "Round 8, refined decision 2, S8 through KnowledgeServer.start: an index written without the volume record (both record files deleted after a first boot indexed the roots) and unchanged drives. The upgrade boot records every declared root on the evidence of its indexed files, and a root with nothing indexed like an add; each root then syncs with nothing queued again and nothing deleted, and its text stays searchable. Fails on ef205d0e9: nothing is recorded at the upgrade boot.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S8",
          "quote": "A root that becomes unreadable or unavailable (expired share credentials, VPN drop, unplugged drive, root folder renamed or moved) keeps its documents and shows one root-level unavailable or unreadable state rather than thousands of per-file failures, until the person removes the root; a drive returning under another letter, or a different volume at the same letter, does not remove the original's documents.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "Round 8, refined decision 2 (orchestrator, under the owner's delegation): only the person's add, carrying an explicit add marker (root_added) through WatchRoot and ScanRoot, records a root's volume. Every other path (recovery, the periodic sync and its volume check, walks, the watcher, the boot reconcile, the deferred check, the prune) refuses a declared root with no recorded volume as unproven and records and deletes nothing. When neither the volume record nor its pending file exists (an upgrade from a version without the record), each declared root is seeded once: a root with indexed documents is recorded only if at least half of up to 50 sampled indexed files are on the drive at its path with their indexed size and modification time; a root with none is recorded like an add; otherwise nothing is recorded, the root reads unavailable with the reason 'drive not confirmed after upgrade', nothing is deleted, and the periodic cycle retries the same rule. A profiling reset that removes roots removes each one as the person's removal does.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r8-volume-repaired.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/UpgradeRootVolumeSeedingTest.java#anUpgradeWithTheSameDrivesRecordsEveryRootOnEvidenceAndKeepsItsIndex",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/UpgradeRootVolumeSeedingTest.java#anUpgradeRecordsARootWithNothingIndexedLikeAnAdd"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/UpgradeRootVolumeSeedingTest.java"
      ],
      "class": "New",
      "behaviour": "Round 8, refined decision 2, S8 with a real SUBST drive letter through KnowledgeServer.start: an upgrade boot where the root's letter now holds another volume (stick B, with a same-named file of other content) records nothing for that root, while the control root is recorded. The Head's re-registration of the watch, the periodic sync and the periodic volume check record nothing; the sync answers 'ROOT_NOT_DIRECTORY: drive not confirmed after upgrade', queues and deletes nothing; the root's text stays searchable and nothing of stick B is. Once the original drive is back, the periodic volume check adopts it without any other action, and the root syncs again deleting nothing. Fails on ef205d0e9: the upgrade boot records nothing and the first sync records stick B and syncs it.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S8",
          "quote": "A root that becomes unreadable or unavailable (expired share credentials, VPN drop, unplugged drive, root folder renamed or moved) keeps its documents and shows one root-level unavailable or unreadable state rather than thousands of per-file failures, until the person removes the root; a drive returning under another letter, or a different volume at the same letter, does not remove the original's documents.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "Round 8, refined decision 2 (orchestrator, under the owner's delegation): only the person's add, carrying an explicit add marker (root_added) through WatchRoot and ScanRoot, records a root's volume. Every other path (recovery, the periodic sync and its volume check, walks, the watcher, the boot reconcile, the deferred check, the prune) refuses a declared root with no recorded volume as unproven and records and deletes nothing. When neither the volume record nor its pending file exists (an upgrade from a version without the record), each declared root is seeded once: a root with indexed documents is recorded only if at least half of up to 50 sampled indexed files are on the drive at its path with their indexed size and modification time; a root with none is recorded like an add; otherwise nothing is recorded, the root reads unavailable with the reason 'drive not confirmed after upgrade', nothing is deleted, and the periodic cycle retries the same rule. A profiling reset that removes roots removes each one as the person's removal does.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r8-volume-repaired.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/UpgradeRootVolumeSeedingTest.java#anUpgradeWhereTheLetterHoldsAnotherVolumeRecordsNothingUntilTheDriveReturns"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/ProfilingResetRemovesRootsTest.java"
      ],
      "class": "New",
      "behaviour": "Round 8, refined decision 2, S8: a profiling reset that clears the roots removes each one as the person's removal does: the Worker is told to stop watching each root (which drops its volume record, pending upgrade seeding, held watch and deferred state) and to delete under it, and no root is kept. Fails on ef205d0e9: the reset only cleared the Head's list.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S8",
          "quote": "A root that becomes unreadable or unavailable (expired share credentials, VPN drop, unplugged drive, root folder renamed or moved) keeps its documents and shows one root-level unavailable or unreadable state rather than thousands of per-file failures, until the person removes the root; a drive returning under another letter, or a different volume at the same letter, does not remove the original's documents.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "Round 8, refined decision 2 (orchestrator, under the owner's delegation): only the person's add, carrying an explicit add marker (root_added) through WatchRoot and ScanRoot, records a root's volume. Every other path (recovery, the periodic sync and its volume check, walks, the watcher, the boot reconcile, the deferred check, the prune) refuses a declared root with no recorded volume as unproven and records and deletes nothing. When neither the volume record nor its pending file exists (an upgrade from a version without the record), each declared root is seeded once: a root with indexed documents is recorded only if at least half of up to 50 sampled indexed files are on the drive at its path with their indexed size and modification time; a root with none is recorded like an add; otherwise nothing is recorded, the root reads unavailable with the reason 'drive not confirmed after upgrade', nothing is deleted, and the periodic cycle retries the same rule. A profiling reset that removes roots removes each one as the person's removal does.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r8-volume-repaired.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/ProfilingResetRemovesRootsTest.java#aResetThatRemovesRootsRemovesEachOneAsThePersonsRemovalDoes"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/RootVolumeCheckTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/CommittedBootRootVolumeTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/DifferentVolumeRootStateTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/PruneMissingDifferentVolumeTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherDeleteDifferentVolumeTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/CommittedBootRemovedDeferredRootTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RootPresenceGuardTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "Round 8, refined decision 2: only the person's add records a root's volume. These tests established the precondition 'the root's volume is recorded on stick A' through the first walk, prune or registration, which no longer records. Each now establishes it the way the product does: the person's add, a WatchRoot request carrying the add marker (set through the descriptor, so the file still compiles and runs on the refs without the marker), or, in RootPresenceGuardTest, a direct record of the root's current volume. CommittedBootRemovedDeferredRootTest's re-add sets the marker on its WatchRoot and ScanRoot. Inputs, the swap, the observations and the expected outcomes are unchanged; each check's earlier evidence was re-run against its original ref.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "Round 8, refined decision 2 (orchestrator, under the owner's delegation): only the person's add, carrying an explicit add marker (root_added) through WatchRoot and ScanRoot, records a root's volume. Every other path (recovery, the periodic sync and its volume check, walks, the watcher, the boot reconcile, the deferred check, the prune) refuses a declared root with no recorded volume as unproven and records and deletes nothing. When neither the volume record nor its pending file exists (an upgrade from a version without the record), each declared root is seeded once: a root with indexed documents is recorded only if at least half of up to 50 sampled indexed files are on the drive at its path with their indexed size and modification time; a root with none is recorded like an add; otherwise nothing is recorded, the root reads unavailable with the reason 'drive not confirmed after upgrade', nothing is deleted, and the periodic cycle retries the same rule. A profiling reset that removes roots removes each one as the person's removal does.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/PeriodicRootVolumeCheckTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/PeriodicCycleRemovedRootTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "Round 8, refined decision 2: the Head now asks the Worker's volume check with its reason (rootVolumeRefusal), so the root-level reason the upgrade rule adds reaches the root's state. The fake Worker answers that call from the same per-root state it answers refusesRootVolume from; the cycle, the swap and the expected outcomes are unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "Round 8, refined decision 2 (orchestrator, under the owner's delegation): only the person's add, carrying an explicit add marker (root_added) through WatchRoot and ScanRoot, records a root's volume. Every other path (recovery, the periodic sync and its volume check, walks, the watcher, the boot reconcile, the deferred check, the prune) refuses a declared root with no recorded volume as unproven and records and deletes nothing. When neither the volume record nor its pending file exists (an upgrade from a version without the record), each declared root is seeded once: a root with indexed documents is recorded only if at least half of up to 50 sampled indexed files are on the drive at its path with their indexed size and modification time; a root with none is recorded like an add; otherwise nothing is recorded, the root reads unavailable with the reason 'drive not confirmed after upgrade', nothing is deleted, and the periodic cycle retries the same rule. A profiling reset that removes roots removes each one as the person's removal does.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRootVolumeSwapTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Round 8, refined decision 2: the readiness probe syncs the control folder before it is added, to learn that the Worker's index half answers; that sync is now refused as unproven ('ROOT_NOT_DIRECTORY...'). The probe accepts that refusal as an answer, as it accepts an empty error. The checks themselves are unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "Round 8, refined decision 2 (orchestrator, under the owner's delegation): only the person's add, carrying an explicit add marker (root_added) through WatchRoot and ScanRoot, records a root's volume. Every other path (recovery, the periodic sync and its volume check, walks, the watcher, the boot reconcile, the deferred check, the prune) refuses a declared root with no recorded volume as unproven and records and deletes nothing. When neither the volume record nor its pending file exists (an upgrade from a version without the record), each declared root is seeded once: a root with indexed documents is recorded only if at least half of up to 50 sampled indexed files are on the drive at its path with their indexed size and modification time; a root with none is recorded like an add; otherwise nothing is recorded, the root reads unavailable with the reason 'drive not confirmed after upgrade', nothing is deleted, and the periodic cycle retries the same rule. A profiling reset that removes roots removes each one as the person's removal does.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/SyncDirectoryCallerProvenanceTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceSyncDirectoryPhaseTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WorkerRecordedScanAdmissionTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/SyncRootReplayProvenanceTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/DocumentIdentityBootImportTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceCandidateReconciliationTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/server/DefaultWorkerAppServicesProducerTransferTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "Round 8, refined decision 2: only the person's add records a root's volume, and every sync, walk, recorded scan, replay and migration walk refuses a root with none recorded. These tests synced, scanned, replayed or migrated a folder that no one had added, relying on the first look to record its volume. Each now adds the folder first the way the product does: a WatchRoot request carrying the add marker (an inline helper in the Worker tests; the candidate-reconciliation helper takes the add's record through the service's own root check; the transfer test's watch carries the marker; the identity test's add then drops only its watch subscription, so its own rename is not raced by watcher events, as before). The folders, inputs, calls and expected outcomes are unchanged; the one sync of a missing folder adds nothing.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "Round 8, refined decision 2 (orchestrator, under the owner's delegation): only the person's add, carrying an explicit add marker (root_added) through WatchRoot and ScanRoot, records a root's volume. Every other path (recovery, the periodic sync and its volume check, walks, the watcher, the boot reconcile, the deferred check, the prune) refuses a declared root with no recorded volume as unproven and records and deletes nothing. When neither the volume record nor its pending file exists (an upgrade from a version without the record), each declared root is seeded once: a root with indexed documents is recorded only if at least half of up to 50 sampled indexed files are on the drive at its path with their indexed size and modification time; a root with none is recorded like an add; otherwise nothing is recorded, the root reads unavailable with the reason 'drive not confirmed after upgrade', nothing is deleted, and the periodic cycle retries the same rule. A profiling reset that removes roots removes each one as the person's removal does.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/PruneMissingUncertainRootTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/PruneDeniedSubfolderTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "Round 8, refined decision 2: the prune refuses a root with no recorded volume, so the prune of a present root no longer records it at its first prune. aGenuinelyDeletedFileUnderAPresentRootIsPruned and aDeniedSubfolderDoesNotStopTheDeletionsBesideIt now add the root first with a WatchRoot request whose add marker is set through the descriptor, so the files compile and run on the refs without the marker. aRootRenamedAwayIsNotPruned is unchanged. The deletions, the denied folder and the expected counts are unchanged; their earlier evidence was re-run against the same refs, written to the same files.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "Round 8, refined decision 2 (orchestrator, under the owner's delegation): only the person's add, carrying an explicit add marker (root_added) through WatchRoot and ScanRoot, records a root's volume. Every other path (recovery, the periodic sync and its volume check, walks, the watcher, the boot reconcile, the deferred check, the prune) refuses a declared root with no recorded volume as unproven and records and deletes nothing. When neither the volume record nor its pending file exists (an upgrade from a version without the record), each declared root is seeded once: a root with indexed documents is recorded only if at least half of up to 50 sampled indexed files are on the drive at its path with their indexed size and modification time; a root with none is recorded like an add; otherwise nothing is recorded, the root reads unavailable with the reason 'drive not confirmed after upgrade', nothing is deleted, and the periodic cycle retries the same rule. A profiling reset that removes roots removes each one as the person's removal does.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineTestHarness.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineIndexingWorkflowTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineMigrationLifecycleTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineCorruptionRebuildTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineSyncDirectoryTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineScanRootFlowTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineSwitchingFenceBufferingTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineVduMigrationReplayTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRootRetirementTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRecordedIngestionProducerTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Round 8, refined decision 2: these Engine tests declared a root by writing watched_roots.json (which the harness's boot root membership does not read, so the upgrade seeding sees no root), composed a Worker service over a root no one added, or synced or scanned a folder directly, relying on the first walk to record its volume; the migration, rebuild, sync, scan and recorded walk now refuse such a root (a migration's cutover then waits). The harness gains addRootAsThePerson and recordAddedRoot: the Worker's WatchRoot with the add marker, after which only the watch subscription is dropped from the Worker's registry (not the person's removal, which would drop the record), so the tests run without watcher events as before. Each test calls it for the folder it declares, syncs or scans; the fence test creates and adds its sync folder before the migration starts instead of during SWITCHING, and the two fixtures that spy the service clear the add's invocations. Files, calls and expected outcomes are unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "Round 8, refined decision 2 (orchestrator, under the owner's delegation): only the person's add, carrying an explicit add marker (root_added) through WatchRoot and ScanRoot, records a root's volume. Every other path (recovery, the periodic sync and its volume check, walks, the watcher, the boot reconcile, the deferred check, the prune) refuses a declared root with no recorded volume as unproven and records and deletes nothing. When neither the volume record nor its pending file exists (an upgrade from a version without the record), each declared root is seeded once: a root with indexed documents is recorded only if at least half of up to 50 sampled indexed files are on the drive at its path with their indexed size and modification time; a root with none is recorded like an add; otherwise nothing is recorded, the root reads unavailable with the reason 'drive not confirmed after upgrade', nothing is deleted, and the periodic cycle retries the same rule. A profiling reset that removes roots removes each one as the person's removal does.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WalkBeforeReregistrationTest.java"
      ],
      "class": "New",
      "behaviour": "Round 8b, decision 2, S8: no walk writes a volume record. After a Worker restart, before the Head registers its roots again, a walk of a folder with no volume recorded for it (a root declared but never proven) is deferred with the root-level reason: it records nothing, queues nothing, and a prune under it deletes nothing, keeping an indexed document whose file is gone. Once the roots are registered again (without the add marker), the root the person added before the restart is walked normally and its new file is queued, while the unproven root stays refused, recording and deleting nothing. Fails on ef205d0e9: the first walk recorded whatever volume was at the folder's path and walked it.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S8",
          "quote": "A root that becomes unreadable or unavailable (expired share credentials, VPN drop, unplugged drive, root folder renamed or moved) keeps its documents and shows one root-level unavailable or unreadable state rather than thousands of per-file failures, until the person removes the root; a drive returning under another letter, or a different volume at the same letter, does not remove the original's documents.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "Round 8b (orchestrator, under the owner's delegation and the task's rule: never delete indexed content, or approve an index, on uncertainty). (1) An explicit ingest request, a person's or an agent's, is a deliberate add and records the folder's volume; only that request may write the record, carrying the same add marker as the person's add (root_added on ScanRoot, and on BatchRequest for SubmitBatch). (2) No walk ever writes a volume record: a walk that finds no record for its root, or cannot tell which root it belongs to (before re-registration after a Worker restart), is deferred, deletes nothing, and resumes when the roots are registered. (3) A configured collection root is the person's configuration: when its record is absent it is seeded once by the upgrade rule (at least half of up to 50 sampled indexed files present with their indexed size and modification time, or nothing indexed); otherwise it reads unavailable with the same reason and nothing is deleted; it never loses indexing it had on ef205d0e9 while its drive is present. (4) A stalled cutover that blocks close is bounded only if it is new with this lane; if it already happened on main it is a named limit. (5) No volume identity off Windows: a named limit. (6) Upgrade evidence by size and modification time, as decided. (7) The duplicated reason constant is shared only through an existing common module, otherwise cross-referenced.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r8b-repaired.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WalkBeforeReregistrationTest.java#aWalkAfterARestartBeforeTheRootsAreRegisteredRecordsAndDeletesNothingThenProceeds"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ConfiguredCollectionVolumeTest.java"
      ],
      "class": "New",
      "behaviour": "Round 8b, decision 3, S8 with a real SUBST drive letter through KnowledgeServer.start: a configured collection (index.collections) whose file was indexed on stick A, an index written without the volume record, and the collection's letter now holding stick B (a same-named file of other content and a new one). The boot records nothing for the collection's root, which reads unavailable with 'ROOT_NOT_DIRECTORY: drive not confirmed after upgrade'. The person's rebuild then keeps the active generation: the collection's indexed text stays searchable, nothing of stick B is, and nothing is recorded. Fails on ef205d0e9: the rebuild records stick B, builds the new generation from it and drops the collection's indexed text.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S8",
          "quote": "A root that becomes unreadable or unavailable (expired share credentials, VPN drop, unplugged drive, root folder renamed or moved) keeps its documents and shows one root-level unavailable or unreadable state rather than thousands of per-file failures, until the person removes the root; a drive returning under another letter, or a different volume at the same letter, does not remove the original's documents.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "Round 8b (orchestrator, under the owner's delegation and the task's rule: never delete indexed content, or approve an index, on uncertainty). (1) An explicit ingest request, a person's or an agent's, is a deliberate add and records the folder's volume; only that request may write the record, carrying the same add marker as the person's add (root_added on ScanRoot, and on BatchRequest for SubmitBatch). (2) No walk ever writes a volume record: a walk that finds no record for its root, or cannot tell which root it belongs to (before re-registration after a Worker restart), is deferred, deletes nothing, and resumes when the roots are registered. (3) A configured collection root is the person's configuration: when its record is absent it is seeded once by the upgrade rule (at least half of up to 50 sampled indexed files present with their indexed size and modification time, or nothing indexed); otherwise it reads unavailable with the same reason and nothing is deleted; it never loses indexing it had on ef205d0e9 while its drive is present. (4) A stalled cutover that blocks close is bounded only if it is new with this lane; if it already happened on main it is a named limit. (5) No volume identity off Windows: a named limit. (6) Upgrade evidence by size and modification time, as decided. (7) The duplicated reason constant is shared only through an existing common module, otherwise cross-referenced.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r8b-repaired.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ConfiguredCollectionVolumeTest.java#aConfiguredCollectionWhoseLetterHoldsAnotherVolumeRecordsNothingAndKeepsItsIndex"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ConfiguredCollectionVolumeTest.java"
      ],
      "class": "New",
      "behaviour": "Round 8b, decision 3, must not break: a configured collection on its own drive, after an upgrade from a version without the volume record, keeps the indexing it had and indexes new files. The person's rebuild completes, the collection's indexed file stays searchable and a file added since is indexed. Passes on ef205d0e9 and on the candidate.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S8",
          "quote": "A root that becomes unreadable or unavailable (expired share credentials, VPN drop, unplugged drive, root folder renamed or moved) keeps its documents and shows one root-level unavailable or unreadable state rather than thousands of per-file failures, until the person removes the root; a drive returning under another letter, or a different volume at the same letter, does not remove the original's documents.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "Round 8b (orchestrator, under the owner's delegation and the task's rule: never delete indexed content, or approve an index, on uncertainty). (1) An explicit ingest request, a person's or an agent's, is a deliberate add and records the folder's volume; only that request may write the record, carrying the same add marker as the person's add (root_added on ScanRoot, and on BatchRequest for SubmitBatch). (2) No walk ever writes a volume record: a walk that finds no record for its root, or cannot tell which root it belongs to (before re-registration after a Worker restart), is deferred, deletes nothing, and resumes when the roots are registered. (3) A configured collection root is the person's configuration: when its record is absent it is seeded once by the upgrade rule (at least half of up to 50 sampled indexed files present with their indexed size and modification time, or nothing indexed); otherwise it reads unavailable with the same reason and nothing is deleted; it never loses indexing it had on ef205d0e9 while its drive is present. (4) A stalled cutover that blocks close is bounded only if it is new with this lane; if it already happened on main it is a named limit. (5) No volume identity off Windows: a named limit. (6) Upgrade evidence by size and modification time, as decided. (7) The duplicated reason constant is shared only through an existing common module, otherwise cross-referenced.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-r8b-preserved.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ConfiguredCollectionVolumeTest.java#aConfiguredCollectionOnItsOwnDriveKeepsItsIndexAndIndexesNewFilesAfterAnUpgrade"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/HeldBootPersonsRebuildTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "Round 8b, decision 1: only an explicit ingest request carrying the add marker records the volume of its files' folder, and a file whose folder has no recorded volume is not admitted. The test's file submitted after the person's rebuild is now sent the way the product's explicit ingest sends it, with the marker (set through the request's descriptor, so the test still builds on earlier refs). The file, the rebuild, the restarts and the expected outcome are unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S8",
          "quote": "A root that becomes unreadable or unavailable (expired share credentials, VPN drop, unplugged drive, root folder renamed or moved) keeps its documents and shows one root-level unavailable or unreadable state rather than thousands of per-file failures, until the person removes the root; a drive returning under another letter, or a different volume at the same letter, does not remove the original's documents.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "Round 8b (orchestrator, under the owner's delegation and the task's rule: never delete indexed content, or approve an index, on uncertainty). (1) An explicit ingest request, a person's or an agent's, is a deliberate add and records the folder's volume; only that request may write the record, carrying the same add marker as the person's add (root_added on ScanRoot, and on BatchRequest for SubmitBatch). (2) No walk ever writes a volume record: a walk that finds no record for its root, or cannot tell which root it belongs to (before re-registration after a Worker restart), is deferred, deletes nothing, and resumes when the roots are registered. (3) A configured collection root is the person's configuration: when its record is absent it is seeded once by the upgrade rule (at least half of up to 50 sampled indexed files present with their indexed size and modification time, or nothing indexed); otherwise it reads unavailable with the same reason and nothing is deleted; it never loses indexing it had on ef205d0e9 while its drive is present. (4) A stalled cutover that blocks close is bounded only if it is new with this lane; if it already happened on main it is a named limit. (5) No volume identity off Windows: a named limit. (6) Upgrade evidence by size and modification time, as decided. (7) The duplicated reason constant is shared only through an existing common module, otherwise cross-referenced.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceCandidateReconciliationTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceDocumentIdentityTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Round 8b, decision 2: nothing but a deliberate add records a volume, so a watcher delete of a file in a folder with no recorded volume is not confirmed and removes nothing (before, the parent folder's volume was recorded the first time it was seen). A watcher delete happens only under a root the person added, so these tests first record their folder as the person's add does, through the service's own root check. The paths, the deletes, the journal order and the expected outcomes are unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S8",
          "quote": "A root that becomes unreadable or unavailable (expired share credentials, VPN drop, unplugged drive, root folder renamed or moved) keeps its documents and shows one root-level unavailable or unreadable state rather than thousands of per-file failures, until the person removes the root; a drive returning under another letter, or a different volume at the same letter, does not remove the original's documents.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "Round 8b (orchestrator, under the owner's delegation and the task's rule: never delete indexed content, or approve an index, on uncertainty). (1) An explicit ingest request, a person's or an agent's, is a deliberate add and records the folder's volume; only that request may write the record, carrying the same add marker as the person's add (root_added on ScanRoot, and on BatchRequest for SubmitBatch). (2) No walk ever writes a volume record: a walk that finds no record for its root, or cannot tell which root it belongs to (before re-registration after a Worker restart), is deferred, deletes nothing, and resumes when the roots are registered. (3) A configured collection root is the person's configuration: when its record is absent it is seeded once by the upgrade rule (at least half of up to 50 sampled indexed files present with their indexed size and modification time, or nothing indexed); otherwise it reads unavailable with the same reason and nothing is deleted; it never loses indexing it had on ef205d0e9 while its drive is present. (4) A stalled cutover that blocks close is bounded only if it is new with this lane; if it already happened on main it is a named limit. (5) No volume identity off Windows: a named limit. (6) Upgrade evidence by size and modification time, as decided. (7) The duplicated reason constant is shared only through an existing common module, otherwise cross-referenced.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/DocumentIdentityBootImportTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/ExplicitIngestRecords.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Round 8b, decisions 1 and 2: the queue item the test puts in place before boot stands for an explicit file ingest made before a restart. That request now also records its file's folder volume, and no admission records one, so without the record the item is never admitted and the migration's cutover waits. A new test helper (ExplicitIngestRecords) writes the record the request would have written, before the boot. The item, the Blue identity, the boot and the expected outcome are unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S8",
          "quote": "A root that becomes unreadable or unavailable (expired share credentials, VPN drop, unplugged drive, root folder renamed or moved) keeps its documents and shows one root-level unavailable or unreadable state rather than thousands of per-file failures, until the person removes the root; a drive returning under another letter, or a different volume at the same letter, does not remove the original's documents.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "Round 8b (orchestrator, under the owner's delegation and the task's rule: never delete indexed content, or approve an index, on uncertainty). (1) An explicit ingest request, a person's or an agent's, is a deliberate add and records the folder's volume; only that request may write the record, carrying the same add marker as the person's add (root_added on ScanRoot, and on BatchRequest for SubmitBatch). (2) No walk ever writes a volume record: a walk that finds no record for its root, or cannot tell which root it belongs to (before re-registration after a Worker restart), is deferred, deletes nothing, and resumes when the roots are registered. (3) A configured collection root is the person's configuration: when its record is absent it is seeded once by the upgrade rule (at least half of up to 50 sampled indexed files present with their indexed size and modification time, or nothing indexed); otherwise it reads unavailable with the same reason and nothing is deleted; it never loses indexing it had on ef205d0e9 while its drive is present. (4) A stalled cutover that blocks close is bounded only if it is new with this lane; if it already happened on main it is a named limit. (5) No volume identity off Windows: a named limit. (6) Upgrade evidence by size and modification time, as decided. (7) The duplicated reason constant is shared only through an existing common module, otherwise cross-referenced.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "Round 8b, decision 1: the coordinator now binds a separate producer for an explicit ingest it did not just accept (recovered at boot or resumed), which carries no add marker, so binding takes three producers and the two-producer form was retired. The tests bind the same producer they bound before for the fresh, recovered and watched-root roles (one test keeps its separate watched-root producer). The ingests, the reindex routing and the expected outcomes are unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261013-ef3c41",
          "scenario": "S8",
          "quote": "A root that becomes unreadable or unavailable (expired share credentials, VPN drop, unplugged drive, root folder renamed or moved) keeps its documents and shows one root-level unavailable or unreadable state rather than thousands of per-file failures, until the person removes the root; a drive returning under another letter, or a different volume at the same letter, does not remove the original's documents.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261013-ef3c41 user turn 2026-10-11T16:05Z",
          "delegationScope": "The owner's standing autonomy delegation in the orchestrating session 886833dd (owner turn 2026-10-11T16:05Z, line 94 of that session's owner-turn log), under which the owner answered this task with \"proceed as you think is best\": implementation choices inside the agreed scenarios S1-S9 and P1-P3, not changes to them.",
          "choice": "Round 8b (orchestrator, under the owner's delegation and the task's rule: never delete indexed content, or approve an index, on uncertainty). (1) An explicit ingest request, a person's or an agent's, is a deliberate add and records the folder's volume; only that request may write the record, carrying the same add marker as the person's add (root_added on ScanRoot, and on BatchRequest for SubmitBatch). (2) No walk ever writes a volume record: a walk that finds no record for its root, or cannot tell which root it belongs to (before re-registration after a Worker restart), is deferred, deletes nothing, and resumes when the roots are registered. (3) A configured collection root is the person's configuration: when its record is absent it is seeded once by the upgrade rule (at least half of up to 50 sampled indexed files present with their indexed size and modification time, or nothing indexed); otherwise it reads unavailable with the same reason and nothing is deleted; it never loses indexing it had on ef205d0e9 while its drive is present. (4) A stalled cutover that blocks close is bounded only if it is new with this lane; if it already happened on main it is a named limit. (5) No volume identity off Windows: a named limit. (6) Upgrade evidence by size and modification time, as decided. (7) The duplicated reason constant is shared only through an existing common module, otherwise cross-referenced.",
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
  "session": "VC-ef3c41-20261014",
  "verdict": "accept",
  "digest": "sha256:5b2ee215be15c75e6a1f5543eccf54f8abee1cbd43a569d280b5c406a8346d75",
  "note": "Independent verifier accepted 925610f44 (round 8): D6 fixed live on the real interface; volume recording and upgrade seeding as decided; suites, ArchUnit, PMD clean; every new check fails on ef205d0e9 by assertion; earlier rounds' live S1-S8 verification still holds. Restart-before-re-registration window and profiling reset verified by tests only."
}
```
