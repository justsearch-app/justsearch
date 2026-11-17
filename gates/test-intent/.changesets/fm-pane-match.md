---
schema: test-intent.v1
task: t-20261113-fmimpl
author-role: builder
author-session: fm-pane-match-builder
---

# Search: a result opens in the side pane at the place that matched

The packaged-app comparison by the author of the first-minutes pictures found: "Left out as agreed:
the 'Place 1 of N' stepper. Consequence: for a long file the pane shows its beginning only ('Showing
the beginning of a longer document')." (tmp/shots/fm-05b-pane-long-manual-harbour-dark.png: query
"harbour", result long-manual.md.) It is repaired under the delegated scenarios M1-M8 (task
t-20261113-fmimpl), within the owner's request "takeover the remaining work and proceed until you think its done".

What changed: opening a search result in Search's side pane passes the result's first returned
passage (the first excerpt region with text, as the row shows it; its offsets must be whole numbers,
start at or after 0 and end after the start) from the result list in memory to the reader. The
reader asks /api/preview for the head as before when the passage ends inside it, otherwise for the
window around it; it confirms the passage's text at its offsets with the citation witness, tints the
passage with the chunk tier, scrolls it into view (also when the window is put back after leaving
Search) and notes a window that does not start at the beginning as "Showing the part of this
document around the match." When the passage is not confirmed (text not there, past the end of the
file, beyond what can be loaded) or /api/preview refuses its window, the reader opens the file from
the beginning, with today's note and no notice. Choosing the file already open at a passage keeps
that passage. Citations, the shared selection's shape and every stored value are unchanged; panes
opened from anywhere else get no passage. No Engine change, no stepper.

