---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b38-batch1-repair-20261009-builder
---

# Batch-1 repair test intent

This repair implements the supplied builder brief. Fresh detached evidence uses actual parents, no saved-state revert. Existing unsafe cleanup is excluded, not a pass. The f648 replay signature patch delegates only shutdown cleanup to that parent's existing API; it changes no indexing or replay decision. Step11's API is absent on its real parent 64e04b891; synthetic null-stub failures are not real-parent assertion proof. Independent acceptance and live verification are pending; this builder supplies no acceptance record.

Records phase B (batch 3) re-ran entries 1-3 on b599d4d7e, with the fail-before again at 1e333857b. Later commits made the checks call production interfaces 1e333857b lacks, so each fail-before applies a compat before-patch. No production code at 1e333857b reads what these patches add, and each check again fails by assertion there.
- Entry 1 (`b6d536-batch1-replay-1e333857b.json`): ChunkCompletenessAdmissionTest gained five cases from 24a3fc682 and fe62ed231 (7 cases now). `gates/test-intent/evidence/b6d536-batch1-replay-compat.patch` adds the type ChunkSetCompleteness with its Declaration record, and IndexingCoordinator.backfillChunkDeclaration returning false at once.
- Entry 2 (`b6d536-batch1-health-repair.json`): ee3c86b19 builds HealthEventChangeRegistry with its delivery executor. `gates/test-intent/evidence/b50-status-timeout-compat.patch` adds only HealthEventChangeRegistry(ExecutorService), which ignores its argument and calls the existing no-argument constructor.
- Entry 3 (`b6d536-batch1-cpu-repair.json`): cbe6650b5 calls buildReadinessEnvelope with a fourth (cached) argument. `gates/test-intent/evidence/b6d536-batch1-cpu-repair-compat.patch` adds only that four-argument overload, which ignores the flag and calls the existing three-argument method.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecoverableChunkCompletenessTest.java"
      ],
      "class": "Adaptation",
      "moved": ["instrumentation"],
      "whatMoved": "Loaded-machine observation only: wait for the same durable COMPLETE condition for up to 300 seconds instead of 60, allow the owned probe 360 seconds instead of 100, and give the three-probe test 20 minutes instead of 180 seconds. The deliberate interruption checkpoint gets the same 300-second allowance instead of 90 seconds. Print completion-wait elapsed time for diagnosis. The first unchanged verifier combined run on 0d90a3388 failed with a RUNNING replay and exit 99 while chunk writes were still advancing. The input, mutation-1000 halt, real JVM restart, operation authorization, COMPLETE states, exact attempt counts, zero failures, entire declared chunk set, unique IDs and ordinals, control/replay equality and one DONE job assertions are all retained. This test establishes durable completeness, not a 60-second throughput contract; no product behavior changes. CI portability repair: spawn the current JVM command, which resolves to java on Linux and java.exe on Windows, instead of assuming java.exe under java.home. The real process halt/replay boundary, complete chunk set, uniqueness, exact attempts, control equality and time bounds remain.",
      "sources": [{
        "kind": "owner-words",
        "quote": "Make the test robust without weakening its assertion. Waiting on the condition with a generous bound is fine; loosening what is asserted is not.",
        "location": "task t-20261005-b6d536 agreement"
      }]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecoverableChunkCompletenessTest.java",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/ChunkCompletenessAdmissionTest.java"
      ],
      "class": "New",
      "behaviour": "An actual parser and Engine recorded-unit path index a large file while its root is watched. An uninterrupted control persists 5,557 chunks. A real child JVM commits at mutation 1,000 and exits 1 during the recorded attempt; durable RUNNING parent and child replay once after restart to COMPLETE only with all declared chunks, unique IDs and ordinals, one DONE job and no failed units. Real Lucene timestamp admission rejects missing rows, duplicate ordinals, wrong content revisions and wrong totals. The new checks fail by assertion on 1e333857b and 9e8c7fae2; separate f648453b5 evidence identifies the earlier behavior and any compatibility scaffolding. CI portability repair: spawn the current JVM command, which resolves to java on Linux and java.exe on Windows, instead of assuming java.exe under java.home. The real process halt/replay boundary, complete chunk set, uniqueness, exact attempts, control equality and time bounds remain.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "A unit must never be COMPLETE while its declared chunk total is not persisted.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request: batch-1 repair builder brief, 2026-10-09'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/b6d536-batch1-replay-1e333857b.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecoverableChunkCompletenessTest.java",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/ChunkCompletenessAdmissionTest.java"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/phases/CapabilityHealthBridgeReplayTest.java"
      ],
      "class": "New",
      "behaviour": "The actual CapabilityHealthBridge subscribes to the real component registry and writes ConditionStore: after model_load_failed, READY with a null reason clears inference.capability. The baseline retains the condition and fails by assertion.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Recovery must clear the condition.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request: batch-1 repair builder brief, 2026-10-09'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/b6d536-batch1-health-repair.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/phases/CapabilityHealthBridgeReplayTest.java#recoveredChatClearsItsModelLoadFailureThroughTheBridge"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusLifecycleQueryOnCpuTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceCpuRecourseVisibilityTest.java",
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleQueryCpuRecourseTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerHealthServiceQueryCpuRecourseTest.java"
      ],
      "class": "New",
      "behaviour": "Lease selection preserves CPU recourse trace detail but cannot publish CPU-served status. Two real NativeSessionHandle deadline misses project through WorkerHealthService into the real status assembler without query_on_cpu; one successful CPU leg then earns query_on_cpu. The worker search miss retains its fallback reason and leaves the process success marker unchanged. A native lease acquired without running leaves the marker unchanged. Baseline checks fail by assertion; successful CPU worker and status cases are preservation checks in the full suites. Changed intent of the earlier worker health timestamp case: acquisition alone leaves the success marker unchanged, then an actual successful CPU run and the encoding notification publish the timestamp through the real WorkerHealthService. The timestamp range assertion is retained after that success.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Only a successful CPU leg may mark searches as served on the CPU; a miss keeps its fallback reason and does not.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request: batch-1 repair builder brief, 2026-10-09'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/b6d536-batch1-cpu-repair.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusLifecycleQueryOnCpuTest.java#cpuMissesDoNotClaimServiceButOneSuccessfulCpuLegDoes",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceCpuRecourseVisibilityTest.java#aCpuEncodeThatAlsoMissesTheRemainingBudgetGivesTheReasonAndItsCause",
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleQueryCpuRecourseTest.java#aTurnedAwayLegIsRecordedForItsTraceAndForStatus",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerHealthServiceQueryCpuRecourseTest.java#theHealthCheckReportsOnlySuccessfullyServedCpuQueryLegs"
      ]
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/HealthyFakeLlamaServer.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Fixture ownership instrumentation changes only: the producer records PID plus creation instant at startup, cleanup and crash injection hold a matching ProcessHandle and recheck birth before termination. No ancestry, name or bare PID is used to stop a server. The fake response modes, launch arguments, all existing resume/device-launch inputs and every expected result remain unchanged. The two E6 launch cases, ten step10 resume cases and controller reason table run fresh in b6d536-batch1-safe-launches.json."
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PersistentExtractionSandboxTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppShutdownWiringTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatResidencyRoutesTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/KnowledgeSearchDuringChatDrainTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationSelfTestSeamTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatBootOrderTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatControllerStepFailureTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatHoldsTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResidencyTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SelfTestSessionSeamTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/VduDeferralDuringChatDrainTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecoverableQuitReplayTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/VduGenerationDoesNotSlowIndexingTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/inference/VerifiedLlamaTestServer.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ChatDeviceLostStopFailureTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ChatResidencyUnloadStepTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerApplyConfigTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerExternalServerTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerShutdownTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ManagedLlamaAdoptionTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/chat/AssistLeaseTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/chat/ChatDrainPolicyTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/chat/GenerationTrackerTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceReasonCodeContractTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "PMD preservation only. Use already imported names instead of redundant qualification; hold close-only leases immediately before try(resource), with the same acquisition position and reverse close ordering; maximum diagnostic samples use AtomicReference set/get instead of a repeatedly assigned volatile reference. Inputs, observed behavior, timing bounds and every assertion are unchanged. Only four method-level DoNotTerminateVM exceptions are added: the rule explicitly targets J2EE/JEE apps and is inapplicable to desktop quit and spawned process-recovery/server fixtures. Their exit behavior is unchanged. Unsafe process-cleanup selections are explicitly excluded from builder execution and remain unverified. The existing replay fixture only documents its expected bounded-drain exception and its child-JVM exit authority; neither the negative-control behavior nor any assertion changes. Build cleanup removes overwritten PID initializers in PersistentExtractionSandboxTest, retaining original inputs, resource lifetimes and every existing assertion. CI portability repair: spawn the current JVM command on every OS instead of assuming java.exe under java.home. The deliberate interrupted extraction, real quit/replay process boundary, exact attempt counts, duplicate rejection and DONE assertions remain."
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-batch2-20261010-independent",
  "verdict": "reject",
  "digest": "sha256:ad20ac59c2ee44a04b8baf932ed0906eb9df41edb7584e037cbc4893da6d2c11",
  "note": "New Health, CPU-service and real Engine/parser chunk checks independently fail on their old parent and pass on this candidate. Full preservation evidence for every adaptation is unavailable under mandatory unsafe-cleanup exclusions; those skips are not passes."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS3-20261015",
  "verdict": "reject",
  "digest": "sha256:f42ea7fac7d19d5943917f4e48b45ad8fce80d73f78668265616419d90acdfea",
  "note": "Candidate rerun mismatch: RecoverableChunkCompletenessTest reached the real replay but the operation remained RUNNING at its 60-second deadline and the probe exited 99, contradicting the recorded candidate pass. An isolated retry passed; this documents intermittency without clearing the observed mismatch under the requested stopping rule."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS4c-20261015",
  "verdict": "accept",
  "digest": "sha256:adff36927d74ca70b35b29c09ec05cf53521920b56c32f2bd9ae4f175665d669",
  "note": "The timing adaptation retains the durable chunk-completeness assertions; both candidate test files pass. The declared 1e333857b before rerun with its compatibility patch reproduces the incomplete-chunk assertion failures without a patch-created failure."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS5c-20261016",
  "verdict": "accept",
  "digest": "sha256:72e3f71c9f1eea3357f04a3d5f92b022f603159e0090e9737797188a3a1c5595",
  "note": "Re-bound to origin/main 298354254 after reviewing the PID-local cleanup and declared add-only compatibility patches. Candidate checks and shared sandbox cases pass; 1e333857b reproduces the 997-chunk/admission, stale-health and premature-CPU-service failures, while two backfill passes rely on scaffolding and do not prove implemented preservation."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS5d-20261016",
  "verdict": "accept",
  "digest": "sha256:5cd7d82710ff6b41f12034b18c30e915c9cecc686d056b4b5c5ada8d4489100e",
  "note": "PID declaration scopes and shared fixture cleanup preserve the guarded outcomes; all listed candidate checks and the patched 1e333857b outcomes match. The declared stub-dependent backfill preservation limit remains an observation."
}
```
