---
schema: test-intent.v1
task: t-20261113-fmimpl
author-role: builder
author-session: codex-builder-fm-top-bar-20261114
---

# Test-intent entries

This changeset accounts for the fourteen modified product-test files in the working
candidate based on ec75ad29d6 against origin/main 1fd084cd79. Each entry names
the superseded presentation, input path or observation and its replacement. `Obsolete` is used
because the adopted product contract changes: even the files that only add a
Menu-opening action no longer promise a directly available Settings gear. Their
unchanged navigation, notification and synchronization outcomes are explicitly
retained. The entry descriptions do not claim independent acceptance; the
acceptance records below are preserved as recorded.

Source provenance follows fm-teal.md. R9 names **rule 9** and H3 names
**handover slice 3** of task t-20261113-uicritique. Both are agent-drafted,
owner-adopted scenarios, not owner-authored wording. Their original names are
retained as `scenarioName` beside the gate's alphanumeric IDs.
The adoption location is task t-20261113-uicritique user turn round 7:
“yes, update accordingly. im especially surprised by how long it took for agents to realise the text size issue by themselves. you are the first one.”
Round 14 continued that implementation handoff:
“now that the prototype is clear, do you think you should do any further work to make the implementation faster and more accurate for the implementers? if so, proceed with that work accordingly and give me the prompt you want me to start the implementer with.”
The entries quote the adopted rule and design-handover slice verbatim.

The delegated choices are agent choices, never owner words. Their delegation is
“You are supposed to be the implementation session”, located at task
t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z, with scope
“implement the agreed first-minutes design set”. The choices cited below are:

1. Remove Search's own title band and command glyph; retain Ctrl+K.
2. Name the button Menu; order fixed entries Settings, System, Help & shortcuts,
   About JustSearch, then keep the existing plugin and developer groups.
3. Use only a dot for every calm status, with the current calm text as accessible
   name/title; retain non-calm words and show a known percentage in them; remove
   the calm plugin-item count from the bar.
4. Retain the narrow fallback; keep an Undo notice inline at narrow widths so it
   fits the 44px bar.
5. Show area icons only where the area word is hidden.

The acceptor must confirm the supplied adoption/delegation turns and their scope.
Private task records were not read in this builder session. Repository decision
records were searched; the entry fields state the scope of that conflict search.
The earlier builder's tmp/s3-evidence/test-expectations.txt was a navigation aid;
the classifications below follow each actual git diff, not its claimed results.

Coverage limits are part of these entries, not passes: the outage test opens
Settings through Menu, empty symbol text does not prove a drawn dot, and the boot
probe samples the fixed, required 44 px bar rather than current control geometry.
The retired Search component loses its own title, glyph, height and decoration assertions by
design. Per-entry `checkLimits` fields identify deletions and weaker observations,
including where another existing test supplies only a partial replacement.
Choice 4's narrow Undo placement and choice 5's icon fallback are source decisions;
these fourteen diffs add no direct layout/icon assertions for them.

