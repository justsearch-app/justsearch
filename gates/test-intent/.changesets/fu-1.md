---
schema: test-intent.v1
task: fu-1
author-role: builder
author-session: 01a1aa00-ad69-78c2-ab99-03e4e933309c
---

# Test-intent entries

```json
{
  "entries": [
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineIncompleteCoverageTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SyncCoverageEvidenceTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "D1-D5: complete current whole-root enumeration clears persisted incomplete coverage through periodic sync, Verify folder, and recorded Reindex with either force setting; a single repaired file does not. R1: empty-message failures and entry interruption remain explicit failures. R2/R6: legitimate exclusions and ownership boundaries certify the full eligible scope; unchanged nonempty roots retain indexed completion and empty roots complete empty. R3: repeated identical incomplete observations invalidate older clean results, including boot. No new saved fields.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "a recorded walk carries, in memory, a digest of the complete effective exclusion policy (frozen globs plus the literal exclusions) taken when that walk starts enumerating; its clean completion clears INCOMPLETE_COVERAGE only if that digest equals the digest of the current complete policy at completion.",
          "location": "task fu-1 request"
        },
        {
          "kind": "owner-words",
          "quote": "a watched folder's INCOMPLETE_COVERAGE warning clears when a complete whole-root check succeeds (periodic sync, Verify folder, recorded Reindex), without an Engine restart, and never on partial, skipped, unverified, failed or stale evidence.",
          "location": "task fu-1 request"
        },
        {
          "kind": "owner-words",
          "quote": "Nonempty repaired roots do not become falsely empty; empty/all-excluded roots become completed-empty. Unchanged indexed files need no new content write to permit clearing.",
          "location": "task fu-1 request"
        },
        {
          "kind": "owner-words",
          "quote": "Legacy and current watched-roots files retain membership, labels, completion semantics and removal markers through clearing and reload.",
          "location": "task fu-1 request"
        },
        {
          "kind": "owner-words",
          "quote": "a narrower request scope (single file, subtree, extra request exclusions, policy changed since preparation) is not whole-root evidence.",
          "location": "task fu-1 request"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-1",
          "scenario": "R2",
          "quote": "Single-file, subtree and additionally restricted recorded walks cannot clear the root warning. Legitimate exclusions and ownership boundaries permit complete eligible coverage.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/fu-1.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineIncompleteCoverageTest.java#D1PeriodicCoverageClearsPersistedWarning",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineIncompleteCoverageTest.java#D2VerifyFolderClearsPersistedWarning",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineIncompleteCoverageTest.java#D3RecordedReindexClearsPersistedWarning",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineIncompleteCoverageTest.java#D4OneRepairedFileCannotClearButWholeRootCan",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineIncompleteCoverageTest.java#D5LegacyReindexRejectsUnverifiedResponseIndependentlyOfSkipped",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineIncompleteCoverageTest.java#R3RepeatedIncompleteObservationInvalidatesOlderCleanCompletion",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineIncompleteCoverageTest.java#R6EmptyRepairedRootCompletesWithoutTimestamp",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineIncompleteCoverageTest.java#R2LegitimatePolicyAndOwnershipExclusionsPermitWholeEligibleCoverage",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SyncCoverageEvidenceTest.java#R1EmptyMessageExceptionIsAnExplicitSyncFailure",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SyncCoverageEvidenceTest.java#R1CancelledAndInterruptedWalksNeverCompleteCoverage",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SyncCoverageEvidenceTest.java#R6UnchangedEligibleFilesRemainCoverageEvidenceWithoutAdmission"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineIncompleteCoverageTest.java"
      ],
      "class": "New",
      "behaviour": "R1/R2: skipped, deferred, unverified, failed, interrupted, missing-volume and narrower-scope results cannot certify root coverage. A digest of frozen globs and live literal exclusions is captured at enumeration start; a policy change before completion prevents clearing, Stable literal exclusions still permit clearing. R4: a removed/re-added registration rejects a result awaiting application, and its new boot walk can complete. R5/R7: persisted warning, labels and completion reload unchanged until fresh boot coverage succeeds. R8: other walk errors and unrelated roots remain independent.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "a recorded walk carries, in memory, a digest of the complete effective exclusion policy (frozen globs plus the literal exclusions) taken when that walk starts enumerating; its clean completion clears INCOMPLETE_COVERAGE only if that digest equals the digest of the current complete policy at completion.",
          "location": "task fu-1 request"
        },
        {
          "kind": "owner-words",
          "quote": "a watched folder's INCOMPLETE_COVERAGE warning clears when a complete whole-root check succeeds (periodic sync, Verify folder, recorded Reindex), without an Engine restart, and never on partial, skipped, unverified, failed or stale evidence.",
          "location": "task fu-1 request"
        },
        {
          "kind": "owner-words",
          "quote": "Nonempty repaired roots do not become falsely empty; empty/all-excluded roots become completed-empty. Unchanged indexed files need no new content write to permit clearing.",
          "location": "task fu-1 request"
        },
        {
          "kind": "owner-words",
          "quote": "Legacy and current watched-roots files retain membership, labels, completion semantics and removal markers through clearing and reload.",
          "location": "task fu-1 request"
        },
        {
          "kind": "owner-words",
          "quote": "a narrower request scope (single file, subtree, extra request exclusions, policy changed since preparation) is not whole-root evidence.",
          "location": "task fu-1 request"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-1",
          "scenario": "R2",
          "quote": "Single-file, subtree and additionally restricted recorded walks cannot clear the root warning. Legitimate exclusions and ownership boundaries permit complete eligible coverage.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/fu-1-preservation.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineIncompleteCoverageTest.java#R1NonqualifyingSyncCannotClear",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineIncompleteCoverageTest.java#R2RestrictedRecordedScopeCannotClear",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineIncompleteCoverageTest.java#R2LiteralPolicyChangedDuringEnumerationCannotClear",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineIncompleteCoverageTest.java#R4RemovedRegistrationRejectsAResultAwaitingApplication",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineIncompleteCoverageTest.java#R5ReloadWhileUnreadableRetainsWarningAndBootAfterRepairClears",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineIncompleteCoverageTest.java#R8SuccessfulSyncLeavesOtherWalkErrorsAndUnrelatedRootsAlone"
      ]
    }
  ]
}
```

