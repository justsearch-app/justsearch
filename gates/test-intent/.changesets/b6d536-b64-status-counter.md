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

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-status6-independent",
  "verdict": "reject",
  "digest": "sha256:d8f61542bfcf8eac4396504390fc1c8b6006515a04de4e653303def9e1b2f7c3",
  "note": "Candidate 6377d0030 changes what advances this counter (structural backstop over the whole body) and where the client observes it (every response including refusals), so this record's evidence no longer describes the running mechanism and is invalidated. Independent candidate 6377d0030: FAIL. (1) StatusPublicationBackstop counts measurement noise: live idle head advanced the counter 51 times in 10 min (GPU utilisation 0<->1, VRAM, recent* arrays, telemetry window state); the idle mounted UI made 37 fresh reads and a failed 422 action triggered a fresh read and snapshot replacement in 3 of 4 runs. (2) Metadata loss: UI kept 'unlocked' for 12 polls while canonical was locked (live, header-stripping proxy; module case fails, passes on 93479e944). (3) After a real head restart a 1000-request UI burst produced 392 refused fresh reads in 3.6 s, contradicting 'refused fresh reads do not create a loop'. Passing: lock/unlock, raw settings, second tab, approval, agent tool, chat, GPU/index, replay/reattach, restart display, full suites, architecture, regen-all, API contract, lane F. Report: F:/agent-sandbox/design-b6d536/codex/verify-status6/report.md"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-status7-independent",
  "verdict": "reject",
  "digest": "sha256:241d3b09dd551375b313d6829ae962f7acc44e4efc618db818b7ebdeb9498e03",
  "note": "Candidate 6f177f241 changes statusCounterSignal.test.ts (entry 2's new check), so entry 2's evidence is invalid (governance: evidence-invalid) and statusOwner-extra.test.ts becomes uncovered; the counter now compares classified state only, so entry 1 no longer describes what advances it. Independent verify-status7 on 6f177f241: FAIL S20 / OWN7-BACKOFF. On the built UI against the running candidate head, after 40 s of refused fresh reads (6 refused, back-off works) and a successful ordinary poll, a lock by another client and, separately, the mounted UI's own successful lock action are not shown at the next render: 0 fresh reads, display unlocked while canonical is locked, caught up only 8-15 s later by ordinary polling. statusInvalidation.ts keeps backoffUntil (up to 30 s, Retry-After up to 300 s) until a successful signal-triggered fresh read; successful ordinary polls do not end it and due() gates every action and receipt signal on it. Unit form fails on 6f177f241 and passes on 823c82104."
}
```
