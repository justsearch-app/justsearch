---
schema: test-intent.v1
task: t-20261102-redesign
author-role: builder
author-session: 01a1b0d6-88cd-7253-a959-c700eed710f4
---

# Retry failed root-volume initialization in the original Engine

This is a separate changeset for the bounded builder assignment. No existing hostile-lock
scenario, selftest, protection fixture, throughput oracle or deadline is changed. Acceptance
is empty. No evidence JSON is authored: independent execution evidence and acceptance remain
with the orchestrator/verifier. The raw local command results described below are observations,
not an independent verdict or a claim of Windows execution.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RootVolumeInitializationRetryTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Real directories at the pending-ledger or ledger temporary write path prevent atomic persistence. Existing refusal boundaries must retry authorized declared-root initialization and configured-root enrollment after release, including a configured root outside watched roots. Repeated failures grant no identity. Partial publication, creation of unrelated explicit-file authority during failed initialization, same-owner retry, restart, sibling progress, removal overlapping evidence, configuration replacement, unreadable indexed evidence, empty and half-matching size/mtime samples, conflicting established authority, explicit-file-folder authority, unchanged completed authority timestamps and UUID-less limits remain covered. rootOnRecordedVolume remains read-only. These are service/ledger checks with injected volume lookup and mocked serving-index samples, not real-Engine boundary proof. Same-Engine root handover retains the original captured inputs, current membership/configuration, unfinished enrollment, evidence checks, explicit-file authority and pending cleanup; removal overlapping evidence and handover cannot resurrect the removed root.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "This handover covers the existing Windows CI repair, reconciliation and cleanup of merged E4c, and independent assessment of the already-built narrow P2b+c close.",
          "delegationLocation": "task t-20261102-redesign user turn 1",
          "delegationScope": "The reported Windows root-volume initialization write failure, recovery of its original accepted operation in the same Engine, and preservation of settled R1-R8 and RP1-RP4 refusal/authority cases only.",
          "choice": "Block the real filesystem write target with a directory; keep existing request interfaces for fail-before, use deterministic indexed stamps and controlled volume lookup for policy cases, and hold an evidence callback to overlap removal.",
          "label": "agent choice"
        }
      ],
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RootVolumeInitializationRetryTest.java"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/server/DefaultWorkerAppServicesProducerTransferTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Unfinished declared-root initialization and configured-only enrollment survive the existing selected deferred-writer, runtime-reload, native, serving and query producer handoffs after either pending-publication or ledger-creation failure. Repeated prepared/discarded candidates do not advance or lose the incumbent obligation. Original roots without native watches and unqueried siblings recover; unrelated later declarations do not gain boot-seeding authority. Current membership/configuration filter unfinished inputs; bound-empty and unbound initialization remain distinct. Completed identity-less initialization is not reenrolled or rewritten on same-Engine replacement. These are real constructor/ownership interfaces with mocked runtimes and injected volume identity, not running-Engine or hosted Windows proof.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "This handover covers the existing Windows CI repair, reconciliation and cleanup of merged E4c, and independent assessment of the already-built narrow P2b+c close.",
          "delegationLocation": "task t-20261102-redesign user turn 1",
          "delegationScope": "The reported Windows root-volume initialization write failure, recovery of its original accepted operation in the same Engine, and preservation of settled R1-R8 and RP1-RP4 refusal/authority cases only.",
          "choice": "Share a small Engine-lifetime initialization state through existing producer preparation and ownership facilities; only the selected physical watcher owner may advance it. Exercise the existing deferred writer, stopped-loop reload, native-to-Green, serving and query successor constructor/installation interfaces without before-tree compatibility helpers. Keep successful and UUID-less completion, exact original boot inputs, current membership/configuration and discarded-candidate isolation.",
          "label": "agent choice"
        }
      ],
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/server/DefaultWorkerAppServicesProducerTransferTest.java#unfinishedRootInitializationSurvivesSelectedProducerReplacement",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/server/DefaultWorkerAppServicesProducerTransferTest.java#uuidlessCompletionIsNotReenrolledByPreparedOrSelectedReplacement",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/server/DefaultWorkerAppServicesProducerTransferTest.java#replacementPreservesBoundEmptyVersusUnboundBootInputs",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/server/DefaultWorkerAppServicesProducerTransferTest.java#replacementUsesCurrentConfigurationAndMembershipForUnfinishedEnrollment"
      ]
    },
    {
      "items": [
        "modules/system-tests/src/integrationTest/java/io/justsearch/systemtests/supervision/RootVolumeInitializationRetryE2ETest.java"
      ],
      "class": "Incidental",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "This handover covers the existing Windows CI repair, reconciliation and cleanup of merged E4c, and independent assessment of the already-built narrow P2b+c close.",
          "delegationLocation": "task t-20261102-redesign user turn 1",
          "delegationScope": "The reported Windows root-volume initialization write failure, recovery of its original accepted operation in the same Engine, and preservation of settled R1-R8 and RP1-RP4 refusal/authority cases only.",
          "choice": "Add a dedicated two-document scenario and JUnit lock holder, retaining existing fixture ownership, cleanup and 180/330/420-second bounds. Observe at least one actually admitted original path PENDING/ROOT_UNAVAILABLE before unlocking: RecordedIngestionCoordinator drives roots serially, so the later child need not exist yet. Require both expected documents at completion. Counterexamples reject missing fault, missing job refusal, restart, failed operation, nonzero failures and partial search.",
          "label": "agent choice"
        }
      ],
      "assessment": {
        "purpose": "Windows-only installed/supervised Engine boundary check for boot pending-ledger IOException under a real FileChannel lock, followed by recovery of the same accepted operation without resubmission or restart. This is not a locally executed or repaired:true/New evidence claim.",
        "callersAndStateOwners": "RootVolumeInitializationRetryE2ETest invokes EngineSupervisedRecoveryE2ETest.runScenario. JUnit owns the private lock and release acknowledgment; the installed Engine owns jobs.db, root-volume files and the operation history. RecordedIngestionCoordinator drives the two submitted file roots serially, so only the deferred first child may have reached jobs.db before release.",
        "indirectBehaviours": "Require the actual boot write-failure log and absent ledger/pending publication while the lock is held; prove an admitted original path PENDING/ROOT_UNAVAILABLE before release; submit once; correlate the original operation via /api/operation-history/{operationKey} because OperationOutcomeView has no operationKey field; reject Engine identity changes; require complete/SUCCESS, unitsFailed=0, both expected paths searchable and healthy HTTP within the unchanged bound. Existing owner cleanup and port-closure proof remain in force.",
        "evidence": "Current completion checks pass: compileIntegrationTestJava, pmdIntegrationTest and spotlessJavaSourcesCheck; dedicated Node orchestration selftests 8/8 and classifier tests 57/57. These selftests cover serial first-child-only admission and refusal counterexamples with private SQLite fixtures; they can reject bad orchestration but cannot prove a Windows lock or real Engine recovery. No local execution of this Windows-only Java check is claimed; Linux skip is not execution evidence.",
        "unresolvedRisks": "Hosted Windows execution remains pending, including proof of the real boot IOException/OS lock, original-operation recovery and unchanged process identity. Windows fail-before on the unfixed product is also missing and pending. Independent verification and generated run-evidence/acceptance remain outstanding."
      }
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerProducerOwnershipTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The existing reaper scheduler/close-order test's fake DefaultWorkerAppServices now returns a mock ingest service, as a real owner does, so the new root-volume retry call can execute before the unchanged queue callback. No assertion, scheduler delay, interruption protocol, resource owner or close-order check changes."
    },
    {
      "items": [
        "modules/system-tests/src/integrationTest/java/io/justsearch/systemtests/supervision/EngineSupervisedRecoveryE2ETest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Only dedicated scenario registration/dispatch: recognize the new Windows-only root-volume scenario, acquire its exact private lock before starting the existing Node fixture, service its release marker in the existing 100ms owner loop, close the lock in the existing cleanup path, and dispatch its proof assertions. Existing scenario selection, fault inputs, expected outcomes, tags, assumptions, cleanup and bounds remain unchanged. Production root-volume repair is not claimed as evidence that this fixture ran."
    }
  ]
}
```

The worker-services test is durable regression coverage. The new Java check is an unverified
Windows boundary harness. The scripts and Node selftests
are outside the product test-intent gate scope; they retain ordinary check coverage and do not
require a product test-intent entry or claim Windows execution. Additions to real-writer-recovery.mjs
and EngineSupervisedRecoveryE2ETest are the minimum shared fixture dispatch scaffolding; the
KnowledgeServer change invokes retry under the existing reaper mutation admission, including
configured roots outside the Head's watched-root cycles. No new scheduler is introduced.

Local fail-before uses a shared clone of baseline `45cb5755de` under the supplied temporary
folder, with only the new worker-services test copied in. Each checkout uses its own build tree.
Use class-level selectors (no renamed/new-method compile dependency on the before tree):

```bash
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew \
  :modules:worker-services:test --tests '*RootVolumeInitializationRetryTest' \
  --tests '*RootVolumeUnknownIdentityTest' --tests '*RootPresenceGuardTest' -PskipWebBuild=true
