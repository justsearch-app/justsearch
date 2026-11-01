---
schema: test-intent.v1
task: t-20261030-ingfix
author-role: builder
author-session: 01a1983a-2327-7182-ad8c-6db284629e3e
---

# Test-intent entries

This record covers narrowed WP8 against merged main 58ff43faa8. S-D10 source-storage evidence is withdrawn on every route; watcher create/modify admission restores main’s behavior, with containment retained before content reads (S-D1). Parameterized check names preserve their descriptions and expose method names for exact evidence selection. Assertion wrappers preserve the original expectations while making unexpected before-state exceptions assertion failures. Windows placeholder fixtures are synthetic; actual Windows cloud-placeholder watcher events remain unverified in this Linux environment. Supplementary check, not in newChecks: RootLifecycleOpsConcurrencyTest#staleSecondRemovalCannotDeleteAReaddedRoot guards a race that only this branch's unblocked producer drain (S-D41) could introduce: two removals of one root waiting on the same drain while the root is re-added. Merged main 58ff43faa8 holds the lifecycle monitor across the producer, so the race has no before-state equivalent and the check cannot fail there for its reason; it passes on the candidate (P6).

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RecordedRootPreparationTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The producer-drain observation accepts WAITING as well as BLOCKED on the same lifecycle owner; the removal must still wait for its producer.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D41 (A long scan blocks unrelated root mutations) Block A’s scan and require B’s removal to complete independently.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RootLifecycleOpsConcurrencyTest.java"
      ],
      "class": "New",
      "behaviour": "Reject directory-link roots; allow unrelated removal while a scan is held. The stale-removal fence across remove/re-add is a supplementary check named in the record's prose.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D5 (A directory symlink can become an apparently empty watched folder) Add a symlink to a populated directory and require explicit rejection or actual enumeration.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D41 (A long scan blocks unrelated root mutations) Block A’s scan and require B’s removal to complete independently.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "P6 Remove/re-add of a root keeps working as the recovery route.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/ingfix-wp8.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RootLifecycleOpsConcurrencyTest.java#directoryLinkCannotBecomeAHealthyEmptyWatchedRoot",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RootLifecycleOpsConcurrencyTest.java#heldScanAllowsRemovalOfAnUnrelatedRoot"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/SyncOpsReconcileVerificationTest.java"
      ],
      "class": "New",
      "behaviour": "Forced skipped sync with uncertainty records unverified and retains its previous verification timestamp.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D105 (Forced incomplete sync retains old verification) Return both flags on forced sync and require the unverified recorder updated.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/ingfix-wp8.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/SyncOpsReconcileVerificationTest.java#forcedIncompleteSyncRecordsUnverifiedEvenWhenSkipped"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/WatchedRootsStateTest.java"
      ],
      "class": "New",
      "behaviour": "A blocked root producer must not hold the lifecycle lock needed by an unrelated root mutation.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D41 (A long scan blocks unrelated root mutations) Block A’s scan and require B’s removal to complete independently.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/ingfix-wp8.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/WatchedRootsStateTest.java#blockedRootProducerDoesNotHoldUnrelatedLifecycleMutations"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/ConditionalDeleteReplayRoutesTest.java"
      ],
      "class": "New",
      "behaviour": "Both promotion routes retain uncertain sources and foreign-backed chunks while retiring confirmed nonregular source parents.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D11 (Offline file-to-directory or symlink replacement retains old text) Replace an indexed file with a directory while stopped and require restart reconciliation to retire its text.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C8. **Implied — D11:** Retirement must remove the old parent and its chunks while preserving rows backed by another file; if the replacement is a directory, eligible new descendants still need discovery. Check search results and stored rows after reconciliation (`PruneOps`).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "P1 Product rule: indexed content is never deleted on uncertainty (unreadable source, unknown count, missing or different volume leaves the index as it is).",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/ingfix-wp8.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/ConditionalDeleteReplayRoutesTest.java#ordinaryPromotionRetiresAFolderBeneathAListableParent",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/ConditionalDeleteReplayRoutesTest.java#promotedPointerRetiresAFolderBeneathAListableParent"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/DeletionCommitObligationTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Move injected deletion failure and its reset from broad deleteByIdAndChunks to rooted pruneFile; retain retry/commit obligation assertions.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D11 (Offline file-to-directory or symlink replacement retains old text) Replace an indexed file with a directory while stopped and require restart reconciliation to retire its text.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C8. **Implied — D11:** Retirement must remove the old parent and its chunks while preserving rows backed by another file; if the replacement is a directory, eligible new descendants still need discovery. Check search results and stored rows after reconciliation (`PruneOps`).",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopStaleDeletionFenceTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "Pass the existing RootWitness and root to deletion, move failure injection/order assertions to pruneFile; retain A-before-B ordering and failure semantics.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D11 (Offline file-to-directory or symlink replacement retains old text) Replace an indexed file with a directory while stopped and require restart reconciliation to retire its text.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C8. **Implied — D11:** Retirement must remove the old parent and its chunks while preserving rows backed by another file; if the replacement is a directory, eligible new descendants still need discovery. Check search results and stored rows after reconciliation (`PruneOps`).",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/OriginBoundaryAdmissionTest.java"
      ],
      "class": "New",
      "behaviour": "A watcher event through an outside-root ancestor link is admitted as retryable work, then the running ingestion loop rejects it before hashing or extraction, retains retry ownership and records SOURCE_UNREADABLE.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D1 (An ancestor symlink replacement escapes the admitted root) Replace a queued file’s parent with an outside-root symlink and require rejection before content access.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/ingfix-wp8.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/OriginBoundaryAdmissionTest.java#runningLoopRejectsAQueuedAncestorReplacementBeforeOpeningContent"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/PersistentByteIoSourceTest.java"
      ],
      "class": "New",
      "behaviour": "An ancestor escape during an admitted write receives its own WRITE_FAILED retry rather than sharing a byte-read outage; healthy peer outage deferral remains unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D1 (An ancestor symlink replacement escapes the admitted root) Replace a queued file’s parent with an outside-root symlink and require rejection before content access.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "P1 Product rule: indexed content is never deleted on uncertainty (unreadable source, unknown count, missing or different volume leaves the index as it is).",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/ingfix-wp8.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/PersistentByteIoSourceTest.java#sourcePolicyDependsOnActualAccessAndPreservesHealthyOutage"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/RefusedCleanupConditionalDeleteTest.java"
      ],
      "class": "New",
      "behaviour": "Refusal cleanup retains uncertain sources and foreign-backed chunks while retiring a confirmed nonregular source parent.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D11 (Offline file-to-directory or symlink replacement retains old text) Replace an indexed file with a directory while stopped and require restart reconciliation to retire its text.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C8. **Implied — D11:** Retirement must remove the old parent and its chunks while preserving rows backed by another file; if the replacement is a directory, eligible new descendants still need discovery. Check search results and stored rows after reconciliation (`PruneOps`).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "P1 Product rule: indexed content is never deleted on uncertainty (unreadable source, unknown count, missing or different volume leaves the index as it is).",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/ingfix-wp8.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/RefusedCleanupConditionalDeleteTest.java#conditionalDeleteRetiresAWhenTheParentIsListable"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/SwitchBufferVersionTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Materialize the admitted 17-byte source so the existing atomic job-and-buffer transaction check meets the new admission precondition; retain supplied digest/provenance and transaction assertions.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D1 (An ancestor symlink replacement escapes the admitted root) Replace a queued file’s parent with an outside-root symlink and require rejection before content access.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "P2 Files inside a watched root that are ordinary files keep being admitted exactly as today (no new rejections of valid names or paths).",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/MigrationEnumerationCompletenessTest.java"
      ],
      "class": "New",
      "behaviour": "Migration skips harmless links, built-in and user-excluded trees without hashing them.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D4 (Migration enumeration rejects normally skipped entries) Migrate a root containing a document, symlink and excluded subtree; require skipping without hashing excluded files.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "P3 Symlinks inside roots keep being skipped (not followed) by ordinary scans as today; no symlink is ever followed out of a root.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/ingfix-wp8.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/MigrationEnumerationCompletenessTest.java#enumerationSkipsLinksAndPrunesExcludedSubtrees",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/MigrationEnumerationCompletenessTest.java#nestedSymbolicFileCannotEscapeNoFollowCoverage"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/Release030ForgetSourceObservationTest.java"
      ],
      "class": "New",
      "behaviour": "Release cleanup rejects outside ancestors before observation/hash and retires only rows owned by a nonregular replacement.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D1 (An ancestor symlink replacement escapes the admitted root) Replace a queued file’s parent with an outside-root symlink and require rejection before content access.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D11 (Offline file-to-directory or symlink replacement retains old text) Replace an indexed file with a directory while stopped and require restart reconciliation to retire its text.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C8. **Implied — D11:** Retirement must remove the old parent and its chunks while preserving rows backed by another file; if the replacement is a directory, eligible new descendants still need discovery. Check search results and stored rows after reconciliation (`PruneOps`).",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/ingfix-wp8.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/Release030ForgetSourceObservationTest.java#cleanupReplayRejectsOutsideAncestorBeforeObservationOrHashing",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/Release030ForgetSourceObservationTest.java#releaseRetirementCorrectionPreservesForeignBackedChunks"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/LegacyVduSourceRefreshTest.java"
      ],
      "class": "New",
      "behaviour": "Legacy hashless refresh retains its ingestion root across restart and defers unsafe ancestry, unknown roots and placeholders without hashing or charging attempts.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D1 (An ancestor symlink replacement escapes the admitted root) Replace a queued file’s parent with an outside-root symlink and require rejection before content access.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D2 (Watcher events bypass cloud-placeholder deferral) Deliver a real Windows placeholder event and require deferral without content access.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/ingfix-wp8.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/LegacyVduSourceRefreshTest.java#legacyVduProcessingDefersBeforeAnyHash",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/LegacyVduSourceRefreshTest.java#hashlessSourceRefreshIsTargetedDurableAndEventuallyProvesItsRevision"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherDeleteCrashRecoveryTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Move the process-halt cut after Lucene deletion to PruneOps.pruneFile, preserving the cut after the real deletion and all crash recovery outcomes.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D11 (Offline file-to-directory or symlink replacement retains old text) Replace an indexed file with a directory while stopped and require restart reconciliation to retire its text.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C8. **Implied — D11:** Retirement must remove the old parent and its chunks while preserving rows backed by another file; if the replacement is a directory, eligible new descendants still need discovery. Check search results and stored rows after reconciliation (`PruneOps`).",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherDeleteSafetyTest.java"
      ],
      "class": "New",
      "behaviour": "Live, buffered and candidate retirement preserve chunks backed by another file; restart discovers nonregular replacement descendants, recovers recreated roots and retries failed watcher registration.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D11 (Offline file-to-directory or symlink replacement retains old text) Replace an indexed file with a directory while stopped and require restart reconciliation to retire its text.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D13 (Recreating a Linux root cannot restore its watcher) Recreate the root and require watching and obsolete-document cleanup to recover.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D14 (Failed initial watcher registration loses recovery ownership) Fail first registration, restore availability and require the recovery tick to retry it.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C8. **Implied — D11:** Retirement must remove the old parent and its chunks while preserving rows backed by another file; if the replacement is a directory, eligible new descendants still need discovery. Check search results and stored rows after reconciliation (`PruneOps`).",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/ingfix-wp8.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherDeleteSafetyTest.java#candidateRetirementPreservesCrossFileChunksInBothGenerations",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherDeleteSafetyTest.java#liveDeleteCallbacksPreserveChunksBackedByAnotherFile",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherDeleteSafetyTest.java#bufferedRetirementPreservesChunksBackedByAnotherFile",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherDeleteSafetyTest.java#recreatedRootRecoveryFindsMissedFilesAndRetiresObsoleteText",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherDeleteSafetyTest.java#failedInitialWatcherRegistrationIsRecoveredByTheWorkerTick",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherDeleteSafetyTest.java#restartReconciliationRetiresNonregularReplacements"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherMigrationReplayTest.java"
      ],
      "class": "New",
      "behaviour": "Scoped replay retirement removes owned parent/chunks, preserves foreign chunks and discovers directory descendants.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D11 (Offline file-to-directory or symlink replacement retains old text) Replace an indexed file with a directory while stopped and require restart reconciliation to retire its text.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C8. **Implied — D11:** Retirement must remove the old parent and its chunks while preserving rows backed by another file; if the replacement is a directory, eligible new descendants still need discovery. Check search results and stored rows after reconciliation (`PruneOps`).",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/ingfix-wp8.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherMigrationReplayTest.java#retiredSourcePreservesChunksBackedByAnotherLiveFile"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopTest.java"
      ],
      "class": "New",
      "behaviour": "Stale-source removal requires verified absence and rooted, scoped retirement.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D11 (Offline file-to-directory or symlink replacement retains old text) Replace an indexed file with a directory while stopped and require restart reconciliation to retire its text.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C8. **Implied — D11:** Retirement must remove the old parent and its chunks while preserving rows backed by another file; if the replacement is a directory, eligible new descendants still need discovery. Check search results and stored rows after reconciliation (`PruneOps`).",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/ingfix-wp8.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopTest.java#staleSourceHandlerMarksIdentityOnlyForAVerifiedAbsentSource"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/JobBatchExtractorForcedPathTest.java"
      ],
      "class": "New",
      "behaviour": "Queued placeholders defer without content access or attempts; ancestor replacement and a raw parent-hop through a symlink cannot reach extraction.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D1 (An ancestor symlink replacement escapes the admitted root) Replace a queued file’s parent with an outside-root symlink and require rejection before content access.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D2 (Watcher events bypass cloud-placeholder deferral) Deliver a real Windows placeholder event and require deferral without content access.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/ingfix-wp8.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/JobBatchExtractorForcedPathTest.java#queuedPlaceholderDefersWithoutExtractionOrAnAttempt",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/JobBatchExtractorForcedPathTest.java#replacedAncestorNeverReachesExtraction",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/JobBatchExtractorForcedPathTest.java#parentHopThroughALinkCannotOpenADifferentFileThanTheNormalizedClaim"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/UncertainAbsenceGatesTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "Observe deleteMissingSource with the same file plus root and witness; retain completion/journal ordering assertions.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D11 (Offline file-to-directory or symlink replacement retains old text) Replace an indexed file with a directory while stopped and require restart reconciliation to retire its text.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "P1 Product rule: indexed content is never deleted on uncertainty (unreadable source, unknown count, missing or different volume leaves the index as it is).",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/WorkerIngestionAuthorityTest.java"
      ],
      "class": "New",
      "behaviour": "Unsafe ancestors cannot admit outside content or prove deletion.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D1 (An ancestor symlink replacement escapes the admitted root) Replace a queued file’s parent with an outside-root symlink and require rejection before content access.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/ingfix-wp8.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/WorkerIngestionAuthorityTest.java#ancestorReplacementNeverAdmitsOutsideContentOrProvesDeletion"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/BufferedFileDeleteReceiptProofTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Observe scoped PruneOps retirement and its guard rather than broad coordinator deletion; retain receipt-volume proof, no second delete on reappearance, and unsafe-receipt rejection.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D11 (Offline file-to-directory or symlink replacement retains old text) Replace an indexed file with a directory while stopped and require restart reconciliation to retire its text.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "P1 Product rule: indexed content is never deleted on uncertainty (unreadable source, unknown count, missing or different volume leaves the index as it is).",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/ConfirmedDeletionMarkerTest.java"
      ],
      "class": "New",
      "behaviour": "Replacement links, including dangling links, are not followed to mark source identities deleted.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D11 (Offline file-to-directory or symlink replacement retains old text) Replace an indexed file with a directory while stopped and require restart reconciliation to retire its text.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "P1 Product rule: indexed content is never deleted on uncertainty (unreadable source, unknown count, missing or different volume leaves the index as it is).",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/ingfix-wp8.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/ConfirmedDeletionMarkerTest.java#replacementLinksAreNotFollowedForIdentityDeletion"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/LinuxReturnedRootTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Observe scoped PruneOps retirement rather than broad coordinator deletion; retain the recorded-volume proof and reappearance behavior.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D11 (Offline file-to-directory or symlink replacement retains old text) Replace an indexed file with a directory while stopped and require restart reconciliation to retire its text.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "P1 Product rule: indexed content is never deleted on uncertainty (unreadable source, unknown count, missing or different volume leaves the index as it is).",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RootPresenceGuardTest.java"
      ],
      "class": "New",
      "behaviour": "Dangling ancestors cannot prove deletion. Supplementary preservation check: RootPresenceGuardTest#separatelyRecordedNestedRootUsesItsOwnVolumeEvidence.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D1 (An ancestor symlink replacement escapes the admitted root) Replace a queued file’s parent with an outside-root symlink and require rejection before content access.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "P1 Product rule: indexed content is never deleted on uncertainty (unreadable source, unknown count, missing or different volume leaves the index as it is).",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/ingfix-wp8.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RootPresenceGuardTest.java#danglingAncestorDoesNotProveAnIndexedSourceWasDeleted"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RootWatcherRegistryTest.java"
      ],
      "class": "New",
      "behaviour": "A failed first registration retains collection and retry ownership without publishing a healthy subscription; same-volume root recreation replaces the watcher incarnation.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D13 (Recreating a Linux root cannot restore its watcher) Recreate the root and require watching and obsolete-document cleanup to recover.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D14 (Failed initial watcher registration loses recovery ownership) Fail first registration, restore availability and require the recovery tick to retry it.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/ingfix-wp8.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RootWatcherRegistryTest.java#initialRegistrationFailureRetainsCollectionAndRetryOwnership",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RootWatcherRegistryTest.java#recreatedDirectoryOnRecordedVolumeGetsANewWatcherIdentity"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SyncDirectoryOpsCandidateDiscoveryTest.java"
      ],
      "class": "New",
      "behaviour": "Candidate discovery omits harmless live/dangling links and retires indexed paths replaced by directories or links.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D3 (A symlink blocks candidate reconciliation and latches uncertainty) Reconcile a candidate root containing a harmless symlink and require successful eligibility handling.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D11 (Offline file-to-directory or symlink replacement retains old text) Replace an indexed file with a directory while stopped and require restart reconciliation to retire its text.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/ingfix-wp8.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SyncDirectoryOpsCandidateDiscoveryTest.java#harmlessLiveAndDanglingLinksDoNotPreventCandidateDiscovery",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SyncDirectoryOpsCandidateDiscoveryTest.java#replacementOfIndexedFileRetiresItsOldSource"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SyncDirectoryOpsChangedStampTest.java"
      ],
      "class": "New",
      "behaviour": "A failed later census page withholds verification while disk discovery continues; either census cap still permits repeated recovery of missed additions and edits.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D12 (Large roots lose periodic recovery for missed watcher events) Exceed either cap, omit a watcher event and require a later periodic sync to find the change.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D104 (Partial indexed-path census can produce verified sync) Fail a later census page and require verification withheld.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/ingfix-wp8.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SyncDirectoryOpsChangedStampTest.java#failedLaterCensusPageWithholdsVerificationAndStillWalksDisk",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SyncDirectoryOpsChangedStampTest.java#censusCapsStillAllowPeriodicRecoveryOfAdditionsAndEdits"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SyncDirectoryOpsWalkSkipPolicyTest.java"
      ],
      "class": "New",
      "behaviour": "Exceeding the retained-character budget still admits disk additions while reporting deletion detection unverified.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D12 (Large roots lose periodic recovery for missed watcher events) Exceed either cap, omit a watcher event and require a later periodic sync to find the change.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/ingfix-wp8.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SyncDirectoryOpsWalkSkipPolicyTest.java#longIndexedPathsReachMemoryBudgetBeforeEntryCountLimit"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceCandidateReconciliationTest.java"
      ],
      "class": "New",
      "behaviour": "Harmless symlinks alongside an eligible addition do not latch candidate replay uncertainty and permit complete reconciliation.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D3 (A symlink blocks candidate reconciliation and latches uncertainty) Reconcile a candidate root containing a harmless symlink and require successful eligibility handling.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/ingfix-wp8.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceCandidateReconciliationTest.java#harmlessLinkDoesNotLatchCandidateReplayUncertainty"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceForceReindexWiringTest.java"
      ],
      "class": "New",
      "behaviour": "Names containing consecutive dots are admitted in both ordinary and forced batch paths; force is stored atomically in each admitted queue entry and never sent through markForced.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D6 (Batch sanitization rejects valid names containing consecutive dots) Submit an existing readable `release..notes.txt` through batch admission.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "P2 Files inside a watched root that are ordinary files keep being admitted exactly as today (no new rejections of valid names or paths).",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/ingfix-wp8.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceForceReindexWiringTest.java#consecutiveDotsInsideNamesAreAdmitted"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerScanOpsTest.java"
      ],
      "class": "New",
      "behaviour": "Skip-heavy walks emit intermediate progress; captured scans reject replaced ancestors before hashing; excluded subtrees are pruned without invented descendant counts; symlink roots fail explicitly and unreadable eligible files produce incomplete coverage.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D122 (Skip-heavy scans emit no intermediate progress) Scan over 100 excluded files and require increasing intermediate walked/skipped counts.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D1 (An ancestor symlink replacement escapes the admitted root) Replace a queued file’s parent with an outside-root symlink and require rejection before content access.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D9 (User-excluded directories are still traversed) Count visits below an excluded directory and require subtree pruning.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D5 (A directory symlink can become an apparently empty watched folder) Add a symlink to a populated directory and require explicit rejection or actual enumeration.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D103 (Inaccessible scan members produce clean completion) Scan an inaccessible subtree as an unprivileged user and require incomplete coverage.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/ingfix-wp8.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerScanOpsTest.java#excludedFilesStillEmitIntermediateProgress",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerScanOpsTest.java#capturedScanRejectsAncestorReplacementBeforeHashing",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerScanOpsTest.java#excludedDirectoryIsPrunedWithoutCountingDescendants",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerScanOpsTest.java#symbolicDirectoryRootReportsFailureWithoutAdmittingTarget",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerScanOpsTest.java#unreadableEligibleFileMakesOrdinaryScanIncomplete"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerVduExclusionClaimTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Clear constructor-time queue observations before the refused VDU callback so the same no-queue-interaction oracle isolates that callback.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "P1 Product rule: indexed content is never deleted on uncertainty (unreadable source, unknown count, missing or different volume leaves the index as it is).",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/ConditionalDeleteReplayRoutesTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Materialize a retained ordinary claim before replacing its source with a directory; unsafe nonregular sources no longer gain a fresh queue admission. Inspect the parent row directly because a preserved foreign-backed chunk deliberately retains its parent ID; assert that chunk independently.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D1 (An ancestor symlink replacement escapes the admitted root) Replace a queued file’s parent with an outside-root symlink and require rejection before content access.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D11 (Offline file-to-directory or symlink replacement retains old text) Replace an indexed file with a directory while stopped and require restart reconciliation to retire its text.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C8. **Implied — D11:** Retirement must remove the old parent and its chunks while preserving rows backed by another file; if the replacement is a directory, eligible new descendants still need discovery. Check search results and stored rows after reconciliation (`PruneOps`).",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/RefusedCleanupConditionalDeleteTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Materialize a retained ordinary claim before replacing its source with a directory. Inspect the parent row directly because a preserved foreign-backed chunk deliberately retains its parent ID; assert that chunk independently.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D1 (An ancestor symlink replacement escapes the admitted root) Replace a queued file’s parent with an outside-root symlink and require rejection before content access.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D11 (Offline file-to-directory or symlink replacement retains old text) Replace an indexed file with a directory while stopped and require restart reconciliation to retire its text.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C8. **Implied — D11:** Retirement must remove the old parent and its chunks while preserving rows backed by another file; if the replacement is a directory, eligible new descendants still need discovery. Check search results and stored rows after reconciliation (`PruneOps`).",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RootLifecycleOpsConcurrencyTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Existing producer/removal preservation assertions observe WAITING as well as BLOCKED on the same lifecycle monitor after the producer fence releases that monitor.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D41 (A long scan blocks unrelated root mutations) Block A’s scan and require B’s removal to complete independently.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/LegacyVduSourceRefreshTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Declare the fixture ingestion root so ordinary hashless-refresh cases satisfy the new owning-boundary requirement.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D1 (An ancestor symlink replacement escapes the admitted root) Replace a queued file’s parent with an outside-root symlink and require rejection before content access.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherMigrationReplayTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Replace a formerly uncertainty-producing symlink fixture with an unreadable eligible subtree: deliberate symlink omission is now successful; genuine incomplete coverage must still fence cutover. Reflection accepts the explicit root-authority constructor used by guarded retirement while retaining the before-state constructor fallback.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D3 (A symlink blocks candidate reconciliation and latches uncertainty) Reconcile a candidate root containing a harmless symlink and require successful eligibility handling.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D103 (Inaccessible scan members produce clean completion) Scan an inaccessible subtree as an unprivileged user and require incomplete coverage.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/Release030ForgetSourceObservationTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Use real default methods on the mocked authority and move existing exact hash-call observations to sha256WithinRoot; keep the same hash count and no-read policy oracles.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D1 (An ancestor symlink replacement escapes the admitted root) Replace a queued file’s parent with an outside-root symlink and require rejection before content access.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceCandidateReconciliationTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Expose the existing candidate/serving runtimes in the fixture, observe pruneFile plus owned-file census instead of broad deletion, and retain no-deletion uncertainty and commit expectations. The harmless-link fixture supplies the same true result and arguments through enqueueMaintenanceFileForGeneration, the queue admission called after the main merge, instead of enqueueAndBufferFileForGeneration; every existing assertion remains.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D11 (Offline file-to-directory or symlink replacement retains old text) Replace an indexed file with a directory while stopped and require restart reconciliation to retire its text.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C8. **Implied — D11:** Retirement must remove the old parent and its chunks while preserving rows backed by another file; if the replacement is a directory, eligible new descendants still need discovery. Check search results and stored rows after reconciliation (`PruneOps`).",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding",
        "instrumentation"
      ],
      "whatMoved": "Give successful stale-source deletion fixtures a missing source and rooted witness; observe scoped pruneFile rather than broad deletion, retaining deletion counters, retry propagation and confirmed identity marking. The retained new check is top-level for method evidence selection; existing nested tests retain their assertions. Remove the unused storage-only afterIdentity fault-injection overload and no-op delegator; retained fixture callers receive the same identity values.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D11 (Offline file-to-directory or symlink replacement retains old text) Replace an indexed file with a directory while stopped and require restart reconciliation to retire its text.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "P1 Product rule: indexed content is never deleted on uncertainty (unreadable source, unknown count, missing or different volume leaves the index as it is).",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/AdmissionSourceHashTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The direct admissionIdentity helper keeps its existing placeholder probe; worker admission injects the identical placeholder condition through CloudPlaceholderDetector, the detector that route now calls. Both probes are verified reached; all deferral and no-hash assertions are retained.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C24. **Implied — D2:** A file can become cloud-only after admission, and a deferred placeholder can later hydrate. Check processing of an already queued placeholder avoids content access, then a later local-file event indexes normally without losing existing text or collection attribution.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/RestoredMtimeQueuedRestartTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Move the same IOException injection from unrooted sha256 to sha256WithinRoot on the same source file and verify it was called. Keep the pending-retry, unchanged-file and force-retention assertions.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "P1 Product rule: indexed content is never deleted on uncertainty (unreadable source, unknown count, missing or different volume leaves the index as it is).",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/AdmissionHashLivenessTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Move candidate-route equal-metadata rewriting from unrooted sha256 to sha256WithinRoot on the same source and root, after the hash and before admission takes the held lock. Verify the rooted seam was invoked. Keep every state, revision, source-proof and claimability assertion.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "P2 Files inside a watched root that are ordinary files keep being admitted exactly as today (no new rejections of valid names or paths).",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRootRetirementTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The same removal-waits observation accepts WAITING as well as BLOCKED after drainRootProducers releases the lifecycle monitor. Inputs, producer hold, completion/drain and timeout assertions are retained.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Block A’s scan and require B’s removal to complete independently.",
          "location": "task t-20261030-ingfix agreement S-D41"
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/LinuxReturnedRootTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Recreate the root and require watching and obsolete-document cleanup to recover.",
          "location": "task t-20261030-ingfix agreement S-D13"
        },
        {
          "kind": "owner-words",
          "quote": "Recreate a Linux watched root on its recorded volume with a new inode while the registry retains its old identity; require automatic watcher restoration, missed-file discovery and obsolete-document cleanup.",
          "location": "task t-20261030-ingfix agreement C10"
        }
      ],
      "conflictSearch": "Searched docs/decisions and docs/ for RootWatcherRegistry, RootPresenceGuard, watched-root recreation, root inode and retained identities. ADR-0028 concerns resolver retention on explicit unwatch, not watcher reincarnation. docs/explanation/11-index-schema-migration.md (#758) says deletion requires the same folder/volume; those held-operation guards remain and a successful same-volume recovery establishes a new watcher witness before reconciliation. docs/tempdocs/418 and 626 describe watcher ownership/deletion safety; no decision requires permanently refusing same-volume recreated roots. Removed replacedFolderBeforeRecoveryNeverBecomesTheNewIdentity and handoverRetainsTheFolderWitnessEvenWhileItsPathIsMissing because their refusal-to-adopt oracle directly contradicts S-D13/C10. Different-volume/symlink/mid-operation replacement guards remain."
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineForgetInProcessTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Migrate a root containing a document, symlink and excluded subtree; require skipping without hashing excluded files.",
          "location": "task t-20261030-ingfix agreement S-D4"
        },
        {
          "kind": "owner-words",
          "quote": "Exclusions keep the same matching semantics; excluded files are never admitted.",
          "location": "task t-20261030-ingfix agreement P4"
        },
        {
          "kind": "owner-words",
          "quote": "Migration must skip symlinks, special files, built-in skipped trees and user-excluded trees before queue hashing or subtree traversal.",
          "location": "task t-20261030-ingfix agreement C27"
        }
      ],
      "conflictSearch": "Searched docs/decisions and docs/ for migration exclusion, excluded-file admission and native policy skip. No decision requires admitting an excluded file just to create a skipped candidate receipt. R9HotPublicSaveAfterNativePolicySkipReadmitsCurrentBytesAndKeepsCompletedForgetReceipt requires that forbidden receipt in its setup, so remove R9 and its exclusively used helper branches; keep the native migration control and completed-forget preservation checks."
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WatcherUpsertCompatibilityTest.java"
      ],
      "class": "Incidental",
      "assessment": {
        "purpose": "Preserve main watcher create/modify admission for folders, in-root symlinks, vanished sources, atomic-save temporary files and ordinary files after withdrawing eager containment.",
        "callersAndStateOwners": "WorkerIngestService.acceptWatcherUpsert routes callbacks to the durable job queue; content admission remains guarded in the consumer and candidate hash routes.",
        "indirectBehaviours": "An event-only source shape must not throw into WatcherCallbacks.failure and latch runtime replay uncertainty. The queue still receives the same eligible entry or built-in policy omission.",
        "evidence": "WatcherUpsertCompatibilityTest#watcherSourcesKeepMainAdmissionBehaviour runs the same five cases against 58ff43faa8 and the candidate; the assertions check no boundary exception and the actual enqueue path/root for each event, including an atomic-save temporary path already moved into place. The standalone WP8WatcherProbe also runs the five cases through real SQLite on both trees and observes one queued job with root/collection preserved and replayCertain=true. Results recorded in tmp/wp8-builder-7-report.txt.",
        "unresolvedRisks": "No Windows placeholder provider is available; these five preservation fixtures use real local filesystem paths and a mocked queue."
      }
    }
  ]
}
```

## Acceptance records
