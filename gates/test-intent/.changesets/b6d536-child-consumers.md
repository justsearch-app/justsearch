---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: 886833dd-a08f-492f-a40a-cc0976b5ce5e
---

# Test-intent entries

Task t-20261005-b6d536, Spec B steps 7 to 9 (the parts with no Rust). Step 7: `ChildControl` asks
the Engine job whether each child it started is inside it and records the answer as `contained`
on `child-start`; the boot reconciler (`ManagedChildReconciler`) leaves a matched live child that
the children ledger records inside its Engine's job to that ledger (`child-survived`), and keeps
ending every other matched live child as the fallback while the Engine has no job and for children
of an earlier version (Spec F step 13 retires that fallback one release after the Engine job). Step 8: the Engine records its own shutdown reason
(`engine-self-intent`) once, before the close, for every cooperative exit (QUIT, RESTART, UPGRADE,
HANG, the escalated restart and the updater's exit), never for a fatal exit; the dev-runner's
classifier ranks the owner's intents above the Engine's own. Step 9: the stop census drops the
Engine's stderr markers and the stop's kill list, a forced child stop counts only with the child
stop code, the stop report carries the ledger summary instead of the taskkill fields, and the
e456 child policy reads the ledger and requires the old llama-server stopped on every path (D2).

Evidence: `gates/test-intent/evidence/b6d536-child-consumers.json` runs every new check on the
candidate. `gates/test-intent/evidence/b6d536-child-consumers-before.json` is the fail-before on
1c6f8da2c of `WindowsEngineJobTest#containsAnswersWhetherAProcessIsInTheEngineJob`,
`EngineJobChildLedgerTest`, `ManagedChildReconcilerTest#aChildTheLedgerRecordsInItsEngineJobIsLeftToItWhileAnUnrecordedOneIsEnded`
and the four new behaviours of `EngineShutdownSelfIntentTest`, each an assertion failure. On
1c6f8da2c the children of the killed holder already die with it and are closed as OWNER_DIED (the
Engine job landed with 624dcfc92); `EngineJobChildLedgerTest` fails there on its last assertion,
that every child-start records the child in the Engine job. Preservation, passing on 1c6f8da2c and
on the candidate: `EngineShutdownSelfIntentTest`'s three cases (a fatal exit, the hook joining a
cooperative exit, a failing sink; on 1c6f8da2c the class drives the sequence without the
self-intent seam) and `ManagedChildReconcilerTest#withoutContainmentAnIdentityMatchedOrphanLlamaIsStillEndedAtBoot`
(on 1c6f8da2c the class uses the only reconciler there is) (`gates/test-intent/evidence/b6d536-child-consumers-preserved.json`),
and on the candidate (`b6d536-child-consumers.json`).
`DefaultChildControlTest#childStartRecordsWhetherTheChildIsInTheEngineJob`
and `HeadlessAppSelfIntentTest` do not compile on 1c6f8da2c (the `JobMembership` seam and
`HeadlessApp.engineSelfIntent` are new here); no assertion fail-before is claimed for them.

