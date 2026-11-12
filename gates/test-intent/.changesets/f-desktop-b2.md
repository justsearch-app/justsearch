---
schema: test-intent.v1
task: t-20261103-shell
author-role: builder
author-session: f-desktop-b2-builder
---

# F-desktop B2: engine loss at desktop start, focus after the treatment and the window controls, the report's exit counts, the web view menu

Follow-up batch B2 to S5c (engine loss) and S5w (window controls), from the real-system checks
s5c live-1 and s5w windows-1. The changes, at the layer that owns each:

- **One definition of "the app is usable"** (`boot/desktopRecovery.ts`, `appIsUsable`): the
  application `mountBootApplication` attached after boot is in the root and its start-up cover has
  left, because its top bar rendered and painted. It is recorded where the hand-off releases the app
  and is the only thing the desktop's engine-loss listener asks. The hand-off's paint wait no longer
  hangs on a hidden window: a hidden window (started to the tray, minimized) runs no animation frame
  and paints nothing, so the cover leaves at once there, or as soon as the window becomes hidden.
  Before, the app beneath the cover stayed inert until the window was next painted, and any engine
  loss in that time replaced the app with the recovery page.
- **No Connecting page while discovery runs**: the start-up frame ("Starting") stays while the API
  is found, so a reload that rebinds to a restarted engine (SC6) looks like the app it replaces. The
  recovery page shows only for a supervisor state that needs it, or when discovery found no engine
  ("Connecting to JustSearch" until the host reports the engine running, as before).
