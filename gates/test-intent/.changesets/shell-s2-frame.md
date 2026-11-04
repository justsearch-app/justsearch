---
schema: test-intent.v1
task: t-20261103-shell
author-role: builder
author-session: dc98670b-ac94-47e4-9c7a-a63ca9082925
---

# Frame (S2): one top bar replaces the title bar and the rail; Back and Forward run on browser history

The slice replaces the title bar and the left rail with one top bar (`chrome/TopBar.ts`: brand,
switcher, status pill, Settings, More menu), moves Back and Forward onto browser history (the
navigation journal follows it), puts the window tab and the Settings section into the address, maps
saved layouts onto the frame, and fixes the status limits L2, L3, L4a and L4b. The rail
(`Shell.ts#Rail`), the recents menu (`components/RecentsMenu.ts`), the bookmarks list
(`components/BookmarksPopover.ts`), Copy URL, the bookmark toggle and its Ctrl+D chord, and the
Simple/Detailed toggle leave the frame (FR4); the retired element names stay in the presentation
vocabulary as inert definitions (`chrome/RetiredFrameElements.ts`).

Repaired behaviour fails before on a07eb770d6 with assertion failures
(`gates/test-intent/evidence/shell-s2-frame-repair.json`): the three FR3 defects (a), (b), (c), L2,
L3, L4a, L4b and the C12 line L4b changes. Defect (c) as briefed (address on Library, stage on
Search) did not reproduce on a07eb770d6 in Chromium or happy-dom; its check fails before on the
duplicate entry an already shown area stacked. The journal drift seen in Chromium on the baseline
with the real Search window and the `sv3-sources` fixtures (each browser Back to the conversation
appended a journal entry) needs that window; with a probe window the baseline already restores the
cursor, so the conversation variant of (c) is recorded as New without a fail-before. New behaviour
that only exists in new modules (top bar, F6 regions, tab and section in the address, Ctrl+1/2/3) is
recorded without a fail-before (`gates/test-intent/evidence/shell-s2-frame-new.json`).