## Verification commands

The orchestrator explicitly requested no evidence JSON and no commit from this builder.
The evidence references above reserve the verifier's files; they are not builder pass claims.
The test-intent gate therefore still needs execution evidence and independent acceptance.
No `.agents/preflight` exists in this checkout.

Run Gradle commands in the background, one at a time. Each tree uses its own normal build
folders and test profiles. The shared base clone is at `$TMPDIR/before`, detached at
`b21749f0e07917daceb0df14e57bd2e9163cb91b`; only the two new test sources are copied into it.
The candidate focused command (base repair assertions are expected to fail):

```bash
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew \
  :modules:app-engine:test --tests '*EngineIncompleteCoverageTest' \
  :modules:worker-services:test --tests '*SyncCoverageEvidenceTest' \
  --tests '*WorkerScanOpsTest.unreadableEligibleFileMakesOrdinaryScanIncomplete' \
  --tests '*SyncDirectoryOpsChangedStampTest.completeIndexedFamilyStaysSuppressedUnlessForced' \
  :modules:app-services:test --tests '*WatchedRootsStoreTest' --tests '*WatchedRootsStateTest' \
  --tests '*RootRemovalIncompleteTest' --tests '*PeriodicRootVolumeCheckTest' \
  --tests '*SyncOpsReconcileVerificationTest' -PskipWebBuild=true --continue
```

Targeted comparison of the earlier AI install failure (once per tree):

```bash
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew \
  :modules:app-services:test --tests '*AiInstallAcquisitionFreezeSeamTest' -PskipWebBuild=true
```

The candidate AI comparison was combined with the literal-policy counterexample before the
new digest guard. That counterexample failed by assertion as expected; the AI test passed.

```bash
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew \
  :modules:app-services:test :modules:app-engine:test :modules:worker-services:test \
  :modules:configuration:test :modules:ipc-common:test -PskipWebBuild=true --continue
node scripts/governance/run.mjs --gate test-intent --mode gate
env -u DISPLAY -u WAYLAND_DISPLAY node scripts/dev/java-prepush.mjs --unreferenced
```

