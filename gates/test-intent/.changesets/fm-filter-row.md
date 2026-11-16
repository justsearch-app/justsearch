---
schema: test-intent.v1
task: t-20261113-fmimpl
author-role: builder
author-session: fm-pf-builder-e64d2c4b
---

# The results filter row and the search field's Clear as drawn in fm-04

The design review of the packaged app by the author of the pictures (2026-11-16) found the filter row
naming kinds by their raw type ("text 36"), showing kind filters when there is one kind, and drawing
the weights in reverse (the kind grey, "Any time" and "More filters" bold), and no Clear cross in the
search field while it holds text. The orchestrator's delegated decisions PF1-PF5 say how each is
drawn. The three new test files are isolated new sources; they carry an entry so their sources and
evidence are on record. Five cases in two existing files pinned the raw type names and an "All"
filter beside a single kind, which PF1 and PF2 replace.

Base: `075b738f0a`. The evidence runs each new file whole (one check per file) on the candidate. The
same files were also run on a shared clone of the base: `kindFilters.test.ts` cannot load there (the
helper `./kindFilters.js` does not exist); `ResultsCard.filterRow.test.ts` fails 6 of 6 by assertion
(the row reads `'All', 'email1', 'pdf1'`, `'text36'`, an "All" and `'pdf2'` beside a single kind, and
the pressed kind reads `'email1'`); the browser file fails 12 of 13: the row reads
`'All', 'email1', 'office1', …`, choosing a kind sends `['email']` alone, one kind still shows "All"
and `'text3'`, the computed weights and colours differ from "All strong, the rest grey" in both
themes, in forced colours the pressed "All" does not take the Highlight pair, and there is no Clear (count 0; the click and the Tab cases time out finding it). The
preservation case (Escape in the field does not empty it) passes on the base and on the candidate.

