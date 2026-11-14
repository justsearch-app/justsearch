---
schema: test-intent.v1
task: t-20261113-fmimpl
author-role: builder
author-session: codex-builder-fm-first-launch-20261114
---

# Test-intent entries

This changeset accounts for the forty modified product-test files in candidate
0d5104a84e073d4173293a59f0a03097180d336c against origin/main
d61d78ce4e35706bee0b2c44d303e84245c58285. Only this changeset is written by this
builder. Every file's actual `git diff origin/main -- <file>` was read; the supplied
`tmp/s4-evidence/test-expectations.txt` was a navigation aid, not authority for an
expectation or independently verified execution evidence.

Source provenance follows fm-top-bar.md. R2, R6, R7 and R10 name **rule 2**,
**rule 6**, **rule 7** and **rule 10**; H4 names **handover slice 4** of task
t-20261113-uicritique. These are agent-drafted, owner-adopted scenarios, not
owner-authored wording. Their original names remain in `scenarioName` beside the
alphanumeric scenario IDs. The supplied adoption location is task
t-20261113-uicritique user turn round 7:
“yes, update accordingly. im especially surprised by how long it took for agents to realise the text size issue by themselves. you are the first one.”
Round 14 continued the implementation handoff:
“now that the prototype is clear, do you think you should do any further work to make the implementation faster and more accurate for the implementers? if so, proceed with that work accordingly and give me the prompt you want me to start the implementer with.”
The entries quote the supplied adopted rules and slice verbatim. The acceptor
must check those adoption turns and their scope; private task records were not
read in this builder session.

Delegated choices are agent choices, never owner words. Each decision used has
its own source object, identified by D1–D10 or the challenger amendment C1–C9;
`choice` quotes that decision text rather than presenting it as an owner quote.
The delegation is “You are supposed to be the implementation session”, located at
task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z, scoped to
“implement the agreed first-minutes design set”, as in fm-top-bar.md. Amendments
qualify earlier decisions: C3 tightens D3; C1 preserves existing unavailable modes;
C9 restricts D10's count lifetime. C7 defers the zero-result AI offer to slice 5
(fm-06); these entries do not claim that work was implemented or verified here.

`Obsolete` identifies an old UI/input/precondition contract superseded by those
sources. Fixture-only changes are explicitly named: feeding installed/CUDA or
developer state, supplying a real conversation, and selecting existing Ask change
preconditions, so they are not represented as keeping every original input.
Three explicit-submit-only files use `Adaptation` / `input re-encoding`: their
submit gesture moves from the removed Search send button to Enter while their
fault injection and outcome oracles stay. A fixture/selector description alone
never licenses an expected-value change.

The six wholly new tests are not items in these entries:
`chrome/FirstMinutes.app-lockdown.test.ts`, `state/FirstMinutes.reading.test.ts`,
`views/HelpSurface.firstMinutes.test.ts`,
`views/search-v3/FirstMinutes.availability.test.ts`,
`views/search-v3/FirstMinutes.test.ts` (all under
`modules/ui-web/src/shell-v0/`), and
`modules/ui-web/src/utils/folderPicker.firstMinutes.test.ts`.
Per-entry file/test pointers identify actual assertions read in them and delimit
partial replacements; titles alone are not evidence. They were not run here,
and no fail-before/pass-after or independent acceptance is claimed.

Coverage limits are findings for acceptance, not passes. In particular, broadening
the offline-copy scanner exclusion to every test file has no supplied warrant;
recents lose retained-preparation and some lock/423, exact-dedup, naming and
keyboard/scroll checks; some assertions become narrower; and fixture helpers can
hide absent controls. `checkLimits` names those gaps even where the file also has
legitimate Obsolete changes. No class/source in this document purports to approve
an unsupported weakening or prove exact new fallback copy came from owner words.
The acceptor must resolve those limits against the real scenarios, rather than
accept the candidate's output as its own intent.