The pre-push script invokes `js-gradle-slot ./gradlew` itself; do not wrap the script in
another slot. No shared dev stack is started. The permission fixtures, SQLite queues,
watched-roots stores and test profiles belong to these checkouts only. The machine's Gradle
slot/admission locks and Gradle dependency/build caches are shared resources touched.

Repair checks include positive completeness cases that depend on the same D1-D3 repair.
R3 boot and entry interruption also expose base failures; they are repair evidence rather
than established preservation passes. The literal-policy regression mutates the live literal
policy during enumeration, as the settled decision captures policy at enumeration start;
a pre-enumeration literal change is already part of that walk's effective eligible scope.
The legitimate-policy check separately covers stable literal exclusions, globs and ownership boundaries.

## Observed results

- Targeted AI acquisition check: passed on both base and candidate, one test each. The
  prior suite failure at line 126 was not reproduced.
- Candidate focused checks: passed; engine 33/33, worker 7/7, app-services 52/52, none skipped.
- The literal-policy counterexample failed by assertion on the inherited candidate before
  the digest guard and passed after it.
- `test-intent` gate: failed because both referenced evidence files and independent
  acceptance are absent. No evidence JSON was written, as requested.
- Base final comparison: engine 33 tests, 15 expected assertion failures (D1-D5,
  positive R2/R6 coverage cases and stale boot completion); the literal-policy regression
  passed. Worker preservation checks passed 4/4. The earlier base run also exposed all
  three worker repair assertions, while app-services preservation checks passed 52/52.
- Base final focused command used the engine class and the two worker preservation
  selectors above; the initial base run used the engine class, `SyncCoverageEvidenceTest`
  and the app-services selectors. The literal-policy regression itself is a preservation
  check; stable-policy clearing is checked separately as repaired behavior.
- Module suites: exit 1 after 16m20s, exactly the six listed environment failures below.
  App-services XML: 5,106 tests, zero failures, 11 skipped; the AI acquisition test passed
  in the suite too. App-engine: 923 tests, two failures, nine skipped; all 33 FU-1 tests
  passed with none skipped. Worker-services console: 4,204 tests, four failures, 23 skipped
  (XML also includes one disabled container). IPC-common: four passed. Configuration's
  370-test successful result was reused (`UP-TO-DATE`), not freshly executed.
  - `LinuxRootMountInfoTest.missingDanglingAndAmbiguousUuidMappingsAreUnknown`:
    `NoSuchFileException: /dev/disk/by-uuid`.
  - `WatcherUnavailabilityScopeTest.aNewerUnavailableIncarnationCannotBeClearedByAnOlderReconciliation`.
  - `WatcherUnavailabilityScopeTest.endedUnavailableIncarnationIsRecoverableOnlyByItsOwnReconciliation`.
  - `WatcherUnavailabilityScopeTest.inaccessibleRootWhoseAbsenceIsUnknownIsStillRootScoped`.
  - `EngineForgetInProcessTest.R9HotPublicSaveAfterNativePolicySkipReadmitsCurrentBytesAndKeepsCompletedForgetReceipt`:
    setup/inconclusive native migration observation lacked target projection refusal.
  - `EngineForgetInProcessTest.S55CompletedForgetAllowsReturnedRootReplayWithoutPublishingExcludedSource`:
    setup requires real volume evidence.
- `env -u DISPLAY -u WAYLAND_DISPLAY node scripts/dev/java-prepush.mjs --unreferenced`:
  exit 0, `BUILD SUCCESSFUL in 6m36s`; compile/test compilation, PMD, the selected
  app-services/UI integration tests and `UnreferencedCodeTest` passed. Its existing
  real-drive exclusion (`LinuxWatchedDriveReturnIntegrationTest`) remained in force.
- `git diff --check`: passed. All edits remain uncommitted. Shared dev stack untouched;
  independent verifier evidence/acceptance and the owned end-to-end fixture remain outside
  this builder's completed checks.

## Acceptance records
