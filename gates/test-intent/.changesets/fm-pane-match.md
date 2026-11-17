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
window around it; it confirms the passage only when the file's text at exactly its span is the
passage's whole text, tints the passage with the chunk tier, scrolls so that the passage's first
line sits an eighth of the way into the pane's scroll area (also when the window is put back after
leaving Search) and notes a window that does not start at the beginning as "Showing the part of
this document around the match." When the passage is not confirmed (other text at its span, past
the end of the file, beyond what can be loaded) or /api/preview refuses its window, the reader opens
the file from the beginning, with today's note and no notice. Opening a result always takes that
result's own passage; only when it is the file and the identical span and text the pane already
shows does nothing reload or move. Citations, the shared selection's shape and every stored value
are unchanged; panes opened from anywhere else get no passage. No Engine change, no stepper.

No existing test was modified. All five test files are new and isolated (exempt):
components/documentPane/DocumentPane.match.test.ts, views/search-v3/SearchV3View.resultMatch.test.ts
and chrome/FirstMinutes.paneMatch-lockdown.test.ts (entries below, their 44 repaired checks in the
evidence file, 31 in the previous version: each fails on the base f7103d5ea2 by assertion and passes
on the candidate), plus views/search-v3/sv3-result-match.test.ts (which passage is taken and which
is kept, for the new module) and state/searchState.excerptPositions.test.ts (the search state keeps
each region's startChar and endChar beside its text, and a chunk hit's chunk_start_char and
chunk_end_char; its whole-file case passes on the base, where the positions were already kept at
run time and only the type left them out, and its chunk case, "R1 a chunk hit keeps its chunk's
place in the file as the answer gave it, its regions untouched", fails on the base by assertion:
the base keeps no chunk). The preserved cases named in each entry pass on the base and the
candidate.

Repair after a failed verification. The packaged-app verification found that a long file, found
through one of its chunks, opened at the top (or, in a file of identical lines, at a wrong place
tinted as the match) because the result's region positions count from the chunk's start, not from
the file's, and that a passage that vanished left the older line-based tint showing. The search
state now carries a chunk hit's `chunk_start_char`/`chunk_end_char` (only when `parent_doc_id` is
non-empty, as SearchResponseBuilder marks a chunk hit); the passage is placed at chunk start plus
region span, and a chunk hit without a whole non-negative chunk start, or with a region beyond the
chunk's length, has no position; a pane opened at a match never falls back to the line-based tints.

What changed in each test file of this branch since the accepted version (digest de539293...):
- components/documentPane/DocumentPane.match.test.ts: added "R4 an unconfirmed match brings back no
  older line-based tint" (text differs, passage gone, file shorter, no usable position, each with
  highlightRange and chunkRange populated: nothing tinted, the beginning, today's note, no notice)
  and one keep case (without a match the line-based tints show as always). Existing cases unchanged:
  they give the reader a file position, which is what the anchor is.
- views/search-v3/SearchV3View.resultMatch.test.ts: the long files' hits (long-manual.txt,
  guide.txt) were whole-file hits with file positions; they are now chunk hits in the Engine's wire
  shape (fields.parent_doc_id, chunk_start_char, chunk_end_char as strings; regions counted from the
  chunk start), and the M3/M6 fixtures feed chunk-relative offsets accordingly. The recorded preview
  requests carry maxChars. Added R1 (deep-long.txt, chunk at 249,619, region 345-765), R2 (five
  unusable chunk starts on a file whose top holds the region's text at its chunk-relative numbers,
  and a first chunk starting at 3), R3 (identical lines, chunk at line 2,400: the requested window
  and the tinted block's file position) and R5 (whole-file hits past the first window and in the
  first lines). R2's five no-position cases pass on f7103d5ea2 (no feature there) and so are not in
  the evidence; on the rejected candidate bc129b9fa3 they fail by assertion (the region's text at
  345 was tinted).
- chrome/FirstMinutes.paneMatch-lockdown.test.ts: the long manual's hit is now a chunk hit as the
  Engine sends it; added deep-long.txt (chunk at 249,619, region at 345) and a file of identical
  lines (chunk at line 2,400), each asserting the requested window, the tinted line's file position
  and its box wholly inside the pane and the window. The recorded preview requests carry maxChars.
- views/search-v3/sv3-result-match.test.ts (exempt): chunk-hit placement and the no-position cases.
- state/searchState.excerptPositions.test.ts (exempt): a chunk hit keeps chunk_start_char and
  chunk_end_char as given and its regions untouched; a whole-file hit has no chunk.