Governance documents, production files, and scripts/jsharness changes are outside
these forty items; this changeset does not modify them or substitute a document
changed in this PR for a source predating the candidate. Repository decision
records were searched as described in each Obsolete entry; task records remain
for the independent acceptor to inspect.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/boot/type-ladder-lockdown.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R7",
          "scenarioName": "rule 7",
          "quote": "The search box is a search field: magnifier, one line, Enter. No send arrow, no effort control. Search / Ask appears in it only once answers work on this computer.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "decision": "D3",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "\"Answers work on this computer\" = AI installed (known true) AND an NVIDIA/CUDA GPU available (known true). Then the field shows Search / Ask; otherwise no mode control. Ask's own availability gate stays for running. Existing conversations and Ask deep links stay reachable regardless.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "D4",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Structured and Delegate are offered in the switch only when developer tools are on (HANDOVER: not on a picture -> behind developer tools).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "D5",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Search mode: magnifier, one line (pasted newlines become spaces), Enter searches, no send arrow, no effort control, no placeholder prompt; the headline labels the field (aria-labelledby); when the headline is not shown (results docked) the field's name is \"Search\". Ask mode: today's Ask composer (multi-line, effort, send, stop, scope).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "C3",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "(D3) \"Answers work\" = installed known true AND CUDA available known true AND the existing Ask availability (F/state/availability.ts) says available or loadable-on-demand, from a live snapshot. Repair-needed, chat switched off, stale or unknown: no switch (C1 still applies for existing tasks).",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none found in docs/decisions for getting started, first launch, recent, search-as-you-type, typeahead, debounce, corpus, Draft kept, Ctrl+Enter, Search / Ask, Structured and Delegate. Matches concern engine query/metadata contracts (ADR-0016/0020), inference/model and context policy (ADR-0019/0047), scoped paths (ADR-0028), primitives (ADR-0031), streams and resource categories (ADR-0036/0037), rather than the superseded UI expectations. Private task records were not read in this builder session; the acceptor must confirm the supplied adoption and delegation turns and scope. This search does not supply missing intent for a coverage reduction flagged in checkLimits.",
      "assertionChanges": [
        {
          "oldAssertion": "The desktop-CSP typography audit must capture exactly 164 surfaces and report zero violations.",
          "newAssertion": "It must capture exactly 156 surfaces and still report zero violations.",
          "reason": "R7 and D3/D4/D5/C3 remove idle Search controls when unavailable, rather than requiring their disclosure states to exist. The supplied builder evidence identifies eight retired captures: dark/light search-idle-expanded-1/2/3/4. That is a proposed capture-inventory change, not authority to set the oracle to whatever count the candidate prints."
        }
      ],
      "checkLimits": "Coverage reduction: eight capture states are no longer required. The exact 156 inventory and the builder-reported baseline 164/candidate 156 with zero violations were not rerun here; this entry alone does not prove which surfaces disappeared or that all surviving surfaces were audited. modules/ui-web/src/shell-v0/views/search-v3/FirstMinutes.test.ts#S4-13 Search has a magnifier and headline label, with no prompt, send or effort and FirstMinutes.availability.test.ts#S4-14 developer tools reveal Structured/Delegate only with answers available cover control presence/availability, not typography, capture identity or the lost disclosure geometries. No replacement capture for those eight states is claimed."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/FirstUse.app-lockdown.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R2",
          "scenarioName": "rule 2",
          "quote": "Every fact has one home on a screen. If a headline says it, the line under it does not; if the picture says it, the sentence does not; a number shown on a row is not repeated in a summary above it. A second place is allowed only where it does a different job (the status in the bar is visible from every area). The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R6",
          "scenarioName": "rule 6",
          "quote": "First launch is one message and one action. No Getting started card, no checklist, no status in the bar. AI is offered where it helps: on a search that finds nothing, and in AI Brain.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R7",
          "scenarioName": "rule 7",
          "quote": "The search box is a search field: magnifier, one line, Enter. No send arrow, no effort control. Search / Ask appears in it only once answers work on this computer.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R10",
          "scenarioName": "rule 10",
          "quote": "No conversations column until there is a conversation. A plain search never fills it.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "decision": "D1",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "No \"of M\": the line is \"N files searchable so far\" (no trustworthy total exists; HANDOVER: never invent data). Unknown count: no number.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "D7",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Getting started card, checklist and \"Getting started · N of 3\" status removed; stored first-use progress is not migrated or deleted. Plugin walkthroughs, hosted by the card today, are listed in Help instead (where \"Getting started\" was reopened). First launch shows no status in the bar.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "D8",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "First-launch folder add (fm-01): \"Add a folder\" -> native picker -> added at once (no second Add, no Collection); browser: a path field + Add (no Collection); Documents/Desktop/Downloads add at once (existing backend paths; missing ones omitted). Errors stay in place with retry. Library's add flow unchanged in this slice (slice 6).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "D10",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Right after the first folder (fm-02): headline \"What are you looking for?\", field focused, one line \"N files searchable so far\", bar status shows reading in words. Returning (fm-03): no count line in Search (it lives in Library).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "C7",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Zero-result AI offer: belongs to slice 5 (fm-06, no results); not in this slice; named in delivery.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "C9",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "fm-02 line lifetime: \"N files searchable so far\" shows on Search's idle screen only while the first reading of the user's folders is under way (indexing activity running, no completed search needed); otherwise no count line; independent of stored first-use progress. Stop writing first-use progress (try-search); keep existing stored records.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none found in docs/decisions for getting started, first launch, recent, search-as-you-type, typeahead, debounce, corpus, Draft kept, Ctrl+Enter, Search / Ask, Structured and Delegate. Matches concern engine query/metadata contracts (ADR-0016/0020), inference/model and context policy (ADR-0019/0047), scoped paths (ADR-0028), primitives (ADR-0031), streams and resource categories (ADR-0036/0037), rather than the superseded UI expectations. Private task records were not read in this builder session; the acceptor must confirm the supplied adoption and delegation turns and scope. This search does not supply missing intent for a coverage reduction flagged in checkLimits.",
      "assertionChanges": [
        {
          "oldAssertion": "The card and Getting started pill appear, show numbered Done/Under way steps, offer Open Library for an empty folder, and repeat searchable plus waiting counts.",
          "newAssertion": "The card/pill are absent; live reading shows only N files searchable so far, including zero. Settled folders and Help count updates produce no count line; a search field remains available.",
          "reason": "R6 removes the checklist and pill; R2/D1/D10/C9 restrict the one partial-count line to live reading and keep returning corpus facts in Library. Old Done, Under way and Open Library assertions are intentionally retired, not selector changes."
        },
        {
          "oldAssertion": "Hide is clicked in the card; Help reopens it across windows, reload and profile changes. A successful search writes try-search, and Help can reopen the checklist above docked Search.",
          "newAssertion": "The legacy hide/reopen APIs are exercised directly and their shared stored dismissal state is awaited; neither revives the card. Search renders results without writing try-search; Help has no Getting started button. Profile change retains the first Add action.",
          "reason": "D7 preserves legacy records while removing their UI; C9 stops new search-progress writes. The old visible Hide/Help-reopen entry paths are no longer the product contract."
        },
        {
          "oldAssertion": "At enlarged text the optional Open AI Brain card action is visibly reachable.",
          "newAssertion": "At the same viewport/theme matrix Add a folder is reachable and opens the path textbox; horizontal-overflow checks remain.",
          "reason": "R6/D8 replace checklist AI promotion with the first-launch folder action. This tests the adopted action rather than accepting the candidate's former AI placement."
        }
      ],
      "checkLimits": "Deletions: checklist header/current step/Done/Under way, empty-folder Open Library, waiting-count text, visible Hide and Help reopen, and first-launch AI Brain reachability have no same-UI replacements because that UI is removed. State sharing is still checked through APIs, a narrower interaction path. modules/ui-web/src/shell-v0/chrome/FirstMinutes.app-lockdown.test.ts#S4-1 first launch has one message/action and no field, checklist, sidebar or status and #S4-8 browser path entry has no Collection; acceptance focuses Search and reading count expires assert replacement launch/focus/count-lifetime behavior. Its S4-19 viewport/theme cases check Add reachability, not the retired AI action. Those new tests were read, not executed here. Existing cancellation, errors/retry, duplicate add, navigation/history and reload assertions remain."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/FirstUse.engine-lockdown.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R2",
          "scenarioName": "rule 2",
          "quote": "Every fact has one home on a screen. If a headline says it, the line under it does not; if the picture says it, the sentence does not; a number shown on a row is not repeated in a summary above it. A second place is allowed only where it does a different job (the status in the bar is visible from every area). The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R6",
          "scenarioName": "rule 6",
          "quote": "First launch is one message and one action. No Getting started card, no checklist, no status in the bar. AI is offered where it helps: on a search that finds nothing, and in AI Brain.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "decision": "D1",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "No \"of M\": the line is \"N files searchable so far\" (no trustworthy total exists; HANDOVER: never invent data). Unknown count: no number.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "D7",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Getting started card, checklist and \"Getting started · N of 3\" status removed; stored first-use progress is not migrated or deleted. Plugin walkthroughs, hosted by the card today, are listed in Help instead (where \"Getting started\" was reopened). First launch shows no status in the bar.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "D10",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Right after the first folder (fm-02): headline \"What are you looking for?\", field focused, one line \"N files searchable so far\", bar status shows reading in words. Returning (fm-03): no count line in Search (it lives in Library).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "C9",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "fm-02 line lifetime: \"N files searchable so far\" shows on Search's idle screen only while the first reading of the user's folders is under way (indexing activity running, no completed search needed); otherwise no count line; independent of stored first-use progress. Stop writing first-use progress (try-search); keep existing stored records.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none found in docs/decisions for getting started, first launch, recent, search-as-you-type, typeahead, debounce, corpus, Draft kept, Ctrl+Enter, Search / Ask, Structured and Delegate. Matches concern engine query/metadata contracts (ADR-0016/0020), inference/model and context policy (ADR-0019/0047), scoped paths (ADR-0028), primitives (ADR-0031), streams and resource categories (ADR-0036/0037), rather than the superseded UI expectations. Private task records were not read in this builder session; the acceptor must confirm the supplied adoption and delegation turns and scope. This search does not supply missing intent for a coverage reduction flagged in checkLimits.",
      "assertionChanges": [
        {
          "oldAssertion": "Card header, current step and progressbar all say step 1 of 3, with a setup pill; hiding the card reveals a second composer corpus line.",
          "newAssertion": "No setup pill, card or progressbar; one Add action. During reading the own partial count appears once, without waiting text, and hide does not expose corpus chrome or remove that partial count.",
          "reason": "R6/D7 retire setup progress; R2/D1/D10/C9 make reading independent of legacy card visibility and prohibit a duplicate returning count."
        }
      ],
      "checkLimits": "Numbered progress/current-step and hide-to-reveal-corpus assertions are removed, not preserved. modules/ui-web/src/shell-v0/chrome/FirstMinutes.app-lockdown.test.ts#S4-8 browser path entry has no Collection; acceptance focuses Search and reading count expires checks focused Search and count expiry; #S4-1 first launch has one message/action and no field, checklist, sidebar or status checks replacement first launch with intercepted responses, not the Engine-backed obligation. This modified file remains the Engine-path check; no Engine run occurred here. Dropping recordFirstUseSearch from the AppWindow type is unused harness signature cleanup."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.searchSummon.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "H4",
          "scenarioName": "handover slice 4",
          "quote": "4. **First launch and the search field** (fm-01, 02, 03, 16; critique A3, A4, B1, B3, B4, B7). One message and one action on first launch; the Getting started card and checklist go. The field: magnifier, one line, Enter; no send arrow, no effort control; Search / Ask in it only once answers work on this computer (fm-16). Recent searches are a plain list under the field with Clear, never opening by themselves over other text. No conversations column until a conversation exists. Checked on main: typing only updates the draft (`Sv3Composer.ts`, `onInput`), a search starts on Enter. That matches the pictures.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R7",
          "scenarioName": "rule 7",
          "quote": "The search box is a search field: magnifier, one line, Enter. No send arrow, no effort control. Search / Ask appears in it only once answers work on this computer.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "decision": "D5",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Search mode: magnifier, one line (pasted newlines become spaces), Enter searches, no send arrow, no effort control, no placeholder prompt; the headline labels the field (aria-labelledby); when the headline is not shown (results docked) the field's name is \"Search\". Ask mode: today's Ask composer (multi-line, effort, send, stop, scope).",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none found in docs/decisions for getting started, first launch, recent, search-as-you-type, typeahead, debounce, corpus, Draft kept, Ctrl+Enter, Search / Ask, Structured and Delegate. Matches concern engine query/metadata contracts (ADR-0016/0020), inference/model and context policy (ADR-0019/0047), scoped paths (ADR-0028), primitives (ADR-0031), streams and resource categories (ADR-0036/0037), rather than the superseded UI expectations. Private task records were not read in this builder session; the acceptor must confirm the supplied adoption and delegation turns and scope. This search does not supply missing intent for a coverage reduction flagged in checkLimits.",
      "assertionChanges": [
        {
          "oldAssertion": "Typing a draft arms timers; summon preserves it, leaves a nonempty aria-label and allows the quick stage/timers to run.",
          "newAssertion": "Typing and summon keep the shared search query empty before and after 200ms, preserve/focus the actual draft field, and accept aria-label or aria-labelledby.",
          "reason": "H4 explicitly says typing changes only the draft and Enter starts search. D5 names the hero field through the headline. Those sources supersede the quick-search timer expectation."
        }
      ],
      "checkLimits": "Weaker accessible-name observation: a nonempty aria-label or aria-labelledby attribute does not prove that an ID resolves to text; no name computation is asserted here. Repeating the empty-query assertion twice adds no coverage. No assertion now proves timer scheduling/cancellation, and the new query observation alone does not rule out every network request. modules/ui-web/src/shell-v0/views/search-v3/FirstMinutes.test.ts#S4-13 Search has a magnifier and headline label, with no prompt, send or effort separately checks the exact hero id and docked Search label, not a computed name. SearchV3View.searchMode.test.ts checks that typing issues no search and Enter does."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/GettingStarted.reading-lockdown.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R2",
          "scenarioName": "rule 2",
          "quote": "Every fact has one home on a screen. If a headline says it, the line under it does not; if the picture says it, the sentence does not; a number shown on a row is not repeated in a summary above it. A second place is allowed only where it does a different job (the status in the bar is visible from every area). The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R6",
          "scenarioName": "rule 6",
          "quote": "First launch is one message and one action. No Getting started card, no checklist, no status in the bar. AI is offered where it helps: on a search that finds nothing, and in AI Brain.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "decision": "D1",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "No \"of M\": the line is \"N files searchable so far\" (no trustworthy total exists; HANDOVER: never invent data). Unknown count: no number.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "D10",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Right after the first folder (fm-02): headline \"What are you looking for?\", field focused, one line \"N files searchable so far\", bar status shows reading in words. Returning (fm-03): no count line in Search (it lives in Library).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "C9",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "fm-02 line lifetime: \"N files searchable so far\" shows on Search's idle screen only while the first reading of the user's folders is under way (indexing activity running, no completed search needed); otherwise no count line; independent of stored first-use progress. Stop writing first-use progress (try-search); keep existing stored records.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none found in docs/decisions for getting started, first launch, recent, search-as-you-type, typeahead, debounce, corpus, Draft kept, Ctrl+Enter, Search / Ask, Structured and Delegate. Matches concern engine query/metadata contracts (ADR-0016/0020), inference/model and context policy (ADR-0019/0047), scoped paths (ADR-0028), primitives (ADR-0031), streams and resource categories (ADR-0036/0037), rather than the superseded UI expectations. Private task records were not read in this builder session; the acceptor must confirm the supplied adoption and delegation turns and scope. This search does not supply missing intent for a coverage reduction flagged in checkLimits.",
      "assertionChanges": [
        {
          "oldAssertion": "Mount jf-getting-started and assert its .progress says 3 files searchable so far · still reading for INDEXING with null/zero queue.",
          "newAssertion": "Mount jf-sv3-composer and assert first-use-progress says 3 files searchable so far for those same queue cases; size html/body to the viewport.",
          "reason": "The card is removed by R6; D1/D10/C9 put only the partial own count on idle Search while live reading continues. The full-height harness is fixture-only support for that composer, not a new count oracle or a passing visibility check."
        }
      ],
      "checkLimits": "The waiting/still-reading words are intentionally removed from this line; no same-line replacement is claimed. modules/ui-web/src/shell-v0/state/FirstMinutes.reading.test.ts#keeps live reading visible with unknown enrichment and queue %s checks reading:true/searchable:3 even when enrichment cannot confirm work; it is a state check, not a browser count rendering replacement. FirstMinutes.app-lockdown.test.ts#S4-8 browser path entry has no Collection; acceptance focuses Search and reading count expires checks the reading ring and count expiry, not this unknown-queue pair. No browser check was run here."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/GettingStarted.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R6",
          "scenarioName": "rule 6",
          "quote": "First launch is one message and one action. No Getting started card, no checklist, no status in the bar. AI is offered where it helps: on a search that finds nothing, and in AI Brain.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "decision": "D7",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Getting started card, checklist and \"Getting started · N of 3\" status removed; stored first-use progress is not migrated or deleted. Plugin walkthroughs, hosted by the card today, are listed in Help instead (where \"Getting started\" was reopened). First launch shows no status in the bar.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "D8",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "First-launch folder add (fm-01): \"Add a folder\" -> native picker -> added at once (no second Add, no Collection); browser: a path field + Add (no Collection); Documents/Desktop/Downloads add at once (existing backend paths; missing ones omitted). Errors stay in place with retry. Library's add flow unchanged in this slice (slice 6).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "D10",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Right after the first folder (fm-02): headline \"What are you looking for?\", field focused, one line \"N files searchable so far\", bar status shows reading in words. Returning (fm-03): no count line in Search (it lives in Library).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "C4",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Picker failure must be distinguishable from cancel (folderPicker returns an error outcome); first launch shows the error and a path field to type a folder. Library's add flow keeps its current confirmation/Collection behaviour (preservation scenario).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "C8",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Plugin walkthroughs stay resumable in Help: interactive WalkthroughCard with existing ids/progress (Next/Done/Dismiss), on both Help render paths.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "C9",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "fm-02 line lifetime: \"N files searchable so far\" shows on Search's idle screen only while the first reading of the user's folders is under way (indexing activity running, no completed search needed); otherwise no count line; independent of stored first-use progress. Stop writing first-use progress (try-search); keep existing stored records.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none found in docs/decisions for getting started, first launch, recent, search-as-you-type, typeahead, debounce, corpus, Draft kept, Ctrl+Enter, Search / Ask, Structured and Delegate. Matches concern engine query/metadata contracts (ADR-0016/0020), inference/model and context policy (ADR-0019/0047), scoped paths (ADR-0028), primitives (ADR-0031), streams and resource categories (ADR-0036/0037), rather than the superseded UI expectations. Private task records were not read in this builder session; the acceptor must confirm the supplied adoption and delegation turns and scope. This search does not supply missing intent for a coverage reduction flagged in checkLimits.",
      "assertionChanges": [
        {
          "oldAssertion": "A pending plugin walkthrough is discoverable in the first-use card; unregistering leaves the entire card text empty.",
          "newAssertion": "Mount Help instead; retain saved step Two, plugin title, no Try a search and saved ids/progress. After unregistering, the walkthrough card must not contain Sample tour.",
          "reason": "D7/C8 move resumable plugin walkthroughs to Help. Expecting the whole Help surface to become empty would contradict its other responsibilities; the new assertion addresses the removed plugin only."
        },
        {
          "oldAssertion": "Suggestions use jf-button; the watched Documents path must not occur anywhere in the host text.",
          "newAssertion": "Select native suggestion buttons, still require two deduplicated suggestions, reseed roots after the AI reset, and require the Add Documents action to be absent.",
          "reason": "D8 uses immediate folder-add actions and excludes already watched suggestions. Native-button selection and reseeding are fixture/selector changes; the new absence-of-action check retains the exclusion purpose but is narrower than forbidding every path mention."
        },
        {
          "oldAssertion": "A later successful own-file search completes try-search; the mounted/reloaded card displays counts, waiting and Try a search.",
          "newAssertion": "Neither search writes try-search; no Try a search is rendered and the legacy host is empty with existing roots, including after reload.",
          "reason": "R6/D7 remove the checklist and C9 stops new progress writes. The Search composer, rather than this host, owns the transient count."
        }
      ],
      "checkLimits": "Weakened observations: unregister no longer proves the whole host empty, and absence of Add Documents no longer proves absence of the literal /Documents path everywhere. No exact replacement for those broader text bans is demonstrated. Removed count/reload checks move in part to modules/ui-web/src/shell-v0/chrome/FirstMinutes.app-lockdown.test.ts#S4-8 browser path entry has no Collection; acceptance focuses Search and reading count expires and modified FirstUse.app-lockdown.test.ts#reloading during indexing rediscovers the folder and live count before offering search. modules/ui-web/src/shell-v0/views/HelpSurface.firstMinutes.test.ts#S4-21 resumes the saved plugin step and completes from Help (declared=false/true) and #S4-21 Next and Dismiss keep their saved progress in Help (declared=false/true) check both Help paths and interactive saved progress. modules/ui-web/src/shell-v0/views/search-v3/FirstMinutes.test.ts#S4-3 native first-launch selection adds once immediately, without confirmation, #S4-4 native cancel restores focus; picker error exposes an actionable manual fallback, and #S4-20 Library selection still requires confirmation and keeps Collection check the changed folder flow/preservation; utils/folderPicker.firstMinutes.test.ts#S4-4 native success and cancel remain distinct from failure checks the picker outcome boundary. These are pointers to read tests, not execution evidence."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R7",
          "scenarioName": "rule 7",
          "quote": "The search box is a search field: magnifier, one line, Enter. No send arrow, no effort control. Search / Ask appears in it only once answers work on this computer.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "decision": "D3",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "\"Answers work on this computer\" = AI installed (known true) AND an NVIDIA/CUDA GPU available (known true). Then the field shows Search / Ask; otherwise no mode control. Ask's own availability gate stays for running. Existing conversations and Ask deep links stay reachable regardless.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "D5",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Search mode: magnifier, one line (pasted newlines become spaces), Enter searches, no send arrow, no effort control, no placeholder prompt; the headline labels the field (aria-labelledby); when the headline is not shown (results docked) the field's name is \"Search\". Ask mode: today's Ask composer (multi-line, effort, send, stop, scope).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "C1",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "(D3/D4) Offering a new mode differs from showing an existing task: when a restored or opened task is Ask/Structured/Delegate while that mode would not be offered, the field shows the switch with that mode and Search, so the user can return to Search; text, schema and scope are kept; no silent mode change.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "C3",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "(D3) \"Answers work\" = installed known true AND CUDA available known true AND the existing Ask availability (F/state/availability.ts) says available or loadable-on-demand, from a live snapshot. Repair-needed, chat switched off, stale or unknown: no switch (C1 still applies for existing tasks).",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none found in docs/decisions for getting started, first launch, recent, search-as-you-type, typeahead, debounce, corpus, Draft kept, Ctrl+Enter, Search / Ask, Structured and Delegate. Matches concern engine query/metadata contracts (ADR-0016/0020), inference/model and context policy (ADR-0019/0047), scoped paths (ADR-0028), primitives (ADR-0031), streams and resource categories (ADR-0036/0037), rather than the superseded UI expectations. Private task records were not read in this builder session; the acceptor must confirm the supplied adoption and delegation turns and scope. This search does not supply missing intent for a coverage reduction flagged in checkLimits.",
      "assertionChanges": [
        {
          "oldAssertion": "From default Search, click a required mode switch while indexing is paused and assert Ask is enabled.",
          "newAssertion": "Fixture-only change: set the window tier to Ask, settle, optionally click its switch, then keep the Ask-enabled assertion and Resume indexing observation.",
          "reason": "C1 keeps existing Ask reachable even when new Ask would not be offered under D3/C3. D5 preserves the Ask composer. This no longer promises that a fresh Search always offers a switch."
        }
      ],
      "checkLimits": "Coverage is narrower: directly setting tier bypasses the user entry route, and optional chaining removes the required trigger-existence observation. There is no equivalent pause-specific fresh-Search switch assertion. modules/ui-web/src/shell-v0/views/search-v3/FirstMinutes.availability.test.ts#S4-14 live installed CUDA availability offers Search/Ask without losing text when readiness changes covers live offer gating, and FirstMinutes.test.ts#S4-14 restored ask always offers Search without discarding draft/schema covers the existing-mode escape; neither is a paused-indexing navigation replacement."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/firstUse.persistence.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R6",
          "scenarioName": "rule 6",
          "quote": "First launch is one message and one action. No Getting started card, no checklist, no status in the bar. AI is offered where it helps: on a search that finds nothing, and in AI Brain.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "decision": "D7",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Getting started card, checklist and \"Getting started · N of 3\" status removed; stored first-use progress is not migrated or deleted. Plugin walkthroughs, hosted by the card today, are listed in Help instead (where \"Getting started\" was reopened). First launch shows no status in the bar.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none found in docs/decisions for getting started, first launch, recent, search-as-you-type, typeahead, debounce, corpus, Draft kept, Ctrl+Enter, Search / Ask, Structured and Delegate. Matches concern engine query/metadata contracts (ADR-0016/0020), inference/model and context policy (ADR-0019/0047), scoped paths (ADR-0028), primitives (ADR-0031), streams and resource categories (ADR-0036/0037), rather than the superseded UI expectations. Private task records were not read in this builder session; the acceptor must confirm the supplied adoption and delegation turns and scope. This search does not supply missing intent for a coverage reduction flagged in checkLimits.",
      "assertionChanges": [
        {
          "oldAssertion": "Malformed legacy welcome data still projects visible:true for an empty library.",
          "newAssertion": "It projects visible:false; malformed progress still reads undefined and the exact raw stored bytes remain unchanged.",
          "reason": "R6/D7 permanently remove the checklist, including malformed-record fallback, while explicitly preserving stored records."
        }
      ],
      "checkLimits": "No storage assertion is weakened or deleted. The visible:true fallback is deliberately retired; rendering the replacement first-launch action is outside this state test and is checked by modules/ui-web/src/shell-v0/chrome/FirstMinutes.app-lockdown.test.ts#S4-1 first launch has one message/action and no field, checklist, sidebar or status."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/firstUse.pill.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R6",
          "scenarioName": "rule 6",
          "quote": "First launch is one message and one action. No Getting started card, no checklist, no status in the bar. AI is offered where it helps: on a search that finds nothing, and in AI Brain.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "decision": "D7",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Getting started card, checklist and \"Getting started · N of 3\" status removed; stored first-use progress is not migrated or deleted. Plugin walkthroughs, hosted by the card today, are listed in Help instead (where \"Getting started\" was reopened). First launch shows no status in the bar.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none found in docs/decisions for getting started, first launch, recent, search-as-you-type, typeahead, debounce, corpus, Draft kept, Ctrl+Enter, Search / Ask, Structured and Delegate. Matches concern engine query/metadata contracts (ADR-0016/0020), inference/model and context policy (ADR-0019/0047), scoped paths (ADR-0028), primitives (ADR-0031), streams and resource categories (ADR-0036/0037), rather than the superseded UI expectations. Private task records were not read in this builder session; the acceptor must confirm the supplied adoption and delegation turns and scope. This search does not supply missing intent for a coverage reduction flagged in checkLimits.",
      "assertionChanges": [
        {
          "oldAssertion": "Calm setup state has the exact label Getting started · 1/2/3 of 3, including the default case.",
          "newAssertion": "Each such label must not contain Getting started; unknown kind and needs/problems/running/finished precedence assertions stay.",
          "reason": "R6/D7 remove setup progress from the bar, independent of the legacy input still supplied to derivation."
        }
      ],
      "checkLimits": "The exact positive calm label is replaced by a narrower forbidden-substring check: arbitrary other text could pass. No exact-label replacement for every step/unknown-verdict variant is demonstrated. frameStatus.optionalAi.test.ts checks two specific default labels; modules/ui-web/src/shell-v0/chrome/FirstMinutes.app-lockdown.test.ts#S4-1 first launch has one message/action and no field, checklist, sidebar or status bans Getting started text but does not check complete absence of every possible bar status. Urgent-state precedence is retained."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/firstUse.reset.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R6",
          "scenarioName": "rule 6",
          "quote": "First launch is one message and one action. No Getting started card, no checklist, no status in the bar. AI is offered where it helps: on a search that finds nothing, and in AI Brain.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "decision": "D7",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Getting started card, checklist and \"Getting started · N of 3\" status removed; stored first-use progress is not migrated or deleted. Plugin walkthroughs, hosted by the card today, are listed in Help instead (where \"Getting started\" was reopened). First launch shows no status in the bar.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none found in docs/decisions for getting started, first launch, recent, search-as-you-type, typeahead, debounce, corpus, Draft kept, Ctrl+Enter, Search / Ask, Structured and Delegate. Matches concern engine query/metadata contracts (ADR-0016/0020), inference/model and context policy (ADR-0019/0047), scoped paths (ADR-0028), primitives (ADR-0031), streams and resource categories (ADR-0036/0037), rather than the superseded UI expectations. Private task records were not read in this builder session; the acceptor must confirm the supplied adoption and delegation turns and scope. This search does not supply missing intent for a coverage reduction flagged in checkLimits.",
      "assertionChanges": [
        {
          "oldAssertion": "After completed factory-reset cleanup, absent progress projects visible:true with all steps false.",
          "newAssertion": "The same projection has visible:false and the same three false steps; native reset invocation and absent stored walkthrough progress remain required.",
          "reason": "R6/D7 remove the checklist even on a reset installation; this does not authorize changing factory-reset deletion semantics."
        }
      ],
      "checkLimits": "No cleanup or done-array assertion is removed. Replacement launch UI is covered by modules/ui-web/src/shell-v0/chrome/FirstMinutes.app-lockdown.test.ts#S4-1 first launch has one message/action and no field, checklist, sidebar or status, which does not run a factory reset."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/firstUse.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R6",
          "scenarioName": "rule 6",
          "quote": "First launch is one message and one action. No Getting started card, no checklist, no status in the bar. AI is offered where it helps: on a search that finds nothing, and in AI Brain.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "decision": "D7",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Getting started card, checklist and \"Getting started · N of 3\" status removed; stored first-use progress is not migrated or deleted. Plugin walkthroughs, hosted by the card today, are listed in Help instead (where \"Getting started\" was reopened). First launch shows no status in the bar.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none found in docs/decisions for getting started, first launch, recent, search-as-you-type, typeahead, debounce, corpus, Draft kept, Ctrl+Enter, Search / Ask, Structured and Delegate. Matches concern engine query/metadata contracts (ADR-0016/0020), inference/model and context policy (ADR-0019/0047), scoped paths (ADR-0028), primitives (ADR-0031), streams and resource categories (ADR-0036/0037), rather than the superseded UI expectations. Private task records were not read in this builder session; the acceptor must confirm the supplied adoption and delegation turns and scope. This search does not supply missing intent for a coverage reduction flagged in checkLimits.",
      "assertionChanges": [
        {
          "oldAssertion": "Confirmed empty state, watched-zero state, legacy welcome variants, explicit reopen and factory reset can make Getting started visible.",
          "newAssertion": "All those cases project visible:false. Empty/known/unknown facts, done/current-step values, folderNeedsAttention, reopened:true, raw records, plugin/legacy progress and co-resident values retain their checks.",
          "reason": "R6/D7 remove checklist visibility across those legacy states while retaining the records. Renaming suppress to _suppress marks the retired welcome-visibility discriminator; it is not evidence of preserved visibility semantics."
        }
      ],
      "checkLimits": "All changed visibility assertions intentionally retire the checklist; no replacement is claimed for Help reopening that card. Legacy state preservation remains checked. modules/ui-web/src/shell-v0/chrome/FirstMinutes.app-lockdown.test.ts#S4-1 first launch has one message/action and no field, checklist, sidebar or status checks the replacement screen, not every legacy projection permutation. C9 reading lifetime is separately tested in FirstMinutes.reading.test.ts#keeps live reading visible with unknown enrichment and queue %s; this file still checks legacy state facts."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/frameStatus.optionalAi.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R6",
          "scenarioName": "rule 6",
          "quote": "First launch is one message and one action. No Getting started card, no checklist, no status in the bar. AI is offered where it helps: on a search that finds nothing, and in AI Brain.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "decision": "D7",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Getting started card, checklist and \"Getting started · N of 3\" status removed; stored first-use progress is not migrated or deleted. Plugin walkthroughs, hosted by the card today, are listed in Help instead (where \"Getting started\" was reopened). First launch shows no status in the bar.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none found in docs/decisions for getting started, first launch, recent, search-as-you-type, typeahead, debounce, corpus, Draft kept, Ctrl+Enter, Search / Ask, Structured and Delegate. Matches concern engine query/metadata contracts (ADR-0016/0020), inference/model and context policy (ADR-0019/0047), scoped paths (ADR-0028), primitives (ADR-0031), streams and resource categories (ADR-0036/0037), rather than the superseded UI expectations. Private task records were not read in this builder session; the acceptor must confirm the supplied adoption and delegation turns and scope. This search does not supply missing intent for a coverage reduction flagged in checkLimits.",
      "assertionChanges": [
        {
          "oldAssertion": "Fresh not_installed/connecting AI with setup visible has the exact Getting started · 1 of 3 pill.",
          "newAssertion": "not_installed expects Keyword search ready; connecting expects Checking search status · last seen just now · checking. Blocked progress and empty needs remain required.",
          "reason": "D7 removes the setup override; the existing readiness/status path supplies those two labels. R6 supports removing the setup pill, not proof that first launch renders either positive status."
        }
      ],
      "checkLimits": "The exact two fallback strings are implementation-derived existing-path expectations; the supplied intent does not separately prescribe their wording. This entry flags that provenance limit rather than treating candidate output as owner words. R6 also requires no bar status on first launch: this deriveFrameStatus unit test does not establish that rendering condition. No assertion is deleted; optional-AI/needs-you checks remain."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.acceptedOutputCompatibility.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R7",
          "scenarioName": "rule 7",
          "quote": "The search box is a search field: magnifier, one line, Enter. No send arrow, no effort control. Search / Ask appears in it only once answers work on this computer.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "decision": "D3",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "\"Answers work on this computer\" = AI installed (known true) AND an NVIDIA/CUDA GPU available (known true). Then the field shows Search / Ask; otherwise no mode control. Ask's own availability gate stays for running. Existing conversations and Ask deep links stay reachable regardless.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "D4",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Structured and Delegate are offered in the switch only when developer tools are on (HANDOVER: not on a picture -> behind developer tools).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "C1",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "(D3/D4) Offering a new mode differs from showing an existing task: when a restored or opened task is Ask/Structured/Delegate while that mode would not be offered, the field shows the switch with that mode and Search, so the user can return to Search; text, schema and scope are kept; no silent mode change.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "C3",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "(D3) \"Answers work\" = installed known true AND CUDA available known true AND the existing Ask availability (F/state/availability.ts) says available or loadable-on-demand, from a live snapshot. Repair-needed, chat switched off, stale or unknown: no switch (C1 still applies for existing tasks).",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none found in docs/decisions for getting started, first launch, recent, search-as-you-type, typeahead, debounce, corpus, Draft kept, Ctrl+Enter, Search / Ask, Structured and Delegate. Matches concern engine query/metadata contracts (ADR-0016/0020), inference/model and context policy (ADR-0019/0047), scoped paths (ADR-0028), primitives (ADR-0031), streams and resource categories (ADR-0036/0037), rather than the superseded UI expectations. Private task records were not read in this builder session; the acceptor must confirm the supplied adoption and delegation turns and scope. This search does not supply missing intent for a coverage reduction flagged in checkLimits.",
      "assertionChanges": [
        {
          "oldAssertion": "Structured-mode suites rely on implicitly offered modes from an online/available snapshot without explicit install, CUDA or developer state.",
          "newAssertion": "Fixture-only changes import install/developer setters, explicitly set install and developer flags after AI reset, feed cudaAvailable:true in online fixtures, and set those flags in teardown too. Expected outcomes are unchanged.",
          "reason": "R7/D3/C3 gate new mode offers on known installed/live CUDA readiness; D4 gates Structured/Delegate on developer tools. The new fixture supplies those prerequisites; it does not derive expected output from the candidate."
        }
      ],
      "checkLimits": "Accepted structured output, incompatibility, transport/stream failure and request assertions are unchanged. Preconditions changed, so this is not claimed as an Adaptation retaining every old input. The fixture no longer covers offering those modes without known install/CUDA/developer state. Teardown sets flags true rather than restoring their previous value; cross-file state isolation was not run here. modules/ui-web/src/shell-v0/views/search-v3/FirstMinutes.availability.test.ts#S4-14 developer tools reveal Structured/Delegate only with answers available checks positive mode visibility; its uninstalled, unknown-install, no-cuda, unknown-gpu, stale, off, failed and loading cases check that no new Ask is offered. Those checks do not prove this suite leaves no state behind."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.agentRun.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R10",
          "scenarioName": "rule 10",
          "quote": "No conversations column until there is a conversation. A plain search never fills it.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "decision": "D6",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Left column only when a conversation exists (canonical conversation rows or locally submitted conversational turns, excluding synthetic unsent-draft rows; a deep-linked conversation counts while resolving). Agent runs goes with the column.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none found in docs/decisions for getting started, first launch, recent, search-as-you-type, typeahead, debounce, corpus, Draft kept, Ctrl+Enter, Search / Ask, Structured and Delegate. Matches concern engine query/metadata contracts (ADR-0016/0020), inference/model and context policy (ADR-0019/0047), scoped paths (ADR-0028), primitives (ADR-0031), streams and resource categories (ADR-0036/0037), rather than the superseded UI expectations. Private task records were not read in this builder session; the acceptor must confirm the supplied adoption and delegation turns and scope. This search does not supply missing intent for a coverage reduction flagged in checkLimits.",
      "assertionChanges": [
        {
          "oldAssertion": "An unclaimed live run in a fresh window has zero sidebar rows.",
          "newAssertion": "The sidebar itself must be absent; getCtrl must still never be called.",
          "reason": "R10/D6 withhold the whole conversations/Agent runs column until a conversation exists. This is stricter about chrome and keeps the no-controller side effect assertion."
        }
      ],
      "checkLimits": "No controller assertion is removed. Zero rows is implied by an absent sidebar for this empty-window case; it does not check row rendering when a column exists. modules/ui-web/src/shell-v0/chrome/FirstMinutes.app-lockdown.test.ts#S4-1 first launch has one message/action and no field, checklist, sidebar or status adds the first-launch absence check."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.controls.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R2",
          "scenarioName": "rule 2",
          "quote": "Every fact has one home on a screen. If a headline says it, the line under it does not; if the picture says it, the sentence does not; a number shown on a row is not repeated in a summary above it. A second place is allowed only where it does a different job (the status in the bar is visible from every area). The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R7",
          "scenarioName": "rule 7",
          "quote": "The search box is a search field: magnifier, one line, Enter. No send arrow, no effort control. Search / Ask appears in it only once answers work on this computer.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "decision": "D3",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "\"Answers work on this computer\" = AI installed (known true) AND an NVIDIA/CUDA GPU available (known true). Then the field shows Search / Ask; otherwise no mode control. Ask's own availability gate stays for running. Existing conversations and Ask deep links stay reachable regardless.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "D4",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Structured and Delegate are offered in the switch only when developer tools are on (HANDOVER: not on a picture -> behind developer tools).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "D5",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Search mode: magnifier, one line (pasted newlines become spaces), Enter searches, no send arrow, no effort control, no placeholder prompt; the headline labels the field (aria-labelledby); when the headline is not shown (results docked) the field's name is \"Search\". Ask mode: today's Ask composer (multi-line, effort, send, stop, scope).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "D10",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Right after the first folder (fm-02): headline \"What are you looking for?\", field focused, one line \"N files searchable so far\", bar status shows reading in words. Returning (fm-03): no count line in Search (it lives in Library).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "C1",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "(D3/D4) Offering a new mode differs from showing an existing task: when a restored or opened task is Ask/Structured/Delegate while that mode would not be offered, the field shows the switch with that mode and Search, so the user can return to Search; text, schema and scope are kept; no silent mode change.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "C3",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "(D3) \"Answers work\" = installed known true AND CUDA available known true AND the existing Ask availability (F/state/availability.ts) says available or loadable-on-demand, from a live snapshot. Repair-needed, chat switched off, stale or unknown: no switch (C1 still applies for existing tasks).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "C9",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "fm-02 line lifetime: \"N files searchable so far\" shows on Search's idle screen only while the first reading of the user's folders is under way (indexing activity running, no completed search needed); otherwise no count line; independent of stored first-use progress. Stop writing first-use progress (try-search); keep existing stored records.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none found in docs/decisions for getting started, first launch, recent, search-as-you-type, typeahead, debounce, corpus, Draft kept, Ctrl+Enter, Search / Ask, Structured and Delegate. Matches concern engine query/metadata contracts (ADR-0016/0020), inference/model and context policy (ADR-0019/0047), scoped paths (ADR-0028), primitives (ADR-0031), streams and resource categories (ADR-0036/0037), rather than the superseded UI expectations. Private task records were not read in this builder session; the acceptor must confirm the supplied adoption and delegation turns and scope. This search does not supply missing intent for a coverage reduction flagged in checkLimits.",
      "assertionChanges": [
        {
          "oldAssertion": "Zero corpus exposes a primitive remedy that dispatches exactly one navigation to Library.",
          "newAssertion": "The corpus remedy must be absent in Search; related imports/listener/click assertions are removed.",
          "reason": "R2/D10/C9 give the corpus fact to Library rather than repeat it in returning Search. This intentionally removes that remedy route, not merely its selector."
        },
        {
          "oldAssertion": "Default Search always exposes mode/send/effort controls for primitive ownership tests.",
          "newAssertion": "Fixture-only changes enable developer tools, set existing Delegate in gate tests and existing Ask in native-menu/send tests. Their run-decision, primitive identity, disabled and menu semantics assertions remain.",
          "reason": "D4/D5 and C1 distinguish a new-mode offer from an existing conversational task, keeping Ask controls while Search loses send/effort."
        }
      ],
      "checkLimits": "The corpus-navigation boundary check is deleted without a replacement Library-navigation assertion in this file. modules/ui-web/src/shell-v0/views/search-v3/FirstMinutes.test.ts#S4-3 native first-launch selection adds once immediately, without confirmation and #S4-20 Library selection still requires confirmation and keeps Collection cover adding/confirmation rather than that retired navigation. Direct tier assignment bypasses availability entry routes; it preserves downstream control checks only. No new mode-offer claim follows from it."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delivery.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Adaptation",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R7",
          "scenarioName": "rule 7",
          "quote": "The search box is a search field: magnifier, one line, Enter. No send arrow, no effort control. Search / Ask appears in it only once answers work on this computer.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "decision": "D5",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Search mode: magnifier, one line (pasted newlines become spaces), Enter searches, no send arrow, no effort control, no placeholder prompt; the headline labels the field (aria-labelledby); when the headline is not shown (results docked) the field's name is \"Search\". Ask mode: today's Ask composer (multi-line, effort, send, stop, scope).",
          "label": "agent choice"
        }
      ],
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Both send sites now dispatch Enter on the input, including the retained-B/review-confirmation case. Delivery, attachment ownership, read confirmation, dispatch count and newer-draft assertions are unchanged.",
      "assertionChanges": [
        {
          "oldAssertion": "An already explicit submit is represented by clicking sv3-composer-send.",
          "newAssertion": "The same explicit submit is encoded as an Enter keydown on sv3-composer-input. Both send sites now dispatch Enter on the input, including the retained-B/review-confirmation case. Delivery, attachment ownership, read confirmation, dispatch count and newer-draft assertions are unchanged.",
          "reason": "R7/D5 remove Search's send arrow and specify Enter. Inputs, fault injection, ownership/retention outcomes and refusal oracles of these explicit-submit cases are retained; they do not test automatic typing."
        }
      ],
      "checkLimits": "Input re-encoding only; no expected value/assertion is deleted. Pointer activation of the old Search send button is retired by R7. These synthetic Enter events do not prove a native browser key path or retain a pointer-send coverage claim."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Adaptation",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R7",
          "scenarioName": "rule 7",
          "quote": "The search box is a search field: magnifier, one line, Enter. No send arrow, no effort control. Search / Ask appears in it only once answers work on this computer.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "decision": "D5",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Search mode: magnifier, one line (pasted newlines become spaces), Enter searches, no send arrow, no effort control, no placeholder prompt; the headline labels the field (aria-labelledby); when the headline is not shown (results docked) the field's name is \"Search\". Ask mode: today's Ask composer (multi-line, effort, send, stop, scope).",
          "label": "agent choice"
        }
      ],
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "The invalid cutover-task test dispatches Enter on the input instead of clicking send. It still checks the original keep text, visible Invalid/Unknown/No readable refusal and zero dispatches.",
      "assertionChanges": [
        {
          "oldAssertion": "An already explicit submit is represented by clicking sv3-composer-send.",
          "newAssertion": "The same explicit submit is encoded as an Enter keydown on sv3-composer-input. The invalid cutover-task test dispatches Enter on the input instead of clicking send. It still checks the original keep text, visible Invalid/Unknown/No readable refusal and zero dispatches.",
          "reason": "R7/D5 remove Search's send arrow and specify Enter. Inputs, fault injection, ownership/retention outcomes and refusal oracles of these explicit-submit cases are retained; they do not test automatic typing."
        }
      ],
      "checkLimits": "Input re-encoding only; no expected value/assertion is deleted. Pointer activation of the old Search send button is retired by R7. These synthetic Enter events do not prove a native browser key path or retain a pointer-send coverage claim."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.honesty.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R2",
          "scenarioName": "rule 2",
          "quote": "Every fact has one home on a screen. If a headline says it, the line under it does not; if the picture says it, the sentence does not; a number shown on a row is not repeated in a summary above it. A second place is allowed only where it does a different job (the status in the bar is visible from every area). The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R10",
          "scenarioName": "rule 10",
          "quote": "No conversations column until there is a conversation. A plain search never fills it.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "decision": "D6",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Left column only when a conversation exists (canonical conversation rows or locally submitted conversational turns, excluding synthetic unsent-draft rows; a deep-linked conversation counts while resolving). Agent runs goes with the column.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "D10",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Right after the first folder (fm-02): headline \"What are you looking for?\", field focused, one line \"N files searchable so far\", bar status shows reading in words. Returning (fm-03): no count line in Search (it lives in Library).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "C9",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "fm-02 line lifetime: \"N files searchable so far\" shows on Search's idle screen only while the first reading of the user's folders is under way (indexing activity running, no completed search needed); otherwise no count line; independent of stored first-use progress. Stop writing first-use progress (try-search); keep existing stored records.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none found in docs/decisions for getting started, first launch, recent, search-as-you-type, typeahead, debounce, corpus, Draft kept, Ctrl+Enter, Search / Ask, Structured and Delegate. Matches concern engine query/metadata contracts (ADR-0016/0020), inference/model and context policy (ADR-0019/0047), scoped paths (ADR-0028), primitives (ADR-0031), streams and resource categories (ADR-0036/0037), rather than the superseded UI expectations. Private task records were not read in this builder session; the acceptor must confirm the supplied adoption and delegation turns and scope. This search does not supply missing intent for a coverage reduction flagged in checkLimits.",
      "assertionChanges": [
        {
          "oldAssertion": "Reported zero corpus has exact remedy copy and clicking it navigates to Library; idle Search has corpus chrome, which disappears when working.",
          "newAssertion": "Neither remedy nor idle corpus chrome may be present. The working-state absence assertion stays.",
          "reason": "R2/D10/C9 remove repeated corpus facts and their duplicate remedy from returning Search."
        },
        {
          "oldAssertion": "Without a claimed conversation an existing sidebar has no session row/export trigger.",
          "newAssertion": "Without a conversation the entire sidebar must be absent; clipboard remains untouched.",
          "reason": "R10/D6 remove the column until a conversation exists."
        },
        {
          "oldAssertion": "The offline copy scanner excludes only SearchV3View.honesty.test.ts from all local .ts files.",
          "newAssertion": "It excludes every *.test.ts file, still rejecting offline in the remaining production .ts files.",
          "reason": "This is a real scan-domain reduction, not a selector update. None of R2/D6/D10/C9 authorizes reducing the test-source scan domain; no supplied source supports that part of the diff."
        }
      ],
      "checkLimits": "Unwarranted weakening flagged: every other test file is newly exempt from the offline copy ban; no replacement is demonstrated, including in FirstMinutes.*.test.ts. Candidate FirstMinutes.availability.test.ts uses an offline fixture value, but making that file pass is not a source of intent. The corpus remedy copy/navigation checks are intentionally deleted with no same-route replacement; FirstMinutes.app-lockdown.test.ts#S4-1 first launch has one message/action and no field, checklist, sidebar or status checks the replacement first-launch action/absent sidebar, not Library navigation or this source scanner. This entry does not claim the unsupported weakening is justified."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.initialLifecycle.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Adaptation",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R7",
          "scenarioName": "rule 7",
          "quote": "The search box is a search field: magnifier, one line, Enter. No send arrow, no effort control. Search / Ask appears in it only once answers work on this computer.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "decision": "D5",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Search mode: magnifier, one line (pasted newlines become spaces), Enter searches, no send arrow, no effort control, no placeholder prompt; the headline labels the field (aria-labelledby); when the headline is not shown (results docked) the field's name is \"Search\". Ask mode: today's Ask composer (multi-line, effort, send, stop, scope).",
          "label": "agent choice"
        }
      ],
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "The shared send helper and Search-placement ABA branch dispatch Enter on the input instead of clicking send. Existing initial-context confirmation, abandonment, ABA and pending-history assertions remain unchanged.",
      "assertionChanges": [
        {
          "oldAssertion": "An already explicit submit is represented by clicking sv3-composer-send.",
          "newAssertion": "The same explicit submit is encoded as an Enter keydown on sv3-composer-input. The shared send helper and Search-placement ABA branch dispatch Enter on the input instead of clicking send. Existing initial-context confirmation, abandonment, ABA and pending-history assertions remain unchanged.",
          "reason": "R7/D5 remove Search's send arrow and specify Enter. Inputs, fault injection, ownership/retention outcomes and refusal oracles of these explicit-submit cases are retained; they do not test automatic typing."
        }
      ],
      "checkLimits": "Input re-encoding only; no expected value/assertion is deleted. Pointer activation of the old Search send button is retired by R7. These synthetic Enter events do not prove a native browser key path or retain a pointer-send coverage claim."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ownCount.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R2",
          "scenarioName": "rule 2",
          "quote": "Every fact has one home on a screen. If a headline says it, the line under it does not; if the picture says it, the sentence does not; a number shown on a row is not repeated in a summary above it. A second place is allowed only where it does a different job (the status in the bar is visible from every area). The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "decision": "D1",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "No \"of M\": the line is \"N files searchable so far\" (no trustworthy total exists; HANDOVER: never invent data). Unknown count: no number.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "D10",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Right after the first folder (fm-02): headline \"What are you looking for?\", field focused, one line \"N files searchable so far\", bar status shows reading in words. Returning (fm-03): no count line in Search (it lives in Library).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "C9",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "fm-02 line lifetime: \"N files searchable so far\" shows on Search's idle screen only while the first reading of the user's folders is under way (indexing activity running, no completed search needed); otherwise no count line; independent of stored first-use progress. Stop writing first-use progress (try-search); keep existing stored records.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none found in docs/decisions for getting started, first launch, recent, search-as-you-type, typeahead, debounce, corpus, Draft kept, Ctrl+Enter, Search / Ask, Structured and Delegate. Matches concern engine query/metadata contracts (ADR-0016/0020), inference/model and context policy (ADR-0019/0047), scoped paths (ADR-0028), primitives (ADR-0031), streams and resource categories (ADR-0036/0037), rather than the superseded UI expectations. Private task records were not read in this builder session; the acceptor must confirm the supplied adoption and delegation turns and scope. This search does not supply missing intent for a coverage reduction flagged in checkLimits.",
      "assertionChanges": [
        {
          "oldAssertion": "Idle corpus line states Searching 12 files / Searching 1 file, zero words/remedy, unknown words, and late unknown-to-7 replacement.",
          "newAssertion": "For own counts 12, 1, 0, undefined and a later 7, the returning Search corpus line has kind:null, empty text and no remedy.",
          "reason": "R2/D10/C9 keep returning corpus facts in Library and permit a partial line only during live first reading. The new expectation is absence across known/unknown counts, not a count learned from output."
        }
      ],
      "checkLimits": "Deleted: positive/singular wording, no-digit zero wording, zero remedy and unknown-word text assertions. Their old Search presentation is retired, and no equivalent Library wording test is demonstrated here. modules/ui-web/src/shell-v0/chrome/FirstMinutes.app-lockdown.test.ts#S4-8 browser path entry has no Collection; acceptance focuses Search and reading count expires covers transient own-count presence/expiry; modules/ui-web/src/shell-v0/state/FirstMinutes.reading.test.ts#keeps live reading visible with unknown enrichment and queue %s covers reading state. Neither restores every removed singular/unknown/remedy oracle."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.pane.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R10",
          "scenarioName": "rule 10",
          "quote": "No conversations column until there is a conversation. A plain search never fills it.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "decision": "D6",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Left column only when a conversation exists (canonical conversation rows or locally submitted conversational turns, excluding synthetic unsent-draft rows; a deep-linked conversation counts while resolving). Agent runs goes with the column.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none found in docs/decisions for getting started, first launch, recent, search-as-you-type, typeahead, debounce, corpus, Draft kept, Ctrl+Enter, Search / Ask, Structured and Delegate. Matches concern engine query/metadata contracts (ADR-0016/0020), inference/model and context policy (ADR-0019/0047), scoped paths (ADR-0028), primitives (ADR-0031), streams and resource categories (ADR-0036/0037), rather than the superseded UI expectations. Private task records were not read in this builder session; the acceptor must confirm the supplied adoption and delegation turns and scope. This search does not supply missing intent for a coverage reduction flagged in checkLimits.",
      "assertionChanges": [
        {
          "oldAssertion": "Restoring a remembered pane width at 1400px clamps to 1400 - 256 - SV3_MAIN_MIN_PX (504), reserving an empty sidebar.",
          "newAssertion": "It clamps to 560 and still meets the pane minimum.",
          "reason": "R10/D6 remove the empty conversation column. The existing pane ceiling is 560 and the existing main minimum is 640: without a sidebar, 1400 - 640 exceeds 560, so the existing ceiling binds. This arithmetic, not a candidate screenshot, supplies the expected value."
        }
      ],
      "checkLimits": "The old 504 geometry is retired for a no-conversation fixture. Other two-boundary scenarios keep their assertions; this one no longer proves reservation of a sidebar that must be absent. FirstMinutes.app-lockdown.test.ts#S4-1 first launch has one message/action and no field, checklist, sidebar or status checks column absence, not pane dimensions. The nearby historical comment still describes the old 504 clamp and was not corrected in this documentation-only assignment."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.recents.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "H4",
          "scenarioName": "handover slice 4",
          "quote": "4. **First launch and the search field** (fm-01, 02, 03, 16; critique A3, A4, B1, B3, B4, B7). One message and one action on first launch; the Getting started card and checklist go. The field: magnifier, one line, Enter; no send arrow, no effort control; Search / Ask in it only once answers work on this computer (fm-16). Recent searches are a plain list under the field with Clear, never opening by themselves over other text. No conversations column until a conversation exists. Checked on main: typing only updates the draft (`Sv3Composer.ts`, `onInput`), a search starts on Enter. That matches the pictures.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R10",
          "scenarioName": "rule 10",
          "quote": "No conversations column until there is a conversation. A plain search never fills it.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "decision": "D2",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Recents: searches only (conversations leave the strip; they live in the column). Each entry keeps query, result count and time, recorded when the response arrives; failed/invalid searches show the query without a count. Memory only, as today (no new saved state). Shown only when non-empty, as a plain list in the page flow under the field; never an overlay; Clear empties it.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "D5",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Search mode: magnifier, one line (pasted newlines become spaces), Enter searches, no send arrow, no effort control, no placeholder prompt; the headline labels the field (aria-labelledby); when the headline is not shown (results docked) the field's name is \"Search\". Ask mode: today's Ask composer (multi-line, effort, send, stop, scope).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "D6",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Left column only when a conversation exists (canonical conversation rows or locally submitted conversational turns, excluding synthetic unsent-draft rows; a deep-linked conversation counts while resolving). Agent runs goes with the column.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "C6",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Recent count = the count the results header shows for that search, in its wording (including lower bounds such as \"1,000+\"), attached to the submitted attempt; superseded, failed or invalid attempts get no count; a completion after Clear or a profile switch does not re-add the entry.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none found in docs/decisions for getting started, first launch, recent, search-as-you-type, typeahead, debounce, corpus, Draft kept, Ctrl+Enter, Search / Ask, Structured and Delegate. Matches concern engine query/metadata contracts (ADR-0016/0020), inference/model and context policy (ADR-0019/0047), scoped paths (ADR-0028), primitives (ADR-0031), streams and resource categories (ADR-0036/0037), rather than the superseded UI expectations. Private task records were not read in this builder session; the acceptor must confirm the supplied adoption and delegation turns and scope. This search does not supply missing intent for a coverage reduction flagged in checkLimits.",
      "assertionChanges": [
        {
          "oldAssertion": "Y1/Y7/R1a expect a focus-opened mixed search/conversation listbox with aria-selected, ArrowUp/Down, active descendant, Escape dismissal/focus return, IME/modifier dismissal, and a separately scrolling 13-row list.",
          "newAssertion": "Empty recents render nothing. Completed search rows carry header-count text/time, remain visible after blur, have native tabbable buttons and no field aria-activedescendant. focusEmpty also forces hero state.",
          "reason": "H4/D2 require a nonempty plain Search list under the field, never an overlay; R10/D6 put conversations in the column. The obsolete combobox interaction and its scroll model are deliberately removed, not preserved under new selectors."
        },
        {
          "oldAssertion": "Y2/Y3 record explicit attempts with full exact-dedup/whitespace variants, palette inclusion, no automatic/facet/external-query additions and no duplicate on replay; arrows replay operators/current filters and the strip opens conversations without dispatch.",
          "newAssertion": "Search rows retain exact-case/outer-space dedup examples and eight-item cap; typing alone sends nothing. A focused button is clicked to replay operators and current pdf filters once. Failed/invalid queries have no matches text; responses have a count and datetime; superseded attempts have no count.",
          "reason": "D2/C6 attach response metadata to explicit submitted attempts; D5 retires automatic Search. Removing conversation replay from this list follows the stated separation, not a candidate-derived count."
        },
        {
          "oldAssertion": "Y4/Y5/R5a Clear suppresses conversation backfill/rename/reactivation and delayed list rows, avoids writes, preserves storage-key sets, forgets searches across windows and rejects profile/workspace ABA results.",
          "newAssertion": "Clear empties Search rows, leaves saved conversation ids and permits only search writes; late search completions cannot repopulate. A new window forgets searches but keeps the column. User-profile and workspace ABA drop recents and pending replies; registry-only edits retain rows; old workspace conversation rows are rejected.",
          "reason": "D2 makes Clear search-only and memory-only; C6 explicitly invalidates completions after Clear/profile switch. Conversations remain owned by the separate store, so Clear no longer hides them."
        },
        {
          "oldAssertion": "Y3 retained Ask/Structured preparations survive both recent-search replay and conversation navigation. Y6 hides optimistic/legacy/untitled labels plus pre-lock list responses and handles a history 423 immediately.",
          "newAssertion": "The retained-preparation test and history-423 test are removed. A new lock case forbids Secret/preview-secret in the column and Secret in search recents, then requires the title again on unlock. Plain search still preserves saved conversations, search-only writes and historical SEARCH projection.",
          "reason": "R10/D2 remove conversations from recents; that warrants retiring their list presentation and Clear-hiding behavior, but does not itself waive retained preparations or confidentiality at a remaining navigation boundary."
        }
      ],
      "checkLimits": "Unreplaced/weaker checks are explicit: (1) the new pointer-and-native-keyboard test focuses a button then calls click(); it never dispatches native Enter/Space, so it is not keyboard-activation evidence. (2) The retired list naming/theme/docked/narrow matrix, overflow isolation, Escape focus return, empty-Enter no-search and IME/modifier paths are not replaced in this file. focusEmpty forces hero state, bypassing the natural return-to-idle transition. (3) Exact dedup loses internal-double-space distinctions; palette inclusion, facet/external-query non-recording, replay promotion/no-duplication and storage-key equality assertions are deleted. Search-only writes are retained but do not prove no local/session persistence. (4) The retained-preparation search-replay branch is still relevant and has no replacement demonstrated; removing conversation entries does not justify losing it. Conversation replay/history-no-dispatch is removed from this strip by design, but no exact moved test was established. (5) Optimistic/legacy/untitled labels, pending pre-lock response suppression, history 423 and post-unlock empty-list handling lose their specific checks; the new ordinary lock/unlock case is narrower. (6) The user-profile ABA case loses its stale-conversation-list response check; workspace ABA retains one; fresh workspace labels and final shared-query-empty assertion are deleted. modules/ui-web/src/shell-v0/views/search-v3/FirstMinutes.test.ts#S4-11 recents are an in-flow list of searches with count/time, keyboard reachable, never empty chrome checks 1,000+ text, a time node and lack of tabindex=-1, but no actual keyboard activation, geometric in-flow predicate, response-to-header equality or timestamp bound proving completion time rather than submission time. #S4-13 newlines are stopped in Search, modifier chords never silently Delegate; restored drafts survive checks modifier/IME submission and a retained multiline draft, not recents-specific retention or copy/lock cases. No exempt new file demonstrates replacements for the remaining lost obligations. These are disclosed gaps, not accepted weakening."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.record.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R2",
          "scenarioName": "rule 2",
          "quote": "Every fact has one home on a screen. If a headline says it, the line under it does not; if the picture says it, the sentence does not; a number shown on a row is not repeated in a summary above it. A second place is allowed only where it does a different job (the status in the bar is visible from every area). The same thing has one name everywhere: the app reads files (not \"indexes\"), the button is \"Add a folder\".",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "decision": "D9",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "\"Draft kept\" no longer emitted as status or status-list entry; drafts themselves still kept.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none found in docs/decisions for getting started, first launch, recent, search-as-you-type, typeahead, debounce, corpus, Draft kept, Ctrl+Enter, Search / Ask, Structured and Delegate. Matches concern engine query/metadata contracts (ADR-0016/0020), inference/model and context policy (ADR-0019/0047), scoped paths (ADR-0028), primitives (ADR-0031), streams and resource categories (ADR-0036/0037), rather than the superseded UI expectations. Private task records were not read in this builder session; the acceptor must confirm the supplied adoption and delegation turns and scope. This search does not supply missing intent for a coverage reduction flagged in checkLimits.",
      "assertionChanges": [
        {
          "oldAssertion": "Leaving the first unsent draft emits one Draft kept toast; leaving a second leaves that same one-toast history.",
          "newAssertion": "The toast history must be empty after each leave.",
          "reason": "D9 explicitly removes Draft kept status emission while preserving drafts. The quiet outcome is an agent choice under delegation, not a supposed owner quote or evidence that storage can be dropped."
        }
      ],
      "checkLimits": "No storage-retention assertion is deleted in this diff. These two empty-toast assertions establish silence only; they do not themselves prove the draft was saved. Existing draft persistence tests remain the retention evidence, not these absence assertions."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.reflow.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R10",
          "scenarioName": "rule 10",
          "quote": "No conversations column until there is a conversation. A plain search never fills it.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "decision": "D6",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Left column only when a conversation exists (canonical conversation rows or locally submitted conversational turns, excluding synthetic unsent-draft rows; a deep-linked conversation counts while resolving). Agent runs goes with the column.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none found in docs/decisions for getting started, first launch, recent, search-as-you-type, typeahead, debounce, corpus, Draft kept, Ctrl+Enter, Search / Ask, Structured and Delegate. Matches concern engine query/metadata contracts (ADR-0016/0020), inference/model and context policy (ADR-0019/0047), scoped paths (ADR-0028), primitives (ADR-0031), streams and resource categories (ADR-0036/0037), rather than the superseded UI expectations. Private task records were not read in this builder session; the acceptor must confirm the supplied adoption and delegation turns and scope. This search does not supply missing intent for a coverage reduction flagged in checkLimits.",
      "assertionChanges": [
        {
          "oldAssertion": "Sidebar-dependent tests mount an otherwise empty window and assume its column/New control exists.",
          "newAssertion": "Fixture-only change: provide an existing conversation before exercising the column. The shared mount seeds one canonical geometry-conversation through mergeStoreConversations. Existing host reflow and 640px reading-column checks remain.",
          "reason": "R10/D6 reserve the column for actual canonical or locally submitted conversations, so these downstream interaction checks now supply that prerequisite. Their expected downstream results have not changed."
        }
      ],
      "checkLimits": "Precondition change, not proof of the former empty-window behavior: these cases now cover existing-conversation windows. No assertion is deleted. modules/ui-web/src/shell-v0/chrome/FirstMinutes.app-lockdown.test.ts#S4-1 first launch has one message/action and no field, checklist, sidebar or status checks the opposite empty-column case. That launch test does not replace these geometry/storage/focus checks or prove deep-linked and synthetic-draft edge cases."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.refusalNavigation.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "H4",
          "scenarioName": "handover slice 4",
          "quote": "4. **First launch and the search field** (fm-01, 02, 03, 16; critique A3, A4, B1, B3, B4, B7). One message and one action on first launch; the Getting started card and checklist go. The field: magnifier, one line, Enter; no send arrow, no effort control; Search / Ask in it only once answers work on this computer (fm-16). Recent searches are a plain list under the field with Clear, never opening by themselves over other text. No conversations column until a conversation exists. Checked on main: typing only updates the draft (`Sv3Composer.ts`, `onInput`), a search starts on Enter. That matches the pictures.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "decision": "D5",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Search mode: magnifier, one line (pasted newlines become spaces), Enter searches, no send arrow, no effort control, no placeholder prompt; the headline labels the field (aria-labelledby); when the headline is not shown (results docked) the field's name is \"Search\". Ask mode: today's Ask composer (multi-line, effort, send, stop, scope).",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none found in docs/decisions for getting started, first launch, recent, search-as-you-type, typeahead, debounce, corpus, Draft kept, Ctrl+Enter, Search / Ask, Structured and Delegate. Matches concern engine query/metadata contracts (ADR-0016/0020), inference/model and context policy (ADR-0019/0047), scoped paths (ADR-0028), primitives (ADR-0031), streams and resource categories (ADR-0036/0037), rather than the superseded UI expectations. Private task records were not read in this builder session; the acceptor must confirm the supplied adoption and delegation turns and scope. This search does not supply missing intent for a coverage reduction flagged in checkLimits.",
      "assertionChanges": [
        {
          "oldAssertion": "After typing Cedar following refusal, waiting alone must issue Search and project its address.",
          "newAssertion": "Explicitly press Enter after the same input, then retain search-request and no-query-in-hash/current-address assertions.",
          "reason": "H4/D5 require explicit Enter rather than typing to start a search. The preservation check now has the newly required submit action."
        }
      ],
      "checkLimits": "The automatic-search entry route is intentionally retired; the navigation/projection oracle is unchanged. This is an input-contract change, not evidence that typing alone still searches. FirstMinutes.test.ts#S4-13 newlines are stopped in Search, modifier chords never silently Delegate; restored drafts survive checks unmodified Enter submission, not refusal navigation."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.sameModeLoad.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R7",
          "scenarioName": "rule 7",
          "quote": "The search box is a search field: magnifier, one line, Enter. No send arrow, no effort control. Search / Ask appears in it only once answers work on this computer.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "decision": "D3",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "\"Answers work on this computer\" = AI installed (known true) AND an NVIDIA/CUDA GPU available (known true). Then the field shows Search / Ask; otherwise no mode control. Ask's own availability gate stays for running. Existing conversations and Ask deep links stay reachable regardless.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "D4",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Structured and Delegate are offered in the switch only when developer tools are on (HANDOVER: not on a picture -> behind developer tools).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "D5",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Search mode: magnifier, one line (pasted newlines become spaces), Enter searches, no send arrow, no effort control, no placeholder prompt; the headline labels the field (aria-labelledby); when the headline is not shown (results docked) the field's name is \"Search\". Ask mode: today's Ask composer (multi-line, effort, send, stop, scope).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "C1",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "(D3/D4) Offering a new mode differs from showing an existing task: when a restored or opened task is Ask/Structured/Delegate while that mode would not be offered, the field shows the switch with that mode and Search, so the user can return to Search; text, schema and scope are kept; no silent mode change.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "C3",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "(D3) \"Answers work\" = installed known true AND CUDA available known true AND the existing Ask availability (F/state/availability.ts) says available or loadable-on-demand, from a live snapshot. Repair-needed, chat switched off, stale or unknown: no switch (C1 still applies for existing tasks).",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none found in docs/decisions for getting started, first launch, recent, search-as-you-type, typeahead, debounce, corpus, Draft kept, Ctrl+Enter, Search / Ask, Structured and Delegate. Matches concern engine query/metadata contracts (ADR-0016/0020), inference/model and context policy (ADR-0019/0047), scoped paths (ADR-0028), primitives (ADR-0031), streams and resource categories (ADR-0036/0037), rather than the superseded UI expectations. Private task records were not read in this builder session; the acceptor must confirm the supplied adoption and delegation turns and scope. This search does not supply missing intent for a coverage reduction flagged in checkLimits.",
      "assertionChanges": [
        {
          "oldAssertion": "Mode/load tests use implicitly ready AI/developer state and click send at two explicit-submit sites.",
          "newAssertion": "Fixture-only install/developer/CUDA prerequisites are explicit, including teardown; the two explicit submits use Enter. Pending Load anyway, text/files/schema and one-dispatch/shape assertions remain.",
          "reason": "D3/D4/C3 gate new modes and D5 supplies Search Enter. C1 preserves existing task modes. These changes prepare the intended test state rather than relaxing load-anyway outcomes."
        }
      ],
      "checkLimits": "No assertion is deleted. The changed fixtures no longer assert offers under unknown install/CUDA/developer conditions. Teardown sets flags true, so isolation is unverified. Enter replaces pointer-send coverage at those sites; direct browser key input is not exercised. FirstMinutes.availability.test.ts#S4-14 pressure-unloaded chat remains offerable through the existing on-demand availability covers the gate for on-demand chat, not these same-mode load ownership races."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "H4",
          "scenarioName": "handover slice 4",
          "quote": "4. **First launch and the search field** (fm-01, 02, 03, 16; critique A3, A4, B1, B3, B4, B7). One message and one action on first launch; the Getting started card and checklist go. The field: magnifier, one line, Enter; no send arrow, no effort control; Search / Ask in it only once answers work on this computer (fm-16). Recent searches are a plain list under the field with Clear, never opening by themselves over other text. No conversations column until a conversation exists. Checked on main: typing only updates the draft (`Sv3Composer.ts`, `onInput`), a search starts on Enter. That matches the pictures.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R7",
          "scenarioName": "rule 7",
          "quote": "The search box is a search field: magnifier, one line, Enter. No send arrow, no effort control. Search / Ask appears in it only once answers work on this computer.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R10",
          "scenarioName": "rule 10",
          "quote": "No conversations column until there is a conversation. A plain search never fills it.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "decision": "D3",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "\"Answers work on this computer\" = AI installed (known true) AND an NVIDIA/CUDA GPU available (known true). Then the field shows Search / Ask; otherwise no mode control. Ask's own availability gate stays for running. Existing conversations and Ask deep links stay reachable regardless.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "D4",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Structured and Delegate are offered in the switch only when developer tools are on (HANDOVER: not on a picture -> behind developer tools).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "D5",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Search mode: magnifier, one line (pasted newlines become spaces), Enter searches, no send arrow, no effort control, no placeholder prompt; the headline labels the field (aria-labelledby); when the headline is not shown (results docked) the field's name is \"Search\". Ask mode: today's Ask composer (multi-line, effort, send, stop, scope).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "D6",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Left column only when a conversation exists (canonical conversation rows or locally submitted conversational turns, excluding synthetic unsent-draft rows; a deep-linked conversation counts while resolving). Agent runs goes with the column.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "C1",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "(D3/D4) Offering a new mode differs from showing an existing task: when a restored or opened task is Ask/Structured/Delegate while that mode would not be offered, the field shows the switch with that mode and Search, so the user can return to Search; text, schema and scope are kept; no silent mode change.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "C2",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "(D5) Stored drafts are never normalised. Search mode stops new newlines (Enter searches; pasted newlines become spaces at paste time); a restored multi-line draft shows as it is until edited.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "C3",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "(D3) \"Answers work\" = installed known true AND CUDA available known true AND the existing Ask availability (F/state/availability.ts) says available or loadable-on-demand, from a live snapshot. Repair-needed, chat switched off, stale or unknown: no switch (C1 still applies for existing tasks).",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none found in docs/decisions for getting started, first launch, recent, search-as-you-type, typeahead, debounce, corpus, Draft kept, Ctrl+Enter, Search / Ask, Structured and Delegate. Matches concern engine query/metadata contracts (ADR-0016/0020), inference/model and context policy (ADR-0019/0047), scoped paths (ADR-0028), primitives (ADR-0031), streams and resource categories (ADR-0036/0037), rather than the superseded UI expectations. Private task records were not read in this builder session; the acceptor must confirm the supplied adoption and delegation turns and scope. This search does not supply missing intent for a coverage reduction flagged in checkLimits.",
      "assertionChanges": [
        {
          "oldAssertion": "Search has a named send button; Ctrl+Enter from Search silently delegates; compositionend and delayed typing run quick then refined requests.",
          "newAssertion": "Search has no send button and has the headline label; Ctrl+Enter produces no extra submit. Compositionend/typing issue no request until Enter; Enter sends one refined intent, keeping result/zero conversational-dispatch assertions.",
          "reason": "H4/R7/D5 specify one Search field with Enter and no send arrow; a modified chord may not silently switch to Delegate. Explicit submit already uses the refined path; the retired quick-on-type pipeline is not an authority for new expectations."
        },
        {
          "oldAssertion": "Unavailable chat can be newly chosen through the menu; New exists without a saved conversation.",
          "newAssertion": "Fixture-only: set existing Ask for refusal checks; record a saved conversation before the New/scope case; explicit install/CUDA/developer state supports genuine mode-offer cases.",
          "reason": "D3/D4/C3 gate new offers; C1 preserves existing Ask and R10/D6 require a conversation for New's column."
        },
        {
          "oldAssertion": "Typing beta clears alpha results immediately, and clearing the draft while alpha is pending prevents alpha from rendering; switching back to Search automatically searches.",
          "newAssertion": "Submit beta before asserting old results clear. Clearing an unsubmitted draft leaves alpha.md visible while the field is empty. Switch back to Search and press Enter before asserting matching results.",
          "reason": "H4/D5 separate draft editing from submitted search state. Alpha remains the last submitted query when no new search is requested; this is a derived consequence of explicit-submit behavior, not a request to label alpha as results of the empty draft."
        }
      ],
      "checkLimits": "Changed oracle: preserving alpha results on a cleared unsubmitted draft replaces the former cancellation/empty-results assertion; it does not verify every stale-result/draft distinction or new-query label. The chatUnavailable helper now feeds cudaAvailable:true even though its historical no-GPU comment remains; it tests unavailable chat with CUDA, not CPU hardware. Teardown sets install/developer true; isolation is unverified. modules/ui-web/src/shell-v0/views/search-v3/FirstMinutes.test.ts#S4-13 Search has a magnifier and headline label, with no prompt, send or effort checks replacement anatomy/name; #S4-13 newlines are stopped in Search, modifier chords never silently Delegate; restored drafts survive checks preserved multiline drafts and ignored modifier/IME Enter events (it does not actually paste or assert newlines cannot enter); #S4-14 restored ask always offers Search without discarding draft/schema checks existing-mode recovery. FirstMinutes.availability.test.ts covers true no-CUDA/uninstalled/unknown gating. No native-paste or actual single-line visual behavior is proved by these pointers."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchPreservation.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "H4",
          "scenarioName": "handover slice 4",
          "quote": "4. **First launch and the search field** (fm-01, 02, 03, 16; critique A3, A4, B1, B3, B4, B7). One message and one action on first launch; the Getting started card and checklist go. The field: magnifier, one line, Enter; no send arrow, no effort control; Search / Ask in it only once answers work on this computer (fm-16). Recent searches are a plain list under the field with Clear, never opening by themselves over other text. No conversations column until a conversation exists. Checked on main: typing only updates the draft (`Sv3Composer.ts`, `onInput`), a search starts on Enter. That matches the pictures.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "decision": "D5",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Search mode: magnifier, one line (pasted newlines become spaces), Enter searches, no send arrow, no effort control, no placeholder prompt; the headline labels the field (aria-labelledby); when the headline is not shown (results docked) the field's name is \"Search\". Ask mode: today's Ask composer (multi-line, effort, send, stop, scope).",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none found in docs/decisions for getting started, first launch, recent, search-as-you-type, typeahead, debounce, corpus, Draft kept, Ctrl+Enter, Search / Ask, Structured and Delegate. Matches concern engine query/metadata contracts (ADR-0016/0020), inference/model and context policy (ADR-0019/0047), scoped paths (ADR-0028), primitives (ADR-0031), streams and resource categories (ADR-0036/0037), rather than the superseded UI expectations. Private task records were not read in this builder session; the acceptor must confirm the supplied adoption and delegation turns and scope. This search does not supply missing intent for a coverage reduction flagged in checkLimits.",
      "assertionChanges": [
        {
          "oldAssertion": "The action named typing emits sv3-composer-draft and exercises automatic Search plus first/second attachment acknowledgement failures.",
          "newAssertion": "That branch sets composer.draft and emits sv3-composer-submit, just like an explicit submit; every storage fault, dispatch count, result and retained-draft assertion remains.",
          "reason": "H4/D5 retire automatic Search on typing. The branch now applies the same retention obligations to an explicit request, not to a keystroke."
        }
      ],
      "checkLimits": "Coverage reduction/redundancy: the parameter is still named typing but no longer sends a draft event and duplicates explicit-submit coverage. There is no test of failed-acknowledgement behavior on mere typing after this change, and it must not be reported as one. SearchV3View.searchMode.test.ts#sends the quick pass while typing and the refined pass on settle, through the one intent seam (historical title) now checks zero search requests until Enter, but does not cover every storage fault on edit. No new exempt file replaces the lost typing/storage combination."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.sessions.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R10",
          "scenarioName": "rule 10",
          "quote": "No conversations column until there is a conversation. A plain search never fills it.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "decision": "D5",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Search mode: magnifier, one line (pasted newlines become spaces), Enter searches, no send arrow, no effort control, no placeholder prompt; the headline labels the field (aria-labelledby); when the headline is not shown (results docked) the field's name is \"Search\". Ask mode: today's Ask composer (multi-line, effort, send, stop, scope).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "D6",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Left column only when a conversation exists (canonical conversation rows or locally submitted conversational turns, excluding synthetic unsent-draft rows; a deep-linked conversation counts while resolving). Agent runs goes with the column.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none found in docs/decisions for getting started, first launch, recent, search-as-you-type, typeahead, debounce, corpus, Draft kept, Ctrl+Enter, Search / Ask, Structured and Delegate. Matches concern engine query/metadata contracts (ADR-0016/0020), inference/model and context policy (ADR-0019/0047), scoped paths (ADR-0028), primitives (ADR-0031), streams and resource categories (ADR-0036/0037), rather than the superseded UI expectations. Private task records were not read in this builder session; the acceptor must confirm the supplied adoption and delegation turns and scope. This search does not supply missing intent for a coverage reduction flagged in checkLimits.",
      "assertionChanges": [
        {
          "oldAssertion": "The shared send helper assumes the default tier can send conversational text; helpers assume the sidebar always exists.",
          "newAssertion": "Fixture-only: set Ask before conversational sends; absent-sidebar row/group readers return empty and New returns early. Conversation assertions otherwise continue.",
          "reason": "D5 distinguishes Search from Ask, and R10/D6 remove the column in empty windows. Helper fallbacks avoid treating an absent column as a missing required element in those cases."
        },
        {
          "oldAssertion": "Deleting the final conversation focuses sv3-sidebar-announcer; the repeated-announcement case ends with no rows.",
          "newAssertion": "Deleting the final conversation focuses sv3-composer-input and emits Conversation deleted. The repeat-announcement fixture first adds survivor, so its final row is survivor and the column/announcer stays mounted.",
          "reason": "R10/D6 remove the final empty column, requiring a focus destination that survives. A surviving conversation is a legitimate precondition for retaining the separate repeated live-announcement test; the exact new toast text is not prescribed by those sources."
        }
      ],
      "checkLimits": "Weaker helpers: missing sidebar now silently counts as zero rows/groups, and New may perform no action; those helpers alone cannot prove a New control was present/activated. The repeated-announcement case no longer covers removal of the final conversation; the separate last-row case covers focus plus a toast event. Listening for an event containing Conversation deleted does not by itself prove a rendered polite live region. No new exempt test replaces the last-row live-announcement requirement. FirstMinutes.app-lockdown.test.ts#S4-1 first launch has one message/action and no field, checklist, sidebar or status covers initial absence only. The exact toast wording and focus fallback are implementation accommodations, not verbatim owner requirements; this provenance limit remains explicit."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.sidebar.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R10",
          "scenarioName": "rule 10",
          "quote": "No conversations column until there is a conversation. A plain search never fills it.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "decision": "D6",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Left column only when a conversation exists (canonical conversation rows or locally submitted conversational turns, excluding synthetic unsent-draft rows; a deep-linked conversation counts while resolving). Agent runs goes with the column.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none found in docs/decisions for getting started, first launch, recent, search-as-you-type, typeahead, debounce, corpus, Draft kept, Ctrl+Enter, Search / Ask, Structured and Delegate. Matches concern engine query/metadata contracts (ADR-0016/0020), inference/model and context policy (ADR-0019/0047), scoped paths (ADR-0028), primitives (ADR-0031), streams and resource categories (ADR-0036/0037), rather than the superseded UI expectations. Private task records were not read in this builder session; the acceptor must confirm the supplied adoption and delegation turns and scope. This search does not supply missing intent for a coverage reduction flagged in checkLimits.",
      "assertionChanges": [
        {
          "oldAssertion": "Sidebar-dependent tests mount an otherwise empty window and assume its column/New control exists.",
          "newAssertion": "Fixture-only change: provide an existing conversation before exercising the column. The shared mount seeds a canonical geometry-conversation by default and adds an existing parameter; the rename case calls mount(1568, false) so its fetched rows are still the fixture. Drag/resize/focus/storage and rename assertions remain.",
          "reason": "R10/D6 reserve the column for actual canonical or locally submitted conversations, so these downstream interaction checks now supply that prerequisite. Their expected downstream results have not changed."
        }
      ],
      "checkLimits": "Precondition change, not proof of the former empty-window behavior: these cases now cover existing-conversation windows. No assertion is deleted. modules/ui-web/src/shell-v0/chrome/FirstMinutes.app-lockdown.test.ts#S4-1 first launch has one message/action and no field, checklist, sidebar or status checks the opposite empty-column case. That launch test does not replace these geometry/storage/focus checks or prove deep-linked and synthetic-draft edge cases."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.sidebarSheet.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R10",
          "scenarioName": "rule 10",
          "quote": "No conversations column until there is a conversation. A plain search never fills it.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "decision": "D6",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Left column only when a conversation exists (canonical conversation rows or locally submitted conversational turns, excluding synthetic unsent-draft rows; a deep-linked conversation counts while resolving). Agent runs goes with the column.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none found in docs/decisions for getting started, first launch, recent, search-as-you-type, typeahead, debounce, corpus, Draft kept, Ctrl+Enter, Search / Ask, Structured and Delegate. Matches concern engine query/metadata contracts (ADR-0016/0020), inference/model and context policy (ADR-0019/0047), scoped paths (ADR-0028), primitives (ADR-0031), streams and resource categories (ADR-0036/0037), rather than the superseded UI expectations. Private task records were not read in this builder session; the acceptor must confirm the supplied adoption and delegation turns and scope. This search does not supply missing intent for a coverage reduction flagged in checkLimits.",
      "assertionChanges": [
        {
          "oldAssertion": "Sidebar-dependent tests mount an otherwise empty window and assume its column/New control exists.",
          "newAssertion": "Fixture-only change: provide an existing conversation before exercising the column. The shared mount seeds one canonical geometry-conversation before the docked narrow-window fixture. The sheet, resize, Escape, focus and stored-width checks remain.",
          "reason": "R10/D6 reserve the column for actual canonical or locally submitted conversations, so these downstream interaction checks now supply that prerequisite. Their expected downstream results have not changed."
        }
      ],
      "checkLimits": "Precondition change, not proof of the former empty-window behavior: these cases now cover existing-conversation windows. No assertion is deleted. modules/ui-web/src/shell-v0/chrome/FirstMinutes.app-lockdown.test.ts#S4-1 first launch has one message/action and no field, checklist, sidebar or status checks the opposite empty-column case. That launch test does not replace these geometry/storage/focus checks or prove deep-linked and synthetic-draft edge cases."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.startFresh.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R10",
          "scenarioName": "rule 10",
          "quote": "No conversations column until there is a conversation. A plain search never fills it.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "decision": "D6",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Left column only when a conversation exists (canonical conversation rows or locally submitted conversational turns, excluding synthetic unsent-draft rows; a deep-linked conversation counts while resolving). Agent runs goes with the column.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none found in docs/decisions for getting started, first launch, recent, search-as-you-type, typeahead, debounce, corpus, Draft kept, Ctrl+Enter, Search / Ask, Structured and Delegate. Matches concern engine query/metadata contracts (ADR-0016/0020), inference/model and context policy (ADR-0019/0047), scoped paths (ADR-0028), primitives (ADR-0031), streams and resource categories (ADR-0036/0037), rather than the superseded UI expectations. Private task records were not read in this builder session; the acceptor must confirm the supplied adoption and delegation turns and scope. This search does not supply missing intent for a coverage reduction flagged in checkLimits.",
      "assertionChanges": [
        {
          "oldAssertion": "Sidebar-dependent tests mount an otherwise empty window and assume its column/New control exists.",
          "newAssertion": "Fixture-only change: provide an existing conversation before exercising the column. Only the New-retains-authored-results-work parameterized case seeds an existing canonical conversation before entering Search. The retained query/chosen-files/schema and reviewable-results-proposal checks remain.",
          "reason": "R10/D6 reserve the column for actual canonical or locally submitted conversations, so these downstream interaction checks now supply that prerequisite. Their expected downstream results have not changed."
        }
      ],
      "checkLimits": "Precondition change, not proof of the former empty-window behavior: these cases now cover existing-conversation windows. No assertion is deleted. modules/ui-web/src/shell-v0/chrome/FirstMinutes.app-lockdown.test.ts#S4-1 first launch has one message/action and no field, checklist, sidebar or status checks the opposite empty-column case. That launch test does not replace these geometry/storage/focus checks or prove deep-linked and synthetic-draft edge cases."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.structured.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R7",
          "scenarioName": "rule 7",
          "quote": "The search box is a search field: magnifier, one line, Enter. No send arrow, no effort control. Search / Ask appears in it only once answers work on this computer.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "decision": "D3",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "\"Answers work on this computer\" = AI installed (known true) AND an NVIDIA/CUDA GPU available (known true). Then the field shows Search / Ask; otherwise no mode control. Ask's own availability gate stays for running. Existing conversations and Ask deep links stay reachable regardless.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "D4",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Structured and Delegate are offered in the switch only when developer tools are on (HANDOVER: not on a picture -> behind developer tools).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "C1",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "(D3/D4) Offering a new mode differs from showing an existing task: when a restored or opened task is Ask/Structured/Delegate while that mode would not be offered, the field shows the switch with that mode and Search, so the user can return to Search; text, schema and scope are kept; no silent mode change.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "C3",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "(D3) \"Answers work\" = installed known true AND CUDA available known true AND the existing Ask availability (F/state/availability.ts) says available or loadable-on-demand, from a live snapshot. Repair-needed, chat switched off, stale or unknown: no switch (C1 still applies for existing tasks).",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none found in docs/decisions for getting started, first launch, recent, search-as-you-type, typeahead, debounce, corpus, Draft kept, Ctrl+Enter, Search / Ask, Structured and Delegate. Matches concern engine query/metadata contracts (ADR-0016/0020), inference/model and context policy (ADR-0019/0047), scoped paths (ADR-0028), primitives (ADR-0031), streams and resource categories (ADR-0036/0037), rather than the superseded UI expectations. Private task records were not read in this builder session; the acceptor must confirm the supplied adoption and delegation turns and scope. This search does not supply missing intent for a coverage reduction flagged in checkLimits.",
      "assertionChanges": [
        {
          "oldAssertion": "Structured-mode suites rely on implicitly offered modes from an online/available snapshot without explicit install, CUDA or developer state.",
          "newAssertion": "Fixture-only changes import install/developer setters, explicitly set install and developer flags after AI reset, feed cudaAvailable:true in online fixtures, and set those flags in teardown too. Expected outcomes are unchanged.",
          "reason": "R7/D3/C3 gate new mode offers on known installed/live CUDA readiness; D4 gates Structured/Delegate on developer tools. The new fixture supplies those prerequisites; it does not derive expected output from the candidate."
        }
      ],
      "checkLimits": "The chat-unavailable case also restores install/developer flags after reset, without feeding live CUDA/chat availability; its schema/instruction retention and zero-dispatch assertions remain. The fixture still exercises unavailable chat, not a new successful Ask offer. Preconditions changed, so this is not claimed as an Adaptation retaining every old input. The fixture no longer covers offering those modes without known install/CUDA/developer state. Teardown sets flags true rather than restoring their previous value; cross-file state isolation was not run here. modules/ui-web/src/shell-v0/views/search-v3/FirstMinutes.availability.test.ts#S4-14 developer tools reveal Structured/Delegate only with answers available checks positive mode visibility; its uninstalled, unknown-install, no-cuda, unknown-gpu, stale, off, failed and loading cases check that no new Ask is offered. Those checks do not prove this suite leaves no state behind."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.structuredOutcome.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R7",
          "scenarioName": "rule 7",
          "quote": "The search box is a search field: magnifier, one line, Enter. No send arrow, no effort control. Search / Ask appears in it only once answers work on this computer.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "decision": "D3",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "\"Answers work on this computer\" = AI installed (known true) AND an NVIDIA/CUDA GPU available (known true). Then the field shows Search / Ask; otherwise no mode control. Ask's own availability gate stays for running. Existing conversations and Ask deep links stay reachable regardless.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "D4",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Structured and Delegate are offered in the switch only when developer tools are on (HANDOVER: not on a picture -> behind developer tools).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "C1",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "(D3/D4) Offering a new mode differs from showing an existing task: when a restored or opened task is Ask/Structured/Delegate while that mode would not be offered, the field shows the switch with that mode and Search, so the user can return to Search; text, schema and scope are kept; no silent mode change.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "C3",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "(D3) \"Answers work\" = installed known true AND CUDA available known true AND the existing Ask availability (F/state/availability.ts) says available or loadable-on-demand, from a live snapshot. Repair-needed, chat switched off, stale or unknown: no switch (C1 still applies for existing tasks).",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none found in docs/decisions for getting started, first launch, recent, search-as-you-type, typeahead, debounce, corpus, Draft kept, Ctrl+Enter, Search / Ask, Structured and Delegate. Matches concern engine query/metadata contracts (ADR-0016/0020), inference/model and context policy (ADR-0019/0047), scoped paths (ADR-0028), primitives (ADR-0031), streams and resource categories (ADR-0036/0037), rather than the superseded UI expectations. Private task records were not read in this builder session; the acceptor must confirm the supplied adoption and delegation turns and scope. This search does not supply missing intent for a coverage reduction flagged in checkLimits.",
      "assertionChanges": [
        {
          "oldAssertion": "Structured-mode suites rely on implicitly offered modes from an online/available snapshot without explicit install, CUDA or developer state.",
          "newAssertion": "Fixture-only changes import install/developer setters, explicitly set install and developer flags after AI reset, feed cudaAvailable:true in online fixtures, and set those flags in teardown too. Expected outcomes are unchanged.",
          "reason": "R7/D3/C3 gate new mode offers on known installed/live CUDA readiness; D4 gates Structured/Delegate on developer tools. The new fixture supplies those prerequisites; it does not derive expected output from the candidate."
        }
      ],
      "checkLimits": "Structured partial/terminal outcome and extraction-scope assertions are unchanged. Preconditions changed, so this is not claimed as an Adaptation retaining every old input. The fixture no longer covers offering those modes without known install/CUDA/developer state. Teardown sets flags true rather than restoring their previous value; cross-file state isolation was not run here. modules/ui-web/src/shell-v0/views/search-v3/FirstMinutes.availability.test.ts#S4-14 developer tools reveal Structured/Delegate only with answers available checks positive mode visibility; its uninstalled, unknown-install, no-cuda, unknown-gpu, stale, off, failed and loading cases check that no new Ask is offered. Those checks do not prove this suite leaves no state behind."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.syntax.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "H4",
          "scenarioName": "handover slice 4",
          "quote": "4. **First launch and the search field** (fm-01, 02, 03, 16; critique A3, A4, B1, B3, B4, B7). One message and one action on first launch; the Getting started card and checklist go. The field: magnifier, one line, Enter; no send arrow, no effort control; Search / Ask in it only once answers work on this computer (fm-16). Recent searches are a plain list under the field with Clear, never opening by themselves over other text. No conversations column until a conversation exists. Checked on main: typing only updates the draft (`Sv3Composer.ts`, `onInput`), a search starts on Enter. That matches the pictures.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "decision": "D5",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Search mode: magnifier, one line (pasted newlines become spaces), Enter searches, no send arrow, no effort control, no placeholder prompt; the headline labels the field (aria-labelledby); when the headline is not shown (results docked) the field's name is \"Search\". Ask mode: today's Ask composer (multi-line, effort, send, stop, scope).",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none found in docs/decisions for getting started, first launch, recent, search-as-you-type, typeahead, debounce, corpus, Draft kept, Ctrl+Enter, Search / Ask, Structured and Delegate. Matches concern engine query/metadata contracts (ADR-0016/0020), inference/model and context policy (ADR-0019/0047), scoped paths (ADR-0028), primitives (ADR-0031), streams and resource categories (ADR-0036/0037), rather than the superseded UI expectations. Private task records were not read in this builder session; the acceptor must confirm the supplied adoption and delegation turns and scope. This search does not supply missing intent for a coverage reduction flagged in checkLimits.",
      "assertionChanges": [
        {
          "oldAssertion": "Automatic invalid search stays quiet and resumes with three quick/refined requests; edits supersede old successes/errors with four requests.",
          "newAssertion": "Explicit invalid submit shows the Engine reason and querySyntax:search; correction clears it and totals two requests. New explicit submits supersede old success/error bodies and total three requests.",
          "reason": "H4/D5 stop background search on edits. Each intentional submit runs the existing refined explicit path and must visibly report its own invalid syntax; supersession is now tied to another submit."
        },
        {
          "oldAssertion": "Typing an advanced query immediately sets isSearching and removes old cards/counts; quick advanced results keep facets/counts pending until refinement.",
          "newAssertion": "Hold an explicit request to inspect pending counts/absence of old cards; accept isSearching or isRefining, then assert new results. An explicit advanced result is refined immediately, has matchCount:1, no counts-pending text, and still only one request after a delay.",
          "reason": "The source requires Enter; the existing explicit/refined request path determines the stage and count oracle. A held-response fixture is necessary to observe the unchanged pending-data obligation rather than sample a completed request."
        }
      ],
      "checkLimits": "Weakened/deleted details: isSearching alone becomes a disjunction, so this test no longer pins that specific phase flag. The pipeline.sparseEnabled:true assertion is replaced by querySyntax:search, which does not check that pipeline property. The quick-stage facets:null assertion is deleted with the obsolete quick stage; no equivalent facet expectation after an explicit advanced completion is added. The initial one-request assertion in the stale-response test is removed, though final three-request total remains. No new exempt file replaces these exact pipeline/phase/facet checks. Existing other explicit-invalid and URL-restoration assertions remain; source intent supports Enter, not arbitrary engine-pipeline changes."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.tail.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R7",
          "scenarioName": "rule 7",
          "quote": "The search box is a search field: magnifier, one line, Enter. No send arrow, no effort control. Search / Ask appears in it only once answers work on this computer.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "decision": "D5",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Search mode: magnifier, one line (pasted newlines become spaces), Enter searches, no send arrow, no effort control, no placeholder prompt; the headline labels the field (aria-labelledby); when the headline is not shown (results docked) the field's name is \"Search\". Ask mode: today's Ask composer (multi-line, effort, send, stop, scope).",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none found in docs/decisions for getting started, first launch, recent, search-as-you-type, typeahead, debounce, corpus, Draft kept, Ctrl+Enter, Search / Ask, Structured and Delegate. Matches concern engine query/metadata contracts (ADR-0016/0020), inference/model and context policy (ADR-0019/0047), scoped paths (ADR-0028), primitives (ADR-0031), streams and resource categories (ADR-0036/0037), rather than the superseded UI expectations. Private task records were not read in this builder session; the acceptor must confirm the supplied adoption and delegation turns and scope. This search does not supply missing intent for a coverage reduction flagged in checkLimits.",
      "assertionChanges": [
        {
          "oldAssertion": "Model identity checks mount the default Search composer and assume its model label/control row exists.",
          "newAssertion": "Fixture-only: mount the same isolated composer in Ask mode, keeping all model identity/name/state assertions.",
          "reason": "R7/D5 remove Search effort/send anatomy and retain today's Ask composer. Model identity is still checked where the conversational composer offers it."
        }
      ],
      "checkLimits": "No assertion is deleted. Default Search model-label coverage is intentionally displaced by the Search anatomy contract; this isolated Ask fixture does not prove switching or a full-window model-label interaction."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "H4",
          "scenarioName": "handover slice 4",
          "quote": "4. **First launch and the search field** (fm-01, 02, 03, 16; critique A3, A4, B1, B3, B4, B7). One message and one action on first launch; the Getting started card and checklist go. The field: magnifier, one line, Enter; no send arrow, no effort control; Search / Ask in it only once answers work on this computer (fm-16). Recent searches are a plain list under the field with Clear, never opening by themselves over other text. No conversations column until a conversation exists. Checked on main: typing only updates the draft (`Sv3Composer.ts`, `onInput`), a search starts on Enter. That matches the pictures.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R7",
          "scenarioName": "rule 7",
          "quote": "The search box is a search field: magnifier, one line, Enter. No send arrow, no effort control. Search / Ask appears in it only once answers work on this computer.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R10",
          "scenarioName": "rule 10",
          "quote": "No conversations column until there is a conversation. A plain search never fills it.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "decision": "D2",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Recents: searches only (conversations leave the strip; they live in the column). Each entry keeps query, result count and time, recorded when the response arrives; failed/invalid searches show the query without a count. Memory only, as today (no new saved state). Shown only when non-empty, as a plain list in the page flow under the field; never an overlay; Clear empties it.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "D5",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Search mode: magnifier, one line (pasted newlines become spaces), Enter searches, no send arrow, no effort control, no placeholder prompt; the headline labels the field (aria-labelledby); when the headline is not shown (results docked) the field's name is \"Search\". Ask mode: today's Ask composer (multi-line, effort, send, stop, scope).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "D6",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Left column only when a conversation exists (canonical conversation rows or locally submitted conversational turns, excluding synthetic unsent-draft rows; a deep-linked conversation counts while resolving). Agent runs goes with the column.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "C2",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "(D5) Stored drafts are never normalised. Search mode stops new newlines (Enter searches; pasted newlines become spaces at paste time); a restored multi-line draft shows as it is until edited.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none found in docs/decisions for getting started, first launch, recent, search-as-you-type, typeahead, debounce, corpus, Draft kept, Ctrl+Enter, Search / Ask, Structured and Delegate. Matches concern engine query/metadata contracts (ADR-0016/0020), inference/model and context policy (ADR-0019/0047), scoped paths (ADR-0028), primitives (ADR-0031), streams and resource categories (ADR-0036/0037), rather than the superseded UI expectations. Private task records were not read in this builder session; the acceptor must confirm the supplied adoption and delegation turns and scope. This search does not supply missing intent for a coverage reduction flagged in checkLimits.",
      "assertionChanges": [
        {
          "oldAssertion": "A fresh window includes an empty sidebar with New, plus Search send/effort in hero and docked states.",
          "newAssertion": "Fresh windows have no sidebar/New/empty-sidebar chrome and Search has no send/effort. Ask is explicitly selected for retained send-disabled and effort-glyph cases; Search dock uses Enter.",
          "reason": "R10/D6 remove the empty column, while R7/D5 retain those controls only for Ask."
        },
        {
          "oldAssertion": "Recents have one bounded scrollable listbox with min-block-size:0, overflow-y:auto, overscroll containment and a constrained band; the global overflow ban permits only that rule.",
          "newAssertion": "Require the listbox rule absent, require hero host overflow-y:auto, and exempt that host plus .search-field textarea rules from the overflow ban. The mixin count loop excludes the absent sidebar and keeps its exact count across main/composer.",
          "reason": "H4/D2 replace an overlay listbox with a list in the page flow; the hero page must scroll. The loop's empty-window sidebar exclusion follows R10, not a claim about sidebar scrollers in populated windows."
        }
      ],
      "checkLimits": "Coverage reductions: listbox min-size/overscroll/band bounds are deleted; the new hero-rule presence check does not assert recents actually remain in flow or cannot overlap other content. The textarea regex is a new overflow allowance with no positive assertion of its exact contents or restored-multiline geometry. Excluding sidebar from the rendered mixin count no longer enforces one scroller across all three populated regions. FirstMinutes.test.ts#S4-11 recents are an in-flow list of searches with count/time, keyboard reachable, never empty chrome checks count/time/button tab order but contains no geometry/position assertion; its title is not proof of in-flow layout. #S4-13 Search has a magnifier and headline label, with no prompt, send or effort checks the replacement anatomy; FirstMinutes.app-lockdown.test.ts#S4-1 first launch has one message/action and no field, checklist, sidebar or status checks absent column. No exact replacement for the removed overflow/containment predicates is demonstrated."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.tier.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R7",
          "scenarioName": "rule 7",
          "quote": "The search box is a search field: magnifier, one line, Enter. No send arrow, no effort control. Search / Ask appears in it only once answers work on this computer.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "decision": "D3",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "\"Answers work on this computer\" = AI installed (known true) AND an NVIDIA/CUDA GPU available (known true). Then the field shows Search / Ask; otherwise no mode control. Ask's own availability gate stays for running. Existing conversations and Ask deep links stay reachable regardless.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "D4",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Structured and Delegate are offered in the switch only when developer tools are on (HANDOVER: not on a picture -> behind developer tools).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "D5",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Search mode: magnifier, one line (pasted newlines become spaces), Enter searches, no send arrow, no effort control, no placeholder prompt; the headline labels the field (aria-labelledby); when the headline is not shown (results docked) the field's name is \"Search\". Ask mode: today's Ask composer (multi-line, effort, send, stop, scope).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "C1",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "(D3/D4) Offering a new mode differs from showing an existing task: when a restored or opened task is Ask/Structured/Delegate while that mode would not be offered, the field shows the switch with that mode and Search, so the user can return to Search; text, schema and scope are kept; no silent mode change.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "C3",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "(D3) \"Answers work\" = installed known true AND CUDA available known true AND the existing Ask availability (F/state/availability.ts) says available or loadable-on-demand, from a live snapshot. Repair-needed, chat switched off, stale or unknown: no switch (C1 still applies for existing tasks).",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none found in docs/decisions for getting started, first launch, recent, search-as-you-type, typeahead, debounce, corpus, Draft kept, Ctrl+Enter, Search / Ask, Structured and Delegate. Matches concern engine query/metadata contracts (ADR-0016/0020), inference/model and context policy (ADR-0019/0047), scoped paths (ADR-0028), primitives (ADR-0031), streams and resource categories (ADR-0036/0037), rather than the superseded UI expectations. Private task records were not read in this builder session; the acceptor must confirm the supplied adoption and delegation turns and scope. This search does not supply missing intent for a coverage reduction flagged in checkLimits.",
      "assertionChanges": [
        {
          "oldAssertion": "Search control row begins with tier then effort; Search send has a Search hint; Ctrl+Enter delegates and calls the controller once.",
          "newAssertion": "The row contains tier and lacks effort; Search send is absent; Ctrl+Enter emits no submit and never calls Delegate/Ask. Existing Ask send-hint assertions remain.",
          "reason": "R7/D5 remove Search effort/send and require intentional Search Enter, rather than a hidden Delegate chord."
        },
        {
          "oldAssertion": "Ready AI implicitly offers every mode; unavailable Ask can be chosen from Search; effort-menu exclusivity is tested in default Search.",
          "newAssertion": "Fixture-only install/CUDA/developer state is explicit; refusal uses existing Ask and menu-exclusivity first chooses Ask. pressSend now uses Enter on the current draft.",
          "reason": "D3/D4/C3 govern new-mode offers, C1 keeps existing unavailable tasks recoverable, and D5 retains effort/send in Ask."
        }
      ],
      "checkLimits": "Weaker row observation: membership does not preserve the original first-two position/order assertion. The removed Search send hint has no replacement because the control is removed. Existing-unavailable Ask is assigned directly, so that case no longer verifies user selection from fresh Search. Enter-only helper no longer exercises pointer sends at its call sites; teardown leaves install/developer flags true, with isolation unverified. FirstMinutes.test.ts#S4-13 newlines are stopped in Search, modifier chords never silently Delegate; restored drafts survive supplies modifier-submit and preserved-draft checks; #S4-14 restored ask always offers Search without discarding draft/schema checks the existing-task escape. FirstMinutes.availability.test.ts#S4-14 live installed CUDA availability offers Search/Ask without losing text when readiness changes and its negative cases check offer gating, not row order."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/Sv3Empty.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R10",
          "scenarioName": "rule 10",
          "quote": "No conversations column until there is a conversation. A plain search never fills it.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "decision": "D6",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Left column only when a conversation exists (canonical conversation rows or locally submitted conversational turns, excluding synthetic unsent-draft rows; a deep-linked conversation counts while resolving). Agent runs goes with the column.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none found in docs/decisions for getting started, first launch, recent, search-as-you-type, typeahead, debounce, corpus, Draft kept, Ctrl+Enter, Search / Ask, Structured and Delegate. Matches concern engine query/metadata contracts (ADR-0016/0020), inference/model and context policy (ADR-0019/0047), scoped paths (ADR-0028), primitives (ADR-0031), streams and resource categories (ADR-0036/0037), rather than the superseded UI expectations. Private task records were not read in this builder session; the acceptor must confirm the supplied adoption and delegation turns and scope. This search does not supply missing intent for a coverage reduction flagged in checkLimits.",
      "assertionChanges": [
        {
          "oldAssertion": "The sidebar zero-state, fanned tiles and no-heading checks obtain the sidebar through a freshly mounted empty Search window.",
          "newAssertion": "Fixture-only: instantiate jf-sv3-sidebar directly for those three checks; retain exact empty-state content, tile/assistive-tech and heading assertions.",
          "reason": "R10/D6 prohibit an empty Search window from mounting the sidebar. Isolated component testing can preserve its internal oracle without claiming that composition is still offered at first launch."
        }
      ],
      "checkLimits": "Observation boundary is narrower: these three tests no longer prove integration of the zero-state into Search or the real shell heading outline. The component predicates are unchanged. FirstMinutes.app-lockdown.test.ts#S4-1 first launch has one message/action and no field, checklist, sidebar or status covers the replacement absent-sidebar composition, not populated-window component composition or whole-shell heading accessibility. Historical test names/comments still refer to the old full-window path."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-focus.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R7",
          "scenarioName": "rule 7",
          "quote": "The search box is a search field: magnifier, one line, Enter. No send arrow, no effort control. Search / Ask appears in it only once answers work on this computer.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "decision": "D4",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Structured and Delegate are offered in the switch only when developer tools are on (HANDOVER: not on a picture -> behind developer tools).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "decision": "D5",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Search mode: magnifier, one line (pasted newlines become spaces), Enter searches, no send arrow, no effort control, no placeholder prompt; the headline labels the field (aria-labelledby); when the headline is not shown (results docked) the field's name is \"Search\". Ask mode: today's Ask composer (multi-line, effort, send, stop, scope).",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none found in docs/decisions for getting started, first launch, recent, search-as-you-type, typeahead, debounce, corpus, Draft kept, Ctrl+Enter, Search / Ask, Structured and Delegate. Matches concern engine query/metadata contracts (ADR-0016/0020), inference/model and context policy (ADR-0019/0047), scoped paths (ADR-0028), primitives (ADR-0031), streams and resource categories (ADR-0036/0037), rather than the superseded UI expectations. Private task records were not read in this builder session; the acceptor must confirm the supplied adoption and delegation turns and scope. This search does not supply missing intent for a coverage reduction flagged in checkLimits.",
      "assertionChanges": [
        {
          "oldAssertion": "Focus tests assume developer modes available and a model label on default Search.",
          "newAssertion": "Fixture-only: enable developer tools in setup and set Ask only for the model-label press/text-selectability test. Its focus and event assertions remain.",
          "reason": "D4 makes developer offers explicit; R7/D5 retain the model-label conversational anatomy in Ask rather than Search."
        }
      ],
      "checkLimits": "No assertion is deleted. This no longer tests a model label on fresh Search. Setting Ask directly bypasses mode-switch activation, and developer state is not restored in the shown diff; isolation was not checked here."
    }
  ]
}
```

## Acceptance records