The colour checks compare with the theme's own tokens resolved by a probe inside the checked tree:
`--text-primary` for the chosen filter and `--text-secondary` for the rest (12 px; 500 and 400), and
`--muted-foreground` for the cross, in dark and light; every filter reads at 4.5:1 or more on what is
painted under it and the cross at 3:1 or more. The cross's equivalence case compares what Search shows
(field value, the composer's state and results attributes, rows, cards, headline, the window's
attributes) after a Clear click with the same after Ctrl+A, Backspace in a separate page.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/searchResults/kindFilters.test.ts",
        "modules/ui-web/src/shell-v0/components/searchResults/ResultsCard.filterRow.test.ts",
        "modules/ui-web/src/shell-v0/chrome/FirstMinutes.filterRow-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "The results filter row names kinds in the words a person would use, plural, with the count: the engine's text/markdown/html are 'Text files', pdf/office/document 'Documents', email/mail 'Mail', image 'Images', spreadsheet/csv 'Spreadsheets', presentation 'Presentations', code 'Code', archive 'Archives', and any other kind 'Other files' — never a raw type name. Kinds sharing a word are one filter whose count is their sum; choosing it chooses all of them in one search, choosing it again lets go of them. The picture's three files read 'All', 'Documents 2', 'Mail 1', 'Any time', 'More filters' (with its funnel). With one kind among the results there are no kind filters and no 'All'; 'Any time' and 'More filters' stay; a chosen kind stays on the row (with 'All') so it can be undone. The chosen filter ('All' when no kind is chosen; 'Any time' and 'More filters' only when they hold a choice — a date range, or a folder, size, format, language or author filter — and not merely because a menu or panel is open) is the normal text colour (--text-primary) at weight 500; the rest are muted (--text-secondary) at 400; all 12 px, each at 4.5:1 or more in dark and light; the chosen kind or 'All' has aria-pressed=true, a chosen date is in the date button's name, and More filters says how many it holds ('More filters, 1 set'); in forced colours the pressed filter takes the system selection pair (Highlight / HighlightText) and the others do not, as the kind chips' selected state did. While the search field holds text it shows a 'Clear' cross at its end (a button named 'Clear search', 24 px target, 14 px icon, --muted-foreground, at 3:1 or more, vertically centred, within 16 px of the field's end); it is gone when the field is empty; Tab from the field reaches it and Enter activates it; a press empties the field, keeps the caret in the field and leaves Search exactly as emptying the field by keyboard does. Escape in the field does not empty it (unchanged).",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "fm-04 filter row [MISS, words and weight]. 'text 36' is a raw type name; use the word a person would ('Text files'), and show no kind filters when there is one kind. Weights are reversed against the picture: there the chosen filter is the strong one and the rest are grey; here 'Any time' and 'More filters' are bold and the kind is grey. Search field [MISS]. No clear cross when it holds text.",
          "location": "task t-20261113-fmimpl request, design review of the packaged app by the author of the pictures (2026-11-16), quoted in the supplied PF builder brief"
        },
        {
          "kind": "owner-words",
          "quote": "<span class=\"flt on\">All</span><span class=\"flt\">Documents 2</span><span class=\"flt\">Mail 1</span><span class=\"flt\">Any time<svg …/></span><span class=\"flt\"><svg …/>More filters</span> .flt{display:inline-flex;align-items:center;gap:3px;font-size:12px;color:var(--muted)}.flt.on{color:var(--fg);font-weight:500}.flt svg{width:13px;height:13px} <span class=\"clr\" title=\"Clear\"><svg …/></span> .sf .clr{display:grid;place-items:center;color:var(--muted);flex:none} .sf .clr svg{width:14px;height:14px}",
          "location": "task t-20261113-fmimpl agreement, agreed picture fm-04-results (tmp/design/fm-04-results.html; the pictures are not in git, the words are quoted here)"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "PF1: kind filters use the words a person would, plural, with the count (text -> 'Text files', document/pdf/docx -> 'Documents', email -> 'Mail', image -> 'Images', spreadsheet -> 'Spreadsheets', presentation -> 'Presentations', code -> 'Code', other -> 'Other files'); never a raw type name. PF2: with one kind among the results, no kind filters (no 'All' either); 'Any time' and 'More filters' stay. PF3: the chosen filter (including 'All' when nothing is chosen, and 'Any time'/'More filters' only when they hold a choice) is the normal text colour, weight 500; the rest are muted, weight 400; all 12 px; chosen state exposed to assistive tech. PF4: the search field shows a 'Clear' cross at its end while it holds text: a real button labelled 'Clear search', reachable by keyboard; it empties the field, keeps focus in the field, and returns Search to its empty state as clearing by keyboard does today; Escape unchanged. PF5: nothing else changes.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fm-filter-row.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/searchResults/kindFilters.test.ts",
        "modules/ui-web/src/shell-v0/components/searchResults/ResultsCard.filterRow.test.ts",
        "modules/ui-web/src/shell-v0/chrome/FirstMinutes.filterRow-lockdown.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/searchResults/ResultsCard.test.ts",
        "modules/ui-web/src/shell-v0/components/searchResults/ResultsCard.firstMinutes.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "'text 36' is a raw type name; use the word a person would ('Text files'), and show no kind filters when there is one kind.",
          "location": "task t-20261113-fmimpl request, design review of the packaged app by the author of the pictures (2026-11-16), quoted in the supplied PF builder brief"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "PF1: kind filters use the words a person would (markdown and text -> 'Text files', pdf -> 'Documents', code -> 'Code'); never a raw type name. PF2: with one kind among the results, no kind filters (no 'All' either).",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. ResultsCard.test.ts 'projects chips from the emitted facet counts, grouped and labeled' expected the kind chips to start with the raw 'markdown' and 'code' under a 'Type' group label; now the row's kind filters read 'Text files 20' and 'Code 8' (no group label), the Language chip and its label behind More filters are unchanged. 'a selected value absent from the current counts still renders (dismissable)' looked for a chip starting 'pdf'; it now looks for the 'Documents' kind filter. 'a chip click emits card-facet-toggle with the field/value' found the chip by the text 'markdown'; it now finds 'Text files' — the emitted detail {field: 'file_kind', value: 'markdown'} is unchanged. ResultsCard.firstMinutes.test.ts 'S5-5 shows All and Type as text …' mounted a single kind (pdf: 2) and expected 'All' pressed; with one kind PF2 shows none, so it mounts two (pdf: 2, code: 1) and keeps its assertions. 'S5-5 pending responses retain known Type values without old counts' expected the pending row to contain 'pdf'; it now expects 'Documents' (still no counts)."
    }
  ]
}
```

## Acceptance records