Out of this gate's scope, run by hand against the 1c6f8da2c sources with the candidate's test file
copied in: `scripts/dev/test-dev-runner-exit-census.mjs` fails 7 of 19 (the ledger summary has no
incarnation and child counts, the stderr marker still makes an extraction exit expected, a forced
child stop with code 1 or the Engine's stop code is still expected, the PowerShell observer still
reports the stop's kills); `scripts/dev/test-dev-runner-supervisor.mjs` fails
`testTheOwnersIntentOutranksTheEnginesOwn` (a hang close is classified ENGINE_RESTART instead of
the supervisor's TIMEOUT); `scripts/dev/test-dev-runner-terminal-children.mjs` fails because the
stop still hands its kills to the census; `scripts/dev/test-dev-runner-death-observability.mjs`
fails because the stop report still has `taskkillExitCode`; `scripts/jseval/lane-f/e456-instruments.test.mjs`
fails 3 (a path with no children ledger passes on the OS alone, and a crash or restart with the
old llama-server alive passes when chat returns); `scripts/supervisor-conformance/component-recovery-scenario.test.mjs`
fails (the scenario's identity rule is a ±1 s window, not `sameBirth`). The new conformance cases
`hang-close-the-engine-runs-is-the-supervisors-timeout` and
`a-crash-during-the-requested-close-stays-unexpected`, with the candidate's contract, fake Engine
and adapter on the 1c6f8da2c classifier, fail on the exit's reason (ENGINE_RESTART instead of
TIMEOUT); `engine-self-restart-is-requested` fails on 1c6f8da2c's fake Engine (no self-intent
written) and passes on its classifier.

The Engine's escalated restart is recorded as reason ENGINE_RESTART with detail escalated and
expected code 5. Spec B's self-intent table names CRASH_RECOVERY there, but the ledger schema's
reason enum for engine-self-intent has no CRASH_RECOVERY, and the schema binds.

This slice edits check files named by evidence of earlier changesets. Four evidence files were
re-run unchanged in their checks: `b6d536-child-control.json`, `b6d536-llama-child.json` and
`b6d536-no-adoption-reconciler.json` (no fail-before; the three `ManagedChildReconcilerTest`
cases this slice had removed are restored unchanged), and `b6d536-engine-job.json` with its
fail-before on f75486245, where all three of its checks again fail by assertion. One was not
re-run: `b6d536-llama-child-registration.json` records its fail-before on 56b1c91e3 with
`ChildControlHarness` as overlay; the harness now names `DefaultChildControl.JobMembership`, which
does not exist there, so a re-run would record a compile failure in place of the assertion
failure. It stays valid at its own base, and `DefaultChildControlTest#exitWhileRegisteringRemovesTheEntry`
is unchanged and passes on this branch (in `b6d536-child-control.json`, which runs the whole class).

```json
{
  "entries": [
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/containment/WindowsEngineJobTest.java",
        "modules/app-api/src/integrationTest/java/io/justsearch/app/api/runtime/containment/EngineJobChildLedgerTest.java"
      ],
      "class": "New",
      "behaviour": "Spec B T23 on real Windows processes. WindowsEngineJobTest#containsAnswersWhetherAProcessIsInTheEngineJob: in a probe JVM, before the Engine job is installed contains has no answer; after it, a child the Engine started and the Engine itself are in the job, and the process that started the Engine is not. EngineJobChildLedgerTest#killedEngineChildrenDieWithItAndTheNextEngineRecordsOwnerDied: a holder JVM boots like the Engine (Engine job, then DefaultChildControl.openForEngine with it) and starts two console children through the control, one cmd.exe that starts a ping grandchild and one ping. The holder is killed with taskkill /F; within 3 s nothing it started is alive, console hosts and the grandchild included; each child-start recorded contained true; and the next Engine's openForEngine on the same data directory writes exactly one owner-died OWNER_DIED with OS check GONE per child, naming the killed holder's instance as the previous owner, and no other ending.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S14",
          "quote": "If the Engine crashes, hangs or is restarted, every helper ends with it (including a chat server just started but not yet registered); the supervisor restarts it and chat returns without user action (D2). Unless a game or low GPU memory blocks it, in which case chat shows why.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S15",
          "quote": "Every exit of the Engine and its helpers is recorded by its owner as intended (with reason) or unexpected; a real crash during an intended stop stays unexpected; an unknown exit time reads as unknown; a clean quit shows zero unexpected exits and nothing unaccounted, console hosts included.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-child-consumers-before.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/containment/WindowsEngineJobTest.java#containsAnswersWhetherAProcessIsInTheEngineJob",
        "modules/app-api/src/integrationTest/java/io/justsearch/app/api/runtime/containment/EngineJobChildLedgerTest.java"
      ]
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/DefaultChildControlTest.java",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/ChildControlHarness.java"
      ],
      "class": "New",
      "behaviour": "Spec B step 7: childStartRecordsWhetherTheChildIsInTheEngineJob starts three children through DefaultChildControl with a job-membership seam answering true, false and no answer in turn. The seam is asked about each started child's own process, in start order, and the three child-start records carry contained true, false and null. ChildControlHarness gains that seam (field job, default JobMembership.NONE) and passes it to the control's constructor.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S14",
          "quote": "If the Engine crashes, hangs or is restarted, every helper ends with it (including a chat server just started but not yet registered); the supervisor restarts it and chat returns without user action (D2). Unless a game or low GPU memory blocks it, in which case chat shows why.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for Spec B steps 7 to 9",
          "choice": "child-start's contained is the Engine job's own answer for the started process (IsProcessInJob), read once at start; it is null when the Engine has no job or the answer cannot be read, never assumed true.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-child-consumers.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/DefaultChildControlTest.java#childStartRecordsWhetherTheChildIsInTheEngineJob"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/runtime/ManagedChildReconcilerTest.java"
      ],
      "class": "New",
      "behaviour": "Spec B step 7 with Spec F step 13's fallback. aChildTheLedgerRecordsInItsEngineJobIsLeftToItWhileAnUnrecordedOneIsEnded: three live predecessor records match their processes on every identity axis (PID, start time, executable): a healthy llama-server and an extraction child whose child-start in the data directory's children ledger says contained true (birth at the manifest start instant's millisecond), and an extraction child with no ledger record (an earlier version's). With the Engine contained, the reconciler HeadlessApp builds (ManagedChildReconciler.withContainment over fromChildrenLedger) ends the unrecorded child within 15 s and releases its record, leaves the two contained children running and keeps their records unchanged and in order.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S15",
          "quote": "Every exit of the Engine and its helpers is recorded by its owner as intended (with reason) or unexpected; a real crash during an intended stop stays unexpected; an unknown exit time reads as unknown; a clean quit shows zero unexpected exits and nothing unaccounted, console hosts included.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for Spec B steps 7 to 9",
          "choice": "A predecessor child the children ledger records inside its Engine's job is left to the ledger (child-survived) and keeps its manifest record; every other identity-matched live child, and every one while this Engine has no job, is still ended at boot (Spec F step 13 keeps that fallback until one release after the Engine job). Containment is read from the ledger's child-start (contained true, matched by PID and birth); an unreadable ledger means no child is known to be contained.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-child-consumers-before.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/runtime/ManagedChildReconcilerTest.java#aChildTheLedgerRecordsInItsEngineJobIsLeftToItWhileAnUnrecordedOneIsEnded"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineShutdownSelfIntentTest.java"
      ],
      "class": "New",
      "behaviour": "Spec B step 8, the Engine's own intent, on EngineShutdownSequence with a recording self-intent sink and one close step. eachCooperativeExitRecordsItsReasonAndExpectedCodeBeforeTheClose: QUIT records QUIT with code 0, RESTART ENGINE_RESTART with code 4, UPGRADE UPGRADE with code 0, HANG ENGINE_RESTART with detail hang and code 0, each before its close. escalatedRestartRecordsAnEngineRestartExpectingCodeFive: ENGINE_RESTART with detail escalated and code 5. updaterExitRecordsAnUpgradeExpectingCodeZero: the updater's exit records UPGRADE with code 0. aCloseTheJvmHookStartsRecordsItsReasonOnce: a close started by the shutdown hook records its reason once. Preserved: aFatalExitRecordsNoIntentAndItsHookAddsNone, theHookJoiningACooperativeExitAddsNoSecondIntent, aFailedIntentRecordDoesNotChangeTheExit (a sink that throws leaves the close and the exit code unchanged).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S15",
          "quote": "Every exit of the Engine and its helpers is recorded by its owner as intended (with reason) or unexpected; a real crash during an intended stop stays unexpected; an unknown exit time reads as unknown; a clean quit shows zero unexpected exits and nothing unaccounted, console hosts included.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for Spec B steps 7 to 9",
          "choice": "The Engine writes one engine-self-intent per exit, before the close, with the exit code it will end with; the escalated restart is recorded as ENGINE_RESTART with detail escalated (the ledger schema's reason enum has no CRASH_RECOVERY for the Engine); a fatal exit records none; a failed record never changes the exit.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-child-consumers-before.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineShutdownSelfIntentTest.java#eachCooperativeExitRecordsItsReasonAndExpectedCodeBeforeTheClose",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineShutdownSelfIntentTest.java#escalatedRestartRecordsAnEngineRestartExpectingCodeFive",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineShutdownSelfIntentTest.java#updaterExitRecordsAnUpgradeExpectingCodeZero",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineShutdownSelfIntentTest.java#aCloseTheJvmHookStartsRecordsItsReasonOnce"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppSelfIntentTest.java"
      ],
      "class": "New",
      "behaviour": "Spec B step 8 at the Engine's composition root: the sink HeadlessApp.engineSelfIntent(dataDir, instanceId) builds writes, for ENGINE_RESTART with detail hang and code 0, exactly one engine-self-intent record in the data directory's engine ledger, naming this process (pid, instance id and, on Windows, its creation FILETIME as a string) with that reason, detail and expected code, written by this process.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S15",
          "quote": "Every exit of the Engine and its helpers is recorded by its owner as intended (with reason) or unexpected; a real crash during an intended stop stays unexpected; an unknown exit time reads as unknown; a clean quit shows zero unexpected exits and nothing unaccounted, console hosts included.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-child-consumers.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppSelfIntentTest.java"
      ]
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/EngineWrittenChildrenFixtureTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/SelfTestGpuBarrierTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "DefaultChildControl.openForEngine takes the Engine's containment as a fifth parameter (the job the control asks about each child). Both tests pass EngineContainment.current(); inputs, assertions and oracles are unchanged (the fixture test still compares its records with governance/fixtures/exit-ledger/engine-written/children.v1.jsonl, unchanged: the test JVM installs no Engine job, so contained stays null as the fixture records; the barrier test still asserts the self-test's ledger order)."
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-b79-independent-20261007",
  "verdict": "accept",
  "digest": "sha256:802aa0223d6303dcfd51ca085d48a1e5135cc6119ab64d65982848a81b296a1b",
  "note": "Reviewed baseline tests, scenarios and sources before candidate changes; independent full suites and reflective/native fail-before checks support the New/Adaptation entries. Two direct new APIs cannot compile unchanged before and are honestly disclosed; no compile-failure claimed. This accepts test intent only: real S15 OCR clean-quit census still fails with seven nested tesseract gaps; overall slice fails."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS4a-20261015",
  "verdict": "accept",
  "digest": "sha256:61b3c8b08e0f2c923d1bda7a60ba3b6ed25a988b5caad7e559b0dbc88a2fbf02",
  "note": "Reviewed the containment, self-intent and reconciler expectations against item 6 and the current tests. Candidate checks passed and declared before assertions reproduced; the JobMembership and composition-root API absence reasons are truthful."
}
```