Narrowing after the second verification. The packaged-app verification found two failures: opening
a file the pane already showed at another search's passage kept the old passage tinted with the
note "around the match" and no word marked (two-words.txt: "frostlatch", then "mossbeacon"), and a
passage whose paragraph was replaced with its first line kept was still confirmed and tinted
(m3-vanish.txt); and it noticed that the passage's first line sat about 46 % down the pane, so the
last lines of a deep passage fell below the fold. Three causes, each removed: the rule that kept the
open passage for the same file (sv3NextPaneMatch) is deleted, so a result always brings its own
passage and only the identical one (same file, span and text) is kept as it is; a search passage is
confirmed by the file's text at exactly its span being its whole text (the Engine cuts the passage
out of the file literally: HighlightingOps.computeExcerptRegions and EvidenceSpanSelector), not by
the citation witness (a 48-character prefix found within 64 characters), which citations keep; the
landing scrolls the first line into the upper quarter instead of centring it. A passage taken away
from the open file (a result without passages) also brings the pane back to the beginning instead
of leaving the window "around the match" untinted.

What changed in each test file of this branch since the last accepted version (digest ac022838...):
- components/documentPane/DocumentPane.match.test.ts: added N3 (a three-line paragraph whose first
  line survived while the rest was replaced, past and inside the first window; the text at the span
  differing only in its last character; the passage found 10 characters after its span: nothing
  tinted, nothing scrolled, the beginning, today's note, no notice), N4 keep (an unchanged passage in
  plain text, in Markdown after headings, a list and a table, with CRLF line ends, with umlauts, emoji
  and CJK before and inside it) and N1 (the open file's passage taken away: the beginning). Existing
  cases unchanged.
- views/search-v3/SearchV3View.resultMatch.test.ts: a file two-words.txt with a passage for each of
  two words joins the fixture (eight results instead of seven). The case "a newer search answer while
  the pane is open does not move it, and choosing the same result again does not jump" pinned the
  deleted rule (after a newer answer gave the open file another passage, choosing it again kept the
  old one); it is replaced by "N2 a newer answer for the same search arriving without a click moves
  nothing (keep)" (its first half, unchanged), "a newer answer for the same search gives the open file
  another passage; opening it then shows that passage" (N1, the opposite of its second half) and "N2
  opening the result whose passage the pane shows, after the reader scrolled away: no request, no
  scroll, no change (keep)". Added N1 "two-words.txt open at "frostlatch"; a search for "mossbeacon"
  returns it with another passage; opening it shows that passage", through the field and the real
  search state. Other cases unchanged.
- chrome/FirstMinutes.paneMatch-lockdown.test.ts: two files join the stubbed answer (landing.txt, a
  six-line passage past the first window; tail.txt, a four-line passage that ends the file; six
  results instead of four, the earlier rows keep their places) and N5 measures the boxes in dark and
  light. Other cases unchanged.
- views/search-v3/sv3-result-match.test.ts (exempt): "the file on show at a passage keeps that
  passage" pinned the deleted rule; it is replaced by "N2 the file on show at the identical passage
  (same span and text) keeps the passage on show" and "N1 the file on show at another passage takes
  the result's own" (another span, the same span with other text, the same text with another span,
  no passage).
- state/searchState.excerptPositions.test.ts (exempt): unchanged.