- **One focus-return rule** (`utils/focusReturn.ts`): back to the element focused before, when it can
  still take focus (connected, not inert across shadow roots, focus actually lands), else the main
  landmark (the shell's window area). The treatment uses it when it leaves and when an action removes
  the control that had focus (Open Health turns it into a band without that button); before, the
  "focus in the treatment" test did not look through `jf-button`'s shadow root, so focus on an action
  dropped to the page body. The window controls use it through `boot/desktopWindow.ts`: Minimize and
  Close return focus as they are used; index.html's window layer lets go of focus on those controls on
  every page (also where no application module runs). Maximize keeps its focus.
- **Recovery announced once**: a live region in the treatment that stays in the page says
  "JustSearch can reach its engine again." when an outage ends, once per outage.
- **Report exit counts from the supervisor's record** (`state/engineReport.ts`): `recentExitCodes` is
  exactly `recentExits.codes`, keys an exit code or, for a signal, its name (the Linux ledger keys a
  SIGKILL by `SIGKILL`, with no exit code); nothing is inferred from `lastExit`. Before, the signal
  key failed the integer filter and the last exit (-9) was added with a count of 1.
- **Progress line**: a new action in the treatment clears "Report copied.".
- **Context-menu policy in one place** (index.html's window layer, desktop only): in production
  builds (`%PROD%`) the web view's own menu never shows, on the top area, its drag region and the
  content of every page; text fields keep the platform edit menu; the app's own menus still receive
  the event. Development builds keep the web view menu.

Windows-specific behaviour (WebView2 focus on restore from the taskbar or tray, its context menu,
the drag region's window menu) is exercised here only through the production assets in headless
Chromium with the native IPC double; the real Windows check is the orchestrator's.

`src/boot/desktopEngineLossStart.test.ts`, `src/boot/desktopChrome-lockdown.test.ts`,
`src/boot/windowLayer.test.ts` and `src/boot/desktopWindow.test.ts` are new and isolated (`[exempt]`
at the gate); they are entered here anyway so their expectations carry sources and evidence.

The window and start-up checks in real Chromium (FD1, the SA7 SA10 deadline over Connecting, FW1,
FW2) live in `src/boot/desktopChrome-lockdown.test.ts`, with a build, server and browser of their own,
and keep one page per behaviour: FW1 Minimize on the running app and Close on the start-up frame,
FW2 on the running app's top area and content, plus the text-field case. The rules are
page-independent, so the other pages are covered without page loads: `windowLayer.test.ts` runs
index.html's own inline script on its own markup (FW1 letting go of focus, FW2's policy) over every
kind of page the root holds, and `desktopWindow.test.ts` covers where focus returns
(`boot/desktopWindow.ts` with `utils/focusReturn.ts`).

Changed existing checks in `src/boot/boot-frame-lockdown.test.ts` (entry below): the harness gains a
`noEngine` option (the host's `api_port` answers no engine), which changes no other case. The
`connecting` surface (window-layer cases) and the `connecting` state of the SW5 legibility cases
opened Connecting by holding discovery forever, which FD1 retires: they now open it by a discovery
that found no engine. "SA7 SA10 deadline survives a long connecting interval before the frame
returns" is removed there; its rewrite, "... which a running engine ends by reloading", is in
`src/boot/desktopChrome-lockdown.test.ts`.

```json
{
  "entries": [
    {
      "items": ["modules/ui-web/src/boot/desktopEngineLossStart.test.ts"],
      "class": "New",
      "repaired": true,
      "behaviour": "Desktop runtime, real boot listener and real Shell, native command and event channels as doubles, the start-up frame in the root. (1) The window is hidden (visibilityState hidden, no animation frame runs): after the shell has rendered, the frame is gone, the app is not inert, and an exhausted supervisor state shows the in-app treatment; the app is not replaced, its stores are kept. (2) The window is visible but paints no frame, then becomes hidden: the hand-off completes on the visibility change and loss shows inside the app. (3) Discovery is slow: while it runs, and when the supervisor reports starting, no recovery page is in the root and the frame stays; when discovery answers, the frame is still there. (4) Exhausted after 16 SIGKILLs with lastExit code -9 and recentExits {count 16, codes {SIGKILL: 16}}: the copied report's recentExitCodes is exactly {SIGKILL: 16}. (5) After Copy report the progress line reads 'Report copied.'; after Restart the engine is refused it reads only the refusal words.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design. proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell request",
          "delegationScope": "the UI between the main windows (start-up, engine loss and recovery, window chrome), its design choices",
          "choice": "FD1 (orchestrator, 2026-11-12, reversible): when the engine dies within ~10-37 s after the window loads (once the shell has painted and booted), the in-app engine-loss treatment shows, not the full-page recovery screen; after a restart no full-page 'Connecting to JustSearch' replaces a booted app. Decision: the in-app engine-loss treatment applies once the app is usable, not only after an arbitrary delay.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design. proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell request",
          "delegationScope": "the UI between the main windows (start-up, engine loss and recovery, window chrome), its design choices",
          "choice": "FD3: the desktop report's recent exit counts come from the supervisor's record (it recorded 16 exits where the report said one -9). FD4: 'Report copied.' does not stay on the progress line after Try again.",
          "label": "agent choice"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "SC5",
          "quote": "Copy report works without the engine: a fixed list of fields (version, runtime, operating-system family, time, last contact age, supervisor state, reason, recent exit codes), no paths, hosts, keys or free error text; if the clipboard refuses, the report is shown ready to select.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-desktop-b2-start.json",
      "newChecks": [
        "modules/ui-web/src/boot/desktopEngineLossStart.test.ts#a window that is hidden while the app starts: once the shell has rendered, loss shows inside it, not the recovery page",
        "modules/ui-web/src/boot/desktopEngineLossStart.test.ts#a window hidden while the hand-off waits for its paint: the hand-off completes and loss shows inside the app",
        "modules/ui-web/src/boot/desktopEngineLossStart.test.ts#a reload after a restart keeps the start-up frame while it finds the engine: no Connecting page",
        "modules/ui-web/src/boot/desktopEngineLossStart.test.ts#the report counts recent exits as the supervisor recorded them, signals included",
        "modules/ui-web/src/boot/desktopEngineLossStart.test.ts#“Report copied.” leaves the progress line when the next action starts"
      ]
    },
    {
      "items": ["modules/ui-web/src/boot/desktopEngineLossStart.test.ts"],
      "class": "New",
      "behaviour": "Preservation, passing on the base and the candidate: (1) before the app has painted (visible window, no animation frame yet) an exhausted state still replaces the covered app with the recovery page; (2) when discovery finds no engine the Connecting page ('Connecting to JustSearch') waits and a running supervisor state reloads the window once; (3) a terminal state while discovery runs shows the recovery page in place of the frame.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "SC2",
          "quote": "Desktop \"not ready\", terminal stops and exhausted restarts after the app has loaded no longer replace the whole app: the same treatment, with the supervisor's reason in plain words and Health reachable. Linux update hold keeps replacing the app; Windows keeps its hand-off; before the app has loaded the existing recovery page stays.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "FL6",
          "quote": "Desktop recovery works without the engine; terminal events beat late mounting; Linux update hold replaces the shell and Windows keeps it; factory-reset gates run before stores and block boot until resolved; customization (theme, presentation, plugins, layouts) restores before the first surface.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-desktop-b2-start-preserved.json",
      "newChecks": [
        "modules/ui-web/src/boot/desktopEngineLossStart.test.ts#before the app has painted, loss still shows the recovery page (preserved)",
        "modules/ui-web/src/boot/desktopEngineLossStart.test.ts#when discovery finds no engine, the Connecting page waits and a running engine reloads (preserved)",
        "modules/ui-web/src/boot/desktopEngineLossStart.test.ts#a terminal state during discovery shows the recovery page (preserved)"
      ]
    },
    {
      "items": ["modules/ui-web/src/shell-v0/chrome/Shell.engineLoss-lockdown.test.ts"],
      "class": "New",
      "repaired": true,
      "behaviour": "Real Chromium, browser runtime, real Shell over the stubbed backend of the existing harness. (1) Focus in the text box, outage, focus moved to the treatment's Try again; when the engine answers the treatment is gone and focus is on the text box, not the page body. (2) Focus in the text box, outage, Enter on Open Health: Health shows, the treatment is a band, the text box is gone, and focus is on the main content ('Main content' landmark), not the page body. (3) Two outages: on each recovery a status live region of the treatment, outside its panel, says exactly once 'JustSearch can reach its engine again.' (no further text within 5 s), and that region is rendered. (4) After Copy report the progress line reads 'Report copied.'; after Try again it reads only 'Still can’t reach the engine. JustSearch keeps trying every few seconds.'",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design. proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell request",
          "delegationScope": "the UI between the main windows (start-up, engine loss and recovery, window chrome), its design choices",
          "choice": "FD2 (orchestrator, 2026-11-12, reversible): when the treatment ends (engine back) with focus inside it, or after 'Open Health' from it, focus returns to where it was before the treatment (or to the main content if that is gone), never the page body; the recovery is announced once to screen readers. FD4: 'Report copied.' does not stay on the progress line after Try again.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-desktop-b2-chromium.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.engineLoss-lockdown.test.ts#FD2 focus on an action of the treatment returns to the text box when the engine is back, never the page",
        "modules/ui-web/src/shell-v0/chrome/Shell.engineLoss-lockdown.test.ts#FD2 after Open Health focus is on the main content that shows Health, not the page",
        "modules/ui-web/src/shell-v0/chrome/Shell.engineLoss-lockdown.test.ts#FD2 the recovery is announced once, by a live region that stays in the page; each outage again",
        "modules/ui-web/src/shell-v0/chrome/Shell.engineLoss-lockdown.test.ts#FD4 “Report copied.” does not stay on the progress line after Try again"
      ]
    },
    {
      "items": ["modules/ui-web/src/boot/desktopChrome-lockdown.test.ts", "modules/ui-web/src/boot/windowLayer.test.ts"],
      "class": "New",
      "repaired": true,
      "behaviour": "Production assets in headless Chromium with the native IPC double and Tauri's drag script (desktopChrome-lockdown), one page per behaviour. FW1: Minimize on the running app and Close on the start-up frame (no application module), by pointer, Enter and Space, each send exactly one native command and leave focus off the window controls; in the running app focus is back on the More control that had it before; one more Space or Enter then sends no window command. Maximize by Enter keeps its focus. FW2: a right-click on the running app's top-area drag region and on its content outside text fields leaves the context-menu event's default prevented (no web view menu). FD1: discovery held for a while keeps the start-up frame in the root, no Connecting page is attached at any moment, the app then takes over with no blank sample. index.html's own inline script on its own markup in happy-dom (windowLayer), on the start-up frame, the failure page, a recovery or reset page, the running app and the app held beneath the start-up frame: FW1 Minimize and Close send their one command and focus leaves the window controls, Maximize keeps its focus; FW2 in the production build a right-click on the top area, its drag region, the content (light DOM and shadow roots) and the window controls is default-prevented while the element's own listener still receives it; so is a right-click on checkboxes, radios, ranges, button inputs and disabled text fields, in light DOM and shadow roots (they take no typing).",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design. proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell request",
          "delegationScope": "the UI between the main windows (start-up, engine loss and recovery, window chrome), its design choices",
          "choice": "FW1: on Windows (and every desktop platform), after minimizing or closing to tray with the window controls and bringing the window back, keyboard focus is not on that control (one Space or Enter must not minimize or hide it again): it returns to where it was before the control was used, else the main content. FW2 (decision 2026-11-12): the web view's own context menu (Back, Refresh, Print, Inspect) never shows on the app's chrome (top bar, drag region); app-wide it is suppressed in production builds except inside editable text fields, where the platform's edit menu stays. FD1: after a restart no full-page 'Connecting to JustSearch' replaces a booted app.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-desktop-b2-window.json",
      "newChecks": [
        "modules/ui-web/src/boot/desktopChrome-lockdown.test.ts#FW1 Minimize on the running app returns focus to where it was before the control, so one key cannot minimize again; Maximize keeps its focus",
        "modules/ui-web/src/boot/desktopChrome-lockdown.test.ts#FW1 Close on the start-up frame lets go of focus with no application module, so one key cannot hide the window again",
        "modules/ui-web/src/boot/desktopChrome-lockdown.test.ts#FW2 production build: right-click on the top area and the content of the running app opens no web view menu",
        "modules/ui-web/src/boot/desktopChrome-lockdown.test.ts#FD1 a slow discovery keeps the start-up frame, never a Connecting page, until the app takes over",
        "modules/ui-web/src/boot/windowLayer.test.ts#FW2 production build: the top area, the drag region, the content and the window controls of the start-up frame open no web view menu",
        "modules/ui-web/src/boot/windowLayer.test.ts#FW1 Minimize and Close on the start-up frame let go of focus as they send the window away; Maximize keeps it",
        "modules/ui-web/src/boot/windowLayer.test.ts#FW2 production build: the top area, the drag region, the content and the window controls of the failure page open no web view menu",
        "modules/ui-web/src/boot/windowLayer.test.ts#FW1 Minimize and Close on the failure page let go of focus as they send the window away; Maximize keeps it",
        "modules/ui-web/src/boot/windowLayer.test.ts#FW2 production build: the top area, the drag region, the content and the window controls of a recovery or reset page open no web view menu",
        "modules/ui-web/src/boot/windowLayer.test.ts#FW1 Minimize and Close on a recovery or reset page let go of focus as they send the window away; Maximize keeps it",
        "modules/ui-web/src/boot/windowLayer.test.ts#FW2 production build: the top area, the drag region, the content and the window controls of the running app open no web view menu",
        "modules/ui-web/src/boot/windowLayer.test.ts#FW1 Minimize and Close on the running app let go of focus as they send the window away; Maximize keeps it",
        "modules/ui-web/src/boot/windowLayer.test.ts#FW2 production build: the top area, the drag region, the content and the window controls of the app held beneath the start-up frame open no web view menu",
        "modules/ui-web/src/boot/windowLayer.test.ts#FW1 Minimize and Close on the app held beneath the start-up frame let go of focus as they send the window away; Maximize keeps it",
        "modules/ui-web/src/boot/windowLayer.test.ts#FW2 production build: controls that take no typing and disabled fields open no web view menu"
      ]
    },
    {
      "items": ["modules/ui-web/src/boot/desktopChrome-lockdown.test.ts", "modules/ui-web/src/boot/windowLayer.test.ts"],
      "class": "New",
      "behaviour": "Preservation, passing on the base and the candidate. In the production build in Chromium a right-click in the command palette's text field leaves the event's default alone (the platform edit menu stays); in index.html's script text, search, email, password, url and number fields, textareas and contenteditable regions, in light DOM and shadow roots, keep the menu; a development build prevents no menu. SA7 SA10 in Chromium: a discovery that finds no engine shows Connecting, 60 s pass with no failure page and Connecting still in the root, and a running supervisor state reloads the window.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design. proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell request",
          "delegationScope": "the UI between the main windows (start-up, engine loss and recovery, window chrome), its design choices",
          "choice": "FW2: Editable text fields keep the platform's edit menu.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design. proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell request",
          "delegationScope": "the UI between the main windows (start-up, engine loss and recovery, window chrome), its design choices",
          "choice": "FW2 (decision 2026-11-12): the web view's own context menu is suppressed app-wide in production builds only; development builds keep it (Inspect).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design. proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell request",
          "delegationScope": "the UI between the main windows (start-up, engine loss and recovery, window chrome), its design choices",
          "choice": "FD1: after a restart no full-page 'Connecting to JustSearch' replaces a booted app. The Connecting page therefore no longer shows while discovery runs; it shows when discovery found no engine.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-desktop-b2-window-preserved.json",
      "newChecks": [
        "modules/ui-web/src/boot/desktopChrome-lockdown.test.ts#FW2 production build: a text field keeps the platform edit menu; the app opens its own menus as before",
        "modules/ui-web/src/boot/desktopChrome-lockdown.test.ts#SA7 SA10 deadline survives a long connecting interval, which a running engine ends by reloading",
        "modules/ui-web/src/boot/windowLayer.test.ts#FW2 production build: text fields keep the platform edit menu",
        "modules/ui-web/src/boot/windowLayer.test.ts#FW2 development build: the web view menu stays for Inspect"
      ]
    },
    {
      "items": ["modules/ui-web/src/boot/desktopWindow.test.ts"],
      "class": "New",
      "behaviour": "Unit level in happy-dom, the window layer's controls beside a root, boot/desktopWindow.ts installed: after Minimize or Close (also clicked through an inner element of the control) focus is on the control that had it before, across shadow roots; when that control is gone (the page changed beneath the controls) focus is on the page's main landmark, made focusable once (tabindex -1, removed on blur); on the start-up frame over an inert app nothing in the app takes focus; Maximize keeps its focus and a click outside the controls moves nothing. The focus-return rule (utils/focusReturn.ts): an element that can still take focus gets it; a disconnected or inert one does not, the main content does; a main landmark's own tabindex is kept; with no main landmark nothing takes focus. The modules are new in this changeset, so there is no assertion fail-before: the repaired behaviour's fail-before is the FW1 entry's.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design. proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell request",
          "delegationScope": "the UI between the main windows (start-up, engine loss and recovery, window chrome), its design choices",
          "choice": "FW1: on Windows (and every desktop platform), after minimizing or closing to tray with the window controls and bringing the window back, keyboard focus is not on that control (one Space or Enter must not minimize or hide it again): it returns to where it was before the control was used, else the main content.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design. proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell request",
          "delegationScope": "the UI between the main windows (start-up, engine loss and recovery, window chrome), its design choices",
          "choice": "FD2 (orchestrator, 2026-11-12, reversible): when the treatment ends (engine back) with focus inside it, or after 'Open Health' from it, focus returns to where it was before the treatment (or to the main content if that is gone), never the page body; the recovery is announced once to screen readers.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-desktop-b2-focus.json",
      "newChecks": [
        "modules/ui-web/src/boot/desktopWindow.test.ts#FW1 Minimize on the running app returns focus to the control that had it before, across shadow roots",
        "modules/ui-web/src/boot/desktopWindow.test.ts#FW1 Minimize on a page whose remembered control is gone puts focus on the main content",
        "modules/ui-web/src/boot/desktopWindow.test.ts#FW1 Minimize on the start-up frame over an inert app leaves focus off the app: nothing beneath the frame takes it",
        "modules/ui-web/src/boot/desktopWindow.test.ts#FW1 Minimize used through an inner element of the control returns focus too",
        "modules/ui-web/src/boot/desktopWindow.test.ts#FW1 Close on the running app returns focus to the control that had it before, across shadow roots",
        "modules/ui-web/src/boot/desktopWindow.test.ts#FW1 Close on a page whose remembered control is gone puts focus on the main content",
        "modules/ui-web/src/boot/desktopWindow.test.ts#FW1 Close on the start-up frame over an inert app leaves focus off the app: nothing beneath the frame takes it",
        "modules/ui-web/src/boot/desktopWindow.test.ts#FW1 Close used through an inner element of the control returns focus too",
        "modules/ui-web/src/boot/desktopWindow.test.ts#FW1 Maximize keeps its focus, and a click outside the window controls moves nothing",
        "modules/ui-web/src/boot/desktopWindow.test.ts#focus returns to the remembered element when it can still take focus",
        "modules/ui-web/src/boot/desktopWindow.test.ts#an element that is gone or inert is not returned to: the main content takes focus for one visit",
        "modules/ui-web/src/boot/desktopWindow.test.ts#the main content keeps its own tabindex, and with no main content nothing takes focus"
      ]
    },
    {
      "items": ["modules/ui-web/src/boot/boot-frame-lockdown.test.ts"],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design. proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell request",
          "delegationScope": "the UI between the main windows (start-up, engine loss and recovery, window chrome), its design choices",
          "choice": "FD1: after a restart no full-page 'Connecting to JustSearch' replaces a booted app. The Connecting page therefore no longer shows while discovery runs; it shows when discovery found no engine.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none (docs/decisions has no record on the desktop Connecting page; SA7/SA10 of shell-s5-startup pin the deadline, not when Connecting shows)",
      "whatChanged": "'SA7 SA10 deadline survives a long connecting interval before the frame returns' held discovery for over 60 s to keep the Connecting page in the root, then released it and expected the stalled failure page followed by the app. FD1 retires Connecting during discovery, so a held discovery now shows the frame and its own deadline (covered by the existing 'SA10 stalled catalog discovery...' and 'SA10 60-second deadline...' cases). The case is removed from this file; its rewrite, 'SA7 SA10 deadline survives a long connecting interval, which a running engine ends by reloading' (a discovery that finds no engine shows Connecting, 60 s pass with no failure page and Connecting still in the root, and a running supervisor state reloads the window), is in modules/ui-web/src/boot/desktopChrome-lockdown.test.ts (preservation entry above). The 'connecting' surface of the window-layer cases (SW1 SW2 SW3 SW7 commands, SW1 SW2 top area) and the 'connecting' state of the twelve 'SW5 X1 window controls are legible over every surface with <palette> <mode>, and in forced colours' cases open the same page by that no-engine discovery instead of a held one; their oracle is unchanged. The harness's added `noEngine` option changes no other case. All pass on the base and the candidate (gates/test-intent/evidence/f-desktop-b2-window-adapted.json).",
      "evidence": "gates/test-intent/evidence/f-desktop-b2-window-adapted.json"
    }
  ]
}
```
