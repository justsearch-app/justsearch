---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: 886833dd-a08f-492f-a40a-cc0976b5ce5e
---

# Test-intent entries

Task t-20261005-b6d536, design item 5 (Spec A step 2, reconciliation F4 and F6): one chat-server
controller (`ChatServerControllerImpl`, thread `chat-controller`) runs every start, stop and relaunch
the inference lifecycle manager describes, one at a time, coalescing waiting steps and going
terminal first at quit, and no lifecycle lock is held while it waits for health or drains; the
runtime reconciler is cut down to forwarding boot, spec and procedure intent as setActivation on
the controller, with its foreign-flip convergence and anti-flap hold deleted. This changeset covers
only this task's files; the other flagged files on the branch belong to other tasks.

Phase B (b599d4d7e): `b6d536-chat-controller-repaired.json` was regenerated with before ref
f75486245, the parent of the lane commit 981aeb298 that introduced the repaired behaviour. The
current checks and their helpers need API that f75486245 lacks, so the compat before-patch
gates/test-intent/evidence/b6d536-chat-controller-repaired-compat.patch is applied there.
- Production, declarations only: ChatActuationRouter.Activation and
  RuntimeReconciler.ActivationForwarder. Also one RuntimeReconciler constructor that ignores the
  forwarder and delegates to the existing constructor, so the reconciler drives its control exactly
  as before. No production code in that state reads or calls any of them.
- Test helpers, before-API variants carried by the patch instead of overlays:
  - ManagedChatSeam without its ChildControl branch, which the named checks never use.
  - SlowStartFakeLlamaServer with ProcessHandle in place of ProcessTerminator for its fixture
    identity and cleanup.

TestActivationForwarders is overlaid. All three checks fail before by assertion: A and B launch
before C, a start holds the lifecycle lock while it waits for health, and a foreign unload is
reloaded.

