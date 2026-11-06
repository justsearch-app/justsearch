---
schema: test-intent.v1
task: t-20261103-shell
author-role: builder
author-session: shell-s4-builder
---

# Details pane: every window has at most one, and the frame has no side drawers (S4)

One shared `jf-details-pane` frame (tabs, close, 360-560 px, inline from 980 px of window width, a
sheet below). Search composes it in `jf-sv3-pane` with a tab per cited source, Context and plugin
tabs. Any other window gets `jf-window-details`, so `host.ui.showInspector` opens beside the work.
Escape or close returns focus to the opener; tab and selection are remembered per conversation in a
new userState slice. Space previews the focused search result.

Drawer homes: Sources → source tabs (hide and restore in the pane's footer); context inspector →
Context tab; failed files → `jf-failed-files-list` under the folder in the shared row; retrospective
→ Search's list (sessions) and Activity (background runs, this run; its system activity tab was the
same ledger Activity already shows); effect journal → a section of Activity; narrow frame reader →
the window's own pane. The `right-drawer` slot and `--z-overlay-drawer` are gone.

Old → new mapping (kept unless marked retired):

| Old check | New owner |
| --- | --- |
| `RetrospectivePanel.test.ts` one shared controller | `RunActivity.test.ts` |
| Retrospective Resume/Replay and interruption rows | `Sv3AgentRuns.test.ts` |
| Retrospective load path settles without looping | `Sv3AgentRuns.test.ts` |
| Retrospective lifecycle groups, count badge | `RunActivity.test.ts` |
| Retrospective scope lines and empty states | `RunActivity.test.ts`, `Sv3AgentRuns.test.ts`; the System activity line retired with the tab: `jf-action-ledger` is that list |
| `openRetrospectiveAt` selects a tab | `ActivitySurface.render.test.ts` (a pointer lands on its section) |
| `retrospectiveDrawer`, `sourcesDrawer`, `contextInspectorDrawer`, `failedJobsDrawer` store checks | Retired: the stores are removed; opening is pinned at each new home |
| `SourcesPane.test.ts` hide/restore and channel wording | `SearchV3View.pane.test.ts` DP5 block |
| Sources cross-highlight | `SearchV3View.pane.test.ts` (source tab marks the answer) |
| Sources document-level row, docked rail, Open all, bounded index, card click, disposition POST | Retired: no list of cards remains; `jf-sources-pane` was mounted only as the frame drawer, which nothing opened |
| Context inspector phases, total, empty state | `ContextInspectorPane.test.ts` |
| `FailedJobsDrawer.test.ts` load, states, scan, PROD posture, R3d, races, feedback | `FailedFilesList.test.ts` |
| Failed files layout | `FailedFilesList.layout.test.ts` |
| Per-row success drops the row; Retry all clears the list | Retired: the inline list's rule (R3d) reads the list again rather than assuming; pinned in `FailedFilesList.test.ts` |
| Inline list never removes the global drawer registration | Retired: no global drawer remains |
| Effect journal checks | `EffectAuditLog.test.ts`; Escape sync and focus restore retired with the dialog |
| Document reader `pane-close` | `DetailsPane.test.ts` (close raises one event, inside the window) |
| `loadTimeline` | Retired with the System activity tab; the ledger's own client is pinned by `ActionLedgerView.test.ts` |
| `responsiveState.test.ts` viewport authority | Retired: the module's one consumer was the narrow frame reader; each window now measures its own box, pinned in `WindowDetails.test.ts` and the lockdown suite |
| (no old check) the frame hands the plugin host to the shared agent controller | `Shell.details.test.ts`; the retired drawers did this as a side effect of rendering |

Persisted format: `detailsByConversation` in `justsearch.userState.v2` (optional, cross-profile,
validated entry by entry, 50 conversations). The width key is unchanged; a stored width above 560
reads back as 560.

Fail-before: `shell-s4-repaired.json` runs against 1544c90ce5. The compatibility patch
`gates/test-intent/compat/shell-s4-failed-files-list.patch` only names the baseline's inline list so
the C4 checks load. `shell-s4-c4-preservation.json` holds the race checks that already passed.
`shell-s4-all.json` re-executes every check in every added or edited test file. Evidence runs use a
shared clone in place of a registered worktree and pass `--maxWorkers=4`, through temporary command
adapters; the evidence producer itself is unchanged, and no source Git metadata is touched.

All added checks are durable; none is scaffolding.

Repair round (against 3c4f7f42df): `Shell.details-lockdown.test.ts` drives the real Shell, Stage, Search,
Library, Browse and AI Brain in Chromium over a stubbed backend, with wide-font variants for the tabs.
`shell-s4-round2-repaired.json` runs against 3c4f7f42df; `shell-s4-round2-preservation.json` holds the
checks that already passed there. X3 and X4 are sourced as delegated choices: their scenario wording
was not in the repair brief. No existing test was edited.

