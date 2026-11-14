---
schema: test-intent.v1
task: t-20261113-fmimpl
author-role: builder
author-session: codex-builder-fm-first-launch-20261114
---

# Test-intent entries

The original inventory accounts for the forty modified product-test files in candidate
0d5104a84e073d4173293a59f0a03097180d336c against origin/main
d61d78ce4e35706bee0b2c44d303e84245c58285. The original documentation pass wrote only this changeset; the restoration pass below also changes tests. Every file's actual `git diff origin/main -- <file>` was read; the supplied
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
partial replacements; titles alone are not evidence. They were initially read
without execution; the builder runs below now exercise their relevant checks. Independent acceptance is not claimed.

Coverage limits are findings for acceptance, not passes. In particular, broadening
the offline-copy scanner exclusion to every test file has no supplied warrant;
the restoration follow-through below resolves retained preparations, lock/423,
exact dedup, accessible names, keyboard replay, scrolling and required controls.
Removed UI obligations remain explicitly retired under their cited decisions.
`checkLimits` records each final resolution and any narrower observation boundary. No class/source in this document purports to approve
an unsupported weakening or prove exact new fallback copy came from owner words.
The acceptor must resolve those limits against the real scenarios, rather than
accept the candidate's output as its own intent.

Governance documents, production files, and scripts/jsharness changes are outside
these forty items; this changeset does not modify them or substitute a document
changed in this PR for a source predating the candidate. Repository decision
records were searched as described in each Obsolete entry; task records remain
for the independent acceptor to inspect.

Restoration follow-through (2026-11-14): the Y6 product defect is repaired.
The slice moved conversations out of the canonical-list-backed Recent strip into
an additive session cache in the column, while removing the strip's freshness
flag. A render-only membership filter now uses canonical rows plus locally
submitted turns without a record, keeps cached transcripts/drafts intact, and
restores the unlock-refresh freshness guard. Locked columns still expose no titles.
The original Y6 checks pass at d61d78ce4e in a shared clone with dependencies
resolved from this worktree (no node_modules links). The restored column tests
pass on the repair, including an unlock whose refresh is held, an empty refresh,
423, profile ABA, and preservation/retirement of pending local turns. The running
Shell's Chromium lock/unlock/empty-refresh check also passes.

The coverage audit is complete in assertionChanges/checkLimits below. Native
Enter/Space, named recents, the wide/narrow dark/light/forced-color flow and scroll
matrix, preserved multiline bounds, native paste, final-deletion polite rendering,
Ask/Structured pointer sends and fixture cleanup are checked. Filters typography
captures are restored in dark/light through the existing docked Filters entry:
158 surfaces, zero violations. Six removed Agent runs/Mode/Effort identities
remain retired under D6, D3/C3 and D5. The two surviving Filters identities are
asserted by name as well as by the total.

