# D1 installed standard-model round (2026-10-01)

Root-run installed scenarios on the integrated lane head after the reconfigure, help-source,
WP2 2b, D1 closure and D1-17 work. Engine launched by the supervised dev-runner from the
worktree distribution; GPU standard models from the main checkout. Raw logs, XML and private
fixtures stay in the `lane-f-pr1-verify` worktree under `tmp/` through lane acceptance plus 30 days.

| Scenario | Revision | Result | Evidence |
|---|---|---|---|
| `EngineLifecycleE2ETest.migrationStartsLiveInTheInstalledEngine` | `ec653a777` | PASSED | `tmp/lane-f-installed-g1.log`, `tmp/lane-f-installed-g1-xml/` |
| `...heldNativeLeaseForcesControlledHardStopAndCountedSupervisorRestart` (D1-13) | `ec653a777` | PASSED | same |
| `...issuedASearchCompletesAfterBesideBServes` | `ec653a777` | PASSED | same |
| `...ordinaryQueryReconfigureProvesBesideAndForcedInPlaceWithoutRestart` (D1-4/12/14/16) | `30f5ca540` | PASSED on run 3 | `tmp/lane-f-installed-reconf-r3.log`, `-r3-xml/`; fixtures `tmp/lane-f-takeover/query-reconfigure-6bb4264d-*` (BESIDE: 344 samples, 0 API outages, restartCount 0) and `query-reconfigure-ea328dea-*` (IN_PLACE with 1 MiB free, reason `candidate_fits_after_source_release`, 501 samples, 0 outages, restartCount 0, applied A -> B -> restored A with exact model hashes) |
| same, run 1 | `ec653a777` | FAILED: harness read model A from its own env (`query-reconfigure.mjs:30`) | fixed in `eee08b92e` |
| same, run 2 | `eee08b92e` | FAILED: harness never ingested its corpus (empty searches) | fixed in the reconfigure harness commit merged at `db24e6d59` |
| `...stuckIndexRecoversLocallyWithoutRestart`, `...stuckIndexExhaustionEscalatesOnceWithoutModels` | `eee08b92e` | PASSED | `tmp/lane-f-installed-g2.log`, `-g2-xml/` |
| `...watcherAddDeleteReplayDuringInPlaceBuildKeepsAAndPromotesB` | `eee08b92e` | PASSED (IN_PLACE, 0 restarts) | same |
| `...refusedInPlaceGapRestoresAThenApprovesAndPromotesB` | `eee08b92e` | PASSED | same |
| `...lowMemoryInPlaceChangingInputRecoversBeforePointer`, `...AfterPointerBeforeSettings` | `30f5ca540` | PASSED | `tmp/lane-f-installed-g3.log`, `-g3-xml/` |
| `EngineSupervisedRecoveryE2ETest.supervisedIndexRecoveryUsesTheRetainedStandardEmbedding` [lock-index-release, lock-index-exhaustion] (D1-7) | `30f5ca540` | PASSED (96 s, 129 s) | `tmp/lane-f-installed-recovery.log`, `-recovery-xml/` |
| `...supervisedRecoveryUsesTheCorrectExitAndReopensDurableState` [writer, migration, processing] | `30f5ca540` | PASSED | same |
| same [lock-ingest] | `30f5ca540` | FAILED once (`INGEST_UNIT_STATE_UNAVAILABLE`), then PASSED on two later executed runs; passed on hosted CI at `2babb36ce` | fixture `tmp/lane-f-takeover/writer-junit-cfe8f1f8-*`; reruns `tmp/lane-f-lock-ingest-rerun.log`, `tmp/lane-f-lock-ingest-ai-r1.log`; diagnosis in progress |

Not yet re-run in this round (proved earlier at older revisions, see the reconciliation):
`nativeCompleteSourceSurvivesMixedReceiptsAndSupervisedPointerCrash`,
`recordedLiveStart*`, `capturedEditReplays*`, `semanticAvailability*`,
`acceptedWriteDuringInPlaceBuildKeepsTheSameEngine`, `refusedAcceptedInPlaceCancellationCrash*`,
`failedInPlaceARecomposeCancelsBAndRestoresA`, `generativeSecondOwnerFailure*`,
`committedPointerBootReconcilesPersistedRootChangesBeforePublication`.
