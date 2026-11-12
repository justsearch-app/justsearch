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

`src/boot/desktopEngineLossStart.test.ts` is new and isolated (`[exempt]` at the gate); it is entered
here anyway so its expectations carry sources and evidence.

Changed existing checks in `src/boot/boot-frame-lockdown.test.ts`: the harness gains a `noEngine`
option (the host's `api_port` answers no engine) and an init-script listener that holds the last
`contextmenu` event so its `defaultPrevented` is read after dispatch; neither changes another case.
The `connecting` surface (window-layer cases) and the `connecting` state of the SW5 legibility cases
opened Connecting by holding discovery forever, which FD1 retires: they now open it by a discovery
that found no engine. "SA7 SA10 deadline survives a long connecting interval
before the frame returns" becomes "... which a running engine ends by reloading" (entry below).

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
      "items": ["modules/ui-web/src/boot/boot-frame-lockdown.test.ts"],
      "class": "New",
      "repaired": true,
      "behaviour": "Production assets in headless Chromium with the native IPC double and Tauri's drag script. FW1, on the start-up frame, the engine terminal page, the running app and the held hand-off: Minimize and Close, by pointer, Enter and Space, each send exactly one native command and leave focus off the window controls; in the running app focus is back on the More control that had it before; one more Space or Enter then sends no window command. Maximize by Enter keeps its focus. FW2, on the start-up frame, the entry failure page, the engine terminal page, the running app and the held hand-off: a right-click on the top area's drag region and on the content outside text fields leaves the context-menu event's default prevented (no web view menu). FD1: discovery held for a while keeps the start-up frame in the root, no Connecting page is attached at any moment, the app then takes over with no blank sample.",
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
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#FW1 Minimize and Close leave focus where it was before the control on start-up frame, so one key cannot send the window away again",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#FW1 Minimize and Close leave focus where it was before the control on engine terminal, so one key cannot send the window away again",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#FW1 Minimize and Close leave focus where it was before the control on running app, so one key cannot send the window away again",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#FW1 Minimize and Close leave focus where it was before the control on held hand-off, so one key cannot send the window away again",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#FW2 production build: right-click on the top area and the content of start-up frame opens no web view menu",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#FW2 production build: right-click on the top area and the content of entry failure opens no web view menu",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#FW2 production build: right-click on the top area and the content of engine terminal opens no web view menu",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#FW2 production build: right-click on the top area and the content of running app opens no web view menu",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#FW2 production build: right-click on the top area and the content of held hand-off opens no web view menu",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#FD1 a slow discovery keeps the start-up frame, never a Connecting page, until the app takes over"
      ]
    },
    {
      "items": ["modules/ui-web/src/boot/boot-frame-lockdown.test.ts"],
      "class": "New",
      "behaviour": "Preservation, passing on the base and the candidate: in the production build, a right-click in the command palette's text field leaves the event's default alone (the platform edit menu stays).",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design. proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell request",
          "delegationScope": "the UI between the main windows (start-up, engine loss and recovery, window chrome), its design choices",
          "choice": "FW2: Editable text fields keep the platform's edit menu.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-desktop-b2-window.json",
      "newChecks": [
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#FW2 production build: a text field keeps the platform edit menu; the app opens its own menus as before"
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
      "whatChanged": "'SA7 SA10 deadline survives a long connecting interval before the frame returns' held discovery for over 60 s to keep the Connecting page in the root, then released it and expected the stalled failure page followed by the app. FD1 retires Connecting during discovery, so a held discovery now shows the frame and its own deadline (covered by the existing 'SA10 stalled catalog discovery...' and 'SA10 60-second deadline...' cases). The case is now 'SA7 SA10 deadline survives a long connecting interval, which a running engine ends by reloading': a discovery that finds no engine shows Connecting, 60 s pass with no failure page and Connecting still in the root, and a running supervisor state reloads the window. The 'connecting' surface of the window-layer cases (SW1 SW2 SW3 SW7 commands, SW1 SW2 top area) and the 'connecting' state of the twelve 'SW5 X1 window controls are legible over every surface with <palette> <mode>, and in forced colours' cases open the same page by that no-engine discovery instead of a held one; their oracle is unchanged. The harness's added `noEngine` option and context-menu event holder change no other case. All pass on the candidate (gates/test-intent/evidence/f-desktop-b2-window.json).",
      "evidence": "gates/test-intent/evidence/f-desktop-b2-window.json"
    }
  ]
}
```
