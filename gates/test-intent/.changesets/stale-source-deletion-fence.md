---
schema: test-intent.v1
task: stale-source-deletion-fence
author-role: builder
author-session: stale-isolated-builder-4-20261026
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
      "note": "Original two tests and their original helpers remain byte-identical to c4aaf2fab5910dc2239ec46ad05616ff9e51afc0. All 13 candidate Java source/test files match the previous builder hashes. No Java edit was needed in this continuation. Candidate 275008e80b passed both full module suites: XML totals 4011 tests, 3960 passes, 51 skips, zero failures/errors; the new class passed 12/12. compileTestJava and pmdAll passed with skipErrorProneTests=false and skipWebBuild=true. The machine-generated repairs.json records eight candidate passes and eight intended assertion failures on exact main 4f9df2c1f8. Its official runner adaptation changes execution plumbing only: mandated slot wrapper, cleared display variables and disabled CUDA, existing per-task profile isolation without an init script, retry rules and retained TMPDIR shared clones. Existing runnable worker and indexer preservation controls pass on both trees. Main full worker-services also reported one missing /dev/disk/by-uuid fixture failure and three watcher root-scope assertion failures; the identical candidate fixtures passed, so the baseline suite is not claimed green or runtime filesystem views identical. Four new preservation methods passed on main in the prior run with identical source hash and pass again in the current full candidate suite. Independent acceptance remains pending. Physical Windows/volume, crash/outcome-write convergence and recreation without a delivered event remain UNVERIFIED."
    }
  ]
}
```

## Observed checks (builder evidence, not independent acceptance)

The SD scenarios remain agent-adopted delegated choices, not owner-agreed scenarios.
The ConfirmedDeletionMarker amendment is unchanged, including dangling-link grace.

Candidate commit `275008e80beca6acc00830bd4f4e5fb14fe9b4b6` contains fix `fbac47ab8e` and
current main. No production or test edit was needed here. All 13 candidate Java files match the
previous builder's recorded hashes, including the entire new test class. The original two tests
and helpers also match `c4aaf2fab5910dc2239ec46ad05616ff9e51afc0` byte for byte.

With DISPLAY and WAYLAND_DISPLAY removed and CUDA_VISIBLE_DEVICES=-1:

- Both candidate module suites passed: worker-services XML 2193 tests (2175 pass, 18 skip),
  indexer-worker 1818 tests (1785 pass, 33 skip), zero failures/errors. New class: 12/12 pass.
- `compileTestJava pmdAll --continue -PskipErrorProneTests=false -PskipWebBuild=true` passed;
  248 actionable tasks (55 executed, 103 from cache, 90 up-to-date).
- Exact main worker-services completed with XML 2068 tests: 2040 pass, four failures, 24 skip.
  The failures were LinuxRootMountInfoTest's missing `/dev/disk/by-uuid` path and three
  WatcherUnavailabilityScopeTest root-scope assertions. Those candidate fixtures passed.
  No equivalence of Gradle daemon filesystem views is claimed, and main's full suite is not
  called green. The relevant worker controls pass 129 runnable cases on both trees; seven skip.
- Additional exact-main indexer controls passed: 55 cases, 52 pass, three skip (including six
  guardrails). The corresponding 46 runnable preservation cases pass in the full candidate run.
- The adapted official evidence runner exited 0. It ran all eight declared repairs in a clean
  committed candidate shared clone (eight pass, no skips), then overlaid only the identical new
  test class into a fresh shared clone of exact main (eight intended assertion failures, no
  errors/skips). `repairs.json` was written by that runner and copied without editing.
- The four new preservation methods also passed on exact main in the earlier run. Its XML and
  test-source hash are retained; the source SHA256 is unchanged in this candidate.

The runner adaptation is documented in
`gates/test-intent/evidence/stale-source-deletion/runner-machine-rules.patch` and identified in the
machine evidence producer record. JUnit parsing, result selection, assertion classification,
source digests and evidence self-digest retain the official implementation. Both revisions have
per-task profile isolation in JvmBaseConventionsPlugin, so no init script or alternate cache was
used. Each checkout has its own build folder and owned test resources. The evidence clones let
that work queue independently of PMD while retaining the mandated global slot limit.

`tmp/stale-delete-runtime-4/REPORT.md` records every SD row, commands/counts and limitations;
its logs folder retains raw completed reports and XML. There was no exit-75 or read-only cache
hazard, no commit/push/publication, and no independent acceptance authored by this builder.
Physical Windows/volume, outcome-write/commit/crash convergence, external recreation without a
delivered event and full Forget privacy acceptance remain unverified/held under the contract.
