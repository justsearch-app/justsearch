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
    }
  ]
}
```

## Acceptance records
