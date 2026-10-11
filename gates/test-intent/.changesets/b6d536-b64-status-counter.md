---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b64-status-counter
---

Server-owned publication metadata replaces the completion inventory. Independent acceptance is pending.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/DefaultEngineComponentRegistryTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/TransitionRunnerTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/PlacementStageTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuProfileResolverTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/power/EnergyStatePollerTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/runtimestate/RuntimeReconcilerTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/SettingsCommitCoordinatorTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RemoteIndexingJobsBridgeTest.java",
        "modules/core/src/test/java/io/justsearch/core/scheduling/BulkWorkPolicyTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/WatchedRootsStatusCounterTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusChangeCounterHttpTest.java",
        "modules/ui-web/src/api/statusOwner-raw-settings.test.ts",
        "modules/ui-web/src/api/statusOwner-reattach.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Successful status-relevant owner publications advance a shared counter; reads, identical publications, settings replay and timestamp-only install rewrites do not. Chat loading and completion each signal. Every API response reports the counter without sampling, canonical reads report a conservative reflected revision. Raw plugin settings writes refresh status; read-only reattachment does not. Original verifier assertions are retained.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "the UI must learn that canonical state changed from the server that owns the state, not from client-side guesses about which requests or operation names change it",
          "location": "task t-20261005-b6d536 request (b64 builder role contract, task item 5 / S20)"
        }
      ],
      "evidence": "gates/test-intent/evidence/b64-status-counter-repaired.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/DefaultEngineComponentRegistryTest.java#statusCounterTracksPublicationAndNotSnapshotOrNoop",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/TransitionRunnerTest.java#statusCounterTracksBothChatTransitionEdges",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/PlacementStageTest.java#statusCounterTracksInstalledDistributionAndNotTimestampOnlyRewrite",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuProfileResolverTest.java#statusCounterTracksGpuProfileAndNotReadsOrIdenticalPlan",
        "modules/app-services/src/test/java/io/justsearch/app/services/power/EnergyStatePollerTest.java#energyStatusChangesAdvanceCounterButReadsAndRepeatedPollsDoNot",
        "modules/app-services/src/test/java/io/justsearch/app/services/runtimestate/RuntimeReconcilerTest.java#statusCounterTracksActivationProcedureAndNotStatusRead",
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/SettingsCommitCoordinatorTest.java#statusCounterTracksSettingsCommitAndNotExactReplay",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RemoteIndexingJobsBridgeTest.java#statusCounterTracksJobsAndNotReadOnlyReattachment",
        "modules/core/src/test/java/io/justsearch/core/scheduling/BulkWorkPolicyTest.java#statusCounterTracksVerdictChangesAndNotRepeatedInputs",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/WatchedRootsStatusCounterTest.java#statusCounterTracksRootsAndNotDuplicateRemoval",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusChangeCounterHttpTest.java#statusCounterResponseReflectsACompletedSampleAndNeverSamplesForMetadata",
        "modules/ui-web/src/api/statusOwner-raw-settings.test.ts",
        "modules/ui-web/src/api/statusOwner-reattach.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/api/statusCounterSignal.test.ts",
        "modules/ui-web/src/api/statusOwner-extra.test.ts"
      ],
      "class": "New",
      "behaviour": "Additive server metadata coalesces bursts, retries stale observations only on another request, isolates head epochs and preserves caller bodies. Read-only approvals and replayed completions with unchanged server counters cause no fresh read. Failed responses can convey a different client's completed publication. The copied verifier approval/replay assertions remain unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "the UI must learn that canonical state changed from the server that owns the state, not from client-side guesses about which requests or operation names change it",
          "location": "task t-20261005-b6d536 request (b64 builder role contract, task item 5 / S20)"
        }
      ],
      "evidence": "gates/test-intent/evidence/b64-status-signal.json",
      "newChecks": [
        "modules/ui-web/src/api/statusCounterSignal.test.ts",
        "modules/ui-web/src/api/statusOwner-extra.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/api/statusCompletion.test.ts",
        "modules/ui-web/src/api/statusInvalidation.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.after-action.test.ts",
        "modules/ui/src/test/java/io/justsearch/ui/api/LocalApiCorsPolicyTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/OpenAiCompatControllerTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusIsolationHttpTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation",
        "input re-encoding"
      ],
      "whatMoved": "Preserved b59/b61 fixtures now supply the server metadata carried by real transports; the Brain transport double invokes the same generic metadata reader as authorizedFetch. The old action/render assertions remain. CORS exact expectations include the three documented additive exposed headers. StatusIsolation child cleanup captures creation time at start and verifies it before stopping. The cross-client case is an additional assertion, with assertion failure on 23b8679ee and pass recorded in before-cross-client.log / cross-client.log.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "the UI must learn that canonical state changed from the server that owns the state, not from client-side guesses about which requests or operation names change it",
          "location": "task t-20261005-b6d536 request (b64 builder role contract, task item 5 / S20)"
        }
      ]
    }
  ]
}
```

## Acceptance records

Pending independent verifier judgment; the builder does not accept its own change.

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-status5-independent",
  "verdict": "reject",
  "digest": "sha256:d8f61542bfcf8eac4396504390fc1c8b6006515a04de4e653303def9e1b2f7c3",
  "note": "Independent ea0c311cb: FAIL S20 / OWN-ENCRYPTION-OWNER on built UI and native head: external encryption lock completes 200, next read-only UI request and render still show unlocked, canonical fresh status is locked, zero fresh reads. OWN-RESTART-MISSING: controlled new epoch plus refused canonical read and an older response without metadata restores Online/READY while native canonical state is OFF. Real-HTTP sampler stale/recovery counter-publication assertions also fail. Evidence and preservation/timing limits: F:/agent-sandbox/design-b6d536/codex/verify-status5/report.md."
}
```