TopBar.singleBar.test.ts and StatusPill.calmDot.test.ts are wholly added test files,
not modified files covered by these entries. Their source-level/DOM checks are
mentioned only where they delimit replacement coverage; their reported earlier
fail-before/pass-after results have not been rerun here. The script self-test
scripts/jsharness/tests/test_ui_proportion_gate.py changes its overlap counterpart
from the retired sv3-topbar to jf-top-bar while retaining the selector-presence
requirement; script self-tests are outside this gate's product-test scope. The
changed governance scenario title and proportion register are not substituted for
a source that predates this candidate.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/TopBar.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R9",
          "scenarioName": "rule 9",
          "quote": "The top bar has three places with one job each. Left: the name, quiet. Centre: the three areas, words only. Right: status, one menu, the window buttons. It is 44 px high and sits on the page's own background. Status is a small dot while everything is fine and turns into words when something is happening or needs you. Settings is the first entry of the menu, not a second button.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "H3",
          "scenarioName": "handover slice 3",
          "quote": "Top bar (fm-01, fm-15, fm-17; critique B5, B6). 44 px, on the page background. Left the name, centre the three areas in words, right status, one menu, window buttons. Status is a dot while all is fine and becomes words when something runs or needs the user. Settings is the first entry of the menu; the gear button and the switcher icons go.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "The menu button is named \"Menu\"; fixed entries Settings, System, Help & shortcuts, About JustSearch, then the existing plugin and developer groups.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Area icons appear only where an area word is hidden.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none in docs/decisions for top bar/topbar, Settings gear/button/menu, More menu, status pill/dot, palette, 44px/56px/3.5rem, sv3-topbar, one bar, command glyph and switcher icons. Matches in ADR-0008 concern settings persistence, ADR-0026 CI contrast measurement, and ADR-0050 GPU status, not these superseded presentation or entry-path expectations. Historical test names and comments describe the previous contract. Private task records were not read; the supplied adoption and delegation provenance must be checked by the acceptor.",
      "assertionChanges": [
        {
          "oldAssertion": "Settings is a standalone trigger, directly selectable without opening More, and its surface id is unique while the menu is closed.",
          "newAssertion": "The standalone .settings-trigger must be absent; opening Menu exposes a Settings entry labelled Settings. Selection tests open Menu before choosing Settings, then retain their surface-selection payload assertions. The closed-menu uniqueness loop retains Search, Library and AI Brain and drops Settings.",
          "reason": "R9 and H3 explicitly put Settings first in the menu and remove the second button. Closed-menu addressability for that menu-only item is intentionally superseded; this is a changed product contract, not an oracle-preserving selector rename."
        },
        {
          "oldAssertion": "The menu is named More and fixed rows are System, Help & shortcuts, About JustSearch, then Acme notes.",
          "newAssertion": "The menu is named Menu and rows must be Settings, System, Help & shortcuts, About JustSearch, Acme notes. System detail, Help F1 text/aria-keyshortcuts and plugin surface-id checks shift one index. App/More group labels and developer-tools-hidden checks remain.",
          "reason": "Delegated choice 2 supplies the exact Menu name and fixed ordering; R9/H3 supply Settings first. The retained plugin and developer grouping is part of that choice, rather than a list copied from the candidate."
        },
        {
          "oldAssertion": "Settings remains directly present even when switcherVisible=false.",
          "newAssertion": "With switcherVisible=false, the navigation landmark stays hidden; opening Menu must expose Settings, and the Menu trigger remains present.",
          "reason": "The adopted menu-only Settings route applies when the area switcher is hidden too. The availability obligation stays, with the old always-present gear expectation retired."
        }
      ],
      "checkLimits": "The removed Settings member of the closed-menu uniqueness loop has no equivalent assertion that its data-surface-id is absent while closed. ShellAreas.test.ts separately counts one Settings entry with Menu open under its stale-catalog fixture. This file does not newly assert word-only areas, hidden-word icon fallback, 44px height, page background, or Menu initial focus. TopBar.singleBar.test.ts independently asserts the Menu trigger name/title, fixed order, initial Settings focus/shortcut and the 2.75rem source token; it is not live geometry evidence."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.frame.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R9",
          "scenarioName": "rule 9",
          "quote": "The top bar has three places with one job each. Left: the name, quiet. Centre: the three areas, words only. Right: status, one menu, the window buttons. It is 44 px high and sits on the page's own background. Status is a small dot while everything is fine and turns into words when something is happening or needs you. Settings is the first entry of the menu, not a second button.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "H3",
          "scenarioName": "handover slice 3",
          "quote": "Top bar (fm-01, fm-15, fm-17; critique B5, B6). 44 px, on the page background. Left the name, centre the three areas in words, right status, one menu, window buttons. Status is a dot while all is fine and becomes words when something runs or needs the user. Settings is the first entry of the menu; the gear button and the switcher icons go.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "The menu button is named \"Menu\"; fixed entries Settings, System, Help & shortcuts, About JustSearch, then the existing plugin and developer groups.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none in docs/decisions for top bar/topbar, Settings gear/button/menu, More menu, status pill/dot, palette, 44px/56px/3.5rem, sv3-topbar, one bar, command glyph and switcher icons. Matches in ADR-0008 concern settings persistence, ADR-0026 CI contrast measurement, and ADR-0050 GPU status, not these superseded presentation or entry-path expectations. Historical test names and comments describe the previous contract. Private task records were not read; the supplied adoption and delegation provenance must be checked by the acceptor.",
      "assertionChanges": [
        {
          "oldAssertion": "Each saved-layout case finds Settings directly in the closed top bar, even with the switcher hidden.",
          "newAssertion": "Each case clicks .more-trigger, waits for its update, then still requires the core.settings-surface entry. Hidden navigation, Ctrl+2 activation, Ctrl+3 action registration and Brain activation assertions remain.",
          "reason": "R9/H3 move Settings into the one menu. The extra interaction is required by that adopted entry path, independently of current markup; availability across the existing layout cases is retained."
        }
      ],
      "checkLimits": "No expected value or history/state assertion is deleted in this diff. The changed precondition retires direct gear availability rather than asserting Settings is always visible in a closed bar."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.navigation.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R9",
          "scenarioName": "rule 9",
          "quote": "The top bar has three places with one job each. Left: the name, quiet. Centre: the three areas, words only. Right: status, one menu, the window buttons. It is 44 px high and sits on the page's own background. Status is a small dot while everything is fine and turns into words when something is happening or needs you. Settings is the first entry of the menu, not a second button.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "H3",
          "scenarioName": "handover slice 3",
          "quote": "Top bar (fm-01, fm-15, fm-17; critique B5, B6). 44 px, on the page background. Left the name, centre the three areas in words, right status, one menu, window buttons. Status is a dot while all is fine and becomes words when something runs or needs the user. Settings is the first entry of the menu; the gear button and the switcher icons go.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "The menu button is named \"Menu\"; fixed entries Settings, System, Help & shortcuts, About JustSearch, then the existing plugin and developer groups.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none in docs/decisions for top bar/topbar, Settings gear/button/menu, More menu, status pill/dot, palette, 44px/56px/3.5rem, sv3-topbar, one bar, command glyph and switcher icons. Matches in ADR-0008 concern settings persistence, ADR-0026 CI contrast measurement, and ADR-0050 GPU status, not these superseded presentation or entry-path expectations. Historical test names and comments describe the previous contract. Private task records were not read; the supplied adoption and delegation provenance must be checked by the acceptor.",
      "assertionChanges": [
        {
          "oldAssertion": "The Settings address/default-section, browser Back/Forward and incoming deep-link cases enter Settings by directly clicking its top-bar surface id.",
          "newAssertion": "Those three entry sites open Menu and settle before clicking the same Settings surface id. Their existing default-section, address/history, area retention, Back/Forward and deep-link destination assertions are unchanged.",
          "reason": "R9/H3 expressly replace the separate Settings button with a menu entry. The navigation outcomes continue to come from the existing tests; only the obsolete direct-entry precondition is superseded."
        }
      ],
      "checkLimits": "This diff adds entry actions and waits, and removes no assertion. It does not introduce a new saved-state or history expectation."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.noticeClick.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R9",
          "scenarioName": "rule 9",
          "quote": "The top bar has three places with one job each. Left: the name, quiet. Centre: the three areas, words only. Right: status, one menu, the window buttons. It is 44 px high and sits on the page's own background. Status is a small dot while everything is fine and turns into words when something is happening or needs you. Settings is the first entry of the menu, not a second button.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "H3",
          "scenarioName": "handover slice 3",
          "quote": "Top bar (fm-01, fm-15, fm-17; critique B5, B6). 44 px, on the page background. Left the name, centre the three areas in words, right status, one menu, window buttons. Status is a dot while all is fine and becomes words when something runs or needs the user. Settings is the first entry of the menu; the gear button and the switcher icons go.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "The menu button is named \"Menu\"; fixed entries Settings, System, Help & shortcuts, About JustSearch, then the existing plugin and developer groups.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none in docs/decisions for top bar/topbar, Settings gear/button/menu, More menu, status pill/dot, palette, 44px/56px/3.5rem, sv3-topbar, one bar, command glyph and switcher icons. Matches in ADR-0008 concern settings persistence, ADR-0026 CI contrast measurement, and ADR-0050 GPU status, not these superseded presentation or entry-path expectations. Historical test names and comments describe the previous contract. Private task records were not read; the supplied adoption and delegation provenance must be checked by the acceptor.",
      "assertionChanges": [
        {
          "oldAssertion": "The shared openSettings helper directly clicks the Settings surface id in a closed bar.",
          "newAssertion": "The helper opens Menu and settles before clicking Settings, then retains its assertion that the Settings window is open. Existing notice-click, notification destination and return assertions remain.",
          "reason": "R9/H3 determine the new Settings route. The old notification/modal outcomes are not re-baselined against the candidate; their assertions remain after the required menu interaction."
        }
      ],
      "checkLimits": "No assertion is weakened or deleted in this diff; it supersedes only the one-click Settings entry precondition."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R9",
          "scenarioName": "rule 9",
          "quote": "The top bar has three places with one job each. Left: the name, quiet. Centre: the three areas, words only. Right: status, one menu, the window buttons. It is 44 px high and sits on the page's own background. Status is a small dot while everything is fine and turns into words when something is happening or needs you. Settings is the first entry of the menu, not a second button.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "H3",
          "scenarioName": "handover slice 3",
          "quote": "Top bar (fm-01, fm-15, fm-17; critique B5, B6). 44 px, on the page background. Left the name, centre the three areas in words, right status, one menu, window buttons. Status is a dot while all is fine and becomes words when something runs or needs the user. Settings is the first entry of the menu; the gear button and the switcher icons go.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "The menu button is named \"Menu\"; fixed entries Settings, System, Help & shortcuts, About JustSearch, then the existing plugin and developer groups.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none in docs/decisions for top bar/topbar, Settings gear/button/menu, More menu, status pill/dot, palette, 44px/56px/3.5rem, sv3-topbar, one bar, command glyph and switcher icons. Matches in ADR-0008 concern settings persistence, ADR-0026 CI contrast measurement, and ADR-0050 GPU status, not these superseded presentation or entry-path expectations. Historical test names and comments describe the previous contract. Private task records were not read; the supplied adoption and delegation provenance must be checked by the acceptor.",
      "assertionChanges": [
        {
          "oldAssertion": "The browser helper and developer-tools/multi-window tests click the direct Settings surface id or .settings-trigger gear.",
          "newAssertion": "The helper clicks .more-trigger then the Settings menu entry. Developer-tools enable/disable sites and both browser-window Settings entry sites use that helper. Existing Settings, developer-menu, Logs/System address and cross-window synchronization assertions remain.",
          "reason": "R9/H3 remove the gear and make Menu the entry point. Delegated choice 2 retains the existing developer group. Using that source-defined route preserves the old settings synchronization expectations without inventing new persisted-state behavior."
        }
      ],
      "checkLimits": "No assertion is removed; the UI setup changes. Existing stale test names still say More. These descriptions do not claim a browser execution pass."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.engineLoss-lockdown.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R9",
          "scenarioName": "rule 9",
          "quote": "The top bar has three places with one job each. Left: the name, quiet. Centre: the three areas, words only. Right: status, one menu, the window buttons. It is 44 px high and sits on the page's own background. Status is a small dot while everything is fine and turns into words when something is happening or needs you. Settings is the first entry of the menu, not a second button.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "H3",
          "scenarioName": "handover slice 3",
          "quote": "Top bar (fm-01, fm-15, fm-17; critique B5, B6). 44 px, on the page background. Left the name, centre the three areas in words, right status, one menu, window buttons. Status is a dot while all is fine and becomes words when something runs or needs the user. Settings is the first entry of the menu; the gear button and the switcher icons go.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "The menu button is named \"Menu\"; fixed entries Settings, System, Help & shortcuts, About JustSearch, then the existing plugin and developer groups.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none in docs/decisions for top bar/topbar, Settings gear/button/menu, More menu, status pill/dot, palette, 44px/56px/3.5rem, sv3-topbar, one bar, command glyph and switcher icons. Matches in ADR-0008 concern settings persistence, ADR-0026 CI contrast measurement, and ADR-0050 GPU status, not these superseded presentation or entry-path expectations. Historical test names and comments describe the previous contract. Private task records were not read; the supplied adoption and delegation provenance must be checked by the acceptor.",
      "assertionChanges": [
        {
          "oldAssertion": "The outage layout helper hit-tests the centre of the standalone Settings surface-id element and reports settingsReachable when the hit belongs to jf-top-bar.",
          "newAssertion": "The helper hit-tests .more-trigger and reports menuReachable; the true assertion names Menu. In every existing outage size/theme/text-scale case, after all prior layout, contrast, focus-order, visible-focus and no-motion assertions, the test clicks Menu, requires exactly one visible, enabled Settings menuitem, clicks it, and polls the Settings dialog until it is open and modal.",
          "reason": "R9/H3 remove the direct gear and put Settings first in Menu. The replacement therefore checks the complete Menu-to-Settings route during engine loss, retaining the Settings reachability obligation and every other original assertion."
        }
      ],
      "checkLimits": "The Menu centre hit still accepts any top-bar descendant, but real clicks plus explicit Settings presence, visibility, enabled state and open-modal assertions now cover the full outage-specific entry route. The Settings surface remains the existing probe fixture; this does not check Settings backend operations during an outage."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/ShellAreas.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R9",
          "scenarioName": "rule 9",
          "quote": "The top bar has three places with one job each. Left: the name, quiet. Centre: the three areas, words only. Right: status, one menu, the window buttons. It is 44 px high and sits on the page's own background. Status is a small dot while everything is fine and turns into words when something is happening or needs you. Settings is the first entry of the menu, not a second button.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "H3",
          "scenarioName": "handover slice 3",
          "quote": "Top bar (fm-01, fm-15, fm-17; critique B5, B6). 44 px, on the page background. Left the name, centre the three areas in words, right status, one menu, window buttons. Status is a dot while all is fine and becomes words when something runs or needs the user. Settings is the first entry of the menu; the gear button and the switcher icons go.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "The menu button is named \"Menu\"; fixed entries Settings, System, Help & shortcuts, About JustSearch, then the existing plugin and developer groups.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none in docs/decisions for top bar/topbar, Settings gear/button/menu, More menu, status pill/dot, palette, 44px/56px/3.5rem, sv3-topbar, one bar, command glyph and switcher icons. Matches in ADR-0008 concern settings persistence, ADR-0026 CI contrast measurement, and ADR-0050 GPU status, not these superseded presentation or entry-path expectations. Historical test names and comments describe the previous contract. Private task records were not read; the supplied adoption and delegation provenance must be checked by the acceptor.",
      "assertionChanges": [
        {
          "oldAssertion": "Settings must be absent from More, with exactly one pinned gear even if a stale catalog declares Settings as a rail surface.",
          "newAssertion": "The menu-id list must contain core.settings-surface; the top-bar Settings button count is still exactly one, and that item still declares aria-haspopup=dialog. The separate exclusion of Settings from catalog-driven rail surfaces remains.",
          "reason": "R9/H3 require Settings in the menu, never as the second button. The singleton and modal obligations survive the relocation and are retained rather than weakened to a mere existence check."
        }
      ],
      "checkLimits": "The renamed test says first entry, but this diff checks membership, singleton count and modal semantics rather than ordering. TopBar.test.ts and TopBar.singleBar.test.ts separately assert Settings first."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.palette.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R9",
          "scenarioName": "rule 9",
          "quote": "The top bar has three places with one job each. Left: the name, quiet. Centre: the three areas, words only. Right: status, one menu, the window buttons. It is 44 px high and sits on the page's own background. Status is a small dot while everything is fine and turns into words when something is happening or needs you. Settings is the first entry of the menu, not a second button.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "H3",
          "scenarioName": "handover slice 3",
          "quote": "Top bar (fm-01, fm-15, fm-17; critique B5, B6). 44 px, on the page background. Left the name, centre the three areas in words, right status, one menu, window buttons. Status is a dot while all is fine and becomes words when something runs or needs the user. Settings is the first entry of the menu; the gear button and the switcher icons go.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Search's own title band and command glyph are removed (one bar); Ctrl+K stays.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none in docs/decisions for top bar/topbar, Settings gear/button/menu, More menu, status pill/dot, palette, 44px/56px/3.5rem, sv3-topbar, one bar, command glyph and switcher icons. Matches in ADR-0008 concern settings persistence, ADR-0026 CI contrast measurement, and ADR-0050 GPU status, not these superseded presentation or entry-path expectations. Historical test names and comments describe the previous contract. Private task records were not read; the supplied adoption and delegation provenance must be checked by the acceptor.",
      "assertionChanges": [
        {
          "oldAssertion": "The Search title-band glyph is focused and clicked to open one app palette; Escape closes it and returns focus to that glyph.",
          "newAssertion": "Search must have no jf-sv3-topbar. Focus the composer field and dispatch Ctrl+K; the palette must open with exactly one palette present, Escape must close it, and focus must return to the composer field.",
          "reason": "Delegated choice 1 explicitly removes Search's title band/glyph while preserving Ctrl+K. The chosen invoker and focus-restoration expectation follow that retained keyboard route, not the candidate's focus output. R9/H3 supply the single-bar intent."
        }
      ],
      "checkLimits": "The click/glyph focus path is deleted with the production band and has no equivalent clickable replacement by design. The preceding unchanged multi-area chord test still asserts exactly one shell.toggle-palette invocation per press and focus return. This changed test checks the Escape return path from Search."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPill.design.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R9",
          "scenarioName": "rule 9",
          "quote": "The top bar has three places with one job each. Left: the name, quiet. Centre: the three areas, words only. Right: status, one menu, the window buttons. It is 44 px high and sits on the page's own background. Status is a small dot while everything is fine and turns into words when something is happening or needs you. Settings is the first entry of the menu, not a second button.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "H3",
          "scenarioName": "handover slice 3",
          "quote": "Top bar (fm-01, fm-15, fm-17; critique B5, B6). 44 px, on the page background. Left the name, centre the three areas in words, right status, one menu, window buttons. Status is a dot while all is fine and becomes words when something runs or needs the user. Settings is the first entry of the menu; the gear button and the switcher icons go.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Every calm status case is the dot only, its accessible name/title the calm text it has today; all other status kinds keep their words; a known percentage is visible in the words; the calm plugin-item count leaves the bar.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "The narrow fallback stays; at narrow widths an Undo notice stays inline so it fits the 44 px bar.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none in docs/decisions for top bar/topbar, Settings gear/button/menu, More menu, status pill/dot, palette, 44px/56px/3.5rem, sv3-topbar, one bar, command glyph and switcher icons. Matches in ADR-0008 concern settings persistence, ADR-0026 CI contrast measurement, and ADR-0050 GPU status, not these superseded presentation or entry-path expectations. Historical test names and comments describe the previous contract. Private task records were not read; the supplied adoption and delegation provenance must be checked by the acceptor.",
      "assertionChanges": [
        {
          "oldAssertion": "A known 40% install fraction draws a ring but visible words are only Installing AI models and trigger text must contain no percent sign.",
          "newAssertion": "Visible words must be Installing AI models · 40% and trigger text must contain 40%. The 40 data-percent, 40 100 arc, full accessible name and unknown-fraction no-ring assertions stay.",
          "reason": "R9/H3 require words when work is happening; delegated choice 3 explicitly requires a known percentage in those words. The exact 40% follows the test's 40-of-100 input, rather than the rendered output."
        },
        {
          "oldAssertion": "At wide width calm Up to date appears as label text; at 900px it is hidden and the symbol contains the literal filled-circle glyph.",
          "newAssertion": "Calm has no .label at wide or 900px width, and the narrow .symbol has empty text and is drawn by CSS as the dot: it sits inside the calm trigger and the component rule .calm .symbol sets inline-size and block-size to --frame-status-dot (0.5rem, asserted in StatusPill.calmDot.test.ts), background --text-success and the round pill radius. Up to date remains the accessible name. The existing narrow needs-you badge/name, notice text, wide non-calm words and Undo/Ctrl+Z behavior checks remain.",
          "reason": "R9/H3 require the calm dot; delegated choice 3 applies it to every calm case and preserves its name, while choice 4 retains the narrow fallback. The symbol text replacement accommodates a drawn dot but does not itself specify how CSS must draw it."
        }
      ],
      "checkLimits": "The dot is asserted through its component style rule and token, not a rendered pixel measurement in this jsdom-style test; the rendered 8 px dot is checked in the browser and packaged-app verification."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPill.transitions.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R9",
          "scenarioName": "rule 9",
          "quote": "The top bar has three places with one job each. Left: the name, quiet. Centre: the three areas, words only. Right: status, one menu, the window buttons. It is 44 px high and sits on the page's own background. Status is a small dot while everything is fine and turns into words when something is happening or needs you. Settings is the first entry of the menu, not a second button.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "H3",
          "scenarioName": "handover slice 3",
          "quote": "Top bar (fm-01, fm-15, fm-17; critique B5, B6). 44 px, on the page background. Left the name, centre the three areas in words, right status, one menu, window buttons. Status is a dot while all is fine and becomes words when something runs or needs the user. Settings is the first entry of the menu; the gear button and the switcher icons go.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Every calm status case is the dot only, its accessible name/title the calm text it has today; all other status kinds keep their words; a known percentage is visible in the words; the calm plugin-item count leaves the bar.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none in docs/decisions for top bar/topbar, Settings gear/button/menu, More menu, status pill/dot, palette, 44px/56px/3.5rem, sv3-topbar, one bar, command glyph and switcher icons. Matches in ADR-0008 concern settings persistence, ADR-0026 CI contrast measurement, and ADR-0050 GPU status, not these superseded presentation or entry-path expectations. Historical test names and comments describe the previous contract. Private task records were not read; the supplied adoption and delegation provenance must be checked by the acceptor.",
      "assertionChanges": [
        {
          "oldAssertion": "Calm Up to date must have a visible label, and one registered plugin item must render count 1 in the bar.",
          "newAssertion": "Calm must have no .label; aria-label remains Up to date and title must now equal Up to date. No Plugin status items count may render after registration, while panel().plugins ids must equal [plugin.timer]. After pluginsVisible=false the no-count and accessible-name assertions remain.",
          "reason": "R9/H3 require the calm dot only. Delegated choice 3 expressly removes the calm plugin count and assigns the calm text to the dot's name/title. The panel identity expectation uses the independently registered plugin.timer id to retain the plugin contribution rather than silently dropping it with the count."
        }
      ],
      "checkLimits": "The old visible label/count expectations have explicit absence replacements, and the panel receives an explicit plugin-id check. The title equality is new: the previous title was the generic status-opening hint, so it is not claimed unchanged. Passing plugins to the panel is narrower than proving their content is visible or actionable there."
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
          "scenario": "R9",
          "scenarioName": "rule 9",
          "quote": "The top bar has three places with one job each. Left: the name, quiet. Centre: the three areas, words only. Right: status, one menu, the window buttons. It is 44 px high and sits on the page's own background. Status is a small dot while everything is fine and turns into words when something is happening or needs you. Settings is the first entry of the menu, not a second button.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "H3",
          "scenarioName": "handover slice 3",
          "quote": "Top bar (fm-01, fm-15, fm-17; critique B5, B6). 44 px, on the page background. Left the name, centre the three areas in words, right status, one menu, window buttons. Status is a dot while all is fine and becomes words when something runs or needs the user. Settings is the first entry of the menu; the gear button and the switcher icons go.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Search's own title band and command glyph are removed (one bar); Ctrl+K stays.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none in docs/decisions for top bar/topbar, Settings gear/button/menu, More menu, status pill/dot, palette, 44px/56px/3.5rem, sv3-topbar, one bar, command glyph and switcher icons. Matches in ADR-0008 concern settings persistence, ADR-0026 CI contrast measurement, and ADR-0050 GPU status, not these superseded presentation or entry-path expectations. Historical test names and comments describe the previous contract. Private task records were not read; the supplied adoption and delegation provenance must be checked by the acceptor.",
      "assertionChanges": [
        {
          "oldAssertion": "Search mounts a title band along with sidebar, main and composer; that band displays Search and exactly one live palette button, with zero inert topbar-control placeholders.",
          "newAssertion": "Sidebar, column, main and composer remain required; jf-sv3-topbar and a directly queried palette glyph must be absent. The catalog/message-bundle Search label and visible/accessibly named frame-switcher assertions remain; the duplicate band-title assertion is deleted.",
          "reason": "Delegated choice 1 removes the entire second band and its glyph. R9/H3 place area names in the frame centre, so retaining the real-catalog switcher name while dropping the duplicate title is the specified product change, not an output-derived snapshot."
        },
        {
          "oldAssertion": "The no-extra-scrollbar/style checks and exactly-one-scroller traversal include Sv3Topbar/jf-sv3-topbar among rendered regions.",
          "newAssertion": "The style constructor and rendered-region loops exclude only that deleted band; SearchV3View, sidebar and composer remain in the style check, and sidebar/main/composer remain in the rendered scroller count. The original scroller and recovery assertions remain.",
          "reason": "With the band retired under delegated choice 1, it is no longer a rendered region to traverse. The other region observations and their expected counts stay intact."
        }
      ],
      "checkLimits": "The deleted band-title and one-live/zero-inert-band-button assertions have no equivalent assertions for that retired component; absence of the band replaces their product purpose. The glyph absence selector on the view does not descend arbitrary shadow roots, so it is not proof that no glyph can exist anywhere. Loops no longer cover Sv3Topbar styles because its production file is deleted. Existing registered/saved Search component vocabulary is not renamed by this test diff."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.paletteActions.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R9",
          "scenarioName": "rule 9",
          "quote": "The top bar has three places with one job each. Left: the name, quiet. Centre: the three areas, words only. Right: status, one menu, the window buttons. It is 44 px high and sits on the page's own background. Status is a small dot while everything is fine and turns into words when something is happening or needs you. Settings is the first entry of the menu, not a second button.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "H3",
          "scenarioName": "handover slice 3",
          "quote": "Top bar (fm-01, fm-15, fm-17; critique B5, B6). 44 px, on the page background. Left the name, centre the three areas in words, right status, one menu, window buttons. Status is a dot while all is fine and becomes words when something runs or needs the user. Settings is the first entry of the menu; the gear button and the switcher icons go.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Search's own title band and command glyph are removed (one bar); Ctrl+K stays.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none in docs/decisions for top bar/topbar, Settings gear/button/menu, More menu, status pill/dot, palette, 44px/56px/3.5rem, sv3-topbar, one bar, command glyph and switcher icons. Matches in ADR-0008 concern settings persistence, ADR-0026 CI contrast measurement, and ADR-0050 GPU status, not these superseded presentation or entry-path expectations. Historical test names and comments describe the previous contract. Private task records were not read; the supplied adoption and delegation provenance must be checked by the acceptor.",
      "assertionChanges": [
        {
          "oldAssertion": "Pressing the Search band glyph must produce exactly [shell.toggle-palette] in the command observer.",
          "newAssertion": "The mounted Search view must contain neither jf-sv3-topbar nor a directly queried sv3-topbar-palette control. The click/command observer, try/finally cleanup and now-unused observer import are removed. Existing working-command registration, retired-command absence and one-owning-Search-window/lifecycle assertions remain.",
          "reason": "Delegated choice 1 removes the glyph and preserves the app palette keyboard path. Its removal changes the correct expectation to absence; a synthetic click on a removed control would preserve an obsolete contract."
        }
      ],
      "checkLimits": "The exact-once click-to-toggle assertion is deleted with its control and has no equivalent click replacement in this file. Shell.palette.test.ts retains exact-once shell.toggle-palette assertions for Ctrl+K across Search and the other areas, and the changed Escape/focus test covers Search's retained keyboard route. The absence selector does not pierce arbitrary nested shadow roots."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-tokens.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R9",
          "scenarioName": "rule 9",
          "quote": "The top bar has three places with one job each. Left: the name, quiet. Centre: the three areas, words only. Right: status, one menu, the window buttons. It is 44 px high and sits on the page's own background. Status is a small dot while everything is fine and turns into words when something is happening or needs you. Settings is the first entry of the menu, not a second button.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "H3",
          "scenarioName": "handover slice 3",
          "quote": "Top bar (fm-01, fm-15, fm-17; critique B5, B6). 44 px, on the page background. Left the name, centre the three areas in words, right status, one menu, window buttons. Status is a dot while all is fine and becomes words when something runs or needs the user. Settings is the first entry of the menu; the gear button and the switcher icons go.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Search's own title band and command glyph are removed (one bar); Ctrl+K stays.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none in docs/decisions for top bar/topbar, Settings gear/button/menu, More menu, status pill/dot, palette, 44px/56px/3.5rem, sv3-topbar, one bar, command glyph and switcher icons. Matches in ADR-0008 concern settings persistence, ADR-0026 CI contrast measurement, and ADR-0050 GPU status, not these superseded presentation or entry-path expectations. Historical test names and comments describe the previous contract. Private task records were not read; the supplied adoption and delegation provenance must be checked by the acceptor.",
      "assertionChanges": [
        {
          "oldAssertion": "The geometry table requires --workspace-topbar-height: 52px. Two Sv3Topbar tests pin height/min-height from that token, no literal 52px, control-radius/hover tokens, an inside border rule, no margin and no max-height shrinkage.",
          "newAssertion": "The 52px band-token table entry, Sv3Topbar import and both component-specific test bodies are deleted. The remaining token and region geometry assertions stay; no replacement 52px or retired-component decoration assertions are added.",
          "reason": "Delegated choice 1 deletes the Search title band rather than restyling it. The source authorizes retiring this component's token/height/control/border rules. R9/H3 instead specify the remaining frame as the one 44px bar."
        },
        {
          "oldAssertion": "The hero composer must use inset: var(--workspace-topbar-height) 0 0 0 below the old band.",
          "newAssertion": "Its style text must contain inset: 0; and still must not contain 52px. Other composer control-token checks remain.",
          "reason": "The one-bar decision leaves no inner title-band reservation. Zero inset expresses the independently specified full Search content region; retaining the old offset would leave space for a deleted band."
        }
      ],
      "checkLimits": "Deleted without equivalent component replacements: the old band-token requirement and both topbar height/decoration test bodies. That removal is deliberate because Sv3Topbar.ts is deleted. TopBar.singleBar.test.ts checks the replacement frame token is 2.75rem and the composer no longer references the old token, but it does not reproduce rendered border/box/hover checks for the remaining frame. Dropping the old token-table entry alone does not assert that the old token is absent everywhere. The inset substring check does not independently prove computed layout."
    },
    {
      "items": [
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "R9",
          "scenarioName": "rule 9",
          "quote": "The top bar has three places with one job each. Left: the name, quiet. Centre: the three areas, words only. Right: status, one menu, the window buttons. It is 44 px high and sits on the page's own background. Status is a small dot while everything is fine and turns into words when something is happening or needs you. Settings is the first entry of the menu, not a second button.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261113-uicritique",
          "scenario": "H3",
          "scenarioName": "handover slice 3",
          "quote": "Top bar (fm-01, fm-15, fm-17; critique B5, B6). 44 px, on the page background. Left the name, centre the three areas in words, right status, one menu, window buttons. Status is a dot while all is fine and becomes words when something runs or needs the user. Settings is the first entry of the menu; the gear button and the switcher icons go.",
          "location": "task t-20261113-uicritique user turn round 7",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none in docs/decisions for top bar/topbar, Settings gear/button/menu, More menu, status pill/dot, palette, 44px/56px/3.5rem, sv3-topbar, one bar, command glyph and switcher icons. Matches in ADR-0008 concern settings persistence, ADR-0026 CI contrast measurement, and ADR-0050 GPU status, not these superseded presentation or entry-path expectations. Historical test names and comments describe the previous contract. Private task records were not read; the supplied adoption and delegation provenance must be checked by the acceptor.",
      "assertionChanges": [
        {
          "oldAssertion": "None: the start-up frame's bar had no test of its own contents.",
          "newAssertion": "Added: for light and dark start-up at 1024 and 600 px, the start-up bar is exactly 44 px, shows the three area words Search, Library, AI Brain (no icons, no gear), shows Starting⋯ on the right, has the same background as the start-up page, and the area words sit inside the bar and centred within 1 px of the room left of the window-control reserve.",
          "reason": "R9/H3: the bar is 44 px on the page's own background with the three areas in words; the start-up frame is the first bar a user sees, and the packaged-app check found it still drawing the old gear and icons."
        },
        {
          "oldAssertion": "topInteractive samples every visible, non-inert interactive box whose top lies above root font size multiplied by 3.5, the old 56px-at-default frame band.",
          "newAssertion": "The helper samples boxes starting above the fixed literal 44 px boundary required by R9/H3. Its selector, visibility/inert filters and shadow traversal stay unchanged, as do drag exclusion and no-overlap/inside-window assertions across existing states, widths and text scales. The cutoff is independent of window-control geometry.",
          "reason": "R9/H3 specify the replacement bar as 44 px. A fixed requirement-derived boundary keeps controls misplaced within that bar in the sample even if the window-control layer is shorter or misplaced; the rejected geometry-derived cutoff could exclude them."
        }
      ],
      "checkLimits": "Controls whose top is 44 px or greater are outside this helper sample, including the strip from 44 px to the old 3.5rem threshold. That narrowing follows the required bar boundary rather than live control placement. The helper does not assert the computed bar height itself. Existing non-frame recovery-action and native-control checks remain."
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1d88a-3aa3-7ac1-9320-b3d64377b0b8",
  "verdict": "reject",
  "digest": "sha256:a5acee50b414b346bd97077004e61d21b5b0f4e4aa040efb2ce20071da934755",
  "note": "Unsupported weakening: Shell.engineLoss-lockdown tests Menu instead of Settings reachability; boot-frame-lockdown derives its sampling cutoff from current window-control geometry, allowing controls inside the required 44px bar to escape checks."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "codex-verifier-fm-top-bar-8mrrdw5k",
  "verdict": "reject",
  "digest": "sha256:effb199d04c103a2a32f3ff351ad311178704cb2c8766eb4c04396e2c0daad92",
  "note": "Unsupported weakening: StatusPill.design.test.ts replaces the calm dot glyph assertion with empty symbol text without any assertion that a visible dot remains. R9/H3 still require a calm dot; delegated choice 3 preserves that obligation, and StatusPill.calmDot.test.ts supplies no replacement drawing assertion."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "codex-verifier-fm-top-bar-02zv5a59",
  "verdict": "accept",
  "digest": "sha256:2a80c111f17bd13d7448036147f02da480b496452c08a2de3e06e1fcdccf5a3b"
}
```
