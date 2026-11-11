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

Repair after verification v1 (three defects): Escape with the keyboard on the page now closes Activity's details (Shell → System host → active member), a burst's details say its duration and source, and the rendered member oracle of the retired in-row expansion is restored at its new home, the details pane (ActivitySurface.group-details.test.ts). Entries 11 and 12 are reclassified Obsolete for the retired in-row assertions; the details-pane checks are New. Checks of what this repair fixes fail before at fedc486273 (the candidate before it); checks of the new home fail before at d3a21166b7; the page-Escape protections fail before against s6-activity-details-p3-before.patch (this repair's production code with only those protections removed, applied to fedc486273).

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
      "class": "Obsolete",
      "sources": [
        {
          "kind": "removal",
          "removed": [
            "modules/ui-web/src/shell-v0/components/ActionLedgerView.ts#toggleExpanded"
          ]
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "A17 as settled (the feed is a list box: no interactive control nested in a row, so a burst no longer expands in its row) and draft section 4 (a burst's members are read in its details). The two burst tests (A10, A9) keep their inputs and their in-row assertions (one burst, its label counting failures or the unknown state); only the in-row expansion and the member rows it drew are retired, and A10's title drops 'and expands to every member'. The rendered member oracle (counts, the 'Index state unknown (PAUSED) · docs' and 'Indexed · docs' labels, a resolved name and the short-hash fallback) moves to its new home, the details pane, as New checks in ActivitySurface.group-details.test.ts."
        }
      ],
      "conflictSearch": "Searched docs/decisions (54 records) with grep -rliE \"burst|scan rollup|rollup|expand(ed|s)? (to|the) (per-document|member)|ledger-burst|ledger-scan|toggleExpanded\": no match. \"escape\" matches only unrelated uses (0035 renderer escape hatches, 0013 Unicode escapes, 0030 and 0040 escape hatches). No decision record covers these oracles."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "removal",
          "removed": [
            "modules/ui-web/src/shell-v0/components/ActionLedgerView.ts#toggleExpanded"
          ]
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "A17 as settled (no interactive control nested in a row, so a scan rollup no longer expands in its row) and draft section 4 (a legacy scan's surviving per-document records are shown in its details). The test 'a scan rollup expands to the per-document rows OF THAT SCAN (matched by scanId)' observed only the in-row expansion (its control's aria-expanded and the child rows it drew), so it is removed whole. Its oracle (two scans, the resolver answer, scan-a's three documents not scan-b's two, the resolved 'scifact/report.pdf' and the short-hash fallback) moves to the details pane as a New check in ActivitySurface.group-details.test.ts."
        }
      ],
      "conflictSearch": "Searched docs/decisions (54 records) with grep -rliE \"burst|scan rollup|rollup|expand(ed|s)? (to|the) (per-document|member)|ledger-burst|ledger-scan|toggleExpanded\": no match. \"escape\" matches only unrelated uses (0035 renderer escape hatches, 0013 Unicode escapes, 0030 and 0040 escape hatches). No decision record covers these oracles."
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
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.group-details.test.ts"
      ],
      "class": "New",
      "behaviour": "A grouped record's members are read in the details pane, as rendered there (fail before at d3a21166b7, where Activity had no selection or details). A burst of a DONE and a PAUSED record lists both: 'Index state unknown (PAUSED) · docs' and 'Indexed · docs'. A burst of three, one failed, lists three: the member whose file resolved reads as its name ('Indexed · docs/report.pdf'), the others keep the short-hash label ('Index failed · docs (hash2)', 'Indexed · docs (hash3)'). A scan rollup lists that scan's documents, matched by scanId (scan-a's three, not scan-b's two), the resolved one as 'scifact/report.pdf' and the rest by short hash. A scan's details say its duration and source from its own rollup record (372 000 ms recorded: '6m 12s'; SYSTEM_INTERNAL: 'The engine').",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Draft section 4 as settled (a burst's members and a legacy scan's surviving members are shown in the record's details), A9 and A10 (a burst counts and names what each state establishes), tempdoc 812 D4 item 5 as the replaced ActionLedgerView tests stated it (a resolved row reads as a name; an unresolvable hash keeps its short-hash label), and the 14:10Z decision (details show each record's duration and source), here for a scan from the rollup record that is the scan's own audit record."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-activity-details-group-members.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.group-details.test.ts#a burst of a known and an unknown state lists both: the unknown state named, never as indexed",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.group-details.test.ts#a member whose file resolved reads as its name; one that did not keeps the short-hash label",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.group-details.test.ts#lists that scan's documents (matched by scanId, not another scan's): a resolved one by name, the rest by short hash",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.group-details.test.ts#says how long the scan took and where it came from, from its own rollup record"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.group-details.test.ts"
      ],
      "class": "New",
      "behaviour": "A burst's details say how long it took and where it came from, like every record's (fail before at fedc486273, where a burst's details omitted both). Duration: from the earliest member's start (its recorded time less its recorded duration) to the latest member's end (its recorded time), only when every member's duration is recorded; otherwise 'Not recorded', as an ordinary record says. Source: the source every member names, in the words an ordinary record uses; members naming different sources (or some none) give 'Several: ' and each, in the order the members are listed (newest first); none recorded gives 'Not recorded'. Index records without duration or transport: 'Not recorded' twice; two records of 4 s ending 10:00:10 and 3 s ending 10:00:20, both SYSTEM_INTERNAL: '14s' and 'The engine'; SCHEDULED and SYSTEM_INTERNAL with one duration missing: 'Not recorded' and 'Several: A schedule · The engine'.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "The 14:10Z decision (candidate 2's details pane shows each record's duration and source), applied to grouped records: a burst's duration is its members' span from first start to last end, known only when every member's is, and its source is the members' shared source or each of theirs; an unknown value is disclosed as 'Not recorded' exactly as an ordinary record discloses it."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-activity-details-group-facts.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.group-details.test.ts#members that record neither: the burst says both are not recorded, as an ordinary record does",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.group-details.test.ts#members that record both: from the first start to the last end, and the source they share",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.group-details.test.ts#members from different sources, one with a duration not recorded: each source named as the members are listed, the duration not recorded"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.page-escape-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "In real Chromium with the real Shell (System › Activity over the shared stream), Escape with the keyboard on the page (nothing focused) closes Activity's open details and returns the keyboard to the record they were for, as Escape inside the details does (fail before at fedc486273, where the details stayed open): at 1280×720 inline; after the windowed list dropped the record's row (the list brings it back); at 600×400 with 200% text, where the details are a sheet, and the window behind is live again; in forced colours, where the record then has no selection bar and shows the focus ring; and with System in the Split layout's second pane, once that pane is the window in use.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "A17 (Escape closes the details and returns the keyboard to the logical opener: the selected record, or the list when the record is no longer shown) applied to Escape with the keyboard on the page, where the Shell's DP1 page Escape already closes the details of the window in use (Search's, the Shell's window details); the decided sheet presentation below 980 px; forced colours keeping selected and focused records apart."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-activity-details-page-escape.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.page-escape-lockdown.test.ts#at 1280×720, Escape with the keyboard on the page closes Activity's details and returns the keyboard to the record",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.page-escape-lockdown.test.ts#the windowed list dropped the record's row: Escape on the page still returns the keyboard to that record",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.page-escape-lockdown.test.ts#at 600×400 with 200% text, Escape on the page closes the sheet, the window behind is live again, and the keyboard is on the record",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.page-escape-lockdown.test.ts#in forced colours, Escape on the page closes the details: the record loses its selection bar and shows the focus ring",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.page-escape-lockdown.test.ts#System in the second pane: Escape on the page closes Activity's details only when that pane is the window in use"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.page-escape-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "Escape on the page leaves Activity's details alone where it belongs to something else. A dialog (an elicit request), the command palette and Settings take Escape and close; Activity's details stay open with the record selected. With System in the Split layout's second pane and the other pane the window in use, Escape on the page leaves Activity's details open. With Activity not on show (another area is), Escape on the page changes nothing, and back on Activity its details are still open. These pass at fedc486273, which had no page Escape for Activity, so they fail before against s6-activity-details-p3-before.patch: this change's production code with only these protections removed (the Shell's dialog guard on page Escape, the choice of the window in use and of windows on show, and the System host's check that its member is on show).",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "The Shell's established Escape order (DP1 page Escape bubbles after anything more local, and stands down while a dialog, the palette or Settings is open: modalOwnsFocus), and A17 and amendment 10 for two panes: Escape on the page closes only the details of the window in use and on show."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-activity-details-page-escape-guards.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.page-escape-lockdown.test.ts#a dialog open: Escape is the dialog's, Activity's details stay open",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.page-escape-lockdown.test.ts#the command palette open: Escape is the palette's, Activity's details stay open",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.page-escape-lockdown.test.ts#Settings open: Escape is Settings', Activity's details stay open",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.page-escape-lockdown.test.ts#Activity not on show: Escape on the page leaves its details as they were",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.page-escape-lockdown.test.ts#System in the second pane: Escape on the page closes Activity's details only when that pane is the window in use"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.group-details.test.ts"
      ],
      "class": "New",
      "behaviour": "Activity details render Background indexing for an indexing record and burst; an unknown transport renders Another source with its raw code nowhere in the pane; every TransportTag enum value plus WORKER_INDEXER renders the specified source words.",
      "repaired": true,
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "Bring the aspects of the UI between the main windows up to date and remove bad practices and code implemented without an overarching design.",
          "choice": "Orchestrator decision, 2026-11-11: the Activity details pane never shows a raw backend source code. Map WORKER_INDEXER to Background indexing, URL_BAR to You, from the address bar, URL_DEEPLINK to A link from another app, PALETTE to You, from the command palette, RAIL to You, in the app, and PLUGIN_EMITTED to A plugin. Keep existing labels and missing-transport rules; unknown codes read Another source. Groups use these words under their existing shared/several rule.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-activity-details-sources.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.group-details.test.ts#an indexing record names Background indexing as its source",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.group-details.test.ts#an indexing burst names Background indexing as its shared source",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.group-details.test.ts#an unknown transport reads Another source and its raw code appears nowhere in the pane",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.group-details.test.ts#every backend transport and WORKER_INDEXER has source words in the rendered pane"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.group-details.test.ts"
      ],
      "class": "New",
      "behaviour": "Activity connected to an Engine at a separate address reads an indexing record's file name from that Engine (POST <apiBase>/api/library/path-label), never from the page's own origin, and the details pane shows the resolved name.",
      "repaired": true,
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "Bring the aspects of the UI between the main windows up to date and remove bad practices and code implemented without an overarching design.",
          "choice": "Settled Activity scenario amendment 14 (2026-11-11): related-file details work where the frontend and the Engine have different origins; file names resolve at the Engine Activity is connected to, as Library and the failed-files list already do.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-activity-details-resolver-origin.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.group-details.test.ts#Activity with a separate Engine address resolves the file name there, not at the page's own origin"
      ]
    }
  ]
}
```

## Acceptance records
