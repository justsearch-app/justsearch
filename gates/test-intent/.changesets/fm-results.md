---
schema: test-intent.v1
task: t-20261113-fmimpl
author-role: builder
author-session: codex-builder-fm-results-20261114
---

# Test-intent entries

5a is the results UI, document pane and nothing-found screen. Decisions E1–E10 and amendments A1–A12 below are delegated agent choices; amendments take precedence. They are not presented as the owner's own wording. The delegated scope and original user turn are retained in every source.

`Obsolete` identifies superseded UI wording, default diagnostic visibility and control locations. The assertions that still apply remain. Diagnostic, citation and contrast checks now opt into the existing developer preference. Pending-response preservation uses an explicitly held response instead of relying on a microtask race. The known facet/pinned-scope stale-response issue remains outside this slice.

Baseline f5fd95ca9d: the 10 originally affected existing files pass 274/274 in the shared clone, which owns separate dependencies. New files: 24 assertion failures and 2 preservation passes on that baseline; the initial 11 checks ran before product changes. Earlier candidate: all 10,074 ordinary unit checks passed. Generated candidate evidence is attached for the five new isolated files. `run-evidence --before` is left to the orchestrator because its worktree creation writes repository Git metadata. The builder writes no acceptance record.

The harness adds dark/light results, scan pane and nothing-found states plus the 600×400 pane pair. The proportion baseline relocates Search filter-editor witnesses from the composer to the existing editor under More filters, retains all field/error/focus checks, and adds viewport checks while visiting each scrollable editor control. The old whole-card versus dock rectangle witness becomes the last visible row versus the top field after scrolling, with both presence and viewport constraints. Unchanged Ask/top-bar baseline failures remain failures: nine rows across five families, all reproduced before 5a. The eight new states pass all 80 constraints; a11y passes all 110 registered states.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/documentPane/DocumentPane.test.ts",
        "modules/ui-web/src/shell-v0/components/searchResults/ResultsCard.searchTrace.test.ts",
        "modules/ui-web/src/shell-v0/components/searchResults/ResultsCard.test.ts",
        "modules/ui-web/src/shell-v0/components/searchResults/matchCountLabel.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.recents.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.search.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.syntax.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "You are supposed to be the implementation session",
          "location": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z"
        },
        {
          "kind": "owner-words",
          "quote": "Six text sizes and four corner radii, nothing in between. Text: 11 \u00b7 12 \u00b7 13 \u00b7 15 \u00b7 20 \u00b7 28. Corners: 4 \u00b7 8 \u00b7 14 \u00b7 fully round. Weight 400 for text, 500 for names and actions, 600 only for the brand.",
          "location": "task t-20261113-fmimpl request (delegated 5a builder brief, Rules that apply verbatim)"
        },
        {
          "kind": "owner-words",
          "quote": "No outlined chips. ... result filters are text.",
          "location": "task t-20261113-fmimpl request (delegated 5a builder brief, Rules that apply verbatim)"
        },
        {
          "kind": "owner-words",
          "quote": "Teal means \"on or working now\" ..., plus the one filled main action on a screen. Tabs, the active area, selected rows and text actions are neutral. A text action is the normal text colour in medium weight; information is grey.",
          "location": "task t-20261113-fmimpl request (delegated 5a builder brief, Rules that apply verbatim)"
        },
        {
          "kind": "owner-words",
          "quote": "What a picture does not show is not on that screen by default: it goes behind Settings \u203a Advanced \u203a Show developer tools, or away.",
          "location": "task t-20261113-fmimpl request (delegated 5a builder brief, Rules that apply verbatim)"
        },
        {
          "kind": "owner-words",
          "quote": "In the app, never set style= on an element: the desktop window drops it.",
          "location": "task t-20261113-fmimpl request (delegated 5a builder brief, Rules that apply verbatim)"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "E1: 5a = UI (results, pane, nothing found) one PR; 5b = Help pages out of results + \"Also in JustSearch help: N pages\" line (Engine query change; quality check) its own PR after 5a. Until 5b, Help rows show as today (named in 5a delivery).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "E2: Search results: the field stays at the top with results under it (fm-04). Ask conversations keep today's composer position (not drawn in this set).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "E3: Summary line left of the filter row: \"N files \u00b7 by words \u00b7 0.04 s\". Mode words from the actual retrieval mode of the response: words-only \"by words\"; hybrid \"by words and meaning\"; meaning-only \"by meaning\"; unknown: omitted. Capped counts keep \"+\". The \"Results for 'query'\" line goes (the query is in the field). Slow/pending states keep their words.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "E4: Filter row (text, no outlined chips, right of the summary): \"All\", then type groups with counts from the Type facet, only groups with a count > 0; \"Any time\" menu (Any time, Past week, Past month, Past year -> modified-from); \"More filters\" opens the existing folder/date/size editor plus the other facets (Format, Language, Author) as text. Active restrictions stay visible and removable as text, also at zero results. Selected = primary text 500; others secondary; keyboard and names as today (aria-pressed).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "E5: Rows: type icon, name (mail with a subject: \"Subject \u00b7 file.eml\"), date at the right (modifiedAt carried into the hit model; absent -> no date), two lines of clean text. Folder trail only when the library has more than one folder (known root count > 1) or the count is unknown; normal UI font in secondary colour (not monospace). Collection badge only with developer tools. MD/JSON/Paths header copy and the per-row \"Why this result?\" leave the default view: the row menu gains \"Why this result\" (opens today's explanation) and \"Copy as Markdown\" / \"Copy as JSON\" for that row; whole-list copy buttons stay only with developer tools on.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "E6: Clean text is display-only in the UI: remove Markdown marks (heading #, emphasis, inline code ticks, link syntax keeping link text, list bullets, quote >, table pipes) and \"--- OCR page N ---\" markers; collapse whitespace. Copy, export, MCP, Ask, citations and jseval keep raw text and offsets.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "E7: Side pane: icon + file name once + Close; one line \"folder path \u00b7 N pages \u00b7 size\" with unknown parts omitted (size from sizeBytes carried from the search hit; pages from existing preview metadata); for a scan one note \"Read from a scan. A few words may be wrong.\"; then the file's text (Markdown rendered as today); footer Open file and Show in folder (Show in folder hidden where unsupported, browser). No place stepper (HANDOVER fallback: the response holds selected passages, not all places); named in delivery. Text source, quality %, OCR details, Rendered/Source switch and provenance go behind developer tools. \"Context\" tab only inside an Ask conversation; a restored pane whose saved tab is Context outside Ask opens the document instead (saved memory is not rewritten until the user changes the pane).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "E8: Nothing found (fm-06): words-only heading \"No file contains these words\" + \"Search looks for the words as typed. Try fewer or different ones.\"; when meaning was used: \"Nothing matches this search\" + \"Try fewer or different words.\" AI row only when meaning was not used and AI is known not installed or installing; \"Open AI Brain\" navigates only, never starts an install. Unknown/stale AI state or meaning already used: no AI row. With filters on: \"Filters are on\" + \"Clear filters\" instead. The query is not repeated. Loading, failed, invalid and stale responses never show this screen.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "E9: Text actions on these screens: normal text colour, weight 500; no teal except running status.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "E10: Folder count for E5/E8 from existing root facts (firstUse/indexedRoots), never from the result page.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "A1 (E3/E8): Mode words from the executed legs, not the retrieval-mode label: \"by words\" only when the search provably ran literal-only (BM25, no SPLADE/dense); with any learned leg \"by words and meaning\"; unknown: no mode word. Nothing found: literal-only -> \"No file contains these words\" / \"Search looks for the words as typed. Try fewer or different ones.\"; otherwise or unknown -> \"Nothing matches this search\" / \"Try fewer or different words.\"",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "A2 (E4): No new type groups in 5a (Mail cannot be derived today; subjects are dropped). The row shows \"All\" plus the Type facet values as text with today's labels, counts and toggle semantics (click toggles that value; All clears the type selection); selected values stay even at zero; counts show only when they come from the current response (pending/stale: values without counts). Other facets in More filters. Mail/Documents grouping and mail subjects -> finding, later.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "A3 (S5-15): The facet/pinned-scope stale-response defect exists on main; not widened into this slice -> finding. Preservation: not worse than main.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "A4 (E2): Placement: the field is at the top whenever the composer tier is Search and results or nothing-found are shown, including Back/Forward returns; whenever the tier is Ask (prepared or conversation) the composer is where it is today. One mounted editor; draft, caret and IME composition survive the move; first and last result rows reachable; at 600 px with the pane open nothing covers the field.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "A5 (E6): Cleaning is format-aware: Markdown marks are removed only for Markdown files (.md, .markdown, .mdx); \"--- OCR page N ---\" markers are removed for all; code and other text keep their punctuation. Clean before display truncation, then highlight.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "A6 (E3): Summary keeps the available/displayed distinction: \"N files\" when all are shown; \"Showing 50 of N files\" when limited; capped \"+\" and existing ranked/hybrid distinctions kept in plain words.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "A7 (E4): \"Any time\" menu: Any time clears both date bounds; Past week/month/year set modified-from to the start of the local day N days back and clear modified-to; custom dates from More filters show as \"Custom dates\" in the menu label; URL/pin semantics of dates unchanged.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "A8 (E7): Restored Context outside Ask: open the saved document if there is one, else keep the pane closed; restoration does not write details memory. \"Inside Ask\" = the pane belongs to an Ask conversation. Citation tabs, anchors and source Hide/Restore preserved.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "A9 (E8/E10): Folder row words: \"N files in M folders can be searched\" (current facts; singular/plural; unknown -> \"Only the folders you added are searched\"), \"Add a folder\" opens today's add flow; no automatic rerun; the user searches again.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "A10 (E5): Row menu Copy/Why act on that row without changing chosen files; plugin actions get the same payload as today; focus returns to the Actions button; copy success/failure feedback as today. Shift+F10 gated on forget capability (main) -> finding.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "A11 (E1/E5): Until 5b, Help rows keep their Help identifier (badge) in the default view.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "A12 (E7): Browser Open file passing a filesystem path to window.open exists on main -> finding. Size shows only when it belongs to the pane's document (same path).",
          "label": "agent choice"
        },
        {
          "kind": "owner-words",
          "quote": "Required: no count and no filter row in those states (as on main d61d78ce4e/eea268db53). Restore the check that was removed for this (`expect(cardOf(results)).toBeNull()` or its equivalent for these states).",
          "location": "task t-20261113-fmimpl request (builder repair brief, 2026-11-14)"
        },
        {
          "kind": "owner-words",
          "quote": "Required: DOM order matches visual order when the field is on top: field, then summary and filters, then results; Tab from the field reaches the filters, Shift+Tab from the first result reaches the filters then the field.",
          "location": "task t-20261113-fmimpl request (builder repair brief, 2026-11-14)"
        },
        {
          "kind": "owner-words",
          "quote": "Required: a text action (normal text colour, weight 500, a chevron icon) that opens a menu (reuse the app's existing menu component) with Any time / Past week / Past month / Past year (and \"Custom dates\" shown as the label when More filters set dates); same in browser and desktop; keyboard and screen-reader names work.",
          "location": "task t-20261113-fmimpl request (builder repair brief, 2026-11-14)"
        },
        {
          "kind": "owner-words",
          "quote": "Required: a new result list starts at the top (first row visible directly under the field and filters); opening or closing the pane keeps the current scroll position. Remove the bottom-dock scroll assumptions that cause this.",
          "location": "task t-20261113-fmimpl request (builder repair brief, 2026-11-14)"
        },
        {
          "kind": "owner-words",
          "quote": "Required: this year \"12 Nov\"; other years \"9 Oct 2025\" (use the app's existing date formatting/locale helpers).",
          "location": "task t-20261113-fmimpl request (builder repair brief, 2026-11-14)"
        },
        {
          "kind": "owner-words",
          "quote": "Required (decision): remove Markdown marks for all files except code and plain text (code = the extensions the app already treats as code; .txt/.log/.csv stay raw), because extracted office/PDF/mail text is stored Markdown-structured; remove extraction marker lines for all files: \"--- OCR page N ---\" and any other \"--- ... ---\" separator lines that the extractors insert (find their producers in modules/worker-services, e.g. extract/PdfOcrEngine.java, and match exactly what they emit; do not strip ordinary \"---\" text a user wrote). Display only: copy, export, Ask, citations and the pane keep raw text and offsets.",
          "location": "task t-20261113-fmimpl request (builder repair brief, 2026-11-14)"
        }
      ],
      "conflictSearch": "Repository docs/decisions searched for result counts, snippets, copy, details pane, no results and developer tools. No conflicting UI decision found. Private task records were not read in this builder session. E3/A1/A6 retire the previous summary words and effectiveMode-only fixture; E4/A2/A7 relocate the filter editor and keep its validation; E5/A10/A11 move copy/Why and collections; E7/A8 move diagnostics behind developer tools and retain anchors; E8/A1/A9 replace empty copy. Every changed assertion is listed in tmp/s5-evidence/test-expectations.txt. No retained payload, selection, error, raw-text or contrast assertion was removed. Repair request supersedes A5: all extracted document formats are Markdown-cleaned, with the existing code-extension set and txt/log/csv literal. Failure/loading card-absence assertions are restored (including x5), not weakened. Additional expectation changes are listed in tmp/s5-evidence/test-expectations-2.txt."
    },
    {
      "items": [
        "modules/ui-web/e2e/type-ladder-chromium.mjs",
        "modules/ui-web/e2e/type-ladder-fixture.js",
        "modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts",
        "modules/ui-web/src/shell-v0/components/searchResults/ResultsCard.firstMinutes.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/Sv3Results.preview.test.ts",
        "modules/ui-web/src/shell-v0/components/detailsPane/DetailsPane.frame-lockdown.test.ts",
        "scripts/jsharness/jsharness/ui_check.py"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation",
        "input re-encoding"
      ],
      "whatMoved": "The existing filter gesture uses More filters on Search results. The type-ladder fixture gains a test-only developer preference control; the default pane is still captured before opting in for both existing Rendered/Source captures. Shell.details quiet-highlight tests explicitly opt in before exercising the same view controls. Original documents, themes, contrast thresholds and assertions remain. The Library pane Arrow-key case remains unchanged. Repair: date selection uses the shared menu instead of a native select; preset/range/path assertions remain. The preview fixture now uses the real ready status rather than the unsupported results status, retaining all 15 preview/keyboard oracles. Search filter races commit their second restriction through the canonical store while pending UI has no filter row; generation, payload and stale-response oracles remain. Error assertions read the failure region, then verify entered restrictions in Ask, where the editor remains available. The real Chromium Space-preview fixture makes the same results-to-ready correction and supplies a complete snapshot; every geometry, focus, keyboard and error assertion stays unchanged. Docked filter cameras retain the displayed latest query in the field before committing restrictions: the recents setup intentionally empties it, and the product deliberately cannot rerun an empty draft, so those restrictions remain stale and the requested UI correctly withholds the filter row. Same query, restriction inputs, viewports, focus, error, accessibility and geometry assertions are retained.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "E7: Side pane: icon + file name once + Close; one line \"folder path \u00b7 N pages \u00b7 size\" with unknown parts omitted (size from sizeBytes carried from the search hit; pages from existing preview metadata); for a scan one note \"Read from a scan. A few words may be wrong.\"; then the file's text (Markdown rendered as today); footer Open file and Show in folder (Show in folder hidden where unsupported, browser). No place stepper (HANDOVER fallback: the response holds selected passages, not all places); named in delivery. Text source, quality %, OCR details, Rendered/Source switch and provenance go behind developer tools. \"Context\" tab only inside an Ask conversation; a restored pane whose saved tab is Context outside Ask opens the document instead (saved memory is not rewritten until the user changes the pane).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "E8: Nothing found (fm-06): words-only heading \"No file contains these words\" + \"Search looks for the words as typed. Try fewer or different ones.\"; when meaning was used: \"Nothing matches this search\" + \"Try fewer or different words.\" AI row only when meaning was not used and AI is known not installed or installing; \"Open AI Brain\" navigates only, never starts an install. Unknown/stale AI state or meaning already used: no AI row. With filters on: \"Filters are on\" + \"Clear filters\" instead. The query is not repeated. Loading, failed, invalid and stale responses never show this screen.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "A7 (E4): \"Any time\" menu: Any time clears both date bounds; Past week/month/year set modified-from to the start of the local day N days back and clear modified-to; custom dates from More filters show as \"Custom dates\" in the menu label; URL/pin semantics of dates unchanged.",
          "label": "agent choice"
        },
        {
          "kind": "owner-words",
          "quote": "Required: no count and no filter row in those states (as on main d61d78ce4e/eea268db53). Restore the check that was removed for this (`expect(cardOf(results)).toBeNull()` or its equivalent for these states).",
          "location": "task t-20261113-fmimpl request (builder repair brief, 2026-11-14)"
        },
        {
          "kind": "owner-words",
          "quote": "Required: DOM order matches visual order when the field is on top: field, then summary and filters, then results; Tab from the field reaches the filters, Shift+Tab from the first result reaches the filters then the field.",
          "location": "task t-20261113-fmimpl request (builder repair brief, 2026-11-14)"
        },
        {
          "kind": "owner-words",
          "quote": "Required: a text action (normal text colour, weight 500, a chevron icon) that opens a menu (reuse the app's existing menu component) with Any time / Past week / Past month / Past year (and \"Custom dates\" shown as the label when More filters set dates); same in browser and desktop; keyboard and screen-reader names work.",
          "location": "task t-20261113-fmimpl request (builder repair brief, 2026-11-14)"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/searchResults/ResultsCard.firstMinutes.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/first-minutes/DetailsRestore.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/first-minutes/ResultsEmpty.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/first-minutes/ResultsLayout.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/first-minutes/ResultsPane.test.ts",
        "modules/ui-web/src/shell-v0/components/searchResults/ResultsCard.firstMinutesRepairs.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/first-minutes/ResultsDomOrder.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/first-minutes/ResultsFailure.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/first-minutes/ResultsScroll.test.ts"
      ],
      "class": "New",
      "behaviour": "Executed-leg summary; available/displayed/capped counts; format-aware display-only text; date and folder visibility; default diagnostic gating; text facets/date presets; per-row raw copy without selection changes and with focus return; literal/general/filtered empty outcomes and AI navigation-only advice; stable mounted editor; browser footer and preview metadata; restored Context fallback without memory writes. Supplemental cases were written during implementation and then run in the unchanged shared clone; initial 11 checks preceded product edits.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "E3: Summary line left of the filter row: \"N files \u00b7 by words \u00b7 0.04 s\". Mode words from the actual retrieval mode of the response: words-only \"by words\"; hybrid \"by words and meaning\"; meaning-only \"by meaning\"; unknown: omitted. Capped counts keep \"+\". The \"Results for 'query'\" line goes (the query is in the field). Slow/pending states keep their words.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "E5: Rows: type icon, name (mail with a subject: \"Subject \u00b7 file.eml\"), date at the right (modifiedAt carried into the hit model; absent -> no date), two lines of clean text. Folder trail only when the library has more than one folder (known root count > 1) or the count is unknown; normal UI font in secondary colour (not monospace). Collection badge only with developer tools. MD/JSON/Paths header copy and the per-row \"Why this result?\" leave the default view: the row menu gains \"Why this result\" (opens today's explanation) and \"Copy as Markdown\" / \"Copy as JSON\" for that row; whole-list copy buttons stay only with developer tools on.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "E6: Clean text is display-only in the UI: remove Markdown marks (heading #, emphasis, inline code ticks, link syntax keeping link text, list bullets, quote >, table pipes) and \"--- OCR page N ---\" markers; collapse whitespace. Copy, export, MCP, Ask, citations and jseval keep raw text and offsets.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "E7: Side pane: icon + file name once + Close; one line \"folder path \u00b7 N pages \u00b7 size\" with unknown parts omitted (size from sizeBytes carried from the search hit; pages from existing preview metadata); for a scan one note \"Read from a scan. A few words may be wrong.\"; then the file's text (Markdown rendered as today); footer Open file and Show in folder (Show in folder hidden where unsupported, browser). No place stepper (HANDOVER fallback: the response holds selected passages, not all places); named in delivery. Text source, quality %, OCR details, Rendered/Source switch and provenance go behind developer tools. \"Context\" tab only inside an Ask conversation; a restored pane whose saved tab is Context outside Ask opens the document instead (saved memory is not rewritten until the user changes the pane).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "E8: Nothing found (fm-06): words-only heading \"No file contains these words\" + \"Search looks for the words as typed. Try fewer or different ones.\"; when meaning was used: \"Nothing matches this search\" + \"Try fewer or different words.\" AI row only when meaning was not used and AI is known not installed or installing; \"Open AI Brain\" navigates only, never starts an install. Unknown/stale AI state or meaning already used: no AI row. With filters on: \"Filters are on\" + \"Clear filters\" instead. The query is not repeated. Loading, failed, invalid and stale responses never show this screen.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "E10: Folder count for E5/E8 from existing root facts (firstUse/indexedRoots), never from the result page.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "A1 (E3/E8): Mode words from the executed legs, not the retrieval-mode label: \"by words\" only when the search provably ran literal-only (BM25, no SPLADE/dense); with any learned leg \"by words and meaning\"; unknown: no mode word. Nothing found: literal-only -> \"No file contains these words\" / \"Search looks for the words as typed. Try fewer or different ones.\"; otherwise or unknown -> \"Nothing matches this search\" / \"Try fewer or different words.\"",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "A2 (E4): No new type groups in 5a (Mail cannot be derived today; subjects are dropped). The row shows \"All\" plus the Type facet values as text with today's labels, counts and toggle semantics (click toggles that value; All clears the type selection); selected values stay even at zero; counts show only when they come from the current response (pending/stale: values without counts). Other facets in More filters. Mail/Documents grouping and mail subjects -> finding, later.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "A4 (E2): Placement: the field is at the top whenever the composer tier is Search and results or nothing-found are shown, including Back/Forward returns; whenever the tier is Ask (prepared or conversation) the composer is where it is today. One mounted editor; draft, caret and IME composition survive the move; first and last result rows reachable; at 600 px with the pane open nothing covers the field.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "A5 (E6): Cleaning is format-aware: Markdown marks are removed only for Markdown files (.md, .markdown, .mdx); \"--- OCR page N ---\" markers are removed for all; code and other text keep their punctuation. Clean before display truncation, then highlight.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "A6 (E3): Summary keeps the available/displayed distinction: \"N files\" when all are shown; \"Showing 50 of N files\" when limited; capped \"+\" and existing ranked/hybrid distinctions kept in plain words.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "A7 (E4): \"Any time\" menu: Any time clears both date bounds; Past week/month/year set modified-from to the start of the local day N days back and clear modified-to; custom dates from More filters show as \"Custom dates\" in the menu label; URL/pin semantics of dates unchanged.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "A8 (E7): Restored Context outside Ask: open the saved document if there is one, else keep the pane closed; restoration does not write details memory. \"Inside Ask\" = the pane belongs to an Ask conversation. Citation tabs, anchors and source Hide/Restore preserved.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "A9 (E8/E10): Folder row words: \"N files in M folders can be searched\" (current facts; singular/plural; unknown -> \"Only the folders you added are searched\"), \"Add a folder\" opens today's add flow; no automatic rerun; the user searches again.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "A10 (E5): Row menu Copy/Why act on that row without changing chosen files; plugin actions get the same payload as today; focus returns to the Actions button; copy success/failure feedback as today. Shift+F10 gated on forget capability (main) -> finding.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "A11 (E1/E5): Until 5b, Help rows keep their Help identifier (badge) in the default view.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "A12 (E7): Browser Open file passing a filesystem path to window.open exists on main -> finding. Size shows only when it belongs to the pane's document (same path).",
          "label": "agent choice"
        },
        {
          "kind": "owner-words",
          "quote": "Required: no count and no filter row in those states (as on main d61d78ce4e/eea268db53). Restore the check that was removed for this (`expect(cardOf(results)).toBeNull()` or its equivalent for these states).",
          "location": "task t-20261113-fmimpl request (builder repair brief, 2026-11-14)"
        },
        {
          "kind": "owner-words",
          "quote": "Required: DOM order matches visual order when the field is on top: field, then summary and filters, then results; Tab from the field reaches the filters, Shift+Tab from the first result reaches the filters then the field.",
          "location": "task t-20261113-fmimpl request (builder repair brief, 2026-11-14)"
        },
        {
          "kind": "owner-words",
          "quote": "Required: a text action (normal text colour, weight 500, a chevron icon) that opens a menu (reuse the app's existing menu component) with Any time / Past week / Past month / Past year (and \"Custom dates\" shown as the label when More filters set dates); same in browser and desktop; keyboard and screen-reader names work.",
          "location": "task t-20261113-fmimpl request (builder repair brief, 2026-11-14)"
        },
        {
          "kind": "owner-words",
          "quote": "Required: a new result list starts at the top (first row visible directly under the field and filters); opening or closing the pane keeps the current scroll position. Remove the bottom-dock scroll assumptions that cause this.",
          "location": "task t-20261113-fmimpl request (builder repair brief, 2026-11-14)"
        },
        {
          "kind": "owner-words",
          "quote": "Required: this year \"12 Nov\"; other years \"9 Oct 2025\" (use the app's existing date formatting/locale helpers).",
          "location": "task t-20261113-fmimpl request (builder repair brief, 2026-11-14)"
        },
        {
          "kind": "owner-words",
          "quote": "Required (decision): remove Markdown marks for all files except code and plain text (code = the extensions the app already treats as code; .txt/.log/.csv stay raw), because extracted office/PDF/mail text is stored Markdown-structured; remove extraction marker lines for all files: \"--- OCR page N ---\" and any other \"--- ... ---\" separator lines that the extractors insert (find their producers in modules/worker-services, e.g. extract/PdfOcrEngine.java, and match exactly what they emit; do not strip ordinary \"---\" text a user wrote). Display only: copy, export, Ask, citations and the pane keep raw text and offsets.",
          "location": "task t-20261113-fmimpl request (builder repair brief, 2026-11-14)"
        }
      ],
      "evidence": "gates/test-intent/evidence/fm-results.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/searchResults/ResultsCard.firstMinutes.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/first-minutes/DetailsRestore.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/first-minutes/ResultsEmpty.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/first-minutes/ResultsLayout.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/first-minutes/ResultsPane.test.ts",
        "modules/ui-web/src/shell-v0/components/searchResults/ResultsCard.firstMinutesRepairs.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/first-minutes/ResultsDomOrder.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/first-minutes/ResultsFailure.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/first-minutes/ResultsScroll.test.ts"
      ]
    }
  ]
}
```


Repair evidence (5ef58a99e4 before checkout): the four new isolated files fail 13 assertions before the product fix; 34 literal-text preservation cases pass. Restored pending/failure assertions fail twice before; restored x5 fails once before. The shared-clone known-good eea268db53 withholds the card; its active restriction chips remain visible, so the stricter no-filter assertion has no known-good pass there. Exact extractor separator inventory found only the OCR marker at PdfOcrEngine.java:341 and :477; ordinary user separators remain. The exact restored pending/transport/refused/syntax/stale checks also run against eea268db53. Candidate execution evidence is regenerated; fail-before publication remains for the orchestrator because the builder cannot create repository worktrees or commit. Acceptance records below are unchanged.


A real Chromium date-choice probe exposed focus loss during the temporary cache disconnection. A focused check failed on null activeElement before the focus repair, then passed; the same control regains focus only if the reader has not focused another control while waiting. The browser probe now reads Modified date: Past week on the focused date action after the rerun.

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "pvl5fiza-test-intent",
  "verdict": "reject",
  "digest": "sha256:ddbdf5811f5f2cca7e39a0c1d06ee7a7326ec83aed095f91ed9a7eae45f3df0c",
  "note": "Entry 1 weakens the live x5 stale-count/facet oracle to row absence; entry 3 has no fail-before evidence (failBefore null), and before/after execution plus file isolation could not be verified because Vitest is missing and dependency preparation fails with EAI_AGAIN."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-88g7ap3z-test-intent",
  "verdict": "accept",
  "digest": "sha256:1d60e7801ddcb908d429fa7316889397e9847359b86abd27f3c91b8c26b242de"
}
```