Signoff repair: the separate b6d536-chat-controller-repaired-seam.json now records the same
f75486245 compatibility patch explicitly, with only TestActivationForwarders overlaid. Its two
checks pass on the candidate and fail by the declared coalescing and lifecycle-lock assertions
on that before-state. The patch supplies the before-API helpers; candidate helpers are not
overlaid on top of them.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatControllerLifecycleSeamTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SlowStartFakeLlamaServer.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ManagedChatSeam.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "relaunchRequestsThatArriveDuringARelaunchLaunchOnlyTheFinalTarget, through the manager BootstrapInferenceFactory builds and a stand-in llama-server that binds its port 1.5 s after start: with chat online, apply X (a new model) starts a relaunch; while X's server is still starting, applies A, B and C arrive one after another. X completes, then only C launches, once (launches recorded by the stand-in: model, x, c); the manager ends online on C; A and B fail with ALREADY_TRANSITIONING (superseded). On the before-state every request relaunched in turn (model, x, a, b, c). SlowStartFakeLlamaServer is the stand-in: it records each launch's -m model and pid, waits the configured delay (exiting if its launcher ends), then serves /health and a llama-shaped /props on the --port it is given. ManagedChatSeam builds the manager through BootstrapInferenceFactory against that stand-in and restores the global config and lite-mode property afterwards. Build cleanup removes redundant qualification in SlowStartFakeLlamaServer, retaining original inputs, resource lifetimes and every existing assertion.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S16",
          "quote": "Quitting at awkward moments (boot, model load, context relaunch, config apply, update prepare, restart cooldown) and Windows shutdown leave nothing running.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 2-4) end to end within the agreed scenarios.",
          "choice": "Spec A section 3.5 as the builder implements it in step 2: one controller thread runs every start, stop and relaunch; a waiting request with the same coalescing key is replaced by the later one (the later configuration is complete, not a delta; the merged apply keeps the stronger restart policy), so requests that pile up during a relaunch launch only the final target once, and each replaced caller is told it was superseded (ModeTransitionException ALREADY_TRANSITIONING).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-build-green-f75486245-236f1c807d.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatControllerLifecycleSeamTest.java#relaunchRequestsThatArriveDuringARelaunchLaunchOnlyTheFinalTarget"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatControllerLifecycleSeamTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ManagedChatSeam.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "noLifecycleLockIsHeldWhileTheControllerWaitsForHealthOrDrains (U4), through the same seam with a 4 s start delay: while switchToOnlineMode waits for the launched server's health, another thread takes the lifecycle manager's transition lock (TransitionRunner.lock, read by reflection) within 1 s and the start is still in progress; then, with an in-flight reply held on the manager's generation admission (the generative request gate here, the generation tracker since step 3), prepareResolvedConfig drains it (admission is closed, nothing relaunched yet) and the lock is again taken within 1 s; after the reply ends, the candidate launches and, while it waits for health, the lock is taken within 1 s once more. On the before-state the first probe failed: the start held the lock for its whole health wait. The drain probe passes on the before-state too (drains already ran unlocked); it pins that the controller keeps it.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 2-4) end to end within the agreed scenarios.",
          "choice": "Spec A section 3.5 item 5 as the builder implements it in step 2: TransitionRunner holds its lock only to begin and to finish a transition; the body (stop, start, health wait) runs unlocked, one lifecycle step at a time through an exclusive-step guard that waits on the monitor; drains run on the controller thread before any step, also unlocked; failure signals and close wait for the transition in progress to settle.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-repaired-seam.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatControllerLifecycleSeamTest.java#noLifecycleLockIsHeldWhileTheControllerWaitsForHealthOrDrains"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatControllerLifecycleSeamTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SlowStartFakeLlamaServer.java"
      ],
      "class": "New",
      "behaviour": "quitWhileTheServerIsStartingLeavesNothingRunningAndRefusesLaterStarts, through the same seam: while switchToOnlineMode waits for a launched stand-in that is not yet healthy, manager.close() (the quit path) makes the start fail, every launched server process ends, and no further launch happens within the stand-in's start delay afterwards. This passes on the before-state as well (the terminal fence already held there); it pins that the controller keeps it. Build cleanup removes redundant qualification in SlowStartFakeLlamaServer, retaining original inputs, resource lifetimes and every existing assertion.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S16",
          "quote": "Quitting at awkward moments (boot, model load, context relaunch, config apply, update prepare, restart cooldown) and Windows shutdown leave nothing running.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "P5",
          "quote": "Tray quit and update leave no chat server or helper behind, and the installer can replace files.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-build-green-candidate-b3b4c08bac.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatControllerLifecycleSeamTest.java#quitWhileTheServerIsStartingLeavesNothingRunningAndRefusesLaterStarts"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatServerControllerImplTest.java"
      ],
      "class": "New",
      "behaviour": "Spec A U1-U4 against the controller with a fake actuator (the class is new, so there is no before-state). U1: eight concurrent requests run one at a time, all on the chat-controller thread, each caller gets its own result, every request publishes a DesiredInputs version; a failing step's exception reaches its caller and the next step still runs. U2: A, B, C with one coalescing key arriving while A drains launch C once with A's drained admission (one drain); A and B report SupersededException; steps waiting behind a running step coalesce into the latest (X then C); the absorb function merges what the superseded step asked for. U3: quit while starting (a launched child waiting for health), while draining, while stopping, and with a step that relaunches after quit began, each ends with DesiredInputs terminal QUIT, one terminal stop through the fence, the step in progress failed, waiting steps refused, no live child, and the controller thread ended; shutdown is idempotent, keeps the first reason and refuses every later request. U4: a request from the controller thread throws IllegalStateException; a caller holding its own (settings) lock gets its step run, and observers read the controller's state while it drains; a caller's interrupt reaches the step it waits for and does not leak into the next one. setActivation: three activations published while a step runs return at once; the first two complete SupersededException immediately, only the latest runs (up, USER_SWITCH) once the running step ends; after shutdown setActivation completes RefusedException without running.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S16",
          "quote": "Quitting at awkward moments (boot, model load, context relaunch, config apply, update prepare, restart cooldown) and Windows shutdown leave nothing running.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 2-4) end to end within the agreed scenarios.",
          "choice": "Spec A section 3.5 and tests U1-U4 as the builder implements them in step 2: one non-daemon registered thread named chat-controller; an immutable DesiredInputs with version++ per request; coalescing re-evaluated after a drain so A then B then C during a drain launches C only, superseded callers told so; a request from the controller thread throws; shutdown goes terminal first, refuses waiting and later steps, stops through the fence and interrupts the step in progress; callers that block on the controller may hold the settings lock, which the controller never takes.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-unit.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatServerControllerImplTest.java#stepsRunOneAtATimeOnTheControllerThreadAndEachRequestPublishesAVersion",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatServerControllerImplTest.java#aStepFailureReachesItsCallerAndTheControllerKeepsServing",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatServerControllerImplTest.java#aToBToCDuringADrainLaunchesCOnceAndTheOthersReportSuperseded",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatServerControllerImplTest.java#stepsWaitingBehindARunningStepCoalesceIntoTheLatest",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatServerControllerImplTest.java#theAbsorbFunctionMergesWhatTheSupersededStepAskedFor",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatServerControllerImplTest.java#quitWhileStartingEndsTerminalWithNoLiveChild",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatServerControllerImplTest.java#quitWhileDrainingEndsTerminalWithoutRunningTheStep",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatServerControllerImplTest.java#quitWhileStoppingEndsTerminalWithNoLiveChild",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatServerControllerImplTest.java#aStepThatRelaunchesAfterQuitCannotLeaveAChild",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatServerControllerImplTest.java#shutdownIsIdempotentAndRefusesEveryLaterRequest",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatServerControllerImplTest.java#aRequestFromTheControllerThreadThrows",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatServerControllerImplTest.java#theControllerHoldsNoLockACallerOrObserverNeedsWhileItDrains",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatServerControllerImplTest.java#aCallerInterruptReachesTheStepItWaitsFor",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatServerControllerImplTest.java#setActivationReturnsAtOnceAndOnlyTheLatestWaitingActivationRuns",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatServerControllerImplTest.java#setActivationAfterShutdownCompletesRefusedWithoutRunning"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatActivationSeamTest.java"
      ],
      "class": "New",
      "behaviour": "bootForwardsOneActivationAndAForeignUnloadIsNeverReloaded, wired as ServicePhase wires the Engine: RuntimeReconciler forwards to InferenceLifecycleManager.setActivation (the chat controller) over the BootstrapInferenceFactory manager and the slow-start stand-in, chatEnabled true. Boot intent launches the server once. Then, four times (past the deleted anti-flap cap of 3), something other than the reconciler unloads it (switchToIndexingMode); after the reconciler is quiescent and a further two start delays, the manager is still INDEXING and no reload was launched; between cycles the same foreign actor loads it again. An explicit spec write afterwards is forwarded and launches once. The forwarder constructor is new, so there is no before-state; the before-state of the same behaviour is foreignUnloadWithChatEnabled_neverReloads.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S8",
          "quote": "When another app takes GPU memory and chat is idle, the chat server unloads with a visible reason; the next human chat shows loading and answers; MCP gets a typed refusal instead of a forced load (D8).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 2-4) end to end within the agreed scenarios.",
          "choice": "Reconciliation F6 and Spec A section 4.1/4.2 as the builder implements them in step 2: the reconciler's boot, spec and procedure requests become one setActivation(active, reason) on the controller, a coalescing step sharing the mode switches' key (inactive detaches an adopted external server, as before); setActivation returns a CompletionStage that the reconciler thread waits on; superseded or refused activations settle without retry. Step 2 keeps its own Activation record because the app-api type carries no transition reason, and completes with Void because ChatStatus publication arrives in a later step.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-unit.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatActivationSeamTest.java#bootForwardsOneActivationAndAForeignUnloadIsNeverReloaded"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/runtimestate/RuntimeReconcilerTest.java"
      ],
      "class": "Obsolete",
      "removedChecks": "foreignFlipNoProcedure_convergesBackOnline (a foreign park with spec on is converged back online by the reconciler, one re-convergence) and repeatedForeignFlips_heldWithFlapReason (more than FLAP_MAX foreign flips inside the window hold convergence and stamp the ENGINE reason convergence-held-flap-suspected). Both pinned the reconciler's foreign-flip convergence and its anti-flap guard, which this change deletes. The NOTE comment pointing at the first is reworded to point at the replacement check.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S8",
          "quote": "When another app takes GPU memory and chat is idle, the chat server unloads with a visible reason; the next human chat shows loading and answers; MCP gets a typed refusal instead of a forced load (D8).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "removal",
          "removed": [
            "modules/app-services/src/main/java/io/justsearch/app/services/runtimestate/RuntimeReconciler.java#FLAP_MAX",
            "modules/app-services/src/main/java/io/justsearch/app/services/runtimestate/RuntimeReconciler.java#FLAP_WINDOW_MS",
            "modules/app-services/src/main/java/io/justsearch/app/services/runtimestate/RuntimeReconciler.java#passesFlapGate",
            "modules/app-services/src/main/java/io/justsearch/app/services/runtimestate/RuntimeReconciler.java#driftTriggered",
            "modules/app-services/src/main/java/io/justsearch/app/services/runtimestate/RuntimeStatus.java#REASON_CONVERGENCE_HELD_FLAP"
          ]
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none (docs/decisions searched for reconciler, convergence, flap and foreign flip; only ADR-0050 decision 6, added on this branch, speaks to the reconciler, and it replaces it as the single writer)"
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/runtimestate/RuntimeReconcilerTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "foreignUnloadWithChatEnabled_neverReloads: with chatEnabled true and the engine brought up by the boot forward, the engine is parked by something other than the reconciler five times (past the old anti-flap cap of 3); each time it stays parked, the ENGINE condition still republishes the observed state (gpu-yielded-to-indexing), and across all of it the reconciler made exactly one switch (the boot online). On the before-state the first foreign park was converged back online.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S8",
          "quote": "When another app takes GPU memory and chat is idle, the chat server unloads with a visible reason; the next human chat shows loading and answers; MCP gets a typed refusal instead of a forced load (D8).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 2-4) end to end within the agreed scenarios.",
          "choice": "Reconciliation F6 as the builder implements it in step 2: the reconciler forwards intent, as setActivation on the chat controller, only on boot, spec write and procedure end; an observed mode change only republishes status, so an unload made elsewhere (a pressure unload, in the later steps) is never undone by a reload behind the controller's back. The ManualClock form of F6's test needs a clock seam the reconciler does not have; with convergence gone nothing in it depends on time, so repeated foreign parks stand in for the 10 minutes.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-repaired.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/runtimestate/RuntimeReconcilerTest.java#foreignUnloadWithChatEnabled_neverReloads"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/runtimestate/RuntimeReconcilerGuardrailsTest.java"
      ],
      "class": "Structural rule kept",
      "rule": "onlyReconcilerMayDriveLifecycleTransitions and onlyReconcilerMayCallServiceSwitchPrimitives: no app-services class other than RuntimeReconciler (and, for the OnlineAiService overloads, the AiInstallService LEGACY-FALLBACK) calls switchToOnlineMode or switchToIndexingMode.",
      "whySurvives": "Both predicates and their allowlists are unchanged; only the class javadoc and two comments are reworded (the reconciler is now the intent forwarder, the controller the single writer). The two new rules below add to the file without loosening these.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 2-4) end to end within the agreed scenarios.",
          "choice": "Reconciliation F4 as the builder implements it: the controller replaces the reconciler as the guarded single writer and RuntimeReconcilerGuardrailsTest is updated in the same change; the switch-caller rules stay, keeping the reconciler the only forwarder of spec and procedure intent.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/runtimestate/RuntimeReconcilerGuardrailsTest.java"
      ],
      "class": "New",
      "behaviour": "onlyChatControllerMayCallActuationPrimitives: no app-services class other than ChatServerControllerImpl calls ChatActuator.drain, execute or terminalStop. onlyCompositionRootInstallsTheChatController: no app-services class other than BootstrapInferenceFactory calls InferenceLifecycleManager.installActuationRouter or chatActuator. The classes are new, so there is no before-state.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 2-4) end to end within the agreed scenarios.",
          "choice": "Reconciliation F4 as the builder implements it: the controller in app-services is the guarded single writer and the lifecycle manager's actuation primitives are called only by it; the guard is two ArchUnit rules in RuntimeReconcilerGuardrailsTest and a ChatServerController row in governance/runtime-state.v1.json.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-guardrails.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/runtimestate/RuntimeReconcilerGuardrailsTest.java#onlyChatControllerMayCallActuationPrimitives",
        "modules/app-services/src/test/java/io/justsearch/app/services/runtimestate/RuntimeReconcilerGuardrailsTest.java#onlyCompositionRootInstallsTheChatController"
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
  "session": "VS3-20261015",
  "verdict": "reject",
  "digest": "sha256:ce7f74cab73f2c6d523f3c6c082f8a9d1afbbff8b306a982859e26df8ffe09ed",
  "note": "Before rerun mismatch for entries 1 and 2: the exact declared f75486245 test/helper overlay, with no patch, fails compileTestJava because ChildControl and ProcessTerminator are absent. The separate repaired-seam evidence records assertion failures; the older combined evidence's compatibility patch does not repair this unpatched record."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS3b-20261015",
  "verdict": "accept",
  "digest": "sha256:7605fd552e776453d91613f68e7c47266ea22d796aa67f523c73c9099725ea61",
  "note": "Re-reviewed the corrected entries since 9f1f6257b: both candidate checks pass and the exact f75486245 compatibility-patch replay reproduces the declared coalescing and lifecycle-lock assertion failures. The patch does not decide those outcomes; other entries, listed items and evidence remain truthful and unchanged; bound to main."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS4b-20261015",
  "verdict": "accept",
  "digest": "sha256:86948d51f415be085aeaa91a749299c0b613d705fa8792aa4e6c647c57e2ef92",
  "note": "All 19 current selected candidate cases pass; the shared controller addition is separately covered by fix-replan. Regenerated evidence retains the exact repaired seam source, compatibility patch and before outcomes independently reproduced in VS3b on f75486245; those bytes and the archived assertion results were checked."
}
```