```

Run that command in the before clone for fail-before and existing policy preservation, then
in the candidate. Individual checks within the new class include preservation; the whole new
class is expected to fail on baseline because retry and conflicting-authority assertions fail.
No known-good version preceding this new initialization-retry behavior was supplied.

```bash
node --test scripts/supervisor-conformance/root-volume-initialization-scenario.test.mjs
node --test scripts/ci/classify-changes.test.mjs
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew \
  :modules:system-tests:compileIntegrationTestJava \
  :modules:worker-services:pmdMain :modules:worker-services:pmdTest \
  :modules:indexer-worker:pmdMain :modules:system-tests:pmdIntegrationTest -PskipWebBuild=true
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew \
  :modules:app-launcher:test --tests '*UnreferencedCodeTest' -PskipWebBuild=true
```

Hosted Windows filter (not run by this builder):

```bash
./gradlew :modules:system-tests:integrationTest \
  --tests 'io.justsearch.systemtests.supervision.RootVolumeInitializationRetryE2ETest' -PskipWebBuild=true
```

Require `ROOT_VOLUME_INITIALIZATION_REFUSED` and `ROOT_VOLUME_INITIALIZATION_RETRY_PASS` in
fixture-output.txt. Their JSON must identify the exact `.tmp` path and boot write failure,
at least one refused PENDING/ROOT_UNAVAILABLE row for an original expected path (the later serial
child need not yet be queued), the original operation key/endpoint, complete/SUCCESS
with unitsFailed=0, matchedCount=2, and identical submitted/recovered runId, pid, instanceId and
incarnation. Require root-volume-lock-held and root-volume-lock-released plus the existing
portsClosed=true stop proof. A Linux skip supplies none of this evidence.

An unchanged neighboring selftest currently fails its UNKNOWN/FAILED outcome cases at
hostile-lock-scenario.test.mjs:296 because it expects one search probe but observes zero.
The same two failures reproduce on baseline 45cb5755de. The dedicated fixture builder owns those files; this candidate does not repair that check. The extra unchanged recovery-startup-budget.test.mjs run also timed out all four subprocess cases at their existing 60/90-second bounds (spawnSync ETIMEDOUT); no startup-budget pass is claimed.

The declared-initialization handoff is now carried by a private in-memory state object shared
through the existing DefaultWorkerAppServices producer construction. The existing physical
watcher close owner selects the only view allowed to advance it. The carrier holds only original
boot paths, unfinished initialization and configured enrollment/completion lists; it holds no
runtime/service reference and creates no persisted marker, format, scheduler or registry.
Current Head membership and configured roots still govern each selected retry. An unpublished
candidate can receive current configuration but cannot advance or consume the incumbent's work.
Configured identity-less completion remains completed within this Engine; a new Engine retains
the existing configured-root reenrollment exception.

The added producer lifecycle cases and service handoff cases are durable regression checks.
Their shared-clone before tree uses committed `a2dcdd8593` and exactly the changed test sources,
with no production/helper-signature patch. The existing fixture and lock harness remain
scaffolding; this completion changes no scripts and reruns neither classifier nor unrelated
hostile-lock/startup-budget selftests. The neighboring hostile-lock UNKNOWN/FAILED selftests
have since been fixed separately at `ba3bfa9ef8`; the earlier failures below are historical
observations and are not current handoff blockers.

Previously observed local results (unchanged worker-services checks, not re-run for this completion): RootVolumeInitializationRetryTest 15/15,
RootVolumeUnknownIdentityTest 9/9, RootPresenceGuardTest 5 passed/2 Windows cases skipped,
KnowledgeServerProducerOwnershipTest 2/2, NoVolumeIdentityRootTest 1/1,
UnreferencedCodeTest 3/3; new scenario selftests 6/6 and classifier audit 57/57.
On 45cb5755de with the new service test and unchanged-oracle producer fixture adaptation copied
in, 12/15 new service cases assertion-fail, all 9 identity and 5 executable guard preservation
cases pass, the same 2 Windows cases skip, producer ownership is 2/2 and UUID-less service is 1/1.
Those are previous-pass results; the fixture corrections below have separate scoped validation.
Scoped PMD and system-tests compileIntegrationTestJava passed in that previous pass. No Windows-only Engine integration
or full candidate gate ran. Checks touched the authorized shared ~/.gradle cache, local CPU and
private fixture resources only; no shared dev stack, GPU, running app, models or ~/.justsearch.

Completion within unverified Windows round 2 (no round-history reset): only the dedicated
scenario, its selftests, Java proof assertions and this metadata changed. Production behavior and
worker-services checks were unchanged, so their previous before/candidate evidence was not rerun.
No `.agents/preflight` exists in this checkout. Current scoped observations:

```bash
node --test scripts/supervisor-conformance/root-volume-initialization-scenario.test.mjs
# 8 passed, 0 failed, 0 skipped (includes first-child-only admission and both final documents).
node --test scripts/ci/classify-changes.test.mjs
# 57 passed, 0 failed; reader audit: 3104 readers, 130 repository inputs, 0 gaps.
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew :modules:system-tests:compileIntegrationTestJava :modules:system-tests:pmdIntegrationTest :modules:system-tests:spotlessJavaSourcesCheck -PskipWebBuild=true
# BUILD SUCCESSFUL; all three requested tasks executed, 10 seconds.
git diff --check
# Exit 0, no output.
```

Checks used private temporary SQLite resources, local CPU and the authorized shared Gradle cache
and build-slot/admission locks. No shared stack, running app or GPU was used. Hosted Windows
execution, Windows fail-before and independent verification remain pending; no evidence JSON or
acceptance was authored.

Same-Engine handoff completion (still the unfinished Windows round 2; no round-history reset):
only WorkerIngestService, DefaultWorkerAppServices, the two durable test classes above and this
changeset changed. Existing RootWatcherRegistry already supplies shared watch membership and removal handover;
DefaultWorkerAppServices supplies producer ownership. No additional registry was needed. The carrier does not keep a stale runtime
alive. The owner decision uses the existing selected watcher ownership, including for a borrowed
query view. Rebinding current roots/configuration does not recapture arbitrary later boot inputs.

Before evidence on committed `a2dcdd8593`, using a shared clone under the supplied TMPDIR and
exact changed test sources with no production compatibility edits:

```bash
cd "$TMPDIR/before"
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew \
  :modules:worker-services:test \
  --tests '*DefaultWorkerAppServicesProducerTransferTest.unfinishedRootInitializationSurvivesSelectedProducerReplacement' \
  --tests '*DefaultWorkerAppServicesProducerTransferTest.uuidlessCompletionIsNotReenrolledByPreparedOrSelectedReplacement' \
  --tests '*DefaultWorkerAppServicesProducerTransferTest.replacementPreservesBoundEmptyVersusUnboundBootInputs' \
  --tests '*DefaultWorkerAppServicesProducerTransferTest.replacementUsesCurrentConfigurationAndMembershipForUnfinishedEnrollment' \
  --tests '*RootVolumeInitializationRetryTest.rootHandoverRetainsFailedBootInputsAndExplicitAuthority' \
  --tests '*RootVolumeInitializationRetryTest.removalAcrossRootHandoverPreservesIndependentChildAndSibling' \
  --tests '*RootVolumeInitializationRetryTest.handoverRetriesAllCapturedSiblingsUsingCurrentEvidenceAndAuthority' \
  --tests '*RootVolumeInitializationRetryTest.recordedAuthorityBeforePendingCleanupFailureStaysCompletedAcrossHandover' \
  -PskipWebBuild=true
