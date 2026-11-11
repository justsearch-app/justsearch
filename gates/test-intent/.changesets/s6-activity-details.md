---
schema: test-intent.v1
task: t-20261103-shell
author-role: builder
author-session: s6-activity-details-builder
---

# S6 pieces 9-10, candidate 2 — Activity details and capability placement

No acceptance is claimed; independent verification is pending. Base d3a21166b7 (the #1220 head).
Checks pinning a new home or the details' content fail before at d3a21166b7 because the baseline has
no selection or details. Checks pinning a protection inside the new surface fail before with a
compatibility patch: the candidate's production code with only that protection removed, applied to
d3a21166b7 (s6-activity-details-p1-before.patch for the happy-dom protections,
s6-activity-details-p2-before.patch for the browser ones). The amendment 6 check is a preservation
check and has no fail-before.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.details.test.ts"
      ],
      "class": "New",
      "behaviour": "The selected record's details and where per-record capabilities live (fail before at d3a21166b7, where Activity had no selection or details). The feed is a list box of option records with no control inside any row; a click, Enter or Space selects a record and opens its details in the shared jf-details-pane (marked for F6), and Escape closes them with the keyboard back on that record. Every record's details say when it happened, its duration (committed start to end) and its source (the recorded transport, in words), and which records it joins. A8: a receipt that is unknown or expired, or a read the Engine refuses, keeps the record and says what it means, never a failed execution; Try again reads it again. A7: a committed outcome stands over a receipt still reading running, which is shown as behind. A12/A14: Ask the engine to undo is a request until the Engine answers, a refusal is said as one and a second click asks nothing; Restore to this point and Undo this AI turn preview a fixed set, name what will remain, Cancel changes nothing, confirming runs once; the causation trace is in the record's details; records chosen in details are saved as a macro and exported in journal order, with the export confirmed in its control rather than a toast; an execution association recorded after the details opened brings Undo at once; Import journal archive and Undo last AI action stay Activity utilities. Amendment 10: a backend copy of another window's effect has no proposal, undo, restore or choice and says so; two Activities keep their own selections. Amendment 14: a resolved file is named; Open file and Show in folder appear only in the desktop app, and an unresolved file offers neither until it resolves. A10: a burst's details list every member and how many failed. A15/amendment 13: the journal arrival resets the feed's filters to this window's records and puts the keyboard there, also when one of two Activities closes before it renders. A selected record no source holds any more stays readable with its last-known content, says so and offers no actions.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Draft section 4 as settled (details per record kind; per-record actions in details: receipt, backend Undo, causation, restore-to-point, undo-turn, related files, run link; AI-wide undo, own reversals, background runs and journal archive import stay utilities; journal members chosen in details are saved or exported in journal order), the 14:10Z decision that details show each record's duration and source, settled amendments 3, 10, 13 and 14, and A8, A10, A12, A14, A15 and A17 for details. The receipt states are worded from OperationOutcomeView's own states; Open file and Show in folder are offered only where platform.ts can act (the desktop runtime); a record evicted from every source keeps its last-known details without actions (the loaded-record lifetime of amendment 15 applied to a selection)."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-activity-details.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.details.test.ts#a click selects the record and opens the shared details pane; rows hold no controls of their own",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.details.test.ts#Enter and Space on a focused record select it; Escape closes the details and returns focus to that record",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.details.test.ts#an operation shows its committed duration and its source, and which records it joins",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.details.test.ts#the history arrives first: a receipt still reading running is shown as behind, not as the outcome",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.details.test.ts#a receipt that is unknown keeps the record and says what it means",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.details.test.ts#a receipt that is expired keeps the record and says what it means",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.details.test.ts#a refused read says the receipt could not be read, and Try again reads it",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.details.test.ts#Ask the engine to undo is a request until the Engine answers; a refusal is said as one, and a second click asks nothing",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.details.test.ts#Restore to this point previews a fixed set, says what will remain, Cancel changes nothing, and confirming runs once",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.details.test.ts#Undo this AI turn and the causation trace are in the turn member's details",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.details.test.ts#records chosen in details save as a macro and export in journal order, whatever order they were chosen in",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.details.test.ts#Import journal archive stays an Activity utility and keeps a pasted archive as a macro",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.details.test.ts#Undo last AI action stays an Activity utility and names what it undid",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.details.test.ts#an execution association recorded after the details opened brings its Undo there at once",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.details.test.ts#a backend copy of another window's effect has no proposal, undo, restore or choice",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.details.test.ts#two Activities keep their own selections",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.details.test.ts#in a browser a resolved file is named, and Open file / Show in folder are said to be desktop-only",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.details.test.ts#in the desktop app a resolved file offers Open file and Show in folder; an unresolved one offers neither until it resolves",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.details.test.ts#a burst counts its failures and its details list every member",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.details.test.ts#a pointer to the journal resets the feed's filters to this window's records and puts the keyboard there",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.details.test.ts#an Activity closing before it renders leaves the request to another, which reveals it",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.details.test.ts#stays readable with its last-known content, says so, and offers no actions"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.details.test.ts"
      ],
      "class": "New",
      "behaviour": "The protections of the details, each failing before on its own assertion with gates/test-intent/evidence/s6-activity-details-p1-before.patch (the candidate's production code with only these protections removed, applied to d3a21166b7). A7/amendment 3: a locally keyed invocation selected before its history and ledger copies arrive converges into one row that stays selected and focused, through the identity it had, with the committed outcome. A18: a receipt answer for an earlier selection, or an older running read answering after a newer completed one, never shows. Amendment 7: the current run's session-filtered records that the general ledger read does not return show in the run scope while the ledger read fails, and a read for the previous run answering after the run switched is not taken. Amendment 9: a profile switch with details, a receipt read and a restore preview open closes them without confirming anything; the late receipt changes nothing, and the records stay as they were, none selected or relabelled.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Settled amendments 3 (receipt convergence, no terminal regression, stale reads), 7 (current-run scope keeps the session-filtered read's reachability, including when the stream is down; a previous session's delayed response is not accepted) and 9 (a profile switch keeps the records' cross-profile ownership and explicitly settles selection, previews and pending callbacks), and A18 (rapid selection cannot show a stale receipt). Settling on a switch means closing: a preview drawn before the switch is never confirmed after it."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-activity-details-protections.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.details.test.ts#the receipt completes before the history arrives: the selection and focus follow the join into one row",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.details.test.ts#A18 — an older running read answering after a newer completed one, for an earlier selection, shows nothing stale",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.details.test.ts#the run read's records outside the general window show in the run scope; a previous run's late answer is not taken",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.details.test.ts#closes them without confirming anything, keeps every record, and relabels none"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.details.test.ts"
      ],
      "class": "New",
      "behaviour": "Amendment 6, preservation (passes before as well, where no details read existed): a receipt read answered after its Activity closed changes nothing in the reopened Activity and does not move focus.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Settled amendment 6: leaving Activity during reads and reopening it, obsolete callbacks cannot alter the replacement view or steal focus."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-activity-details-lifetimes.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.details.test.ts#a receipt read answered after Activity closed does not reach the reopened Activity or move focus"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.details-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "In real Chromium with the real Shell opening System › Activity over the shared stream as an Engine sends it (fail before at d3a21166b7, which had no details): at 1280×720 in the light and the dark theme, a clicked record's details sit beside the feed (inline, the window not inert, no sideways page overflow), the keyboard stays on the record, Tab moves on into the details, F6 from the record reaches the details pane's tab, and Escape closes them with the keyboard back on the record.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "A17 (keyboard selects rows, reaches details via Tab/F6, Escape returns to the opener), the decided placement (the shared jf-details-pane, inline from 980 px of window width) and A20's repeat of the wide light and dark states."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-activity-details-browser.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.details-lockdown.test.ts#at 1280×720 (light), a record's details sit beside the feed; Tab and F6 reach them; Escape returns to the record",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.details-lockdown.test.ts#at 1280×720 (dark), a record's details sit beside the feed; Tab and F6 reach them; Escape returns to the record"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.details-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "Browser protections, each failing before on its own assertion with gates/test-intent/evidence/s6-activity-details-p2-before.patch (the candidate's production code with only these removed, applied to d3a21166b7). A17: once the windowed list has dropped the selected record's row, Escape still returns the keyboard to that record. A20: at 600×400 with 200% text the details are a sheet, the window behind is inert, opening it moves the keyboard into it, Tab reaches Restore to this point and the sheet's content scrolls to bring it into view, with no sideways page overflow, and Escape returns to the record. A19: in forced colours the selected record has a bar at its start that no other record has, and the focused record a focus ring, so selection and focus are told apart without colour.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "A17 (if virtualization removed the opener, focus returns to the logical row), A19 (forced colours distinguish selection and focus without colour alone), A20 (at 600×400 with 200% text details actions remain reachable, the sheet scrolls, no page-level sideways overflow). Named visual choice: selection is a bar at the record's start (twice the focus-ring width, accent; Highlight in forced colours) plus a tint, and only the selected record has that border, because forced colours paint every border."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-activity-details-browser-protections.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.details-lockdown.test.ts#the windowed list dropped the record's row: Escape still returns the keyboard to that record",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.details-lockdown.test.ts#at 600×400 with 200% text, the details are a sheet: the window behind is inert, the sheet scrolls to its actions, and nothing overflows sideways",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.details-lockdown.test.ts#in forced colours, the selected record and the focused record are told apart without colour"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.render.test.ts"
      ],
      "class": "New",
      "behaviour": "Activity shows background runs and the one feed, with no separate effect-journal list and no separate This run list (fail before at d3a21166b7, where both were stacked lists); a pointer to the effect journal lands on the feed scoped to what this window recorded, with the keyboard there, whether Activity was up yet or not and also when one of two Activities closes before it renders; arriving there resets every feed filter (quick, actor, proposal, date) to that scope. The background-runs pointer case of the same table is unchanged and passes before.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "The decided removal of the stacked lists once each capability is reachable from the feed, details or a utility, and the draft's direction that the effect-journal arrival goes to the local-effect scope of the feed, with settled amendment 13's two-consumer and filter cases."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-activity-details-arrivals.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.render.test.ts#shows background runs and the one feed; the journal and This run are scopes of the feed, not lists of their own",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.render.test.ts#a pointer to effect-journal lands on that section, whether Activity is up yet or not",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.render.test.ts#an Activity closing before it renders leaves the request to another Activity, which reveals it",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.render.test.ts#arriving at the journal clears a filter that would hide what the pointer named"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.render.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "removal",
          "removed": [
            "modules/ui-web/src/shell-v0/components/EffectAuditLog.ts"
          ]
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "The stacked effect-journal list (jf-effect-audit-log) is retired: its records are the feed's local scope and its per-record actions are in the details. The old oracles (the journal panel present with its undo controls, the arrival focusing that panel, its originator/outcome selects reset on arrival) are replaced by the New checks of this file."
        }
      ],
      "conflictSearch": "Searched docs/decisions (54 records) with grep -rniE \"effect.?journal|audit.?log|this run|causation turn|journal archive|jf-effect-audit-log|groupJournalForDisplay\": the only match is \"on this runner's intermittent uptime\" in 0026 (manual CI triggering), unrelated. \"details pane\" and \"activity\" match no record about Activity. No decision record covers these oracles."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/EffectAuditLog.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "removal",
          "removed": [
            "modules/ui-web/src/shell-v0/components/EffectAuditLog.ts"
          ]
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "The stacked journal list is removed (decided). Each capability its tests guarded has a new home with its own check: causation chip and why trace -> details trace (Undo this AI turn and the causation trace...); undo turn -> details; restore to here and its irreversible remainder -> details (Restore to this point...); Undo for an undoable entry and an association arriving later -> details (Ask the engine to undo..., an execution association recorded after...); Undo last AI action -> Activity utility (Undo last AI action stays an Activity utility...); Undo all AI actions preview and Cancel -> the existing Activity utility checks in ActivitySurface.actions.test.ts; Export selected with an in-control receipt and Save selected as macro -> details' chosen records (records chosen in details...); Import archive -> Activity utility (Import journal archive stays...); the empty and filtered-empty journal copy -> the feed's local scope. Named removals: the journal's own originator/outcome selects (the feed's advanced who and proposal filters cover both), the collapsible causation-turn grouping of the list (a turn's steps are shown by the details' trace and the Undo this AI turn preview), and the unavailable-state of AI undo buttons (Activity shows its AI undo utilities only when a reversible AI action exists, as before this change)."
        }
      ],
      "conflictSearch": "Searched docs/decisions (54 records) with grep -rniE \"effect.?journal|audit.?log|this run|causation turn|journal archive|jf-effect-audit-log|groupJournalForDisplay\": the only match is \"on this runner's intermittent uptime\" in 0026 (manual CI triggering), unrelated. \"details pane\" and \"activity\" match no record about Activity. No decision record covers these oracles."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/substrates/effects/journalView.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "removal",
          "removed": [
            "modules/ui-web/src/shell-v0/substrates/effects/journalView.ts"
          ]
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "groupJournalForDisplay folded the journal into causation turns for the stacked journal list only; with that list retired nothing renders turns, so the helper is retired with it (displaced code)."
        }
      ],
      "conflictSearch": "Searched docs/decisions (54 records) with grep -rniE \"effect.?journal|audit.?log|this run|causation turn|journal archive|jf-effect-audit-log|groupJournalForDisplay\": the only match is \"on this runner's intermittent uptime\" in 0026 (manual CI triggering), unrelated. \"details pane\" and \"activity\" match no record about Activity. No decision record covers these oracles."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/RunActivity.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "removal",
          "removed": [
            "modules/ui-web/src/shell-v0/components/RunActivity.ts#thisRun"
          ]
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "The separate This run list is replaced by the feed's run scope (draft section 4: replace the duplicate This run list with a session scope over the merged feed), whose reachability and failed-read behaviour are checked in ActivitySurface.details.test.ts (the run read's records ... show in the run scope ...). The Background runs tests of this file are unchanged."
        }
      ],
      "conflictSearch": "Searched docs/decisions (54 records) with grep -rniE \"effect.?journal|audit.?log|this run|causation turn|journal archive|jf-effect-audit-log|groupJournalForDisplay\": the only match is \"on this runner's intermittent uptime\" in 0026 (manual CI triggering), unrelated. \"details pane\" and \"activity\" match no record about Activity. No decision record covers these oracles."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.feed.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation",
        "rename"
      ],
      "whatMoved": "Two burst tests (A10 and A9) reached a burst's members by clicking its in-row expand control and reading the expanded member rows; rows hold no controls now, so they select the burst (a click on its entry, as its owner would hold the selection) and read the members the feed publishes for that selection, which the details list. Inputs (the same index rows and Indexing filter), the burst label assertions, the member count (3; 2) and the member labels ('Index state unknown (PAUSED) · docs', 'Indexed · docs') are unchanged. The A10 title changed from 'expands to every member' to 'its details list every member'.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "A17 as settled (no nested interactive row controls; a burst's surviving members are reached in its details, draft section 4 index/legacy scan row)"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation",
        "rename"
      ],
      "whatMoved": "The scan rollup test reached the per-document rows of a scan by its in-row expand control; it now selects the rollup's entry and reads the members the feed publishes for that selection (shown in the details). The two scans, the resolver answer, the count (scan-a's three documents, not scan-b's two) and the label checks (the resolved 'scifact/report.pdf' and the short-hash fallback) are unchanged. The control's aria-expanded false/true became the entry's aria-selected false/true, because the record is now selected, not expanded. The title changed from 'expands to the per-document rows' to 'lists the per-document rows ... in its details'.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "A17 as settled (no nested interactive row controls) and draft section 4 (a legacy scan's surviving members are shown in its details)."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.scroll-lockdown.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "A click on a record now also opens its details (a sheet at 600×400, which takes the keyboard). After the click the test presses Escape, which closes the details and returns the keyboard to that record, so the following Ctrl+End, Ctrl+Home, End and Home start from the record as before. Inputs, the click, the 'a click on a record focuses it' assertion and every later assertion are unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "A17 (selecting a record opens its details; Escape returns to the opener) and the decided sheet presentation below 980 px."
        }
      ]
    }
  ]
}
```

## Acceptance records
