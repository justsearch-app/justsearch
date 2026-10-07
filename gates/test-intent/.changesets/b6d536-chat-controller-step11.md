---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: 886833dd-a08f-492f-a40a-cc0976b5ce5e
---

# Test-intent entries

Task t-20261005-b6d536, design item 5, Spec A step 11 (option B) on top of 64e04b891: a
llama-server stop's reason comes from the chat controller's step. ChatActuation#stopReason names
each step kind's reason; the manager's actuator records the running step, and the stops that used
the interim TransitionReason mapping (the switch to indexing, prepare, apply and detach) take the
running step's reason, MODEL_SWITCH when a reconfiguring step changes the model file. The mapping
(InferenceLifecycleManager.stopReasonFor) is deleted; a stop outside a step is refused, with no
fallback. Spec B step 5 (llama-server through ChildControl, intent before terminate, REQUESTED vs
UNEXPECTED) was already on integrate through the llama-child slice (reconciliation F5).

Changed intent: LlamaServerChildControlTest#everyTransitionReasonStopsForAKnownReason becomes
everyControllerStepStopsForAKnownSpecificReason (reason: reasons now originate at the controller
step). Departure from Spec A row 11's "LSO keeps argv, log rotation and health probing only",
accepted by the orchestrator under the owner's delegation: LlamaServerOps keeps its start/stop
lifecycle through ChildControl. Follow-up: move the LSO lifecycle into the controller.

Fail-before for the check on API new in this change (manual run, not a run-evidence file). The run
used a temporary `git worktree add` of 64e04b891 outside the repo, removed after the run; the
script, the stub patch and the JUnit result are kept in the builder sandbox (stubs11/run.sh,
stub-logs11). Before-state: 64e04b891 plus a layer stub of ChatActuation#stopReason that carries
no step reason (returns null), with the candidate's LlamaServerChildControlTest. Result (stub-logs11): everyControllerStepStopsForAKnownSpecificReason fails by AssertionFailedError, "ACTIVATE ==> expected: <RECONFIGURE> but was: <null>"; the file's two unchanged checks (aRequestedStopIsRecordedWithItsReasonAndIsNotACrash, anExitNobodyAskedForIsStillACrash) pass.

