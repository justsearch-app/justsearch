---
schema: test-intent.v1
task: stale-source-deletion-fence
author-role: builder
author-session: stale-isolated-builder-3-20261026
---

# Stale-source deletion fence

The specific SD scenarios are agent-adopted delegated choices, not owner-agreed scenarios.
The orchestrator's amendment keeps ConfirmedDeletionMarker unchanged, including dangling-link grace.

```json
{
  "entries": [
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/ConditionalDeleteReplayRoutesTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/OriginBoundaryAdmissionTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/RefusedCleanupConditionalDeleteTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/UnreadableAndReplacedSourceQueueTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopCutoverPauseTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/JobBatchExtractorForcedPathTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/LegacyChunkBackfillTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/RootVolumeSwapGatesTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/UncertainAbsenceGatesTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "instrumentation"
      ],
      "whatMoved": "Pass an explicit real FileMutationFence at every direct extractor/resolver construction. Legacy mock queues in UncertainAbsenceGatesTest, RootVolumeSwapGatesTest and IndexingLoopCutoverPauseTest call the existing JobQueue default ownership method for their unitRevision-null legacy fixtures. No expected value, assertion, timeout, assumption, selection or existing protection is weakened.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "These are agent-adopted scenarios under the owner's delegation.",
          "delegationLocation": "task stale-source-deletion-fence user turn 2026-10-26 builder continuation brief, Contract",
          "delegationScope": "Implement the narrow stale-source deletion fence under SD1-SD12, including the amendment preserving ConfirmedDeletionMarker behavior for dangling links.",
          "choice": "Agent-adopted contract (delegated choice): current full source/root/volume/exclusion admission and the actual issued claim must authorize deletion within the loop file fence. Keep genuine deletion, uncertainty, existing marker/grace, A-before-B best effort, accepted replacement work and lifecycle behavior; no new atomic filesystem/queue or recovery guarantee.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopStaleDeletionFenceTest.java"
      ],
      "class": "New",
      "behaviour": "SD1-SD3, SD6 and SD10: stale absence/nonregular observations and superseded/returned/reconstructed claims cannot remove current A/B parent/chunks or start identity grace. Both actual post-extraction branches and initial admission wait behind the loop fence and reobserve H1/H2. A current exclusion skips; a root disappearing during the wait retains rows and retries. Four additional preservation methods check genuine absence with commit/reopen, unwired serving A, nonregular retirement without following links, and existing partial-failure/order semantics without a DONE-after-failure oracle.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "These are agent-adopted scenarios under the owner's delegation.",
          "delegationLocation": "task stale-source-deletion-fence user turn 2026-10-26 builder continuation brief, Contract",
          "delegationScope": "Implement the narrow stale-source deletion fence under SD1-SD12, including the amendment preserving ConfirmedDeletionMarker behavior for dangling links.",
          "choice": "Agent-adopted contract (delegated choice): current full source/root/volume/exclusion admission and the actual issued claim must authorize deletion within the loop file fence. Keep genuine deletion, uncertainty, existing marker/grace, A-before-B best effort, accepted replacement work and lifecycle behavior; no new atomic filesystem/queue or recovery guarantee.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/stale-source-deletion/repairs.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopStaleDeletionFenceTest.java#initialMissingSourceCannotDeleteAcrossTheActualFenceAndMustReobserveRecreatedH1",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopStaleDeletionFenceTest.java#postExtractionMissingSourceCannotDeleteAcrossTheActualFenceAndMustReobserveRecreatedH1",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopStaleDeletionFenceTest.java#finalSnapshotReobservesH1AndChangedH2BeforeDeleting",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopStaleDeletionFenceTest.java#postExtractionHashFailureReobservesChangedH2BeforeDeleting",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopStaleDeletionFenceTest.java#initialNonregularObservationCannotDeleteAReturnedRegularSource",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopStaleDeletionFenceTest.java#initialAndFinalSnapshotRejectSupersededReturnedAndReconstructedClaims",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopStaleDeletionFenceTest.java#currentExclusionWinsAfterWaitingAtInitialAndFinalSnapshotGates",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopStaleDeletionFenceTest.java#rootDisappearanceWhileWaitingKeepsRowsAndRetriesWithoutExpiry"
      ],
      "repaired": true,
      "note": "Original two tests and their original helpers remain byte-identical to c4aaf2fab5910dc2239ec46ad05616ff9e51afc0; neighboring checks are appended. The actual extractor exercises the source-hash-failure and final-snapshot branches. Final-snapshot instrumentation removes the owned source during a captured-walk read after both SHA passes; real SQLite owns the claim and real Lucene A/B effects are checked. Builder 3 compiled the final production/test sources and observed all 12 new-class tests pass with no skips. The exact-main shared clone ran the identical final test file: all eight named repair tests failed assertions for the intended fence/ownership/grace defect; four preservation tests passed. Machine-generated repairs.json is still absent: the full module run then encountered unrelated AWT/X11 initialization failures and a Linux UUID fixture NoSuchFileException. Per the builder handback rule, the run and queued compile/PMD/adjacent checks were stopped instead of changing unrelated environment or tests. No final full-suite or independent acceptance is claimed. Physical Windows/volume tests, crash/outcome-write convergence and recreation without a delivered event remain UNVERIFIED."
    }
  ]
}
```