# Across these 20 new cases: 19 behavior assertions fail; unbound-input preservation passes.
# Constructor/transfer compilation succeeds without any new production-method dependency.
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew \
  :modules:worker-services:test \
  --tests '*RootVolumeInitializationRetryTest.removalOverlappingEvidenceRetryWinsWithoutReenrollment' \
  -PskipWebBuild=true
# Preservation passes after observing removal through the successor registry, its current owner.
```

The before run with the new cases also included the overlap selector before its observation was
corrected: that additional fixture assertion failed on the retired registry. The corrected
preservation check was copied unchanged into the before clone and passed there. The first
pending-cleanup fixture mistakenly tried to seed an already-completed root and did not produce
its intended IOException; the corrected fixture creates pending state, persists authority, and
fails cleanup on the real temporary write path. Its before failure then moved to the intended
uncleaned-pending assertion. Neither setup failure is counted as fail-before evidence. PMD also
identified two unused identity-scope names; those were corrected without suppressing the check.

Final candidate command, run in this worktree:

```bash
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew \
  :modules:worker-services:test \
  --tests '*RootVolumeInitializationRetryTest' \
  --tests '*DefaultWorkerAppServicesProducerTransferTest' \
  :modules:worker-services:pmdMain :modules:worker-services:pmdTest \
  :modules:worker-services:spotlessJavaSourcesCheck \
  :modules:app-launcher:test --tests '*UnreferencedCodeTest' -PskipWebBuild=true
