---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: uiux-memory-builder-aza9i77t
---

# Test-intent entries

WP5.3a. These scenarios are agent-adopted (delegated-choice), never owner-adopted.
The pass-2 amendment supersedes the original lost-response clause in S53.3.
N53.2 and N53.3 are preservation checks; existing expected outcomes are unchanged.
F1–F3 continuation: confirmation inputs now include key release; confirmed Forget fixtures include the new pre-delete read. Neither adaptation weakens an oracle.

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
      "preservationEvidence": "gates/test-intent/evidence/uiux-memory-preservation.json",
      "setupAdaptation": "The existing browser fixture inherits the caller’s short TMPDIR for Chromium. Playwright supplies its fresh --user-data-dir there. Width and native-focus assertions are unchanged."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/ConfirmDialog.activation.test.ts",
        "modules/ui-web/src/shell-v0/views/MemorySurface.safety.test.ts"
      ],
      "class": "New",
      "behaviour": "A held/repeated Enter or Space never confirms deletion; confirmation needs a fresh down/up pair after showModal, with no repeat. Forget triggers reject repeat keydowns. After confirmation, Memory re-reads the store and refuses deletion if any existing displayed-record field or createdAt/sourceConversationId changed, or the item disappeared. Fresh consent is required for a replacement. Automatic and manual reconciliation announce confirmed presence or absence in the same live region and restore useful focus. Mouse, fresh Enter and Space confirmation remain usable with and without a matching typed phrase.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement 2026-10-19",
          "delegationScope": "WP5.3a F1–F3 repair explicitly assigned by the orchestrator continuation on 2026-10-23.",
          "label": "agent choice, adopted under the user's delegation",
          "scenario": "F1 / S53.3 / S53.7",
          "quote": "a key held down can never confirm a deletion, and can never carry over into another item's Forget.\nConfirming needs a fresh, separate activation that begins after the dialog is shown. Repeat keydowns and a key held since before the dialog opened do nothing.\nCheck the other ConfirmDialog callers still confirm with a normal press and with Enter and Space.",
          "choice": "a key held down can never confirm a deletion, and can never carry over into another item's Forget.\nConfirming needs a fresh, separate activation that begins after the dialog is shown. Repeat keydowns and a key held since before the dialog opened do nothing.\nCheck the other ConfirmDialog callers still confirm with a normal press and with Enter and Space.",
          "continuationLocation": "task t-20261019-bd718f orchestrator continuation F1–F3, 2026-10-23"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement 2026-10-19",
          "delegationScope": "WP5.3a F1–F3 repair explicitly assigned by the orchestrator continuation on 2026-10-23.",
          "label": "agent choice, adopted under the user's delegation",
          "scenario": "F2 / S53.3",
          "quote": "consent covers what was shown.\nIf the memory API or store offers a conditional delete (a version, timestamp or content match), use it.\nOtherwise, re-read the item right before deleting. If it changed or is gone, do not delete; show what is now there and ask again. Record the remaining read-then-delete window as a limit, and only if there is no conditional option in the owner's code.",
          "choice": "consent covers what was shown.\nIf the memory API or store offers a conditional delete (a version, timestamp or content match), use it.\nOtherwise, re-read the item right before deleting. If it changed or is gone, do not delete; show what is now there and ask again. Record the remaining read-then-delete window as a limit, and only if there is no conditional option in the owner's code.",
          "continuationLocation": "task t-20261019-bd718f orchestrator continuation F1–F3, 2026-10-23"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement 2026-10-19",
          "delegationScope": "WP5.3a F1–F3 repair explicitly assigned by the orchestrator continuation on 2026-10-23.",
          "label": "agent choice, adopted under the user's delegation",
          "scenario": "F3 / S53.3 amendment / S53.7",
          "quote": "When that reload confirms absence or presence, announce the outcome in the same live region and wording style as automatic reconciliation.\nPut focus somewhere useful, the way automatic reconciliation does, never on BODY.",
          "choice": "When that reload confirms absence or presence, announce the outcome in the same live region and wording style as automatic reconciliation.\nPut focus somewhere useful, the way automatic reconciliation does, never on BODY.",
          "continuationLocation": "task t-20261019-bd718f orchestrator continuation F1–F3, 2026-10-23"
        }
      ],
      "conflictSearch": "The F1–F3 continuation explicitly amends shared confirmation and Memory consent/reconciliation in WP5.3a. No conditional delete exists in MemoryStore, FileMemoryStore or MemoryController. Existing createdAt is compared; no API field or Engine change is introduced. The permitted read/delete race remains. Initial focus, native modality and visible design are unchanged.",
      "evidence": "gates/test-intent/evidence/uiux-memory-safety.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/ConfirmDialog.activation.test.ts",
        "modules/ui-web/src/shell-v0/views/MemorySurface.safety.test.ts"
      ],
      "repaired": true,
      "cumulativeEvidence": "gates/test-intent/evidence/uiux-memory-repair.json",
      "liveEvidence": "tmp/memory-fixes/before/live-fixes.json and tmp/memory-fixes/after/live-fixes.json",
      "remainingLimit": "Without an owner conditional-delete API/store operation, replacement in the interval between the final GET and DELETE cannot be detected atomically.",
      "preservationEvidence": "gates/test-intent/evidence/uiux-memory-preservation.json"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/ConfirmDialog.test.ts",
        "modules/ui-web/src/shell-v0/components/ConfirmDialog.keyboard-ownership.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Existing ordinary Enter activations now dispatch keyup after keydown. All original expected confirmations/cancellations, phrase matching, ownership checks, and observations are retained. F1 requires confirmation on fresh release so that a subsequent repeat can invalidate the activation. The matching-input handler is now part of the shared capture guard; the retargeted-host case still cannot confirm while the input owns focus.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement 2026-10-19",
          "delegationScope": "WP5.3a F1–F3 repair explicitly assigned by the orchestrator continuation on 2026-10-23.",
          "label": "agent choice, adopted under the user's delegation",
          "scenario": "F1 / S53.3 / S53.7",
          "quote": "a key held down can never confirm a deletion, and can never carry over into another item's Forget.\nConfirming needs a fresh, separate activation that begins after the dialog is shown. Repeat keydowns and a key held since before the dialog opened do nothing.\nCheck the other ConfirmDialog callers still confirm with a normal press and with Enter and Space.",
          "choice": "a key held down can never confirm a deletion, and can never carry over into another item's Forget.\nConfirming needs a fresh, separate activation that begins after the dialog is shown. Repeat keydowns and a key held since before the dialog opened do nothing.\nCheck the other ConfirmDialog callers still confirm with a normal press and with Enter and Space.",
          "continuationLocation": "task t-20261019-bd718f orchestrator continuation F1–F3, 2026-10-23"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/MemorySurface.admission.test.ts",
        "modules/ui-web/src/shell-v0/api/authorizedFetch.singleAttempt.test.ts",
        "modules/ui-web/src/shell-v0/components/ConfirmDialog.menu-ownership.test.ts"
      ],
      "class": "New",
      "behaviour": "Memory DELETE opts out of automatic admission and stale-token replay. A retry-safe refusal is reported and refreshed, with the replacement preserved until fresh user consent and its identity check. The one-shot authenticated path retains origin-bound session credentials, redirect refusal and response observation. Shared confirmation owns its modal key events at window capture before earlier document listeners; a ContextMenu cannot reopen on confirmation Enter or consume other modal keys. Fresh Enter completes one action, and held Enter completes none.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement 2026-10-19",
          "delegationScope": "WP5.3a second rejection continuation: admission replay and shared confirmation key ownership. Agent-adopted under the existing delegation.",
          "label": "agent choice, adopted under the user's delegation",
          "scenario": "WP5.3a continuation finding 1 / S53.3",
          "quote": "every DELETE attempt for a Forget must be preceded by the same identity check. Re-read and compare first; if X changed or vanished, send nothing and show what is there now. Choose whichever is simpler and keeps the admission behavior honest: revalidate inside the retry, or skip the automatic retry for this call and handle the refusal yourself.\nThe single read-then-delete window the API forces remains a limit; nothing wider is acceptable.",
          "choice": "every DELETE attempt for a Forget must be preceded by the same identity check. Re-read and compare first; if X changed or vanished, send nothing and show what is there now. Choose whichever is simpler and keeps the admission behavior honest: revalidate inside the retry, or skip the automatic retry for this call and handle the refusal yourself.\nThe single read-then-delete window the API forces remains a limit; nothing wider is acceptable.",
          "continuationLocation": "task t-20261019-bd718f orchestrator continuation against eb0dcaf91"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement 2026-10-19",
          "delegationScope": "WP5.3a second rejection continuation: admission replay and shared confirmation key ownership. Agent-adopted under the existing delegation.",
          "label": "agent choice, adopted under the user's delegation",
          "scenario": "WP5.3a continuation finding 2 / S53.7",
          "quote": "ordinary Enter and Space confirm exactly once in every ConfirmDialog caller, including one opened from a ContextMenu. Held or repeated keys still never confirm.\nFix the ownership so the menu's listener never sees a keystroke that belongs to the open confirmation; prefer the dialog owning its keys over special-casing the menu.",
          "choice": "ordinary Enter and Space confirm exactly once in every ConfirmDialog caller, including one opened from a ContextMenu. Held or repeated keys still never confirm.\nFix the ownership so the menu's listener never sees a keystroke that belongs to the open confirmation; prefer the dialog owning its keys over special-casing the menu.",
          "continuationLocation": "task t-20261019-bd718f orchestrator continuation against eb0dcaf91"
        }
      ],
      "conflictSearch": "The continuation permits skipping automatic replay for this call. A stale-token replay would violate the same every-attempt requirement, so the opt-out covers both transport layers. Existing automatic retry defaults for other callers remain. The window capture change is inside ConfirmDialog; ContextMenu production source and its known pointer cancellation defect are untouched. No Engine/API changes.",
      "evidence": "gates/test-intent/evidence/uiux-memory-retry.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/MemorySurface.admission.test.ts",
        "modules/ui-web/src/shell-v0/api/authorizedFetch.singleAttempt.test.ts",
        "modules/ui-web/src/shell-v0/components/ConfirmDialog.menu-ownership.test.ts"
      ],
      "repaired": true,
      "retryChoice": "Return admission/stale-token refusals for manual retry. Existing failure reporting refreshes Memory; a later activation needs fresh consent and the existing pre-delete identity read. This avoids replaying a destructive request after its precondition expires, without adding a retry state machine.",
      "liveEvidence": "tmp/memory-retry/before/live-results.json and tmp/memory-retry/after/live-results.json; production bundles with SES enabled, private Engine and real replacement.",
      "callerPreservation": "tmp/memory-retry/after/caller-results.json: 294 pass; six known pre-existing pointer failures. Ten real caller paths, three themes, two widths, Enter/Space/click/held Enter/held Space. Synthetic conversation backend responses are fixture-only; Memory uses the real API."
    }
  ]
}
```