Fail-before against the starting commit df65e94ea2 (the accepted candidate), observed with the new
test files before the production change: the four N3 cases failed by assertion there (the replaced
paragraph past the first window: "expected [ true ] to deeply equal [ true, false ]"; the three inside
the first window: tinted lines where none were expected), as did the N1 cases (window level: "expected
11421 to be greater than or equal to 17722" and "expected { at: 8360 } to deeply equal { at: 17662 }",
the frostlatch passage kept) and N5's deep passage (first line 234.5 px below the box's top where at
most 121.2 px is allowed, dark and light). The N2 and N4 cases passed there. Against the base
f7103d5ea2, which has no passage at all, three N3 cases pass (nothing is tinted there either) and so
are not in the evidence: inside the first window, the last character, 10 characters later.

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
      "behaviour": "Repaired (fail on the base by assertion, pass on the candidate): the reader given a search result's passage (match: startChar, endChar, the passage text) for a 14,400-character document asks /api/preview once, at an offset above 0 and not past the passage, covering it, when the passage lies past the first window, and once from offset 0 when it lies inside it; either way the passage's line alone is tinted, the pane scrolls to it and the found word is marked in it; a window that does not start at offset 0 is noted 'Showing the part of this document around the match.'; a passage whose text is not at its offsets, that lies past the end of a shorter file, or whose window /api/preview refuses (HTTP 400) is tried once and then the file opens from offset 0 with the note 'Showing the beginning of a longer document.', no notice or failure, nothing tinted, nothing scrolled; a second document chosen while the first one's window is held ends on the second one's passage and note; in a scan the passage after a '--- OCR page 9 ---' line is found and tinted, no page-marker line is shown, and its line number addresses the raw text. Preserved (pass on the base and the candidate): a citation keeps the note 'around the cited passage' and its tint; offsets missing, not numbers, not finite or inverted ask only from offset 0 and open as today; a passage inside the first window that is not confirmed opens with one request and no notice; a lost preview answer shows 'This source could not be read. Please try again when the index is ready.'; a citation set beside a match is the one read, and its unconfirmed-passage notice is still shown. Repair R4 (fail on the base by assertion, pass on the candidate): with highlightRange (lines 21-23) and chunkRange (lines 20-28) also set, a match whose text differs inside the first window, whose passage is gone beyond it, that lies past the end of a shorter file, or whose start is not a number leaves nothing tinted in either tier, shows the file from line 0 with the note 'Showing the beginning of a longer document.' and no notice. Preserved: with no match set the line-based ranges tint lines 20-28 as always. Narrowing (fail on the base by assertion, pass on the candidate): N1 the open file's passage taken away (match set to null after the pane opened around it): a second request from offset 0, line 0 shown, nothing tinted, the note 'Showing the beginning of a longer document.'; N3 a three-line paragraph past the first window whose first line survived while the rest was replaced: the window around it is tried, then offset 0, nothing tinted, nothing scrolled, the beginning note, no notice; N4 an unchanged passage is tinted line for line in plain text deep in the file (the note 'around the match', no notice), in Markdown after headings, a list and a table (its lines tinted, the list not), with CRLF line ends deep in the file (the tinted lines are the passage's, less their CR), and with umlauts, emoji and CJK before and inside it (no notice). Preserved on the base, failing by assertion on the starting commit df65e94ea2: N3 inside the first window, the same paragraph replaced with its first line kept, the text at the span differing only in its last character, and the passage found 10 characters after its span: one request from offset 0, nothing tinted, nothing scrolled, the beginning note, no notice.",
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
          "choice": "M1 deliver: Search mode, a result with at least one returned passage: opening it in the side pane (click, keyboard, and moving to another result while the pane is open) shows that result's first returned passage inside the pane's visible area without the person scrolling, with the found words highlighted as today, also when the passage lies beyond the first window of a long file (the pane requests the window around it) and when the file fits in one window but the passage is below the fold. M2 deliver: when the shown window does not start at the beginning of the file the pane says \"Showing the part of this document around the match.\" (not \"the cited passage\", not \"the beginning\"). Wording for citations is unchanged. M3 deliver: if the passage cannot be confirmed at its position (the file changed since it was read, the offsets are missing or not numbers, or lie outside what can be loaded) the pane opens exactly as today: the beginning of the file, today's note, no warning about a cited passage. The result row's passage is a hint, not a claim. M4 keep: Ask citations and claim highlights (anchor, witness, tiers, notices, late claim matches) are unchanged. A pane opened from anywhere else (Library, Browse, another window's selection, a restored conversation) opens as today. M5 keep: nothing new is stored and the shared selection's shape is unchanged: the position is taken from the result list in memory at the moment of opening. After a reload, or when the results are no longer the ones the selection came from, the pane opens as today. M6 deliver: races. A newer search answer arriving while the pane is open does not move the pane; selecting the same result again does not jump; selecting a second result while the first one's window is still loading ends on the second one's passage; emptying the field closes the pane as today; a result without passages opens at the beginning. M7 keep: a scan's page-marker lines stay hidden in the reading view and the passage after a marker is still found (offsets and line numbers address the raw text). M8 keep: no stepper, no \"Place 1 of N\", no Engine change. views/search-v3/SearchV3View.ts stays under its size ceiling (2924 lines; it has 2904): put new logic into an existing or new module of views/search-v3/. Repair after a failed verification (scenarios R1-R7, the same delegation): R1 deliver: a chunk hit as the Engine sends it (raw answer with fields.parent_doc_id, fields.chunk_start_char = 249619, fields.chunk_end_char, a region at 345-765 with its text) opens the pane with a preview request around file position 249,964, the passage tinted and in view, the note \"Showing the part of this document around the match.\" Driven through the real mapping and state, from a raw HTTP answer, not from a constructed anchor. R2 deliver: chunk hits without a usable chunk start (field missing; \"abc\"; \"-5\"; \"12.5\"; region end beyond chunk_end_char - chunk_start_char) give no position: the pane requests the beginning only, tints nothing, says today's note. A first chunk whose start is not 0 (leading whitespace stripped, e.g. chunk_start_char \"3\") is placed at 3 + region start. R3 deliver: repeated text. A file of identical lines, the passage in a chunk far down: the pane lands on that passage's position (assert the requested window and the tinted block's position in the text, not only that a text check succeeded); the identical lines near the top are not tinted. R4 deliver: a match that cannot be confirmed (text at the position differs; file now shorter; passage gone) with the older line-based range also populated: nothing is tinted, the pane is at the beginning with today's note, no warning about a cited passage. R5 keep: whole-file hits (no parent_doc_id) are placed at the region's own span; first-lines files open at the top with the word visible; the row's passage and its highlighted words are unchanged; top-level match spans untouched. R6 keep: everything the first build's scenarios said and the verification passed: citations and their wording and precedence; pane opened from Library, Browse, another window's selection, a restored conversation or after reload has no passage; nothing stored (session storage, shared selection); same-result and newer-answer do not move the pane; second result while the first loads; emptying the field; no stepper; views/search-v3/SearchV3View.ts under 2924 lines (it has 2914). R7 keep: a window start that /api/preview refuses (for example inside an emoji's surrogate pair) or a lost answer does not produce a new error state: the existing behaviours of the first build hold (refused window: the beginning, silently; lost answer: today's error). Narrowing after the second verification (scenarios N1-N6, the same delegation; N1 replaces the part of M6 and R6 that kept the open passage when the same file was opened again: 'selecting the same result again does not jump' now holds only for the identical passage, N2): N1 deliver: the pane open on file F at the passage of a first search; a second search for another word returns F with a different passage; opening that result shows the second passage (the window around it, tinted, its words marked, the note \"Showing the part of this document around the match.\"). Driven through the real search state from raw answers in the Engine's wire shape. Opening a result always takes that result's own passage; only when it is the same file and the identical span and text as the one the pane already shows does nothing reload or move. N2 keep: opening the result whose passage the pane already shows (same file, same span and text), after the reader scrolled away: no request, no scroll, no change. A newer answer for the same search arriving without a click moves nothing. A second result clicked while the first loads ends on the second. N3 deliver: a search passage (the match, not a citation) is confirmed only when the file's text at exactly its span is the passage's whole text (the Engine cuts the passage's text out of the file's text literally: HighlightingOps.computeExcerptRegions, EvidenceSpanSelector.toExcerptRegion); no prefix comparison, no search around the position. The file's text at the span begins like the passage but differs later (the paragraph was replaced, its first line kept): nothing is tinted, the pane is at the beginning with the ordinary note, no warning. Also: differs only in its last character; the passage is found 10 characters later than its span (no longer accepted). Citations keep their own check. N4 keep: an unchanged passage is confirmed in plain text, in Markdown with headings, a list and a table before it, with CRLF line ends, with non-ASCII text (umlauts, emoji, CJK) before and inside it, and after a scan's page-marker line; a chunk hit is placed at chunk start plus region span; a chunk hit without a usable start has no position; citations and their notices, tiers and late claim matches are unchanged (their tests are untouched and pass). N5 deliver: after landing, the top of the passage's first line is within the upper quarter of the pane's visible box and, for a passage shorter than three quarters of that box, its bottom is inside the box (browser test, boxes; 1024x700; dark and light; a deep passage and one near the end of the file where there is not enough text below: then it is simply visible). N6 keep: everything else the earlier scenarios said (nothing stored; a pane opened from elsewhere has no passage; emptying the field closes the pane; no stepper; views/search-v3/SearchV3View.ts under 2924 lines (it has 2914)).",
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
        "modules/ui-web/src/shell-v0/components/documentPane/DocumentPane.match.test.ts#stay hidden, and the passage after a marker is found and tinted",
        "modules/ui-web/src/shell-v0/components/documentPane/DocumentPane.match.test.ts#the text at the position differs (inside the first window)",
        "modules/ui-web/src/shell-v0/components/documentPane/DocumentPane.match.test.ts#the passage is gone (beyond the first window)",
        "modules/ui-web/src/shell-v0/components/documentPane/DocumentPane.match.test.ts#the file is now shorter than the position",
        "modules/ui-web/src/shell-v0/components/documentPane/DocumentPane.match.test.ts#a match without a usable position",
        "modules/ui-web/src/shell-v0/components/documentPane/DocumentPane.match.test.ts#the paragraph was replaced, its first line kept (beyond the first window): the beginning, nothing tinted",
        "modules/ui-web/src/shell-v0/components/documentPane/DocumentPane.match.test.ts#plain text, three lines deep in the file",
        "modules/ui-web/src/shell-v0/components/documentPane/DocumentPane.match.test.ts#Markdown with headings, a list and a table before the passage",
        "modules/ui-web/src/shell-v0/components/documentPane/DocumentPane.match.test.ts#CRLF line ends, deep in the file",
        "modules/ui-web/src/shell-v0/components/documentPane/DocumentPane.match.test.ts#non-ASCII text (umlauts, emoji, CJK) before and inside the passage",
        "modules/ui-web/src/shell-v0/components/documentPane/DocumentPane.match.test.ts#a passage replaced by none on the open file: the beginning, nothing tinted, today's note"
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
      "behaviour": "Repaired (fail on the base by assertion, pass on the candidate): in the real Search window with the real shared search store, a result found through a chunk of a long file, whose raw answer carries fields.parent_doc_id, chunk_start_char and chunk_end_char (strings) and an excerpt region counted from the chunk's start, opened by card-open, makes the pane ask /api/preview at an offset above 0 and not past the passage, tint the passage and say 'around the match'; opening a second result while the pane is open moves to that result's passage; choosing a second result while the first one's preview is held ends on the second one's passage. Preserved (pass on the base and the candidate): regions whose offsets are not numbers or are inverted open the file from offset 0 with no tint, no notice and the beginning note; the shared selection (setSelected + setOpen) opening a listed file after another result was open at its passage requests that file only from offset 0 and tints nothing; after opening a result no localStorage or sessionStorage value holds the passage's offset or 'startChar' and the shared selection is unchanged; a result without regions opened after one with a passage requests only offset 0; emptying the field closes the pane. Repair (fail on the base by assertion, pass on the candidate): R1 deep-long.txt, chunk_start_char '249619', chunk_end_char '251219', region 345-765: one /api/preview request at an offset above 244,964 and not past 249,964 that covers 250,384, the tinted lines start at file position 249,964 and are the 420-character passage, the note 'around the match', no notice, and the search state's region still reads 345-765; R2 a first chunk starting at '3' with a region counted from it tints the passage at its file position; R3 a file of 3,000 identical 22-character lines, chunk at character 55,200, region 345-459: one request at an offset above 50,545 and not past 55,545 covering the passage, five lines tinted, the first at file position 55,545, the note 'around the match'; R5 whole-file hits (no parent_doc_id): a passage past the first window is requested above offset 0 and tinted at its own file position, and one in the first lines is requested from offset 0, the first line shown, tinted at its position, the beginning note. Preserved: R2 chunk hits whose chunk_start_char is missing, 'abc', '-5' or '12.5', or whose region ends past chunk_end_char minus chunk_start_char, on a file holding the region's text at its chunk-relative numbers, request only offset 0, tint nothing and say 'Showing the beginning of a longer document.' with no notice (these pass on the base, where there is no match at all, and fail on the rejected candidate bc129b9fa3). Narrowing (fail on the base by assertion, pass on the candidate): N1 two-words.txt open at its 'frostlatch' chunk hit; 'mossbeacon' typed into the field and searched, whose answer gives the same file another chunk hit: opening it asks /api/preview at an offset above 0 and not past the mossbeacon passage, covering it, tints that passage at its file position, marks 'mossbeacon' in it and says 'around the match'; the same after a newer answer for the same search gave the open file another passage and the result is opened; N2 a newer answer for the same search whose first region for the open file is another passage, arriving without a click, leaves the pane's passage object, its one request and its tint unchanged; after the same search answered again with the same span and text and the reader scrolled the pane to 37 px, opening that result again keeps the same reader and passage object, sends no request, calls no scroll and leaves scrollTop at 37.",
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
          "choice": "M1 deliver: Search mode, a result with at least one returned passage: opening it in the side pane (click, keyboard, and moving to another result while the pane is open) shows that result's first returned passage inside the pane's visible area without the person scrolling, with the found words highlighted as today, also when the passage lies beyond the first window of a long file (the pane requests the window around it) and when the file fits in one window but the passage is below the fold. M2 deliver: when the shown window does not start at the beginning of the file the pane says \"Showing the part of this document around the match.\" (not \"the cited passage\", not \"the beginning\"). Wording for citations is unchanged. M3 deliver: if the passage cannot be confirmed at its position (the file changed since it was read, the offsets are missing or not numbers, or lie outside what can be loaded) the pane opens exactly as today: the beginning of the file, today's note, no warning about a cited passage. The result row's passage is a hint, not a claim. M4 keep: Ask citations and claim highlights (anchor, witness, tiers, notices, late claim matches) are unchanged. A pane opened from anywhere else (Library, Browse, another window's selection, a restored conversation) opens as today. M5 keep: nothing new is stored and the shared selection's shape is unchanged: the position is taken from the result list in memory at the moment of opening. After a reload, or when the results are no longer the ones the selection came from, the pane opens as today. M6 deliver: races. A newer search answer arriving while the pane is open does not move the pane; selecting the same result again does not jump; selecting a second result while the first one's window is still loading ends on the second one's passage; emptying the field closes the pane as today; a result without passages opens at the beginning. M7 keep: a scan's page-marker lines stay hidden in the reading view and the passage after a marker is still found (offsets and line numbers address the raw text). M8 keep: no stepper, no \"Place 1 of N\", no Engine change. views/search-v3/SearchV3View.ts stays under its size ceiling (2924 lines; it has 2904): put new logic into an existing or new module of views/search-v3/. Repair after a failed verification (scenarios R1-R7, the same delegation): R1 deliver: a chunk hit as the Engine sends it (raw answer with fields.parent_doc_id, fields.chunk_start_char = 249619, fields.chunk_end_char, a region at 345-765 with its text) opens the pane with a preview request around file position 249,964, the passage tinted and in view, the note \"Showing the part of this document around the match.\" Driven through the real mapping and state, from a raw HTTP answer, not from a constructed anchor. R2 deliver: chunk hits without a usable chunk start (field missing; \"abc\"; \"-5\"; \"12.5\"; region end beyond chunk_end_char - chunk_start_char) give no position: the pane requests the beginning only, tints nothing, says today's note. A first chunk whose start is not 0 (leading whitespace stripped, e.g. chunk_start_char \"3\") is placed at 3 + region start. R3 deliver: repeated text. A file of identical lines, the passage in a chunk far down: the pane lands on that passage's position (assert the requested window and the tinted block's position in the text, not only that a text check succeeded); the identical lines near the top are not tinted. R4 deliver: a match that cannot be confirmed (text at the position differs; file now shorter; passage gone) with the older line-based range also populated: nothing is tinted, the pane is at the beginning with today's note, no warning about a cited passage. R5 keep: whole-file hits (no parent_doc_id) are placed at the region's own span; first-lines files open at the top with the word visible; the row's passage and its highlighted words are unchanged; top-level match spans untouched. R6 keep: everything the first build's scenarios said and the verification passed: citations and their wording and precedence; pane opened from Library, Browse, another window's selection, a restored conversation or after reload has no passage; nothing stored (session storage, shared selection); same-result and newer-answer do not move the pane; second result while the first loads; emptying the field; no stepper; views/search-v3/SearchV3View.ts under 2924 lines (it has 2914). R7 keep: a window start that /api/preview refuses (for example inside an emoji's surrogate pair) or a lost answer does not produce a new error state: the existing behaviours of the first build hold (refused window: the beginning, silently; lost answer: today's error). Narrowing after the second verification (scenarios N1-N6, the same delegation; N1 replaces the part of M6 and R6 that kept the open passage when the same file was opened again: 'selecting the same result again does not jump' now holds only for the identical passage, N2): N1 deliver: the pane open on file F at the passage of a first search; a second search for another word returns F with a different passage; opening that result shows the second passage (the window around it, tinted, its words marked, the note \"Showing the part of this document around the match.\"). Driven through the real search state from raw answers in the Engine's wire shape. Opening a result always takes that result's own passage; only when it is the same file and the identical span and text as the one the pane already shows does nothing reload or move. N2 keep: opening the result whose passage the pane already shows (same file, same span and text), after the reader scrolled away: no request, no scroll, no change. A newer answer for the same search arriving without a click moves nothing. A second result clicked while the first loads ends on the second. N3 deliver: a search passage (the match, not a citation) is confirmed only when the file's text at exactly its span is the passage's whole text (the Engine cuts the passage's text out of the file's text literally: HighlightingOps.computeExcerptRegions, EvidenceSpanSelector.toExcerptRegion); no prefix comparison, no search around the position. The file's text at the span begins like the passage but differs later (the paragraph was replaced, its first line kept): nothing is tinted, the pane is at the beginning with the ordinary note, no warning. Also: differs only in its last character; the passage is found 10 characters later than its span (no longer accepted). Citations keep their own check. N4 keep: an unchanged passage is confirmed in plain text, in Markdown with headings, a list and a table before it, with CRLF line ends, with non-ASCII text (umlauts, emoji, CJK) before and inside it, and after a scan's page-marker line; a chunk hit is placed at chunk start plus region span; a chunk hit without a usable start has no position; citations and their notices, tiers and late claim matches are unchanged (their tests are untouched and pass). N5 deliver: after landing, the top of the passage's first line is within the upper quarter of the pane's visible box and, for a passage shorter than three quarters of that box, its bottom is inside the box (browser test, boxes; 1024x700; dark and light; a deep passage and one near the end of the file where there is not enough text below: then it is simply visible). N6 keep: everything else the earlier scenarios said (nothing stored; a pane opened from elsewhere has no passage; emptying the field closes the pane; no stepper; views/search-v3/SearchV3View.ts under 2924 lines (it has 2914)).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fm-pane-match.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.resultMatch.test.ts#asks the reader for the part around the passage and tints it",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.resultMatch.test.ts#moving to another result while the pane is open shows that result's passage",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.resultMatch.test.ts#choosing a second result while the first one's window is loading ends on the second one's passage",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.resultMatch.test.ts#deep-long.txt: chunk at 249,619, region 345-765: the window around 249,964, the passage tinted, the match note",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.resultMatch.test.ts#a first chunk starting at 3 (leading whitespace stripped) is placed at 3 + the region's start",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.resultMatch.test.ts#a file of identical lines, the passage in a chunk at line 2,400: that place, not the same text near the top",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.resultMatch.test.ts#a passage past the first window: the window around it, tinted at its place",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.resultMatch.test.ts#a passage in the first lines: the beginning, the word visible and tinted",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.resultMatch.test.ts#N2 a newer answer for the same search arriving without a click moves nothing (keep)",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.resultMatch.test.ts#N2 opening the result whose passage the pane shows, after the reader scrolled away: no request, no scroll, no change (keep)",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.resultMatch.test.ts#two-words.txt open at \"frostlatch\"; a search for \"mossbeacon\" returns it with another passage; opening it shows that passage",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.resultMatch.test.ts#a newer answer for the same search gives the open file another passage; opening it then shows that passage"
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
      "behaviour": "Repaired (fail on the base by assertion, pass on the candidate): the real Shell and Search in headless Chromium at 1024x700, dark and light, over a stubbed backend whose /api/preview serves by offset: a chunk hit for long-manual.md (parent_doc_id, chunk_start_char and chunk_end_char in fields, region counted from the chunk's start) whose passage sits near character 12,000, opened by click, is requested at an offset above 8,000 and its tinted passage lies wholly inside the pane's scroll area and the window with 'harbour' marked, the note 'around the match'; short-notes.txt, which fits in one window, opened by keyboard (Enter on the row) is requested once from offset 0 and its passage below the fold is wholly visible, the scroll area scrolled; moving from one result to the other while the pane is open (dark), a preview answering 1.5 s late (light), 600x700 with 200 % text (dark), and leaving Search for Help and coming back (light) each end with the long file's passage wholly visible. Repair (fail on the base by assertion, pass on the candidate): R1 deep-long.txt as a chunk hit, chunk_start_char '249619', region at 345 (dark): one request at an offset above 244,964 and not past 249,964 covering the passage, the one tinted line at file position 249,964 is the passage with 'harbour' marked and lies wholly inside the pane's scroll area and the window, the note 'around the match'; R3 a file of 3,000 identical lines as a chunk hit at character 55,200, region 345-459 (light): one request above 50,545 and not past 55,545, five tinted lines starting at file position 55,545, the first wholly inside the pane and the window, the note 'around the match'. Narrowing N5 (fail on the base by assertion, pass on the candidate), dark and light at 1024x700: landing.txt, a six-line passage past the first window (whole-file hit), opened by click: requested above offset 0, six lines tinted, shorter than three quarters of the pane's visible box (the scroll area cut to the window), the first line's top inside the box and at most a quarter of its height below its top, the last line's bottom inside the box; tail.txt, a four-line passage that ends the file: four lines tinted, all inside the box, the scroll area scrolled to its end.",
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
          "choice": "M1 deliver: Search mode, a result with at least one returned passage: opening it in the side pane (click, keyboard, and moving to another result while the pane is open) shows that result's first returned passage inside the pane's visible area without the person scrolling, with the found words highlighted as today, also when the passage lies beyond the first window of a long file (the pane requests the window around it) and when the file fits in one window but the passage is below the fold. M2 deliver: when the shown window does not start at the beginning of the file the pane says \"Showing the part of this document around the match.\" (not \"the cited passage\", not \"the beginning\"). Wording for citations is unchanged. M3 deliver: if the passage cannot be confirmed at its position (the file changed since it was read, the offsets are missing or not numbers, or lie outside what can be loaded) the pane opens exactly as today: the beginning of the file, today's note, no warning about a cited passage. The result row's passage is a hint, not a claim. M4 keep: Ask citations and claim highlights (anchor, witness, tiers, notices, late claim matches) are unchanged. A pane opened from anywhere else (Library, Browse, another window's selection, a restored conversation) opens as today. M5 keep: nothing new is stored and the shared selection's shape is unchanged: the position is taken from the result list in memory at the moment of opening. After a reload, or when the results are no longer the ones the selection came from, the pane opens as today. M6 deliver: races. A newer search answer arriving while the pane is open does not move the pane; selecting the same result again does not jump; selecting a second result while the first one's window is still loading ends on the second one's passage; emptying the field closes the pane as today; a result without passages opens at the beginning. M7 keep: a scan's page-marker lines stay hidden in the reading view and the passage after a marker is still found (offsets and line numbers address the raw text). M8 keep: no stepper, no \"Place 1 of N\", no Engine change. views/search-v3/SearchV3View.ts stays under its size ceiling (2924 lines; it has 2904): put new logic into an existing or new module of views/search-v3/. Repair after a failed verification (scenarios R1-R7, the same delegation): R1 deliver: a chunk hit as the Engine sends it (raw answer with fields.parent_doc_id, fields.chunk_start_char = 249619, fields.chunk_end_char, a region at 345-765 with its text) opens the pane with a preview request around file position 249,964, the passage tinted and in view, the note \"Showing the part of this document around the match.\" Driven through the real mapping and state, from a raw HTTP answer, not from a constructed anchor. R2 deliver: chunk hits without a usable chunk start (field missing; \"abc\"; \"-5\"; \"12.5\"; region end beyond chunk_end_char - chunk_start_char) give no position: the pane requests the beginning only, tints nothing, says today's note. A first chunk whose start is not 0 (leading whitespace stripped, e.g. chunk_start_char \"3\") is placed at 3 + region start. R3 deliver: repeated text. A file of identical lines, the passage in a chunk far down: the pane lands on that passage's position (assert the requested window and the tinted block's position in the text, not only that a text check succeeded); the identical lines near the top are not tinted. R4 deliver: a match that cannot be confirmed (text at the position differs; file now shorter; passage gone) with the older line-based range also populated: nothing is tinted, the pane is at the beginning with today's note, no warning about a cited passage. R5 keep: whole-file hits (no parent_doc_id) are placed at the region's own span; first-lines files open at the top with the word visible; the row's passage and its highlighted words are unchanged; top-level match spans untouched. R6 keep: everything the first build's scenarios said and the verification passed: citations and their wording and precedence; pane opened from Library, Browse, another window's selection, a restored conversation or after reload has no passage; nothing stored (session storage, shared selection); same-result and newer-answer do not move the pane; second result while the first loads; emptying the field; no stepper; views/search-v3/SearchV3View.ts under 2924 lines (it has 2914). R7 keep: a window start that /api/preview refuses (for example inside an emoji's surrogate pair) or a lost answer does not produce a new error state: the existing behaviours of the first build hold (refused window: the beginning, silently; lost answer: today's error). Narrowing after the second verification (scenarios N1-N6, the same delegation; N1 replaces the part of M6 and R6 that kept the open passage when the same file was opened again: 'selecting the same result again does not jump' now holds only for the identical passage, N2): N1 deliver: the pane open on file F at the passage of a first search; a second search for another word returns F with a different passage; opening that result shows the second passage (the window around it, tinted, its words marked, the note \"Showing the part of this document around the match.\"). Driven through the real search state from raw answers in the Engine's wire shape. Opening a result always takes that result's own passage; only when it is the same file and the identical span and text as the one the pane already shows does nothing reload or move. N2 keep: opening the result whose passage the pane already shows (same file, same span and text), after the reader scrolled away: no request, no scroll, no change. A newer answer for the same search arriving without a click moves nothing. A second result clicked while the first loads ends on the second. N3 deliver: a search passage (the match, not a citation) is confirmed only when the file's text at exactly its span is the passage's whole text (the Engine cuts the passage's text out of the file's text literally: HighlightingOps.computeExcerptRegions, EvidenceSpanSelector.toExcerptRegion); no prefix comparison, no search around the position. The file's text at the span begins like the passage but differs later (the paragraph was replaced, its first line kept): nothing is tinted, the pane is at the beginning with the ordinary note, no warning. Also: differs only in its last character; the passage is found 10 characters later than its span (no longer accepted). Citations keep their own check. N4 keep: an unchanged passage is confirmed in plain text, in Markdown with headings, a list and a table before it, with CRLF line ends, with non-ASCII text (umlauts, emoji, CJK) before and inside it, and after a scan's page-marker line; a chunk hit is placed at chunk start plus region span; a chunk hit without a usable start has no position; citations and their notices, tiers and late claim matches are unchanged (their tests are untouched and pass). N5 deliver: after landing, the top of the passage's first line is within the upper quarter of the pane's visible box and, for a passage shorter than three quarters of that box, its bottom is inside the box (browser test, boxes; 1024x700; dark and light; a deep passage and one near the end of the file where there is not enough text below: then it is simply visible). N6 keep: everything else the earlier scenarios said (nothing stored; a pane opened from elsewhere has no passage; emptying the field closes the pane; no stepper; views/search-v3/SearchV3View.ts under 2924 lines (it has 2914)).",
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
        "modules/ui-web/src/shell-v0/chrome/FirstMinutes.paneMatch-lockdown.test.ts#leaving Search and coming back shows the passage again (light)",
        "modules/ui-web/src/shell-v0/chrome/FirstMinutes.paneMatch-lockdown.test.ts#deep-long.txt, chunk at 249,619, region at 345: the window around 249,964, the passage tinted there and visible (dark)",
        "modules/ui-web/src/shell-v0/chrome/FirstMinutes.paneMatch-lockdown.test.ts#a file of identical lines: the five lines at the chunk's place are tinted and visible, none near the top (light)",
        "modules/ui-web/src/shell-v0/chrome/FirstMinutes.paneMatch-lockdown.test.ts#a deep passage of six lines: its first line in the upper quarter, the whole passage inside the pane (dark)",
        "modules/ui-web/src/shell-v0/chrome/FirstMinutes.paneMatch-lockdown.test.ts#a deep passage of six lines: its first line in the upper quarter, the whole passage inside the pane (light)",
        "modules/ui-web/src/shell-v0/chrome/FirstMinutes.paneMatch-lockdown.test.ts#a passage that ends the file, too little text below it: the whole passage inside the pane (dark)",
        "modules/ui-web/src/shell-v0/chrome/FirstMinutes.paneMatch-lockdown.test.ts#a passage that ends the file, too little text below it: the whole passage inside the pane (light)"
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

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-ggl8ogm8",
  "verdict": "accept",
  "digest": "sha256:ac0228383eb377bffcf6b66a41a484d13fab5a5b24a8fbe2a5bd4a3decf1c514"
}
```
