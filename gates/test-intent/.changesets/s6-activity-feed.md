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
checks and pass before as well. After verification of candidate 1 (ef84062e5e), snapshot
arbitration in both streams, the history source's unavailable state and the undoable reset fixture
were repaired; those checks have their own fail-before evidence against ef84062e5e. After verification
of 81b02ecadf, index-state classification in burst summaries and resolved labels and keyboard reach
in the windowed feed were repaired; those checks have fail-before evidence against 81b02ecadf.
After a check against a real Engine at 85d57d405e, three defects were repaired, each with fail-before
evidence against 85d57d405e: Activity on a healthy, idle shared stream said its ledger showed
last-known records until the next event; the System tab panel did not scroll at all (no element held
the feed's overflow, and Health could not scroll either); and every command palette use added a No-op
row under Your actions. After the test-intent tie-break, the browser checks of A16, A19 and A20 are
separate entries; A19 asserts what a reader can tell apart in forced colours rather than exact
system colours, and A19 and A20 fail before at c09de15a10 with patches that remove only the
forced-colour indicators or only the controls' wrapping. The acceptance records below predate these
changes.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/EffectAuditLog.test.ts",
        "modules/ui-web/src/shell-v0/operations/ActionLedgerClient.test.ts",
        "modules/ui-web/src/shell-v0/substrates/effects/effects.test.ts",
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
        "modules/ui-web/src/shell-v0/substrates/effects/undoable.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation",
        "input re-encoding"
      ],
      "whatMoved": "The undo association moved from the retired in-memory side-map, which took the operation id as an argument for any entry (markUndoableOperation(id, operationId, executionId)), to the journal entry itself (associateOperation(id, { executionId })), where the operation id is the entry's own invoke-operation effect. The first test already journals an invoke-operation of core.file-operations, so only the call moved (instrumentation). The reset test journaled a noop entry and named operation 'op' in the side-map call; the candidate associates only invoke-operation entries, so that input is re-encoded as an invoke-operation entry of 'op' with execution 'e' (input re-encoding), the same association getUndoableOperation reported before reset at the baseline. A precondition assertion now shows that association ({ operationId: 'op', executionId: 'e' }) before the reset, which also holds at the baseline; the reset oracle (undefined after __resetJournalForTest) is unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Retire the memory-only undo side-map so the execution association is persisted with its journal entry, as the settled saved-state decision requires, keeping the association and reset oracles intact and observable."
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
        "modules/ui-web/src/shell-v0/views/HealthSurface.quiet.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation",
        "input re-encoding"
      ],
      "whatMoved": "The SH7 test's ledger() fixture handed the ledger reader's multiplex listener a hand-built reducer state, which was the bare array of retained ledger rows. The ledger reader's state is now the arbitrated stream state ({ rows, snapshotApplied, resets, unconfirmed }), so the bare array no longer reaches the controller (rows was undefined and the reader threw). The fixture now keeps the reducer the ledger reader registers with the multiplexer and folds each retained history through it as a snapshot frame ({ kind: 'snapshot', entries }) before calling the listener, which is what the shared multiplexed stream does (instrumentation). The same retained histories are delivered: the same keys, outcomes, operation id, originator and time, carried as snapshot entries instead of the old state array (input re-encoding). A snapshot is the Engine's current retention, so the empty history still drops the records it established, as the old array did. Every call to history(), every outcome the history reads return, the mark-read step and every assertion are unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Keep one reading of the ledger: System Health's status controller and the Activity feed both read the ledger through the same arbitrated reducer. The Health test therefore delivers its histories through that reducer instead of building the reducer's internal state itself, and keeps every SH7 expectation."
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
      "behaviour": "The one feed: A1 a keyed operation in ledger, history and journal is one row with all three; A2 different keys stay apart and re-delivered ids stay one row; A3 unkeyed history joins only the exact legacy identity at raw precision, shared legacy identities are kept and disclosed, a legacy numeric journal id matching a backend effect keeps both rows labelled; A4 a local effect without a ledger copy reads Recorded locally and its later delivery joins without a second row; A5 a smaller reconnect snapshot drops ledger-only rows while history partners stay; A6 the bounded window is disclosed without an older-records control; arbitration (connected/heartbeat never claims empty, an empty snapshot does, an UPDATE no snapshot has contained survives later snapshots in the ledger and the history until a reset, a record a snapshot contained leaves with a smaller one, the GET seed gives way to the snapshot, a reset discards rows and a pre-reset GET cannot resurrect them); per-source loading, unavailable (a history stream failing before its first snapshot, with Retry), partial and last-known states; A9 the Problems and Indexing tables including agent wire names, gates and grants, and outcome-unavailable; A10 failed per-file indexing reachable in Problems and Indexing despite routine, bursts counting failures and expanding to members; the explicit routine choice overriding quick filters; A11 proposal decisions filterable with the actor and never outcomes; the cursor with mixed precision and mark-all-read scope (passes before: preservation); two feeds with independent filters; resolver recovery; Today advancing at local midnight.",
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
      "behaviour": "In real Chromium, A16: 1,040 loaded records (520 ledger, 520 local) are all in a windowed list reachable from newest to oldest, and a live arrival keeps the record being read in place. Fail before at 716017c61f, which had no windowed one feed (the feed's quick-filter control the checks open on is absent).",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Check the feed's windowing in the browser, as scenario A16 requires (draft s6-9 section 7: 'Long live session exceeds 500 ledger and 500 local entries; virtualized navigation reaches oldest/newest loaded rows. Live arrivals preserve scroll position and selected details.')."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-activity-feed-a16.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.feed-lockdown.test.ts#A16 — more than 500 ledger and 500 local records are all reachable, newest to oldest, through a windowed list",
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.feed-lockdown.test.ts#A16 — a live arrival keeps the reader where they were"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.feed-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "In real Chromium on light and dark forced-colour palettes (A19), checked by what a reader can tell apart, not by which system colours are used: the quick filters are a radio group named Show activity whose radios have names; with focus elsewhere the selected filter differs from an unselected one in a property other than colour (border width, font weight, text decoration or outline); keyboard focus on a filter draws an outline it does not draw without focus; an unread record's mark keeps its image role and its label 'new since you looked', has a size, and is painted opaque in a colour other than the forced background; a failed outcome reads as a glyph and the word FAILURE. Fail before at c09de15a10 with gates/test-intent/evidence/s6-activity-feed-a19-before.patch, which removes only the forced-colour indicators (the selected filter's heavier border and weight, and the forced-colours rules: the Highlight border and outline and the unread mark's own paint): the selection checks fail because the selected and unselected filter look the same without colour (1px, weight 400 each), the unread checks because the mark is painted in the background colour. The focus outline and the outcome glyph are not what fails before: the patch keeps the focus outline (a forced palette recolours it but keeps it) and the outcome glyph predates this change.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Scenario A19 (draft s6-9 section 7: 'Forced colours distinguish selection, focus, unread and outcome without colour alone; controls retain accessible names and valid roles'), with these specific design choices: the selected quick filter is marked by a heavier border and bolder text as well as colour; keyboard focus is shown by an outline; unread is the existing dot with its label, which stays painted (in the system text colour) under forced colours; an outcome is the existing glyph and word. The check asserts the distinctions, not the exact border widths or system colours."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-activity-feed-a19.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.feed-lockdown.test.ts#A19 — on a light forced-colours palette, the selected quick filter and keyboard focus are marked by more than colour",
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.feed-lockdown.test.ts#A19 — on a dark forced-colours palette, the selected quick filter and keyboard focus are marked by more than colour",
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.feed-lockdown.test.ts#A19 — on a light forced-colours palette, the unread mark stays visible with its label, and an outcome reads as a glyph and a word",
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.feed-lockdown.test.ts#A19 — on a dark forced-colours palette, the unread mark stays visible with its label, and an outcome reads as a glyph and a word"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.feed-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "In real Chromium with text at 200 percent (A20), light and dark at 600x400 and light at 1280x800: with Activity's advanced filters open, the quick filters, the advanced filters' summary, the routine choice, Today only and mark-read all end within the scroller's width and there is no page-level sideways overflow. Fail before at c09de15a10 with gates/test-intent/evidence/s6-activity-feed-a20-before.patch, which removes only the wrapping of the controls row, the quick filters and the advanced filter groups: each case fails because the content is 1,325 pixels wide.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Scenario A20 (draft s6-9 section 7: 'At 600×400 with 200% text, filters and details actions remain reachable, sheet content scrolls, and there is no page-level sideways overflow. Repeat wide/light/dark states.'): the controls wrap onto further lines rather than widening the page."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-activity-feed-a20.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.feed-lockdown.test.ts#A20 — light at 600×400 with text at 200 percent: filters and advanced controls are reachable without sideways scrolling",
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.feed-lockdown.test.ts#A20 — dark at 600×400 with text at 200 percent: filters and advanced controls are reachable without sideways scrolling",
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.feed-lockdown.test.ts#A20 — light at 1280×800 with text at 200 percent: filters and advanced controls are reachable without sideways scrolling"
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
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.feed.test.ts"
      ],
      "class": "New",
      "behaviour": "Arbitration and source states repaired after verification of candidate 1 (fail before at ef84062e5e): a ledger UPDATE no snapshot has contained survives a reconnect snapshot that lacks it, and only a reset voids it; a history UPDATE newer than a snapshot that lacks it is kept, on the same or a later connection, while a reset voids it and later snapshots do not bring it back; the history stream failing before its first snapshot says Operation history could not be read (unavailable, distinct from loading, with partial activity from the ledger), a connected or heartbeat frame does not change that, Retry reopens the stream (loading), and a snapshot then reads it. A record a snapshot has contained still leaves with a smaller snapshot (preservation, covered by the whole-file check).",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Settled amendments 4 and 5 (adopted challenger points 4 and 5): a snapshot never erases an observation newer than the snapshot it replaces unless the source signals a reset, as the stream protocol defines (snapshot = current retention, reset = earlier state void); a source that fails before its first success says it is unavailable, with the stream's retry, never loading."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-activity-feed-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.feed.test.ts#a ledger UPDATE no snapshot has contained survives a reconnect snapshot that lacks it; only a reset voids it",
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.feed.test.ts#a history snapshot cannot erase a newer UPDATE it lacks; a reset voids it and later snapshots do not bring it back",
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.feed.test.ts#the history stream failing before its first snapshot says it is unavailable, not loading, until a snapshot reads it"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.feed.test.ts"
      ],
      "class": "New",
      "behaviour": "Index classification repaired after verification of candidate 1 (fail before at 81b02ecadf; the defect is also at 716017c61f): an indexing burst counts a record only in the category its state establishes (DONE indexed; FAILED and RETRY_EXHAUSTED failed), so one DONE and one unknown-state record read Indexed 1 · 1 with unknown state, never Indexed 2, and both members stay reachable, the unknown one named Index state unknown with its state; a row relabelled with its resolved file name keeps what its state established (Index failed, Index gave up, Index state unknown, Indexed), where before every resolved row read Indexed.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Scenario A9 as the verifier applied it: summaries and counts include a record only in the category its known state establishes; unknown states are counted and named as unknown, never folded into success; the members stay reachable. One classifier of index states serves the row label, the resolved-name label, the problem flag and the burst counts."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-activity-feed-repair2.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.feed.test.ts#A9 — a burst counts a record only as its state establishes; an unknown state is counted and named, never indexed",
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.feed.test.ts#A9 — a resolved file name keeps what the state established: failed, gave up and unknown never read as indexed"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.feed-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "Windowed feed keyboard reach repaired after verification of candidate 1 (fail before at 81b02ecadf): in real Chromium with 1,040 loaded records, at 1280x720 and at 600x400 with 200 percent text, after reading down the feed with the wheel and clicking a record, Ctrl+Home, Ctrl+End, Home and End focus the newest and the oldest loaded record, rendered and on screen; PageUp and PageDown move by a screenful, each step rendered, focused and on screen, until they reach the newest or oldest record. Before, Ctrl+Home stopped partway down with the newest record absent. With focus on the page instead (the wheel, then a click on the scroller beside the records, which takes no focus), the browser's own Ctrl+Home, Ctrl+End, Home and End take the feed's scroller to its start and its end, the newest record's start and the oldest record on screen, and PageUp and PageDown move it a page at a time without passing a row unseen until they reach either end; focus stays on the page. Before, Ctrl+Home with focus on the page stopped partway down. A live arrival keeps the reader in place (preservation, covered by the whole-file check).",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Scenario A16 as the verifier applied it, against the unwindowed list at 716017c61f that reached both ends: the feed handles Home, End, Ctrl+Home, Ctrl+End, PageUp and PageDown itself and focuses the entry reached (each entry is a list item with its position; one entry is in the Tab order), because the browser's smooth scroll for these keys is cut short when the windowed list corrects for newly measured rows, and the virtualizer's own scroll-to-index renders nothing inside an outer scroller that has moved. When focus is not in the feed's entries, the browser keeps these keys and chooses the scroller; once it has scrolled the feed's scroller and that scroll ends, the feed takes it the rest of the way at once (the start or end, held while rows are measured; a page of seven eighths of the view measured on an entry that was on screen), and leaves alone keys another handler took, text fields, open modals and the reader's own wheel, touch or click."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-activity-feed-repair2.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.feed-lockdown.test.ts#A16 — at 1280×720, Home, End, Ctrl+Home, Ctrl+End, PageUp and PageDown reach the newest and oldest loaded rows, rendered and focused",
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.feed-lockdown.test.ts#A16 — at 600×400 with text at 200 percent, Home, End, Ctrl+Home, Ctrl+End, PageUp and PageDown reach the newest and oldest loaded rows, rendered and focused",
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.feed-lockdown.test.ts#A16 — at 1280×720, with focus on the page, not the feed, the scrolling keys move the feed to its newest and oldest loaded rows",
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.feed-lockdown.test.ts#A16 — at 600×400 with text at 200 percent, with focus on the page, not the feed, the scrolling keys move the feed to its newest and oldest loaded rows"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.feed.test.ts"
      ],
      "class": "New",
      "behaviour": "Repaired after a check against a real Engine (fail before at 85d57d405e). Live state: the Shell's ledger readers subscribe the action-ledger channel of the shared shell-events stream at boot, so the Engine's connected and snapshot frames for that channel reach them, not Activity; an idle channel sends nothing more, and the connection's heartbeat travels on its own system:shell-events-heartbeat channel. Activity opened on that healthy, idle stream (frames sent exactly as MultiplexedSseWriter and SseEnvelopeWriter send them) shows the ledger as live at once, on every opening, with no 'live updates are not connected' notice; when the connection really drops the ledger says it shows last-known records and keeps them. Before, it said last-known until the next ledger event. Your actions: a command palette action that does its own work journals a no-op as the user's, and its ledger copy arrives by the effect ingest; Your actions no longer lists it, while witnessed navigation is still revealed there and All with routine rows shown still lists the no-op once. Before, every palette use added a No-op row under Your actions (at 716017c61f the no-op showed only behind the routine toggle, which this change keeps).",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Join a channel at its current state: the ledger source starts from what the shared channel has already established and whether the connection is up, so connection state comes from the stream's own connection signal and emptiness still only from a snapshot or a successful read (amendment 5). A no-op records that nothing was done, so it is not one of Your actions; All keeps showing it with routine rows as at 716017c61f."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-activity-feed-repair3.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.feed.test.ts#Activity opened on a healthy, idle stream is live at once, every time; a real drop shows last-known",
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.feed.test.ts#a command palette use is not one of Your actions: its journaled no-op stays with the routine rows of All"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.feed.test.ts"
      ],
      "class": "New",
      "behaviour": "Preservation on the shared stream as a real Engine sends it (passes before as well): with the connection up and the ledger channel's connected frame and heartbeats received but no snapshot and no answered read, the feed says it is loading and never claims an empty ledger; the channel's snapshot then establishes the empty ledger and the source is live.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Amendment 5 as settled: a heartbeat or connection alone never proves an empty source; the live-state repair must not weaken it."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-activity-feed-repair3.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.feed.test.ts#connected and heartbeat frames alone never claim an empty ledger; the snapshot does"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.scroll-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "Repaired after a check against a real Engine (fail before at 85d57d405e; the same at 716017c61f). In real Chromium with the real Shell opening System › Activity, whose 140 ledger records arrive over the shared stream as an Engine sends them, at 1280×720 and at 600×400 with 200 percent text: exactly one scroller holds the feed and it ends inside the window; the wheel and a touch drag over the feed move it; after clicking a record, Ctrl+End, Ctrl+Home, End and Home focus the oldest and the newest record, on screen; with focus on the page (a click on the Activity heading), the browser's Ctrl+End, Ctrl+Home, End and Home take that scroller to its end and its start. Before, no element scrolled: the tab panel clipped a member grown to its full content height. The Health and Logs tabs end inside the window and scroll in their own body; at 1280×720 the wheel moves Health's body. Before, Health had grown to its content height too and could not scroll. Named limit, not covered: at 600×400 with 200 percent text the Health and Logs windows' own fixed headers are taller than the tab, so their bodies start below the window.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "The member's own scroller owns the scroll, as the surface layout authority declares: the shared tab panel hands its height down as a flex column (the block wrapper added in #1096 broke that chain for every tab host). Activity scrolls as a whole, header and feed in one scroller, because a fixed header at 600×400 with 200 percent text leaves the feed about 24 pixels."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-activity-feed-repair3.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.scroll-lockdown.test.ts#at 1280×720, one scroller holds the Activity feed, and the wheel, touch and keys reach both ends",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.scroll-lockdown.test.ts#at 600×400 with 200% text, one scroller holds the Activity feed, and the wheel, touch and keys reach both ends",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.scroll-lockdown.test.ts#at 1280×720, the Health and Logs tabs keep their own scrolling",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.scroll-lockdown.test.ts#at 600×400 with 200% text, the Health and Logs tabs keep their own scrolling"
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
  "session": "01a1cb23-9c67-7590-8404-d16895df7d0d",
  "verdict": "accept",
  "digest": "sha256:a098bc0bfd48715bda483b371e08168beeae660170e0ac21e7183d37b8241db4"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1cb7a-d9ac-7e23-b364-4cb288c03c9d",
  "verdict": "accept",
  "digest": "sha256:4038c6eee90e75e461546c59f8a8b26b486c2710b2c64fa84895f68f5c4247e4",
  "note": "Test expectations and cited intent accepted. This is not product acceptance: independent Chromium verification fails amendment 13 when a hidden connected Activity consumes the section request before a visible consumer."
}
```

```json
{
  "kind": "audit",
  "role": "verifier",
  "session": "01a1cbc5-c82a-7d73-a1fd-1b5e7c4b7472",
  "verdict": "reject",
  "digest": "sha256:4038c6eee90e75e461546c59f8a8b26b486c2710b2c64fa84895f68f5c4247e4",
  "note": "Entries 13 and 14 lack independently checkable sources for all their new expectations. Entry 13 cites the settled design classification table and indexing set without recording their contents; the supplied s6-9-scenarios.md delegates A1-A20 to draft section 7 in results/s6-9/investigator-1, which is not provided. Entry 14 cites A16/A19/A20 without their contents and describes a testing strategy rather than the specific forced-colour and layout choices its assertions pin. Supply the referenced scenario/design text or explicit specific delegated choices covering those expectations. The other 19 entries pass this test-intent audit; no Adaptation hides a changed existing oracle. This is a source-warrant rejection, not a product defect verdict."
}
```

```json
{
  "kind": "tie-break",
  "role": "verifier",
  "session": "01a1cbd0-3470-7db2-a1bc-d56a39e068ef",
  "verdict": "reject",
  "digest": "sha256:4038c6eee90e75e461546c59f8a8b26b486c2710b2c64fa84895f68f5c4247e4",
  "note": "Entry 13 is warranted by the supplied draft section 3 classification table/indexing set, section 7 scenarios and settled amendments. Entry 14 remains rejected: draft A19 requires distinguishable selection, focus, unread and outcome without colour alone, but does not choose the asserted 2px selected/1px unselected borders, Highlight border and solid Highlight focus outline, or CanvasText unread fill. Its delegated-choice says to check these features in a browser, not to adopt these exact visual choices. The broad delegation permits making such choices; record the specific choices as an independently checkable source or test the sourced behavioural requirement. A16/A20 are otherwise supported. The remaining 20 entries pass this intent review; no Adaptation hides a changed existing oracle. This is a source-warrant rejection, not a product defect verdict."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1cbf3-d60a-7ce0-9e3b-b0efaa7cf450",
  "verdict": "accept",
  "digest": "sha256:0037471e716183fe68f0d94ec35af657c8373528b1d4989ca2386980523ffd15"
}
```

```json
{
  "kind": "audit",
  "role": "verifier",
  "session": "01a1cbfb-5213-7713-8b36-ddc6402b5ea5",
  "verdict": "accept",
  "digest": "sha256:0037471e716183fe68f0d94ec35af657c8373528b1d4989ca2386980523ffd15"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "acceptor-50547733-ecf2-44d9-8904-25b6121dd7d8",
  "verdict": "accept",
  "digest": "sha256:a5d368d82614b94e611f82cc206cf97100b5347ab4423fee4fbdb2b45fc4125b"
}
```

```json
{
  "kind": "audit",
  "role": "verifier",
  "session": "audit-cbf529e4-aca3-4425-a6b2-2352dee0f33e",
  "verdict": "accept",
  "digest": "sha256:a5d368d82614b94e611f82cc206cf97100b5347ab4423fee4fbdb2b45fc4125b"
}
```
