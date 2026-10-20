---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: agent-session-svef4avy
---

# WP2.2a implementation inventory and plan

Resumed implementation, final evidence preparation in progress (2026-10-19).
The missing Linux helper is being staged under the shared Gradle lock as explicitly
authorized in the resumed brief. Native desktop scenarios remain unverified.

Estimate: 4–7 hours. Start: 2026-10-19 21:48 UTC. Stop: 2026-10-20 11:48 UTC.
Scope: WP2.2a only, including the orchestrator decisions and pass-2 amendments.

| Scenarios | Paths and state owners |
| --- | --- |
| SC1, SC6, SC9, SN1–SN4, SN7 | Rust `lib.rs` setup, single-instance callback, tray, window-state restoration and Exit; shell owns window visibility and process lifetime. |
| SC2–SC5, SC14, SN5–SN6 | Shared `chrome/Shell.ts`, Stage retention and split panes, router bootstrap, `router/tauriBridge.ts`, modality and keyboard guards; `NavigationJournal.ts`, URL/projector, search/session stores and retained elements own state. UnifiedChatView is read-only. |
| SC7–SC8, SC10, SC13, SN3, SN8–SN9 | Shell-owned preference under the app-data root, reset before registration, native IPC, `SettingsSurface.ts`, shared switch primitive and forced-colors styles; `store-recoverability.v1.json` is also an upgrade wire contract. |
| SC11, SN5 | `KeybindingRegistry.ts`, persisted keybinding overrides and native registration status; saved bindings must remain unchanged. |
| SC12 | `HelpSurface.ts` SHORTCUTS, bundled `SSOT/docs/help/keyboard-shortcuts.md`, `KnowledgeServerBootstrap.java` help version and ingestion checks. |

Plan: establish failing component checks before code; implement shell preference/registration and a coalesced summon handshake; wire shared destination/focus protection; add independently loaded Settings controls, help and command-line instructions; finalize scenario evidence, run module checks and leave the acceptance record to the verifier.

Protocol before implementation: Rust shows/unminimizes/focuses immediately, independently of Engine readiness. Rust retains at most one pending summon in memory and emits a wakeup event; a ready Shell atomically takes that request, so a consumed request cannot replay after reload. Recovery never takes a request. Explicit deep links take precedence over `--focus-search`, which takes precedence over `--minimized`. Held native chords latch until release. The preference is versioned JSON, default-on only when absent; unreadable/future bytes remain untouched and registration stays off until an explicit user change. Serialize persistence and registration; failed persistence keeps the actual state. Only main-window IPC may read/change this state. In-app chord suppression follows actual registration and never rewrites bindings.

Real Windows/X11 foreground takeover, layout/IME/input-language safety, screen-reader output, live accessibility-tree landing and installer/update behavior require external verification; unit checks cannot establish them.

Gate aliases: SCn = S14.n and SNn = N14.n. Source quotes below are verbatim from the agreed scenarios, with pass-2 amendments where applicable. No acceptance record is authored here.

Resumed protocol refinement before implementation (2026-10-19 22:14 UTC): Tauri's built-in
cold-link reader only accepts one URL argument. Mixed focus/link launches therefore use the same
coalesced native slot, carrying either focusSearch or deepLink with its explicit URL. The shared
Shell dispatches the latter through the existing URL parser/router. Native bridge installation
must not await Engine-provided schemas; a handled native request supersedes later boot restoration
and its redundant URL-source boot dispatch. Bridge teardown is installed synchronously and also
runs after partial subscription failures.

Startup review before the final native adjustment: a warm focus request can be queued before
setup reaches its normal --minimized visibility decision. Setup must foreground an already-pending
request without replacing it with the original launch arguments. This keeps the coalesced request
and makes an autostart-first / focus-second startup visible once the window exists. Its actual
multi-process startup timing remains unverified without a desktop.

Final native callback protocol review: the global-hotkey callback must return without waiting for
window geometry on the UI thread, because a UI-thread registration may be awaiting the hotkey
thread. Schedule the summon through Tauri's existing main-thread dispatcher. The held-key latch
still runs at ingress; one scheduled summon owns the pending slot. Real callback/toggle timing
remains unverified on a desktop.

