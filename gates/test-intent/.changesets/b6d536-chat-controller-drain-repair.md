---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: 886833dd-a08f-492f-a40a-cc0976b5ce5e
---

# Test-intent entries

Task t-20261005-b6d536, design item 5, repair of Spec A steps 2-3. While the chat server drains,
starts or stops, its generative gate refuses admission with the new typed
`GenerationUnavailableException` (an IllegalStateException, so `isAvailable()` and existing callers
are unchanged). Every optional generative step treats that refusal, thrown at the call or carried
by the returned future, exactly as AI being off: search query expansion records AI_UNAVAILABLE,
query understanding, filter normalization and context sufficiency fall back to their AI-off
results, GPL waits on the AI-off backoff and retries the document, and VDU defers the document as
AI offline. Settings and chat-mode refusals caused by a transition (ALREADY_TRANSITIONING,
INTERRUPTED, an observation race, an occupied settings transaction) are retryable and project as
503 or 409 TRANSIENT, never 500 PERMANENT. The `/v1` proxy's connection-refused message no longer
names the stale port. This changeset covers only this slice's files.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/KnowledgeSearchDuringChatDrainTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "aSearchDuringADeactivateDrainAnswersWithoutExpansionLikeAiOff, on the real seam: the lifecycle manager BootstrapInferenceFactory builds runs StreamingFakeLlamaServer as a separate process and goes ONLINE; a GENERATE lease (as the /v1 proxy holds for a streaming reply) is open while switchToIndexingMode runs in the background, and the test waits until an AUX admission is refused while isOnline() is still true (the drain window). A text search with expansion enabled (and query understanding on) then runs through the real POST /api/knowledge/search handler (KnowledgeSearchController) with the production OnlineAiServiceImpl and EngineAdmissionController; only the index client is stubbed. The handler sets no error status, the expansion stage is SKIPPED with reason AI_UNAVAILABLE (the AI-off reason), only the base worker search ran, and once the reply closes the deactivate completes and chat is offline. On d3fe74ef2 the search answered 500 INVALID_STATE PERMANENT 'Generative component is unavailable'.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S1",
          "quote": "During the scripted agent on a quiet 12 GB machine, no search returns a 504 or times out; every search answers within its deadline.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 2-4) end to end within the agreed scenarios.",
          "choice": "Item 5 repair of Spec A steps 2-3: a search that meets the generative gate closed by a drain, start or stop answers without the optional AI steps and records the same skip reason as when AI is off (expansion AI_UNAVAILABLE), whether the refusal is thrown at the call or carried by the future; the drain itself and isAvailable() are unchanged.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-drain-repair-repaired.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/KnowledgeSearchDuringChatDrainTest.java#aSearchDuringADeactivateDrainAnswersWithoutExpansionLikeAiOff"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/OptionalAiStepsDuringChatDrainTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "The optional search-time and answer-time AI steps against the production GenerationTracker closed by closeNow() (as a drain leaves it), reached through an OnlineAiService that reports available (the mode stays ONLINE while draining). KnowledgeSearchEngine with PipelineConfig.TEXT: aSearchAnswersWithoutExpansionWhenTheDrainingGateRefusesAtTheCall (the refusal thrown synchronously from chatCompletion, as OnlineModeOps.requireOnline does) answers, expansion SKIPPED AI_UNAVAILABLE, one worker search; aSearchRecordsTheAiOffReasonWhenTheRefusalArrivesThroughTheFuture (the refusal as the failed future) gives the same. queryUnderstandingSkipsLikeAiOffWhenTheDrainingGateRefusesAtTheCall: extract returns a future of null (no boost filters). filterNormalizationFallsBackLikeAiOffWhenTheDrainingGateRefusesAtTheCall: a meta_source value with no deterministic match normalizes to the same filters and source as with AI off. contextSufficiencyAnswersUnknownLikeAiOffWhenTheDrainingGateRefusesAtTheCall: classify returns a future of null. On d3fe74ef2 the synchronous refusal escaped every one of these ('Generative component is unavailable') and the future refusal was recorded as FAILED.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S1",
          "quote": "During the scripted agent on a quiet 12 GB machine, no search returns a 504 or times out; every search answers within its deadline.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 2-4) end to end within the agreed scenarios.",
          "choice": "Item 5 repair of Spec A steps 2-3: every caller of an optional generative step (query expansion, query understanding, filter normalization, context sufficiency) treats a refusal because chat is draining, starting, stopping or unavailable exactly as AI being off, recording the same skip reason or returning the same AI-off result, and a synchronous throw is handled like an asynchronous failure.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-drain-repair-repaired.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/OptionalAiStepsDuringChatDrainTest.java#aSearchAnswersWithoutExpansionWhenTheDrainingGateRefusesAtTheCall",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/OptionalAiStepsDuringChatDrainTest.java#aSearchRecordsTheAiOffReasonWhenTheRefusalArrivesThroughTheFuture",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/OptionalAiStepsDuringChatDrainTest.java#queryUnderstandingSkipsLikeAiOffWhenTheDrainingGateRefusesAtTheCall",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/OptionalAiStepsDuringChatDrainTest.java#filterNormalizationFallsBackLikeAiOffWhenTheDrainingGateRefusesAtTheCall",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/OptionalAiStepsDuringChatDrainTest.java#contextSufficiencyAnswersUnknownLikeAiOffWhenTheDrainingGateRefusesAtTheCall"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/OptionalAiStepsDuringChatDrainTest.java"
      ],
      "class": "New",
      "behaviour": "Preservation, passing on d3fe74ef2 and after (recorded in the preserved evidence's fail-before run). aSearchWithAiOnStillExpands: with the gate open and the completion admitted, the expansion stage is EXECUTED and a second, expanded worker search runs with the query 'how do solar panels turn sunlight into power photovoltaic^0.3'. aSearchWithAiOffSkipsExpansionAsToday: with isAvailable() false, expansion is SKIPPED AI_UNAVAILABLE, one worker search runs and chatCompletion is never called.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "P6",
          "quote": "Idle benchmark (E1) quality unchanged; lexical-only requests stay off the model latch and the budget.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 2-4) end to end within the agreed scenarios.",
          "choice": "Item 5 repair of Spec A steps 2-3 changes only the refused path: with AI on a search still expands, and with AI off it skips expansion as before without calling the model.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-drain-repair-preserved.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/OptionalAiStepsDuringChatDrainTest.java#aSearchWithAiOnStillExpands",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/OptionalAiStepsDuringChatDrainTest.java#aSearchWithAiOffSkipsExpansionAsToday"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpl/GplDeferralDuringChatDrainTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/VduDeferralDuringChatDrainTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Background generative jobs defer instead of aborting or counting a failure. GplJobCoordinator's real job runner over one document, with the production GenerationTracker closed: aRefusalAtTheCallDefersTheDocumentAndTheJobCompletesAfterTheDrain (the first chatCompletion throws the gate's refusal and the drain then ends) and aRefusalThroughTheFutureDefersTheDocumentInsteadOfDroppingIt (the refusal as the failed future) both retry the document after the AI-off backoff, complete with status COMPLETED and write its 2 triples, with exactly 2 generation calls. VduBatchProcessor, aDrainingGateDefersTheDocumentInsteadOfFailingIt: process() fails with a VduException wrapping the gate's refusal; the pass counts 0 failed and 0 processed, leaves the document remaining, reports blockedReason AI_OFFLINE and writes no VDU result. On d3fe74ef2 the synchronous refusal aborted the GPL job (FAILED, 'GPL job aborted at offset 0'), the future refusal dropped the document (0 triples), and VDU counted the document failed.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 2-4) end to end within the agreed scenarios.",
          "choice": "Item 5 repair of Spec A steps 2-3: GPL and other background generative jobs treat a refusal because chat is draining, starting, stopping or unavailable as AI being off; they defer and retry later (GPL on the AI-off backoff and budget, ending the job as the AI-off timeout does when the budget runs out; VDU leaving the document pending and blocking the pass as AI offline) instead of aborting or counting a failure.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-drain-repair-repaired.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpl/GplDeferralDuringChatDrainTest.java#aRefusalAtTheCallDefersTheDocumentAndTheJobCompletesAfterTheDrain",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpl/GplDeferralDuringChatDrainTest.java#aRefusalThroughTheFutureDefersTheDocumentInsteadOfDroppingIt",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/VduDeferralDuringChatDrainTest.java#aDrainingGateDefersTheDocumentInsteadOfFailingIt"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/TransitionRefusalProjectionTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Transition refusals reach the client typed transient, through the real HTTP error mapping. The refusal comes from the production GenerativeSettingsComponentOwner whose lifecycle manager refuses prepareResolvedConfig with ModeTransitionException ALREADY_TRANSITIONING ('Chat is busy: ...'): aBusyChatServerRefusalIsTransientThroughTheSettingsRoute (SettingsController POST /api/settings/v2) and aBusyChatServerRefusalIsTransientThroughTheChatModeRoute (InferenceHandlers POST /api/inference/mode) answer 503 with errorClass TRANSIENT and retryable true, errorCode GENERATIVE_PREPARATION_REFUSED; anInterruptedTransitionRefusalIsTransient gives the same for INTERRUPTED. anOccupiedSettingsTransactionIsATransientConflict: a second reserve on the production SettingsCommitCoordinator while the first holds it projects as 409 RECONFIGURE_IN_PROGRESS TRANSIENT with retryable true. On d3fe74ef2 the busy and interrupted refusals were 500 PERMANENT retryable false and the occupied conflict carried retryable false.",
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
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 2-4) end to end within the agreed scenarios.",
          "choice": "Item 5 repair of Spec A steps 2-3: a refusal caused by a chat-server transition (busy draining, another transition, interrupted, a superseded observation, an occupied settings transaction) is typed transient: retryable true, status 503 or 409 per the existing error contract (RECONFIGURE_IN_PROGRESS stays 409, an otherwise unlisted retryable refusal is 503 SERVICE_UNAVAILABLE), never 500 or PERMANENT.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-drain-repair-repaired.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/TransitionRefusalProjectionTest.java#aBusyChatServerRefusalIsTransientThroughTheSettingsRoute",
        "modules/ui/src/test/java/io/justsearch/ui/api/TransitionRefusalProjectionTest.java#aBusyChatServerRefusalIsTransientThroughTheChatModeRoute",
        "modules/ui/src/test/java/io/justsearch/ui/api/TransitionRefusalProjectionTest.java#anInterruptedTransitionRefusalIsTransient",
        "modules/ui/src/test/java/io/justsearch/ui/api/TransitionRefusalProjectionTest.java#anOccupiedSettingsTransactionIsATransientConflict"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/TransitionRefusalProjectionTest.java"
      ],
      "class": "New",
      "behaviour": "Preservation, passing on d3fe74ef2 and after (recorded in the preserved evidence's fail-before run). aPermanentGenerativeRefusalStaysAPermanentFault: the same owner refused with INVALID_CONFIG ('Model file not found') still projects through the settings route as 500 PERMANENT with retryable false.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 2-4) end to end within the agreed scenarios.",
          "choice": "Item 5 repair of Spec A steps 2-3 types only transition refusals transient; a refusal that the same candidate cannot outlive (an invalid configuration) keeps its existing permanent projection.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-drain-repair-preserved.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/TransitionRefusalProjectionTest.java#aPermanentGenerativeRefusalStaysAPermanentFault"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/OpenAiCompatOfflineWordingTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "aRefusedConnectionSaysChatIsNotRunningWithoutTheStalePort: OpenAiCompatController.handleChatCompletions with an upstream that refuses the connection (port supplier 8081) answers 503 TRANSIENT, and the error message names neither the port nor llama-server. On d3fe74ef2 the message was 'llama-server connect refused on port 8081'.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 2-4) end to end within the agreed scenarios.",
          "choice": "Item 5 repair of Spec A steps 2-3: the /v1 proxy's connection-refused reply says chat is not running and stays a retryable 503, without the stale internal port wording.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-drain-repair-repaired.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/OpenAiCompatOfflineWordingTest.java#aRefusedConnectionSaysChatIsNotRunningWithoutTheStalePort"
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
  "session": "1da6afea-63f7-4a90-94ac-ed3e97c5f9df",
  "verdict": "accept",
  "digest": "sha256:102145d9a4e6f34794f70cf66476692b1e5f8743836695d6bc3e8c06b1d9a7bf",
  "note": "Repaired checks fail on 7edc726dd for the reason under test (search 500 INVALID_STATE PERMANENT; optional steps leak IllegalStateException; GPL aborted; VDU counted failed; refusal 500/retryable:false; 'port 8081' wording) and pass on 125bfe23d; preservation checks pass on both. Live drain on both builds agrees: baseline search 500 PERMANENT, reactivate 409 retryable:false, GPL FAILED on IllegalStateException; candidate search 200 AI_UNAVAILABLE, 409 TRANSIENT retryable:true, GPL deferred and COMPLETED 68/68."
}
```
