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
      "behaviour": "A runtime reload retains Engine work ownership through actual worker exit without capturing a serving view. Real client/server lease dispatch permits idle and repeated reloads, bounds holder refusal and permits retry, refuses migration before destruction including changes while queued, drains older shared-runtime holders, serializes concurrent swaps and does not reopen serving across shutdown. Native IO is mocked in this deterministic boundary fixture. Cancellation, deadlines, executor refusal, queued cancellation and ordinary lifecycle serving leases are preservation checks. Real HTTP search, ingestion and restart checks remain unverified in this builder pass; see tmp/712d3e/real-engine-steps.md.",
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
    }
  ]
}
```

## Acceptance records