Settings status protocol before implementation: subscribe to the existing native status event,
then read the initial snapshot. Apply only revisions at least as new as the row's current revision,
including toggle replies and failed-save readback. Stop the listener when the view disconnects,
including a subscription that resolves late. A retained Settings mount therefore follows shell
state while closed and ignores older replies from overlapping calls.

The temporary core fallback is tied to the override that caused it. A later explicit override
change clears that fallback; it must not keep masking a newly configured search composer.

Integration blocker requiring an orchestrator decision: the actual legacy composer is disabled
while streaming. A component diagnostic confirmed no modal was active, the existing legacy focus
handler was called once, streaming remained running, and focus stayed on the prior button. SC14
therefore does not hold for that state under the no-UnifiedChatView-edit constraint. The diagnostic
is scaffolding retained under tmp/uiux-shortcut, not a passing durable check. An explicit decision
is pending: hold for the Search v3 flip, exempt the legacy streaming state, or separately specify
a legacy composer change. No exception, acceptance record or legacy edit is authored here.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.searchSummon.test.ts"
      ],
      "class": "New",
      "behaviour": "Shared native handoff targets only the default search composer, reuses a visible secondary pane, preserves composer overrides or falls back without rewriting them (including a non-callable focus property), keeps same-surface draft/URL/journal, navigates once from another non-modal view, consumes pending requests once, works independently of schema fetch, preserves explicit link destinations, and suppresses overlapping bindings only while native registration is active. Late subscription cleanup from a disconnected view cannot disable native chord suppression in a reloaded view. The real Search v3 composer receives focus without a chat model and keeps its draft; a missing override target uses the core composer without rewriting the override. A subsequent override change clears a temporary core fallback and targets the newly configured composer.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SC2",
          "quote": "**S14.2 Destination.** The summon logic lives in shared shell code, not in UnifiedChatView. It resolves \"the default search window\" at run time.\n  - When Search v3 is the target, every scenario here holds for it. Tests may make it the target before the flip.\n  - When the old window is the default (before the WP1.5 flip), summon foregrounds it and focuses its composer through its existing handler (`UnifiedChatView.ts:1006,3294`). No UnifiedChatView code changes. Its missing refusals (rename, palette, typing elsewhere) are a known pre-existing limit to report, not to fix (decision 2).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SC3",
          "quote": "**S14.3 Summon preserves state (same window).** Setup: the default search window is active, with a draft, a mode (Search/Ask/Delegate), an open conversation, selected results, a reading/scroll position and a Forward history entry. Repeated summons through both entry points (shortcut and command line) change nothing but visibility and focus:\n  - draft, mode, conversation, selection, scroll, URL and Back/Forward history stay as they were;\n  - the navigation journal gets no new entry;\n  - nothing is sent, searched, asked, delegated or cleared;\n  - a streaming answer, a running agent or an agent waiting for approval is not stopped, steered or approved.\n\n  Counterexample to cover: re-activating the same surface clears the selection (`Shell.ts:1548`) and pushes history (`navigationHandler.ts:195-213`). Summon must not go through it when the window is already active.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SC4",
          "quote": "**S14.4 Summon from another view, or with something open.**\n  - **A non-modal view is showing** (Library, Help, etc.). Summon navigates once to the default search window and focuses its bar. The previous view is one Back away and loses no input.\n  - **Settings is open** (a modal window, `SettingsWindow.ts`; stage navigation dismisses it at `Shell.ts:1578-1585`). Summon foregrounds the app and leaves Settings open and focused.\n  - **A confirmation dialog, either command palette, or a session rename is open.** Same as Settings. It leaves them open and focused, including when focus is on a button inside them. It never dismisses, submits or confirms anything.\n  - **Focus is in another ordinary text field of the search window.** Decision 3 settles it; the recommendation is to keep the existing focus refusals, `SearchV3View.ts:1038-1046`.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SC5",
          "quote": "**S14.5 Independent of the Engine, the AI and boot phase.** The window becomes visible at once whenever the summon arrives. That includes:\n  - before the frontend has subscribed;\n  - while the desktop recovery screen is showing (`desktopRecovery.ts`, mounted before Shell, `main.jsx:454`);\n  - just before a recovery reload;\n  - with the Engine starting, restarting or failed;\n  - with no chat model.\n\n  At most one pending focus request reaches the search bar once it exists. A request already handled never replays after a reload. While the recovery screen is in charge, its own keyboard controls stay usable and summon does not steal their focus. No error toast, no crash.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SC9",
          "quote": "**S14.9 Command line** (`--focus-search`, task record).\n  - It works whether the shortcut is on, off or not active, on Windows, Linux X11 and Linux Wayland.\n  - **App running:** it does what S14.1 does (including from hidden and minimized), and the second process exits without a second app or Engine.\n  - **App not running:** it starts the app with the window shown and focus in the search bar. This holds even though cold boot otherwise restores the last view, and even with autostart on.\n  - **Mixed arguments** (decision 7):\n    - `--focus-search --minimized` performs the summon, cold and warm;\n    - a `justsearch://` deep link given with the flag keeps the link's destination, with the window shown;\n    - a deep link alone and other arguments work as today.\n  - A second request arriving while the first instance is still starting causes neither a second Engine nor a lost summon.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SC11",
          "quote": "**S14.11 No collisions.**\n  - The chord is none of the in-app bindings. That covers the stock ones (`HelpSurface.ts:73`, `keyboard-shortcuts.md`), and also a user override or plugin binding of the same chord loaded from saved state (`KeybindingRegistry.ts` `loadPersistedKeybindings`). With such an override present, one press does only the global summon.\n  - It cannot be produced with AltGr (so not Ctrl+Alt+…).\n  - Holding and releasing it types no character, activates no focused control and, with two input languages installed on Windows, does not switch the input language.\n  - Checked on at least US and one AltGr layout (e.g. German), plus one IME (decision 9).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SC2",
          "quote": "- Split panes (finding 4): summon focuses exactly the default search composer; if it is already visible in a pane, that pane is reused; the other search implementation's composer never receives focus.\n- Surface override (finding 5): summon respects a configured override that has a search composer; if the override has none or its target is missing, summon shows the core default search temporarily and leaves the saved override unchanged.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SC4",
          "quote": "- Findings 1-5 accepted with the challenger's proposed text. Decision 2 amended: the summon protections (modal incl. the indexing overlay, rename, palette, ordinary-field edit) hold on either destination and are enforced in shared code without editing UnifiedChatView; if that is impossible, WP2.2a waits for the flip.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SC6",
          "quote": "**S14.6 Foreground press and repeats.**\n  - Pressing the shortcut while JustSearch is already in front behaves as decision 4 settles.\n  - Repeats leave no surprising state: rapid repeated presses, holding the keys (auto-repeat), and presses after the UI reloads. One physical press gives exactly one summon effect. A held chord queues no burst. Handlers never double-register. No second window or app instance starts.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-shortcut-frontend-new.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.searchSummon.test.ts#SC2 SC3 focuses the current default once without changing draft, URL or journal",
        "modules/ui-web/src/shell-v0/chrome/Shell.searchSummon.test.ts#SC4 navigates from a non-modal view once with the prior view one Back away",
        "modules/ui-web/src/shell-v0/chrome/Shell.searchSummon.test.ts#SC5 drains a request already pending before subscription and never replays it on reconnect",
        "modules/ui-web/src/shell-v0/chrome/Shell.searchSummon.test.ts#SC2 preserves a composer override and uses it as the destination",
        "modules/ui-web/src/shell-v0/chrome/Shell.searchSummon.test.ts#SC2 falls back temporarily for an override without a composer and preserves the saved override",
        "modules/ui-web/src/shell-v0/chrome/Shell.searchSummon.test.ts#SC2 reuses a secondary search pane and never focuses the other composer",
        "modules/ui-web/src/shell-v0/chrome/Shell.searchSummon.test.ts#SC9 a pending explicit deep link keeps its destination instead of focusing Search",
        "modules/ui-web/src/shell-v0/chrome/Shell.searchSummon.test.ts#SC5 does not wait for the Engine schema request before consuming a ready focus request",
        "modules/ui-web/src/shell-v0/chrome/Shell.searchSummon.test.ts#SC11 native registration takes precedence and an overlapping saved binding resumes when inactive",
        "modules/ui-web/src/shell-v0/chrome/Shell.searchSummon.test.ts#SC5 SC6 late subscription cleanup cannot disable the reloaded view shortcut guard",
        "modules/ui-web/src/shell-v0/chrome/Shell.searchSummon.test.ts#SC2 ignores a missing override target without changing the saved override",
        "modules/ui-web/src/shell-v0/chrome/Shell.searchSummon.test.ts#SC2 targets the real Search v3 composer with no chat model and preserves its draft",
        "modules/ui-web/src/shell-v0/chrome/Shell.searchSummon.test.ts#SC2 respects a changed override after a temporary core fallback"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.searchShortcut.test.ts"
      ],
      "class": "New",
      "behaviour": "Desktop shortcut controls load independently of autostart, distinguish off/active/unavailable or malformed replies, expose a polite status and the reason on the keyboard switch, restore and display shell state alongside save errors, and prevent overlapping toggles. A retained Settings row follows native status events and rejects older toggle replies, so its state agrees with the shell after overlap or reopen.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SC7",
          "quote": "**S14.7 Three distinct states.** The shell and Settings tell apart:\n  - turned off by choice;\n  - on and registered;\n  - on but not active.\n\n  \"Not active\" covers: another app holds the chord, the OS refuses, an unsupported session (S14.10), or an unreadable preference (S14.8). Settings says in plain words that the shortcut is not active and why, and points to the command-line alternative. JustSearch starts and works normally. A failed registration never shows as active.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SC8",
          "quote": "**S14.8 On/off preference.**\n  - **Showing it.** Settings shows the shortcut (keys written out) and an on/off switch.\n  - **Switching.** Turning it off releases the chord at once, so another app can take it without a restart. Turning it on registers at once and reports failure as in S14.7.\n  - **Toggles that fail or overlap.** A failed save puts the switch back to the real state and says so. Slow or overlapping toggles never leave disk, shell and UI disagreeing, and reopening Settings or reloading the UI shows the shell's current state.\n  - **Boot and restart.** The choice survives restart, including autostart launches. It is applied before registration, so a chord turned off is never grabbed briefly at boot.\n  - **Writes and defaults.** A kill during a write leaves a complete, readable preference. A missing file (fresh install) gives the default (decision 5). After factory reset or \"delete all data\", the next start uses the default (reset runs in setup, before registration).\n  - **Unreadable or future-version file.** Decision 6. The recommendation: chord not registered, Settings explains, the file's bytes are kept until the user changes the setting.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SC13",
          "quote": "**S14.13 Settings row accessibility.**\n  - The row has its own loading. It shows its state and errors even while the autostart status is slow or failing. Today the whole Desktop section hides until autostart loads (`SettingsSurface.ts:1688-1691`).\n  - Keyboard only; light and dark themes; Windows forced colors and the app's High Contrast; minimum window width 600 px (`tauri.conf.json`).\n  - A screen reader announces the switch's name and state and the reason it is not active. A status change is announced politely.\n  - The plain browser build shows no native shortcut controls and logs no errors from the bridge.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-shortcut-frontend-new.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.searchShortcut.test.ts#SC13 shows shortcut status independently of failed autostart and announces it politely",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.searchShortcut.test.ts#SC13 exposes the unavailable reason on the keyboard switch itself",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.searchShortcut.test.ts#SC7 distinguishes off, active and enabled but unavailable",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.searchShortcut.test.ts#SC8 keeps the shell state after a failed save and prevents overlapping toggles",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.searchShortcut.test.ts#SC7 reports an unavailable status when the native reply is malformed",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.searchShortcut.test.ts#SC8 keeps newer shell state when an older toggle reply arrives late"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/HelpSurface.test.ts"
      ],
      "class": "New",
      "behaviour": "Visible Help describes the desktop chord, focus exceptions and command-line alternative.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SC12",
          "quote": "**S14.12 Help.** The visible in-app Help shortcut table (`HelpSurface.ts` SHORTCUTS, both reference presentations) and the bundled searchable help (`SSOT/docs/help/keyboard-shortcuts.md`) both describe the chord, when it does not move focus (S14.4), and the command-line alternative. They agree with each other. Existing entries are unchanged. An upgraded install receives the indexed change (help version bump).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-shortcut-frontend-new.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/HelpSurface.test.ts#SC12 explains the desktop summon, focus exceptions and command-line alternative"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.searchSummon.test.ts"
      ],
      "class": "New",
      "behaviour": "Preserve modal/ordinary-field focus during summon.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SC4",
          "quote": "**S14.4 Summon from another view, or with something open.**\n  - **A non-modal view is showing** (Library, Help, etc.). Summon navigates once to the default search window and focuses its bar. The previous view is one Back away and loses no input.\n  - **Settings is open** (a modal window, `SettingsWindow.ts`; stage navigation dismisses it at `Shell.ts:1578-1585`). Summon foregrounds the app and leaves Settings open and focused.\n  - **A confirmation dialog, either command palette, or a session rename is open.** Same as Settings. It leaves them open and focused, including when focus is on a button inside them. It never dismisses, submits or confirms anything.\n  - **Focus is in another ordinary text field of the search window.** Decision 3 settles it; the recommendation is to keep the existing focus refusals, `SearchV3View.ts:1038-1046`.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SC4",
          "quote": "- Findings 1-5 accepted with the challenger's proposed text. Decision 2 amended: the summon protections (modal incl. the indexing overlay, rename, palette, ordinary-field edit) hold on either destination and are enforced in shared code without editing UnifiedChatView; if that is impossible, WP2.2a waits for the flip.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-shortcut-frontend-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.searchSummon.test.ts#SC4 leaves a modal and an ordinary field in charge, including a modal button"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.searchShortcut.test.ts"
      ],
      "class": "New",
      "behaviour": "Browser settings hide native shortcut controls and issue no native calls.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SN6",
          "quote": "N14.6 Browser dev mode without Tauri.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-shortcut-frontend-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.searchShortcut.test.ts#SN6 hides native shortcut controls and makes no native calls in a browser"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/search_shortcut.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "Native preference parsing defaults on only when absent, preserves unreadable/future bytes, persists explicit choices atomically, reports registration conflict and save/release state, coalesces focus requests, latches chord repeats, rejects Wayland registration, and applies launch-argument precedence. These new private native APIs have no assertion-fail-before evidence on c00a0cb24; earlier neutral scaffold runs are not claimed as such.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SC5",
          "quote": "**S14.5 Independent of the Engine, the AI and boot phase.** The window becomes visible at once whenever the summon arrives. That includes:\n  - before the frontend has subscribed;\n  - while the desktop recovery screen is showing (`desktopRecovery.ts`, mounted before Shell, `main.jsx:454`);\n  - just before a recovery reload;\n  - with the Engine starting, restarting or failed;\n  - with no chat model.\n\n  At most one pending focus request reaches the search bar once it exists. A request already handled never replays after a reload. While the recovery screen is in charge, its own keyboard controls stay usable and summon does not steal their focus. No error toast, no crash.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SC6",
          "quote": "**S14.6 Foreground press and repeats.**\n  - Pressing the shortcut while JustSearch is already in front behaves as decision 4 settles.\n  - Repeats leave no surprising state: rapid repeated presses, holding the keys (auto-repeat), and presses after the UI reloads. One physical press gives exactly one summon effect. A held chord queues no burst. Handlers never double-register. No second window or app instance starts.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SC7",
          "quote": "**S14.7 Three distinct states.** The shell and Settings tell apart:\n  - turned off by choice;\n  - on and registered;\n  - on but not active.\n\n  \"Not active\" covers: another app holds the chord, the OS refuses, an unsupported session (S14.10), or an unreadable preference (S14.8). Settings says in plain words that the shortcut is not active and why, and points to the command-line alternative. JustSearch starts and works normally. A failed registration never shows as active.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SC8",
          "quote": "**S14.8 On/off preference.**\n  - **Showing it.** Settings shows the shortcut (keys written out) and an on/off switch.\n  - **Switching.** Turning it off releases the chord at once, so another app can take it without a restart. Turning it on registers at once and reports failure as in S14.7.\n  - **Toggles that fail or overlap.** A failed save puts the switch back to the real state and says so. Slow or overlapping toggles never leave disk, shell and UI disagreeing, and reopening Settings or reloading the UI shows the shell's current state.\n  - **Boot and restart.** The choice survives restart, including autostart launches. It is applied before registration, so a chord turned off is never grabbed briefly at boot.\n  - **Writes and defaults.** A kill during a write leaves a complete, readable preference. A missing file (fresh install) gives the default (decision 5). After factory reset or \"delete all data\", the next start uses the default (reset runs in setup, before registration).\n  - **Unreadable or future-version file.** Decision 6. The recommendation: chord not registered, Settings explains, the file's bytes are kept until the user changes the setting.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SC9",
          "quote": "**S14.9 Command line** (`--focus-search`, task record).\n  - It works whether the shortcut is on, off or not active, on Windows, Linux X11 and Linux Wayland.\n  - **App running:** it does what S14.1 does (including from hidden and minimized), and the second process exits without a second app or Engine.\n  - **App not running:** it starts the app with the window shown and focus in the search bar. This holds even though cold boot otherwise restores the last view, and even with autostart on.\n  - **Mixed arguments** (decision 7):\n    - `--focus-search --minimized` performs the summon, cold and warm;\n    - a `justsearch://` deep link given with the flag keeps the link's destination, with the window shown;\n    - a deep link alone and other arguments work as today.\n  - A second request arriving while the first instance is still starting causes neither a second Engine nor a lost summon.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SC10",
          "quote": "**S14.10 Supported sessions** (decision 8).\n  - Windows 10/11 and Linux X11 count as supported only after a real summon from another foreground app has been seen there.\n  - A session the shell identifies as Wayland shows \"not available in this desktop session\" plus how to bind the command line, never \"active\". The detection rule is stated in the how-to.\n  - A `docs/how-to/` page explains binding the command line as a custom shortcut on Windows, GNOME and KDE.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SN1",
          "quote": "N14.1 A second plain launch focuses the existing window and exits. Deep links work cold and warm (`lib.rs:1672-1693`, `tauriBridge.ts`).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SN3",
          "quote": "N14.3 Autostart: `--minimized` alone starts hidden, and the Launch-on-startup switch works.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-shortcut-shell-new.json",
      "newChecks": [
        "modules/shell/src-tauri/src/search_shortcut.rs#search_shortcut::tests::sc8_reads_off_before_registration_and_preserves_future_or_corrupt_bytes",
        "modules/shell/src-tauri/src/search_shortcut.rs#search_shortcut::tests::sc9_focus_flag_beats_minimized_and_deep_links_keep_their_destination",
        "modules/shell/src-tauri/src/search_shortcut.rs#search_shortcut::tests::sn1_sn3_plain_launch_and_minimized_arguments_keep_their_behavior",
        "modules/shell/src-tauri/src/search_shortcut.rs#search_shortcut::tests::sc5_sc6_pending_requests_coalesce_and_a_consumed_request_cannot_replay",
        "modules/shell/src-tauri/src/search_shortcut.rs#search_shortcut::tests::sc10_wayland_detection_fails_closed_even_with_xwayland_display",
        "modules/shell/src-tauri/src/search_shortcut.rs#search_shortcut::tests::sc7_sc8_registration_conflict_and_off_switch_report_real_state",
        "modules/shell/src-tauri/src/search_shortcut.rs#search_shortcut::tests::sc8_failed_save_restores_the_choice_and_registration",
        "modules/shell/src-tauri/src/search_shortcut.rs#search_shortcut::tests::sc8_unavailable_sessions_persist_the_choice_without_attempting_registration"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/HelpIngestMarkerRecoveryTest.java"
      ],
      "class": "New",
      "behaviour": "A historical v2 marker and the previous current v3 marker each trigger exactly one help re-ingest, advance to v4, and suppress another re-ingest. The old v2-to-v3 check now expects the adopted shortcut-help generation; its once-only oracle is retained.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SC12",
          "quote": "**S14.12 Help.** The visible in-app Help shortcut table (`HelpSurface.ts` SHORTCUTS, both reference presentations) and the bundled searchable help (`SSOT/docs/help/keyboard-shortcuts.md`) both describe the chord, when it does not move focus (S14.4), and the command-line alternative. They agree with each other. Existing entries are unchanged. An upgraded install receives the indexed change (help version bump).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-shortcut-help-upgrade.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/HelpIngestMarkerRecoveryTest.java#v2MarkerReingestsOnceAndWritesV4",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/HelpIngestMarkerRecoveryTest.java#v3MarkerReingestsOnceAndWritesV4"
      ],
      "repaired": true
    }
  ]
}
```

## Acceptance records