Builder evidence so far: 807 unit files / 10,045 tests passed; the final touched
unit run passed 27 files / 638 tests; 37 isolated source mutations each failed a
relevant restored assertion and were restored byte-for-byte, followed by 243
passing tests in the restored clone. Mutation IDs below identify these experiments;
no acceptance, independent verification or committed-candidate gate is claimed.
Six additional Chromium mutations fail the restored native Enter/Space, named
region, in-flow headline bounds, native wheel scrolling, multiline bounds and
native paste oracles; all mutated bytes are restored and the 14 browser tests
pass afterward. Accessibility passes all 102 registered surfaces with no new or
severe violations; lint, TypeScript and the dead-code gate (8 informational
findings) pass. The first lockdown run passed 665/666 tests across 51/52 files;
HealthSurface.outage-lockdown.test.ts then passed all 5 tests alone. The initial
accessibility sweep had one Filters click capture timeout; its fresh full sweep
passes. Dark and light Filters identity mutations each keep the 158-surface total but
remove one required identity; both fail the named-inventory assertion. The script
is restored byte-for-byte and its lockdown wrapper passes afterward. In total,
45 source mutations fail relevant assertions and have byte-for-byte restoration
evidence. The final stable-source lockdown rerun passes all 52 files / 667 tests. The source tree and all test changes remain uncommitted.
Acceptance records are untouched.

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
          "newAssertion": "Exactly 158 surfaces and zero violations, with named passing dark/search-results-filters and light/search-results-filters captures containing rendered text.",
          "reason": "Baseline d61d78ce4e was rerun in an isolated shared clone: 164 captured surfaces, zero violations. Its eight named states are dark/search-idle-expanded-1 (Agent runs), dark/search-idle-expanded-2 (Mode), dark/search-idle-expanded-3 (Effort), dark/search-idle-expanded-4 (Filters), light/search-idle-expanded-1 (Agent runs), light/search-idle-expanded-2 (Mode), light/search-idle-expanded-3 (Effort), light/search-idle-expanded-4 (Filters). D6 removes Agent runs with the empty column; D3/C3 remove the new-mode offer under this uninstalled fixture; D5 removes Search effort. Filters remains product UI and no quoted decision removes its typography obligation. The surviving Filters states are now reached through the existing results/docked control; no mode offer is forced into the unavailable first-launch fixture."
        }
      ],
      "checkLimits": "Resolved: baseline 164/zero violations and candidate 158/zero violations were executed in owned headless Chromium. Six removed Agent runs/Mode/Effort states map to D6, D3/C3 and D5; two Filters states survive under explicit dark/search-results-filters and light/search-results-filters identities, each required to pass and contain text. This is browser typography/CSP coverage, not Engine behavior. Mutations filters-dark and filters-light preserve the surface count but replace the respective Filters identity; each fails the named-inventory assertion. The script is restored byte-for-byte; the full Chromium typography wrapper then passes."
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
      "checkLimits": "R6/D7 retire the checklist and its pill/reopen UI; R6 retires the first-launch AI action. Deletions: checklist header/current step/Done/Under way, empty-folder Open Library, waiting-count text, visible Hide and Help reopen, and first-launch AI Brain reachability have no same-UI replacements because that UI is removed. State sharing is still checked through APIs, a narrower interaction path. modules/ui-web/src/shell-v0/chrome/FirstMinutes.app-lockdown.test.ts#S4-1 first launch has one message/action and no field, checklist, sidebar or status and #S4-8 browser path entry has no Collection; acceptance focuses Search and reading count expires assert replacement launch/focus/count-lifetime behavior. Its S4-19 viewport/theme cases check Add reachability, not the retired AI action. Those replacement browser tests pass in the full lockdown run. Existing cancellation, errors/retry, duplicate add, navigation/history and reload assertions remain."
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
      "checkLimits": "Numbered progress/current-step and hide-to-reveal-corpus assertions are removed, not preserved. modules/ui-web/src/shell-v0/chrome/FirstMinutes.app-lockdown.test.ts#S4-8 browser path entry has no Collection; acceptance focuses Search and reading count expires checks focused Search and count expiry; #S4-1 first launch has one message/action and no field, checklist, sidebar or status checks replacement first launch with intercepted responses, not the Engine-backed obligation. Despite its historical Engine-path filename, this modified file intercepts fetch and is not an independent live Engine check; it passes in the lockdown run. Dropping recordFirstUseSearch from the AppWindow type is unused harness signature cleanup."
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
          "newAssertion": "Typing and summon keep the shared query empty before and after 200ms, preserve/focus the draft field, and require its resolved accessible name to equal What are you looking for?.",
          "reason": "H4 explicitly says typing changes only the draft and Enter starts search. D5 names the hero field through the headline. Those sources supersede the quick-search timer expectation."
        }
      ],
      "checkLimits": "Restored and passing: resolve every aria-labelledby reference in the input shadow root (or aria-label) and require exact accessible text What are you looking for?. Mutation name-reference breaks the reference and fails this oracle. Draft/focus/no-submit checks remain. H4/D5 retire automatic quick-search timer scheduling/cancellation; this component test does not establish a native accessibility tree."
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
      "checkLimits": "Resolved browser boundary: GettingStarted.reading-lockdown.test.ts executes the actual composer in owned Chromium for both omitted (null) and zero pendingJobs while indexState is INDEXING and requires exact rendered 3 files searchable so far. R2/D1/D10/C9 retire the waiting/still-reading suffix; the count and live-reading obligation are retained. State permutations remain in FirstMinutes.reading.test.ts."
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
          "newAssertion": "Mount Help instead; retain saved step Two, plugin title, no Try a search and saved ids/progress. After unregistering, the entire plugin shadow text is empty and its card absent; the enclosing Help surface retains its other responsibilities.",
          "reason": "D7/C8 move resumable plugin walkthroughs to Help. Expecting the whole Help surface to become empty would contradict its other responsibilities; the new assertion addresses the removed plugin only."
        },
        {
          "oldAssertion": "Suggestions use jf-button; the watched Documents path must not occur anywhere in the host text.",
          "newAssertion": "Select native suggestion buttons, still require two deduplicated suggestions, reseed roots after AI reset, and require both Add Documents absence and no literal /Documents anywhere in the host text.",
          "reason": "D8 uses immediate folder-add actions and excludes already watched suggestions. Native-button selection and reseeding are fixture/selector changes; the new absence-of-action check retains the exclusion purpose but is narrower than forbidding every path mention."
        },
        {
          "oldAssertion": "A later successful own-file search completes try-search; the mounted/reloaded card displays counts, waiting and Try a search.",
          "newAssertion": "Neither search writes try-search; no Try a search is rendered and the legacy host is empty with existing roots, including after reload.",
          "reason": "R6/D7 remove the checklist and C9 stops new progress writes. The Search composer, rather than this host, owns the transient count."
        }
      ],
      "checkLimits": "Restored and passing: after unregister, the walkthrough has no .card and its entire shadow text is empty; the surrounding Help host may remain populated under D7/C8. The watched /Documents path is forbidden throughout the first-launch host, alongside absence of its Add action. Mutations walkthrough-empty-text and watched-path-text fail these restored oracles. R6/D7 retire checklist Try a search, card counts and card reload UI; C9 retires progress writes. Launch and Help integration remain separately exercised."
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
          "newAssertion": "Fixture-only: select the existing Ask task, settle, require and click its mode switch, then retain Ask-enabled and Resume indexing assertions.",
          "reason": "C1 keeps existing Ask reachable even when new Ask would not be offered under D3/C3. D5 preserves the Ask composer. This no longer promises that a fresh Search always offers a switch."
        }
      ],
      "checkLimits": "Restored and passing: existing Ask while indexing is paused must expose a non-null mode trigger before activation; Ask stays enabled and Resume stays visible. Mutation ask-mode-required removes that existing-task control and fails the required-element assertion. C1 preserves existing Ask; D3/C3 retire unconditional fresh-Search offers."
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
          "newAssertion": "Calm default/steps 2 and 3 have the exact existing Up to date label; the unknown-verdict variant has the exact Checking search status · last seen just now · checking label. Unknown kind and urgent precedence remain.",
          "reason": "R6/D7 remove setup progress from the bar, independent of the legacy input still supplied to derivation."
        }
      ],
      "checkLimits": "Restored and passing: exact Up to date labels for calm step 2, step 3, default and hidden variants, plus exact Checking search status · last seen just now · checking for an unknown verdict. Mutations calm-step2, calm-step3, calm-default, calm-hidden and checking-exact-label independently change those outputs and fail. R6/D7 retire only the Getting started override; the strings are existing-path expectations, not owner-prescribed new copy. Urgent precedence remains."
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
      "checkLimits": "R6/D7 retire the checklist and Help reopen action. All changed visibility assertions intentionally retire the checklist; no replacement is claimed for Help reopening that card. Legacy state preservation remains checked. modules/ui-web/src/shell-v0/chrome/FirstMinutes.app-lockdown.test.ts#S4-1 first launch has one message/action and no field, checklist, sidebar or status checks the replacement screen, not every legacy projection permutation. C9 reading lifetime is separately tested in FirstMinutes.reading.test.ts#keeps live reading visible with unknown enrichment and queue %s; this file still checks legacy state facts."
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
          "newAssertion": "Fixture-only changes import install/developer setters, explicitly set install and developer flags after AI reset, feed cudaAvailable:true in online fixtures, then reset AI/install observations and restore the previous developer preference in teardown. Expected outcomes are unchanged.",
          "reason": "R7/D3/C3 gate new mode offers on known installed/live CUDA readiness; D4 gates Structured/Delegate on developer tools. The new fixture supplies those prerequisites; it does not derive expected output from the candidate."
        }
      ],
      "checkLimits": "Accepted structured output, incompatibility, transport/stream failure and request assertions are unchanged. Preconditions changed, so this is not claimed as an Adaptation retaining every old input. The fixture no longer covers offering those modes without known install/CUDA/developer state. Fixture cleanup is restored: reset AI/install observations and restore the previous developer preference after removing views. The touched and full unit suites pass. modules/ui-web/src/shell-v0/views/search-v3/FirstMinutes.availability.test.ts#S4-14 developer tools reveal Structured/Delegate only with answers available checks positive mode visibility; its uninstalled, unknown-install, no-cuda, unknown-gpu, stale, off, failed and loading cases check that no new Ask is offered. Those checks do not prove this suite leaves no state behind."
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
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "The invalid cutover-task test dispatches Enter on the input instead of clicking send. It still checks the original keep text, visible Invalid/Unknown/No readable refusal and zero dispatches.",
      "assertionChanges": [
        {
          "oldAssertion": "An already explicit submit is represented by clicking sv3-composer-send.",
          "newAssertion": "The same explicit submit is encoded as an Enter keydown on sv3-composer-input. The invalid cutover-task test dispatches Enter on the input instead of clicking send. It still checks the original keep text, visible Invalid/Unknown/No readable refusal and zero dispatches.",
          "reason": "R7/D5 remove Search's send arrow and specify Enter. Inputs, fault injection, ownership/retention outcomes and refusal oracles of these explicit-submit cases are retained; they do not test automatic typing."
        },
        {
          "oldAssertion": "Outgoing unsent conversation fixtures manufacture only a cached placeholder for previous, then navigate back through the column.",
          "newAssertion": "Those four fixtures return previous from the canonical conversation list; retention, recovery-capacity, no-dispatch and navigation assertions are unchanged.",
          "reason": "D6 and the repaired canonical membership filter require an existing conversation or submitted local turn to populate the column. An unsent cached placeholder alone is deliberately excluded."
        }
      ],
      "checkLimits": "All original outcomes remain; Search submit is re-encoded to Enter under R7/D5. Four outgoing-conversation fixtures now supply a canonical previous row so retained text/files and recovery-capacity checks exercise a real remaining column route. This is an explicit precondition change, not a claim that every old input is unchanged. All 67 tests pass; native keyboard replay is additionally checked in FirstMinutes.app-lockdown.test.ts."
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
          "newAssertion": "The scanner again excludes only SearchV3View.honesty.test.ts. The pressure-unloaded availability case retains its literal wire fixture and oracle in state/FirstMinutes.onDemand.test.ts.",
          "reason": "No decision removed the scanner obligation. The original window-local scan domain is restored; a wire-state availability test is isolated outside it."
        }
      ],
      "checkLimits": "Restored and passing: scan every local .ts file except this self-referential honesty test, including other test files. Mutation scan-test-files adds the forbidden copy token to SearchV3View.branch.test.ts and fails the scanner. The literal pressure-unloaded wire fixture moved unchanged to state/FirstMinutes.onDemand.test.ts outside this original scan directory. R2/D10/C9 retire duplicate Search corpus remedy/navigation; R10/D6 retire the empty column."
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
      "checkLimits": "The old 504 geometry is retired for a no-conversation fixture. Other two-boundary scenarios keep their assertions; this one no longer proves reservation of a sidebar that must be absent. FirstMinutes.app-lockdown.test.ts#S4-1 first launch has one message/action and no field, checklist, sidebar or status checks column absence, not pane dimensions. The geometry comment now names the no-column 560px automatic default; the other two-boundary assertions remain."
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
          "newAssertion": "Search rows restore exact case/outer/internal-space dedup, eight-item cap, palette inclusion, no typing/facet/external-query records, replay promotion without duplication, operator/current-filter replay, failed/invalid/superseded count rules, exact rendered-header equality and response-completion time bounds. Pointer replay and independent native Enter/Space activations are tested with the current file-kind restriction.",
          "reason": "D2/C6 attach response metadata to explicit submitted attempts; D5 retires automatic Search. Removing conversation replay from this list follows the stated separation, not a candidate-derived count."
        },
        {
          "oldAssertion": "Y4/Y5/R5a Clear suppresses conversation backfill/rename/reactivation and delayed list rows, avoids writes, preserves storage-key sets, forgets searches across windows and rejects profile/workspace ABA results.",
          "newAssertion": "Clear empties Search rows without new writes or storage keys, keeps conversation ids and rejects late completions. New windows forget searches. User-profile and workspace ABA reject stale search/list replies, require fresh conversation labels and an empty shared query; registry edits preserve recents.",
          "reason": "D2 makes Clear search-only and memory-only; C6 explicitly invalidates completions after Clear/profile switch. Conversations remain owned by the separate store, so Clear no longer hides them."
        },
        {
          "oldAssertion": "Y3 retained Ask/Structured preparations survive both recent-search replay and conversation navigation. Y6 hides optimistic/legacy/untitled labels plus pre-lock list responses and handles a history 423 immediately.",
          "newAssertion": "Retained Ask/Structured preparations survive search replay and column navigation without dispatch. History 423 hides rows immediately. Lock hides optimistic/legacy/untitled rows and stale responses; unlock keeps them suppressed while its refresh is held, then renders exactly Refreshed, and an empty refresh removes the column. Canonical membership retains only unrecorded local turns alongside canonical rows without deleting cache/draft/transcript state.",
          "reason": "R10/D2 remove conversations from recents; that warrants retiring their list presentation and Clear-hiding behavior, but does not itself waive retained preparations or confidentiality at a remaining navigation boundary."
        }
      ],
      "checkLimits": "Restored and passing: exact internal/outer/case dedup, palette inclusion, facet/external-query non-recording, replay promotion/no duplication, Clear storage-key and write equality, retained Ask/Structured preparations, history/no-dispatch, history-423, empty/IME/modifier Enter (also with nonempty text), profile/workspace ABA stale-list rejection before later replies can overwrite it, fresh labels and empty shared query, exact rendered-header metadata and completion-time bounds. Mutations recent-internal-spaces, recent-palette, recent-facet-not-recorded, recent-external-not-recorded, recent-replay-promotion, clear-no-storage, clear-no-write, retained-preparations, column-canonical-membership, unlock-awaits-refresh, history-423-lock, recent-modifier-enter, profile-aba-stale-lists, header-exact-text, completion-time and pending-local-membership fail their corresponding oracles. All 18 cases pass; both original Y6 cases pass on baseline d61d78ce4e. FirstMinutes.app-lockdown.test.ts covers native Enter/Space with current restrictions, named list/Tab order, no active descendant, in-flow/no-overlap/width bounds and hero scrolling without workspace ancestor movement at 1280x700 and 360x400 in dark/light/forced colors; docked recents are absent. D2 deliberately retires the overlay listbox/active-descendant/arrows/Escape and the separately scrolling 13-row geometry, not keyboard reachability. D6 moves conversation navigation to the canonical column. Chromium mutations native-enter-space, recent-accessible-name, recent-in-flow and recent-wheel-scroll fail the corresponding real-browser oracles; the restored 14-test browser suite passes. Both native keys are separate cases. Headline top bounds are checked at scroll position zero, and wheel scrolling is verified with native wheel input."
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
      "checkLimits": "Precondition change, not proof of the former empty-window behavior: these cases now cover existing-conversation windows. No assertion is deleted. modules/ui-web/src/shell-v0/chrome/FirstMinutes.app-lockdown.test.ts#S4-1 first launch has one message/action and no field, checklist, sidebar or status checks the opposite empty-column case. That launch test does not replace these geometry/storage/focus checks or prove deep-linked and synthetic-draft edge cases. Its geometry precondition now uses a genuinely submitted local turn rather than an empty cached row; assertions remain unchanged."
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
      "checkLimits": "No assertion is deleted. The changed fixtures no longer assert offers under unknown install/CUDA/developer conditions. Fixture cleanup resets AI/install observations and restores the previous developer preference; touched and full unit suites pass. Pointer send is restored for the existing Ask pressure-refusal path; only the Search preparation uses Enter. Mutation ask-pointer-send disables conversational pointer submit and fails both pending-Load cases. Native browser key input for those load races is outside this test. FirstMinutes.availability.test.ts#S4-14 pressure-unloaded chat remains offerable through the existing on-demand availability covers the gate for on-demand chat, not these same-mode load ownership races."
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
      "checkLimits": "Changed oracle: preserving alpha results on a cleared unsubmitted draft replaces the former cancellation/empty-results assertion; it does not verify every stale-result/draft distinction or new-query label. chatUnavailable explicitly documents unavailable chat with CUDA; true no-CUDA gating stays in FirstMinutes.availability.test.ts. Teardown resets AI/install state and restores the prior developer preference; full and touched unit runs pass. modules/ui-web/src/shell-v0/views/search-v3/FirstMinutes.test.ts#S4-13 Search has a magnifier and headline label, with no prompt, send or effort checks replacement anatomy/name; #S4-13 newlines are stopped in Search, modifier chords never silently Delegate; restored drafts survive checks preserved multiline drafts and ignored modifier/IME Enter events (it does not actually paste or assert newlines cannot enter); #S4-14 restored ask always offers Search without discarding draft/schema checks existing-mode recovery. FirstMinutes.availability.test.ts covers true no-CUDA/uninstalled/unknown gating. FirstMinutes.app-lockdown.test.ts now uses native clipboard paste and requires newlines to become spaces without dispatch; a restored multiline draft keeps its exact bytes and fits its existing bounded geometry. The restored legacy multiline case is not claimed to be a newly typed single-line draft."
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
          "newAssertion": "The action is named edit then Enter and dispatches actual input and Enter keydown events. A failed edit retention and failed submit retention must each be observed; all storage faults, zero dispatch, retained draft/owner and second-ack refusal oracles remain.",
          "reason": "H4/D5 retire automatic Search on typing. The branch now applies the same retention obligations to an explicit request, not to a keystroke."
        }
      ],
      "checkLimits": "Restored and passing: edit then Enter uses a genuine input event followed by keyboard Enter, distinct from direct submit and palette. Faults must intercept edit and submit acknowledgements exactly twice, while dispatch remains zero and retained text, storage ownership and second-ack refusal assertions remain. Mutation edit-input-ack suppresses the input notification and fails all three edit-path interception-count assertions. H4/D5 retire search-on-edit timers; no automatic-search-on-typing oracle is claimed."
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
          "newAssertion": "Fixture-only: select Ask for conversational sends. Absent-sidebar row/group readers return empty; New requires and activates a real control. The three-conversation fixture creates its first conversation before using New.",
          "reason": "D5 distinguishes Search from Ask, and R10/D6 remove the column in empty windows. Helper fallbacks avoid treating an absent column as a missing required element in those cases."
        },
        {
          "oldAssertion": "Deleting the final conversation focuses sv3-sidebar-announcer; the repeated-announcement case ends with no rows.",
          "newAssertion": "Deleting the final conversation focuses sv3-composer-input and emits Conversation deleted. The repeat-announcement fixture first adds survivor, so its final row is survivor and the column/announcer stays mounted.",
          "reason": "R10/D6 remove the final empty column, requiring a focus destination that survives. A surviving conversation is a legitimate precondition for retaining the separate repeated live-announcement test; the exact new toast text is not prescribed by those sources."
        }
      ],
      "checkLimits": "Restored and passing: New requires a sidebar and a non-null real New control; its fixture creates the first conversation before requesting New. Mutation new-required-control removes the control and fails this obligation. Empty sidebar readers remain absence-only observations. Final deletion focuses the surviving composer and renders Conversation deleted through the real AdvisoryStore/StatusPill polite leaf region, with no controls inside it; mutation final-deletion-polite changes that region to assertive and fails. The separate survivor fixture retains repeated live-region mutations. R10/D6 retire the empty-column focus destination."
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
      "checkLimits": "Precondition change, not proof of the former empty-window behavior: these cases now cover existing-conversation windows. No assertion is deleted. modules/ui-web/src/shell-v0/chrome/FirstMinutes.app-lockdown.test.ts#S4-1 first launch has one message/action and no field, checklist, sidebar or status checks the opposite empty-column case. That launch test does not replace these geometry/storage/focus checks or prove deep-linked and synthetic-draft edge cases. The fixture now supplies a genuinely submitted local turn, retaining the existing width, focus and storage oracles without relying on a cached placeholder."
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
      "checkLimits": "Precondition change, not proof of the former empty-window behavior: these cases now cover existing-conversation windows. No assertion is deleted. modules/ui-web/src/shell-v0/chrome/FirstMinutes.app-lockdown.test.ts#S4-1 first launch has one message/action and no field, checklist, sidebar or status checks the opposite empty-column case. That launch test does not replace these geometry/storage/focus checks or prove deep-linked and synthetic-draft edge cases. The fixture now supplies a genuinely submitted local turn, retaining the existing width, focus and storage oracles without relying on a cached placeholder."
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
      "checkLimits": "Precondition change, not proof of the former empty-window behavior: these cases now cover existing-conversation windows. No assertion is deleted. modules/ui-web/src/shell-v0/chrome/FirstMinutes.app-lockdown.test.ts#S4-1 first launch has one message/action and no field, checklist, sidebar or status checks the opposite empty-column case. That launch test does not replace these geometry/storage/focus checks or prove deep-linked and synthetic-draft edge cases. The backend fixture now returns an existing canonical conversation throughout the New/retained-results tests; no outcome assertion is weakened."
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
          "newAssertion": "Fixture-only changes import install/developer setters, explicitly set install and developer flags after AI reset, feed cudaAvailable:true in online fixtures, then reset AI/install observations and restore the previous developer preference in teardown. Expected outcomes are unchanged.",
          "reason": "R7/D3/C3 gate new mode offers on known installed/live CUDA readiness; D4 gates Structured/Delegate on developer tools. The new fixture supplies those prerequisites; it does not derive expected output from the candidate."
        }
      ],
      "checkLimits": "The chat-unavailable case also restores install/developer flags after reset, without feeding live CUDA/chat availability; its schema/instruction retention and zero-dispatch assertions remain. The fixture still exercises unavailable chat, not a new successful Ask offer. Preconditions changed, so this is not claimed as an Adaptation retaining every old input. The fixture no longer covers offering those modes without known install/CUDA/developer state. Fixture cleanup is restored: reset AI/install observations and restore the previous developer preference after removing views. The touched and full unit suites pass. modules/ui-web/src/shell-v0/views/search-v3/FirstMinutes.availability.test.ts#S4-14 developer tools reveal Structured/Delegate only with answers available checks positive mode visibility; its uninstalled, unknown-install, no-cuda, unknown-gpu, stale, off, failed and loading cases check that no new Ask is offered. Those checks do not prove this suite leaves no state behind. Structured extraction uses its surviving pointer send and requires the send control; the schema-field modifier-key check remains."
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
          "newAssertion": "Fixture-only changes import install/developer setters, explicitly set install and developer flags after AI reset, feed cudaAvailable:true in online fixtures, then reset AI/install observations and restore the previous developer preference in teardown. Expected outcomes are unchanged.",
          "reason": "R7/D3/C3 gate new mode offers on known installed/live CUDA readiness; D4 gates Structured/Delegate on developer tools. The new fixture supplies those prerequisites; it does not derive expected output from the candidate."
        }
      ],
      "checkLimits": "Structured partial/terminal outcome and extraction-scope assertions are unchanged. Preconditions changed, so this is not claimed as an Adaptation retaining every old input. The fixture no longer covers offering those modes without known install/CUDA/developer state. Fixture cleanup is restored: reset AI/install observations and restore the previous developer preference after removing views. The touched and full unit suites pass. modules/ui-web/src/shell-v0/views/search-v3/FirstMinutes.availability.test.ts#S4-14 developer tools reveal Structured/Delegate only with answers available checks positive mode visibility; its uninstalled, unknown-install, no-cuda, unknown-gpu, stale, off, failed and loading cases check that no new Ask is offered. Those checks do not prove this suite leaves no state behind."
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
          "newAssertion": "Hold an explicit request to observe pending data and exact isRefining:true/isSearching:false, then assert new results. Advanced explicit completion has refined stage, matchCount:1, facets:null, no pending-count copy and one request after delay.",
          "reason": "The source requires Enter; the existing explicit/refined request path determines the stage and count oracle. A held-response fixture is necessary to observe the unchanged pending-data obligation rather than sample a completed request."
        }
      ],
      "checkLimits": "Restored and passing: exact isRefining:true/isSearching:false on the held explicit refined request, initial one-request count during supersession, facets:null when advanced completion omits facets, querySyntax:search and no pipeline override. Mutations syntax-no-quick-pipeline, syntax-refining-searching, syntax-first-request and syntax-facets-null fail those exact oracles. H4/D5 retire the automatic BM25 quick-pass override and quick-stage pending-facet contract; explicit Enter already uses the default refined pipeline."
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
          "newAssertion": "Require no listbox rule, a scrolling hero host and the exact Search textarea overflow rule. The rendered one-scroller count includes sidebar, main and composer in a populated conversation window. Browser recents flow/scroll/width bounds and preserved multiline bounds are checked in the real Shell in FirstMinutes.app-lockdown.test.ts.",
          "reason": "H4/D2 replace an overlay listbox with a list in the page flow; the hero page must scroll. The loop's empty-window sidebar exclusion follows R10, not a claim about sidebar scrollers in populated windows."
        }
      ],
      "checkLimits": "Restored and passing: the exact Search textarea overflow rule (white-space:pre, overflow:auto, resize:none) is required; the one-scroller integration count includes sidebar, main and composer in a populated local-turn window. Mutations textarea-overflow-rule and populated-scroller-inventory fail these oracles. Browser flow/no-overlap/scroll/name/theme and restored-multiline/native-paste checks are in FirstMinutes.app-lockdown.test.ts. D2 retires overlay-listbox min-size/overscroll/band bounds and D6 retires a fresh empty sidebar; those removed-UI predicates remain deliberately absent. Chromium mutations recent-in-flow, recent-wheel-scroll, restored-multiline-geometry and native-paste each fail their relevant browser assertions, then all 14 browser tests pass with the original bytes restored."
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
          "newAssertion": "Search's first control is tier and effort/send are absent; Ctrl+Enter emits no submit and never invokes Delegate/Ask. Existing Ask send-hint assertions remain.",
          "reason": "R7/D5 remove Search effort/send and require intentional Search Enter, rather than a hidden Delegate chord."
        },
        {
          "oldAssertion": "Ready AI implicitly offers every mode; unavailable Ask can be chosen from Search; effort-menu exclusivity is tested in default Search.",
          "newAssertion": "Fixture-only install/CUDA/developer state is explicit; refusal uses existing Ask and menu-exclusivity first chooses Ask. pressSend now uses Enter on the current draft.",
          "reason": "D3/D4/C3 govern new-mode offers, C1 keeps existing unavailable tasks recoverable, and D5 retains effort/send in Ask."
        }
      ],
      "checkLimits": "Restored and passing: mode is the first data-testid in the Search control row; effort is absent. Mutation mode-first-control inserts an earlier control and fails the exact order assertion. Conversational pressSend uses and requires its pointer button; Search uses Enter because R7/D5 retire its send and pointer hint. Existing Ask send hints and direct Enter routing remain. Cleanup resets AI/install observations and restores the previous developer preference; full and touched unit runs pass. C1 preserves unavailable existing tasks; D3/D4/C3 govern new offers."
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
      "checkLimits": "Observation boundary is narrower: these three tests no longer prove integration of the zero-state into Search or the real shell heading outline. The component predicates are unchanged. FirstMinutes.app-lockdown.test.ts#S4-1 first launch has one message/action and no field, checklist, sidebar or status covers the replacement absent-sidebar composition, not populated-window component composition or whole-shell heading accessibility. The sidebar zero-state name/comment now explicitly describes the isolated component; no full-window zero-column integration claim is made."
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
      "checkLimits": "No assertion is removed. The model-label focus case explicitly uses existing Ask under R7/D5; direct assignment does not prove mode-switch activation. Developer preference is captured before setup and restored after removing the views, alongside the existing AI reset. Full and touched unit suites pass."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.export.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
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
          "oldAssertion": "The export fixture installs a local cached session with recorded turns, while its canonical list response is empty.",
          "newAssertion": "The fixture also supplies that session through the real canonical list load; export identity, copy/download, error, lock, lifecycle and modal/focus assertions remain unchanged.",
          "reason": "D6 and the repaired column membership require canonical conversations or pending local turns, rather than a stale cached transcript, for a visible column export action."
        }
      ],
      "checkLimits": "Fixture-only repair of the remaining export interface; all export tests pass in the full unit suite. The test still stubs its HTTP boundary; independent Engine export verification is not claimed."
    }
  ]
}
```

## Acceptance records
