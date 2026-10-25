---
schema: test-intent.v1
task: memory-forget
author-role: builder
author-session: memory-forget-builder
---

# Test-intent entries

The orchestrator chose these adaptations under the owner's delegation to finish, re-verify and ship
the existing memory-forget F1-F6 fix. Every existing protection, including replaced/vanished-row
data-loss protection, remains required; the delegation does not authorize weakening or dropping it.
The default evidence runner uses linked dependencies; its before-tree is an isolated shared clone.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/MemorySurface.admission.test.ts",
        "modules/ui-web/src/shell-v0/views/MemorySurface.forget.test.ts",
        "modules/ui-web/src/shell-v0/views/MemorySurface.locked.test.ts",
        "modules/ui-web/src/shell-v0/views/MemorySurface.retention.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding",
        "signature"
      ],
      "whatMoved": "The locked-state delete probe passes its row revision to the updated private method. Rows now carry valid revisions; successful deletion fixtures carry removed:true; scripted fetches omit the retired preliminary GET. Confirmation cancellation, single activation, refusal handling, admission replay refusal, focus, lost-response reconciliation, retained navigation, drafts and locked-state assertions remain intact.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; ask me only what its section 10 lists.",
          "delegationLocation": "task memory-forget user turn 2026-10-25 takeover follow-up directing section 7 steps 1 and 2 before documentation",
          "delegationScope": "Finish, re-verify and ship the existing memory-forget F1-F6 fix.",
          "choice": "The orchestrator chose to adapt fixtures with revision or removal fields and assertions about the retired preliminary read, and to allow further adaptations of those same kinds only while preserving every existing protection; anything else must stop and be reported.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/MemorySurface.lock-metadata.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding",
        "instrumentation"
      ],
      "whatMoved": "Rows gain valid revisions and scripted fetches omit the retired preliminary GET. The invalid-consent-read case now explicitly reloads invalid lock metadata before activating the retained control. The same invalid metadata values still refuse DELETE and show an unavailable list; lost-response reconciliation, initial-list validation and manual recovery assertions are unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; ask me only what its section 10 lists.",
          "delegationLocation": "task memory-forget user turn 2026-10-25 takeover follow-up directing section 7 steps 1 and 2 before documentation",
          "delegationScope": "Finish, re-verify and ship the existing memory-forget F1-F6 fix.",
          "choice": "The orchestrator chose to adapt fixtures with revision or removal fields and assertions about the retired preliminary read, explicitly reloading invalid lock metadata before activating the retained control. Further adaptations of those same kinds must preserve every existing protection; anything else must stop and be reported.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/MemorySurface.safety.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; ask me only what its section 10 lists.",
          "delegationLocation": "task memory-forget user turn 2026-10-25 takeover follow-up directing section 7 steps 1 and 2 before documentation",
          "delegationScope": "Finish, re-verify and ship the existing memory-forget F1-F6 fix.",
          "choice": "The orchestrator chose that the conditional DELETE meets 412 (replacement) or reports nothing removed (vanished); the surviving record is intact; the UI shows the kept or already-gone message, never \"Forgotten\". Keep its replacement and disappearance cases.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "Compared the agreed v2 scenarios F1-F6, challenger findings and the orchestrator's test-adaptation choices: the zero-DELETE intermediate observation conflicts with the chosen server-side precondition. All replaced/vanished-row protections remain required; the delegation does not authorize weakening or dropping data-loss protection. No conflicting owner decision was supplied."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/MemorySurface.safety.test.ts"
      ],
      "class": "New",
      "behaviour": "Retain the content-change, timestamp-change and disappearance cases. Send the original revision; a replacement is intact after 412, absence reports removed:false, the UI names kept/already gone and never Forgotten; only fresh consent removes the replacement. Existing repeated-keydown and reconciliation focus cases remain.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; ask me only what its section 10 lists.",
          "delegationLocation": "task memory-forget user turn 2026-10-25 takeover follow-up directing section 7 steps 1 and 2 before documentation",
          "delegationScope": "Finish, re-verify and ship the existing memory-forget F1-F6 fix.",
          "choice": "The orchestrator chose that the conditional DELETE meets 412 (replacement) or reports nothing removed (vanished); the surviving record is intact; the UI shows the kept or already-gone message, never \"Forgotten\". Keep its replacement and disappearance cases.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/memory-conditional-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/MemorySurface.safety.test.ts"
      ],
      "repaired": true
    }
  ]
}
```

## Acceptance records