Repair round 3 (against 05d31c437e): `shell-s4-round3-repaired.json` holds the checks of the eight repairs
with their fail-before; `shell-s4-round3-preservation.json` holds those that already passed there. Three test
files were edited (`Shell.details-lockdown`, `DetailsPane.frame-lockdown`, `SearchV3View.pane`), so
`shell-s4-all.json`, `shell-s4-repaired.json`, `shell-s4-round2-repaired.json` and
`shell-s4-round2-preservation.json` were re-run against their original baselines, with the same adapters. An
existing expectation changed only in the three places the Obsolete entry names.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.pane.test.ts"
      ],
      "class": "New",
      "behaviour": "Escape or the close control returns the keyboard to the citation that opened the pane. Before: the pane left and focus fell to the page.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP1",
          "quote": "Search's details pane on the right shows tabs per cited source plus Context (what the assistant sees); inline when Search is ≥ 980 px wide, a sheet over Search below; resizable within limits; Escape or close returns focus to the citation or result that opened it; state remembered per conversation.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s4-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.pane.test.ts#Escape returns focus to the citation that opened it",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.pane.test.ts#the close control returns focus the same way"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.pane.test.ts"
      ],
      "class": "New",
      "behaviour": "A profile change closes Search's pane. Before: the citation stayed on show across the change.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "C4",
          "quote": "Details ownership and races: in a split layout each window's details show its own selection; rapid selection changes, close/reopen and profile changes never show another window's or another folder's details; a delayed response for folder A never fills folder B (repairs today's failed-files folder-switch defect).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s4-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.pane.test.ts#a profile change closes the pane"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/FailedFilesList.folderSwitch.test.ts"
      ],
      "class": "New",
      "behaviour": "The render after a folder switch shows nothing of the previous folder: rows and errors go before the first frame, not after it.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "C4",
          "quote": "Details ownership and races: in a split layout each window's details show its own selection; rapid selection changes, close/reopen and profile changes never show another window's or another folder's details; a delayed response for folder A never fills folder B (repairs today's failed-files folder-switch defect).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s4-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/FailedFilesList.folderSwitch.test.ts#the render after a folder switch already shows none of the previous folder",
        "modules/ui-web/src/shell-v0/components/FailedFilesList.folderSwitch.test.ts#an error for the previous folder does not stay under the new one"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/FailedFilesList.folderSwitch.test.ts"
      ],
      "class": "New",
      "behaviour": "Preservation: late replies and failures for a folder left behind never reach the folder on show, whatever order they land in.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "C4",
          "quote": "Details ownership and races: in a split layout each window's details show its own selection; rapid selection changes, close/reopen and profile changes never show another window's or another folder's details; a delayed response for folder A never fills folder B (repairs today's failed-files folder-switch defect).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s4-c4-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/FailedFilesList.folderSwitch.test.ts#a delayed response for folder A never fills folder B",
        "modules/ui-web/src/shell-v0/components/FailedFilesList.folderSwitch.test.ts#a delayed failure for folder A says nothing under folder B",
        "modules/ui-web/src/shell-v0/components/FailedFilesList.folderSwitch.test.ts#rapid switching settles on the last folder, whichever reply lands first"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/detailsPane/DetailsPane.test.ts"
      ],
      "class": "New",
      "behaviour": "The shared frame: tablist semantics, roving keys, close and backdrop events kept inside the window, 360-560 px and the 980 px switch, width memory; plugin tabs in registry order with `when` honoured, text kept as text, a thrown render costing that tab alone.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP1",
          "quote": "Search's details pane on the right shows tabs per cited source plus Context (what the assistant sees); inline when Search is ≥ 980 px wide, a sheet over Search below; resizable within limits; Escape or close returns focus to the citation or result that opened it; state remembered per conversation.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP4",
          "quote": "Plugin inspector tabs (registered, rendered nowhere today) appear as details-pane tabs.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "One shared jf-details-pane frame with a 360-560 px width, inline at 980 px of window width and a sheet below; a plugin's string renders as text.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s4-all.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/detailsPane/DetailsPane.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/detailsPane/WindowDetails.test.ts"
      ],
      "class": "New",
      "behaviour": "Outside Search a selection opens in that window's own pane, sized and closed like Search's; each window keeps its own document through a second selection, a close, a reused element, rapid selection and a profile reset.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP3",
          "quote": "Frame-level right drawers retired: Sources drawer and context inspector → Search pane tabs; failed-files drawer → inline under the folder in Library (coordinated with the Library redesign, L5/L13); retrospective → Search's list (sessions) and System › Activity (system activity, background runs); effect journal → System › Activity; narrow frame reader → the details pane of the window showing it.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP5",
          "quote": "Citation selection, hide/restore of sources from the assistant's context, document reader modes and failure states, host.ui.showInspector() signalling.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "C4",
          "quote": "Details ownership and races: in a split layout each window's details show its own selection; rapid selection changes, close/reopen and profile changes never show another window's or another folder's details; a delayed response for folder A never fills folder B (repairs today's failed-files folder-switch defect).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "host.ui.showInspector outside Search opens the pane of the window the reader is in, beside the work, rather than navigating to Search.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s4-all.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/detailsPane/WindowDetails.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/detailsPane/DetailsPane.frame-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "Real Chromium: inline and sheet sizes at 1280x800, 900x800 and 600x400 with no sideways overflow, a wide-font variant, a window narrowed while the pane is open, the grip's limits, focus into the pane and back to the opener, and the Space preview on screen.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP1",
          "quote": "Search's details pane on the right shows tabs per cited source plus Context (what the assistant sees); inline when Search is ≥ 980 px wide, a sheet over Search below; resizable within limits; Escape or close returns focus to the citation or result that opened it; state remembered per conversation.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP2",
          "quote": "Space previews the focused search result (a floating card); Space again or Escape closes it.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "X1",
          "quote": "Dark, light, forced colours; 1280×800, 900×800, 600×400 without sideways overflow; no new hard-coded colours, sizes or z-indexes; ui-a11y-gate shows no new violations.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s4-all.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/detailsPane/DetailsPane.frame-lockdown.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/Sv3Results.preview.test.ts"
      ],
      "class": "New",
      "behaviour": "Space opens the floating card on the focused row and Space or Escape closes it; Enter opens the file and the right arrow opens details; the card leaves with its result; it never fires from a control in the row, a text field, a composition, a repeat, a modifier or under a modal.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP2",
          "quote": "Space previews the focused search result (a floating card); Space again or Escape closes it.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "C8",
          "quote": "Keyboard ownership: Space preview never fires inside text fields, during IME composition, on key repeat, or under a modal.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "Enter and the right arrow act on the previewed result, as the design's card footer says; a live row takes the keyboard whether or not forget is available.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s4-all.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/Sv3Results.preview.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/Sv3AgentRuns.test.ts"
      ],
      "class": "New",
      "behaviour": "Agent runs at the end of Search's list: Resume only on resumable runs, Replay and Export on the rest, the interruption lines, a closed section reading nothing, and failed and stale reads that keep rows and say so.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP3",
          "quote": "Frame-level right drawers retired: Sources drawer and context inspector → Search pane tabs; failed-files drawer → inline under the folder in Library (coordinated with the Library redesign, L5/L13); retrospective → Search's list (sessions) and System › Activity (system activity, background runs); effect journal → System › Activity; narrow frame reader → the details pane of the window showing it.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "Failed and stale states for the session list: a failed first read says so and offers Try again; a failed refresh keeps the rows and says when they were last seen.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s4-all.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/Sv3AgentRuns.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/RunActivity.test.ts"
      ],
      "class": "New",
      "behaviour": "Background runs and This run in Activity: lifecycle groups, scope lines, empty states, a count that is never a false zero, replay going to Search, the launcher, and failed and stale reads.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP3",
          "quote": "Frame-level right drawers retired: Sources drawer and context inspector → Search pane tabs; failed-files drawer → inline under the folder in Library (coordinated with the Library redesign, L5/L13); retrospective → Search's list (sessions) and System › Activity (system activity, background runs); effect journal → System › Activity; narrow frame reader → the details pane of the window showing it.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "This run lands in Activity beside Background runs, by the design's rule that history lives in System › Activity; failed and stale states as in Search's list.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s4-all.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/RunActivity.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.failedFiles.test.ts"
      ],
      "class": "New",
      "behaviour": "Each folder's failed files list under its own row, in default and themed rows; the chip and the folder's problem both land on the list; a folder that fails to load says so without touching the other; one folder's late reply never fills another.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP3",
          "quote": "Frame-level right drawers retired: Sources drawer and context inspector → Search pane tabs; failed-files drawer → inline under the folder in Library (coordinated with the Library redesign, L5/L13); retrospective → Search's list (sessions) and System › Activity (system activity, background runs); effect journal → System › Activity; narrow frame reader → the details pane of the window showing it.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "C4",
          "quote": "Details ownership and races: in a split layout each window's details show its own selection; rapid selection changes, close/reopen and profile changes never show another window's or another folder's details; a delayed response for folder A never fills folder B (repairs today's failed-files folder-switch defect).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "The list shows under a failing folder automatically, as the attention band showed it, so retained last-known failures stay visible; the chip and the problem take the reader to it.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s4-all.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.failedFiles.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details.test.ts"
      ],
      "class": "New",
      "behaviour": "The frame has no right-drawer slot and mounts no drawer; Search composes its own pane and any other window gets one; showInspector stays in the window, a selection belongs to the window holding the keyboard, and the audit-log command arrives at Activity.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP3",
          "quote": "Frame-level right drawers retired: Sources drawer and context inspector → Search pane tabs; failed-files drawer → inline under the folder in Library (coordinated with the Library redesign, L5/L13); retrospective → Search's list (sessions) and System › Activity (system activity, background runs); effect journal → System › Activity; narrow frame reader → the details pane of the window showing it.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP5",
          "quote": "Citation selection, hide/restore of sources from the assistant's context, document reader modes and failure states, host.ui.showInspector() signalling.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "C4",
          "quote": "Details ownership and races: in a split layout each window's details show its own selection; rapid selection changes, close/reopen and profile changes never show another window's or another folder's details; a delayed response for folder A never fills folder B (repairs today's failed-files folder-switch defect).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s4-all.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/detailsMemory.test.ts"
      ],
      "class": "New",
      "behaviour": "Each conversation's tab and selection persist through a reload, bounded at 50 and oldest-written first; a malformed entry is dropped alone; chat width, pins, keybindings and plugin settings survive both ways.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP1",
          "quote": "Search's details pane on the right shows tabs per cited source plus Context (what the assistant sees); inline when Search is ≥ 980 px wide, a sheet over Search below; resizable within limits; Escape or close returns focus to the citation or result that opened it; state remembered per conversation.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "C11",
          "quote": "Any userState change preserves co-resident values (chat width, pins, keybindings, plugin settings).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "Tab and selection live in a validated cross-profile userState slice keyed by conversation; width keeps its existing localStorage key, so no stored width is lost.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s4-all.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/detailsMemory.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.pane.test.ts"
      ],
      "class": "New",
      "behaviour": "Search's pane: a tab per cited source then Context then plugin tabs; a source tab moves the reader and marks the answer; the reader stays mounted under Context; the opener survives moving between tabs and falls back to the composer; memory per conversation across a switch and a reload without taking the keyboard; a closed pane stays closed; a remembered source the record lost opens nothing; rapid selection, late records, other windows' selections; hide and restore with its failure; plugin tabs.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP1",
          "quote": "Search's details pane on the right shows tabs per cited source plus Context (what the assistant sees); inline when Search is ≥ 980 px wide, a sheet over Search below; resizable within limits; Escape or close returns focus to the citation or result that opened it; state remembered per conversation.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP4",
          "quote": "Plugin inspector tabs (registered, rendered nowhere today) appear as details-pane tabs.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP5",
          "quote": "Citation selection, hide/restore of sources from the assistant's context, document reader modes and failure states, host.ui.showInspector() signalling.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "C4",
          "quote": "Details ownership and races: in a split layout each window's details show its own selection; rapid selection changes, close/reopen and profile changes never show another window's or another folder's details; a delayed response for folder A never fills folder B (repairs today's failed-files folder-switch defect).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s4-all.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.pane.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.render.test.ts"
      ],
      "class": "New",
      "behaviour": "Activity shows background runs, this run and the effect journal, and a pointer lands on the section it named whether Activity is up yet or not.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP3",
          "quote": "Frame-level right drawers retired: Sources drawer and context inspector → Search pane tabs; failed-files drawer → inline under the folder in Library (coordinated with the Library redesign, L5/L13); retrospective → Search's list (sessions) and System › Activity (system activity, background runs); effect journal → System › Activity; narrow frame reader → the details pane of the window showing it.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s4-all.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.render.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/RetiredFrameElements.test.ts"
      ],
      "class": "New",
      "behaviour": "The retired drawer elements stay defined, inert and in the vocabulary, so a presentation naming one still validates and mounts.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "jf-sources-pane, jf-interaction-retrospective-panel and jf-failed-jobs-drawer stay as inert definitions, the RetiredFrameElements pattern.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s4-all.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/RetiredFrameElements.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-boundaries.test.ts"
      ],
      "class": "New",
      "behaviour": "The pane stops at 560 px on any box, and a stored or dragged width past it comes back at the ceiling; the main column's 640 still binds first on a narrower box.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP1",
          "quote": "Search's details pane on the right shows tabs per cited source plus Context (what the assistant sees); inline when Search is ≥ 980 px wide, a sheet over Search below; resizable within limits; Escape or close returns focus to the citation or result that opened it; state remembered per conversation.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "The details pane's 360-560 px replaces the 70% share, in Search as everywhere.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s4-all.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-boundaries.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-boundaries.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP1",
          "quote": "Search's details pane on the right shows tabs per cited source plus Context (what the assistant sees); inline when Search is ≥ 980 px wide, a sheet over Search below; resizable within limits; Escape or close returns focus to the citation or result that opened it; state remembered per conversation.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "The details pane's 360-560 px replaces the 70% share, in Search as everywhere.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.pane.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP1",
          "quote": "Search's details pane on the right shows tabs per cited source plus Context (what the assistant sees); inline when Search is ≥ 980 px wide, a sheet over Search below; resizable within limits; Escape or close returns focus to the citation or result that opened it; state remembered per conversation.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "Reopening a conversation restores its own pane, so the switch test now pins the restored document, and the closed case keeps the old absence; the sheet entry animation goes because panels open without one by default (design system section 6).",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/RetrospectivePanel.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "removal",
          "removed": [
            "modules/ui-web/src/shell-v0/components/RetrospectivePanel.ts",
            "modules/ui-web/src/shell-v0/state/retrospectiveDrawer.ts"
          ]
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP3",
          "quote": "Frame-level right drawers retired: Sources drawer and context inspector → Search pane tabs; failed-files drawer → inline under the folder in Library (coordinated with the Library redesign, L5/L13); retrospective → Search's list (sessions) and System › Activity (system activity, background runs); effect journal → System › Activity; narrow frame reader → the details pane of the window showing it.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/SourcesPane.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "removal",
          "removed": [
            "modules/ui-web/src/shell-v0/components/SourcesPane.ts",
            "modules/ui-web/src/shell-v0/state/sourcesDrawer.ts"
          ]
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP3",
          "quote": "Frame-level right drawers retired: Sources drawer and context inspector → Search pane tabs; failed-files drawer → inline under the folder in Library (coordinated with the Library redesign, L5/L13); retrospective → Search's list (sessions) and System › Activity (system activity, background runs); effect journal → System › Activity; narrow frame reader → the details pane of the window showing it.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/ContextInspectorPane.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "removal",
          "removed": [
            "modules/ui-web/src/shell-v0/state/contextInspectorDrawer.ts"
          ]
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP3",
          "quote": "Frame-level right drawers retired: Sources drawer and context inspector → Search pane tabs; failed-files drawer → inline under the folder in Library (coordinated with the Library redesign, L5/L13); retrospective → Search's list (sessions) and System › Activity (system activity, background runs); effect journal → System › Activity; narrow frame reader → the details pane of the window showing it.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/FailedJobsDrawer.test.ts",
        "modules/ui-web/src/shell-v0/components/FailedFilesList.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "removal",
          "removed": [
            "modules/ui-web/src/shell-v0/state/failedJobsDrawer.ts",
            "modules/ui-web/src/shell-v0/components/FailedJobsDrawer.ts"
          ]
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP3",
          "quote": "Frame-level right drawers retired: Sources drawer and context inspector → Search pane tabs; failed-files drawer → inline under the folder in Library (coordinated with the Library redesign, L5/L13); retrospective → Search's list (sessions) and System › Activity (system activity, background runs); effect journal → System › Activity; narrow frame reader → the details pane of the window showing it.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none"
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
            "modules/ui-web/src/shell-v0/components/EffectAuditLog.ts#ModalController"
          ]
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP3",
          "quote": "Frame-level right drawers retired: Sources drawer and context inspector → Search pane tabs; failed-files drawer → inline under the folder in Library (coordinated with the Library redesign, L5/L13); retrospective → Search's list (sessions) and System › Activity (system activity, background runs); effect journal → System › Activity; narrow frame reader → the details pane of the window showing it.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/documentPane/DocumentPane.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "removal",
          "removed": [
            "modules/ui-web/src/shell-v0/components/documentPane/DocumentPane.ts#handleClose"
          ]
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP3",
          "quote": "Frame-level right drawers retired: Sources drawer and context inspector → Search pane tabs; failed-files drawer → inline under the folder in Library (coordinated with the Library redesign, L5/L13); retrospective → Search's list (sessions) and System › Activity (system activity, background runs); effect journal → System › Activity; narrow frame reader → the details pane of the window showing it.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "removal",
          "removed": [
            "modules/ui-web/src/shell-v0/controllers/AgentSessionController.ts#loadTimeline"
          ]
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP3",
          "quote": "Frame-level right drawers retired: Sources drawer and context inspector → Search pane tabs; failed-files drawer → inline under the folder in Library (coordinated with the Library redesign, L5/L13); retrospective → Search's list (sessions) and System › Activity (system activity, background runs); effect journal → System › Activity; narrow frame reader → the details pane of the window showing it.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/responsiveState.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "removal",
          "removed": [
            "modules/ui-web/src/shell-v0/state/responsiveState.ts"
          ]
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP3",
          "quote": "Frame-level right drawers retired: Sources drawer and context inspector → Search pane tabs; failed-files drawer → inline under the folder in Library (coordinated with the Library redesign, L5/L13); retrospective → Search's list (sessions) and System › Activity (system activity, background runs); effect journal → System › Activity; narrow frame reader → the details pane of the window showing it.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/FailedJobsDrawer.test.ts",
        "modules/ui-web/src/shell-v0/components/FailedFilesList.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "rename",
        "signature"
      ],
      "whatMoved": "The element is jf-failed-files-list and takes its folder as a property instead of the drawer store; the load, render, state wording, retry, race and failure-feedback oracles are unchanged, and Retry all failed files is the list's Try again."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/FailedJobsDrawer.layout.test.ts",
        "modules/ui-web/src/shell-v0/components/FailedFilesList.layout.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "rename",
        "signature"
      ],
      "whatMoved": "Same element rename and folder property; the stacked-row assertions are unchanged."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.redesign.test.ts",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.bandFocus-lockdown.test.ts",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.focusKeeper-lockdown.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "rename"
      ],
      "whatMoved": "The selector follows the element's new name and the setup drops inline and the drawer store; every assertion is unchanged."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.pane.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding",
        "instrumentation"
      ],
      "whatMoved": "Width checks use inputs inside the new ceiling (a 1300 px box, a 10 px drag, the keys in the other order) and read the backdrop from the shared frame; the memory is reset between tests. The oracles are unchanged."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/Sv3Pane.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "instrumentation"
      ],
      "whatMoved": "The region is opened with activeTab as well as docPath, and closing comes from the shared frame rather than the reader; forwarding and re-raising oracles are unchanged."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The inspector's view is read from the pane's Context tab rather than the retired store; what it must contain is unchanged."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The set-up also opens the pane's tab, and the retired store's reset goes; the close-on-branch assertions are unchanged."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/ContextInspectorPane.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The rendering checks pass the view directly, as they did, without the open store; their assertions are unchanged."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/EffectAuditLog.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The journal is always rendered, so the checks no longer open it first; their assertions are unchanged."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/themes/token-names.generated.ts"
      ],
      "class": "Structural rule kept",
      "rule": "The closed token vocabulary equals what the stylesheets declare, regenerated by scripts/ci/gen-token-names.mjs and held by its --check gate.",
      "whySurvives": "Generated output only: it gains the details pane's size and sheet tokens and drops --z-overlay-drawer with the slot it served."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "Real app in Chromium: Library Folders, Browse and AI Brain › Memory are as wide as their window at 900, 1280 and 1600 px. Before: several hosts are display: contents, so the frame's flex layout sized their inner shell to its content and left the rest blank.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "Windows lay out as the baseline did, as block content of the stage or split pane, and an inline details pane reserves its own width; a width assertion for the three views guards it.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s4-round2-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#X4 every window fills its area at 900 px Library Folders, Browse and AI Brain › Memory are as wide as the window",
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#X4 every window fills its area at 1280 px Library Folders, Browse and AI Brain › Memory are as wide as the window",
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#X4 every window fills its area at 1600 px Library Folders, Browse and AI Brain › Memory are as wide as the window"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "Preservation: a split's windows fill their halves, and a window takes exactly what its open pane leaves and gets it back on close.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "Windows lay out as the baseline did, as block content of the stage or split pane, and an inline details pane reserves its own width; a width assertion for the three views guards it.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s4-round2-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#X4 every window fills its area in a split at 1600 px each window fills its half",
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#X4 every window fills its area with a document open inline a window takes exactly what its pane leaves, and closing gives it back"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "At 900×800 a click or Enter on a result opens the sheet with the keyboard in it; Escape closes it and focus returns to the result. Before: focus dropped to the page and Escape did nothing.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP1",
          "quote": "Search's details pane on the right shows tabs per cited source plus Context (what the assistant sees); inline when Search is ≥ 980 px wide, a sheet over Search below; resizable within limits; Escape or close returns focus to the citation or result that opened it; state remembered per conversation.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s4-round2-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#DP1 a result opens the sheet with the keyboard in it at 900×800 click on a result: the sheet takes focus, Escape closes it, focus returns to the result",
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#DP1 a result opens the sheet with the keyboard in it at 900×800 Enter on a result: the sheet takes focus, Escape closes it, focus returns to the result"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "Escape or close from an inline citation, or inside a pane a result opened, returns to that citation or result. Before: the answer was re-woven on every window render, which replaced the mark, and a result was never remembered, so focus landed in the composer.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP1",
          "quote": "Search's details pane on the right shows tabs per cited source plus Context (what the assistant sees); inline when Search is ≥ 980 px wide, a sheet over Search below; resizable within limits; Escape or close returns focus to the citation or result that opened it; state remembered per conversation.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s4-round2-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#DP1 closing returns the keyboard to what opened the pane Escape from an inline citation lands on that citation at 1280 px",
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#DP1 closing returns the keyboard to what opened the pane close from an inline citation lands on that citation at 1280 px",
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#DP1 closing returns the keyboard to what opened the pane Escape from an inline citation lands on that citation at 900 px",
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#DP1 closing returns the keyboard to what opened the pane Escape from inside a pane a result opened lands on the result",
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#DP1 closing returns the keyboard to what opened the pane close from inside a pane a result opened lands on the result"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "With Search's pane open F6 walks top bar, content, details and around. Before: the pane's own tab list was taken for the window's tab row, so F6 alternated details and top bar.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "F6 follows the design system's keyboard order, top bar → window tabs → content → details, and lands on the pane's selected tab; a details pane's tabs are the details region, never the window's tab row.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s4-round2-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#X3 F6 walks the frame in the design’s order Search with its pane open: top bar → content → details, and around again"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "Preservation: a window with a tab row of its own keeps the full order, top bar, window tabs, content, details.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "F6 follows the design system's keyboard order, top bar → window tabs → content → details, and lands on the pane's selected tab; a details pane's tabs are the details region, never the window's tab row.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s4-round2-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#X3 F6 walks the frame in the design’s order Library with its pane open: top bar → window tabs → content → details"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "A document tab and plugin tabs keep their size and the strip scrolls, at default and wide fonts: plugin labels stay whole in a split, and the selected tab stays on show. Before: tabs shrank to fit, leaving plugin labels one or two letters.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP1",
          "quote": "Search's details pane on the right shows tabs per cited source plus Context (what the assistant sees); inline when Search is ≥ 980 px wide, a sheet over Search below; resizable within limits; Escape or close returns focus to the citation or result that opened it; state remembered per conversation.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP4",
          "quote": "Plugin inspector tabs (registered, rendered nowhere today) appear as details-pane tabs.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "Named tabs do not shrink: the strip scrolls, a name longer than a tab's limit is cut at that limit, and the selected tab is brought into view when it changes or the strip is resized.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s4-round2-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#DP4 tabs keep readable labels in a split with plugin tabs : labels stay whole and the strip scrolls",
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#DP4 tabs keep readable labels in a split with plugin tabs ?wideFont: labels stay whole and the strip scrolls"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "A document shown in a conversation with sources has a tab of its own, selected, and the panel is labelled by it. Before: no tab was selected and the panel's label pointed at a tab that did not exist.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP1",
          "quote": "Search's details pane on the right shows tabs per cited source plus Context (what the assistant sees); inline when Search is ≥ 980 px wide, a sheet over Search below; resizable within limits; Escape or close returns focus to the citation or result that opened it; state remembered per conversation.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP5",
          "quote": "Citation selection, hide/restore of sources from the assistant's context, document reader modes and failure states, host.ui.showInspector() signalling.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s4-round2-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#DP4 a document shown in a conversation with sources has a tab of its own, selected, and the panel is labelled by it"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "Escape with the keyboard on the page closes the pane of the window the pointer or last focus was in, and only that pane. Before: nothing heard the key until Tab moved focus into a pane.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP1",
          "quote": "Search's details pane on the right shows tabs per cited source plus Context (what the assistant sees); inline when Search is ≥ 980 px wide, a sheet over Search below; resizable within limits; Escape or close returns focus to the citation or result that opened it; state remembered per conversation.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "C4",
          "quote": "Details ownership and races: in a split layout each window's details show its own selection; rapid selection changes, close/reopen and profile changes never show another window's or another folder's details; a delayed response for folder A never fills folder B (repairs today's failed-files folder-switch defect).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "Escape reaches the frame only when focus is on the page or the content region: a control inside a window keeps its own Escape.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s4-round2-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#DP1 Escape with the keyboard on the page closes the pane of the window the pointer is in, and only that one",
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#DP1 Escape with the keyboard on the page with the pointer still, closes the pane of the window focus was last in",
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#DP1 Escape with the keyboard on the page Search alone: with focus dropped to the page, Escape still closes and returns to the citation"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "In a Search/Library split Search's Close leaves Library's document open. Before: it closed the shared inspector, and Library's pane with it.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "C4",
          "quote": "Details ownership and races: in a split layout each window's details show its own selection; rapid selection changes, close/reopen and profile changes never show another window's or another folder's details; a delayed response for folder A never fills folder B (repairs today's failed-files folder-switch defect).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s4-round2-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#C4 each window’s close is its own Search’s Close leaves Library’s document open"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "Preservation: Library's Close leaves Search's document open.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "C4",
          "quote": "Details ownership and races: in a split layout each window's details show its own selection; rapid selection changes, close/reopen and profile changes never show another window's or another folder's details; a delayed response for folder A never fills folder B (repairs today's failed-files folder-switch defect).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s4-round2-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#C4 each window’s close is its own Library’s Close leaves Search’s document open"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "The Space preview's distance from its row and the window pane grip's layer come from tokens. Before: a PREVIEW_EDGE_PX of 8 and a literal z-index of 1.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "X1",
          "quote": "Dark, light, forced colours; 1280×800, 900×800, 600×400 without sideways overflow; no new hard-coded colours, sizes or z-indexes; ui-a11y-gate shows no new violations.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s4-round2-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#X1 the frame’s numbers are tokens the Space preview sits one step of the scale inside its row",
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#X1 the frame’s numbers are tokens the window pane’s grip takes its layer from the token"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "sv3-details-domain.ts is declared in the execution-surface register. Before: the gate reported it as undeclared.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "The pane's decisions consume the evidence record and re-author no field of it, so the file is declared as a consumer guarded by the pane's own tests.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s4-round2-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#governance the pane’s decisions are a declared execution surface"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "A folder's failed files stay closed under a closed folder, open and close with it, and scroll past --frame-failed-list-max; the failed chip opens a closed folder and puts the keyboard on the list. Before: the list stayed open under collapsed folders, 14 files taking 1648 px.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP3",
          "quote": "Frame-level right drawers retired: Sources drawer and context inspector → Search pane tabs; failed-files drawer → inline under the folder in Library (coordinated with the Library redesign, L5/L13); retrospective → Search's list (sessions) and System › Activity (system activity, background runs); effect journal → System › Activity; narrow frame reader → the details pane of the window showing it.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "The list stays mounted while its folder is closed, so the chip and the folder's problem keep their one way in, revealFailedFiles; its height is bounded by the --frame-failed-list-max token.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s4-round2-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#DP3 a folder’s failed files stay closed under a closed folder, open and close with it, and scroll past the token’s height",
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#DP3 a folder’s failed files the failed chip opens a closed folder and puts the keyboard on the list"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "The Space preview closes when focus leaves the row, including a Tab to the row's actions. Before: a move inside the results card's shadow tree never reached the list's focusout listener.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP2",
          "quote": "Space previews the focused search result (a floating card); Space again or Escape closes it.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "The preview closes as soon as focus leaves the row, so it never describes a row the keyboard has left.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s4-round2-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#DP2 the Space preview follows the keyboard closes when Tab moves on from the row"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts",
        "modules/ui-web/src/shell-v0/components/detailsPane/DetailsPane.frame-lockdown.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.pane.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP1",
          "quote": "Search's details pane on the right shows tabs per cited source plus Context (what the assistant sees); inline when Search is ≥ 980 px wide, a sheet over Search below; resizable within limits; Escape or close returns focus to the citation or result that opened it; state remembered per conversation.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "Design section 7, \"Tabs with source numbers\": a source tab shows its number, not its file name. This retires three expectations: that every tab at 1280 with 3 sources stays at least 40 px wide with its label whole (Shell.details-lockdown, \"at 1280 with 3 sources\"), that a long name is cut inside its tab (DetailsPane.frame-lockdown), and that a source tab's text is its number and name (SearchV3View.pane, \"1 note-0.md\").",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts",
        "modules/ui-web/src/shell-v0/components/detailsPane/DetailsPane.frame-lockdown.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.pane.test.ts"
      ],
      "class": "New",
      "behaviour": "Source tabs show their number; the source's full name is the tooltip and, with the number, the accessible name; the selected source's name shows in the pane's header; Context and plugin tabs keep their names; everything fits for up to about six sources at 1280, 900 and 600 px with default and wide fonts; past that the strip takes the wheel, shows an edge affordance at each end it can still scroll towards, and keeps the selected tab clear of them. Before: at 1280 with the sidebar open the pane is 384 px, tab 3 was cut and Context was off the edge, with a thin scrollbar as the only hint and a wheel that did nothing.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP1",
          "quote": "Search's details pane on the right shows tabs per cited source plus Context (what the assistant sees); inline when Search is ≥ 980 px wide, a sheet over Search below; resizable within limits; Escape or close returns focus to the citation or result that opened it; state remembered per conversation.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP4",
          "quote": "Plugin inspector tabs (registered, rendered nowhere today) appear as details-pane tabs.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "Source tabs show their number; the source's full name is the tooltip and, with the number, the accessible name; the selected source's name shows in the pane's header; Context and plugin tabs keep their names; everything fits for up to about six sources at 1280, 900 and 600 px with default and wide fonts; past that the strip takes the wheel, shows an edge affordance at each end it can still scroll towards, and keeps the selected tab clear of them.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s4-round3-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#DP4 tabs with source numbers at 1280x800  six sources, Context and a plugin tab all fit, sources by number",
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#DP4 tabs with source numbers at 1280x800 ?wideFont six sources, Context and a plugin tab all fit, sources by number",
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#DP4 tabs with source numbers at 900x800  six sources, Context and a plugin tab all fit, sources by number",
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#DP4 tabs with source numbers at 900x800 ?wideFont six sources, Context and a plugin tab all fit, sources by number",
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#DP4 tabs with source numbers at 600x400  six sources, Context and a plugin tab all fit, sources by number",
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#DP4 tabs with source numbers at 600x400 ?wideFont six sources, Context and a plugin tab all fit, sources by number",
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#DP4 tabs with source numbers a source tab is named and tipped by its full name, and the header names the selected source ",
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#DP4 tabs with source numbers a source tab is named and tipped by its full name, and the header names the selected source ?wideFont",
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#DP4 tabs with source numbers past what fits the strip takes the wheel, shows its edges and keeps the selected tab clear of them ",
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#DP4 tabs with source numbers past what fits the strip takes the wheel, shows its edges and keeps the selected tab clear of them ?wideFont",
        "modules/ui-web/src/shell-v0/components/detailsPane/DetailsPane.frame-lockdown.test.ts#Search’s details pane is inline and within 360-560 px at 1280x800 , and tabs never push the close control out",
        "modules/ui-web/src/shell-v0/components/detailsPane/DetailsPane.frame-lockdown.test.ts#Search’s details pane is inline and within 360-560 px at 1280x800 ?wideFont, and tabs never push the close control out",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.pane.test.ts#DP1 the details pane shows tabs per cited source plus Context offers a tab per cited source, numbered, then Context",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.pane.test.ts#DP1 the details pane shows tabs per cited source plus Context a citation the answer did not cite still gets its own tab, and a result gets a plain one",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.pane.test.ts#DP4 plugin inspector tabs are details-pane tabs appear after Context, receive the document on show, and leave without stranding the pane"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "Roving focus follows the selection in Search's and Library's panes: arrows, Home and End move the keyboard with it, and Enter on the selected tab stays there. Before: selection reached Context while the keyboard stayed on tab 3, and Enter jumped back.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP1",
          "quote": "Search's details pane on the right shows tabs per cited source plus Context (what the assistant sees); inline when Search is ≥ 980 px wide, a sheet over Search below; resizable within limits; Escape or close returns focus to the citation or result that opened it; state remembered per conversation.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "The strip's keyboard focus follows the selected tab however the selection moved, read before the render, so it does not depend on when the window answers.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s4-round3-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#DP4 the keyboard stays on the selected tab in Search, from a citation opened with Enter",
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#DP4 the keyboard stays on the selected tab in Library’s pane"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "A hide whose response lands after a switch to another conversation changes only its own conversation: B keeps Hide, and A shows Restore on return. Before: B flipped to Restore.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "C4",
          "quote": "Details ownership and races: in a split layout each window's details show its own selection; rapid selection changes, close/reopen and profile changes never show another window's or another folder's details; a delayed response for folder A never fills folder B (repairs today's failed-files folder-switch defect).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP5",
          "quote": "Citation selection, hide/restore of sources from the assistant's context, document reader modes and failure states, host.ui.showInspector() signalling.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "The hidden-source set knows its conversation; a late response stays out of another conversation's set, and its own conversation reads the change back from its record.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s4-round3-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#C4 a late hide belongs to its own conversation a response released in B leaves B’s Hide alone, and A has it when the reader returns"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "A mouse click on an inline citation opens the pane at 1280, 900 and 600 px, and the preview sits under its mark. Before: the card's host stayed at its static position, took pointer events there and covered the mark.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP1",
          "quote": "Search's details pane on the right shows tabs per cited source plus Context (what the assistant sees); inline when Search is ≥ 980 px wide, a sheet over Search below; resizable within limits; Escape or close returns focus to the citation or result that opened it; state remembered per conversation.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP5",
          "quote": "Citation selection, hide/restore of sources from the assistant's context, document reader modes and failure states, host.ui.showInspector() signalling.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "The citation preview is positioned from the viewport's origin and takes no pointer events: it has nothing to press.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s4-round3-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#DP1 a citation opens from the mouse at every width at 1280x800 the preview sits under the mark and the click reaches it",
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#DP1 a citation opens from the mouse at every width at 900x800 the preview sits under the mark and the click reaches it",
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#DP1 a citation opens from the mouse at every width at 600x800 the preview sits under the mark and the click reaches it"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "The reader on a search result shows no citation notice. Before: it said \"This citation did not record a usable position\".",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP5",
          "quote": "Citation selection, hide/restore of sources from the assistant's context, document reader modes and failure states, host.ui.showInspector() signalling.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "Only a citation can have an unusable span: a document opened with no turn is not one.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s4-round3-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#DP5 the reader on a search result has no citation notice: a result is not a citation"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "Retry and Cancel on a failed file's row wear the shared button styling. Before: the inline list's overrides made the border and fill transparent.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DP3",
          "quote": "Frame-level right drawers retired: Sources drawer and context inspector → Search pane tabs; failed-files drawer → inline under the folder in Library (coordinated with the Library redesign, L5/L13); retrospective → Search's list (sessions) and System › Activity (system activity, background runs); effect journal → System › Activity; narrow frame reader → the details pane of the window showing it.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "The list withdraws its button overrides and keeps only the form-control spacing, so jf-action-button looks as it does everywhere else.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s4-round3-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#DP3 a failed file’s actions wear the shared button styling"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "A tab's number takes its least width from the spacing scale. Before: a literal 1.25em.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "X1",
          "quote": "Dark, light, forced colours; 1280×800, 900×800, 600×400 without sideways overflow; no new hard-coded colours, sizes or z-indexes; ui-a11y-gate shows no new violations.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s4-round3-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#X1 a tab’s number is sized from the scale its least width is the token"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "The reader's quiet highlight reads at 4.5:1 or better in the light theme's Source view. Before: 4.19:1, muted ink on the tint.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "X1",
          "quote": "Dark, light, forced colours; 1280×800, 900×800, 600×400 without sideways overflow; no new hard-coded colours, sizes or z-indexes; ui-a11y-gate shows no new violations.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "A --text-highlight-weak token carries the highlight's ink: the primary text colour in the light theme, the passage's own colour elsewhere.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s4-round3-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#X1 the reader’s quiet highlight reads at 4.5:1 in the light theme, Source view"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "Preservation: the quiet highlight reads at 4.5:1 or better in the light theme's Rendered view, and in dark and forced colours the passage keeps the ink around it in both views.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "X1",
          "quote": "Dark, light, forced colours; 1280×800, 900×800, 600×400 without sideways overflow; no new hard-coded colours, sizes or z-indexes; ui-a11y-gate shows no new violations.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "A --text-highlight-weak token carries the highlight's ink: the primary text colour in the light theme, the passage's own colour elsewhere.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s4-round3-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#X1 the reader’s quiet highlight reads at 4.5:1 in the light theme, Rendered view",
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#X1 the reader’s quiet highlight in dark colours, Rendered view, the passage keeps the ink around it",
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#X1 the reader’s quiet highlight in dark colours, Source view, the passage keeps the ink around it",
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#X1 the reader’s quiet highlight in forced colours, Rendered view, the passage keeps the ink around it",
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts#X1 the reader’s quiet highlight in forced colours, Source view, the passage keeps the ink around it"
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
  "session": "verifier-e98ofqb0",
  "verdict": "accept",
  "digest": "sha256:b968249e807576dd98326b55637224da5a49edef6e66e3d4b67a6b741d22d758",
  "note": "Round 3 on e0b402cdc1: entries and evidence hold; 9347 unit tests, 68 Chromium tests, 110 axe surfaces pass. Earlier rounds accepted earlier digests (01a1af73-8c14-74f1-8b9b-1b46333aff65, mfmse3gc-verifier) while product repairs proceeded. Transcribed by the orchestrator from the verifier's answer (Codex sandbox)."
}
```
