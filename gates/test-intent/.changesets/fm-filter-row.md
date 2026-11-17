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

Base: `e3143c73ed`. The evidence runs each new file whole (one check per file) on the candidate. The
same files were also run on a shared clone of `075b738f0a` (before the first cut): `kindFilters.test.ts`
cannot load there (the helper `./kindFilters.js` does not exist); the other two fail by assertion on
the raw type names, an "All" beside a single kind, the reversed weights and the missing Clear. The
preservation case (Escape in the field does not empty it) passes before and after.

A real-app verifier then failed two points of the first cut (`5a2ac8226e`), repaired as PF1' and
PF4'. Mail: the Engine files .eml as its catch-all kind "binary" (message/rfc822), so the first cut
read it as "Other files" while its row showed the mail icon. The kind filters now take mail from the
recognition the rows and Browse already use (`display/fileKind.ts`, `isMail`): the Mail filter counts
and selects the mail file types (the same `mime_base` filter More filters offers), "Other files"
counts the catch-all less its mail, and the stubbed Engine of the browser file now describes files
with the Engine's own kinds (mail "binary") and counts both fields. Clear: its place hung on a
`:has()` rule hiding the search field's empty control row, which was not reapplied after a pointer
Clear, and a press took the caret out of the field. The empty control row is no longer rendered in the
search field (without the × the field keeps its room as padding, so the text box is the same width
empty and typed and an empty Search stays centred, as `Shell.wide-lockdown` checks), and a press on the
× does not take focus. On a shared clone of `5a2ac8226e` with the
candidate's three files: `kindFilters.test.ts` fails 6 of 7 (the signature and the mail recognition),
`ResultsCard.filterRow.test.ts` 5 of 8 (the picture's row is not 'All', 'Documents 2', 'Mail 1';
mail beside two other binary files reads 'Other files 3' with no Mail; the presses send a different
selection; a chosen mail file type shows no kind filters and presses only 'All'), the browser file 8
of 17: the picture's row (dark and light) and "ledger" ('Other files 3') have no Mail, so Mail cannot
be chosen (the click waits for it) and a chosen kind reads 'Other files 1'; pressing the × takes the
caret from the field in 5 of 5 rounds; and the search field's parts include the empty control row
(`footer`). The verifier's 44 px
displacement of the × did not reproduce in headless Chromium (headless shell or full, typed or filled
input): the × stayed in place on `5a2ac8226e` there, so the round check fails before on the caret and
passes after on the caret, the × geometry (identical in all five rounds, unmoved while pressed) and the
first click clearing each time.

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
      "behaviour": "The results filter row names kinds in the words a person would use, plural, with the count: the engine's text/markdown/html are 'Text files', pdf/office/document 'Documents', image 'Images', spreadsheet/csv 'Spreadsheets', presentation 'Presentations', code 'Code', archive 'Archives', and any other kind 'Other files' — never a raw type name. 'Mail' is what the rows and Browse show as mail (display/fileKind.ts: a message/* media type), which the Engine files under its kind 'binary': the Mail filter counts and selects the mail file types (the More filters file-type filter), never other 'binary' files, and 'Other files' counts the catch-all without its mail (a .eml beside two other binary files reads 'Other files 2', 'Mail 1'); a chosen mail file type is a kind choice, not a More filters one. Kinds sharing a word are one filter whose count is their sum; choosing it chooses all of them in one search, choosing it again lets go of them; choosing Mail lets go of chosen engine kinds and the reverse, in the same search, and 'All' lets go of every kind choice including Mail. The picture's three files read 'All', 'Documents 2', 'Mail 1', 'Any time', 'More filters' (with its funnel). With one kind among the results there are no kind filters and no 'All'; 'Any time' and 'More filters' stay; a chosen kind stays on the row (with 'All') so it can be undone. The chosen filter ('All' when no kind is chosen; 'Any time' and 'More filters' only when they hold a choice — a date range, or a folder, size, format, language or author filter — and not merely because a menu or panel is open) is the normal text colour (--text-primary) at weight 500; the rest are muted (--text-secondary) at 400; all 12 px, each at 4.5:1 or more in dark and light; the chosen kind or 'All' has aria-pressed=true, a chosen date is in the date button's name, and More filters says how many it holds ('More filters, 1 set'); in forced colours the pressed filter takes the system selection pair (Highlight / HighlightText) and the others do not, as the kind chips' selected state did. While the search field holds text it shows a 'Clear' cross at its end (a button named 'Clear search', 24 px target, 14 px icon, --muted-foreground, at 3:1 or more, vertically centred, within 16 px of the field's end); it is gone when the field is empty; Tab from the field reaches it and Enter activates it; a press empties the field, keeps the caret in the field (also while the pointer is down) and leaves Search exactly as emptying the field by keyboard does; by pointer five times in a row across new searches, each first click clears and the × sits in the same place every round and does not move while pressed; the field after Clear is the same as after Ctrl+A, Backspace, with no empty block left in it. Escape in the field does not empty it (unchanged).",
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
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "PF1': the kind words come from what the file is, using the existing display/fileKind.ts recognition (extension and media type), so .eml and message/* are 'Mail', and a filter selects exactly the files shown under its word (if the Engine's kind for mail is 'binary', the filter must select by the same recognition, e.g. via the file-type filter More filters already offers; never select other 'binary' files as Mail). One word list, not a second one. PF4': the × stays at the field's end in every state, including after a pointer Clear and on the next search; pressing it never moves it or the field; the field is in the same state after Clear as after Ctrl+A Backspace (no leftover empty block).",
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

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "5a45532b-8cb3-46a5-b8d4-e2cf9739790d",
  "verdict": "accept",
  "digest": "sha256:7e323f14b178d087103cbc92d23ab08d0f376cbd2ab26fc335ddf865cd67d4f8"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "3e3b8057-c591-41e1-b6a8-b9446cc33ec1",
  "verdict": "accept",
  "digest": "sha256:390873d8e43e0291a503203d0524c9e64df611123e3fad8cbab7d8a9d3d1bc9a"
}
```