## Observed final-source before/after checks (builder evidence)

All specific SD scenarios above are agent-adopted under delegated choice. They are not owner-agreed.

The exact-main baseline is `4f9df2c1f8b3c5c0377508c1b9ea8ae97d01d227`, checked out in a
shared clone inside the supplied TMPDIR. Only the final new test class was copied into it;
production/build sources were unchanged. The eight methods listed in `newChecks` all failed
assertions: old work deleted parent/chunks while the actual fence was held, deleted current rows,
or started identity grace. The four new preservation methods all passed on baseline and candidate.

Candidate command:

`CUDA_VISIBLE_DEVICES=-1 js-gradle-slot ./gradlew :modules:indexer-worker:test --tests '*IndexingLoopStaleDeletionFenceTest' -PskipWebBuild=true`

Result: exit 0, BUILD SUCCESSFUL in 1m21s after 693s slot wait. Final-source class: 12 tests,
12 pass, 0 failures/errors/skips. Both original tests and all original helpers were checked
byte-for-byte against c4aaf2fab5910dc2239ec46ad05616ff9e51afc0 and matched.

Baseline command:

`CUDA_VISIBLE_DEVICES=-1 js-gradle-slot ./gradlew :modules:worker-services:test --tests '*UncertainAbsenceGatesTest' --tests '*RootVolumeSwapGatesTest' --tests '*IndexingLoopTest*' --tests '*IndexingLoopCutoverPauseTest' --tests '*FileFreshness*Test' :modules:indexer-worker:test --tests '*IndexingLoopStaleDeletionFenceTest' --tests '*ConditionalDeleteReplayRoutesTest' --tests '*RefusedCleanupConditionalDeleteTest' --tests '*SqliteDocumentIdentityStoreTest' -PskipWebBuild=true`

Result: exit 1 for eight intended assertion failures. Worker-services XML: 109 cases, 102 pass,
0 failures/errors, 7 skips. Indexer-worker XML: 42 cases, 34 pass, 8 assertion failures,
0 errors/skips. This includes the four new preservation passes, six replay/refusal passes,
18 identity-store passes and six mandatory guardrail passes. Skipped Windows cases are unverified.

The requested full candidate module command entered a slot after 1170s, then emitted failures in
OcrReceiptForcingTest, PdfOcrEngineTest, PolicyDrivenTikaExtractorTest and StructuredContentDeOcrTest,
including AWTError and X11GraphicsEnvironment initialization errors. LinuxRootMountInfoTest also
reported NoSuchFileException at its `/dev/disk/by-uuid` enumeration. The run was interrupted
at handback after 28 observed failure events; no completed suite total is available. Previously
written XML was recognized as stale and is not claimed as fresh full-suite evidence.

The builder contract requires coming back for a failure that is not plainly an implementation
defect. No unrelated OCR/display/Linux-volume test or environment workaround was made. Queued
compile/PMD and extra baseline adaptation/watcher/prune checks were stopped before acquiring a
slot. The prepared machine-rule adaptation of the official evidence runner was not executed;
no repairs.json or acceptance record was fabricated. Existing production/test edits remain
uncommitted. Ignored `tmp/stale-delete-runtime-3/REPORT.md` contains the SD1–SD12 map, command
results and copied logs/XML. The orchestrator must resolve the full-suite environment failures,
finish the remaining checks/evidence and arrange independent acceptance.