No existing test was modified. All five test files are new and isolated (exempt):
components/documentPane/DocumentPane.match.test.ts, views/search-v3/SearchV3View.resultMatch.test.ts
and chrome/FirstMinutes.paneMatch-lockdown.test.ts (entries below, their 20 repaired checks in the
evidence file: each fails on the base af6d2466a1 by assertion and passes on the candidate), plus
views/search-v3/sv3-result-match.test.ts (which passage is taken and which is kept, for the new
module) and state/searchState.excerptPositions.test.ts (the search state keeps each region's
startChar and endChar beside its text; it passes on the base too, where the positions were already
kept at run time and only the type left them out). The preserved cases named in each entry pass on
the base and the candidate.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/documentPane/DocumentPane.match.test.ts"
      ],
      "change": "added",
      "kind": "test-code",
      "class": "New",
      "behaviour": "Repaired (fail on the base by assertion, pass on the candidate): the reader given a search result's passage (match: startChar, endChar, the passage text) for a 14,400-character document asks /api/preview once, at an offset above 0 and not past the passage, covering it, when the passage lies past the first window, and once from offset 0 when it lies inside it; either way the passage's line alone is tinted, the pane scrolls to it and the found word is marked in it; a window that does not start at offset 0 is noted 'Showing the part of this document around the match.'; a passage whose text is not at its offsets, that lies past the end of a shorter file, or whose window /api/preview refuses (HTTP 400) is tried once and then the file opens from offset 0 with the note 'Showing the beginning of a longer document.', no notice or failure, nothing tinted, nothing scrolled; a second document chosen while the first one's window is held ends on the second one's passage and note; in a scan the passage after a '--- OCR page 9 ---' line is found and tinted, no page-marker line is shown, and its line number addresses the raw text. Preserved (pass on the base and the candidate): a citation keeps the note 'around the cited passage' and its tint; offsets missing, not numbers, not finite or inverted ask only from offset 0 and open as today; a passage inside the first window that is not confirmed opens with one request and no notice; a lost preview answer shows 'This source could not be read. Please try again when the index is ready.'; a citation set beside a match is the one read, and its unconfirmed-passage notice is still shown.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "takeover the remaining work and proceed until you think its done",
          "location": "task t-20261113-fmimpl request, the user's turn of 2026-11-17"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "takeover the remaining work and proceed until you think its done",
          "delegationLocation": "task t-20261113-fmimpl request",
          "delegationScope": "finish the remaining first-minutes design repairs found by comparing the packaged app with the pictures; here the side pane opened from a search result showing the file's beginning instead of the place that matched (fm-05b, query \"harbour\", long-manual.md)",
          "choice": "M1 deliver: Search mode, a result with at least one returned passage: opening it in the side pane (click, keyboard, and moving to another result while the pane is open) shows that result's first returned passage inside the pane's visible area without the person scrolling, with the found words highlighted as today, also when the passage lies beyond the first window of a long file (the pane requests the window around it) and when the file fits in one window but the passage is below the fold. M2 deliver: when the shown window does not start at the beginning of the file the pane says \"Showing the part of this document around the match.\" (not \"the cited passage\", not \"the beginning\"). Wording for citations is unchanged. M3 deliver: if the passage cannot be confirmed at its position (the file changed since it was read, the offsets are missing or not numbers, or lie outside what can be loaded) the pane opens exactly as today: the beginning of the file, today's note, no warning about a cited passage. The result row's passage is a hint, not a claim. M4 keep: Ask citations and claim highlights (anchor, witness, tiers, notices, late claim matches) are unchanged. A pane opened from anywhere else (Library, Browse, another window's selection, a restored conversation) opens as today. M5 keep: nothing new is stored and the shared selection's shape is unchanged: the position is taken from the result list in memory at the moment of opening. After a reload, or when the results are no longer the ones the selection came from, the pane opens as today. M6 deliver: races. A newer search answer arriving while the pane is open does not move the pane; selecting the same result again does not jump; selecting a second result while the first one's window is still loading ends on the second one's passage; emptying the field closes the pane as today; a result without passages opens at the beginning. M7 keep: a scan's page-marker lines stay hidden in the reading view and the passage after a marker is still found (offsets and line numbers address the raw text). M8 keep: no stepper, no \"Place 1 of N\", no Engine change. views/search-v3/SearchV3View.ts stays under its size ceiling (2924 lines; it has 2904): put new logic into an existing or new module of views/search-v3/.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fm-pane-match.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/documentPane/DocumentPane.match.test.ts#a passage beyond the first window: the window around it is asked for, the passage tinted, found words marked, scrolled to",
        "modules/ui-web/src/shell-v0/components/documentPane/DocumentPane.match.test.ts#a passage inside the first window but below the fold: the beginning is asked for, the passage tinted and scrolled to",
        "modules/ui-web/src/shell-v0/components/documentPane/DocumentPane.match.test.ts#says \"around the match\", not the cited passage and not the beginning",
        "modules/ui-web/src/shell-v0/components/documentPane/DocumentPane.match.test.ts#the file changed since it was read: the window around the passage is tried, then the beginning",
        "modules/ui-web/src/shell-v0/components/documentPane/DocumentPane.match.test.ts#the file is now shorter than the passage's position",
        "modules/ui-web/src/shell-v0/components/documentPane/DocumentPane.match.test.ts#the preview refuses the window around the passage: the beginning, no failure shown",
        "modules/ui-web/src/shell-v0/components/documentPane/DocumentPane.match.test.ts#ends on the second document's passage",
        "modules/ui-web/src/shell-v0/components/documentPane/DocumentPane.match.test.ts#stay hidden, and the passage after a marker is found and tinted"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.resultMatch.test.ts"
      ],
      "change": "added",
      "kind": "test-code",
      "class": "New",
      "behaviour": "Repaired (fail on the base by assertion, pass on the candidate): in the real Search window with the real shared search store, a result whose answer carries an excerpt region with offsets, opened by card-open, makes the pane ask /api/preview at an offset above 0 and not past the passage, tint the passage and say 'around the match'; opening a second result while the pane is open moves to that result's passage; a newer answer to the same search whose first region for the open file is another passage leaves the pane's passage object and requests unchanged, and choosing the same result again sends no request and keeps the passage; choosing a second result while the first one's preview is held ends on the second one's passage. Preserved (pass on the base and the candidate): regions whose offsets are not numbers or are inverted open the file from offset 0 with no tint, no notice and the beginning note; the shared selection (setSelected + setOpen) opening a listed file after another result was open at its passage requests that file only from offset 0 and tints nothing; after opening a result no localStorage or sessionStorage value holds the passage's offset or 'startChar' and the shared selection is unchanged; a result without regions opened after one with a passage requests only offset 0; emptying the field closes the pane.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "takeover the remaining work and proceed until you think its done",
          "location": "task t-20261113-fmimpl request, the user's turn of 2026-11-17"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "takeover the remaining work and proceed until you think its done",
          "delegationLocation": "task t-20261113-fmimpl request",
          "delegationScope": "finish the remaining first-minutes design repairs found by comparing the packaged app with the pictures; here the side pane opened from a search result showing the file's beginning instead of the place that matched (fm-05b, query \"harbour\", long-manual.md)",
          "choice": "M1 deliver: Search mode, a result with at least one returned passage: opening it in the side pane (click, keyboard, and moving to another result while the pane is open) shows that result's first returned passage inside the pane's visible area without the person scrolling, with the found words highlighted as today, also when the passage lies beyond the first window of a long file (the pane requests the window around it) and when the file fits in one window but the passage is below the fold. M2 deliver: when the shown window does not start at the beginning of the file the pane says \"Showing the part of this document around the match.\" (not \"the cited passage\", not \"the beginning\"). Wording for citations is unchanged. M3 deliver: if the passage cannot be confirmed at its position (the file changed since it was read, the offsets are missing or not numbers, or lie outside what can be loaded) the pane opens exactly as today: the beginning of the file, today's note, no warning about a cited passage. The result row's passage is a hint, not a claim. M4 keep: Ask citations and claim highlights (anchor, witness, tiers, notices, late claim matches) are unchanged. A pane opened from anywhere else (Library, Browse, another window's selection, a restored conversation) opens as today. M5 keep: nothing new is stored and the shared selection's shape is unchanged: the position is taken from the result list in memory at the moment of opening. After a reload, or when the results are no longer the ones the selection came from, the pane opens as today. M6 deliver: races. A newer search answer arriving while the pane is open does not move the pane; selecting the same result again does not jump; selecting a second result while the first one's window is still loading ends on the second one's passage; emptying the field closes the pane as today; a result without passages opens at the beginning. M7 keep: a scan's page-marker lines stay hidden in the reading view and the passage after a marker is still found (offsets and line numbers address the raw text). M8 keep: no stepper, no \"Place 1 of N\", no Engine change. views/search-v3/SearchV3View.ts stays under its size ceiling (2924 lines; it has 2904): put new logic into an existing or new module of views/search-v3/.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fm-pane-match.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.resultMatch.test.ts#asks the reader for the part around the passage and tints it",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.resultMatch.test.ts#moving to another result while the pane is open shows that result's passage",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.resultMatch.test.ts#a newer search answer while the pane is open does not move it, and choosing the same result again does not jump",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.resultMatch.test.ts#choosing a second result while the first one's window is loading ends on the second one's passage"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/FirstMinutes.paneMatch-lockdown.test.ts"
      ],
      "change": "added",
      "kind": "test-code",
      "class": "New",
      "behaviour": "Repaired (fail on the base by assertion, pass on the candidate): the real Shell and Search in headless Chromium at 1024x700, dark and light, over a stubbed backend whose /api/preview serves by offset: a result for long-manual.md whose passage sits near character 12,000, opened by click, is requested at an offset above 0 and its tinted passage lies wholly inside the pane's scroll area and the window with 'harbour' marked, the note 'around the match'; short-notes.txt, which fits in one window, opened by keyboard (Enter on the row) is requested once from offset 0 and its passage below the fold is wholly visible, the scroll area scrolled; moving from one result to the other while the pane is open (dark), a preview answering 1.5 s late (light), 600x700 with 200 % text (dark), and leaving Search for Help and coming back (light) each end with the long file's passage wholly visible.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "takeover the remaining work and proceed until you think its done",
          "location": "task t-20261113-fmimpl request, the user's turn of 2026-11-17"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "takeover the remaining work and proceed until you think its done",
          "delegationLocation": "task t-20261113-fmimpl request",
          "delegationScope": "finish the remaining first-minutes design repairs found by comparing the packaged app with the pictures; here the side pane opened from a search result showing the file's beginning instead of the place that matched (fm-05b, query \"harbour\", long-manual.md)",
          "choice": "M1 deliver: Search mode, a result with at least one returned passage: opening it in the side pane (click, keyboard, and moving to another result while the pane is open) shows that result's first returned passage inside the pane's visible area without the person scrolling, with the found words highlighted as today, also when the passage lies beyond the first window of a long file (the pane requests the window around it) and when the file fits in one window but the passage is below the fold. M2 deliver: when the shown window does not start at the beginning of the file the pane says \"Showing the part of this document around the match.\" (not \"the cited passage\", not \"the beginning\"). Wording for citations is unchanged. M3 deliver: if the passage cannot be confirmed at its position (the file changed since it was read, the offsets are missing or not numbers, or lie outside what can be loaded) the pane opens exactly as today: the beginning of the file, today's note, no warning about a cited passage. The result row's passage is a hint, not a claim. M4 keep: Ask citations and claim highlights (anchor, witness, tiers, notices, late claim matches) are unchanged. A pane opened from anywhere else (Library, Browse, another window's selection, a restored conversation) opens as today. M5 keep: nothing new is stored and the shared selection's shape is unchanged: the position is taken from the result list in memory at the moment of opening. After a reload, or when the results are no longer the ones the selection came from, the pane opens as today. M6 deliver: races. A newer search answer arriving while the pane is open does not move the pane; selecting the same result again does not jump; selecting a second result while the first one's window is still loading ends on the second one's passage; emptying the field closes the pane as today; a result without passages opens at the beginning. M7 keep: a scan's page-marker lines stay hidden in the reading view and the passage after a marker is still found (offsets and line numbers address the raw text). M8 keep: no stepper, no \"Place 1 of N\", no Engine change. views/search-v3/SearchV3View.ts stays under its size ceiling (2924 lines; it has 2904): put new logic into an existing or new module of views/search-v3/.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fm-pane-match.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/FirstMinutes.paneMatch-lockdown.test.ts#a long file: the window around the passage, the passage visible without scrolling, its found word marked (dark)",
        "modules/ui-web/src/shell-v0/chrome/FirstMinutes.paneMatch-lockdown.test.ts#a long file: the window around the passage, the passage visible without scrolling, its found word marked (light)",
        "modules/ui-web/src/shell-v0/chrome/FirstMinutes.paneMatch-lockdown.test.ts#a file that fits in one window, opened by keyboard: the passage below the fold is visible without scrolling (dark)",
        "modules/ui-web/src/shell-v0/chrome/FirstMinutes.paneMatch-lockdown.test.ts#a file that fits in one window, opened by keyboard: the passage below the fold is visible without scrolling (light)",
        "modules/ui-web/src/shell-v0/chrome/FirstMinutes.paneMatch-lockdown.test.ts#moving to another result while the pane is open shows that result's passage (dark)",
        "modules/ui-web/src/shell-v0/chrome/FirstMinutes.paneMatch-lockdown.test.ts#a preview that answers late still lands on the passage (light)",
        "modules/ui-web/src/shell-v0/chrome/FirstMinutes.paneMatch-lockdown.test.ts#at 600 px wide with 200 % text the passage is visible without scrolling (dark)",
        "modules/ui-web/src/shell-v0/chrome/FirstMinutes.paneMatch-lockdown.test.ts#leaving Search and coming back shows the passage again (light)"
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
  "session": "verifier-dcu2ampi",
  "verdict": "accept",
  "digest": "sha256:de5392932b6903a91bddb72a7cb734a67f9a2105a26540585404b80e7ebfaad0"
}
```
