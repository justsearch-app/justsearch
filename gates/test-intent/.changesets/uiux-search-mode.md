---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: 9bd46baf-6d6a-4821-8af8-913e90a952af
---

# Search v3 Search mode (WP1.1)

The Search v3 window gains a Search mode, the default of a fresh window, which lists matching
documents on the shared results card. Scenarios S1.n are cited as SMn and N1.n as SNn (the gate
accepts only short ids). S1.12 (results reachable beside the floating composer at narrow width) is
checked by the jseval `sv3-search-results` capture, which this gate does not cover.

Evidence: `gates/test-intent/evidence/uiux-search-mode.json` runs all 56 new checks on the
candidate. The one repaired behaviour (a missing document's message) has a fail-before on the
merge base in `gates/test-intent/evidence/uiux-search-mode-repaired.json`. The other new checks
pin new behaviour and carry no fail-before requirement; most fail on the before-state, but several
fail there by exception or because they import modules that do not exist yet. Five pass there,
because they pin behaviour that already held (no search on reload or in Ask, a cleared query shows
nothing, a restored draft is kept, a non-404 failure keeps its status).

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/activity/idleSurfaces.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.agentRun.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ask.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.degradation.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delegateEvidence.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.effort.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.honesty.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.markdown.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.pane.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.record.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.sessions.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Each file's mount helper (or, in idleSurfaces.test.ts, the one case that mounts the window; in SearchV3View.degradation.test.ts, the composer mount) now sets tier = 'ask' right after mounting. The Ask tier was the window's default, so it was these cases' implicit precondition; a fresh window now starts in Search, so the same precondition is stated explicitly. Nothing else in these files changed: inputs, observations, fault injection and every expected outcome are as before. In SearchV3View.sessions.test.ts this covers only the mount helper; its retired and new in-motion-dot cases have their own entries below.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SM2",
          "quote": "A fresh window with no conversation open starts in Search mode. Reload never runs a search, a question or an agent by itself; restored draft text stays in the composer.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SN1",
          "quote": "Ask and Delegate: choosing them, Enter and send-label behaviour, streaming, stop, late stop, reload.",
          "label": "agent-drafted, owner-adopted"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/Sv3Palette.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "The 906 pointer-press case clicked the \"Search this text\" option (SV3_COMMAND_SEARCH_TEXT), which this change removes from the palette; it now clicks the \"Search everything\" option (SV3_COMMAND_SEARCH, id cmd-everything) through the same pointer sequence. The oracle is unchanged: the command dispatched is the clicked option's id, the palette closes and focus returns to the trigger. Only the import and the option id changed.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SM11",
          "quote": "The palette's developer-only \"Search this text\" entry is gone; searching from the palette uses Search mode and its results are visible.",
          "label": "agent-drafted, owner-adopted"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.search.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding",
        "instrumentation",
        "rename"
      ],
      "whatMoved": "Input: the send() helper reached the shared-store search seam through the palette's removed \"Search this text\" command; it now presses Enter in the composer in Search mode (the window's default). It still yields exactly one request per send (the first case keeps its exactly-one-request assertion past the debounce and settle timers). Observation: the results moved from Sv3Main's own rows (sv3-main-row, .row-title, .row-path, sv3-main-count, sv3-main-skeleton, sv3-main-empty, sv3-main-unreachable, sv3-main-failure-detail) to the Search-mode results region (jf-sv3-results) and the shared results card inside it (search-result-row, .title-text, .path[title], card-meta, sv3-results-loading, sv3-results-empty, sv3-results-failed, sv3-results-failure-detail); settle() waits two more macrotask turns and the extra renders. Oracle differences an acceptor should check: (1) the count label was compared for equality with the on-screen count element; it is now checked as the leading text of the card's meta line, which appends latency and pass-stage words after it, and the leading-number probe reads the expected label rather than the on-screen one, still compared with the rendered row count; (2) \"pending\" was \"skeleton rows present, no empty state, no count, zero rows\" and is now \"loading element present, no empty state, no card\"; (3) \"zero rows\" in the empty and failure cases is now \"no card\". Expected values (row titles and paths, MAIN_EMPTY and MAIN_UNREACHABLE copy, failure-detail text, role=alert, request counts) are unchanged. The case \"is NOT what a plain send does any more\" now chooses the Ask tier first and keeps its expectation (no search request); its old claim about the default tier is retired in the Obsolete entry below.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SM3",
          "quote": "In Search mode, typing shows ranked results as you type (the same quick-then-refined searches the current default window sends for the same input), and Enter runs the full search at once. No answer generation, no agent, no new conversation and no conversation turn is created; the search requests equal the current window's for the same input.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SM5",
          "quote": "Results use the shared results card: title, path, snippet, the facet and date filters the default window offers today (applied to quick and refined passes, removable after zero hits), multi-select (click, Ctrl/Cmd toggle, Shift range). Opening a hit by mouse, keyboard or context menu shows the document in the document pane, without clearing a multi-file selection. A missing or deleted file says so.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SM9",
          "quote": "Loading, refining, no matches (\"no documents matched\") and failure (\"search failed\", Engine unreachable or error) are distinguishable; results of an older query are never shown as the current one (fast typing, out-of-order responses, clearing during a request).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SM11",
          "quote": "The palette's developer-only \"Search this text\" entry is gone; searching from the palette uses Search mode and its results are visible.",
          "label": "agent-drafted, owner-adopted"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.search.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.tier.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.sessions.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SM2",
          "quote": "A fresh window with no conversation open starts in Search mode. Reload never runs a search, a question or an agent by itself; restored draft text stays in the composer.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SM3",
          "quote": "In Search mode, typing shows ranked results as you type (the same quick-then-refined searches the current default window sends for the same input), and Enter runs the full search at once. No answer generation, no agent, no new conversation and no conversation turn is created; the search requests equal the current window's for the same input.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SM8",
          "quote": "Running a search while a conversation is open (idle, streaming, waiting for a decision, or history-locked) shows the results and does not lose the conversation: it stays in the sidebar and opens again with one click. A search never steers, approves or stops a running agent.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SM11",
          "quote": "The palette's developer-only \"Search this text\" entry is gone; searching from the palette uses Search mode and its results are visible.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "removal",
          "removed": [
            "modules/ui-web/src/shell-v0/views/search-v3/sv3-results.ts",
            "modules/ui-web/src/shell-v0/views/search-v3/fixtures.ts#SV3_COMMAND_SEARCH_TEXT"
          ]
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none (docs/decisions/0001-0051 searched for Search v3, composer, tier, palette, search mode and session in-motion indicator; none governs these behaviours)",
      "whatRetired": [
        "SearchV3View.search.test.ts \"is NOT what a plain send does any more — the composer talks to the model\": a send on the DEFAULT tier never reaches the search endpoint. A fresh window now starts in Search (S1.2), whose send is the search (S1.3).",
        "SearchV3View.tier.test.ts \"still asks — and never reaches the controller — on the default tier\": a send on the default tier dispatches an ask. Same reason; the ask arm is kept on the chosen Ask tier.",
        "SearchV3View.sessions.test.ts \"still shows it for a palette search that refines BEHIND displayed results\": the open session's in-motion dot lights while a palette search runs. A search is now not a conversation turn and shows its results outside every conversation (S1.3, S1.8), and the \"Search this text\" command it drove is gone (S1.11)."
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-search.test.ts"
      ],
      "class": "New",
      "behaviour": "Three modes, Search first and default; Search labelled for finding documents with no AI claim; the mode is announced; Enter, Shift+Enter and IME composition do in Search what the label and hint say; the delegate chord is named only where it delegates.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SM1",
          "quote": "The Search v3 composer offers three modes: Search, Ask, Delegate. Search is labelled for what it does (find matching documents) and makes no \"no AI\" claim, because the full search pass uses the chat model for query expansion when AI is on, exactly as the current window does (decided), choosable by mouse and keyboard; the mode control is announced to screen readers. Each mode's visible label and keyboard hint matches the request it actually sends (Enter, Ctrl/Cmd+Enter, Shift+Enter, IME composition).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SM2",
          "quote": "A fresh window with no conversation open starts in Search mode. Reload never runs a search, a question or an agent by itself; restored draft text stays in the composer.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-search-mode.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-search.test.ts#lists Search, Ask and Delegate, in that order",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-search.test.ts#starts a fresh window in Search (S1.2) and badges it as the default",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-search.test.ts#labels Search by what it does and claims nothing about AI",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-search.test.ts#is always the search control, even while a run holds a decision or is running",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-search.test.ts#keeps the standing stop beside it while something is running — a search stops nothing",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-search.test.ts#names the delegate chord only when the chord would actually delegate",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-search.test.ts#says a scoped Ask asks about the chosen files, and that the delegate chord leaves them behind",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#starts in Search, says so to assistive tech, and words what Enter does",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#restores the draft into the composer without searching, asking or delegating",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#keeps Shift+Enter a newline and an IME Enter the IME’s, in Search mode too",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#does not search a half-composed IME string, and searches once composition ends"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts"
      ],
      "class": "New",
      "behaviour": "Typing in Search sends the quick then refined pass through the shared intent seam and Enter runs the full pass at once, with no turn, ask or agent; typing in Ask sends no search.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SM3",
          "quote": "In Search mode, typing shows ranked results as you type (the same quick-then-refined searches the current default window sends for the same input), and Enter runs the full search at once. No answer generation, no agent, no new conversation and no conversation turn is created; the search requests equal the current window's for the same input.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SN1",
          "quote": "Ask and Delegate: choosing them, Enter and send-label behaviour, streaming, stop, late stop, reload.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-search-mode.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#sends the quick pass while typing and the refined pass on settle, through the one intent seam",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#runs the full pass at once on Enter, superseding the staged ones",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#typing in Ask mode issues no search at all"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts"
      ],
      "class": "New",
      "behaviour": "With chat unavailable Search lists, selects and opens; Ask keeps the query and shows the unavailable reason.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SM4",
          "quote": "With chat unavailable (no supported GPU, AI not activated, model loading or failed), Search works fully: type, filter, select, open. Ask and Delegate, and every \"ask\" action on results, show the existing unavailable reason accessibly and keep the query and selection.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-search-mode.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#types, lists, selects and opens; Ask keeps the query and states the reason"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts",
        "modules/ui-web/src/shell-v0/components/searchResults/ResultsCard.test.ts"
      ],
      "class": "New",
      "behaviour": "Results render on the shared results card and a hit opens in the document pane; in explicit selection mode a plain click opens without discarding a multi-file choice, Ctrl/Shift choose without opening (Shift ranges), and the row menu opens and chooses from the keyboard.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SM5",
          "quote": "Results use the shared results card: title, path, snippet, the facet and date filters the default window offers today (applied to quick and refined passes, removable after zero hits), multi-select (click, Ctrl/Cmd toggle, Shift range). Opening a hit by mouse, keyboard or context menu shows the document in the document pane, without clearing a multi-file selection. A missing or deleted file says so.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-search-mode.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#renders the shared card, opens a hit in the pane, and keeps a multi-file choice",
        "modules/ui-web/src/shell-v0/components/searchResults/ResultsCard.test.ts#a plain click opens and leaves the selection alone",
        "modules/ui-web/src/shell-v0/components/searchResults/ResultsCard.test.ts#Ctrl and Shift choose without opening, and Shift ranges from the row read last",
        "modules/ui-web/src/shell-v0/components/searchResults/ResultsCard.test.ts#the row menu opens without choosing, and offers choosing to the keyboard"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-search.test.ts"
      ],
      "class": "New",
      "behaviour": "Ask about chosen files sends exactly the chosen documents by path, each document once; the set is fixed when asked; rows that left the list are visibly dropped; an empty or invalid choice is refused, never a whole-index ask.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SM6",
          "quote": "\"Ask about this file\" / \"Ask about these results\" starts an Ask whose retrieval is limited to exactly the chosen files, checked with an unchosen file holding a unique answer. The chosen set is fixed when the user asks (later refinement reordering, typing or filter changes do not alter it); rows that disappear under a filter leave the selection or are visibly dropped, never silently kept; several hits from one document count as that document once. An empty or invalid selection never becomes a whole-index question.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-search-mode.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#sends the chosen documents as paths, each document once",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#fixes the set when asked: a later re-rank or filter does not change it",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#drops a chosen row that a new result list no longer holds — visibly",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#offers no ask about an empty choice, and never sends an empty set as a whole-index ask",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#scopes \"Ask about this file\" from the card menu to that one document",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-search.test.ts#counts several hits from one document once, in first-seen order",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-search.test.ts#takes only chosen rows, never the rest of the list",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-search.test.ts#refuses an empty choice, ids that are not on screen, and hits with no path",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-search.test.ts#drops chosen rows that left the list, and keeps the same set when nothing left",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-search.test.ts#words the act and the scope by document count"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-search.test.ts"
      ],
      "class": "New",
      "behaviour": "Chosen files do not carry into a later ordinary Ask or a new session; Retry keeps the same files and says so; after reload an unsaved scope does not look scoped and Retry/Edit say they search all files.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SM7",
          "quote": "Scope has a lifetime: a later ordinary Ask, a new session or another conversation does not inherit chosen files. Retry/Edit of a scoped answer either keeps the same files or says visibly that it will search all files; it never silently widens, live and after reload (scope that was not saved must not come back as an unscoped question that looks scoped).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-search-mode.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#does not carry the files into a later ordinary Ask",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#drops a prepared scope on New session",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#Retry of a scoped answer keeps the same files, and says so",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#after a reload the turn does not look scoped, and Retry/Edit say they search all files",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-search.test.ts#keeps the same files when the turn carries its scope, and says all files when it does not"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.sessions.test.ts"
      ],
      "class": "New",
      "behaviour": "A search while a conversation streams or a delegated run is live shows results, leaves the conversation streaming in the sidebar one click away, never steers the run, and lights no session row.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SM8",
          "quote": "Running a search while a conversation is open (idle, streaming, waiting for a decision, or history-locked) shows the results and does not lose the conversation: it stays in the sidebar and opens again with one click. A search never steers, approves or stops a running agent.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SM3",
          "quote": "In Search mode, typing shows ranked results as you type (the same quick-then-refined searches the current default window sends for the same input), and Enter runs the full search at once. No answer generation, no agent, no new conversation and no conversation turn is created; the search requests equal the current window's for the same input.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-search-mode.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#leaves a streaming answer streaming, in the sidebar, and reopens it with one click",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#never steers a live delegated run",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.sessions.test.ts#lights no row for a search — a search is not a turn, and leaves the conversation one click away"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-search.test.ts"
      ],
      "class": "New",
      "behaviour": "Rows of an older query stop showing when the query changes or is cleared, even when the earlier request answers late; no matches and failure are worded differently.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SM9",
          "quote": "Loading, refining, no matches (\"no documents matched\") and failure (\"search failed\", Engine unreachable or error) are distinguishable; results of an older query are never shown as the current one (fast typing, out-of-order responses, clearing during a request).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-search-mode.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#stops showing the previous rows the moment the query changes",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#shows nothing for a cleared query, even when the earlier request answers late",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#says \"no documents matched\" and \"search failed\" in different words",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-search.test.ts#is stale while the store holds a newer query than the one its rows answered"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts"
      ],
      "class": "New",
      "behaviour": "A scope chip, facet filter or date range from the shared search state is shown and removable (also after zero hits); the composer, heading and rows follow the one shared query, without overwriting an unsent Ask draft or contradicting a restored draft.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SM10",
          "quote": "Search filters and query shared with the old window are visible and removable in Search v3; nothing restricts results invisibly.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SN3",
          "quote": "The current default window (UnifiedChatView) behaves as today, including its shared search state; Search v3 stays developer-only until the flip (WP1.5).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-search-mode.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#shows a scope chip the default window pinned, and removing it re-runs the search unrestricted",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#keeps a facet filter removable when it leaves zero hits",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#shows a date range set elsewhere, and clears it",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#follows a search made in the other window when the reader comes back",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#shows the shared query on a first entry, in the composer and in the list",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#returns to the list with the shared query after a conversation, too",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#never overwrites an unsent Ask draft; Search mode then searches what the composer holds",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#keeps a restored draft on a first entry and shows no list it would contradict"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts"
      ],
      "class": "New",
      "behaviour": "The palette has no \"Search this text\" entry; \"Search everything\" runs Search mode and its results are visible.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SM11",
          "quote": "The palette's developer-only \"Search this text\" entry is gone; searching from the palette uses Search mode and its results are visible.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-search-mode.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#has no developer \"Search this text\" entry, and \"Search everything\" shows results"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts"
      ],
      "class": "New",
      "behaviour": "Back/Forward keep the results, query and chosen scope with the results place and none of them in the conversation.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SM13",
          "quote": "Back/Forward between conversations and between results and a conversation keep each draft, result list and chosen scope with its own place; none appears to belong to another.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-search-mode.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#keeps the results, the query and the chosen scope with the results, and none in the conversation"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.tier.test.ts"
      ],
      "class": "New",
      "behaviour": "On the default (Search) tier a send searches with no ask and no agent, plain Enter submits tier \"search\", the send control names the Search hint, and no ask notice shows; the Ask and Delegate arms behave as before once chosen.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SM1",
          "quote": "The Search v3 composer offers three modes: Search, Ask, Delegate. Search is labelled for what it does (find matching documents) and makes no \"no AI\" claim, because the full search pass uses the chat model for query expansion when AI is on, exactly as the current window does (decided), choosable by mouse and keyboard; the mode control is announced to screen readers. Each mode's visible label and keyboard hint matches the request it actually sends (Enter, Ctrl/Cmd+Enter, Shift+Enter, IME composition).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SM2",
          "quote": "A fresh window with no conversation open starts in Search mode. Reload never runs a search, a question or an agent by itself; restored draft text stays in the composer.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SM3",
          "quote": "In Search mode, typing shows ranked results as you type (the same quick-then-refined searches the current default window sends for the same input), and Enter runs the full search at once. No answer generation, no agent, no new conversation and no conversation turn is created; the search requests equal the current window's for the same input.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SM4",
          "quote": "With chat unavailable (no supported GPU, AI not activated, model loading or failed), Search works fully: type, filter, select, open. Ask and Delegate, and every \"ask\" action on results, show the existing unavailable reason accessibly and keep the query and selection.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SN1",
          "quote": "Ask and Delegate: choosing them, Enter and send-label behaviour, streaming, stop, late stop, reload.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-search-mode.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.tier.test.ts#searches on the default tier — no ask, no agent — and asks on the ask tier",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.tier.test.ts#sends plain Enter at the CHOSEN tier — the mode governs the default key",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.tier.test.ts#names the key that now runs, in every mode",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.tier.test.ts#yields the ask tier reason for the delegate tier when the mode changes"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.tier.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "In \"sends plain Enter at the CHOSEN tier\", \"names the key that now runs, in every mode\" and \"yields the ask tier reason for the delegate tier when the mode changes\", the Ask arm used to run on the default tier and now chooses Ask first (chooseTier(el, 'ask')); its inputs and expected outcomes (the submitted {query, tier: 'ask'}, SV3_SEND_HINT in the send label, the ask reason in the notice, and the following delegate arm) are unchanged. The Search arms these cases gained are in the New entry above; the retired default-tier ask case is in the Obsolete entry.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SM2",
          "quote": "A fresh window with no conversation open starts in Search mode. Reload never runs a search, a question or an agent by itself; restored draft text stays in the composer.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SN1",
          "quote": "Ask and Delegate: choosing them, Enter and send-label behaviour, streaming, stop, late stop, reload.",
          "label": "agent-drafted, owner-adopted"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/documentPane/DocumentPane.test.ts"
      ],
      "class": "New",
      "behaviour": "Opening a document the index no longer has (HTTP 404) says it is no longer in the index and may have been moved or deleted, instead of the bare status code.",
      "repaired": true,
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SM5",
          "quote": "Results use the shared results card: title, path, snippet, the facet and date filters the default window offers today (applied to quick and refined passes, removable after zero hits), multi-select (click, Ctrl/Cmd toggle, Shift range). Opening a hit by mouse, keyboard or context menu shows the document in the document pane, without clearing a multi-file selection. A missing or deleted file says so.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-search-mode-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/documentPane/DocumentPane.test.ts#says the file is no longer in the index, not a bare status code"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/documentPane/DocumentPane.test.ts"
      ],
      "class": "New",
      "behaviour": "Any other load failure (here HTTP 503) still shows its status: only a missing document is called missing. Passes on the before-state; it pins the existing behaviour beside the repair.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SM5",
          "quote": "Results use the shared results card: title, path, snippet, the facet and date filters the default window offers today (applied to quick and refined passes, removable after zero hits), multi-select (click, Ctrl/Cmd toggle, Shift range). Opening a hit by mouse, keyboard or context menu shows the document in the document pane, without clearing a multi-file selection. A missing or deleted file says so.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-search-mode.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/documentPane/DocumentPane.test.ts#keeps the status for any other failure"
      ]
    }
  ]
}
```

## Acceptance records
