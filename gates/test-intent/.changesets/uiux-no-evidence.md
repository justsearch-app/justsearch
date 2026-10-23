---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: uiux-no-evidence-builder
---

# Test-intent entries

WP4.2b. All scenario sources below are agent-adopted under delegated design choice.
The scenario text in each choice is quoted verbatim from the builder brief. The original
scenario id is carried in agreedScenario. No independent acceptance is authored here.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/Sv3NoEvidence.test.ts"
      ],
      "class": "New",
      "behaviour": "No-evidence classification and model provenance live and reloaded; scope and error separation; model-only request with no client context; latest-turn action, model and lock gates, single-flight activation, focus, retries after failures and Stop, tier-preserving Retry/Edit, destination ownership across conversation switches, preservation of an unrelated composer draft and the existing rewrite note, and ordering with pending context writes.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "S11.1",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The orchestrator adopted the WP4.2b scenarios under the user's delegation over UI/UX designs; these are agent-adopted choices, within WP4.2b and its adopted decisions.",
          "choice": "An unscoped Ask whose retrieval ran and found nothing shows a no-evidence outcome: a plain statement that nothing in the user's files matched, and one action to answer without the files. It shows no failure message, no empty-response text, no sources disclosure and no grounding frame. Produced through the real dispatch interface (not a handcrafted record), live, after cold reload and after an Engine restart.",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "S11.2",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The orchestrator adopted the WP4.2b scenarios under the user's delegation over UI/UX designs; these are agent-adopted choices, within WP4.2b and its adopted decisions.",
          "choice": "The action answers the same question without searching, in the same conversation. The answer is labelled as the model's own answer, not drawn from the user's files, and carries no citation marks, live, after cold reload and after an Engine restart.",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "S11.3",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The orchestrator adopted the WP4.2b scenarios under the user's delegation over UI/UX designs; these are agent-adopted choices, within WP4.2b and its adopted decisions.",
          "choice": "Only a retrieval that ran and found nothing gives the no-evidence outcome. Retrieval timeout, retrieval failure and fetch failure (RAGContext.java:432-455) still read as errors with their existing explanation live; after cold reload and restart the question and its error classification and explanation are still there (decision D5). An empty search that ran degraded (keyword-only fallback) shows the no-evidence outcome with the existing fallback explanation beside it (decision D4).",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "S11.4",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The orchestrator adopted the WP4.2b scenarios under the user's delegation over UI/UX designs; these are agent-adopted choices, within WP4.2b and its adopted decisions.",
          "choice": "An Ask scoped to chosen files whose files had no content (or were all hidden) is unchanged from today live, and after reload is still told apart from the unscoped case (decision D3; older records fall back on the fixed message, RAGContext.java:458 vs :466).",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "S11.5",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The orchestrator adopted the WP4.2b scenarios under the user's delegation over UI/UX designs; these are agent-adopted choices, within WP4.2b and its adopted decisions.",
          "choice": "Before activation: the action needs the AI model to be available but not any indexed documents (it still works after the last indexed file is removed; the documents gate is availability.ts:138-156). When the model is unavailable, another turn is running or the conversation is locked, it is not offered or says why. It never sends an Ask instead; pressing it twice gives one answer.",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "S11.6",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The orchestrator adopted the WP4.2b scenarios under the user's delegation over UI/UX designs; these are agent-adopted choices, within WP4.2b and its adopted decisions.",
          "choice": "The model answer is answered in the light of the conversation: after Ask answers, exclusions, a context starting point and a compaction summary, it receives each eligible earlier message once and the summary once, honouring exclusions and the starting point; the record gains only the new question and answer. After cold reload, and after a second model answer, the transcript has no duplicated messages and existing message identities are unchanged (decision D6).",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "S11.7",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The orchestrator adopted the WP4.2b scenarios under the user's delegation over UI/UX designs; these are agent-adopted choices, within WP4.2b and its adopted decisions.",
          "choice": "Retry and Edit on the no-evidence turn re-run it as an Ask. Retry and Edit on a model answer re-run it as a model answer, also after cold reload. A later follow-up Ask in the same conversation works normally.",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "S11.8",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The orchestrator adopted the WP4.2b scenarios under the user's delegation over UI/UX designs; these are agent-adopted choices, within WP4.2b and its adopted decisions.",
          "choice": "Free-chat turns made in the old window, opened in Search v3, are labelled as model answers, not shown as unframed answers that look grounded (today sv3-record.ts:470-472, sv3-honesty.ts:405-408).",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "S11.9",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The orchestrator adopted the WP4.2b scenarios under the user's delegation over UI/UX designs; these are agent-adopted choices, within WP4.2b and its adopted decisions.",
          "choice": "The outcome and the action work by keyboard alone; the action has an accessible name; after activation, or when the action disappears, focus lands on a useful target, not the page top. Legible in both themes, forced colors, about 360 px and 200 % text zoom. The statement is announced once, not on each frame.",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "S11.11",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The orchestrator adopted the WP4.2b scenarios under the user's delegation over UI/UX designs; these are agent-adopted choices, within WP4.2b and its adopted decisions.",
          "choice": "After activation: model failure, Stop and a lock refusal each leave the original no-evidence outcome intact, show what happened to the model answer, and allow trying again. The answer lands in the conversation it was asked from even if the user switches conversation before it settles.",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "D13",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The orchestrator adopted the WP4.2b scenarios under the user's delegation over UI/UX designs; these are agent-adopted choices, within WP4.2b and its adopted decisions.",
          "choice": "D13 No-evidence action lifetime. Options: latest turn only; any no-evidence turn. Recommend latest turn only, live and reloaded; the outcome statement stays. Why: answering an older turn would insert an answer mid-conversation or need branching.",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "D6",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The orchestrator adopted the WP4.2b scenarios under the user's delegation over UI/UX designs; these are agent-adopted choices, within WP4.2b and its adopted decisions.",
          "choice": "D6 History source for the model answer. Options: server store (send sessionId, no client context); client context like Ask (conversationId only, so the persistent path is not taken). Recommend the server store. Why: the store already applies floor, exclusions and summary (loadEffectiveContext, fixed by WP1.2) and appends only the new prompt; sending both duplicates history in prompt and record. Cost to accept: stored history is not token-capped, as in the old window's free chat today.",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "F2",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The orchestrator adopted the WP4.2b scenarios under the user's delegation over UI/UX designs; these are agent-adopted choices, within WP4.2b and its adopted decisions.",
          "choice": "After Engine loss during checking, and after model-answer failure or context overflow, cold reload retains the saved question and accurately represents its failed or unfinished attempt. It never shows a completed empty answer, silently retries, or duplicates the question. The original no-evidence outcome remains intact.",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "N10.9",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The orchestrator adopted the WP4.2b scenarios under the user's delegation over UI/UX designs; these are agent-adopted choices, within WP4.2b and its adopted decisions.",
          "choice": "Ask follow-up context (WP1.2 S2.0) and the old window's free chat behave as today.",
          "label": "agent choice; agent-adopted (delegated-choice)"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-no-evidence-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/Sv3NoEvidence.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/AskTerminalReload.test.ts"
      ],
      "class": "New",
      "behaviour": "Replace the superseded Search v3 expectation that unscoped NO_CONTENT renders as a failure with the adopted no-evidence outcome, preserving all fallback explanations. Preserve the old window's existing error, Stop and model-answer rendering, including a mixed conversation carrying the new optional error field.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "S11.1",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The orchestrator adopted the WP4.2b scenarios under the user's delegation over UI/UX designs; these are agent-adopted choices, within WP4.2b and its adopted decisions.",
          "choice": "An unscoped Ask whose retrieval ran and found nothing shows a no-evidence outcome: a plain statement that nothing in the user's files matched, and one action to answer without the files. It shows no failure message, no empty-response text, no sources disclosure and no grounding frame. Produced through the real dispatch interface (not a handcrafted record), live, after cold reload and after an Engine restart.",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "S11.3",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The orchestrator adopted the WP4.2b scenarios under the user's delegation over UI/UX designs; these are agent-adopted choices, within WP4.2b and its adopted decisions.",
          "choice": "Only a retrieval that ran and found nothing gives the no-evidence outcome. Retrieval timeout, retrieval failure and fetch failure (RAGContext.java:432-455) still read as errors with their existing explanation live; after cold reload and restart the question and its error classification and explanation are still there (decision D5). An empty search that ran degraded (keyword-only fallback) shows the no-evidence outcome with the existing fallback explanation beside it (decision D4).",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "S11.10",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The orchestrator adopted the WP4.2b scenarios under the user's delegation over UI/UX designs; these are agent-adopted choices, within WP4.2b and its adopted decisions.",
          "choice": "The same conversation opened in the old window renders both turns (its own no-matching-documents error and the free-chat answer) without errors.",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "N10.8",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The orchestrator adopted the WP4.2b scenarios under the user's delegation over UI/UX designs; these are agent-adopted choices, within WP4.2b and its adopted decisions.",
          "choice": "/api/thread and history reads by other clients (MCP, API) still parse conversations containing the new turns and any new optional error fields.",
          "label": "agent choice; agent-adopted (delegated-choice)"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-no-evidence-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/AskTerminalReload.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/AskUnfinishedReload.test.ts"
      ],
      "class": "New",
      "behaviour": "A saved Ask or model question with no recorded outcome remains unfinished on cold projection; it never becomes a completed empty answer after Engine loss.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "F2",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The orchestrator adopted the WP4.2b scenarios under the user's delegation over UI/UX designs; these are agent-adopted choices, within WP4.2b and its adopted decisions.",
          "choice": "After Engine loss during checking, and after model-answer failure or context overflow, cold reload retains the saved question and accurately represents its failed or unfinished attempt. It never shows a completed empty answer, silently retries, or duplicates the question. The original no-evidence outcome remains intact.",
          "label": "agent choice; agent-adopted (delegated-choice)"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-no-evidence-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/AskUnfinishedReload.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/AskSubmillisecondOrder.test.ts"
      ],
      "class": "New",
      "behaviour": "Keep a real fast question and NO_CONTENT error in authoritative chronological order below one millisecond, including nanosecond-separated and timezone-equivalent instants. Cold Search v3 remains one no-evidence turn and the old window merge keeps the same order. Equal instants retain the existing id tiebreak and input events are not mutated.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "S11.1",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The orchestrator adopted the WP4.2b scenarios under the user's delegation over UI/UX designs; these are agent-adopted choices, within WP4.2b and its adopted decisions.",
          "choice": "An unscoped Ask whose retrieval ran and found nothing shows a no-evidence outcome: a plain statement that nothing in the user's files matched, and one action to answer without the files. It shows no failure message, no empty-response text, no sources disclosure and no grounding frame. Produced through the real dispatch interface (not a handcrafted record), live, after cold reload and after an Engine restart.",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "S11.10",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The orchestrator adopted the WP4.2b scenarios under the user's delegation over UI/UX designs; these are agent-adopted choices, within WP4.2b and its adopted decisions.",
          "choice": "The same conversation opened in the old window renders both turns (its own no-matching-documents error and the free-chat answer) without errors.",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "F2",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The orchestrator adopted the WP4.2b scenarios under the user's delegation over UI/UX designs; these are agent-adopted choices, within WP4.2b and its adopted decisions.",
          "choice": "After Engine loss during checking, and after model-answer failure or context overflow, cold reload retains the saved question and accurately represents its failed or unfinished attempt. It never shows a completed empty answer, silently retries, or duplicates the question. The original no-evidence outcome remains intact.",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "N10.8",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The orchestrator adopted the WP4.2b scenarios under the user's delegation over UI/UX designs; these are agent-adopted choices, within WP4.2b and its adopted decisions.",
          "choice": "/api/thread and history reads by other clients (MCP, API) still parse conversations containing the new turns and any new optional error fields.",
          "label": "agent choice; agent-adopted (delegated-choice)"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-no-evidence-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/AskSubmillisecondOrder.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/NoEvidencePersistenceTest.java"
      ],
      "class": "New",
      "behaviour": "Persist the explicit retrieval scope on unchanged NO_CONTENT errors, preserve the existing all-hidden scoped-file refusal without fetching hidden content, preserve question/classification/explanation for metadata-free retrieval errors, and save model failures including synchronous overflow as classified errors while preserving synchronous exceptions for the existing API adapter.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "S11.3",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The orchestrator adopted the WP4.2b scenarios under the user's delegation over UI/UX designs; these are agent-adopted choices, within WP4.2b and its adopted decisions.",
          "choice": "Only a retrieval that ran and found nothing gives the no-evidence outcome. Retrieval timeout, retrieval failure and fetch failure (RAGContext.java:432-455) still read as errors with their existing explanation live; after cold reload and restart the question and its error classification and explanation are still there (decision D5). An empty search that ran degraded (keyword-only fallback) shows the no-evidence outcome with the existing fallback explanation beside it (decision D4).",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "S11.4",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The orchestrator adopted the WP4.2b scenarios under the user's delegation over UI/UX designs; these are agent-adopted choices, within WP4.2b and its adopted decisions.",
          "choice": "An Ask scoped to chosen files whose files had no content (or were all hidden) is unchanged from today live, and after reload is still told apart from the unscoped case (decision D3; older records fall back on the fixed message, RAGContext.java:458 vs :466).",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "S11.11",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The orchestrator adopted the WP4.2b scenarios under the user's delegation over UI/UX designs; these are agent-adopted choices, within WP4.2b and its adopted decisions.",
          "choice": "After activation: model failure, Stop and a lock refusal each leave the original no-evidence outcome intact, show what happened to the model answer, and allow trying again. The answer lands in the conversation it was asked from even if the user switches conversation before it settles.",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "F2",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The orchestrator adopted the WP4.2b scenarios under the user's delegation over UI/UX designs; these are agent-adopted choices, within WP4.2b and its adopted decisions.",
          "choice": "After Engine loss during checking, and after model-answer failure or context overflow, cold reload retains the saved question and accurately represents its failed or unfinished attempt. It never shows a completed empty answer, silently retries, or duplicates the question. The original no-evidence outcome remains intact.",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "N10.9",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The orchestrator adopted the WP4.2b scenarios under the user's delegation over UI/UX designs; these are agent-adopted choices, within WP4.2b and its adopted decisions.",
          "choice": "Ask follow-up context (WP1.2 S2.0) and the old window's free chat behave as today.",
          "label": "agent choice; agent-adopted (delegated-choice)"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-no-evidence-java.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/NoEvidencePersistenceTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/NoEvidenceDispatchHttpTest.java"
      ],
      "class": "New",
      "behaviour": "Drive real production dispatch and thread/history routes against private Engine instances with the actual RAG/free-chat shapes and SPIs and the repository fake model process: keep no-evidence and model turns through cold reads and Engine reassembly, keep scoped/all-hidden outcomes and retrieval errors, use the persistent floor/exclusion/summary history once, preserve existing message ids, and retain an honest retryable model error when oversized saved history is refused, and preserve the legacy synchronous-failure wire code while saving its failed model attempt.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "S11.1",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The orchestrator adopted the WP4.2b scenarios under the user's delegation over UI/UX designs; these are agent-adopted choices, within WP4.2b and its adopted decisions.",
          "choice": "An unscoped Ask whose retrieval ran and found nothing shows a no-evidence outcome: a plain statement that nothing in the user's files matched, and one action to answer without the files. It shows no failure message, no empty-response text, no sources disclosure and no grounding frame. Produced through the real dispatch interface (not a handcrafted record), live, after cold reload and after an Engine restart.",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "S11.2",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The orchestrator adopted the WP4.2b scenarios under the user's delegation over UI/UX designs; these are agent-adopted choices, within WP4.2b and its adopted decisions.",
          "choice": "The action answers the same question without searching, in the same conversation. The answer is labelled as the model's own answer, not drawn from the user's files, and carries no citation marks, live, after cold reload and after an Engine restart.",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "S11.3",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The orchestrator adopted the WP4.2b scenarios under the user's delegation over UI/UX designs; these are agent-adopted choices, within WP4.2b and its adopted decisions.",
          "choice": "Only a retrieval that ran and found nothing gives the no-evidence outcome. Retrieval timeout, retrieval failure and fetch failure (RAGContext.java:432-455) still read as errors with their existing explanation live; after cold reload and restart the question and its error classification and explanation are still there (decision D5). An empty search that ran degraded (keyword-only fallback) shows the no-evidence outcome with the existing fallback explanation beside it (decision D4).",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "S11.4",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The orchestrator adopted the WP4.2b scenarios under the user's delegation over UI/UX designs; these are agent-adopted choices, within WP4.2b and its adopted decisions.",
          "choice": "An Ask scoped to chosen files whose files had no content (or were all hidden) is unchanged from today live, and after reload is still told apart from the unscoped case (decision D3; older records fall back on the fixed message, RAGContext.java:458 vs :466).",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "S11.6",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The orchestrator adopted the WP4.2b scenarios under the user's delegation over UI/UX designs; these are agent-adopted choices, within WP4.2b and its adopted decisions.",
          "choice": "The model answer is answered in the light of the conversation: after Ask answers, exclusions, a context starting point and a compaction summary, it receives each eligible earlier message once and the summary once, honouring exclusions and the starting point; the record gains only the new question and answer. After cold reload, and after a second model answer, the transcript has no duplicated messages and existing message identities are unchanged (decision D6).",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "S11.7",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The orchestrator adopted the WP4.2b scenarios under the user's delegation over UI/UX designs; these are agent-adopted choices, within WP4.2b and its adopted decisions.",
          "choice": "Retry and Edit on the no-evidence turn re-run it as an Ask. Retry and Edit on a model answer re-run it as a model answer, also after cold reload. A later follow-up Ask in the same conversation works normally.",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "S11.10",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The orchestrator adopted the WP4.2b scenarios under the user's delegation over UI/UX designs; these are agent-adopted choices, within WP4.2b and its adopted decisions.",
          "choice": "The same conversation opened in the old window renders both turns (its own no-matching-documents error and the free-chat answer) without errors.",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "S11.11",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The orchestrator adopted the WP4.2b scenarios under the user's delegation over UI/UX designs; these are agent-adopted choices, within WP4.2b and its adopted decisions.",
          "choice": "After activation: model failure, Stop and a lock refusal each leave the original no-evidence outcome intact, show what happened to the model answer, and allow trying again. The answer lands in the conversation it was asked from even if the user switches conversation before it settles.",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "D6",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The orchestrator adopted the WP4.2b scenarios under the user's delegation over UI/UX designs; these are agent-adopted choices, within WP4.2b and its adopted decisions.",
          "choice": "D6 History source for the model answer. Options: server store (send sessionId, no client context); client context like Ask (conversationId only, so the persistent path is not taken). Recommend the server store. Why: the store already applies floor, exclusions and summary (loadEffectiveContext, fixed by WP1.2) and appends only the new prompt; sending both duplicates history in prompt and record. Cost to accept: stored history is not token-capped, as in the old window's free chat today.",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "D6-overflow",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The orchestrator adopted the WP4.2b scenarios under the user's delegation over UI/UX designs; these are agent-adopted choices, within WP4.2b and its adopted decisions.",
          "choice": "because stored history is not token-capped, WP4.2b adds a scenario: a model answer in a conversation whose history exceeds the model's context either succeeds (history trimmed by the existing context rules) or shows an honest, retryable error; it never hangs or saves a broken turn.",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "N10.8",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The orchestrator adopted the WP4.2b scenarios under the user's delegation over UI/UX designs; these are agent-adopted choices, within WP4.2b and its adopted decisions.",
          "choice": "/api/thread and history reads by other clients (MCP, API) still parse conversations containing the new turns and any new optional error fields.",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "N10.9",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The orchestrator adopted the WP4.2b scenarios under the user's delegation over UI/UX designs; these are agent-adopted choices, within WP4.2b and its adopted decisions.",
          "choice": "Ask follow-up context (WP1.2 S2.0) and the old window's free chat behave as today.",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "F2",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The orchestrator adopted the WP4.2b scenarios under the user's delegation over UI/UX designs; these are agent-adopted choices, within WP4.2b and its adopted decisions.",
          "choice": "After Engine loss during checking, and after model-answer failure or context overflow, cold reload retains the saved question and accurately represents its failed or unfinished attempt. It never shows a completed empty answer, silently retries, or duplicates the question. The original no-evidence outcome remains intact.",
          "label": "agent choice; agent-adopted (delegated-choice)"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-no-evidence-java.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/NoEvidenceDispatchHttpTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StreamingFakeLlamaServer.java"
      ],
      "class": "New",
      "behaviour": "Add an isolated context-rejection fault to the existing fake model server. Without the private marker, its existing streaming behavior and deadlines are unchanged. The new HTTP check owns the marker, process and cleanup.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "F2",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The orchestrator adopted the WP4.2b scenarios under the user's delegation over UI/UX designs; these are agent-adopted choices, within WP4.2b and its adopted decisions.",
          "choice": "After Engine loss during checking, and after model-answer failure or context overflow, cold reload retains the saved question and accurately represents its failed or unfinished attempt. It never shows a completed empty answer, silently retries, or duplicates the question. The original no-evidence outcome remains intact.",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "D6",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The orchestrator adopted the WP4.2b scenarios under the user's delegation over UI/UX designs; these are agent-adopted choices, within WP4.2b and its adopted decisions.",
          "choice": "D6 History source for the model answer. Options: server store (send sessionId, no client context); client context like Ask (conversationId only, so the persistent path is not taken). Recommend the server store. Why: the store already applies floor, exclusions and summary (loadEffectiveContext, fixed by WP1.2) and appends only the new prompt; sending both duplicates history in prompt and record. Cost to accept: stored history is not token-capped, as in the old window's free chat today.",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "D6-overflow",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The orchestrator adopted the WP4.2b scenarios under the user's delegation over UI/UX designs; these are agent-adopted choices, within WP4.2b and its adopted decisions.",
          "choice": "because stored history is not token-capped, WP4.2b adds a scenario: a model answer in a conversation whose history exceeds the model's context either succeeds (history trimmed by the existing context rules) or shows an honest, retryable error; it never hangs or saves a broken turn.",
          "label": "agent choice; agent-adopted (delegated-choice)"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-no-evidence-java.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/NoEvidenceDispatchHttpTest.java"
      ],
      "repaired": true
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "ipjuyrq4-verifier-2",
  "verdict": "accept",
  "digest": "sha256:7ce824ce3d74dda78f4eb986c31a40af2909c3ca57dab13a5fb8ff53cb335072",
  "note": "Accepted New classifications and delegated-choice sources after baseline/source-first review. Independent run-evidence: 14 Java cases pass, zero skipped; baseline has 12 assertion failures plus 2 preservation passes. Frontend confirmation 34/34. Scope-disclosure finding and pre-existing esbuild suite failure remain separate verification findings; this accepts test intent, not product sign-off."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "uvce0kse-verifier-3",
  "verdict": "accept",
  "digest": "sha256:7ce824ce3d74dda78f4eb986c31a40af2909c3ca57dab13a5fb8ff53cb335072",
  "note": "Reaffirmed original test-intent acceptance: current digest exactly matches the prior accepted sha256:7ce824ce3d74dda78f4eb986c31a40af2909c3ca57dab13a5fb8ff53cb335072. The merge leaves the covered tests, entries and producer evidence unchanged; the original agent-adopted delegated-choice intent still applies. Product verification is reported separately."
}
```
