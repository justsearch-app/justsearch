---
schema: test-intent.v1
task: memory-forget
author-role: builder
author-session: memory-forget-builder
---

# Test-intent entries

The owner approved these adaptations on the condition that every existing protection remains.
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
          "kind": "owner-words",
          "location": "task memory-forget user turn test-adaptation-decision",
          "quote": "Further adaptations of the same kind (fixtures gaining revision or removal fields, or assertions about the retired preliminary read) are pre-approved on the same rule; anything else, stop and report."
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
          "kind": "owner-words",
          "location": "task memory-forget user turn test-adaptation-decision",
          "quote": "Further adaptations of the same kind (fixtures gaining revision or removal fields, or assertions about the retired preliminary read) are pre-approved on the same rule; anything else, stop and report."
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
          "kind": "owner-words",
          "location": "task memory-forget user turn test-adaptation-decision",
          "quote": "the conditional DELETE meets 412 (replacement) or reports nothing removed (vanished); the surviving record is intact; the UI shows the kept or already-gone message, never \"Forgotten\". Keep its replacement and disappearance cases."
        }
      ],
      "conflictSearch": "Compared the agreed v2 scenarios F1-F6, challenger findings and the owner's explicit test-adaptation decision: the zero-DELETE intermediate observation conflicts with the approved server-side precondition. No conflicting owner decision was supplied."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/MemorySurface.safety.test.ts"
      ],
      "class": "New",
      "behaviour": "Retain the content-change, timestamp-change and disappearance cases. Send the original revision; a replacement is intact after 412, absence reports removed:false, the UI names kept/already gone and never Forgotten; only fresh consent removes the replacement. Existing repeated-keydown and reconciliation focus cases remain.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task memory-forget user turn test-adaptation-decision",
          "quote": "the conditional DELETE meets 412 (replacement) or reports nothing removed (vanished); the surviving record is intact; the UI shows the kept or already-gone message, never \"Forgotten\". Keep its replacement and disappearance cases."
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
