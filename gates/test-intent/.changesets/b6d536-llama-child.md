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

This slice edits check files named by evidence of earlier changesets. Four evidence files whose
checks carry no fail-before were re-run unchanged in their checks:
`b6d536-chat-controller-guardrails.json`, `b6d536-chat-controller-unit.json`,
`b6d536-chat-controller-step56-unit.json` and `b6d536-child-control.json`. Five evidence files with a fail-before
(`b6d536-chat-controller-step4-repaired.json`, `b6d536-chat-controller-step4-preserved.json`,
`b6d536-no-adoption-launch.json`, `b6d536-no-adoption-rewrites-inference.json`,
`b6d536-no-adoption-ownership.json`) were not re-run: their adapted check files call
`stopLlamaServer(StopRequest)`, which does not exist at their before-refs (7edc726dd, 6f7895a79), so
a re-run would record a compile failure in place of the assertion failure each recorded.

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
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
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
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
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
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
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
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
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
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
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
      "whatMoved": "LlamaServerOps no longer owns a registration rollback: rollbackFailedRegistration and unregisteredRollbackProcess are removed, ChildControl.start rolls back a refused registration (DefaultChildControlTest refusedRegistrationRollsBack covers that rollback, and survivorIsRetriedUnderTheSameIntent the retry of a survivor). The two tests that drove the removed seam with a fake RetryKillProcess, failedRegistrationRollbackRetainsHandleForRootCleanup and repeatedRollbackTerminationFailureMakesCloseFailAndRetainsCleanup, are replaced by aSurvivingServerIsRetriedAndBlocksEveryStartUntilItEnds. Oracle kept: a managed server that survives its stop keeps its handle and ownership, the stop throws, every later start is refused with 'survived terminal cleanup' without launching, a start first retries the survivor's stop, and once that stop takes the next start launches (2 stops and no start, then 3 stops and 1 start). The survivor is now a ChildHandle in state SURVIVED from RecordingChildControl (a recording ChildControl fake: it records every stop request and started spec; a child exits REQUESTED with 0x4A530001, hangs, or survives with ChildSurvivedException) instead of a Process whose destroy fails. The other tests' stopLlamaServer() and stopServerForTerminalShutdown(Duration) calls pass a StopRequest (LlamaServerTestAccess.TEST_STOP), with inputs and assertions unchanged. RecordingChildControl is also the fake of LlamaServerProcessOwnershipTest and InferenceLifecycleManagerShutdownTest."
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerProcessOwnershipTest.java"
      ],
      "class": "Adaptation",
      "moved": ["instrumentation", "rename"],
      "whatMoved": "LlamaServerProcessOwnership owns ChildHandles started through ChildControl instead of ProcessHandles it destroys itself. terminalFenceRefusesLaunchAndOwnershipHasNoAdoptionPath keeps its oracle (a launch after the fence is refused and starts nothing; no adoptManaged). anInflightLaunchCompletingAfterTheDeadlineIsStillForcedToExit is renamed anInflightLaunchCompletingAfterTheDeadlineIsStillStoppedWithTheTerminalRequest: oracle kept (a launch that completes after the terminal stop is stopped), observed as a recorded stop with the terminal request instead of destroyForcibly on a mock handle. everySurvivorIsForcedWithinOneSharedDeadline is renamed everyOwnedServerIsStoppedTogetherWithinOneSharedDeadline: oracle kept (eight owned servers that do not exit are all stopped and stopTerminal fails within one shared deadline, not one deadline each), observed as one stopAll of all eight with the terminal request instead of destroy and destroyForcibly per mock handle. failedExitObservationStillForcesTheOwnedProcess is replaced by aServerThatExitedIsNoLongerOwned: the exit observation and the forcing it guarded are ChildControl's; what the ownership still decides is that an exited server is not stopped again. New in the file: aSurvivorReportedByTheControlFailsTheTerminalStopAtOnce, a survivor's ChildSurvivedException fails stopTerminal at once with that cause."
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerShutdownTest.java"
      ],
      "class": "Adaptation",
      "moved": ["instrumentation", "signature", "rename"],
      "whatMoved": "The manager is built with a RecordingChildControl (the public ChildControl constructor) instead of reflecting a mocked Process into LlamaServerOps. terminalStopTerminatesOwnedChildWhileUndrainedWorkHoldsLifecycleLock was parameterized over the two Process fields (process, unregisteredRollbackProcess); the second field is removed with the rollback seam, so it is one test, renamed terminalStopEndsTheOwnedChildWhileUndrainedWorkHoldsLifecycleLock. Oracle kept: with the lifecycle lock held by undrained work, the terminal stop still ends the owned server within 2 s; observed as a recorded stop with QUIT asked for by engine-shutdown instead of destroy then destroyForcibly on a mock handle. closeAlwaysStopsServer verifies a stop with reason QUIT in place of the no-argument stop. terminalFenceIsAppliedToTheRealProcessBuilderPath is renamed terminalFenceIsAppliedToTheRealLaunchPath: oracle kept (after the fence the real launch path throws IOException and starts nothing), observed on ChildControl's started specs instead of a ProcessBuilder. failedTerminalPreStop verifies stopServerForTerminalShutdown(StopRequest, Duration). New in the file: terminalStopCarriesTheRecordedEngineReason, the terminal stop uses the first recorded Engine reason (ENGINE_RESTART with detail HANG)."
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
    }
  ]
}
```
