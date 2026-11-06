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
      "behaviour": "Real directories at the pending-ledger or ledger temporary write path prevent atomic persistence. Existing refusal boundaries must retry authorized declared-root initialization and configured-root enrollment after release, including a configured root outside watched roots. Repeated failures grant no identity. Partial publication, creation of unrelated explicit-file authority during failed initialization, same-owner retry, restart, sibling progress, removal overlapping evidence, configuration replacement, unreadable indexed evidence, empty and half-matching size/mtime samples, conflicting established authority, explicit-file-folder authority, unchanged completed authority timestamps and UUID-less limits remain covered. rootOnRecordedVolume remains read-only. These are service/ledger checks with injected volume lookup and mocked serving-index samples, not real-Engine boundary proof.",
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

Open ownership limitation found by inspection: unfinished declared initialization currently belongs
to the WorkerIngestService instance. KnowledgeServer.newAppServices can replace that instance;
wireAppServicesPostConstruction seeds configured roots but does not transfer the failed declared
initialization state. No producer-replacement recovery test has run and no handover change is
included. The orchestrator must settle that case before claiming recovery for every replacement
within one Engine lifetime. The previous pass returned needs-decision rather than expanding
the state handover design; this fixture completion does not broaden that scope.

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

## Acceptance records
