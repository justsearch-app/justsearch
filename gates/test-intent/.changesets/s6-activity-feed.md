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
