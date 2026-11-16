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
2d51325e39 it fails by assertion in 19 of 27 cases: the radios are the browser's own (appearance auto), "How rules work" shows the browser
triangle, "One rule per line" is visible text, an empty rule field shows with no rules, a second field is
added instead of the empty one being focused, an added field left empty stays, removing a rule by
keyboard leaves focus on a cross instead of moving it to the next rule's field, the previous one or Add a
rule, and "and" is 15 px. The keyboard-focus and forced-colours cases (3) and X1 with four of its
neighbours where a field cleared of a saved rule keeps its row and its cross (5) pass on the base and on
the candidate (preserved behaviour).

PD4'' (the orchestrator's narrowing after a second real-app verification, 2026-11-16, replacing PD4' for
these cases): on 763a34063b leaving a cleared field removed it, the rows shifted up under a pointer press
already in progress and the press was lost (rules *.b, *.a, *.c, *.a cleared, a press on the cross of
*.c: *.c stayed and focus fell to the page). Now a field cleared of a saved rule keeps its row and its
remove cross and is never removed on leave; leaving it saves the list without that rule, exactly as the
base does (checked on 2d51325e39: the row and cross stay, the list without the rule is saved, and with
the only rule cleared Tab goes on to its cross). Only the new empty field from Add a rule is removed when
left empty (without a save); it is the last row, and when a pointer press is in progress its removal
waits until the press has ended, so nothing moves under the press. Add a rule while a cleared or a new
empty field exists focuses that field instead of adding another. Removing a rule from its cross moves
focus to the next rule's field, else the previous one, else Add a rule (a new empty field is not a
rule). Seven cases fail by assertion on 763a34063b and pass now: X1 (the press on *.c's cross removes it;
the cleared row stays with its cross; saves [*.b, *.c] then [*.b]); with *.a cleared, a press on a
radio, on Add a rule (focus in the cleared field, one empty field), on an earlier rule's cross, on a
later rule's cross, and a press on Add a rule released elsewhere keep the cleared row; and the only rule
cleared and left keeps its row and cross. The same five presses with a new empty field instead pass on
763a34063b and now (the new field goes after the press, which is not lost).

PD4'' supersedes these PD4' cases of the new file, replaced as named: "a middle rule cleared, then Add a
rule: still one empty field, the focused one" (now "cleared empty field: a pointer press on Add a rule
focuses that field; still one empty field", the cleared row stays in place); "the empty field left
after that goes; no blank field without a cross remains" and "a press on Add a rule released elsewhere
leaves no blank field" (now "cleared/new empty field: a press on Add a rule released elsewhere keeps the
cleared row / removes the new field"); and the last step of "existing rules stay; an added field left
empty goes without a save, and removing the last rule leaves only Add a rule" (an existing rule emptied
and left was removed; now it keeps its row and cross, the save of [] is unchanged, and the case is
renamed "... the only rule cleared and left keeps its row"). Removing the last rule with its cross still
leaves only Add a rule.

PD4 changes what four existing checks pinned: with no rules (or before the list is known) there is no
empty rule field, a new empty field has no remove cross, leaving a new empty field removes it without a
save, and Add a rule never leaves a second empty field (an existing empty field is the one focused). Each changed
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
          "choice": "PD4: with no rules, no empty rule field and no remove cross: only \"+ Add a rule\". Adding a rule shows one empty field with focus in it; leaving it empty removes it without a saved change. Existing rules and saving on leave are unchanged. Builder's detail within it, narrowed by PD4'': a new empty field from Add a rule has no remove cross and is removed when left empty, after any pointer press in progress has ended, so nothing moves under the press; a field cleared of a saved rule keeps its row and cross as on the base; Add a rule while an empty field (cleared or new) exists focuses that field instead of adding another; before the list is known no field shows and Add a rule is disabled.",
          "label": "agent choice"
        }
      ],
      "behaviour": "With no rules or before the skip-rule list is known there is no rule field; an added field left empty goes without a write; a field cleared of a rule keeps its row; Add a rule never leaves two empty fields.",
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

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-amdwh43b",
  "verdict": "accept",
  "digest": "sha256:a177fff7000d9ae2b385a1fdf1b7765fc0e325821093f9f1b43b3e55d6639aa3"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-wek0s8o_",
  "verdict": "accept",
  "digest": "sha256:5232691e57c5d0e9b8654a93569455df4c07e197beadaa1ccf3d5663d572877c"
}
```
