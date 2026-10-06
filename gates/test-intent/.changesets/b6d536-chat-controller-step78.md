---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: 886833dd-a08f-492f-a40a-cc0976b5ce5e
---

# Test-intent entries

Task t-20261005-b6d536, design item 5, Spec A steps 7 and 8 on top of 19f7c8a81. Step 7 publishes
why chat failed: the failure the controller classified (step 4) reaches the generative component,
the operating state and the UI as its own reason code instead of only `inference.crashed` or
`inference.activation_failed`, and a server serving after a context step-down carries the
`inference.context_reduced` notice. The crash path now latches the classified failure before its
OFFLINE landing is published (it latched after, so the landing's listeners never saw it). Step 7
also adds the controller's `ChatStatus` snapshot and its pure Component and Mode projections. Step 8
adds the boot-order gate (Spec A §6.2): the first GPU chat start waits until the KnowledgeServer
publishes the encoders component READY, or 45 s. This changeset covers only these files.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ChatCrashLatchOrderTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/phases/InferenceCapabilityWiringTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingStateChatReasonTest.java",
        "modules/ui-web/src/shell-v0/state/readinessNotice.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Why chat failed is shown as itself. The new checks are in new files or in files no earlier evidence covers, so no other changeset's evidence goes stale. aCrashIsLatchedBeforeItsOfflineLandingIsPublished: a real manager online on the fake llama-server; the server crashes moments after ready; a mode-transition listener reads the manager's latched failure at the CRASH_RECOVERY landing on OFFLINE and sees CRASH_LOOP. aCrashLatchedAsOutOfMemoryIsPublishedAsOutOfMemory: the generative component of a crash landing whose latched failure is RESOURCE_EXHAUSTED is FAILED with inference.out_of_memory. aFailedActivationNamesItsClassifiedFailure: a first start that lands OFFLINE with MODEL_LOAD_FAILED latched is FAILED with inference.model_load_failed. aSteppedDownServerIsReadyWithTheContextReducedNotice: a server online on a window whose reason is stepped-from:8192 is READY with inference.context_reduced; one at its planned window (top-rung) is READY with no reason. aFailedChatNamesItsClassifiedFailure: with the engine down and the generative component FAILED, the operating state's chat is FAILED and its reason is RESOURCE_EXHAUSTED, STARTUP_TIMEOUT, START_FAILED or MODEL_LOAD_FAILED for the four new codes, and none for inference.crashed, which several failures share. aStartWaitingWhileTheEngineIsDownIsLoading: engine down, component STARTING, chat LOADING. The two readinessNotice checks: each of the four failure codes has its own wording, warn severity, the Open Brain remedy and the ai-unavailable consequence; context_reduced is an info notice outside ai-unavailable that raises no banner. On 19f7c8a81: the listener saw null ('expected: <[CRASH_LOOP]> but was: <[null]>'), the component read inference.crashed, inference.activation_failed and null, the operating state's failed chat had no reason ('expected: <RESOURCE_EXHAUSTED> but was: <null>') and the waiting start read OFF, and the UI worded every new code as 'Degraded: <code>'.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S12",
          "quote": "A chat server crash from GPU pressure is reported as such and does not loop on the same configuration.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S20",
          "quote": "The GPU, chat, indexing and lifecycle states the UI shows are the ones dev tools and evaluation scripts read.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 7-8) end to end within the agreed scenarios.",
          "choice": "Spec A step 7 as the builder implements it: the classified failures RESOURCE_EXHAUSTED, STARTUP_TIMEOUT, START_FAILED and MODEL_LOAD_FAILED get the reason codes inference.out_of_memory, inference.startup_timeout, inference.start_failed and inference.model_load_failed (FAULT), with their own UI wording; the other failures keep inference.crashed (MISSING_DLL keeps inference.runtime_not_installed); a stepped-down context window is the TRANSIENT notice inference.context_reduced on a READY component; a component that is starting while the engine is down reads as chat LOADING.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-step78-repaired.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ChatCrashLatchOrderTest.java#aCrashIsLatchedBeforeItsOfflineLandingIsPublished",
        "modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/phases/InferenceCapabilityWiringTest.java#aCrashLatchedAsOutOfMemoryIsPublishedAsOutOfMemory",
        "modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/phases/InferenceCapabilityWiringTest.java#aFailedActivationNamesItsClassifiedFailure",
        "modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/phases/InferenceCapabilityWiringTest.java#aSteppedDownServerIsReadyWithTheContextReducedNotice",
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingStateChatReasonTest.java#aFailedChatNamesItsClassifiedFailure",
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingStateChatReasonTest.java#aStartWaitingWhileTheEngineIsDownIsLoading",
        "modules/ui-web/src/shell-v0/state/readinessNotice.test.ts#Spec A step 7: each classified chat failure words its own cause and is a warn",
        "modules/ui-web/src/shell-v0/state/readinessNotice.test.ts#Spec A step 7: context_reduced is a calm notice that stays out of the banner"
      ]
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/chat/ChatReasonCodesTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatStatusProjectionTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/phases/InferenceCapabilityWiringTest.java"
      ],
      "class": "New",
      "behaviour": "Spec A §9.2 U15, on new API (no before-state). ChatReasonCodesTest: every ChatState with every reason its slot allows maps to one lifecycle reason code, and only READY to none; OFF is inference.deactivated, LOADING inference.starting, REFUSED EXECUTABLE_MISSING inference.runtime_not_installed; the nine FailedCodes map as the step-7 choice says, each FAULT; a code names its failure back only when exactly one failure produces it (not inference.crashed); only a stepped-from: window reason yields the TRANSIENT inference.context_reduced. ChatStatusProjectionTest: for every status and every prior component state, LOADING is RELOADING after READY or RELOADING and STARTING otherwise, READY is READY with no reason, FAILED is FAILED, OFF, UNLOADED and REFUSED are UNAVAILABLE, and every non-READY state carries a reason code; the legacy Mode is ONLINE for READY, TRANSITIONING for LOADING and OFFLINE otherwise, never INDEXING. aLatchedFailureDoesNotOverrideTurningChatOff (preservation): with a failure latched, turning chat off still publishes the generative component ABSENT with inference.deactivated.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S20",
          "quote": "The GPU, chat, indexing and lifecycle states the UI shows are the ones dev tools and evaluation scripts read.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 7-8) end to end within the agreed scenarios.",
          "choice": "Spec A §3.1 and reconciliation F4 as the builder implements them: one total mapping from ChatStatus to a lifecycle reason code, to a generative component observation and to the legacy Mode, with a chat that does not run never projected to INDEXING; a latched failure never replaces the user's choice to turn chat off.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-step78-new.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/chat/ChatReasonCodesTest.java#everyStateAndReasonMapsToOneLifecycleCode",
        "modules/app-api/src/test/java/io/justsearch/app/api/chat/ChatReasonCodesTest.java#statesMapToTheirLifecycleCodes",
        "modules/app-api/src/test/java/io/justsearch/app/api/chat/ChatReasonCodesTest.java#classifiedFailuresHaveTheirOwnCodes",
        "modules/app-api/src/test/java/io/justsearch/app/api/chat/ChatReasonCodesTest.java#aCodeNamesItsFailureOnlyWhenUnshared",
        "modules/app-api/src/test/java/io/justsearch/app/api/chat/ChatReasonCodesTest.java#onlyASteppedDownWindowCarriesTheContextReducedNotice",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatStatusProjectionTest.java#everyStatusProjectsToOneComponentAndOneMode",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatStatusProjectionTest.java#aFailedChatCarriesItsClassifiedCode",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatStatusProjectionTest.java#readyCarriesTheContextReducedNoticeOnlyAfterAStepDown",
        "modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/phases/InferenceCapabilityWiringTest.java#aLatchedFailureDoesNotOverrideTurningChatOff"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatBootOrderTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/QueryEncoderReadinessTest.java"
      ],
      "class": "New",
      "behaviour": "Spec A §9.2 U15 (the controller's status) and U16 (the boot-order gate), on new API (no before-state), with a stand-in actuator. With the gate armed and a GPU chat: the first activation is not acted on and status is LOADING with cause BOOT_ORDER; reportQueryEncodersReady runs the forwarder's replay once, which sends the intent again, and chat starts once; a second report does nothing. With a 300 ms gate the start goes ahead after the bound, not before. A CPU chat, and an unarmed controller, start at once. Encoders ready before the gate is armed leave it open. Turning chat off during the wait ends it (status OFF) and the gate's opening then sends nothing and starts nothing. Through QueryEncoderReadiness on a counting registry: the encoders' READY publication opens the gate and chat starts; five off/on toggles afterwards read and write nothing on the registry and the encoders stay READY. Status: OFF; TRANSITIONING is LOADING COLD_START (Mode TRANSITIONING); ONLINE with two in flight is READY, generating, managed; a self-test session is UNLOADED SELF_TEST; OFFLINE with RESOURCE_EXHAUSTED latched is FAILED and projects to FAILED inference.out_of_memory; INDEXING projects to Mode OFFLINE; an unchanged server returns the same snapshot, activity moves version but not since, a new state moves both, an external server is not managed. QueryEncoderReadinessTest: the hook fires once when the encoders component turns READY later (not on STARTING, not on another component, not again after a reload), at once when it is already READY, and never after it is closed.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S9",
          "quote": "A machine reaches the same GPU profile whatever order chat and encoders started in; toggling chat repeatedly never recreates encoder sessions.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S21",
          "quote": "Search models are warm before the app reports ready; the first search after activation does not fall back.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S20",
          "quote": "The GPU, chat, indexing and lifecycle states the UI shows are the ones dev tools and evaluation scripts read.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 7-8) end to end within the agreed scenarios.",
          "choice": "Spec A §6.2 T2/T3 as the builder implements them: the boot-order gate is armed at boot; a GPU chat activation that meets it is not acted on (BOOT_WAIT, shown as LOADING BOOT_ORDER); the gate opens once, on the encoders component's READY publication or after 45 s, and then asks the forwarder to send its current intent again rather than replaying a stored step; CPU chat and an unarmed controller never wait; the hook only reads the component registry.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-step78-new.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatBootOrderTest.java#aGpuStartWaitsForTheEncodersAndStartsWhenTheyAreReady",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatBootOrderTest.java#theGateExpiresAfterItsBoundAndTheStartGoesAhead",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatBootOrderTest.java#aCpuStartAndAnUnarmedControllerNeverWait",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatBootOrderTest.java#encodersReadyBeforeTheGateIsArmedLeaveItOpen",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatBootOrderTest.java#chatSwitchedOffDuringTheWaitIsNotStartedWhenTheGateOpens",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatBootOrderTest.java#activationTogglesNeverTouchTheEncoders",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatBootOrderTest.java#statusFollowsTheObservedServerAndTheControllersOwnState",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatBootOrderTest.java#versionMovesOnEveryChangeAndSinceOnlyWhenTheStateChanges",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/QueryEncoderReadinessTest.java#firesOnceWhenTheEncodersBecomeReadyLater",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/QueryEncoderReadinessTest.java#firesAtOnceWhenTheEncodersAreAlreadyReady",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/QueryEncoderReadinessTest.java#aClosedHookNeverFires"
      ]
    }
  ]
}
```

## Acceptance records
