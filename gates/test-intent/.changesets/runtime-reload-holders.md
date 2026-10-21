---
schema: test-intent.v1
task: t-20261020-712d3e
author-role: builder
author-session: reload-holders-builder
---

# Runtime reload holder safety

The delegated builder brief supplied scenarios v2, R1–R8. This adds checks without changing an existing expectation. Independent acceptance is pending.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRuntimeReloadTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Runtime-reload dispatch stops capturing a serving view. Real client/server lease dispatch permits idle and repeated reloads, bounds holder refusal and permits retry, refuses migration before destruction including changes while queued, drains older shared-runtime holders, serializes concurrent swaps and does not reopen serving across shutdown. Native IO is mocked in this deterministic boundary fixture.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-712d3e",
          "scenario": "R1",
          "quote": "On a real Engine with no outstanding work, POST /api/admin/runtime/reload succeeds and reports the swap duration, and a second reload right after it also succeeds.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-712d3e",
          "scenario": "R2",
          "quote": "No resource still used by issued work is closed. Views may become `retiring` while their holders finish; that stays.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-712d3e",
          "scenario": "R3",
          "quote": "A reload during a migration (MIGRATING with separate serving and writer runtimes, SWITCHING, or a concurrent start or cutover) is refused with a clear error.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-712d3e",
          "scenario": "R4",
          "quote": "If a real holder does not drain within the 5-second holder bound, the reload fails with the existing refusal before any destructive work. Serving admission is then restored, and after the holders release, a second reload succeeds.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-712d3e",
          "scenario": "R5",
          "quote": "Only the runtime-reload dispatch stops taking a serving view; other lifecycle RPCs stay leased.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-712d3e",
          "scenario": "R6",
          "quote": "An automated check through the real client and the real server's lease supplier fails today with the active-holder refusal specifically (not a generic timeout) and passes after.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-712d3e",
          "scenario": "R7",
          "quote": "Two concurrent reloads, latched around publication: each executes against a valid lifecycle owner, none touches closed services, and neither overwrites the newer publication.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-712d3e",
          "scenario": "R8",
          "quote": "a refused drain never reopens admission once shutdown has started;",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "location": "task t-20261020-712d3e request",
          "quote": "Never cut validation at trust boundaries, error handling that prevents data loss, security or accessibility."
        }
      ],
      "evidence": "gates/test-intent/evidence/runtime-reload-holders.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRuntimeReloadTest.java#idleClientReloadAndImmediateRepeatDoNotRetainTheirOwnServingView",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRuntimeReloadTest.java#holderTimeoutRestoresAdmissionAndRetrySucceedsWithoutClosingIssuedRuntime",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRuntimeReloadTest.java#interruptedHolderDrainRestoresAdmissionAndRetrySucceeds",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRuntimeReloadTest.java#reloadRefusesMigratingBeforeDestructiveWork",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRuntimeReloadTest.java#reloadRefusesSwitchingBeforeDestructiveWork",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRuntimeReloadTest.java#reloadRefusesSeparateServingAndWriterEvenBeforeMigrationStatePublication",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRuntimeReloadTest.java#olderViewSharingRuntimeMustDrainEvenWithNoCurrentHolders",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRuntimeReloadTest.java#concurrentReloadsSerializeAroundPublication",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRuntimeReloadTest.java#shutdownDuringHolderDrainNeverReopensAdmissionOrOpensReplacement",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRuntimeReloadTest.java#queuedReloadCannotResurrectClosedServer",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRuntimeReloadTest.java#reloadQueuedBehindMigrationStartRechecksTheNewOwner",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRuntimeReloadTest.java#reloadQueuedBehindCutoverRechecksTheNewOwner",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRuntimeReloadTest.java#interruptedReloadWithoutHoldersLeavesServingAdmissionOpen",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRuntimeReloadTest.java#reloadRefusesUnavailableMigrationStateBeforeDestructiveWork"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRuntimeReloadTest.java"
      ],
      "class": "New",
      "repaired": false,
      "behaviour": "New pass-after checks of running-reload ownership through actual worker exit after deadline expiry or running cancellation. These are not repairs of deadline or cancellation ownership. On f6a14b5a66e7b879c711ef9ca54280723de2c242, deadlineReleasesCallerButOwnsRunningReloadUntilWorkerExit fails at line 350 and cancellationKeepsRunningReloadOwnedUntilItsWorkerExits at line 397: both entered.await(3, TimeUnit.SECONDS) assertions fail before the opener barrier is reached. The baseline reload dispatch captures its own serving lease, then waits for that same lease in retireServingView; the deadline can interrupt this drain, but cannot make the opener reachable. The cancellation check never reaches cancelInteractive. Therefore these baseline failures do not demonstrate premature ownership release after the named events. The New row of docs/reference/testing/test-intent.md requires assertion fail-before only for repaired behaviour; repaired is false here. The candidate reaches the blocked opener, releases the caller on deadline or cancellation, asserts activeWorkCount remains 1 until the worker is released, then observes 0.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-712d3e",
          "scenario": "R5",
          "quote": "The reload dispatch itself keeps Engine work owned until the worker actually exits, including deadline expiry, cancellation, executor rejection, and cancellation before execution. A timed-out caller never lets shutdown close resources beneath a running swap.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/runtime-reload-ownership-mutation.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRuntimeReloadTest.java#deadlineReleasesCallerButOwnsRunningReloadUntilWorkerExit",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRuntimeReloadTest.java#cancellationKeepsRunningReloadOwnedUntilItsWorkerExits"
      ],
      "mutationProof": "The before run is candidate 85ee1ac9d25658721452f40b7f8165d24d5946ae plus gates/test-intent/evidence/runtime-reload-release-on-caller-exit.patch: only the reloadRuntime failure callback closes its Engine work handle before delivering a deadline or cancellation failure, removing retention until worker exit while leaving dispatch, serving leases, opener entry and tests unchanged. Both unmodified checks pass on the candidate; under this mutation deadlineReleasesCallerButOwnsRunningReloadUntilWorkerExit fails at EngineRuntimeReloadTest.java:353 and cancellationKeepsRunningReloadOwnedUntilItsWorkerExits at EngineRuntimeReloadTest.java:400, each at its activeWorkCount ownership assertion (expected 1, observed 0), after the opener latch has succeeded. This proves check sensitivity, not a named ownership defect in f6a14b5a6; that baseline reachability evidence remains in gates/test-intent/evidence/runtime-reload-ownership-classification.json."
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRuntimeReloadTest.java"
      ],
      "class": "New",
      "repaired": false,
      "behaviour": "New checks of preserved behavior, not repaired checks: executor refusal releases reload ownership without entering the worker; queued cancellation removes the queued task, releases ownership and never enters the opener; ordinary resetIndex lifecycle calls retain a serving-view lease until completion. Each named check must pass unchanged on f6a14b5a66e7b879c711ef9ca54280723de2c242 and on the candidate. Both runs are recorded in the cited evidence. These use the New row with repaired false because the checks are new but their named behavior is preserved. The evidence utility exits 1 when --before finds these preservation checks passing, since its repair-oriented success condition requires assertion fail-before; this is not a failed preservation run.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-712d3e",
          "scenario": "R5",
          "quote": "The reload dispatch itself keeps Engine work owned until the worker actually exits, including deadline expiry, cancellation, executor rejection, and cancellation before execution. A timed-out caller never lets shutdown close resources beneath a running swap.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-712d3e",
          "scenario": "R5",
          "quote": "Only the runtime-reload dispatch stops taking a serving view; other lifecycle RPCs stay leased.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/runtime-reload-ownership-classification.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRuntimeReloadTest.java#executorRejectionReleasesReloadOwnershipWithoutEnteringWorker",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRuntimeReloadTest.java#cancellingQueuedReloadReleasesItsOwnerWithoutEnteringWorker",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRuntimeReloadTest.java#otherLifecycleCallsStillRetainServingView"
      ]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "codex-verifier-c93a81d9c-20261021",
  "verdict": "accept",
  "digest": "sha256:e9e9ba80425a9cba3a7f21bfafb5120fb596ec4ed69a25fae20585fb19a54f20",
  "note": "Verified c93a81d9c: 216 candidate checks, 167 predecessor preservation checks, native HTTP reload/ingestion/backfill/restart/migration checks; tmp/verify-http/c93a81d9c/report.md"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "codex-verifier-784a8dafc",
  "verdict": "accept",
  "digest": "sha256:c34f5634793061154fb682afc915e9c91788ad9ba1d96fc73cf728c25ffd5fa8",
  "note": "31 checks/build; CI lint; tmp/verify-http/784a8dafc/report.md"
}
```

```json
{
  "kind": "audit",
  "role": "reviewer",
  "session": "codex-auditor-850-20261021",
  "verdict": "reject",
  "digest": "sha256:c34f5634793061154fb682afc915e9c91788ad9ba1d96fc73cf728c25ffd5fa8",
  "note": "behaviour line 21 calls deadline and running-cancellation checks preservation, but both fail on f6a14b5a6 before reaching the opener and are absent from newChecks/evidence; provide genuine before/after preservation evidence or reframe and evidence them as repaired checks. Independent results: tmp/audit-850/ownership-before.json and preservation-and-producers.json."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "codex-verifier-dfab48022",
  "verdict": "accept",
  "digest": "sha256:0f57069fb93927ae8ca285d8cb2b3f4c957507bc4b258ddfa20cf50614ea84de",
  "note": "Classes reviewed; preservation 3/3 before/after; mutation fails exactly at 353/400. tmp/verify-http/dfab48022/report.md"
}
```

```json
{
  "kind": "audit",
  "role": "reviewer",
  "session": "codex-auditor-850-20261021",
  "verdict": "accept",
  "digest": "sha256:0f57069fb93927ae8ca285d8cb2b3f4c957507bc4b258ddfa20cf50614ea84de",
  "note": "Prior finding resolved: new ownership checks are distinct from genuine preservation checks. Independently reran both mutation checks: candidate passes; reload-only premature release fails ownership assertions at lines 353 and 400. Evidence: tmp/audit-850b/ownership-mutation-disk-retry.json; initial tmp-space failure retained."
}
```
