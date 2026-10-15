---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: 886833dd-a08f-492f-a40a-cc0976b5ce5e
---

# Test-intent entries

Task t-20261005-b6d536, design item 5, Spec A step 9 on top of 43564e20f, under reconciliation v2
(Switch A lease rules). Residency: the chat controller takes a GPU pressure report
(reportPressure, the method and app-api PressureSignal type Spec E's sampler feeds), unloads an
idle GPU chat server when another GPU user needs the memory, and keeps it UNLOADED with a visible
reason until a demand policy lets a load through: a person's chat (allowLoad with webview or paired
device evidence) loads at once when the pressure is gone; under pressure it is refused with
forceAllowed and may be loaded anyway through POST /api/chat/load-anyway; agents wait at most 30 s
and never force; assist and auxiliary demands never load. A reload waits out the 120 s dwell after a
pressure unload; after a load anyway only a new pressure edge unloads again. The refusals reach
HTTP and MCP with their reason, retry time and forceAllowed. A dev-only trigger
(POST /api/debug/chat-pressure, eval mode only) reports pressure, optionally with a profile name.
Reconciliation v2 cut the token bucket and the force window; neither is built. This changeset
covers only these files.

Follow-up round (coordinator, before commit):

- The game rule waits for the machine profile. Spec A O4 says "Game alone on a 16 GB+ fitting
  profile: recommend no unload (P3)", C8 says Spec A unloads on GAME "only when the profile lacks a
  fit margin or memory pressure is present", and Spec E maps an unresolved profile to today's
  behaviour. Until E7 supplies the profile, the controller's profile is UNRESOLVED and a game alone
  keeps chat loaded. A game unloads chat only on P12 or P8. EXTERNAL_MEMORY always unloads, and VDU
  and bulk work still yield to any reported pressure, a game included.
- The controller loop survives a step that fails unexpectedly. The defect is on 43564e20f too: a
  drain that threw anything but IllegalStateException ended the loop, so the step's caller was
  never answered and every later step was refused. Now the step fails alone (logged, its future
  completed with the failure) and the loop keeps serving; only a VirtualMachineError still ends it.