Frame repair (after independent checks on 6fe76d255f) fails before on 6fe76d255f with assertion
failures (`gates/test-intent/evidence/shell-s2-frame-repair-2.json`), most of them in a real browser
(`Shell.frame-lockdown.test.ts`, the lockdown Vitest selection, headless Chromium over a private
Vite server): the L3 notice at 600×400, Settings closing once after Back/Forward and after a
reload, the Settings section in the address against the one Settings starts on, Alt+Left/Right from
the Search text box, a window tab as a history step (orchestrator decision: a tab change adds an
entry; Alt+arrows, mouse buttons and browser buttons land on the same views), one entry per palette
navigation, the selected tab in forced colours, and Settings › Layout following the switcher's
order and words. Settings' remembered section and the navigation journal keep their stored formats;
the Settings history entry additionally records the area it opened over (`settingsOver` in the
entry's history state).

Orchestrator repair on 8a6eb41201 (macOS): Option+Left/Right move the caret by word in a text
field, so there the app leaves the chord to the text; it fails before on 8a6eb41201
(`gates/test-intent/evidence/shell-s2-frame-repair-3.json`). The Settings nav anchor for the
layout's area list reads "Switcher" (backend message `settings.section.rail`).

Frame repair on 38c6e7027f fails before on 38c6e7027f with assertion failures
(`gates/test-intent/evidence/shell-s2-frame-repair-4.json`), the layout and colour cases in a real
browser (`Shell.frame-lockdown.test.ts`): L2 a plugin element wider than the status panel (the row
now contains it in a clipping body whose tooltip carries its words), C1 window addresses the id
grammar rejects (`vendor.nope-window`, a bare id), which the URL and deep-link sources dropped
silently and now report as the same unknown-destination notice, C9 Alt+Left/Right with Settings as
the only modal, FR3 the window tab under Settings after a reload (the Settings history entry also
records the tab it opened over, `settingsOverTab`, next to `settingsOver`), X1 the selected tab's
label in forced colours (the theme's text colour outranked CanvasText), X1 the inline margin in
Settings › Layout, and L4b's ledger path (the GATED row of a waiting MCP request; the ledger's gate
facet now carries the raw `operationId`). The Settings search reads the Layout anchor's label from
the same backend message as the nav (`settings.section.rail`); it has no copy of its own.

`Shell.navigation.test.ts` restores the HTML session-history rule for `replaceState` in its own run:
happy-dom 21 drops the entries after the current one on `replaceState`, which Chromium, WebView2 and
WebKit do not, and Forward is only testable under the spec's behaviour.

## Old test → new owner

| Old test (group) | New test | Kept / retired |
| --- | --- | --- |
| Rail.test "bands DIAGNOSTIC surfaces…", "keeps the TRUST Activity surface first-class…", "renders no diagnostics band…", "§6 DERIVES TRUST position…" | — (System's own tab row keeps per-tab altitude framing: SurfaceTabs.test) | retired with the rail (FR1): diagnostics live in System (More › System), not in a band of the frame |
| Rail.test "defaults to collapsed…", "renders a text label beside each icon when expanded", "the expand toggle is a keyboard-accessible native button…" | TopBar.test "renders the brand, three areas…", "tooltips show the shortcuts…" | expand/collapse retired with the rail (FR1); the switcher always names its areas (label, accessible name, tooltip) |
| ShellRail.test (slice 472 visibility and order, 5 cases) | ShellAreas.test (same 5 cases, same assertions on `shell.surfaces`) | kept (rename) |
| ShellRail.test "renders exactly ONE settings button — the pinned affordance…" | ShellAreas.test "renders exactly ONE settings button — the top bar's gear…" | kept: one Settings control, aria-haspopup=dialog; now also asserts the More menu offers no second one |
| ShellRail.test "Simple mode hides System from the rail but keeps AI Brain…" | ShellAreas.test "Simple mode: System is a More-menu row, not an area…" | the Simple-mode trim retired (System is a More row in every detail level); AI Brain stays an area, Settings never a list entry, Theme Editor (DEEPLINK) never listed: kept |
| ShellRail.test "Detailed mode restores System…" | ShellAreas.test "Detailed mode: System is the same More-menu row…" | kept at the More menu |
| RecentsMenu.test (trail list, jump to an entry) | — | retired with the recents menu (FR4); the journal data stays stored and resumes the last view (Shell.navigation "FR6 / C11 … pre-change journal resumes the last view") |
| Shell.test "Tempdoc 738 — the topbar Simple/Detailed toggle reflects the live uiMode and drives it on click" | SettingsSurface.ui-mode.test (existing) | the title-bar toggle retired (FR4); Settings › Detail level keeps the live reflection and the shared queue |
| Shell.test "923 review — topbar persistence joins the shared cross-control mode queue" | SettingsSurface.ui-mode.test "queues its mode POST behind an earlier mode write from another projection" (existing) | as above |
| Shell.test "C1 C2 pending conversation claims precede a surface navigation…" | same test: Back is Alt+Left (the Go back button retired) | kept; every assertion unchanged |
| Shell.globalKeys.test Ctrl+D cases (select, button, IME/repeat, modal, shadow input) | same tests on Alt+Left/Right (typing guard) and Ctrl+2 / F6 / F1 (IME, repeat, modal) | Ctrl+D retired with the bookmark toggle (FR4); every guard stays pinned on a live chord |
| SearchV3View.test "W7 renders Search in the title band and rail…" (rail button label, name, title) | same test renamed "…the top bar switcher…": segment label, accessible name, tooltip "Search · Ctrl+1" | kept at the switcher; tooltip gains the shortcut (FR1) |
| HelpSurface.test "lists only shortcuts that actually fire…" (Ctrl + D row present) | same test: Ctrl + D row absent; Ctrl/⌘ + 1/2/3, F6/Shift + F6, F1 rows present | kept: the table mirrors the real bindings |
| frameStatus.test "C12 the broadcast advisory and the request-scoped prompt…" (waiting advisory in Earlier with no actions) | same test: not in Earlier while waiting; frameStatus "L4b …" pins the answered/expired/withdrawn rows and their inert actions | changed by L4b |
| Shell.globalKeys.test "leaves a chord alone while a <select> has focus…", "…while a shadow-nested <input> has focus" | Shell.globalKeys.test "FR3: Alt+Left/Right are claimed in a <select>, a shadow <input> and a <textarea>…"; Shell.frame-lockdown "Back into Search puts focus in its text box…" | expectation reversed (frame repair item 3): Alt+Left/Right navigate from every control |
| Shell.navigation.test "a tab click names the tab in the address without a new entry…" | Shell.navigation.test "a tab click names the tab in the address as a new entry…"; Shell.frame-lockdown "Library › Browse adds an entry…" | expectation reversed (orchestrator decision, item 4); address, Back/Forward and reload assertions kept |
| SettingsSurface.v15.test "lists rail surfaces in catalog order by default", "respects userConfig.surfaceOrder" | same file: "lists the switcher areas in the switcher order…", "respects userConfig.surfaceOrder for the More windows…" | changed by item 8: the list mirrors the top bar |
| SettingsSurface.v15.test "hidden surfaces have visibility=false…", "move-down arrow on first surface…" | same file, on a fixture of the real areas plus two More windows | kept (input re-encoding); the move-down case adds "areas have no arrows" |
| admissionFetch.test UPGRADE_PREPARING fixture (no preparationId) | same tests with the real upgrade body (`preparationId`); admissionFetch "L4a …" pins the closing engine | kept (input re-encoding); L4a adds the closing case |

```json
{
  "entries": [
    {
      "items": ["modules/ui-web/src/shell-v0/chrome/Rail.test.ts"],
      "class": "Obsolete",
      "removedChecks": "All seven checks of the rail element: altitude bands (DIAGNOSTIC band, TRUST adjacency, no band without DIAGNOSTIC surfaces, derived TRUST position) and the collapsed/expanded chrome with its expand toggle. The rail is removed; areas are the top bar's switcher (TopBar.test) and diagnostics are System's tabs.",
      "sources": [
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "FR1",
          "quote": "Top bar replaces title bar and rail: brand, switcher (Search, Library, AI Brain), status pill, Settings, More (⋯).",
          "label": "agent-drafted, owner-adopted" },
        { "kind": "removal", "removed": ["modules/ui-web/src/shell-v0/chrome/Shell.ts#Rail"] }
      ],
      "conflictSearch": "decision records searched for a conflict: none — docs/decisions/*.md (54 records) searched for rail, title bar, topbar, bookmark, Copy URL, Simple/Detailed, Detail level, navigation journal, back/forward, history.back, recents; the only hit (0006, 'abstain rail') is about citation scoring"
    },
    {
      "items": ["modules/ui-web/src/shell-v0/components/RecentsMenu.test.ts"],
      "class": "Obsolete",
      "removedChecks": "All checks of the title bar's recents menu (the journal trail list and jumping to an entry). The menu is removed from the frame; the journal it read stays stored and is pinned by Shell.navigation (FR6 / C11).",
      "sources": [
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "FR4",
          "quote": "Removed from everyday chrome: Copy URL, bookmarks button and list, recents menu, Simple/Detailed toggle in the title bar, backend address.",
          "label": "agent-drafted, owner-adopted" },
        { "kind": "removal", "removed": ["modules/ui-web/src/shell-v0/components/RecentsMenu.ts"] }
      ],
      "conflictSearch": "decision records searched for a conflict: none — docs/decisions/*.md (54 records) searched for recents, navigation journal, title bar, rail"
    },
    {
      "items": ["modules/ui-web/src/shell-v0/chrome/ShellRail.test.ts", "modules/ui-web/src/shell-v0/chrome/ShellAreas.test.ts"],
      "class": "Adaptation",
      "moved": ["rename", "instrumentation"],
      "whatMoved": "The file is renamed (the rail is gone; its windows are the switcher and the More menu). The five slice-472 visibility/order checks are unchanged (same inputs, same assertions on `shell.surfaces`). The 855 S1 single-Settings check observes the top bar instead of the rail (helper `topBarButtonsFor` replaces `railButtonsFor`); its oracle (exactly one control, aria-haspopup=dialog) is unchanged and it additionally asserts the More menu offers no second one.",
      "sources": [
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "FR1",
          "quote": "Top bar replaces title bar and rail: brand, switcher (Search, Library, AI Brain), status pill, Settings, More (⋯).",
          "label": "agent-drafted, owner-adopted" }
      ]
    },
    {
      "items": ["modules/ui-web/src/shell-v0/chrome/ShellRail.test.ts"],
      "class": "Obsolete",
      "removedChecks": "'Simple mode hides System from the rail…': the expectation that System is absent in Simple mode. System is no switcher area and is a fixed More-menu row in every detail level; the Simple-mode trim is removed with the rail.",
      "sources": [
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "FR1",
          "quote": "Top bar replaces title bar and rail: brand, switcher (Search, Library, AI Brain), status pill, Settings, More (⋯).",
          "label": "agent-drafted, owner-adopted" },
        { "kind": "removal", "removed": ["modules/ui-web/src/shell-v0/chrome/Shell.ts#hiddenInSimple"] }
      ],
      "conflictSearch": "decision records searched for a conflict: none — docs/decisions/*.md (54 records) searched for Simple mode, Detailed, System, rail"
    },
    {
      "items": ["modules/ui-web/src/shell-v0/chrome/ShellAreas.test.ts"],
      "class": "New",
      "behaviour": "System is reachable from More › System in Simple and Detailed modes and is never a switcher area; AI Brain stays an area; Settings is never a window-list entry.",
      "sources": [
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "FR1",
          "quote": "Top bar replaces title bar and rail: brand, switcher (Search, Library, AI Brain), status pill, Settings, More (⋯).",
          "label": "agent-drafted, owner-adopted" }
      ],
      "evidence": "gates/test-intent/evidence/shell-s2-frame-new.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/ShellAreas.test.ts#renders exactly ONE settings button — the top bar's gear, never a catalog slot (855 S1)",
        "modules/ui-web/src/shell-v0/chrome/ShellAreas.test.ts#Simple mode: System is a More-menu row, not an area; AI Brain stays an area (Theme Editor is DEEPLINK — never listed)",
        "modules/ui-web/src/shell-v0/chrome/ShellAreas.test.ts#Detailed mode: System is the same More-menu row (Theme Editor stays unlisted — it is DEEPLINK, not RAIL)"
      ]
    },
    {
      "items": ["modules/ui-web/src/shell-v0/chrome/Shell.test.ts"],
      "class": "Obsolete",
      "removedChecks": "'Tempdoc 738 — the topbar Simple/Detailed toggle reflects the live uiMode and drives it on click' and '923 review — topbar persistence joins the shared cross-control mode queue': both pinned the title bar's toggle. The remaining control (Settings › Detail level) keeps both behaviours under SettingsSurface.ui-mode.test.",
      "sources": [
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "FR4",
          "quote": "Removed from everyday chrome: Copy URL, bookmarks button and list, recents menu, Simple/Detailed toggle in the title bar, backend address.",
          "label": "agent-drafted, owner-adopted" },
        { "kind": "removal", "removed": ["modules/ui-web/src/shell-v0/chrome/Shell.ts#renderUiModeToggle", "modules/ui-web/src/shell-v0/chrome/Shell.ts#handleUiModeSelect"] }
      ],
      "conflictSearch": "decision records searched for a conflict: none — docs/decisions/*.md (54 records) searched for Simple/Detailed, Detail level, uiMode, title bar"
    },
    {
      "items": ["modules/ui-web/src/shell-v0/chrome/Shell.test.ts"],
      "class": "Adaptation",
      "moved": ["input re-encoding"],
      "whatMoved": "'C1 C2 pending conversation claims precede a surface navigation…': Back is pressed as Alt+Left (document keydown) instead of clicking the retired title-bar Go back control, and the timer advance after it is 50 ms instead of 0 because Back now runs through browser history and popstate. Inputs, preconditions and all assertions (journal URLs and cursor, active surface, conversation in the address) are unchanged. Unused imports of the removed toggle tests are dropped.",
      "sources": [
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "FR3",
          "quote": "Back and forward (Alt+Left/Right, mouse buttons) work reliably: same-surface navigation keeps the earlier entry; Back then Forward returns to the later view; browser history and the stage stay in step.",
          "label": "agent-drafted, owner-adopted" }
      ]
    },
    {
      "items": ["modules/ui-web/src/shell-v0/chrome/Shell.globalKeys.test.ts"],
      "class": "Obsolete",
      "removedChecks": "The Ctrl+D (bookmark toggle) assertions: Ctrl+D unclaimed in a <select> and a shadow <input>, claimed on a button, ignored while composing/repeating and under a modal. The chord is removed with the bookmark toggle; every guard stays pinned on a live chord (the Adaptation entry).",
      "sources": [
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "FR4",
          "quote": "Removed from everyday chrome: Copy URL, bookmarks button and list, recents menu, Simple/Detailed toggle in the title bar, backend address.",
          "label": "agent-drafted, owner-adopted" },
        { "kind": "removal", "removed": ["modules/ui-web/src/shell-v0/chrome/Shell.ts#handleBookmarkToggle"] }
      ],
      "conflictSearch": "decision records searched for a conflict: none — docs/decisions/*.md (54 records) searched for bookmark, Ctrl+D, saved view"
    },
    {
      "items": ["modules/ui-web/src/shell-v0/chrome/Shell.globalKeys.test.ts"],
      "class": "Adaptation",
      "moved": ["input re-encoding"],
      "whatMoved": "The IME/repeat and modal cases press Ctrl+2, F6, F1 and Alt+Left instead of Ctrl+D; the oracle (stands down while composing, repeating or under a modal; claimed otherwise) is unchanged, and the modal case also presses Alt+Left. The button case keeps Alt+Left and adds Alt+Right as the claimed chord. The file header names the current chords and their guards.",
      "sources": [
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "C8",
          "quote": "Keyboard ownership: new shortcuts (Ctrl+1/2/3, F6) never fire inside text fields where they would edit text, during IME composition, on key repeat, or under another modal.",
          "label": "agent-drafted, owner-adopted" }
      ]
    },
    {
      "items": ["modules/ui-web/src/shell-v0/chrome/Shell.globalKeys.test.ts"],
      "class": "New",
      "behaviour": "Ctrl+1/2/3, F6 and Alt+Left/Right work from a text field (they do not edit text); AltGr (Ctrl+Alt) and Ctrl+Shift digit combinations are left to typing; Ctrl+D is no longer taken; Ctrl+2, F6, F1 and Alt+Left stand down during IME composition, on key repeat and under a modal.",
      "sources": [
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "C8",
          "quote": "Keyboard ownership: new shortcuts (Ctrl+1/2/3, F6) never fire inside text fields where they would edit text, during IME composition, on key repeat, or under another modal.",
          "label": "agent-drafted, owner-adopted" }
      ],
      "evidence": "gates/test-intent/evidence/shell-s2-frame-new.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.globalKeys.test.ts#C8: area keys and F6 work from a text field; an AltGr combination is left to typing",
        "modules/ui-web/src/shell-v0/chrome/Shell.globalKeys.test.ts#864: ignores an IME-composing chord and an auto-repeat chord",
        "modules/ui-web/src/shell-v0/chrome/Shell.globalKeys.test.ts#864: stands down while a modal owns the keyboard",
        "modules/ui-web/src/shell-v0/chrome/Shell.globalKeys.test.ts#still claims the same chord when focus is on a control the reader does NOT type into"
      ]
    },
    {
      "items": ["modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.test.ts"],
      "class": "Obsolete",
      "removedChecks": "The rail half of 'W7 renders Search in the title band and rail…': the rail button's `.rail-label` text and its title 'Search'. The rail is removed; the same label, accessible name and tooltip are asserted on the switcher segment (the New entry).",
      "sources": [
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "FR1",
          "quote": "Top bar replaces title bar and rail: brand, switcher (Search, Library, AI Brain), status pill, Settings, More (⋯).",
          "label": "agent-drafted, owner-adopted" },
        { "kind": "removal", "removed": ["modules/ui-web/src/shell-v0/chrome/Shell.ts#Rail"] }
      ],
      "conflictSearch": "decision records searched for a conflict: none — docs/decisions/*.md (54 records) searched for rail, title bar, switcher"
    },
    {
      "items": ["modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.test.ts"],
      "class": "New",
      "behaviour": "Search's switcher segment carries the catalog label 'Search' as text and accessible name, and its tooltip names the shortcut ('Search · Ctrl+1').",
      "sources": [
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "FR1",
          "quote": "Ctrl+1/2/3 switch areas; tooltips show the shortcuts.",
          "label": "agent-drafted, owner-adopted" }
      ],
      "evidence": "gates/test-intent/evidence/shell-s2-frame-new.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.test.ts#W7 renders Search in the title band and the top bar switcher using the real catalog message bundle"
      ]
    },
    {
      "items": ["modules/ui-web/src/shell-v0/views/HelpSurface.test.ts"],
      "class": "New",
      "behaviour": "The Help shortcut table mirrors the real bindings: no Ctrl + D row (the chord is retired with the bookmark toggle); rows for Ctrl/⌘ + 1/2/3, F6/Shift + F6 and F1; Alt + ←/→ stays.",
      "sources": [
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "FR4",
          "quote": "Removed from everyday chrome: Copy URL, bookmarks button and list, recents menu, Simple/Detailed toggle in the title bar, backend address.",
          "label": "agent-drafted, owner-adopted" },
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "FR8",
          "quote": "Keyboard and screen reader: landmarks (banner, navigation, main), F6 cycles top bar → window tabs → content → details pane; the pill has a name that states its state.",
          "label": "agent-drafted, owner-adopted" }
      ],
      "evidence": "gates/test-intent/evidence/shell-s2-frame-new.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/HelpSurface.test.ts#lists only shortcuts that actually fire; the defunct `/` and `??` rows are gone"
      ]
    },
    {
      "items": ["modules/ui-web/src/shell-v0/state/frameStatus.test.ts"],
      "class": "New",
      "repaired": true,
      "behaviour": "L4b: a waiting approval appears only in Needs you; its advisory joins Earlier (unread, inert) once the request is answered, expired or withdrawn; another waiting request does not hold it back. C12 keeps one decision route.",
      "sources": [
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "L4b",
          "quote": "Expected: a needs-you item appears only in Needs you while it waits; it enters Earlier once answered, expired or withdrawn.",
          "label": "agent-drafted, owner-adopted" }
      ],
      "evidence": "gates/test-intent/evidence/shell-s2-frame-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts#L4b a waiting approval is in Needs you only; it enters Earlier once answered, expired or withdrawn",
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts#C12 the broadcast advisory and the request-scoped prompt for one authorization give one decision route"
      ]
    },
    {
      "items": ["modules/ui-web/src/api/admissionFetch.test.ts"],
      "class": "Adaptation",
      "moved": ["input re-encoding"],
      "whatMoved": "The UPGRADE_PREPARING refusal fixture carries the `preparationId` the real upgrade refusal carries (ApiSecurityFilters writes it); the three it.each cases and their assertions are unchanged.",
      "sources": [
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "L4a",
          "quote": "Expected: no update announcement unless an update is actually being prepared.",
          "label": "agent-drafted, owner-adopted" }
      ]
    },
    {
      "items": ["modules/ui-web/src/api/admissionFetch.test.ts"],
      "class": "New",
      "repaired": true,
      "behaviour": "L4a: a refusal from an engine that is closing (no upgrade preparation) still waits and retries but announces nothing; only a refusal naming an upgrade preparation says JustSearch is preparing an update.",
      "sources": [
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "L4a",
          "quote": "Expected: no update announcement unless an update is actually being prepared.",
          "label": "agent-drafted, owner-adopted" }
      ],
      "evidence": "gates/test-intent/evidence/shell-s2-frame-repair.json",
      "newChecks": [
        "modules/ui-web/src/api/admissionFetch.test.ts#L4a a closing engine (no upgrade preparation) waits and retries without announcing an update"
      ]
    },
    {
      "items": ["modules/ui-web/src/api/admissionFetch.test.ts"],
      "class": "New",
      "behaviour": "L4a: a refusal that names an upgrade preparation announces 'JustSearch is preparing an update. Waiting to reconnect.'",
      "sources": [
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "L4a",
          "quote": "Expected: no update announcement unless an update is actually being prepared.",
          "label": "agent-drafted, owner-adopted" }
      ],
      "evidence": "gates/test-intent/evidence/shell-s2-frame-new.json",
      "newChecks": [
        "modules/ui-web/src/api/admissionFetch.test.ts#L4a only a refusal naming an upgrade preparation says an update is being prepared"
      ]
    },
    {
      "items": ["modules/ui-web/src/shell-v0/components/StatusPanel.plugins.test.ts"],
      "class": "New",
      "repaired": true,
      "behaviour": "L2: a single plugin status item wider than the panel's plugin row shows directly with its provenance and no '… 1 more'; with more items than fit the first shows and '… N more' counts only the rest.",
      "sources": [
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "L2",
          "quote": "Expected: one item always shows directly (wrapping or truncating with its full text available); \"… N more\" only when there are more items than fit, and never hides the only item. Provenance stays visible.",
          "label": "agent-drafted, owner-adopted" }
      ],
      "evidence": "gates/test-intent/evidence/shell-s2-frame-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/StatusPanel.plugins.test.ts#L2 a single item wider than the row shows directly, with its provenance, and no \"… 1 more\"",
        "modules/ui-web/src/shell-v0/components/StatusPanel.plugins.test.ts#L2 with more items than fit, the first shows and \"… N more\" counts only the rest"
      ]
    },
    {
      "items": ["modules/ui-web/src/shell-v0/chrome/Shell.navigation.test.ts"],
      "class": "New",
      "repaired": true,
      "behaviour": "FR3 defects: (a) an incoming same-window address keeps the earlier conversation and Back returns to it; (b) Back then Forward after Search → Library returns to Library and the browser's own Forward agrees; (c) browser Back/Forward keep stage, address and journal in step and re-choosing the shown area adds no entry.",
      "sources": [
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "FR3",
          "quote": "Back and forward (Alt+Left/Right, mouse buttons) work reliably: same-surface navigation keeps the earlier entry; Back then Forward returns to the later view; browser history and the stage stay in step.",
          "label": "agent-drafted, owner-adopted" }
      ],
      "evidence": "gates/test-intent/evidence/shell-s2-frame-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.navigation.test.ts#(a) an incoming same-window address (conversation B while A is shown) keeps A: Back returns to A",
        "modules/ui-web/src/shell-v0/chrome/Shell.navigation.test.ts#(b) Back then Forward after Search → Library returns to Library, and browser history agrees",
        "modules/ui-web/src/shell-v0/chrome/Shell.navigation.test.ts#(c) Library → Search → Library by area clicks, then browser Back and Forward: stage, address and journal stay in step"
      ]
    },
    {
      "items": ["modules/ui-web/src/shell-v0/chrome/Shell.navigation.test.ts"],
      "class": "New",
      "behaviour": "Back stays in the app on its first view; mouse buttons 3/4 are Back/Forward; rapid area switching, a late boot response and reload mid-navigation keep the reader's view and Back step; the window tab and the Settings section are part of the address and survive Back/Forward and reload (a tab change is a new entry, a section change replaces); deep links to every window, a member id and retired aliases land on the right window and tab; Settings closes once onto its area by Escape or browser Back and a deep link arriving while it is open closes it; a pre-change journal and userState load and a userState write keeps every co-resident value.",
      "sources": [
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "FR3",
          "quote": "The window tab and Settings section are part of the address.",
          "label": "agent-drafted, owner-adopted" },
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "FR6",
          "quote": "Deep links (justsearch://surface/<id>, member ids, retired aliases), desktop deep links and backend intents land on the right window and tab; saved navigation journal and bookmarks load after upgrade without loss.",
          "label": "agent-drafted, owner-adopted" },
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "C9",
          "quote": "Settings dismissal history: direct Settings entry and section deep links, reload, backdrop, Escape and browser Back/Forward close it once, keep the underlying area, and never need a second Back.",
          "label": "agent-drafted, owner-adopted" },
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "C11",
          "quote": "any userState document change preserves co-resident values (pins, keybindings, plugin settings, recent commands, workspace scopes, bookmarks, viewer audience, chat width).",
          "label": "agent-drafted, owner-adopted" }
      ],
      "evidence": "gates/test-intent/evidence/shell-s2-frame-new.json",
      "newChecks": ["modules/ui-web/src/shell-v0/chrome/Shell.navigation.test.ts"]
    },
    {
      "items": ["modules/ui-web/src/shell-v0/chrome/Shell.frame.test.ts"],
      "class": "New",
      "repaired": true,
      "behaviour": "L3: the unknown-destination notice says \"Couldn't find that window\" (no internal id) and offers 'Choose destination'.",
      "sources": [
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "L3",
          "quote": "Expected: in the new top bar it shows without truncating the pill or wrapping, at all three sizes.",
          "label": "agent-drafted, owner-adopted" }
      ],
      "evidence": "gates/test-intent/evidence/shell-s2-frame-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.frame.test.ts#names what happened in words that fit the top bar and offers the destinations"
      ]
    },
    {
      "items": ["modules/ui-web/src/shell-v0/chrome/Shell.frame.test.ts"],
      "class": "New",
      "behaviour": "The frame is one top bar (banner) with the switcher (navigation) over one main holding the page heading; no rail, recents, bookmarks, Copy URL, Simple/Detailed toggle or backend address; Ctrl+D untaken; the pill sits in the top bar with a name stating its state; Ctrl+1/2/3 and F1; layouts with rail.visible:false hide the switcher while Ctrl+2 and the palette still reach the areas, statusBar.visible:false hides plugin status only, split keeps the second pane and picker, rail.surfaces limits the lists; a plugin window is listed under More and mounts with host injection; a surface override mounts and the provenance badge names it; the Developer group follows the developer view tier; About opens Settings › Desktop; Copy link copies the address; F6/Shift+F6 cycle top bar → window tabs → content → details pane and skip absent regions.",
      "sources": [
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "FR1",
          "quote": "Top bar replaces title bar and rail: brand, switcher (Search, Library, AI Brain), status pill, Settings, More (⋯). Ctrl+1/2/3 switch areas; tooltips show the shortcuts.",
          "label": "agent-drafted, owner-adopted" },
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "FR4",
          "quote": "Removed from everyday chrome: Copy URL, bookmarks button and list, recents menu, Simple/Detailed toggle in the title bar, backend address.",
          "label": "agent-drafted, owner-adopted" },
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "FR5",
          "quote": "Layout compatibility: a saved layout's `rail.visible:false` hides the switcher (areas stay reachable by palette and Ctrl+1/2/3); `statusBar.visible:false` hides plugin status in the pill, never notices or needs-you; split layouts keep the second window pane with its picker; focus/zen keep working.",
          "label": "agent-drafted, owner-adopted" },
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "FR7",
          "quote": "Plugin windows (catalog contributions) remain reachable (⋯ menu \"More\" group) and mount with host injection as today; surface overrides and provenance badge keep working.",
          "label": "agent-drafted, owner-adopted" },
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "FR8",
          "quote": "Keyboard and screen reader: landmarks (banner, navigation, main), F6 cycles top bar → window tabs → content → details pane; the pill has a name that states its state.",
          "label": "agent-drafted, owner-adopted" }
      ],
      "evidence": "gates/test-intent/evidence/shell-s2-frame-new.json",
      "newChecks": ["modules/ui-web/src/shell-v0/chrome/Shell.frame.test.ts"]
    },
    {
      "items": ["modules/ui-web/src/shell-v0/chrome/TopBar.test.ts"],
      "class": "New",
      "behaviour": "The top bar renders the brand, the three areas in order with the active one current, tooltips and aria-keyshortcuts naming Ctrl+1/2/3, a status slot, Settings and More; each choice reports one selection; one data-surface-id per destination; the More menu holds System, Help & shortcuts (F1), About, plugin windows under More, and the Developer group (API explorer, Copy link, backend address) only when on; menu keyboard (arrows, End, wrap, Escape returns focus) and outside-click close; the minimal bar's area menu; switcherVisible=false hides the switcher.",
      "sources": [
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "FR1",
          "quote": "Top bar replaces title bar and rail: brand, switcher (Search, Library, AI Brain), status pill, Settings, More (⋯). Ctrl+1/2/3 switch areas; tooltips show the shortcuts.",
          "label": "agent-drafted, owner-adopted" },
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "FR2",
          "quote": "at 600×400 a minimal bar (brand dot, active area menu, pill as icon with count, settings, more). Nothing overflows.",
          "label": "agent-drafted, owner-adopted" },
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "FR7",
          "quote": "Plugin windows (catalog contributions) remain reachable (⋯ menu \"More\" group) and mount with host injection as today; surface overrides and provenance badge keep working.",
          "label": "agent-drafted, owner-adopted" }
      ],
      "evidence": "gates/test-intent/evidence/shell-s2-frame-new.json",
      "newChecks": ["modules/ui-web/src/shell-v0/chrome/TopBar.test.ts"]
    },
    {
      "items": ["modules/ui-web/src/shell-v0/views/SettingsSurface.section-address.test.ts"],
      "class": "New",
      "behaviour": "Settings reports the section it shows on mount and when a section is requested, so the frame can put it in the address.",
      "sources": [
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "FR3",
          "quote": "The window tab and Settings section are part of the address.",
          "label": "agent-drafted, owner-adopted" }
      ],
      "evidence": "gates/test-intent/evidence/shell-s2-frame-new.json",
      "newChecks": ["modules/ui-web/src/shell-v0/views/SettingsSurface.section-address.test.ts"]
    },
    {
      "items": ["modules/ui-web/src/shell-v0/chrome/RetiredFrameElements.test.ts"],
      "class": "New",
      "behaviour": "The retired frame elements (jf-rail, jf-recents-menu, jf-bookmarks-popover) stay defined, in the presentation vocabulary and authorable, and mount inert (hidden, no shadow root, no listeners); jf-top-bar is in the vocabulary and reserved from authored presentations.",
      "sources": [
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "C11",
          "quote": "Customization lifecycle and shared storage: apply → profile switch → reload → revert and missing-plugin fallback work for themes, presentations and layouts",
          "label": "agent-drafted, owner-adopted" }
      ],
      "evidence": "gates/test-intent/evidence/shell-s2-frame-new.json",
      "newChecks": ["modules/ui-web/src/shell-v0/chrome/RetiredFrameElements.test.ts"]
    },
    {
      "items": ["modules/ui-web/src/shell-v0/themes/token-names.generated.ts"],
      "class": "Incidental",
      "sources": [
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "X1",
          "quote": "no new hard-coded colours, sizes or z-indexes (ui-web gates).",
          "label": "agent-drafted, owner-adopted" }
      ],
      "assessment": {
        "purpose": "Generated list of design-token names (scripts/ci/gen-token-names.mjs over src/styles/tokens.css). Regenerated after adding the top bar's frame tokens (--frame-topbar-height, --frame-brand-dot, --frame-brand-halo, --frame-control-size, --frame-control-radius, --frame-menu-width) so the bar uses tokens instead of hard-coded sizes.",
        "callersAndStateOwners": "tokens.css is the owner; the generator writes this file; the token-name tests and theme validation read it.",
        "indirectBehaviours": "Additive: six --frame-* names. No existing token name is removed or renamed (--rail-width stays for themes that author it).",
        "evidence": "node scripts/ci/gen-token-names.mjs reports 238 tokens and the file is unchanged by a second run; the ui-web gates (31/31) and the full vitest run pass on the final tree.",
        "unresolvedRisks": "None known."
      }
    },
    {
      "items": ["modules/ui-web/src/shell-v0/chrome/Shell.globalKeys.test.ts"],
      "class": "Obsolete",
      "removedChecks": "'leaves a chord alone while a <select> has focus — the omission 857 closed elsewhere' and 'leaves a chord alone while a shadow-nested <input> has focus': the expectation that Alt+Left/Right are unclaimed while a <select> or a text field has focus. Alt+Left/Right edit no text, and Back lands in the Search composer, where the guard swallowed the Forward that followed (frame repair item 3); they now navigate from every control and stop propagating. No chord of this handler is typing-guarded any more.",
      "sources": [
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "FR3", "quote": "Back and forward (Alt+Left/Right, mouse buttons) work reliably: same-surface navigation keeps the earlier entry; Back then Forward returns to the later view; browser history and the stage stay in step.", "label": "agent-drafted, owner-adopted" },
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "C8", "quote": "Keyboard ownership: new shortcuts (Ctrl+1/2/3, F6) never fire inside text fields where they would edit text, during IME composition, on key repeat, or under another modal.", "label": "agent-drafted, owner-adopted" }
      ],
      "conflictSearch": "decision records searched for a conflict: none — docs/decisions/*.md (54 records) searched for history entry, pushState, Alt+Left, typing guard, window tab, surfaceOrder, activity rail, forced colours; the only hits are about content hashes (0021, 0038 and others), not navigation"
    },
    {
      "items": ["modules/ui-web/src/shell-v0/chrome/Shell.globalKeys.test.ts"],
      "class": "New",
      "repaired": true,
      "behaviour": "FR3: Alt+Left/Right are claimed in a <select>, a shadow <input> and a <textarea>, and the focused control's own keydown never sees them; Shift+Alt and Ctrl+Alt arrows stay the platform's.",
      "sources": [
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "FR3", "quote": "Back and forward (Alt+Left/Right, mouse buttons) work reliably: same-surface navigation keeps the earlier entry; Back then Forward returns to the later view; browser history and the stage stay in step.", "label": "agent-drafted, owner-adopted" },
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "C8", "quote": "Keyboard ownership: new shortcuts (Ctrl+1/2/3, F6) never fire inside text fields where they would edit text, during IME composition, on key repeat, or under another modal.", "label": "agent-drafted, owner-adopted" }
      ],
      "evidence": "gates/test-intent/evidence/shell-s2-frame-repair-2.json",
      "newChecks": ["modules/ui-web/src/shell-v0/chrome/Shell.globalKeys.test.ts#FR3: Alt+Left/Right are claimed in a <select>, a shadow <input> and a <textarea>, and reach nothing under them"]
    },
    {
      "items": ["modules/ui-web/src/shell-v0/chrome/Shell.globalKeys.test.ts"],
      "class": "New",
      "repaired": true,
      "behaviour": "C8 on macOS: Option+Left/Right in a text field stay the text's word moves (not claimed by the app); outside a text field they are still Back and Forward.",
      "sources": [
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "C8", "quote": "Keyboard ownership: new shortcuts (Ctrl+1/2/3, F6) never fire inside text fields where they would edit text, during IME composition, on key repeat, or under another modal.", "label": "agent-drafted, owner-adopted" }
      ],
      "evidence": "gates/test-intent/evidence/shell-s2-frame-repair-3.json",
      "newChecks": ["modules/ui-web/src/shell-v0/chrome/Shell.globalKeys.test.ts#C8 macOS: Option+Left/Right stay word moves in a text field and navigate elsewhere"]
    },
    {
      "items": ["modules/ui-web/src/shell-v0/chrome/Shell.navigation.test.ts"],
      "class": "Obsolete",
      "removedChecks": "'a tab click names the tab in the address without a new entry; Back/Forward and reload restore it': the expectation that a window tab change adds no history entry. Orchestrator decision for the frame repair (item 4): switching a window tab adds a history entry and Back returns to the previous tab. The address, Back/Forward and reload assertions stay in the replacement test.",
      "sources": [
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "FR3", "quote": "Back and forward (Alt+Left/Right, mouse buttons) work reliably: same-surface navigation keeps the earlier entry; Back then Forward returns to the later view; browser history and the stage stay in step.", "label": "agent-drafted, owner-adopted" },
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "FR3", "quote": "The window tab and Settings section are part of the address.", "label": "agent-drafted, owner-adopted" }
      ],
      "conflictSearch": "decision records searched for a conflict: none — docs/decisions/*.md (54 records) searched for history entry, pushState, Alt+Left, typing guard, window tab, surfaceOrder, activity rail, forced colours; the only hits are about content hashes (0021, 0038 and others), not navigation"
    },
    {
      "items": ["modules/ui-web/src/shell-v0/chrome/Shell.navigation.test.ts"],
      "class": "New",
      "repaired": true,
      "behaviour": "A window tab change is one new history entry (the journal keeps one entry for the window); Back returns to the tab before, which the departing entry now names (`?tab=folders`), Forward and reload restore the later tab.",
      "sources": [
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "FR3", "quote": "Back and forward (Alt+Left/Right, mouse buttons) work reliably: same-surface navigation keeps the earlier entry; Back then Forward returns to the later view; browser history and the stage stay in step.", "label": "agent-drafted, owner-adopted" },
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "FR3", "quote": "The window tab and Settings section are part of the address.", "label": "agent-drafted, owner-adopted" }
      ],
      "evidence": "gates/test-intent/evidence/shell-s2-frame-repair-2.json",
      "newChecks": ["modules/ui-web/src/shell-v0/chrome/Shell.navigation.test.ts#a tab click names the tab in the address as a new entry; Back returns to the tab before; Forward and reload restore it"]
    },
    {
      "items": ["modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts"],
      "class": "New",
      "repaired": true,
      "behaviour": "In headless Chromium over the real Shell, router, journal, top bar, pill, Settings window, palette and Library tab row: (1) at 600×400 the unknown-window notice is readable under the top bar (real size, inside the window, not cut, not covered, not hidden from assistive technology) and the pill is not cut; (2) Settings → browser Back → Forward → Escape keeps the area and the next Back does not reopen Settings; (7) reload with Settings open keeps the area underneath and closing returns to it once; (5) a Settings › Developer link opens Developer and reload keeps it while the window catalog answers late; (3) after Alt+Left lands in Search's text box, Alt+Right goes Forward and edits nothing; (4) Library › Browse adds one entry and Alt+arrows, mouse buttons and browser buttons all land on Folders/Browse; (6) a Ctrl+K area is one entry; (9) in forced colours only the selected tab carries an underline, in Highlight.",
      "sources": [
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "L3", "quote": "Expected: in the new top bar it shows without truncating the pill or wrapping, at all three sizes.", "label": "agent-drafted, owner-adopted" },
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "FR2", "quote": "at 600×400 a minimal bar (brand dot, active area menu, pill as icon with count, settings, more). Nothing overflows.", "label": "agent-drafted, owner-adopted" },
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "C9", "quote": "Settings dismissal history: direct Settings entry and section deep links, reload, backdrop, Escape and browser Back/Forward close it once, keep the underlying area, and never need a second Back.", "label": "agent-drafted, owner-adopted" },
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "FR3", "quote": "Back and forward (Alt+Left/Right, mouse buttons) work reliably: same-surface navigation keeps the earlier entry; Back then Forward returns to the later view; browser history and the stage stay in step.", "label": "agent-drafted, owner-adopted" },
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "FR3", "quote": "The window tab and Settings section are part of the address.", "label": "agent-drafted, owner-adopted" },
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "X1", "quote": "no new hard-coded colours, sizes or z-indexes (ui-web gates).", "label": "agent-drafted, owner-adopted" }
      ],
      "evidence": "gates/test-intent/evidence/shell-s2-frame-repair-2.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts#600×400: the notice can be read, the pill is not cut and \"Choose destination\" is offered",
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts#Settings, browser Back, browser Forward, close: the next Back does not reopen Settings",
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts#reload with Settings open keeps the area underneath; closing returns to it once",
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts#a link to Settings › Developer opens Developer, and reload keeps it, while the engine is slow to answer",
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts#Back into Search puts focus in its text box; Alt+Right still goes Forward and edits nothing",
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts#Library › Browse adds an entry; Alt+arrows, mouse buttons and browser buttons all land on the same tabs",
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts#opening an area from the Ctrl+K palette is one step: one Back returns",
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts#the selected window tab is told apart from the others by a shape in a system colour"
      ]
    },
    {
      "items": ["modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts"],
      "class": "New",
      "behaviour": "L3 at 1280×800 and 900×700: the same readable-notice oracle (the notice shows in the pill there); the pill is not cut and 'Choose destination' is offered.",
      "sources": [
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "L3", "quote": "Expected: in the new top bar it shows without truncating the pill or wrapping, at all three sizes.", "label": "agent-drafted, owner-adopted" }
      ],
      "evidence": "gates/test-intent/evidence/shell-s2-frame-new.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts#1280×800: the notice can be read, the pill is not cut and \"Choose destination\" is offered",
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts#900×700: the notice can be read, the pill is not cut and \"Choose destination\" is offered"
      ]
    },
    {
      "items": ["modules/ui-web/src/shell-v0/views/SettingsSurface.v15.test.ts"],
      "class": "Obsolete",
      "removedChecks": "'lists rail surfaces in catalog order by default' (catalog order, Settings listed) and 'respects userConfig.surfaceOrder' (the saved order also reordering Search and Library): the list now mirrors the top bar (frame repair item 8): the switcher's areas in its fixed order (Ctrl+1/2/3 follow it), then the More windows in the saved order; Settings and System have fixed places and are not listed.",
      "sources": [
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "FR1", "quote": "Top bar replaces title bar and rail: brand, switcher (Search, Library, AI Brain), status pill, Settings, More (⋯).", "label": "agent-drafted, owner-adopted" },
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "FR5", "quote": "Layout compatibility: a saved layout's `rail.visible:false` hides the switcher (areas stay reachable by palette and Ctrl+1/2/3); `statusBar.visible:false` hides plugin status in the pill, never notices or needs-you; split layouts keep the second window pane with its picker; focus/zen keep working.", "label": "agent-drafted, owner-adopted" }
      ],
      "conflictSearch": "decision records searched for a conflict: none — docs/decisions/*.md (54 records) searched for history entry, pushState, Alt+Left, typing guard, window tab, surfaceOrder, activity rail, forced colours; the only hits are about content hashes (0021, 0038 and others), not navigation"
    },
    {
      "items": ["modules/ui-web/src/shell-v0/views/SettingsSurface.v15.test.ts"],
      "class": "New",
      "repaired": true,
      "behaviour": "Settings › Layout lists the switcher's areas in the switcher's order, then the More menu's windows in catalog or saved order, under the heading 'Switcher' with no 'rail' or 'surface' wording; checkboxes read 'Show <area> in the switcher'.",
      "sources": [
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "FR1", "quote": "Top bar replaces title bar and rail: brand, switcher (Search, Library, AI Brain), status pill, Settings, More (⋯).", "label": "agent-drafted, owner-adopted" },
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "FR5", "quote": "Layout compatibility: a saved layout's `rail.visible:false` hides the switcher (areas stay reachable by palette and Ctrl+1/2/3); `statusBar.visible:false` hides plugin status in the pill, never notices or needs-you; split layouts keep the second window pane with its picker; focus/zen keep working.", "label": "agent-drafted, owner-adopted" }
      ],
      "evidence": "gates/test-intent/evidence/shell-s2-frame-repair-2.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.v15.test.ts#lists the switcher areas in the switcher order, then the More windows in catalog order, in the design words",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.v15.test.ts#respects userConfig.surfaceOrder for the More windows; the switcher keeps its fixed order"
      ]
    },
    {
      "items": ["modules/ui-web/src/shell-v0/views/SettingsSurface.v15.test.ts"],
      "class": "Adaptation",
      "moved": ["input re-encoding", "instrumentation"],
      "whatMoved": "'hidden surfaces have visibility=false reflected in checkbox' and the move-down case run on a fixture of the real areas plus two More windows (the old fixture's Settings row is no longer listed, and its first row is now an area without arrows). Rows are found by `data-surface-id`; the hidden-row case checks an area and a window instead of Settings; the move-down case moves the first More window. The oracles (checkbox reflects surfaceVisibility; Move down writes surfaceOrder and the list reorders) are unchanged.",
      "sources": [
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "FR1", "quote": "Top bar replaces title bar and rail: brand, switcher (Search, Library, AI Brain), status pill, Settings, More (⋯).", "label": "agent-drafted, owner-adopted" },
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "FR5", "quote": "Layout compatibility: a saved layout's `rail.visible:false` hides the switcher (areas stay reachable by palette and Ctrl+1/2/3); `statusBar.visible:false` hides plugin status in the pill, never notices or needs-you; split layouts keep the second window pane with its picker; focus/zen keep working.", "label": "agent-drafted, owner-adopted" }
      ]
    },
    {
      "items": ["modules/ui-web/src/shell-v0/views/SettingsSurface.v15.test.ts"],
      "class": "New",
      "behaviour": "The switcher's areas carry no reorder arrows (their order is fixed); moving a More window does not move an area.",
      "sources": [
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "FR1", "quote": "Top bar replaces title bar and rail: brand, switcher (Search, Library, AI Brain), status pill, Settings, More (⋯).", "label": "agent-drafted, owner-adopted" }
      ],
      "evidence": "gates/test-intent/evidence/shell-s2-frame-new.json",
      "newChecks": ["modules/ui-web/src/shell-v0/views/SettingsSurface.v15.test.ts#move-down arrow on the first More window updates surfaceOrder; the switcher areas have no arrows"]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Frame repair on 38c6e7027f, in headless Chromium over the real Shell: (L2) a plugin status item that renders a fixed 1000 px wide element stays inside the status panel (no sideways scroll), its provenance chip inside the panel and its words as the item's tooltip; (C1) an address naming `vendor.nope-window` or the bare `nope-window` (ids the address grammar rejects) shows the same readable 'Couldn't find that window' notice with 'Choose destination' as an unknown `core.` id; (C9) with Settings open, Alt+Left closes it like browser Back and Alt+Right reopens it like Forward, and after Escape one Alt+Left returns to the view before; (FR3) reload with Settings open over Library › Browse shows Browse behind Settings from the start and after closing; (X1) with the dark app theme in forced colours on a light and on a dark system palette, both tab labels are CanvasText on Canvas while hovered, the selected one keeping its Highlight underline.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "L2",
          "quote": "Expected: one item always shows directly (wrapping or truncating with its full text available); \"… N more\" only when there are more items than fit, and never hides the only item. Provenance stays visible.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "L3",
          "quote": "Expected: in the new top bar it shows without truncating the pill or wrapping, at all three sizes.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "C1",
          "quote": "Ordinary operation feedback keeps a visible, accessible destination with the panel closed: clipboard failures, unknown destinations, operation failures and successes of the user's own actions show at the place of the action (inline receipt) or in the pill; never silently dropped (ST10, DE5).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "C9",
          "quote": "Settings dismissal history: direct Settings entry and section deep links, reload, backdrop, Escape and browser Back/Forward close it once, keep the underlying area, and never need a second Back.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "FR3",
          "quote": "Back and forward (Alt+Left/Right, mouse buttons) work reliably: same-surface navigation keeps the earlier entry; Back then Forward returns to the later view; browser history and the stage stay in step.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "FR3",
          "quote": "The window tab and Settings section are part of the address.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "X1",
          "quote": "no new hard-coded colours, sizes or z-indexes (ui-web gates).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s2-frame-repair-4.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts#a plugin item that renders a fixed 1000 px wide element stays inside its row, its words and provenance reachable",
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts#C1 an unknown destination vendor.nope-window shows the same notice",
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts#C1 an unknown destination nope-window shows the same notice",
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts#with Settings open, Alt+Left is browser Back and Alt+Right browser Forward",
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts#reload with Settings open over Library › Browse shows Browse behind Settings from the start",
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts#the dark app theme on a light system palette: every tab label is readable, the selected one too",
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts#the dark app theme on a dark system palette: every tab label is readable, the selected one too"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "L4b on the ledger path: an MCP request that is also recorded as a GATED action-ledger row ('Approval requested: <operation>', no pending id) and broadcast as an `authorization.pending` advisory (the projector's real classExtras) is in Needs you only while it waits; the GATED row joins Earlier once the request is answered or expires; an older, answered request for the same operation stays in Earlier meanwhile.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "L4b",
          "quote": "Expected: a needs-you item appears only in Needs you while it waits; it enters Earlier once answered, expired or withdrawn.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s2-frame-repair-4.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts#L4b an MCP request also recorded as a GATED ledger row waits in Needs you only; the row joins Earlier once it is answered or expires"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.v15.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "X1: the 'Windows listed under More (⋯)' line in Settings › Layout carries no inline style; its spacing is a class rule on the space tokens (`--jf-space-*`).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "X1",
          "quote": "no new hard-coded colours, sizes or z-indexes (ui-web gates).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s2-frame-repair-4.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.v15.test.ts#X1 the More windows line takes its spacing from the space tokens, not an inline size"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/settingsRegister.test.ts"
      ],
      "class": "New",
      "behaviour": "With the shipped message bundle, the Settings search finds Layout's area list by the nav's own label: 'switcher' finds it and 'rail' does not (the search reads the same backend message, `settings.section.rail`, as the nav).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "FR1",
          "quote": "Top bar replaces title bar and rail: brand, switcher (Search, Library, AI Brain), status pill, Settings, More (⋯).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s2-frame-new.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/settingsRegister.test.ts#the Settings search indexes the switcher list by the nav's own label: \"switcher\" finds it, \"rail\" does not"
      ]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1a65b-d490-7e22-8052-4ba61d6aceaa",
  "verdict": "reject",
  "digest": "sha256:7451f821ded832dd940d8d2af034e58d98cc1f4d03265fbd4464c3a58f5479ba",
  "note": "Round 1 on 6fe76d255f: the L3 oracle checked wording only while the notice was invisible at 600 px; Settings Back/Forward/close/Back reopened Settings. Transcribed by the orchestrator from the verifier's answer (Codex sandbox)."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1a6d4-cbed-7f33-9008-a4cba896811a",
  "verdict": "reject",
  "digest": "sha256:9e7204d247b146dc18876d9b8b1e4c8b03eab2fd79aca4859b92185182013176",
  "note": "Round 2 on 38c6e7027f: five stale evidence citations (Shell.globalKeys.test.ts changed after its evidence run). Transcribed by the orchestrator from the verifier's answer (Codex sandbox)."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1a73b-7a53-74a2-842f-b3867918669c",
  "verdict": "accept",
  "digest": "sha256:f985634454b5e9e9cabee4ab5e31d08af47822bfca90c3da8e18cb2a64a2758e",
  "note": "Round 3 on 705c73b316: 26 New (repaired entries fail before by assertion), 9 Obsolete (removals verified), 5 Adaptation (whole oracle kept), 1 Incidental; evidence valid. Transcribed by the orchestrator from the verifier's answer (Codex sandbox)."
}
```
