---
schema: test-intent.v1
task: t-20261113-fmimpl
author-role: builder
author-session: fm-pd-builder
---

# Library Folders form looks as drawn in fm-07

The design review of the packaged app (fm-07: "form controls [MISS, looks unfinished]") is repaired
under the orchestrator's delegated decisions PD1-PD6 (task t-20261113-fmimpl, 2026-11-16, within the
delegation "You are supposed to be the implementation session"). The new behaviour is covered by the
isolated new file `views/library-first-minutes/form-controls-lockdown.test.ts` (exempt). On the base
2d51325e39 it fails by assertion in 10 of 13 cases: the radios are the browser's own (appearance auto),
"How rules work" shows the browser triangle, "One rule per line" is visible text, an empty rule field
shows with no rules, a second field is added instead of the empty one being reused, and "and" is
15 px. The keyboard-focus and forced-colours cases pass on the base and on the candidate (preserved
behaviour).

PD4 changes what four existing checks pinned: with no rules (or before the list is known) there is no
empty rule field, an empty field has no remove cross, leaving an empty field removes it without a
save, and Add a rule focuses an empty field already last instead of adding a second one. Each changed
assertion is listed below; every other assertion in these files is unchanged and passes.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/library-first-minutes/slice-6b2.test.ts",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.settings-attempt.test.ts",
        "modules/ui-web/src/shell-v0/views/library-first-minutes/slice-6b2-reload-lockdown.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "An empty rule field with a remove cross shows when there are no rules.",
          "location": "task t-20261113-fmimpl request, fm-07 design review of the packaged app by the author of the pictures, 2026-11-16"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "PD4: with no rules, no empty rule field and no remove cross: only \"+ Add a rule\". Adding a rule shows one empty field with focus in it; leaving it empty removes it without a saved change. Existing rules and saving on leave are unchanged. Builder's detail within it: an empty field has no remove cross (leaving it removes it); a pointer press on Add a rule from an empty field keeps that field and Add focuses it, so the press is not lost and no second empty field is stacked; before the list is known no field shows and Add a rule is disabled.",
          "label": "agent choice"
        }
      ],
      "behaviour": "With no rules or before the skip-rule list is known there is no rule field; an added field left empty goes without a write; Add a rule never leaves two empty fields.",
      "conflictSearch": "Decision records under docs/decisions/ (54) searched for skip rule, exclude pattern, excludePatterns, empty field, rule field, radio, checkbox and disclosure: 0008 governs persisting excludePatterns (unchanged: the saved list and its writes are the same); 0015 uses progressive disclosure for MCP tool responses only. No conflict.",
      "assertionChanges": [
        {
          "oldAssertion": "slice-6b2.test.ts 'S6b-1: no Save, Reload, Preview or Apply row; first run writes nothing on open': with no saved rules, Skip rule 1 exists; focusing and leaving it writes nothing.",
          "newAssertion": "No Skip rule field exists with no saved rules; after Add a rule, Skip rule 1 exists and focusing and leaving it writes nothing (posts and ops stay empty).",
          "reason": "PD4: with no rules only Add a rule shows. The no-write expectation is kept and now goes through Add a rule."
        },
        {
          "oldAssertion": "slice-6b2.test.ts 'S6b-1: a blank new row writes nothing; deleting the last rule saves an empty list': after leaving the blank Skip rule 2, its Remove skip rule 2 is pressed, then Remove skip rule 1; one write of [].",
          "newAssertion": "After leaving the blank Skip rule 2 nothing is written and the field is gone; Remove skip rule 1 then writes [] once.",
          "reason": "PD4: leaving an empty field removes it without a save, so there is no Remove skip rule 2 to press. Both write expectations are unchanged."
        },
        {
          "oldAssertion": "slice-6b2.test.ts 'B3: a load failure says so with Try again, and the editor is not editable until loaded': while the load failed, Skip rule 1 is disabled.",
          "newAssertion": "While the load failed, no Skip rule field exists and Add a rule is disabled; after Try again Skip rule 1 is enabled and holds *.log (unchanged).",
          "reason": "PD4: before the list is known the draft is empty, which now shows no field. Not editable until loaded is kept through the disabled Add a rule."
        },
        {
          "oldAssertion": "LibrarySurface.settings-attempt.test.ts 'failed or unwitnessed load (%i) keeps editing/save disabled and never posts empty patterns': the first .skip-rule input is disabled.",
          "newAssertion": "No .skip-rule input exists and .add-rule is disabled; the alert, excludesLoaded false, empty draft and zero POSTs and invocations are unchanged.",
          "reason": "PD4: with an unknown (empty) list no rule field shows; editing stays unavailable through the disabled Add a rule."
        },
        {
          "oldAssertion": "slice-6b2-reload-lockdown.test.ts 'R26: Add a rule adds exactly one empty focused field ...': each press makes the field count one more than before the press, also while an empty field from the previous press is open; the two empty fields are then removed with Remove skip rule 4 and 3.",
          "newAssertion": "Each press ends with exactly one empty focused field after the rules held (count of non-empty fields + 1), also across a passive re-read pressed while an empty field is open; Shift+Tab out of that empty field removes it with no write (saved list stays [*.log, *.tmp]). The completing-save and completing-Undo steps are unchanged.",
          "reason": "PD4: an empty field already last is the one focused, and an empty field left goes by itself (it has no remove cross)."
        }
      ]
    }
  ]
}
```

## Acceptance records
