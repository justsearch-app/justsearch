---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: uiux-memory-builder-mzl00urp
---

# Test-intent entries

WP5.3a. These scenarios are agent-adopted (delegated-choice), never owner-adopted.
The pass-2 amendment supersedes the original lost-response clause in S53.3.
N53.2 and N53.3 are preservation checks; existing test expectations are unchanged.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/MemorySurface.forget.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.memory.test.ts"
      ],
      "class": "New",
      "behaviour": "Search v3 opens the existing Memory surface. Forget names the item, confirms, serializes repeated activations, reconciles uncertain responses against a validated readable list, preserves unknown outcomes, restores useful focus, and announces confirmed absence. Earlier visits cannot initiate or overwrite a newer visit. An earlier Remember completion cannot clear a newer draft or release the busy guard for a later Forget.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S53.3",
          "quote": "From Search v3 the user can reach what the assistant remembers (decision DM1) and forget an item. Specifically:\n  - each Forget control names its memory to a screen reader;\n  - forgetting asks for confirmation;\n  - repeated presses forget one item;\n  - a failed or lost response is reported, and the item is not shown as forgotten;\n  - the item stays forgotten after reload and restart;\n  - after removal, focus lands somewhere useful and the outcome is announced.",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement 2026-10-19",
          "delegationScope": "WP5.3 Memory UI design; adopted by the orchestrator after two challenger passes. Conversation delete is excluded from WP5.3a.",
          "choice": "S53.3: From Search v3 the user can reach what the assistant remembers (decision DM1) and forget an item. Specifically:\n  - each Forget control names its memory to a screen reader;\n  - forgetting asks for confirmation;\n  - repeated presses forget one item;\n  - a failed or lost response is reported, and the item is not shown as forgotten;\n  - the item stays forgotten after reload and restart;\n  - after removal, focus lands somewhere useful and the outcome is announced.",
          "label": "agent choice, adopted under the user's delegation"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S53.3 amendment",
          "quote": "A lost response reports an unknown outcome and reconciles with the store. A confirmed absence removes the item; an unsuccessful reconciliation leaves a visibly stale or unknown state. Neither success nor non-deletion is guessed.",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement 2026-10-19",
          "delegationScope": "WP5.3 Memory UI design; adopted by the orchestrator after two challenger passes. Conversation delete is excluded from WP5.3a.",
          "choice": "S53.3 amendment: A lost response reports an unknown outcome and reconciles with the store. A confirmed absence removes the item; an unsuccessful reconciliation leaves a visibly stale or unknown state. Neither success nor non-deletion is guessed.",
          "label": "agent choice, adopted under the user's delegation"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S53.4",
          "quote": "Memory shows loading, empty, locked and failed states as distinct states. A failed load never presents the stale list as current. While the store is locked, Forget says so and changes nothing.",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement 2026-10-19",
          "delegationScope": "WP5.3 Memory UI design; adopted by the orchestrator after two challenger passes. Conversation delete is excluded from WP5.3a.",
          "choice": "S53.4: Memory shows loading, empty, locked and failed states as distinct states. A failed load never presents the stale list as current. While the store is locked, Forget says so and changes nothing.",
          "label": "agent choice, adopted under the user's delegation"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S53.5",
          "quote": "Memories learned from a deleted conversation stay listed and can be forgotten. Nothing about them breaks.",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement 2026-10-19",
          "delegationScope": "WP5.3 Memory UI design; adopted by the orchestrator after two challenger passes. Conversation delete is excluded from WP5.3a.",
          "choice": "S53.5: Memories learned from a deleted conversation stay listed and can be forgotten. Nothing about them breaks.",
          "label": "agent choice, adopted under the user's delegation"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S53.7",
          "quote": "The new dialogs and the Memory changes work by keyboard and screen reader, in light, dark and forced-colors themes, and at narrow width.",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement 2026-10-19",
          "delegationScope": "WP5.3 Memory UI design; adopted by the orchestrator after two challenger passes. Conversation delete is excluded from WP5.3a.",
          "choice": "S53.7: The new dialogs and the Memory changes work by keyboard and screen reader, in light, dark and forced-colors themes, and at narrow width.",
          "label": "agent choice, adopted under the user's delegation"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "N53.2",
          "quote": "Memory remember, list and the locked state.",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement 2026-10-19",
          "delegationScope": "WP5.3 Memory UI design; adopted by the orchestrator after two challenger passes. Conversation delete is excluded from WP5.3a.",
          "choice": "N53.2: Memory remember, list and the locked state. A completion from an earlier visit cannot release a later Forget guard or overwrite the current draft.",
          "label": "agent choice, adopted under the user's delegation"
        }
      ],
      "conflictSearch": "Compared WP5.3 and pass-2 amendments with DC3, DC4 and DM1 in the builder assignment: amendment overrides the original lost-response clause; Memory saved-state semantics and conversation delete stay unchanged.",
      "evidence": "gates/test-intent/evidence/uiux-memory-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/MemorySurface.forget.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.memory.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/ConfirmDialog.viewport-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "The real shared confirmation retains 16px side gaps at a 16px root font size in 320/360/480/800px viewports, in light/dark/forced-colors themes. Wide width remains 450px at a 1280px viewport (28rem content plus the original borders). Native modal browser focus is preserved: behind-page focus is refused; Shift+Tab and Tab return into the dialog. These checks use the existing separate browser/lockdown lane and private Vite/Chromium instances, without an Engine.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S53.7",
          "quote": "The new dialogs and the Memory changes work by keyboard and screen reader, in light, dark and forced-colors themes, and at narrow width.",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement 2026-10-19",
          "delegationScope": "WP5.3a shared confirmation sizing and native-focus preservation, explicitly scoped by the orchestrator continuation on 2026-10-23.",
          "choice": "ConfirmDialog width at 360 px: fix it in this package. The dialog has width 100% plus a 1 px border on each side, so it measures 362 px in a 360 px viewport. Make the shared dialog fit inside the viewport at any width, with a small side gap (for example border-box sizing and a max width that leaves 16 px each side). It must keep its current size wherever it fits today. Tab from the last control leaving BODY active: no change. ConfirmDialog uses native showModal. Focus can leave to the browser's own controls and comes back into the dialog, while the page behind stays inert; that is the platform's modal behavior. Record it in your handoff as checked and not a defect. Show that the page behind cannot take focus and that Shift+Tab and Tab return into the dialog.",
          "label": "agent choice, adopted under the user's delegation",
          "continuationLocation": "task t-20261019-bd718f orchestrator continuation 2026-10-23"
        }
      ],
      "conflictSearch": "The orchestrator explicitly scoped the shared width correction into WP5.3a and adopted native modal focus as preservation, not a defect; no conversation-delete behavior is changed.",
      "evidence": "gates/test-intent/evidence/uiux-memory-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/ConfirmDialog.viewport-lockdown.test.ts#fits narrow viewports with a side gap in every theme (S53.7)"
      ],
      "repaired": true,
      "preservationChecks": [
        "modules/ui-web/src/shell-v0/components/ConfirmDialog.viewport-lockdown.test.ts#preserves the wide dialog width (S53.7 preservation)",
        "modules/ui-web/src/shell-v0/components/ConfirmDialog.viewport-lockdown.test.ts#keeps the page inert and returns from native browser focus with Tab and Shift+Tab (S53.7 preservation)"
      ],
      "preservationEvidence": "gates/test-intent/evidence/uiux-memory-preservation.json"
    }
  ]
}
```