- Load anyway in the app (design-v2 line 124: "'Load anyway' exists only in the UI (D8)";
  reconciliation v1: UNLOADED "tells the UI whether 'Load anyway' applies"). While the AI
  readiness carries inference.unloaded_gpu_pressure or inference.unloaded_game_mode, the chat
  window keeps Send live (a person's send loads chat again), shows the reason and offers Load
  anyway (POST /api/chat/load-anyway, allowLoad true). A send refused under pressure is taken back
  (bubble removed, text back in the composer) and worded from the typed refusal; Load anyway then
  sends it again. The shared stream-error words use the refusal's own message, so the search
  window's ask says why instead of showing the raw 503.

Fail-before for checks on API new in this change (manual run, not a run-evidence file). The run
used a temporary `git worktree add` of 43564e20f outside the repo, removed after the run. Stubs and
logs are kept in the builder sandbox (stub-logs/step1-*, step2-*). Two before-states:

1. All-stub: 43564e20f plus signature-only stubs of every new API. The controller ignores
   pressure, never unloads, completes every demand and load anyway at once, and has the
   six-argument constructor. ChatResidency is the interface only. The gate admits everything, the
   routes do nothing and the setters are no-ops. New enum constants and interface defaults are
   copied in, because they are types. ChatResidencyTest: 22 of 26 fail, every one by
   AssertionFailedError.
   - CH7 fails at its own assertion: "Expected java.util.concurrent.ExecutionException to be
     thrown, but nothing was thrown".
   - U19's contrast half fails: p3OnSixteenGigabytesAGameAloneDoesNotUnloadButAnotherAppDoes,
     "EXTERNAL_MEMORY always unloads ==> expected: <[unload:gpu_busy]> but was: <[]>".
   - u19NoPressureNeverUnloadsAnIdleServer passes, as a negative property must on a stub.
2. Layer stubs: the candidate's app-api, app-inference and app-services, with only the
   controller's demand() and loadAnyway() reduced to stubs that complete at once. The UI API is
   stubbed as in run 1. This isolates the demand policy, the dwell, load anyway and the HTTP
   refusals from the unload. ChatResidencyTest: 10 fail; ChatResidencyRoutesTest: 9 of 9 fail.
   Every failure is an AssertionFailedError:
   - U17: u17UnderPressureEveryDemandKindIsRefusedOrWaitsAndNoneLoads. A HUMAN_CHAT demand under
     pressure was not refused; the failure is at refused().
   - U18 and the dwell: u18NoDemandReloadsWithinTwoMinutesOfAPressureUnload and
     u17WithinTheDwellAnAgentUnderPressureIsToldToWaitForTheDwell. A demand inside the dwell was
     not refused.
   - Load anyway (controller): t26APersonLoadsAnywayAndOnlyANewPressureEdgeUnloadsAgain, "load
     anyway skips the pressure and the dwell ==> expected: <[up:RESIDENCY_RELOAD]> but was:
     <[]>". f8OnlyAPersonMayLoadAnyway: "Expected ChatRefusal to be thrown, but nothing was
     thrown".
   - I7: "expected: <CHAT_UNAVAILABLE> but was: <SERVICE_UNAVAILABLE>". The refused connection
     to the unloaded server answered instead.
   - Load anyway (route): t26LoadAnywayFromTheAppLoadsUnderPressure, "the model loads in spite of
     the pressure ==> expected: <true> but was: <false>". f8LoadAnywayWithoutThePersonsEvidence
     IsRefused: "expected: <403> but was: <200>".
   - CH7: ch7AGpuStartUnderPressureIsRefusedIntoUnloadedWithoutADwell, "expected:
     <[unload:gpu_busy, up:RESIDENCY_RELOAD]> but was: <[unload:gpu_busy]>".

The MCP refusal mapping fails before in the ui-repaired evidence, because it compiles on
43564e20f. Checks that only test plumbing of a new type stay New without fail-before, and their
entry says so. They are ChatResidencyUnloadStepTest, InferenceResidencyStatusTest,
ChatRefusalWireTest, the dev-trigger route checks and chatResidency.test.ts.

Evidence regenerated on b599d4d7e. The fail-before of
b6d536-chat-controller-step9-loop-repaired.json runs on 43564e20f, the parent of dee93cc5a, which
added the check. It applies
`gates/test-intent/evidence/b6d536-chat-controller-step9-loop-repaired-compat.patch`, because the
check now uses the later stop and self-test launcher API. The patch adds a public
`ChatServerControllerImpl(executors, actuator, selfTestLauncher)` constructor that delegates to the
existing one with the default drain bound. It gives `ChatActuator` a default
`terminalStop(StopReason, Duration)` that calls `terminalStop(Duration)`. It also turns the abstract
`terminalStop(Duration)` into a default that calls the reasoned form with QUIT, so that an actuator
implementing only the later method compiles. Every production actuator at 43564e20f implements
`terminalStop(Duration)` itself, so none of them changes. The check fails there by assertion: the
caller gets TimeoutException instead of "UnsupportedOperationException: drain broke".

```json
{
  "entries": [
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingStateChatUnloadedTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpChatRefusalTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "The unloaded chat and its refusal are visible as themselves. aChatUnloadedForGpuPressureIsUnloadedWithTheGpuBusyDetail: with the generative component UNAVAILABLE on inference.unloaded_gpu_pressure (engine Down), the operating block's chat is UNLOADED, reason RESIDENCY_PRESSURE, detail GPU_BUSY and reason code inference.unloaded_gpu_pressure. aChatUnloadedForAGameIsUnloadedWithTheGameModeDetail: inference.unloaded_game_mode gives UNLOADED, RESIDENCY_PRESSURE, GAME_MODE. theMcpProjectionCarriesTheRefusalReasonRetryTimeAndForceAllowed: a ChatRefusal(GPU_BUSY, 30 s, forceAllowed false) that a tool's join() wraps in a CompletionException reaches the MCP client through the tool-failure path as CHAT_UNAVAILABLE, TRANSIENT, retryable, retryAfterMs 30000, reason GPU_BUSY, forceAllowed false, with 'Retry after: 30000 ms.' and 'Reason: GPU_BUSY.' in the text. On 43564e20f (the checks compile there): both unloads read OFF ('expected: <UNLOADED> but was: <OFF>'), and the refusal reached MCP as a generic failure ('expected: <CHAT_UNAVAILABLE> but was: <INTERNAL_ERROR>').",
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
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A step 9) end to end within the agreed scenarios.",
          "choice": "Spec A step 9 as the builder implements it: a residency unload is published on the generative component as UNAVAILABLE with inference.unloaded_gpu_pressure or inference.unloaded_game_mode (TRANSIENT, info, outside the ai-unavailable set), which the operating block reads as chat UNLOADED with reason RESIDENCY_PRESSURE and the StopDetail; the indexing yield keeps inference.gpu_yielded_to_indexing and stays OFF. A chat refusal on MCP carries the same errorCode, retryAfterMs, reason and forceAllowed as the HTTP body.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-step9-ui-repaired.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingStateChatUnloadedTest.java#aChatUnloadedForGpuPressureIsUnloadedWithTheGpuBusyDetail",
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingStateChatUnloadedTest.java#aChatUnloadedForAGameIsUnloadedWithTheGameModeDetail",
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpChatRefusalTest.java#theMcpProjectionCarriesTheRefusalReasonRetryTimeAndForceAllowed"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/readinessNoticeResidency.test.ts",
        "modules/ui-web/src/shell-v0/views/unifiedChatHumanLoad.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "The app words a residency unload calmly and marks a person's chat. The two readinessNoticeResidency checks: inference.unloaded_gpu_pressure is worded 'Chat was paused to free graphics memory. It reloads when you ask.' and inference.unloaded_game_mode 'Chat is paused while a game is running.', both at info severity and outside the ai-unavailable set. withHumanLoad marks a body allowLoad true and keeps prompt and sessionId; the chat window calls withHumanLoad(body) once, before its POST to /api/chat/dispatch. On 43564e20f: both codes were worded 'Degraded: <code>' ('expected 'Degraded: inference.unloaded_game_mode' to be 'Chat is paused while a game is runnin…''), withHumanLoad did not exist ('expected 'undefined' to be 'function'') and the chat window never marked its dispatch ('expected -1 to be greater than -1').",
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
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A step 9) end to end within the agreed scenarios.",
          "choice": "Reconciliation v2 F8/CH1 as the builder implements it in the app: only the chat window's own send to /api/chat/dispatch carries allowLoad true; the shared body builder never adds it, and the search window's ask is left unmarked in this step. The two residency codes get their own calm wording at info severity.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-step9-fe.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/readinessNoticeResidency.test.ts#words a GPU-pressure unload as paused and reloading on request, at info",
        "modules/ui-web/src/shell-v0/state/readinessNoticeResidency.test.ts#words a game-mode unload as paused while a game runs, at info",
        "modules/ui-web/src/shell-v0/views/unifiedChatHumanLoad.test.ts#withHumanLoad marks the body as a person's chat and keeps the rest",
        "modules/ui-web/src/shell-v0/views/unifiedChatHumanLoad.test.ts#the chat window marks its /api/chat/dispatch body before it sends it"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatControllerStepFailureTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "The chat controller's single thread survives a step that fails unexpectedly. aStepWhoseDrainThrowsFailsAloneAndTheControllerKeepsServing: a draining step whose policy drain throws UnsupportedOperationException fails alone (its caller gets 'UnsupportedOperationException: drain broke' within 5 s); the next activation still runs and starts the server, and a later draining step answers 'applied'. On 43564e20f (the check compiles there) the loop ended on the throw, so the caller was never answered ('the step that raised the failure gets it ==> expected: <UnsupportedOperationException: drain broke> but was: <TimeoutException: null>'). Fail-before: 43564e20f with b6d536-chat-controller-step9-loop-repaired-compat.patch, by assertion.",
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
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A step 9) end to end within the agreed scenarios.",
          "choice": "An unexpected RuntimeException or Error from a step (its drain included) or from the boot-gate replay is logged and given to that step's caller; the controller loop keeps serving. Only a VirtualMachineError still ends the loop.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-step9-loop-repaired.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatControllerStepFailureTest.java#aStepWhoseDrainThrowsFailsAloneAndTheControllerKeepsServing"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/unifiedChatResidency.test.ts",
        "modules/ui-web/src/api/streams.chatRefusal.test.ts",
        "modules/ui-web/src/shell-v0/utils/streamError.chatRefusal.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Load anyway in the app (D8: UI only). 'keeps Send live, says why chat is paused and offers Load anyway': with chat not answering and the AI state naming inference.unloaded_gpu_pressure, Send is enabled with no blocking reason, the chat window shows 'Chat was paused to free graphics memory' and a Load anyway button. 'a send refused under pressure is taken back and worded; Load anyway loads and sends it again': a dispatch that fails with the 503 CHAT_UNAVAILABLE refusal (GPU_BUSY, forceAllowed true) removes the user bubble, puts the text back in the composer, sets no raw error, and shows an alert with the Engine's words and 'Your message was not sent'; activating Load anyway POSTs {allowLoad: true} once to /api/chat/load-anyway and then sends the same turn again with allowLoad true. 'a refusal Load anyway does not apply to says when to try again and offers nothing': a RELOAD_BACKOFF refusal with forceAllowed false and 90 s shows 'Try again in about 90 s.' and no button. 'a 503 CHAT_UNAVAILABLE carries the refusal: reason, words and Load anyway': consumeShapeStream's error carries errorCode, reason, message and forceAllowed from the refusal body. The two friendlyStreamError checks word a refusal by its message, adding 'Try again in about 45 s.' when retryAfterMs is known. On 43564e20f: Send was disabled ('expected true to be false'), the refused send kept its bubble and showed the raw HTTP error ('expected true to be false'), no notice rendered ('expected '' to contain 'Try again in about 90 s.''), the stream error had no refusal ('expected undefined to deeply equal …') and friendlyStreamError returned the raw 'consumeShapeStream: HTTP 503 …' text.",
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
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A step 9) end to end within the agreed scenarios.",
          "choice": "The chat window reads 'unloaded for another GPU user' from the AI readiness reason codes (the lifecycle_inference dimension of the aiFeatures composite), keeps the chat tiers sendable then (the agent tier, which sends no allowLoad, is not), shows a calm notice with Load anyway, and handles a typed refusal like the locked refusal: bubble taken back, text restored; Load anyway resends a refused turn. The shared stream-error words use the refusal's message, so the search window's ask, which sends no allowLoad (CH1), says why it was refused.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-step9-fe-loadanyway.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/unifiedChatResidency.test.ts#keeps Send live, says why chat is paused and offers Load anyway",
        "modules/ui-web/src/shell-v0/views/unifiedChatResidency.test.ts#a send refused under pressure is taken back and worded; Load anyway loads and sends it again",
        "modules/ui-web/src/shell-v0/views/unifiedChatResidency.test.ts#a refusal Load anyway does not apply to says when to try again and offers nothing",
        "modules/ui-web/src/api/streams.chatRefusal.test.ts#a 503 CHAT_UNAVAILABLE carries the refusal: reason, words and Load anyway",
        "modules/ui-web/src/shell-v0/utils/streamError.chatRefusal.test.ts#words a pressure refusal in the Engine words",
        "modules/ui-web/src/shell-v0/utils/streamError.chatRefusal.test.ts#adds when to try again when the Engine knows"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/chatResidency.test.ts"
      ],
      "class": "New",
      "behaviour": "Plumbing of the new chatResidency module and the new AiState field (the module is new in this change, so these checks stay New without fail-before). The AI state's chatUnloadedReason is inference.unloaded_gpu_pressure or inference.unloaded_game_mode when the aiFeatures readiness carries it, and null for the indexing yield; a chat the user switched off is not unloaded; parseChatRefusal reads errorCode, reason, message, forceAllowed and retryAfterMs and ignores other bodies; requestLoadAnyway POSTs {allowLoad: true} to /api/chat/load-anyway and reads loading, or the refusal of a 403.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A step 9) end to end within the agreed scenarios.",
          "choice": "One pure module holds the residency codes, the refusal parser and the load-anyway request; the AI state store derives chatUnloadedReason from it.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-step9-new.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/chatResidency.test.ts#the AI state names a GPU-pressure or game unload, and nothing else",
        "modules/ui-web/src/shell-v0/state/chatResidency.test.ts#a chat the user switched off is off, not unloaded",
        "modules/ui-web/src/shell-v0/state/chatResidency.test.ts#reads the refusal body and ignores other bodies",
        "modules/ui-web/src/shell-v0/state/chatResidency.test.ts#Load anyway posts allowLoad to the route and reads loading or the refusal"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingStateChatUnloadedTest.java",
        "modules/ui-web/src/shell-v0/views/unifiedChatHumanLoad.test.ts",
        "modules/ui-web/src/shell-v0/views/unifiedChatResidency.test.ts",
        "modules/ui-web/src/api/streams.chatRefusal.test.ts",
        "modules/ui-web/src/shell-v0/utils/streamError.chatRefusal.test.ts"
      ],
      "class": "New",
      "behaviour": "Preservation, passing on 43564e20f and after (recorded in the preserved evidence's fail-before run). theIndexingYieldStaysOff: a generative component UNAVAILABLE on inference.gpu_yielded_to_indexing is still chat OFF with no reason and no detail. 'the shared body builder itself never sends allowLoad': buildRequestBody for core.free-chat, core.rag-ask, core.extract and core.agent-run has no allowLoad key, so agents and the search window's ask stay unmarked. 'negative control: with chat loaded there is no residency notice': a chat window with chat answering shows no residency notice. 'an error body without a refusal stays a plain HTTP error': a 500 with an INTERNAL_ERROR body carries no chatRefusal. 'leaves any other error as it was': friendlyStreamError of a plain Error is its message.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A step 9) end to end within the agreed scenarios.",
          "choice": "The residency unload has its own codes, so the indexing yield's projection and the shared request body are left as they were.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-step9-preserved.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingStateChatUnloadedTest.java#theIndexingYieldStaysOff",
        "modules/ui-web/src/shell-v0/views/unifiedChatHumanLoad.test.ts#the shared body builder itself never sends allowLoad",
        "modules/ui-web/src/shell-v0/views/unifiedChatResidency.test.ts#negative control: with chat loaded there is no residency notice",
        "modules/ui-web/src/api/streams.chatRefusal.test.ts#an error body without a refusal stays a plain HTTP error",
        "modules/ui-web/src/shell-v0/utils/streamError.chatRefusal.test.ts#leaves any other error as it was"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResidencyTest.java"
      ],
      "class": "New",
      "behaviour": "Spec A §9.2 U17-U19 and the residency rows of step 9, on new API (the ChatResidency interface, reportPressure and demand are new in this change; the manual stub runs described above record which checks fail before by assertion), with a stand-in actuator, a hand clock and a fake generation tracker. U19: with pressure NONE an idle GPU server is never unloaded. T21: pressure from another GPU user unloads an idle GPU server (RESIDENCY_UNLOAD, IF_IDLE) and chat reads UNLOADED with GPU_BUSY; a CPU server is never unloaded. P3: on P16_PLUS a GAME report alone does not unload and EXTERNAL_MEMORY does; on P12 a game unloads with GAME_MODE; until the profile is known (UNRESOLVED) a game alone leaves chat loaded and a person's chat goes straight through, while no VDU pass may start and EXTERNAL_MEMORY still unloads. T22: a reply in progress defers the unload to the next idle edge, and a lease opened before the drain does the same; pressure that clears before the stop leaves chat loaded. F6: ten minutes of forwarded chatEnabled activations do not reload an unloaded chat. U18: no demand reloads within 120 s of a pressure unload; an agent demand reloads once pressure is gone and the dwell is over. U17: under pressure a person's chat (webview or paired device) is refused GPU_BUSY with forceAllowed true and a 30 s retry; a HUMAN_CHAT demand from an MCP client, the CLI, an internal caller or the supervisor, and an agent's chat, are refused GPU_BUSY without forceAllowed and told when to ask again; assist and aux are refused and never load (also with pressure gone); a background demand waits and nothing loads; within the dwell an agent under a game on P12 is told to retry after the dwell. A waiting background demand ends with the reload a person starts; switching chat off ends it as OFF (and chat reads OFF, not unloaded); a demand while chat runs goes straight through. T26: a person's loadAnyway loads under pressure and only a new pressure edge unloads again; F8: loadAnyway without a person's evidence is refused REFUSED. CH7: a GPU start under pressure is refused into UNLOADED without a dwell. T40: an open VDU hold is asked to finish under pressure and the idle server then unloads; U13: no VDU pass starts under pressure or while chat is unloaded for another GPU user; a running hold starts an unloaded server and clears the latch. After shutdown a demand is refused ENGINE_STOPPING, a waiting background demand ends ENGINE_STOPPING, and the idle-edge subscription is closed.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S8",
          "quote": "When another app takes GPU memory and chat is idle, the chat server unloads with a visible reason; the next human chat shows loading and answers; MCP gets a typed refusal instead of a forced load (D8).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "P3",
          "quote": "On 16 GB and up, the chat model unloads only when another app needs the memory, and indexing slows only while chat is answering.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A step 9) end to end within the agreed scenarios.",
          "choice": "Spec A step 9 under reconciliation v2 as the builder implements it: the unload is an IF_IDLE drain on the controller thread that a reply or an open lease defers to the next idle edge (or the next pressure report); a pressure that clears before the stop cancels it; the 120 s reload dwell is kept in memory only; the token bucket and the force window are not built, and after a load anyway only a new pressure edge unloads again; a GPU start refused under pressure (CH7) latches UNLOADED without a dwell; an agent under pressure is refused with retryAfter the longer of the dwell left and 30 s; the machine profile has no producer yet (Spec E E7), so it stays UNRESOLVED, which keeps today's behaviour for a game alone (chat stays loaded, Spec A O4/C8, Spec E's UNRESOLVED row); a game unloads chat only on P12 or P8, while VDU and bulk work yield to any reported pressure.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-step9-new.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResidencyTest.java#u19NoPressureNeverUnloadsAnIdleServer",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResidencyTest.java#t21AnIdleGpuServerUnloadsUnderPressureWithAVisibleReason",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResidencyTest.java#aCpuServerIsNeverUnloadedForGpuPressure",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResidencyTest.java#p3OnSixteenGigabytesAGameAloneDoesNotUnloadButAnotherAppDoes",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResidencyTest.java#untilTheProfileIsKnownAGameAloneLeavesChatLoadedButAnotherAppUnloadsIt",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResidencyTest.java#aGameUnloadsChatBelowSixteenGigabytesWithTheGameModeDetail",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResidencyTest.java#t22AReplyInProgressDefersTheUnloadToTheNextIdleEdge",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResidencyTest.java#aLeaseThatOpensBeforeTheUnloadDrainsMakesItWaitForTheNextIdleEdge",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResidencyTest.java#pressureThatClearsBeforeTheStopLeavesChatLoaded",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResidencyTest.java#f6AnUnloadedChatStaysUnloadedOverTenMinutesOfForwardedActivations",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResidencyTest.java#u18NoDemandReloadsWithinTwoMinutesOfAPressureUnload",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResidencyTest.java#anAgentDemandReloadsOnceThePressureIsGoneAndTheDwellIsOver",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResidencyTest.java#u17UnderPressureEveryDemandKindIsRefusedOrWaitsAndNoneLoads",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResidencyTest.java#u17WithinTheDwellAnAgentUnderPressureIsToldToWaitForTheDwell",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResidencyTest.java#u17AssistAndAuxNeverLoadEvenWithThePressureGone",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResidencyTest.java#aWaitingBackgroundDemandEndsWithTheReloadAPersonStarts",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResidencyTest.java#switchingChatOffEndsWaitingBackgroundDemandsAsOff",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResidencyTest.java#aDemandWhileChatRunsGoesStraightThrough",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResidencyTest.java#t26APersonLoadsAnywayAndOnlyANewPressureEdgeUnloadsAgain",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResidencyTest.java#f8OnlyAPersonMayLoadAnyway",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResidencyTest.java#ch7AGpuStartUnderPressureIsRefusedIntoUnloadedWithoutADwell",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResidencyTest.java#t40AnOpenVduHoldIsAskedToFinishUnderPressureAndTheIdleServerThenUnloads",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResidencyTest.java#u13NoVduPassStartsUnderPressure",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResidencyTest.java#u13NoVduPassStartsWhileChatIsUnloadedForAnotherGpuUser",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResidencyTest.java#aRunningHoldStartsAnUnloadedServerAndClearsTheLatch",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResidencyTest.java#afterShutdownADemandIsRefusedAsStopping"
      ]
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ChatResidencyUnloadStepTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/phases/InferenceResidencyStatusTest.java"
      ],
      "class": "New",
      "behaviour": "The unload step and its publication, on new API (the residency unload step and residencyUnloadDetail are new in this change; these checks test the plumbing of the new step and its status projection, so they stay New without fail-before). ChatResidencyUnloadStepTest, a real manager on the fake llama-server: an idle server stops and the manager reports the GPU_BUSY unload detail until its next start; with a GENERATE lease open the unload is refused BUSY and the server, mode and detail are unchanged; pressure that cleared before the stop leaves the server running; a start refused under pressure reports the unload detail with no server launched. InferenceResidencyStatusTest: a pressure unload publishes the generative component UNAVAILABLE with inference.unloaded_gpu_pressure, a game-mode unload with inference.unloaded_game_mode, the indexing yield keeps inference.gpu_yielded_to_indexing, and the reload after an unload is READY again.",
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
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 9) end to end within the agreed scenarios.",
          "choice": "The residency unload is a manager step that stops only an idle server, re-checks the pressure just before the stop, and records the unload detail until the next start; the status wiring publishes it as the TRANSIENT residency reason codes on the generative component.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-step9-new.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ChatResidencyUnloadStepTest.java#anIdleServerStopsAndChatReadsUnloadedUntilItsNextStart",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ChatResidencyUnloadStepTest.java#aReplyInProgressRefusesTheUnloadAndNothingChanges",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ChatResidencyUnloadStepTest.java#pressureThatClearedBeforeTheStopLeavesTheServerRunning",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ChatResidencyUnloadStepTest.java#aStartRefusedUnderPressureReadsUnloadedWithoutAServer",
        "modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/phases/InferenceResidencyStatusTest.java#aPressureUnloadPublishesTheGpuPressureReason",
        "modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/phases/InferenceResidencyStatusTest.java#aGameModeUnloadPublishesTheGameModeReason",
        "modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/phases/InferenceResidencyStatusTest.java#theIndexingYieldKeepsItsOwnReason",
        "modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/phases/InferenceResidencyStatusTest.java#theReloadAfterAnUnloadIsReadyAgain"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatResidencyRoutesTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatRefusalWireTest.java"
      ],
      "class": "New",
      "behaviour": "Spec A I7 and the step-9 routes, on new API (the residency gate, the load-anyway route and the dev trigger are new in this change; I7, the person/agent gate and the load-anyway checks fail before by assertion in the layer-stub run described above, while the dev-trigger checks and ChatRefusalWireTest test plumbing of new types and stay New without fail-before), with the real controller on a stand-in actuator. I7: /v1/chat/completions while chat is unloaded under pressure is 503 CHAT_UNAVAILABLE TRANSIENT, reason GPU_BUSY, forceAllowed false, retryAfterMs over 30 s and at most 120 s, with Retry-After, conforming to api-error-response.v1; no request reaches the server (which, unloaded, would refuse the connection) and nothing loads. A header-less POST /api/chat/runs without allowLoad is an agent's and is refused GPU_BUSY without creating a run. A person's chat (allowLoad, webview) under a game is refused GAME_MODE with forceAllowed true; an MCP-client caller claiming allowLoad gets forceAllowed false. With the pressure gone a person's chat reloads an unloaded model (RESIDENCY_RELOAD) and goes on once it is ready. POST /api/chat/load-anyway from the app loads under pressure; without allowLoad, or from an MCP client, it is 403 AI_OFFLINE reason REFUSED and nothing loads. The dev pressure trigger is 404 outside eval mode and leaves the pressure NONE; in eval mode EXTERNAL_MEMORY is reported and chat unloads, and an unknown signal is 400; GAME without a profile leaves chat loaded, and GAME with profile p12 sets P12 and unloads with GAME_MODE. ChatRefusalWireTest: every RefusalReason maps to its wire code (CHAT_UNAVAILABLE for GPU_BUSY, GAME_MODE, RELOAD_BACKOFF, SELF_TEST, BUSY, DEVICE_RECOVERING; AI_STARTING for LOADING; AI_OFFLINE for OFF, FAILED, REFUSED; SERVICE_UNAVAILABLE for ENGINE_STOPPING) with reason and forceAllowed, a permanent code carries no retry time, and every body conforms to api-error-response.v1.",
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
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A step 9) end to end within the agreed scenarios.",
          "choice": "Reconciliation v2 F8/CH1 at the routes as the builder implements it: a demand is a person's only with body allowLoad true and client kind WEBVIEW or PAIRED_DEVICE, and load anyway needs the same evidence; an agent's wait is capped at 30 s and lowered by waitForReadyMs; api-error-response.v1 gains the optional reason and forceAllowed fields; the dev trigger answers only in eval mode.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-step9-new.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatResidencyRoutesTest.java#i7ChatCompletionsWhileUnloadedUnderPressureIs503ChatUnavailableWithReasonGpuBusy",
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatResidencyRoutesTest.java#aHeaderLessRunWithoutAllowLoadIsAnAgentsAndIsRefusedWithoutARun",
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatResidencyRoutesTest.java#aPersonsChatUnderPressureMayBeLoadedAnywayButAnAgentClaimingAllowLoadMayNot",
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatResidencyRoutesTest.java#aPersonsChatReloadsAnUnloadedModelAndGoesOnOnceItIsReady",
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatResidencyRoutesTest.java#t26LoadAnywayFromTheAppLoadsUnderPressure",
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatResidencyRoutesTest.java#f8LoadAnywayWithoutThePersonsEvidenceIsRefused",
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatResidencyRoutesTest.java#theDevPressureTriggerIsAbsentOutsideEvalMode",
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatResidencyRoutesTest.java#theDevPressureTriggerReportsPressureAndChatUnloads",
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatResidencyRoutesTest.java#theDevTriggerTriesTheGameRuleOnANamedProfile",
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatRefusalWireTest.java#everyRefusalReasonMapsToItsWireCodeAndTheBodyConformsToTheSchema"
      ]
    }
  ]
}
```

## Acceptance records
