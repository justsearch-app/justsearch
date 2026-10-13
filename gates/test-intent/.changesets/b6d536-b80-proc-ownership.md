---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b80-proc-ownership
---

# A GPU child's release barrier precedes its exit, and a surviving self-test candidate activates nothing (b80 F4, F5)

Two reviewer findings on 44a9e8aa2 were confirmed by assertion and repaired at their cause. F4: DefaultChildControl published a GPU child's exit (and its stop future) before it registered the pending VRAM release, so a model switch woken by the exit could see no barrier and start the replacement while the old process's memory was still being reclaimed; the release is now registered before the exit is published and its poll starts after. F5: ChildSelfTestLauncher only logged a candidate that survived its forced stop and returned the exercise's success, which RuntimeActivationService then committed; a run whose candidate (or a process it started) did not exit is now a failed run that is never exercised, so activation commits nothing.

Both checks are new files, so no earlier changeset's checks changed. Fail-before was recorded with --before 44a9e8aa2 (no node_modules linked in this worktree): exitCompletionPublishesGpuBarrierBeforeNotifyingWaiters fails with "the release is pending when the exit is published ==> expected: <[false]> but was: <[true]>", and selfTestStopFailurePreventsActivation with "a candidate that survived its stop is not a passed self-test: GPU runtime activated. ==> expected: <failed> but was: <passed>"; both pass on this change.

The same change repairs F1-F3 in scripts/dev (dev-runner stop: termination validated and acted on through one handle, a record without a recorded birth never establishes ownership, an unresolved registered helper makes the stop incomplete). Their checks are dev-runner node tests under scripts/dev, which are out of this gate's scope by design (tempdoc 966 D1), and are reported with the builder report instead.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/DefaultChildControlGpuBarrierTest.java"
      ],
      "class": "New",
      "behaviour": "When a GPU child stopped for a model switch exits, a caller woken by that exit already sees the child's VRAM release as pending (awaitGpuRelease(Duration.ZERO) returns false), and a replacement GPU child it starts from the exit is not spawned until that release completes; the ledger records the gpu-release before the replacement's child-spawn.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 request (item 5)",
          "quote": "GPU co-residence"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "process ownership and exit accounting",
          "delegationLocation": "task t-20261005-b6d536 request",
          "delegationScope": "task item 6 (process ownership and exit accounting); the orchestrator decided the b80 builder brief under this delegation",
          "choice": "a GPU pending release is visible before the exit is published.",
          "label": "agent choice (orchestrator decision in the b80 builder brief)"
        }
      ],
      "conflictSearch": "DefaultChildControlTest (unchanged) keeps its GPU release, model-switch barrier and engine-ending stop expectations (no release poll when the stop ends the Engine); ChildControl.awaitGpuRelease javadoc and Spec B section 2.6 step 8 describe the release as a barrier for the next GPU start; decision records under docs/decisions/ searched for GPU release or child exit ordering: ADR-0050 (GPU co-residence by prevention) and ADR-0051 (held process lifetimes and owner-recorded exits) agree, none conflicting.",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/DefaultChildControlGpuBarrierTest.java#exitCompletionPublishesGpuBarrierBeforeNotifyingWaiters"
      ],
      "evidence": "gates/test-intent/evidence/b6d536-b80-proc-ownership.json",
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationSelfTestStopFailureTest.java"
      ],
      "class": "New",
      "behaviour": "A runtime activation whose self-test candidate is healthy and exercised but survives its forced stop (ChildSurvivedException) ends with result failed: the stop was attempted once, no candidate context is installed, and the stored server executable and model path are unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 request (item 6)",
          "quote": "process ownership and exit accounting"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "process ownership and exit accounting",
          "delegationLocation": "task t-20261005-b6d536 request",
          "delegationScope": "task item 6 (process ownership and exit accounting); the orchestrator decided the b80 builder brief under this delegation",
          "choice": "a self-test whose candidate survives is not a success and changes no activation settings.",
          "label": "agent choice (orchestrator decision in the b80 builder brief)"
        }
      ],
      "conflictSearch": "RuntimeActivationServiceChatProfileTest, RuntimeActivationServiceTest and SelfTestGpuBarrierTest (unchanged) keep their passed, failed and refused self-test expectations; SelfTestRun's javadoc now states that a run whose candidate did not exit is never exercised; decision records under docs/decisions/ searched for self-test or activation commit rules: ADR-0051 names the activation self-test's llama-server as a held child whose stop is recorded by its owner, none conflicting.",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationSelfTestStopFailureTest.java#selfTestStopFailurePreventsActivation"
      ],
      "evidence": "gates/test-intent/evidence/b6d536-b80-proc-ownership.json",
      "repaired": true
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VC-proc3-20261014",
  "verdict": "accept",
  "digest": "sha256:23d42cb418b2c4433bd87f968d2f22a6252f2109b421a0b87b4d4cdec44d6d69",
  "note": "VC-proc3 (independent Claude verifier) judged the b80 records at 002073b2b: DefaultChildControlGpuBarrierTest and RuntimeActivationSelfTestStopFailureTest are byte-identical to a164833f5, fail at 44a9e8aa2 by the recorded assertions and pass at 002073b2b; preservation checks RuntimeActivationServiceChatProfileTest, RuntimeActivationServiceTest and SelfTestGpuBarrierTest pass on both. The Java behaviour itself was accepted by VC-procjava3 on proc-java 1eb01f51e. DefaultChildControlTest was not run because it ends processes by PID alone; its preservation rests on the file being unchanged. Digest bound with --base 44a9e8aa2, the lane's base."
}
```