Preservation: ChatControllerChildApiTest (new, on existing API) and the pre-existing checks that pin step stop reasons
(InferenceLifecycleManagerApplyConfigTest: MODEL_SWITCH, DEACTIVATE, RECONFIGURE then ROLLBACK,
VDU_ENTER then VDU_EXIT, CRASH_RECOVERY; ChatResidencyUnloadStepTest: RESIDENCY_PRESSURE with its
detail) pass on 64e04b891 and after, recorded in the preserved evidence (all 50 checks pass
before and after; the gate's "does not show a repair" note is expected for preservation checks).

```json
{
  "entries": [
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerChildControlTest.java"
      ],
      "class": "Obsolete",
      "removedChecks": "Changed intent of LlamaServerChildControlTest#everyTransitionReasonStopsForAKnownReason: it pinned the interim TransitionReason-to-StopReason mapping (InferenceLifecycleManager.stopReasonFor, Spec B §2.8), which this change deletes because stop reasons now originate at the controller's step. Its successor at the new seam is everyControllerStepStopsForAKnownSpecificReason (the New entry below).",
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
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A step 11) end to end within the agreed scenarios.",
          "choice": "Spec A step 11, option B as the orchestrator accepted it under this delegation: a llama-server stop's reason originates at the chat controller's step, not at a mapping from the transition reason. Each step kind names one known, specific StopReason (ACTIVATE, APPLY, PREPARE and DETACH RECONFIGURE; DEACTIVATE and PREPARED_RETIRE DEACTIVATE; PREPARED_ABORT ROLLBACK; VDU_ENTER and VDU_EXIT themselves; RECOVER CRASH_RECOVERY; SELF_TEST, SELF_TEST_RUN and SELF_TEST_END SELF_TEST; RESIDENCY_UNLOAD RESIDENCY_PRESSURE; DEVICE_LOST DEVICE_LOST; RESUME_PROBE TIMEOUT), stopped NOW and requested by chat-controller; a reconfiguring step whose model file changes stops with MODEL_SWITCH; sub-events a step handles itself keep their own reason (ROLLBACK, TIMEOUT, CRASH_RECOVERY, DEVICE_LOST). There is no fallback: a stop outside a controller step is refused. This departs from Spec A row 11's 'LSO keeps the launch argv, log rotation and health probing only': LlamaServerOps keeps its start/stop lifecycle through ChildControl; moving it into the controller is a named follow-up.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none (docs/decisions, 52 files, searched for StopReason, stop reason, TransitionReason, requestedBy, StopMode, stop intent, MODEL_SWITCH, RECONFIGURE and DEACTIVATE: no match; Spec B §2.8's interim mapping is the guarded behaviour this change retires)"
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerChildControlTest.java"
      ],
      "class": "New",
      "behaviour": "Changed intent, the successor of everyTransitionReasonStopsForAKnownReason at the new seam (reason: reasons now originate at the controller step). everyControllerStepStopsForAKnownSpecificReason: every ChatActuation kind stops for its expected StopReason (the table in the source), none for a generic or Engine-level reason (QUIT, UPGRADE, ENGINE_RESTART, IDLE_UNLOAD, RECYCLE, CANCELLED) and none without one; the step's stop is NOW and requested by chat-controller; every kind has an expectation. The test's other two checks (aRequestedStopIsRecordedWithItsReasonAndIsNotACrash, anExitNobodyAskedForIsStillACrash) are unchanged; they pass on the candidate (new evidence) and on the head in the layer-stub run below. The stub run records the fail-before by assertion for the new check, on API new in this change (ChatActuation#stopReason).",
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
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A step 11) end to end within the agreed scenarios.",
          "choice": "Spec A step 11, option B as the orchestrator accepted it under this delegation: a llama-server stop's reason originates at the chat controller's step, not at a mapping from the transition reason. Each step kind names one known, specific StopReason (ACTIVATE, APPLY, PREPARE and DETACH RECONFIGURE; DEACTIVATE and PREPARED_RETIRE DEACTIVATE; PREPARED_ABORT ROLLBACK; VDU_ENTER and VDU_EXIT themselves; RECOVER CRASH_RECOVERY; SELF_TEST, SELF_TEST_RUN and SELF_TEST_END SELF_TEST; RESIDENCY_UNLOAD RESIDENCY_PRESSURE; DEVICE_LOST DEVICE_LOST; RESUME_PROBE TIMEOUT), stopped NOW and requested by chat-controller; a reconfiguring step whose model file changes stops with MODEL_SWITCH; sub-events a step handles itself keep their own reason (ROLLBACK, TIMEOUT, CRASH_RECOVERY, DEVICE_LOST). There is no fallback: a stop outside a controller step is refused. This departs from Spec A row 11's 'LSO keeps the launch argv, log rotation and health probing only': LlamaServerOps keeps its start/stop lifecycle through ChildControl; moving it into the controller is a named follow-up.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-step11-new.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerChildControlTest.java#everyControllerStepStopsForAKnownSpecificReason"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatControllerChildApiTest.java"
      ],
      "class": "New",
      "behaviour": "Spec A I8 at the controller, against an in-memory Spec B child API (§9.1 F2) under the Engine's composition (BootstrapInferenceFactory: lifecycle manager and ChatServerControllerImpl). everyStopAStepMakesCarriesTheStepsReasonAfterItsIntentAndEndsRequested: online, an apply of the same configuration with RESTART_ALWAYS, an apply with another model file, VDU enter, VDU exit and a switch to indexing give exactly start, stop RECONFIGURE NOW, start, stop MODEL_SWITCH NOW, start, stop VDU_ENTER NOW, start, stop VDU_EXIT NOW, start, stop DEACTIVATE NOW; every stop is requested by chat-controller, its intent comes before its child ends, the child ends REQUESTED with that reason, and no child ends without an intent. anExitNobodyAskedForIsUnexpectedAndHasNoIntent: a child that exits on its own right after READY ends UNEXPECTED with no reason, chat leaves ONLINE (T29) and no stop is asked of it. Conformance and preservation: these properties already held at the integrate head through the transition mapping, so both checks pass before and after (preserved evidence); the step-origin of the reasons is pinned by the New check above.",
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
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A step 11) end to end within the agreed scenarios.",
          "choice": "Spec A step 11, option B as the orchestrator accepted it under this delegation: a llama-server stop's reason originates at the chat controller's step, not at a mapping from the transition reason. Each step kind names one known, specific StopReason (ACTIVATE, APPLY, PREPARE and DETACH RECONFIGURE; DEACTIVATE and PREPARED_RETIRE DEACTIVATE; PREPARED_ABORT ROLLBACK; VDU_ENTER and VDU_EXIT themselves; RECOVER CRASH_RECOVERY; SELF_TEST, SELF_TEST_RUN and SELF_TEST_END SELF_TEST; RESIDENCY_UNLOAD RESIDENCY_PRESSURE; DEVICE_LOST DEVICE_LOST; RESUME_PROBE TIMEOUT), stopped NOW and requested by chat-controller; a reconfiguring step whose model file changes stops with MODEL_SWITCH; sub-events a step handles itself keep their own reason (ROLLBACK, TIMEOUT, CRASH_RECOVERY, DEVICE_LOST). There is no fallback: a stop outside a controller step is refused. This departs from Spec A row 11's 'LSO keeps the launch argv, log rotation and health probing only': LlamaServerOps keeps its start/stop lifecycle through ChildControl; moving it into the controller is a named follow-up.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-step11-preserved.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatControllerChildApiTest.java#everyStopAStepMakesCarriesTheStepsReasonAfterItsIntentAndEndsRequested",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatControllerChildApiTest.java#anExitNobodyAskedForIsUnexpectedAndHasNoIntent"
      ]
    }
  ]
}
```
