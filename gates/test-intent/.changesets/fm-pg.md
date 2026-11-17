---
schema: test-intent.v1
task: t-20261113-fmimpl
author-role: builder
author-session: fm-pg-builder
---

# Library's edges, its foot actions and the size of text actions as drawn

The design review of the packaged app by the author of the pictures ("Library alignment and weight
[MISS, spacing]" in fm-07; "Text actions [MISS, sizes]" in fm-06 and fm-07) is repaired under the
orchestrator's delegated decisions PG1-PG5 (task t-20261113-fmimpl, 2026-11-17, within the delegation
"You are supposed to be the implementation session").

The new behaviour is covered by two new files. On the base 02435b349e they fail by assertion:
`views/library-first-minutes/alignment-lockdown.test.ts` fails in all 14 cases (the first tab's label
sits 8 px left of the page at 1024, 1280 and 1920 px: 64 against 72, 192 against 200, 512 against 520,
and 12-16 px right of it at 800 and 600 px; on Browse 8 px, 20 px at 640 and 600 px; "Read everything again" is weight 400;
"Open AI Brain" is 11 px), and `views/library-first-minutes/up-to-date-icon.test.ts` fails in its
first case (a folder that says "Up to date" while meaning work waits for AI draws a clock). Its four
other cases (Up to date with nothing waiting has the check; meaning work in progress keeps the clock;
failed meaning work keeps the warning; an unconfirmed status keeps the clock) pass on the base and on
the candidate (preserved behaviour).

Repair after the real-app check of e3a24c9209 ("PPG-1 for Browse at 600 px (likely any width up to 640): the content runs 12–588 px, the tab row 16–584 px"): Browse's own rule at 640 px and below set its inline padding to a fixed .75rem instead of Library's page edge (1rem there); it now reads the same edge. The alignment file gains Browse at 640 and 600 px; on e3a24c9209 both fail by assertion (16 against 12 px), on 02435b349e both fail by assertion (32 against 12 px), and both pass on the candidate (gates/test-intent/evidence/fm-pg.json).

What the new files check, in their own words: Library Folders at 1024, 1280, 1920 (maximised), 800 and 600 px and Browse at 1024, 1920, 800, 640 and 600 px: the first tab's label begins on the page content's left edge, the last tab-row action ends on its right edge, the folder list and heading (Folders) or the file table and the developer filter field (Browse) keep to the same two edges, the first foot action's words begin on the left edge, and the tab row's line spans the window; maximised, the page column is 960 px centred with its 40 px inset. Activity and Read everything again are 13 px, weight 500, the normal text colour (at least 4.5:1), identical to each other, and under the pointer show no box and an underline; Pause reading in Library's tab row, and Open AI Brain and Add a folder where Search found nothing, are 13 px, weight 500, the normal text colour, in dark and light. A folder row that says Up to date carries the check icon in the success class, also while meaning work only waits for AI; meaning work in progress and an unconfirmed reading status keep the clock and failed meaning work keeps the warning icon.

