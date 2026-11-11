---
schema: test-intent.v1
task: t-20261103-shell
author-role: builder
author-session: s6-activity-feed-builder
---

# S6 pieces 9-10, candidate 1 — Activity correlation, classification and one feed

No acceptance is claimed; independent verification is pending. Two defects broken at 716017c61f are
repaired: undo-all reported Engine undo success from dispatch alone, and the shared seen cursor
compared timestamps as strings. The replayed-run and mark-all-read-scope checks are preservation
checks and pass before as well.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/EffectAuditLog.test.ts",
        "modules/ui-web/src/shell-v0/operations/ActionLedgerClient.test.ts",
        "modules/ui-web/src/shell-v0/substrates/effects/effects.test.ts",
        "modules/ui-web/src/shell-v0/substrates/effects/undoable.test.ts",
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.halt.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The undo association these tests set up moved from the retired in-memory side-map (markUndoableOperation(id, operationId, executionId)) to the journal entry itself (associateOperation(id, { executionId })); every entry these tests mark is an invoke-operation whose effect names the same operation id, so the observed undo effects, Undo affordances and correlation results are unchanged. The halt test's mock of the retired function is dropped because the controller no longer calls it.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Retire the memory-only undo side-map so the execution association is persisted with its journal entry, as the settled saved-state decision requires, keeping each existing undo oracle intact."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Four tests revealed routine rows by assigning the retired showRoutine flag; they now set the same reveal through the feed filter's explicit routine choice (filter.routine = 'show'). Inputs, frames and every assertion are unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Fold the routine reveal into the one feed filter (an explicit routine choice that overrides the quick filters) without changing what the existing ledger tests observe."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/substrates/effects/undoEntries.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Per settled challenger point 2 (adopted): undoEntries distinguishes Engine requests from reversals applied in the window, so its result gains a requested list; the old exact result shape {reversed, skipped} is replaced."
        }
      ],
      "conflictSearch": "Searched docs/decisions with rg -n -i \"operation-history|operations table|activity surface|undo.?all|undoEntries\" and \"seen.?cursor|recall cursor\": only an unrelated audit-policy row in 0030 matched (operation-history record emission). No decision record covers these oracles."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.render.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Per the settled S6 pieces 9-10 decision (one chronological projection joining ledger, operation history and journal), the separate Operations (structured) jf-resource panel is removed; its api-base forwarding and resource-id oracles are replaced by the feed reading history and ledger from the surface's api-base."
        }
      ],
      "conflictSearch": "Searched docs/decisions with rg -n -i \"operation-history|operations table|activity surface|undo.?all|undoEntries\" and \"seen.?cursor|recall cursor\": only an unrelated audit-policy row in 0030 matched (operation-history record emission). No decision record covers these oracles."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/substrates/recall/recallCursor.test.ts"
      ],
      "class": "New",
      "behaviour": "The shared seen cursor orders whole-second, millisecond and finer timestamps by the instant they name (a later millisecond mark is newer than a whole-second cursor, sub-millisecond digits count, an earlier whole-second mark never rewinds it); storage key and stored spelling are unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Compare parsed instants in the one seen cursor (settled point 11, broken before) and keep its key, raw stored value and monotonic meaning."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-activity-feed.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/substrates/recall/recallCursor.test.ts#orders whole-second, millisecond and finer timestamps by time, not by spelling",
        "modules/ui-web/src/shell-v0/substrates/recall/recallCursor.test.ts#a whole-second mark after a later millisecond mark does not rewind the cursor"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.actions.test.ts"
      ],
      "class": "New",
      "behaviour": "Save as macro takes the agent actions newer than the seen cursor by time: an action 100 ms after a whole-second cursor is included and one before it is not.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Keep recent-AI macro selection on the shared cursor while comparing instants (settled point 11)."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-activity-feed.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.actions.test.ts#S6 — \"Save as macro\" takes the AI actions after the seen cursor by time, across timestamp spellings"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/substrates/effects/undoEntries.test.ts"
      ],
      "class": "New",
      "behaviour": "A confirmed undo run returns UI reversals as reversed and reversals the Engine performs (an inverse that is another operation, an undo of an execution) as requested, never reversed; each request settles with the Engine's answer reported for its own journal entry. Runs of UI reversals only report no requests.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Distinguish requests dispatched from reversals confirmed (settled point 2, broken before)."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-activity-feed.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/substrates/effects/undoEntries.test.ts#a reversal the Engine performs is returned as requested, never reversed, and settles with the Engine answer",
        "modules/ui-web/src/shell-v0/substrates/effects/undoEntries.test.ts#reverses the previewed entries newest first and nothing recorded after the preview"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.test.ts"
      ],
      "class": "New",
      "behaviour": "Through the real Shell listener and client, Undo all AI actions on an Engine-undoable agent action first says the engine was asked, never 'Undid', then reports the Engine's answer: a declined approval as not undone, a success as confirmed.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Report Engine undo requests as asked and then answered, never success from dispatch (settled point 2; decided: the backend's refusal is shown as such)."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-activity-feed.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.test.ts#S6: undo-all says an Engine undo was asked for, then what the Engine answered (declined)",
        "modules/ui-web/src/shell-v0/chrome/Shell.test.ts#S6: undo-all says an Engine undo was asked for, then what the Engine answered (confirmed)"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.test.ts"
      ],
      "class": "New",
      "behaviour": "Two identical invocations answered in reverse order each record their own operationKey on their own journal entry; a keyed failure records its key without an execution; both associations are written to justsearch.effect-journal.v1.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Persist the existing operationKey and executionId associations on the dispatching journal entry, including keyed failures, without inventing success (settled saved-state decision; point 8)."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-activity-feed.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.test.ts#S6: identical invocations answering in reverse order each keep their invocation, a keyed failure joins as a failure, both persist"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.test.ts"
      ],
      "class": "New",
      "behaviour": "Identical concurrent agent calls completing in reverse order are journaled with their own operationKey and executionId; a failed keyed call journals nothing; a replayed run journals no effect and no association (the last passes before: preservation).",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Record the completed call's own invocation on its journal entry; keep failures and replay out of the journal (point 8)."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-activity-feed.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.test.ts#identical concurrent calls completing in reverse order each keep their own invocation; a keyed failure journals nothing",
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.test.ts#a replayed run journals no effect and no association"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/substrates/effects/associations.test.ts"
      ],
      "class": "New",
      "behaviour": "After a reload a restored execution association still lets the person request the backend undo and the invocation key is kept; legacy entries restore unchanged and unlinked; non-string or blank associations are dropped while the entry, its id and order are kept.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Restored associations restore the ability to request backend undo (decided); legacy and malformed entries stay safely unlinked (saved-state decision)."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-activity-feed.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/substrates/effects/associations.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.feed.test.ts"
      ],
      "class": "New",
      "behaviour": "The one feed: A1 a keyed operation in ledger, history and journal is one row with all three; A2 different keys stay apart and re-delivered ids stay one row; A3 unkeyed history joins only the exact legacy identity at raw precision, shared legacy identities are kept and disclosed, a legacy numeric journal id matching a backend effect keeps both rows labelled; A4 a local effect without a ledger copy reads Recorded locally and its later delivery joins without a second row; A5 a smaller reconnect snapshot drops ledger-only rows while history partners stay; A6 the bounded window is disclosed without an older-records control; arbitration (connected/heartbeat never claims empty, an empty snapshot does, an UPDATE before the snapshot survives, the GET seed gives way to the snapshot, a reset discards rows and a pre-reset GET cannot resurrect them); per-source loading, unavailable, partial and last-known states; A9 the Problems and Indexing tables including agent wire names, gates and grants, and outcome-unavailable; A10 failed per-file indexing reachable in Problems and Indexing despite routine, bursts counting failures and expanding to members; the explicit routine choice overriding quick filters; A11 proposal decisions filterable with the actor and never outcomes; the cursor with mixed precision and mark-all-read scope (passes before: preservation); two feeds with independent filters; resolver recovery; Today advancing at local midnight.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Build candidate 1 of the settled S6 pieces 9-10 design: exact-identity joins, the classification table and indexing set, quick and advanced filters, source states and arbitration, as amended by the adopted challenger points 1, 4, 5, 10, 11, 12, 14 and 15."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-activity-feed.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.feed.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.feed-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "In real Chromium: A16 1,040 loaded records (520 ledger, 520 local) are all in a windowed list reachable from newest to oldest, and a live arrival keeps the record being read in place; A19 on light and dark forced-colour palettes the selected quick filter has a heavier Highlight border, keyboard focus a solid Highlight outline, the unread dot stays visible in CanvasText with its label, and outcomes carry a glyph and word; A20 at 600x400 and 1280x800 with 200 percent text the quick filters, advanced controls and mark-read stay within the width without sideways scrolling, light and dark.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Check the feed's windowing, forced colours and narrow large-text layout in the browser, as scenarios A16, A19 and A20 require."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-activity-feed.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.feed-lockdown.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.render.test.ts"
      ],
      "class": "New",
      "behaviour": "Activity's one feed reads the ledger and the operation history from the surface's api-base and no separate operations table remains; an arrival request is taken only by an Activity still on screen, so a second Activity reveals it when the first closes; arriving at the journal clears journal filters that would hide it.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Keep the existing arrival requests and api-base forwarding while replacing the stacked operations table, with settled point 13's two-consumer and filter cases."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-activity-feed.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.render.test.ts#the one feed reads the ledger and the operation history from its own api-base",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.render.test.ts#an Activity closing before it renders leaves the request to another Activity, which reveals it",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.render.test.ts#arriving at the journal clears a journal filter that would hide what the pointer named"
      ]
    }
  ]
}
```

## Acceptance records
