---
schema: test-intent.v1
task: t-20261030-ingfix
author-role: builder
author-session: 01a19138-440e-7410-b519-33d274de04ae
---

# Test-intent entries

The gate flags 48 existing files between 9eb36cc7923b530624f7ced8ea0bc678f0011b31 and this branch. The eight isolated new test sources remain exempt. Apply Excludes root-relative matching for completed ad-hoc imports (C14 partly) is open and is not claimed here.

Named newChecks are assertion fail-before checks only; supplementary preservation checks are recorded in prose. No test-support overlays are needed: both NoVolumeIdentityExtension helpers already exist unchanged at the before-state. Before-state compilation uses gates/test-intent/compatibility/ingfix-wp9-before.patch, which adds only a no-op collection cancellation signature and a journal overload forwarding to the old method. The D40 publication-completion check and one Mockito verification use assertDoesNotThrow so the evidence classifier recognizes their assertions. No acceptance record is authored here.


The behavioural replacements were selected by method name and run on both states. The before-state was a shared clone at 9eb36cc7923b530624f7ced8ea0bc678f0011b31 with only the compatibility patch and candidate check files applied. D42 measured 51,056,016 -> 197,689,680 allocated bytes (3.87x) before and 3,405,904 -> 6,817,088 bytes (2.00x) on the candidate; the 2.6x bound has margin on both sides. C36 failed before with “returned count must equal rows removed after unrefreshed publication for serving: expected 3 but was 0” and passed on the candidate across all three routes. The published metadata equality comparisons passed on both states as preservation checks. The D40 completion assertion also fails on the compatibility-patched before-clone with `DocValuesField "chunk_heading_text" is too large, must be <= 32766` and passes on the candidate. C11 retains its recorded assertion fail-before (`expected: <2> but was: <0>`) and candidate pass; its own Defect pin entry accounts for the added oracle.

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
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/rag/ChunkDocumentWriterLineFieldsTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/rag/ChunkPublicationMetadataTest.java"
      ],
      "class": "Defect pin",
      "behaviour": "Oversized ASCII, multibyte and supplementary-character headings must complete initial chunk publication, VDU regeneration and replacement replay, retain readable bounded metadata and survive reopen (S-D40, C27/C28). Doubling similarly chunked content requires near-linear publication allocation under the shared mutation fence. Preservation check ChunkDocumentWriterLineFieldsTest#D42PublishedMetadataKeepsPrefixLawsIncludingInsideHeadings compares published line/heading metadata with prefix laws across overlapping chunks, offsets inside headings, CRLF, PDF/Office markers and fixed-seed generated whitespace and heading boundaries; it passes on both states and is not a repair pin.",
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
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D40 (An oversized heading poisons chunk publication after writes begin) Ingest a 40,000-character ASCII heading and require bounded metadata or complete publication.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C27. D40 — heading bounds must cover encoded bytes and every publication path: keyword DocValues receive the unbounded heading; test oversized ASCII, multibyte and supplementary-character headings through initial ingest, VDU update and replay, then verify complete publication and readable metadata.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C28. D40 — a later poisonous heading must not trap replacement in repeatable partial publication: place it after several valid children and replace an existing family; repeat processing and reopen, requiring complete recovery rather than repeated identical failure.",
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
        "reproduction": "Publish a document with several valid children before a 40,000-character ASCII, multibyte or supplementary-character heading; require publication without an exception, complete child counts, bounded UTF-8 metadata, repeated replacement and VDU regeneration, then reopen. Publish similarly chunked Markdown documents of N and 2N characters under FileMutationFence; after warmup, compare the minimum thread-allocated bytes from three paired samples and require doubled content to allocate at most 2.6 times as much. Metadata equality against the prefix laws is a preservation check."
      },
      "reproduction": "Publish a document with several valid children before a 40,000-character ASCII, multibyte or supplementary-character heading; require publication without an exception, complete child counts, bounded UTF-8 metadata, repeated replacement and VDU regeneration, then reopen. Publish similarly chunked Markdown documents of N and 2N characters under FileMutationFence; after warmup, compare the minimum thread-allocated bytes from three paired samples and require doubled content to allocate at most 2.6 times as much. Metadata equality against the prefix laws is a preservation check.",
      "evidence": "gates/test-intent/evidence/ingfix-wp9.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/rag/ChunkPublicationMetadataTest.java#D42DoublingSimilarlyChunkedContentHasNearLinearPublicationAllocation",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/rag/ChunkPublicationMetadataTest.java#D40OversizedUtf8HeadingsCompleteReplacementAndReplayAfterReopen"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceCandidateReconciliationTest.java"
      ],
      "class": "Defect pin",
      "behaviour": "Candidate reconciliation cannot delete without recorded/current volume proof; collection removal cancels its queued work and reports the actual number of removed parent/chunk rows, including just-published unrefreshed rows and repeated deletion through serving, same-runtime pre-Green and distinct-candidate routes. Existing journal ordering and exactly-one mutation obligations remain.",
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
        "reproduction": "Reconcile a missing candidate source under unknown volume and require refusal without deletes. Through real runtimes, publish and commit one parent plus two chunks without refreshing the runtime searcher, independently count the committed rows, delete the collection and assert the returned count equals rows actually removed; repeat and require zero. Exercise serving, same-runtime pre-Green and distinct-candidate routing. Preserve journal-before-effect ordering, collection queue cancellation and exactly one effect per request without asserting refresh call counts.",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "Admission of files that are present and readable (adding, ingesting, re-ingesting) keeps today's rule: a folder with no recorded volume and no current volume identity is admitted. Any decision that infers absence and removes or prunes indexed content requires proof: a recorded volume equal to the current identity, or a declared root's recorded volume; no record, no current identity, or a different volume means unproven, and the rows stay (retry later).\nC4. D37 — removing a collection must invalidate pending, claimed and already-extracted work: JobBatchWriter trusts claim ownership, which collection removal leaves intact; pause each stage, remove the collection, resume and require no recreated parents or chunks.\nC36. D129 — counts must describe the rows actually removed: collection counting acquires an older searcher; delete immediately after unrefreshed publication, include parents and chunks, repeat deletion, and exercise serving/candidate routing.",
          "label": "agent choice"
        }
      },
      "reproduction": "Reconcile a missing candidate source under unknown volume and require refusal without deletes. Through real runtimes, publish and commit one parent plus two chunks without refreshing the runtime searcher, independently count the committed rows, delete the collection and assert the returned count equals rows actually removed; repeat and require zero. Exercise serving, same-runtime pre-Green and distinct-candidate routing. Preserve journal-before-effect ordering, collection queue cancellation and exactly one effect per request without asserting refresh call counts.",
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
      "whatMoved": "Move the outcome-write latch and verification from immediate markClaimDone to markDoneTransitions, the journal drain used by the commit owner. The transition verification requires the same supplied claim object; the in-flight pause/wait/resume oracle and latch remain. Preservation check: modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopCutoverPauseTest.java#pauseWaitsForAnAlreadyClaimedBatch. Supplementary preservation check: modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopCutoverPauseTest.java#successfulPauseStopsPollingAndResumeReusesTheSameThread.",
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
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/ReturnedRootCurrentByteReplayTest.java"
      ],
      "class": "Defect pin",
      "behaviour": "C11: a returning or republished source defeats obsolete deletion; returned-root reconciliation rechecks absence under the publication fence and preserves the current parent and child.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C11. D15/D33 — a returning or republished source must defeat an obsolete deletion: pause maintenance before application, recreate or publish the source, and verify the current family survives.",
          "label": "agent choice"
        }
      ],
      "defectSource": {
        "kind": "defect-source",
        "safeguard": "no data loss",
        "reproduction": "Pause returned-root reconciliation after observing absence and before applying deletion; republish the current parent and child under the real file-mutation fence, resume, and require both current rows to survive commit and reopen."
      },
      "reproduction": "Pause returned-root reconciliation after observing absence and before applying deletion; republish the current parent and child under the real file-mutation fence, resume, and require both current rows to survive commit and reopen.",
      "evidence": "gates/test-intent/evidence/ingfix-wp9.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/ReturnedRootCurrentByteReplayTest.java#C11ReturnedRootRechecksAbsenceUnderThePublicationFence"
      ]
    }
  ]
}
```

## Acceptance records
