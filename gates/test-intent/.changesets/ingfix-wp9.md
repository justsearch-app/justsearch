---
schema: test-intent.v1
task: t-20261030-ingfix
author-role: builder
author-session: 01a19138-440e-7410-b519-33d274de04ae
---

# Test-intent entries

The gate flags 48 existing files between 9eb36cc7923b530624f7ced8ea0bc678f0011b31 and this branch. The eight isolated new test sources remain exempt. Apply Excludes root-relative matching for completed ad-hoc imports (C14 partly) is open and is not claimed here.

Named newChecks are assertion fail-before checks only; supplementary preservation checks are recorded in prose. No test-support overlays are needed: both NoVolumeIdentityExtension helpers already exist unchanged at the before-state. Before-state compilation uses gates/test-intent/compatibility/ingfix-wp9-before.patch, which adds only a no-op collection cancellation signature and a journal overload forwarding to the old method. Two Mockito verifications are wrapped in assertDoesNotThrow so the evidence classifier recognizes their unchanged assertions. No acceptance record is authored here.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineForgetMovePublicationTest.java"
      ],
      "class": "Defect pin",
      "behaviour": "Accepted MOVE replaces stale or racing indexed destination content with the source parent, original source UID and source child set; repeating the mapping retains that family.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C15. D34 — rename onto a physically vacant but historically indexed destination must replace its family: SQLite replaces destination identity while Lucene retains destination parents/chunks; use distinct source/destination content and require exactly the source-owned family at the destination.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C16. D34/D35 — replay must preserve the family already moved: after a successful rename or lost response, repeat the mapping; require no deletion of the source-owned destination family, duplicate parents or identity reminting.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C21. D34 — destination-creation races need a consistent result: the brief explicitly includes these races; create the destination between physical movement and index update, and verify the indexed family agrees with the accepted file-operation outcome.",
          "label": "agent choice"
        }
      ],
      "defectSource": {
        "kind": "defect-source",
        "reproduction": "Move a real source file onto a physically vacant destination that has old indexed rows, or publish a competing indexed destination in the move callback. Inspect destination ownership, content and count, then replay the mapping.",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C15. D34 — rename onto a physically vacant but historically indexed destination must replace its family: SQLite replaces destination identity while Lucene retains destination parents/chunks; use distinct source/destination content and require exactly the source-owned family at the destination.",
          "label": "agent choice"
        }
      },
      "reproduction": "Move a real source file onto a physically vacant destination that has old indexed rows, or publish a competing indexed destination in the move callback. Inspect destination ownership, content and count, then replay the mapping.",
      "evidence": "gates/test-intent/evidence/ingfix-wp9.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineForgetMovePublicationTest.java#C21AcceptedPhysicalMoveReplacesHistoricOrRacingDestinationFamily"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/SyncOpsReconcileVerificationTest.java"
      ],
      "class": "New",
      "behaviour": "Periodic maintenance schedules indexed-source reconciliation even with no watched roots.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D15 (Completed ad-hoc sources have no automatic deletion reconciliation) Delete a completed out-of-root source and require ordinary maintenance to remove its rows.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C8. D15 — completed ad-hoc deletion must reconcile with zero watched roots: periodic sync returns immediately when the root list is empty; complete an out-of-root ingest, delete it and require ordinary maintenance to remove its entire family.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/ingfix-wp9.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/SyncOpsReconcileVerificationTest.java#D15PeriodicMaintenanceRevisitsIndexedSourcesWithZeroWatchedRoots"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopStaleDeletionFenceTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AdversarialCorpusIngestionTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/UncertainAbsenceGatesTest.java"
      ],
      "class": "Defect pin",
      "behaviour": "Deletion errors propagate for retry; absent/nonregular-source retirements across serving and candidate writes stay unacknowledged until the commit owner drains the outcome journal. Existing post-drain outcome and generation-order assertions remain. Volume-evidence setup is an adaptation to the volume rule, not permission to infer absence from present-file admission.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C1. D33 — deletion failure must retain recoverable work: StaleSourceHandler swallows failure while both extractor and stale resolver complete the claim; inject failure at each entry point and require eventual deletion.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C2. D33 — deletion must survive the acknowledgement/commit crash window: normal writes defer completion through IngestionOutcomeJournal, but missing-source deletes complete immediately; crash before Lucene commit, restart and require the obligation to remain.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "Admission of files that are present and readable (adding, ingesting, re-ingesting) keeps today's rule: a folder with no recorded volume and no current volume identity is admitted. Any decision that infers absence and removes or prunes indexed content requires proof: a recorded volume equal to the current identity, or a declared root's recorded volume; no record, no current identity, or a different volume means unproven, and the rows stay (retry later).",
          "label": "agent choice"
        }
      ],
      "defectSource": {
        "kind": "defect-source",
        "safeguard": "no data loss",
        "reproduction": "Inject an A or B delete failure and require an exception; extract a missing or nonregular source and require no completed outcome before commit; run the stale snapshot resolver and require a journaled transition rather than immediate claim completion."
      },
      "reproduction": "Inject an A or B delete failure and require an exception; extract a missing or nonregular source and require no completed outcome before commit; run the stale snapshot resolver and require a journaled transition rather than immediate claim completion.",
      "evidence": "gates/test-intent/evidence/ingfix-wp9.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopStaleDeletionFenceTest.java#deletionKeepsAThenBOrderingAndPropagatesPartialFailureForRetry",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AdversarialCorpusIngestionTest.java#directoryIsAdmissionSkipped",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopTest.java#missingSourceRetiresTheActiveGenerationsTextToo",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopTest.java#nonRegularSourceRetiresTheActiveGenerationsTextToo",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/UncertainAbsenceGatesTest.java#theFreshnessCheckStillRetiresAGenuinelyDeletedSource"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/ForgetMutationBoundaryTest.java"
      ],
      "class": "Defect pin",
      "behaviour": "Folder Forget durably removes supported pathless legacy parents and their owned children while preserving the sibling path boundary.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C22. D38 — removal and its completion proof must share legacy ownership coverage: prefix deletion and strict counting use only path, while PruneOps already recognizes filesystem IDs and pathless children; test supported pathless parents and owned chunks through both removal and Folder Forget.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C23. D38 — broader legacy matching must retain path boundaries and exclusions: test sibling bc beside target b, mixed separators, accepted survivors, excluded subtrees and rows whose explicit backing path differs from their ID; verify deletion and proof agree without pulling unrelated rows into scope.",
          "label": "agent choice"
        }
      ],
      "defectSource": {
        "kind": "defect-source",
        "safeguard": "no leak across a requested scope",
        "reproduction": "Index a filesystem-ID parent without path and a pathless owned child under b, with an unrelated bc sibling; Forget b, inspect and reopen the index."
      },
      "reproduction": "Index a filesystem-ID parent without path and a pathless owned child under b, with an unrelated bc sibling; Forget b, inspect and reopen the index.",
      "evidence": "gates/test-intent/evidence/ingfix-wp9.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/ForgetMutationBoundaryTest.java#D38FolderForgetRemovesLegacyParentAndPathlessOwnedChildDurably"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/NoVolumeIdentityRootTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/LinuxRootVolumeSafetyTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RootVolumeUnknownIdentityTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/WorkerIngestionAuthorityTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineLinuxRootVolumeLiveTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030AdmissionDebtTest.java"
      ],
      "class": "Defect pin",
      "behaviour": "Present files remain admitted without current/recorded volume identity, but unknown volume, lost positive proof and unproven nonregular replacements never authorize deletion. Old expectations that absence could be proven with no volume identity are replaced by the volume rule.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "Admission of files that are present and readable (adding, ingesting, re-ingesting) keeps today's rule: a folder with no recorded volume and no current volume identity is admitted. Any decision that infers absence and removes or prunes indexed content requires proof: a recorded volume equal to the current identity, or a declared root's recorded volume; no record, no current identity, or a different volume means unproven, and the rows stay (retry later).",
          "label": "agent choice"
        }
      ],
      "defectSource": {
        "kind": "defect-source",
        "safeguard": "no data loss",
        "reproduction": "Use absent volume lookup with declared and ad-hoc roots; lose volume lookup after a positive proof; replace a readable source with a directory. Require retained rows or retryable ROOT_UNAVAILABLE and no invented volume. Native mount/UUID and boot campaign cases in the grouped files require their own environment and are supplementary, not claimed as executed here."
      },
      "reproduction": "Use absent volume lookup with declared and ad-hoc roots; lose volume lookup after a positive proof; replace a readable source with a directory. Require retained rows or retryable ROOT_UNAVAILABLE and no invented volume. Native mount/UUID and boot campaign cases in the grouped files require their own environment and are supplementary, not claimed as executed here.",
      "evidence": "gates/test-intent/evidence/ingfix-wp9.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/NoVolumeIdentityRootTest.java#withNoVolumeIdentityARootAdmitsPresentFilesAndKeepsUnprovenDeletions",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/WorkerIngestionAuthorityTest.java#C10PresentFileAdmissionDoesNotAuthorizeRetirementOfNonregularReplacement",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/LinuxRootVolumeSafetyTest.java#missingLookupAdmitsPresentSourcesButCannotProveAbsence",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RootVolumeUnknownIdentityTest.java#C10UnrecordedFolderCannotProveAbsenceWithoutVolumeIdentity",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RootVolumeUnknownIdentityTest.java#C10PreviouslyPositiveProofCannotSurviveLossOfCurrentVolumeIdentity",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RootVolumeUnknownIdentityTest.java#deliberatelyAddedAdHocFolderStillAdmitsPresentFilesWithoutVolumeIdentity",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RootVolumeUnknownIdentityTest.java#initiallyUnknownVolumeIsNotDeferredAndFinishesSeeding"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/rag/ChunkDocumentWriterLineFieldsTest.java"
      ],
      "class": "Defect pin",
      "behaviour": "Per-document metadata indexing preserves prefix line/heading laws at every offset, including inside headings, CRLF and PDF/Office markers; fixed-seed generated inputs exercise whitespace and heading boundaries.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C29. D42 — metadata work must scale with content plus chunk count: each child scans prefixes for lines and headings while publication holds the shared fence; compare similarly chunked sizes and heading densities, checking scan/allocation work and fence duration.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C30. D42 — optimization must preserve metadata semantics: test overlapping chunks, leading whitespace, newline boundaries, CRLF, heading levels, no-heading documents, structured PDF/Office markers and offsets inside heading lines; compare line/citation metadata with the existing laws.",
          "label": "agent choice"
        }
      ],
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C29. D42 — metadata work must scale with content plus chunk count: each child scans prefixes for lines and headings while publication holds the shared fence; compare similarly chunked sizes and heading densities, checking scan/allocation work and fence duration.",
          "label": "agent choice"
        },
        "reproduction": "Build the per-document metadata index and compare its public lineNumber and precedingHeading observations with the existing prefix laws at every offset of representative and generated documents."
      },
      "reproduction": "Build the per-document metadata index and compare its public lineNumber and precedingHeading observations with the existing prefix laws at every offset of representative and generated documents.",
      "evidence": "gates/test-intent/evidence/ingfix-wp9.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/rag/ChunkDocumentWriterLineFieldsTest.java#D42IndexedMetadataKeepsPrefixLawsAtEveryOffsetIncludingInsideHeadings"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceCandidateReconciliationTest.java"
      ],
      "class": "Defect pin",
      "behaviour": "Candidate reconciliation cannot delete without recorded/current volume proof; collection removal cancels its queued work and refreshes the snapshot used for deletion counts before committing.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "Admission of files that are present and readable (adding, ingesting, re-ingesting) keeps today's rule: a folder with no recorded volume and no current volume identity is admitted. Any decision that infers absence and removes or prunes indexed content requires proof: a recorded volume equal to the current identity, or a declared root's recorded volume; no record, no current identity, or a different volume means unproven, and the rows stay (retry later).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C4. D37 — removing a collection must invalidate pending, claimed and already-extracted work: JobBatchWriter trusts claim ownership, which collection removal leaves intact; pause each stage, remove the collection, resume and require no recreated parents or chunks.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C36. D129 — counts must describe the rows actually removed: collection counting acquires an older searcher; delete immediately after unrefreshed publication, include parents and chunks, repeat deletion, and exercise serving/candidate routing.",
          "label": "agent choice"
        }
      ],
      "defectSource": {
        "kind": "defect-source",
        "reproduction": "Reconcile a missing candidate source under unknown volume and require refusal without deletes. Exercise pre-Green path and collection deletion and require collection queue cancellation and the refreshes needed for current-snapshot counts.",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "Admission of files that are present and readable (adding, ingesting, re-ingesting) keeps today's rule: a folder with no recorded volume and no current volume identity is admitted. Any decision that infers absence and removes or prunes indexed content requires proof: a recorded volume equal to the current identity, or a declared root's recorded volume; no record, no current identity, or a different volume means unproven, and the rows stay (retry later).\nC4. D37 — removing a collection must invalidate pending, claimed and already-extracted work: JobBatchWriter trusts claim ownership, which collection removal leaves intact; pause each stage, remove the collection, resume and require no recreated parents or chunks.\nC36. D129 — counts must describe the rows actually removed: collection counting acquires an older searcher; delete immediately after unrefreshed publication, include parents and chunks, repeat deletion, and exercise serving/candidate routing.",
          "label": "agent choice"
        }
      },
      "reproduction": "Reconcile a missing candidate source under unknown volume and require refusal without deletes. Exercise pre-Green path and collection deletion and require collection queue cancellation and the refreshes needed for current-snapshot counts.",
      "evidence": "gates/test-intent/evidence/ingfix-wp9.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceCandidateReconciliationTest.java#candidateReconciliationKeepsAbsentRowsWithoutRecordedOrCurrentVolume",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceCandidateReconciliationTest.java#sameRuntimePreGreenJournalsBeforeExactlyOneServingEffectForEachDelete"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/excludes/ExcludesOutcomeTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/DeletionCompletionOutcomeTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/PeriodicCycleRemovedRootTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/PeriodicRootVolumeCheckTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation",
        "input re-encoding"
      ],
      "whatMoved": "Existing mocks now answer empty indexed-source enumeration/reconciliation as the new interfaces do for empty scope. Their original success, failure and removed-root/volume oracles are unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D15 (Completed ad-hoc sources have no automatic deletion reconciliation) Delete a completed out-of-root source and require ordinary maintenance to remove its rows.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D16 (Apply Excludes omits indexed files outside watched roots) Apply a matching exclusion to an indexed out-of-root file and require removal.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/CommittedNativeEffectsTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation",
        "input re-encoding"
      ],
      "whatMoved": "Replace mock default-method counting with a real IndexCountOps against the same fixture searcher; the original replay survivor, removal and count predicates remain unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C22. D38 — removal and its completion proof must share legacy ownership coverage: prefix deletion and strict counting use only path, while PruneOps already recognizes filesystem IDs and pathless children; test supported pathless parents and owned chunks through both removal and Folder Forget.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C23. D38 — broader legacy matching must retain path boundaries and exclusions: test sibling bc beside target b, mixed separators, accepted survivors, excluded subtrees and rows whose explicit backing path differs from their ID; verify deletion and proof agree without pulling unrelated rows into scope.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopCutoverPauseTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation",
        "input re-encoding"
      ],
      "whatMoved": "Move the outcome-write latch and verification from immediate markClaimDone to markDoneTransitions, the journal drain used by the commit owner. The in-flight pause/wait/resume oracle remains. Supplementary preservation check: modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopCutoverPauseTest.java#successfulPauseStopsPollingAndResumeReusesTheSameThread.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C2. D33 — deletion must survive the acknowledgement/commit crash window: normal writes defer completion through IngestionOutcomeJournal, but missing-source deletes complete immediately; crash before Lucene commit, restart and require the obligation to remain.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "Admission of files that are present and readable (adding, ingesting, re-ingesting) keeps today's rule: a folder with no recorded volume and no current volume identity is admitted. Any decision that infers absence and removes or prunes indexed content requires proof: a recorded volume equal to the current identity, or a declared root's recorded volume; no record, no current identity, or a different volume means unproven, and the rows stay (retry later).",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/CommittedBootRootReconciliationTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/ConditionalDeleteReplayRoutesTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineIndexingWorkflowTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineMigrationWatcherRemovalTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EnginePruneReappearedFileTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineSyncDirectoryTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineTestHarness.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/NestedWatchedRootTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/PruneLatePublicationTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/PruneMissingUncertainRootTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/PrunePrefixReplayFenceTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/PrunePrefixReplayUncertainRootTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/PruneWriterFenceTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/RefusedCleanupConditionalDeleteTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/Release030ForgetRootReplayTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/ReturnedRootCurrentByteReplayTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RootPresenceGuardTest.java",
        "modules/ui/src/integrationTest/java/io/justsearch/app/engine/RuntimeReloadProducersHttpIntegrationTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/UnreadableAndReplacedSourceQueueTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherDeleteSafetyTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherMigrationReplayTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/WatcherReloadRetryTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WatcherTransitionMatrixGuardTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceDocumentIdentityTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceSyncDirectoryPhaseTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerMethvinWatcherTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/WorkerUserExclusionsEngineTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation",
        "input re-encoding"
      ],
      "whatMoved": "Supply stable volume identity for deletion fixtures and record roots/explicit folders as a person's deliberate add does (RootWitness.recordAdded or the existing in-memory admission path), including restart/crash drivers. Persist the service base path where the fixture needs its recorded ledger. Share the existing scoped known-volume seam in EngineTestHarness and restore it on cleanup. RootPresenceGuard receives the recorded ledger explicitly. Assertions, fault injection and guarded outcomes remain unchanged. These are adaptations to the volume rule: deleting fixture content needs matching recorded/current volume evidence, whereas present-file admission remains independent. Supplementary preservation checks: modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RootPresenceGuardTest.java#aPresentRootVouchesUntilItIsRenamedAway; modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/UncertainAbsenceGatesTest.java#theFreshnessCheckKeepsTheDocumentWhenTheRootWasRenamedAway.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "Admission of files that are present and readable (adding, ingesting, re-ingesting) keeps today's rule: a folder with no recorded volume and no current volume identity is admitted. Any decision that infers absence and removes or prunes indexed content requires proof: a recorded volume equal to the current identity, or a declared root's recorded volume; no record, no current identity, or a different volume means unproven, and the rows stay (retry later).",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```

## Acceptance records
