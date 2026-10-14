---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: 886833dd-a08f-492f-a40a-cc0976b5ce5e
---

# Test-intent entries

Task t-20261005-b6d536, Spec B (item 6) steps 5 and 6: the main llama-server and the activation
self-test's candidate start and stop through `ChildControl`. Every stop of the main server carries
the reason of its caller (the L1 table: crash recovery, rollback, deactivate, reconfigure, model
switch, VDU enter and exit, and the Engine's own reason at terminal shutdown), the crash monitor
stays armed through a stop and ignores the stop's own REQUESTED exit, and a server that survives
both terminate calls blocks every later start until a retry ends it. The ILM's fixed 2 s sleep
after a stop is replaced by the GPU barrier (`awaitGpuRelease`). The self-test candidate is a
`LLAMA_SELF_TEST` child launched through the controller's `SelfTestLauncher` seam
(`ChildSelfTestLauncher`, replacing `ProcessSelfTestLauncher`), stopped with `SELF_TEST`, and the
launcher returns only after its exit, after every process it started has exited and after its GPU
release poll. `LlamaServerOps` no longer has its own registration rollback (`rollbackFailedRegistration`,
`unregisteredRollbackProcess`): `ChildControl.start` rolls back a refused registration. The exit
census gives the self-test candidate its own role.

Evidence: `gates/test-intent/evidence/b6d536-llama-child.json` runs the new checks on the
candidate. `gates/test-intent/evidence/b6d536-llama-child-before.json` is the fail-before of
`LlamaStopCodeSeamTest` on 56b1c91e3 (with `ExitCodeWatch` as overlay): there both planned stops
end llama-server with 1 instead of 0x4A530001 (assertion failures). `LlamaServerChildControlTest`,
`SelfTestGpuBarrierTest` and `HeadlessAppTerminalStopTest` do not compile on 56b1c91e3, because the
API they drive (`LlamaServerOps` with a `ChildControl`, `ChildSelfTestLauncher`,
`HeadlessApp.terminalStopOf`) is new here; no assertion fail-before is claimed for them. The census
case in `scripts/dev/test-dev-runner-exit-census.mjs` is out of this gate's scope; run against the
56b1c91e3 `stop-exit-census.cjs` it fails its role assertion (`child` instead of `llama-self-test`).
`gates/test-intent/evidence/b6d536-llama-child-registration.json` is the fail-before of
`DefaultChildControlTest#exitWhileRegisteringRemovesTheEntry` on 56b1c91e3 (with
`ChildControlHarness` as overlay): there the dead child's manifest entry stays (assertion failure).

Repair after the b56 verifier reject (candidate 1c6f8da2c). A llama-server whose manifest
registration was refused and whose ROLLBACK stop did not end it is handed to its caller again
(`ChildRegistrationRefusedException.survivor()`): `LlamaServerOps` holds it and
`LlamaServerProcessOwnership` owns it for the terminal stop. `DefaultChildControl.start` refuses a
`LLAMA_SERVER` or `LLAMA_SELF_TEST` start while a chat-server child is SURVIVED or still stopping,
after retrying (or awaiting) that stop under its first intent. This covers the rollback, deactivate,
relaunch and self-test paths. The entry 7 and entry 9 oracles are restored under their base names,
and the new checks are in the last two entries.
`gates/test-intent/evidence/b6d536-llama-child-rollback-survivor.json` runs them, with the
fail-before on bca7dd0dd, b6d536/child-consumers before this repair was merged into it (assertion
failures, among them the verifier's "a second llama-server was launched beside the rollback
survivor"). On 1c6f8da2c the merged `DefaultChildControlTest` does not compile (its harness there
has no `h.job`), so the merge re-ran the fail-before on bca7dd0dd. The census repair (console hosts of closed ledger children,
tier C helpers, one gap row per process, the b56 run2 replay) is in
`scripts/dev/test-dev-runner-exit-census.mjs`, which is outside this gate's scope. Its new cases fail on
1c6f8da2c by assertion; the run2 replay shows 26 gap rows for 18 processes there and none here. The
re-run evidence (`b6d536-child-control.json`, `b6d536-llama-child-registration.json`,
`b6d536-llama-child-terminal.json`) has unchanged check lists and refs.

This slice edits check files named by evidence of earlier changesets. Four evidence files whose
checks carry no fail-before were re-run unchanged in their checks:
`b6d536-chat-controller-guardrails.json`, `b6d536-chat-controller-unit.json`,
`b6d536-chat-controller-step56-unit.json` and `b6d536-child-control.json`. Five evidence files with a fail-before
(`b6d536-chat-controller-step4-repaired.json`, `b6d536-chat-controller-step4-preserved.json`,
`b6d536-no-adoption-launch.json`, `b6d536-no-adoption-rewrites-inference.json`,
`b6d536-no-adoption-ownership.json`) were not re-run: their adapted check files call
`stopLlamaServer(StopRequest)`, which does not exist at their before-refs (7edc726dd, 6f7895a79), so
a re-run would record a compile failure in place of the assertion failure each recorded. They stay
valid at their own stacked bases. The checks they name that this slice changed have Adaptation
entries below, and `gates/test-intent/evidence/b6d536-llama-child-adapted.json` shows all 16 pass
on this branch.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerChildControlTest.java"
      ],
      "class": "New",
      "behaviour": "Spec B T21, against a stand-in llama-server (HealthyFakeLlamaServer) started by LlamaServerOps through a real DefaultChildControl with the real OS terminator and a recording ledger. aRequestedStopIsRecordedWithItsReasonAndIsNotACrash: a stop asked for with chatStop(DEACTIVATE) is one stop-intent for the child with reason DEACTIVATE and requestedBy chat-controller, recorded before the child-exit; the exit is REQUESTED(DEACTIVATE) with the terminator's own code (0x4A530001 on Windows); the crash handler is not called within 1 s. anExitNobodyAskedForIsStillACrash: a server that ends by itself is reported to the crash handler once, with no stop-intent, and its exit is UNEXPECTED. everyTransitionReasonStopsForAKnownReason: every TransitionReason maps to its stop reason (USER_SWITCH and AUTO_START to DEACTIVATE, SELF_TEST to SELF_TEST, VDU_ENTER and VDU_EXIT to themselves, CRASH_RECOVERY to itself, CONFIG_APPLY, EXTERNAL_DETACH and ADMIN_TRIGGERED to RECONFIGURE, SHUTDOWN to QUIT), and UNKNOWN is refused with IllegalArgumentException.",
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
          "delegationScope": "completing the agreed design for items 5 and 6",
          "choice": "Every stop of the main llama-server goes through ChildControl with a known reason: the lifecycle manager maps its transition reason to a stop reason and refuses UNKNOWN; its stops are asked for by chat-controller. The crash monitor stays armed through a stop: the stop's own exit is REQUESTED and ignored, an exit the server makes by itself is UNEXPECTED and handled as a crash.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-llama-child.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerChildControlTest.java"
      ]
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/SelfTestGpuBarrierTest.java"
      ],
      "class": "New",
      "behaviour": "Spec B T22. ChildSelfTestLauncher runs a self-test against a stand-in server on -ngl 1, through a real DefaultChildControl opened for an Engine data directory, with a fake adapter memory probe that shows the release only from its fourth reading. When run returns, the children ledger holds exactly one child-spawn of kind LLAMA_SELF_TEST, one stop-intent with reason SELF_TEST, one child-exit REQUESTED and one gpu-release for that child with result recovered, in that order; so the launcher returned only after the release poll finished. A main LLAMA_SERVER started on the same control afterwards is spawned after that gpu-release record.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S19",
          "quote": "The activation self-test never loads a second chat model beside the running one when that would not fit.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for items 5 and 6",
          "choice": "The self-test candidate is a LLAMA_SELF_TEST child of the Engine's ChildControl, launched by the chat controller's SelfTestLauncher seam; it is stopped with SELF_TEST, and the launcher returns only after the candidate's recorded exit, after every process the candidate started has exited, and after the GPU barrier (at most one 5 s release poll), so the main server never loads while the candidate still holds adapter memory.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-llama-child.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/SelfTestGpuBarrierTest.java"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/LlamaStopCodeSeamTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ExitCodeWatch.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Spec B B1 at the Engine's real seam (ManagedChatSeam: the composition root's lifecycle manager launching a stand-in server). A planned stop (deactivating chat) and quit each end the main llama-server with the child control's terminate code, JS_CHILD_STOP (0x4A530001), observed as the real OS exit code of the launched process (ExitCodeWatch holds a Windows process handle across the stop). Windows only: the code is the Windows terminator's.",
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
          "delegationScope": "completing the agreed design for items 5 and 6",
          "choice": "llama-server is ended only by the child control's terminate, never by the JDK's destroy or a taskkill, so on Windows every planned stop of it exits 0x4A530001, a code it never exits with by itself.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-llama-child-before.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/LlamaStopCodeSeamTest.java"
      ]
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/DefaultChildControlTest.java",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/ChildControlHarness.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Manifest adapter: a child that exits while ChildControl.start is registering its manifest entry leaves no entry behind. exitWhileRegisteringRemovesTheEntry kills a real fake child from inside the registry's register and holds register until the exit watcher has recorded the exit (the exit is seen before the control knows the entry's id); start then returns an EXITED handle whose exit is UNEXPECTED, the registry holds no live entry, and the entry was removed exactly once. The other DefaultChildControlTest cases are unchanged; ChildControlHarness's recording registry gains a hook that runs inside register (default: nothing). Found by the full app-inference suite: LlamaServerCandidateContextTest#oneShotRecoveryHealthFailureCannotRelaunchFallbackLadders failed intermittently ('dead managed-child ownership is retired') once llama-server was started through ChildControl.",
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
          "delegationScope": "completing the agreed design for items 5 and 6",
          "choice": "ChildControl owns its children's manifest entries for their whole life: an entry registered for a child that has already exited is removed by start itself before it returns (removal is idempotent), so a dead child never stays in the manifest.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-llama-child-registration.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/DefaultChildControlTest.java#exitWhileRegisteringRemovesTheEntry"
      ]
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerShutdownTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerProcessOwnershipTest.java"
      ],
      "class": "New",
      "behaviour": "The terminal stop of the owned llama-server, over a RecordingChildControl. InferenceLifecycleManagerShutdownTest: closeStopsTheServerAsAQuit, closing the manager with no Engine reason recorded stops the server with reason QUIT; terminalStopCarriesTheRecordedEngineReason, after recording ENGINE_RESTART with detail HANG and then QUIT, the terminal stop ends the owned child with the first recorded request (the same StopRequest instance). LlamaServerProcessOwnershipTest: aTerminalStopWithNothingOwnedStopsNothing, a terminal stop with nothing owned asks the control for no stop; aServerThatExitedIsNoLongerOwned, a server that exited by itself (code 1) is not stopped again by the terminal stop; aSurvivorReportedByTheControlFailsTheTerminalStopAtOnce, when the control reports the server survived, stopTerminal fails within 1 s (not after its 30 s deadline) with IllegalStateException caused by ChildSurvivedException.",
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
          "delegationScope": "completing the agreed design for items 5 and 6",
          "choice": "The llama-server's terminal stop is one ChildControl stop of exactly the servers still owned, with the Engine's first recorded reason (QUIT when none was recorded); an exited server is no longer owned and is not stopped again; a survivor reported by the control fails the terminal stop at once instead of waiting out its deadline.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-llama-child-terminal.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerShutdownTest.java#closeStopsTheServerAsAQuit",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerShutdownTest.java#terminalStopCarriesTheRecordedEngineReason",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerProcessOwnershipTest.java#aTerminalStopWithNothingOwnedStopsNothing",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerProcessOwnershipTest.java#aServerThatExitedIsNoLongerOwned",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerProcessOwnershipTest.java#aSurvivorReportedByTheControlFailsTheTerminalStopAtOnce"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppTerminalStopTest.java"
      ],
      "class": "New",
      "behaviour": "Spec B §2.8, SHUTDOWN to the Engine reason: the llama-server stop each Engine shutdown reason records, asked for by engine-shutdown with mode NOW. QUIT is QUIT; RESTART is ENGINE_RESTART; UPGRADE is UPGRADE; HANG is ENGINE_RESTART with detail HANG. Every ShutdownRequest.Reason has an expected stop.",
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
          "delegationScope": "completing the agreed design for items 5 and 6",
          "choice": "At terminal shutdown the main llama-server is stopped with the Engine's own reason: the Engine records its shutdown reason before the head is closed, and the lifecycle manager's terminal stop uses the first recorded reason (QUIT when none was recorded).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-llama-child.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppTerminalStopTest.java"
      ]
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ManagedLlamaAdoptionTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/RecordingChildControl.java"
      ],
      "class": "Adaptation",
      "moved": ["instrumentation", "signature"],
      "evidence": "gates/test-intent/evidence/b6d536-llama-child-rollback-survivor.json",
      "whatMoved": "LlamaServerOps no longer owns a registration rollback. rollbackFailedRegistration and unregisteredRollbackProcess are removed, and ChildControl.start rolls back a refused registration. When that ROLLBACK stop does not end the server, start throws ChildRegistrationRefusedException carrying the survivor, and LlamaServerOps holds it as its child. failedRegistrationRollbackRetainsHandleForRootCleanup and repeatedRollbackTerminationFailureMakesCloseFailAndRetainsCleanup keep their names and their base oracle. First: after the refused registration whose rollback kill failed, the server is still alive and held, and the next stop ends it. Second: while it keeps surviving, the stop (close) throws and the server stays alive, the next start is refused with 'survived terminal cleanup' after retrying the kill and launches nothing, the next close still throws, and once a kill takes, the close ends it. Instrumentation moved. The base called rollbackFailedRegistration with a fake RetryKillProcess and counted destroy calls 1, 2, 3. The tests now drive startLlamaServer through a real DefaultChildControl: its registry refuses the first registration, and its terminator (SurvivingTerminator) kills nothing while told to. Each kill attempt is now one recorded stop, which is ChildControl's two terminate calls, so the counts are 2, 4, 6. 'Launches nothing' is observed as one child-spawn record. The base's closeUnregisteredChild calls are stopLlamaServer, because the separate rollback handle no longer exists. aSurvivingServerIsRetriedAndBlocksEveryStartUntilItEnds stays: the same blocking for a server that survives an ordinary stop. The other tests' stopLlamaServer() and stopServerForTerminalShutdown(Duration) calls pass a StopRequest (LlamaServerTestAccess.TEST_STOP), with inputs and assertions unchanged. RecordingChildControl (a recording ChildControl fake: it records every stop request and started spec; a child exits REQUESTED with 0x4A530001, hangs, or survives with ChildSurvivedException) is the fake of aSurvivingServer..., LlamaServerProcessOwnershipTest and InferenceLifecycleManagerShutdownTest."
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerProcessOwnershipTest.java"
      ],
      "class": "Adaptation",
      "moved": ["instrumentation", "rename"],
      "whatMoved": "LlamaServerProcessOwnership owns ChildHandles started through ChildControl instead of ProcessHandles it destroys itself. terminalFenceRefusesLaunchAndOwnershipHasNoAdoptionPath keeps its oracle (a launch after the fence is refused and starts nothing; no adoptManaged). anInflightLaunchCompletingAfterTheDeadlineIsStillForcedToExit is renamed anInflightLaunchCompletingAfterTheDeadlineIsStillStoppedWithTheTerminalRequest: oracle kept (a launch that completes after the terminal stop is stopped), observed as a recorded stop with the terminal request instead of destroyForcibly on a mock handle. everySurvivorIsForcedWithinOneSharedDeadline is renamed everyOwnedServerIsStoppedTogetherWithinOneSharedDeadline: oracle kept (eight owned servers that do not exit are all stopped and stopTerminal fails within one shared deadline, not one deadline each), observed as one stopAll of all eight with the terminal request instead of destroy and destroyForcibly per mock handle. failedExitObservationStillForcesTheOwnedProcess is removed: the exit observation and the forcing it guarded are ChildControl's (DefaultChildControlTest). New in the file, with their own New entry and evidence: aServerThatExitedIsNoLongerOwned, aSurvivorReportedByTheControlFailsTheTerminalStopAtOnce and aTerminalStopWithNothingOwnedStopsNothing."
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerShutdownTest.java"
      ],
      "class": "Adaptation",
      "moved": ["instrumentation", "signature", "rename"],
      "evidence": "gates/test-intent/evidence/b6d536-llama-child-rollback-survivor.json",
      "whatMoved": "The manager is built with a ChildControl (the public constructor) instead of reflecting a mocked Process into LlamaServerOps. terminalStopTerminatesOwnedChildWhileUndrainedWorkHoldsLifecycleLock was parameterized over the two owned Process fields (process, unregisteredRollbackProcess). It is renamed terminalStopEndsTheOwnedChildWhileUndrainedWorkHoldsLifecycleLock and stays parameterized over the two kinds of owned server: launched, and unregisteredRollbackProcess. Oracle kept: with the lifecycle lock held by undrained work, the terminal stop still ends the owned server within 2 s, in both cases. For 'launched', a RecordingChildControl child is launched through the ownership. The end is observed as a recorded stop with QUIT asked for by engine-shutdown instead of destroy then destroyForcibly on a mock handle. For 'unregisteredRollbackProcess', a real stand-in process (ManagedLlamaAdoptionTest.SleepingChild) is started through the real launch path (startManagedChild) with a real DefaultChildControl, whose registry refuses the registration and whose terminator leaves the rollback survivor alive. The end is observed as that process no longer alive instead of the mock handle's destroy calls. closeAlwaysStopsServer verifies stopLlamaServer(any()) in place of the no-argument stop (oracle unchanged). terminalFenceIsAppliedToTheRealProcessBuilderPath is renamed terminalFenceIsAppliedToTheRealLaunchPath. Its oracle is kept (after the fence the real launch path throws IOException and starts nothing), observed on ChildControl's started specs instead of a ProcessBuilder. failedTerminalPreStop verifies stopServerForTerminalShutdown(StopRequest, Duration). New in the file, with their own New entry and evidence: terminalStopCarriesTheRecordedEngineReason and closeStopsTheServerAsAQuit."
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerApplyConfigTest.java"
      ],
      "class": "Adaptation",
      "moved": ["signature"],
      "whatMoved": "stopLlamaServer takes the StopRequest of its caller, so every verify of stopLlamaServer() matches any() and the FakeServer's stop answer reads its argument; inputs and the existing assertions are unchanged. The fake now also records each stop's reason, and the tests assert the reason of the stops they already verify (Spec B T21, the L1 table): a committed disable stops with DEACTIVATE; a prepared model switch with MODEL_SWITCH; an aborted same-model prepare stops A with RECONFIGURE and the aborted B with ROLLBACK; a failed cold start cleans up with ROLLBACK; an admitted recovery's one stop is CRASH_RECOVERY; both explicit indexing switches stop with DEACTIVATE; VDU enter and exit stop with VDU_ENTER then VDU_EXIT."
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerCandidateContextTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerTestAccess.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerPropsInsightsTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerStartFailureTest.java"
      ],
      "class": "Adaptation",
      "moved": ["signature", "instrumentation"],
      "whatMoved": "LlamaServerOps holds a ChildHandle started through ChildControl instead of a Process. LlamaServerTestAccess gains TEST_STOP (DEACTIVATE, NOW, asked by test), startThroughControl (starts a LLAMA_SERVER through the ops' own ChildControl) and installChild (installs that child as the active server); installActive is reflected with ChildHandle; installLogicalOwner replaces the PropsInsights test's own reflection. LlamaServerCandidateContextTest: stops pass TEST_STOP; armedLaunchedChildCleanExitSignalsCapturedOwnerFailure starts its clean-exit child through startThroughControl and installChild instead of starting it with a ProcessBuilder and reflecting the Process in, and reads the exit code 0 from the child's recorded exit instead of Process.exitValue (oracle kept: the armed owner's clean exit reaches recovery with the captured guard); its private installActive is removed for the shared one. LlamaServerStartFailureTest: its two stops pass TEST_STOP. Inputs, assertions and oracles are otherwise unchanged."
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerLogRetentionTest.java"
      ],
      "class": "Adaptation",
      "moved": ["instrumentation"],
      "whatMoved": "The launch's log is now prepared by LlamaServerOps.prepareServerLog (formerly configureServerLogRedirection(ProcessBuilder, Path)) and handed to ChildControl as Stdio.AppendToLog, so the tests call prepareServerLog and launchRedirectsToAFreshLiveLog no longer asserts a ProcessBuilder's two append redirects (display name now 'the launch's log is the live log, started empty'). Oracle kept: the returned path is the live log, it starts empty, the previous launch's output survives exactly one restart, a log still held open is handled as before, and the rotation keeps its generations (those assertions are unchanged). That the launched server's output lands in that log stays asserted end to end by LlamaServerStartFailureTest, which classifies failures from the lines the stand-in server writes there."
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppFailedDrainBackendTest.java"
      ],
      "class": "Adaptation",
      "moved": ["instrumentation"],
      "whatMoved": "The owned llama-server is a mocked ChildHandle owned through a mocked ChildControl (the manager's ChildControl constructor) instead of a mocked Process. Oracle kept: when the head's drain fails, an owned server is still stopped and an external one is not. Observed as the stop requests the control received instead of destroy calls; the owned case now also asserts that the request is HeadlessApp.terminalStopOf(reason), the stop the Engine recorded."
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerStartFailureTest.java"
      ],
      "class": "Adaptation",
      "moved": ["signature"],
      "evidence": "gates/test-intent/evidence/b6d536-llama-child-adapted.json",
      "whatMoved": "stopLlamaServer() became stopLlamaServer(StopRequest). The checks earlier evidence names (b6d536-chat-controller-step4-repaired.json, b6d536-chat-controller-step4-preserved.json): theSteppedDownRungIsWhereTheNextStartOfThatConfigurationBegins, anOverrideThatDoesNotFitFailsWithoutAStepDown, anUnknownArchitectureFailsAfterExactlyOneLaunch, anUnclassifiedExitWhileStartingFailsAtOnce, aWindowThatDoesNotFitStepsDownOneRungAndServes. Their only change is that call: in the shared teardown, and between the two starts of theSteppedDownRung..., now stopLlamaServer(LlamaServerTestAccess.TEST_STOP). Oracle kept: inputs, launches, rungs and expected failures are unchanged. All five pass on this branch (the evidence named here)."
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ManagedLlamaAdoptionTest.java"
      ],
      "class": "Adaptation",
      "moved": ["signature"],
      "evidence": "gates/test-intent/evidence/b6d536-llama-child-adapted.json",
      "whatMoved": "stopLlamaServer() became stopLlamaServer(StopRequest), and stopServerForTerminalShutdown(Duration) became stopServerForTerminalShutdown(StopRequest, Duration). The checks b6d536-no-adoption-launch.json names: healthyServerOnConfiguredPortIsNotAdoptedWithoutExternalEndpoint, explicitExternalEndpointIsUsedAndNeverStopped, twoLaunchesUseDifferentFreshPortsAndTheSameDeclaredHash, lostBindRetriesOnceOnAFreshPortAtTheSameContextRung, lostBindOnRecoveryRetriesOnceOnAFreshPortAtTheSameContextRung. Their only change is those calls, now passing LlamaServerTestAccess.TEST_STOP. Oracle kept: ports, launches, adoption and registry assertions are unchanged. All five pass on this branch."
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerApplyConfigTest.java"
      ],
      "class": "Adaptation",
      "moved": ["signature"],
      "evidence": "gates/test-intent/evidence/b6d536-llama-child-adapted.json",
      "whatMoved": "stopLlamaServer() became stopLlamaServer(StopRequest). The checks b6d536-no-adoption-rewrites-inference.json names here, managedFailureRejectsReplacedOwnerAndCannotMutateAfterClose and failedDetachCleanupRetainsCandidateOwnershipAndCannotReadoptExternal, are unchanged in their bodies; only the shared fake server's stub moved to stopLlamaServer(any()) (it also records each request's reason, which these two checks do not read). Oracle kept. Both pass on this branch."
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerShutdownTest.java"
      ],
      "class": "Adaptation",
      "moved": ["signature"],
      "evidence": "gates/test-intent/evidence/b6d536-llama-child-adapted.json",
      "whatMoved": "stopLlamaServer() became stopLlamaServer(StopRequest). closeAlwaysStopsServer (named by b6d536-no-adoption-rewrites-inference.json) verifies stopLlamaServer(any()) instead of stopLlamaServer(); nothing else in it changed. Oracle kept: close stops the server even after a request to leave it running. That the stop is a QUIT is the separate New check closeStopsTheServerAsAQuit. Passes on this branch."
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerCandidateContextTest.java"
      ],
      "class": "Adaptation",
      "moved": ["signature"],
      "evidence": "gates/test-intent/evidence/b6d536-llama-child-adapted.json",
      "whatMoved": "stopLlamaServer() became stopLlamaServer(StopRequest). The checks b6d536-no-adoption-rewrites-inference.json names, explicitCandidateOwnsGpuArgvHashAndFailureSignalAcrossGlobalChanges and strictRequestRefusesTheNamedExternalServerWhileLegacyRequestUsesIt, change only in those calls (cleanup, and the stop between the two requests), now stopLlamaServer(LlamaServerTestAccess.TEST_STOP). Oracle kept. Both pass on this branch."
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerProcessOwnershipTest.java"
      ],
      "class": "Adaptation",
      "moved": ["signature"],
      "evidence": "gates/test-intent/evidence/b6d536-llama-child-adapted.json",
      "whatMoved": "The terminal stop takes the Engine's request: new LlamaServerProcessOwnership() became new LlamaServerProcessOwnership(ChildControl) (a RecordingChildControl) and stopTerminal(Duration) became stopTerminal(StopRequest, Duration) (QUIT). terminalFenceRefusesLaunchAndOwnershipHasNoAdoptionPath (named by b6d536-no-adoption-ownership.json) keeps its oracle unchanged: after the terminal stop every launch is refused and there is no adoption path. That nothing is stopped when nothing is owned is the separate New check aTerminalStopWithNothingOwnedStopsNothing. Passes on this branch."
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppComponentRegistryCoverageTest.java"
      ],
      "class": "Adaptation",
      "moved": ["signature"],
      "whatMoved": "HeadlessApp.buildApi takes the Engine's ChildControl as a new last parameter (handed on to HeadAssembly), so the test's reflective lookup names ChildControl.class and passes null, the documented 'the inference manager keeps its own control' value (AI is disabled in this test). Inputs, assertions and oracles are otherwise unchanged."
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatHoldsTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatServerControllerImplTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/runtimestate/RuntimeReconcilerGuardrailsTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/inference/VerifiedLlamaTestServer.java"
      ],
      "class": "Adaptation",
      "moved": ["signature"],
      "whatMoved": "ChatActuator.terminalStop takes the Engine's stop reason with the timeout (terminalStop(StopReason, Duration)), and stopLlamaServer takes a StopRequest. The two ChatActuator fakes implement the new signature; ChatServerControllerImplTest's fake records the reason and its terminal assertions also check that it is QUIT. RuntimeReconcilerGuardrailsTest's ArchUnit rule names terminalStop(StopReason, Duration) as the actuation primitive. VerifiedLlamaTestServer's stopLlamaServer() becomes stopLlamaServer(any()). Inputs, assertions and oracles are otherwise unchanged."
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/DefaultChildControlTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "A chat server whose stop failed and that may still be alive (SURVIVED, or its stop still running) blocks every new chat-server start, LLAMA_SERVER or LLAMA_SELF_TEST, until it is confirmed gone. Other roles are not blocked. aChatServerRollbackSurvivorBlocksEveryChatServerStart: a LLAMA_SERVER whose registration is refused and whose ROLLBACK stop survives stays held. The next LLAMA_SERVER and LLAMA_SELF_TEST starts throw IOException and spawn nothing (one child-spawn record), while an EXTRACTION start runs. Once the kill takes, the next LLAMA_SERVER start runs, and the survivor's one exit keeps its first reason, ROLLBACK. aSelfTestThatSurvivedItsStopBlocksTheMainServer: a LLAMA_SELF_TEST whose SELF_TEST stop survived blocks the LLAMA_SERVER start (the message names its pid, nothing spawned) until a retry ends it. Its exit keeps SELF_TEST. aChatServerStartWaitsForAStopStillRunning: a LLAMA_SERVER start made while a chat server's stop is still inside terminate waits. The second server's child-spawn follows the first one's child-exit.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S19",
          "quote": "The activation self-test never loads a second chat model beside the running one when that would not fit.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for items 5 and 6",
          "label": "agent choice",
          "choice": "A chat-server child (LLAMA_SERVER or LLAMA_SELF_TEST) whose stop failed may still hold the chat port and adapter memory, so no chat-server child starts beside it. The start first retries that stop under its first intent, or awaits a stop still running, and is refused while the child is not confirmed gone. This restores the base behaviour of ManagedLlamaAdoptionTest repeatedRollbackTerminationFailureMakesCloseFailAndRetainsCleanup for every path that starts a chat server."
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-llama-child-rollback-survivor.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/DefaultChildControlTest.java#aChatServerRollbackSurvivorBlocksEveryChatServerStart",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/DefaultChildControlTest.java#aSelfTestThatSurvivedItsStopBlocksTheMainServer",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/DefaultChildControlTest.java#aChatServerStartWaitsForAStopStillRunning"
      ]
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ManagedLlamaAdoptionTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/SurvivingTerminator.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "aRegistrationRollbackSurvivorBlocksTheNextStart (the b56 verifier's scenario at the real seam): startLlamaServer through a real DefaultChildControl whose registry refuses the first registration and whose terminator leaves the rolled-back server alive. The first start throws IOException with that server still alive. The second start launches nothing beside it: one child-spawn record, whatever the second start throws. SurvivingTerminator is the OS terminator, except that while 'survive' is set a terminate call reports OK, kills nothing and its wait returns at once. It counts terminate calls and kills every process started under it at cleanup.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S19",
          "quote": "The activation self-test never loads a second chat model beside the running one when that would not fit.",
          "label": "agent-drafted, owner-adopted"
        },
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
          "delegationScope": "completing the agreed design for items 5 and 6",
          "label": "agent choice",
          "choice": "A server that survived the rollback of its refused registration is handed back to LlamaServerOps (ChildRegistrationRefusedException.survivor) and owned for the terminal stop. It blocks the next start as any survivor does."
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-llama-child-rollback-survivor.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ManagedLlamaAdoptionTest.java#aRegistrationRollbackSurvivorBlocksTheNextStart"
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
  "session": "e7b0e885-4d80-4c28-8767-7d0cdb468bb1",
  "verdict": "reject",
  "digest": "sha256:2bebd84a02262acf3721a1438f9350d28b06eab883f4b8572d63427779b56283",
  "note": "Entry 7 (ManagedLlamaAdoptionTest Adaptation) loses an oracle. Base repeatedRollbackTerminationFailureMakesCloseFailAndRetainsCleanup asserted that a llama-server surviving the rollback of a refused registration blocks the next start ('survived terminal cleanup', no launch). On 1c6f8da2c ChildControl.start throws after the ROLLBACK stop survives, LlamaServerOps never holds the handle (child null, reservation removed) and DefaultChildControl.start has no survivor check, so the next startLlamaServer launches a second server beside the survivor. Verifier scratch test VerifierRollbackSurvivorTest fails on the candidate: 'a second llama-server was launched beside the rollback survivor'. The cited DefaultChildControlTest refusedRegistrationRollsBack / survivorIsRetriedUnderTheSameIntent do not assert that later starts are refused. Entry 9 drops the matching unregisteredRollbackProcess terminal case. Other entries: oracles kept (checked diffs; the 16 checks named by the five stale evidence files change only stop signatures/helpers)."
}
```
