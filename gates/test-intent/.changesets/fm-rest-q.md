---
schema: test-intent.v1
task: t-20261113-fmimpl
author-role: builder
author-session: fm-rest-q-builder
---

# Search's emptied field is the idle screen; the pane hides a scan's page markers

The packaged-app comparison by the author of the first-minutes pictures found two misses: "Emptying
the search field keeps the old results on screen; recent searches show only after Escape." (fm-03,
tmp/shots/fm-03x-cleared-by-button-results-kept-dark.png against the drawn idle screen fm-03-search-idle)
and "Side pane on a scan shows '--- OCR page 1 ---'; result rows strip the marker
(views/searchResultViewModel.ts:110), the pane does not." (fm-05). They are repaired under the
orchestrator's delegated decisions Q1-Q7 (task t-20261113-fmimpl, 2026-11-17), within the owner's
request "takeover the remaining work and proceed until you think its done".

What changed: in Search mode, after a search, a field emptied by the Clear button or by deleting its
text (select-all and Backspace included) moves the window to the same idle layout Escape requests
(the headline, the empty focused field, recent searches); the result list, its count line and filter
row go with it, an open side pane closes, and a late answer to the earlier search cannot bring the
list back (the list renders only in the docked layout). Nothing else moves: no draft is approved or
discarded, no conversation is changed and nothing is sent. In the pane's reading view (source lines
for a PDF, rendered Markdown blocks) a line that is only "--- OCR page N ---" is not shown; the
pane's raw content, offsets and line numbers are unchanged, and the developer source view still
shows the markers. The marker rule is one shared module (views/scanPageMarker.ts) read by the result
row's snippet and the pane.

Two new files are isolated new test sources (exempt): chrome/FirstMinutes.clearedField-lockdown.test.ts
(real Shell in headless Chromium: Q1 by Clear and by keyboard in dark and light, at 600 px with 200 %
text, Q2 a held answer released after Clear, Q5 reload with an emptied field, Q6 the pane on a
scanned PDF in dark and light) and components/documentPane/DocumentPane.scanMarker.test.ts (Q6 PDF
source lines, rendered Markdown, found words and a longer line holding the marker words; Q7 citation
tint, excerpt witness confirmed and refused, developer source view). On the base b63294eff8 every Q1,
Q2 and Q6 case fails by assertion and every Q5 and Q7 case passes (preserved behaviour). Their
repair cases are in the evidence file below with the repair cases of the modified file.

The one modified existing test file is SearchV3View.searchMode.test.ts. Q2 changes what its case
"shows nothing for a cleared query, even when the earlier request answers late" pinned: it expected
the old row to stay on screen after the field was emptied and the earlier request answered. The new
cases of its added "fm-rest Q1-Q5" block that pin repaired behaviour fail by assertion on the base;
its preserved cases (a new query after emptying searches, emptying changes no conversation, sends
nothing and stores only the raw draft and the session recovery attachment, a restored draft under
review is neither approved nor discarded, leaving Search and coming back with a query shows its
results, a reload with an emptied field opens idle) pass on the base and on the candidate. The Clear
cases of chrome/FirstMinutes.filterRow-lockdown.test.ts that assert Clear and keyboard clearing give
the same screen were not changed: both now reach the idle screen, so the parity they assert still
holds and they pass unchanged.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "New",
      "behaviour": "Repaired: in Search mode after a search, emptying the field by the Clear button or by deleting its text shows the idle screen as Escape does (composer state hero, the headline, the empty field, no results region, no rows, the side pane closed, the window no longer marked search-results); a response to the earlier search that lands after the field was emptied leaves the screen idle with no rows. The existing case 'shows nothing for a cleared query, even when the earlier request answers late' expected the old row ['alpha.md'] to stay; it now expects no rows and the hero state, the field still empty. Preserved (pass before and after): a new query typed after emptying and Enter searches and docks with its rows; emptying changes no conversation, sends nothing, makes no write request and changes no stored value except the raw draft and the session recovery attachment; emptying a restored draft under review leaves the review open and Enter still searches nothing; leaving Search and coming back with a query present shows its results; a reload with an emptied field opens on the idle screen.",
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
          "delegationScope": "finish the remaining first-minutes design repairs found by comparing the packaged app with the pictures",
          "choice": "Q1: in Search mode, after a search, emptying the field (the Clear button, or deleting all its text by keyboard, including select-all and Backspace) shows the idle screen as Escape does today: the headline, the empty focused field, recent searches when there are some; the old results, their count line and the filter row are gone; an open side pane closes. Q2: a response to the earlier search that arrives after the field was emptied does not bring results back and does not move the screen out of idle. Q3: typing a new query after emptying and pressing Enter searches as today; Escape does not empty the field. Q4: emptying never approves or discards a restored draft under review, never deletes, leaves or changes a conversation, never starts New, and writes no stored value other than what an empty committed draft writes today (the raw draft and the session recovery attachment). Q5: leaving Search and coming back with a query present still shows its results; reloading with an empty field shows the idle screen.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fm-rest-q.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#shows nothing for a cleared query, even when the earlier request answers late",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#after a search, the Clear button shows the idle screen and closes the pane",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#after a search, deleting the text shows the idle screen and closes the pane",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#a response to the earlier search that lands after the field was emptied brings nothing back"
      ],
      "repaired": true
    }
  ]
}
```

## Acceptance records
