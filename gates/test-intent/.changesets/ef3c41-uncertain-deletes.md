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
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-repaired.json",
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
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-preserved.json",
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
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-repaired.json",
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
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-preserved.json",
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
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-repaired.json",
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
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-preserved.json",
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
      "evidence": "gates/test-intent/evidence/ef3c41-uncertain-deletes-after-only.json",
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
    }
  ]
}
```

## Acceptance records