The review's words: "Library alignment and weight [MISS, spacing]. Three left edges: tabs 64, content 72, foot actions 85; the tab row is wider than the content on both sides (clear when maximised: 504 against 520, 1424 against 1400). 'Activity' is bold and 'Read everything again' grey: siblings, same style. 'Up to date' carries a clock, which reads as waiting; picture: a check." (fm-07) and "Text actions [MISS, sizes]. 'Open AI Brain', 'Add a folder' (fm-06) and 'Pause reading' look like 11 px bold. 11 is for small labels; an action is 13 px medium." (fm-06, fm-07). The decisions: PG1 Library (Folders and Browse): the tab row, the page content and the foot actions share one left edge and one right edge at every width, including maximised; the tab row's line still spans the window. PG2 Activity and Read everything again are styled the same: text actions, 13 px, medium (500), normal text colour. PG3 a folder's Up to date state carries a check icon, not a clock; other states keep theirs. PG4 text actions Open AI Brain and Add a folder (fm-06) and Pause reading (Library) are 13 px, weight 500, normal text colour. PG5 nothing else changes. Builder's detail within it: the edge a tab keeps to is its label (as drawn in fm-07, where the tab's underline reaches past it); Library's page inset is one length (2.5rem, 1.25rem at 800 px and below, 1rem at 640 px and below, as before) read by the tab row and by Browse. Decision records under docs/decisions/ (54) searched for tab row, surface-tabs, SurfaceTabs, text action, text-action, link button, font-size, alignment, gutter, status icon, glyph and check-circle: the hits (0005 memory alignment, 0018 visual-text alignment, 0030/0031 doc alignment, 0015, 0023, 0040) do not govern the UI's layout, type or icons. The wide-window decision (owner Q1, 15 November) keeps the tab row's line across the window and the content on the 960 px column; both hold and are checked here and in Shell.wide-lockdown. No conflict.

Indirect effects, named: Library's one text-action style now says the rule, so its other text
actions (Try again, Use the saved rules, Keep my draft, the Read again and Cancel of the reread ask,
the removal ask's buttons) are the normal text colour in medium weight too; "Clear filters", in the
same list as "Open AI Brain", is 13 px; at 800 px and below Browse's table takes Library's narrower
page inset (20 px, 16 px at 640 px and below) instead of 40 px, so the shared tab row has one edge on
both tabs; developer tools' Browse filter field moves from 24 px to the page edge. Other hosts of the
tab row (AI Brain, System) keep their geometry: without --surface-tabs-edge the paddings are the
base's (1.5rem before the tabs, 1rem after the actions).

PG3 changes what one existing check pinned: IndexingPause F5 asserted the pending glyph beside "Up to
date" for a mature folder whose meaning work waits for AI. PG1 changes two expectations of
Shell.wide-lockdown: Browse's table inset at 800 px and how far Library's content may start inside the
column at 200% text. Each is listed below; every other assertion in those files is unchanged and passes.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "'Up to date' carries a clock, which reads as waiting; picture: a check.",
          "location": "task t-20261113-fmimpl request, fm-07 design review of the packaged app by the author of the pictures, 2026-11-17"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "PG3: a folder's Up to date state carries a check icon, not a clock; other states keep theirs.",
          "label": "agent choice"
        }
      ],
      "behaviour": "A mature folder whose meaning work waits for AI says Up to date with the check icon before and during Pause; the caveat in the open row's Search by meaning line is unchanged.",
      "conflictSearch": "Decision records under docs/decisions/ (54) searched for status icon, glyph, check-circle and Up to date: none governs a folder row's icon; folderStatus's own glyph vocabulary is unchanged (the check is chosen where the row says Up to date). No conflict.",
      "assertionChanges": [
        {
          "oldAssertion": "IndexingPause.test.ts 'F5 keeps a mature folder semantic-install caveat and pending glyph through Pause': .status-icon.pending is present while the row says Up to date, before and during Pause, and .status-icon.indexed is absent during Pause.",
          "newAssertion": "Renamed 'F5 keeps a mature folder semantic-install caveat and its Up to date check through Pause': .status-icon.indexed is present before and during Pause and .status-icon.pending is absent during Pause; the Up to date words, the Search by meaning caveat and the absence of 'semantic search' are unchanged.",
          "reason": "PG3: the row that says Up to date carries the check, not the clock."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.wide-lockdown.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Library alignment and weight [MISS, spacing]. Three left edges: tabs 64, content 72, foot actions 85; the tab row is wider than the content on both sides (clear when maximised: 504 against 520, 1424 against 1400).",
          "location": "task t-20261113-fmimpl request, fm-07 design review of the packaged app by the author of the pictures, 2026-11-17"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "PG1 Library (Folders and Browse): the tab row, the page content and the foot actions share one left edge and one right edge at every width, including maximised (the tab row's tabs and actions sit on the same column as the content; the tab row's line still spans the window, as the wide-window rule says).",
          "label": "agent choice"
        }
      ],
      "behaviour": "At 800 px Browse's table takes Library's 20 px page inset, the one the shared tab row keeps to; at 200% text in a maximised window Library's content, its tab label included, starts on the page's own 2.5rem inset (80 px) inside the centred column. Every other case of the wide-window file is unchanged and passes.",
      "conflictSearch": "The wide-window decision (owner Q1, 15 November) asks for content on the centred column with tab-row lines spanning the window; both still hold and are checked by this file. Decision records under docs/decisions/ (54): none governs page insets. No conflict.",
      "assertionChanges": [
        {
          "oldAssertion": "Shell.wide-lockdown.test.ts 'at %i px each area keeps its own padding and no gutter' (800): core.browse-surface .body has 40 px inline padding.",
          "newAssertion": "At 800 px core.browse-surface .body has 20 px inline padding (960 px: 40 px, unchanged); the other areas' paddings are unchanged.",
          "reason": "PG1: at every width Browse shares the tab row's and Folders' edge; Folders' inset at 800 px and below was already 20 px."
        },
        {
          "oldAssertion": "Shell.wide-lockdown.test.ts '200% text at 1920: Search results and Library stay on the column': Library's content starts at most 64 px after the column's start.",
          "newAssertion": "Library's content starts at most 80 px after the column's start (inside it, as before); the Search half and the right-edge check are unchanged.",
          "reason": "PG1: the tab label, Library's leftmost words, moved from the tab row's own 2rem to the page's 2.5rem inset, which is 80 px at 200% text; before, the label at 64 px was the content's left edge."
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
  "session": "ol8xhfrk-verifier",
  "verdict": "accept",
  "digest": "sha256:e8ddc6b79ef23e1a9d9b7d3cd6bb79b219f0673ec99972fd09d8d553195ca9fe"
}
```
