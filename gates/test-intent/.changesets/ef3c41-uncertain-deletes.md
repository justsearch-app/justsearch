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
    }
  ]
}
```

## Acceptance records