# BUILD SUCCESSFUL, 39 seconds.
# RootVolumeInitializationRetryTest 20/20; ProducerTransferTest 56/56;
# UnreferencedCodeTest 3/3; requested PMD and Spotless tasks executed successfully.
```

Additional scoped preservation/lifecycle checks in this handoff completion used:

```bash
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew \
  :modules:worker-services:test --tests '*RootVolumeInitializationRetryTest' \
  --tests '*RootVolumeUnknownIdentityTest' --tests '*RootPresenceGuardTest' \
  --tests '*RootWatcherRegistryTest' --tests '*DefaultWorkerAppServicesProducerTransferTest' \
  :modules:indexer-worker:test --tests '*KnowledgeServerProducerOwnershipTest' \
  --tests '*UpgradeRootVolumeSeedingTest' --tests '*NoVolumeIdentityRootTest' \
  --tests '*RootVolumeCheckTest' --tests '*CommittedBootRootVolumeTest' -PskipWebBuild=true
```

That invocation failed solely on the first pending-cleanup fixture setup described above.
Executed preservation results were RootVolumeUnknownIdentityTest 9/9, RootPresenceGuardTest
5 passed/2 Windows skips, RootWatcherRegistryTest 9/9, KnowledgeServerProducerOwnershipTest 2/2,
NoVolumeIdentityRootTest 1/1, and automatically included IndexerWorkerGuardrailsTest 6/6.
UpgradeRootVolumeSeedingTest (3), RootVolumeCheckTest (1) and CommittedBootRootVolumeTest (1)
were platform skips, not passes. The final 20/20 service run covers the corrected cleanup setup
and the corrected handoff/removal overlap observation.

No `.agents/preflight` exists. No scripts changed, so classifier rerun was unnecessary.
No full candidate gate or real Windows lock execution was run. The dedicated Windows harness
remains Incidental with pending hosted lock/fail-before proof. The orchestrator still owns the
combined changed-module gate and independent verification. Checks used private checkout/build
and temporary fixture resources, local CPU, shared authorized ~/.gradle and the Gradle slot locks;
no shared dev stack, GPU, running owner app, main models or ~/.justsearch was touched.
Acceptance remains empty; no evidence JSON was authored.

## Acceptance records
