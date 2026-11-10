---
schema: test-intent.v1
task: t-20261103-shell
author-role: builder
author-session: shell-s5c-builder
---

# S5c: engine loss after the app has loaded

Once the app is on screen, losing the engine shows one treatment inside the shell, below the top
bar, instead of replacing the window: `components/EngineLoss.ts` over `state/engineLoss.ts`, driven
by the status pill's own "Can't reach the engine" decision (reported by `FrameStatusController`
after the existing 40 s contact window and 10 s grace) and, on the desktop, by the supervisor state
the boot listener receives (`boot/desktopRecovery.ts` hands terminal and not-ready states to the
treatment once the app is presented; the Linux update hold and pre-mount recovery are unchanged).
Health renders last known facts with their ages while the engine is lost; Copy report is
`state/engineReport.ts` with a fixed field list. No backend or native command was added; Restart
the engine is the existing `restart_engine`.

Three test files are new and isolated (`[exempt]` at the gate); they are entered here anyway so
their expectations carry sources and evidence. Each new-behaviour check fails by assertion on
`origin/main` (21fe180483): the treatment, its actions or the kept app are absent there
(`shell-s5c-browser.json`, `shell-s5c-desktop.json`, `shell-s5c-chromium.json`). The four
preservation checks (a blip shorter than the grace, polls failing with live streams, terminal
while the start-up frame still covers the app, the Windows installer hand-off) pass on the
candidate (`*-preserved.json`) and also on `origin/main`. The new test files reach the engine-loss
store's reset seam through an import the bundler does not resolve ahead of time, so that on
`origin/main`, where the module does not exist, they fail by assertion rather than at import.

One existing file changes, `src/boot/desktopRecoveryPolling.test.ts`: two cases mounted the app and
then sent an exhausted state, expecting the app replaced and every store poller stopped. SC2 makes
that state keep the loaded app (and the stores its treatment and Health read), so the cases now
use the Linux update hold, which still replaces the loaded app; the rest of their oracle is
unchanged. No other existing test changed. `src/boot/desktopRecovery.test.ts` "replaces a running
shell with terminal recovery without a successor binding" still passes unchanged: it places the
shell with `replaceChildren`, not through `mountBootApplication`, so the boot listener does not
treat it as a presented app.

The measured-axe register gains `engine-loss` (1280x800, dark) and `engine-loss-600x400-forced`;
their capture setup in `scripts/jsharness/jsharness/ui_check.py` reloads with the app's
`?lockdown=0` debug switch because SES freezes `Date`, which the page clock must replace. The
proportion register gains width and no-overlap rows for both captures.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.engineLoss.test.ts"
      ],
      "class": "New",
      "behaviour": "Browser runtime, real Shell, status pill, status store and Health over a stubbed backend (other windows are probes). After the engine is lost (no answer and no stream frame) past the 40 s contact window and the pill's 10 s grace, the window area below the top bar is inert behind \"JustSearch can’t reach its engine\" with \"Searching and indexing are on hold. Your files and your index are safe. JustSearch tries again every few seconds.\", Try again, Open Health, Copy report, \"Last connected n s ago\", \"The report leaves out paths and keys\" and the browser restart hint; no Restart the engine. The same from Search, Library and Help, beneath an open Settings window (which stays open and takes input) and with the status panel open (which stays open); the pill says Can't reach the engine at the same time. Open Health shows Health with the last known index state, searchable files and files waiting, each 'last seen <age>', the engine 'last connected <age>', 'Nothing here is live.', no live controls, connected or Up to date claims, no path or host; the treatment becomes a band with Try again and Copy report and no Open Health, and Health takes input; after recovery Health is live again. Focus moves into the treatment (named by its heading, described by its explanation, no alert or assertive region, status line empty until an action) and stays while ages update; when the engine answers the treatment leaves without a reload, the window area takes input again and focus returns to the text box; a second outage behaves the same. Try again issues one fresh status read for three clicks, says \"Still can’t reach the engine.\", and the next answer ends the outage; no wording mentions reloading. Copy report copies exactly productVersion, runtimeKind, operatingSystem, time, lastContactAgeSeconds, supervisorState, reason, recentExitCodes (browser, reason unreachable, no supervisor state) with no secret, path, host, port or error text seeded into the status body and every failing request; clipboard refusal shows the report in a focused field named \"Report ready to select\". A draft and its window survive the outage and nothing non-GET is sent again. A typed approval open during the loss keeps its modal dialog and focus and is not answered by the treatment or the recovery. An agent request announced on its live arrival before the outage is not announced again when replayed after it, and an indexing end that happened unseen gives no audible completion. A new status epoch rebinds; a late answer from the retired epoch neither shows nor ends the outage; the new epoch's answer ends it.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "FL5",
          "quote": "Engine unreachable after mount: the page dims with the explanation, Restart the engine, Open Health, Copy report; desktop \"index not ready\" no longer removes the route to Health. Wording distinguishes restarting the engine from reloading the window.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "SC1",
          "quote": "When the engine is confirmed unreachable after the app has loaded (after today's grace periods, so a short blip shows nothing), the work area dims behind \"JustSearch can't reach its engine\" with the explanation, Restart the engine, Open Health, Copy report, last connected and last tried. The top bar (pill, Settings, More, window controls) stays usable. Same from any area or page that was open.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "SC3",
          "quote": "Restart the engine (desktop) uses the existing native restart when the host accepts it, shows that a restart is under way until the engine answers or fails again, ignores repeated clicks, and when the host refuses (already starting or running, quit or update hold) says why in plain words. The browser has no restart: it says the engine must be restarted where it runs, and offers Try again. Wording never confuses restarting the engine with reloading the window.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "SC4",
          "quote": "Open Health shows the last known facts, each marked with when it was observed, and says the engine can't be reached; nothing on it claims live state.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "SC5",
          "quote": "Copy report works without the engine: a fixed list of fields (version, runtime, operating-system family, time, last contact age, supervisor state, reason, recent exit codes), no paths, hosts, keys or free error text; if the clipboard refuses, the report is shown ready to select.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "SC6",
          "quote": "When the engine comes back, the treatment leaves without a reload; a new engine identity rebinds as today and late answers from the old one are ignored; status pill, Search, Health and the treatment tell one consistent story throughout, and returning never announces old approvals or completions as new.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "SC7",
          "quote": "Drafts, the selected conversation and an interrupted request survive; nothing is resubmitted automatically. Partial degradation (worker recovering, missing AI model, stale observation) keeps keyword search and navigation usable and does not show this treatment. Polls failing while live streams continue is not an outage.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "SC8",
          "quote": "Security dialogs and typed approvals stay on top and keep their own rules; the treatment never grants or restores approval authority; expired requests stay expired.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "SC9",
          "quote": "Keyboard and assistive technology: focus moves into the treatment when it appears and back to a sensible place when it leaves, announced once; works at 600x400, enlarged text, forced colours and both themes; a second outage after recovery behaves the same.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "Wording: title \"JustSearch can’t reach its engine\" (\"JustSearch’s engine isn’t ready\" for not ready), the ref-11 explanation, \"The engine runs outside this browser. Restart it where it runs, then try again.\", \"Still can’t reach the engine. JustSearch keeps trying every few seconds.\", \"Report copied.\"; the browser offers Try again as its primary action and Try again is one fresh status read; with Health on screen the treatment becomes a band above it without Open Health so Health stays readable and takes input; focus goes to the treatment's region once per outage (not while a modal holds it) and back to the element focused before, else the window area; the Health last-known view lists engine, engine process and its own words (desktop), index state, searchable files and files waiting, each with its age, and shows none of Health's live cards or controls.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5c-browser.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.engineLoss.test.ts#dims the window area from Search behind the explanation, actions and times, keeping the top bar",
        "modules/ui-web/src/shell-v0/chrome/Shell.engineLoss.test.ts#dims the window area from Library behind the explanation, actions and times, keeping the top bar",
        "modules/ui-web/src/shell-v0/chrome/Shell.engineLoss.test.ts#dims the window area from Help behind the explanation, actions and times, keeping the top bar",
        "modules/ui-web/src/shell-v0/chrome/Shell.engineLoss.test.ts#appears beneath an open Settings window, which stays open and usable",
        "modules/ui-web/src/shell-v0/chrome/Shell.engineLoss.test.ts#appears with the status panel open, which stays open and says the engine cannot be reached",
        "modules/ui-web/src/shell-v0/chrome/Shell.engineLoss.test.ts#shows the last known facts, each with when it was seen, and nothing live",
        "modules/ui-web/src/shell-v0/chrome/Shell.engineLoss.test.ts#moves focus in once, leaves without a reload when the engine is back, returns focus, and a second outage behaves the same",
        "modules/ui-web/src/shell-v0/chrome/Shell.engineLoss.test.ts#reads the status once per request, says the engine still cannot be reached, then leaves when it answers",
        "modules/ui-web/src/shell-v0/chrome/Shell.engineLoss.test.ts#copies only the fixed fields, with no path, host, key or error text",
        "modules/ui-web/src/shell-v0/chrome/Shell.engineLoss.test.ts#shows the report ready to select when the clipboard refuses it",
        "modules/ui-web/src/shell-v0/chrome/Shell.engineLoss.test.ts#keeps the draft and the open window, and sends nothing again when the engine is back",
        "modules/ui-web/src/shell-v0/chrome/Shell.engineLoss.test.ts#a typed approval open during the loss keeps its dialog, focus and rules; the treatment answers nothing",
        "modules/ui-web/src/shell-v0/chrome/Shell.engineLoss.test.ts#returning never announces an approval already waiting, or a completion that happened unseen, as new",
        "modules/ui-web/src/shell-v0/chrome/Shell.engineLoss.test.ts#a new engine identity rebinds; a late answer from the old one neither ends the outage nor shows"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.engineLoss.test.ts"
      ],
      "class": "New",
      "behaviour": "Preserved behaviour, browser runtime: a contact gap that reaches the unreachable state but ends before the pill's grace shows no treatment at any second and the window area stays usable; failing polls while a stream heartbeat arrives every 15 s for three minutes shows no treatment and the pill never says Can't reach the engine.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "SC1",
          "quote": "When the engine is confirmed unreachable after the app has loaded (after today's grace periods, so a short blip shows nothing), the work area dims behind \"JustSearch can't reach its engine\" with the explanation, Restart the engine, Open Health, Copy report, last connected and last tried. The top bar (pill, Settings, More, window controls) stays usable. Same from any area or page that was open.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "SC7",
          "quote": "Drafts, the selected conversation and an interrupted request survive; nothing is resubmitted automatically. Partial degradation (worker recovering, missing AI model, stale observation) keeps keyword search and navigation usable and does not show this treatment. Polls failing while live streams continue is not an outage.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5c-browser-preserved.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.engineLoss.test.ts#a contact gap shorter than the grace shows nothing",
        "modules/ui-web/src/shell-v0/chrome/Shell.engineLoss.test.ts#polls failing while live streams keep arriving is not an outage"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/boot/desktopEngineLoss.test.ts"
      ],
      "class": "New",
      "behaviour": "Desktop runtime, real boot listener and Shell, native command and event channels as doubles. After the app is presented, not ready, a terminal stop, exhausted restarts and exhausted on a locked data folder keep the app and its stores (no recovery page, no teardown) and show the treatment with the supervisor's reason in plain words (\"The engine is running, but its index is not ready.\", \"The engine stopped.\", \"The engine stopped 3 times, so JustSearch stopped restarting it.\", \"Another program is using JustSearch’s data folder. Close it, then restart the engine.\"), Restart the engine, Open Health, Copy report and no reload wording, with the window area inert; a later running state with contact removes it. The Linux update hold still replaces the loaded app (recovery page, teardown once) after the treatment showed. Restart the engine sends one restart_engine for repeated clicks, says \"Restarting the engine…\" on its button and \"Restarting the engine… This window stays open.\" until the supervisor is running and contact returns, then leaves without a reload; starting then exhausted again says \"The engine stopped again.\" and offers the restart again. Each of the six host refusals of request_manual_restart is said in plain words without the host's text, and the restart and Open Health stay offered. A backend-restart event during the treatment reloads the window once, as before. Copy report with secrets in every supervisor field (reason, requested reason, not-ready text, last exit reason, crash file, instance id, pid, port, recent exit code keys) and in the refusal copies only the fixed fields: desktop, Linux, exhausted, reason restart_exhausted:spawn_failed, recent exit codes {3: 4, 1: 1}, and no secret, path, port, pid or crash text; the treatment shows none of them either.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "FL5",
          "quote": "Engine unreachable after mount: the page dims with the explanation, Restart the engine, Open Health, Copy report; desktop \"index not ready\" no longer removes the route to Health. Wording distinguishes restarting the engine from reloading the window.",
          "label": "agent-drafted, owner-adopted"
        },
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
          "scenario": "SC3",
          "quote": "Restart the engine (desktop) uses the existing native restart when the host accepts it, shows that a restart is under way until the engine answers or fails again, ignores repeated clicks, and when the host refuses (already starting or running, quit or update hold) says why in plain words. The browser has no restart: it says the engine must be restarted where it runs, and offers Try again. Wording never confuses restarting the engine with reloading the window.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "SC5",
          "quote": "Copy report works without the engine: a fixed list of fields (version, runtime, operating-system family, time, last contact age, supervisor state, reason, recent exit codes), no paths, hosts, keys or free error text; if the clipboard refuses, the report is shown ready to select.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "SC6",
          "quote": "When the engine comes back, the treatment leaves without a reload; a new engine identity rebinds as today and late answers from the old one are ignored; status pill, Search, Health and the treatment tell one consistent story throughout, and returning never announces old approvals or completions as new.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "FL6",
          "quote": "Desktop recovery works without the engine; terminal events beat late mounting; Linux update hold replaces the shell and Windows keeps it; factory-reset gates run before stores and block boot until resolved; customization (theme, presentation, plugins, layouts) restores before the first surface.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "Supervisor loss after the app is presented (attached and no longer covered by the start-up frame) shows the treatment at once, without the pill's grace, since the host has confirmed it; while the frame still covers the app the recovery page keeps the root. A restart is under way from the accepted request until the supervisor reports running without not ready, and fails again when the supervisor returns to a stopped, exhausted or not-ready state after leaving it. Refusals are matched by the host's fixed refusal sentences and worded for the person; unknown refusals say \"The engine could not be restarted.\" The desktop offers Restart the engine, not Try again. The report's reason is a code from a closed list (unreachable, not_ready, restart_exhausted, stopped) with an optional exit-register reason, spawn_failed or restart_failed; exit codes are integers only.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5c-desktop.json",
      "newChecks": [
        "modules/ui-web/src/boot/desktopEngineLoss.test.ts#not ready: the same treatment, the reason in plain words, Health reachable",
        "modules/ui-web/src/boot/desktopEngineLoss.test.ts#a terminal stop: the same treatment, the reason in plain words, Health reachable",
        "modules/ui-web/src/boot/desktopEngineLoss.test.ts#exhausted restarts: the same treatment, the reason in plain words, Health reachable",
        "modules/ui-web/src/boot/desktopEngineLoss.test.ts#exhausted on a locked data folder: the same treatment, the reason in plain words, Health reachable",
        "modules/ui-web/src/boot/desktopEngineLoss.test.ts#the Linux update hold still replaces the loaded app",
        "modules/ui-web/src/boot/desktopEngineLoss.test.ts#uses the native restart once for repeated clicks, shows it under way, and leaves when the engine answers",
        "modules/ui-web/src/boot/desktopEngineLoss.test.ts#says when the restarted engine fails again, and a later restart is offered again",
        "modules/ui-web/src/boot/desktopEngineLoss.test.ts#a refusal (Quit or upgrade is in progress; restart refused) is said in plain words and the treatment stays usable",
        "modules/ui-web/src/boot/desktopEngineLoss.test.ts#a refusal (An update holds Engine replacement; restart refused) is said in plain words and the treatment stays usable",
        "modules/ui-web/src/boot/desktopEngineLoss.test.ts#a refusal (Engine has no terminal state; restart refused) is said in plain words and the treatment stays usable",
        "modules/ui-web/src/boot/desktopEngineLoss.test.ts#a refusal (The Engine is already starting or running; restart refused) is said in plain words and the treatment stays usable",
        "modules/ui-web/src/boot/desktopEngineLoss.test.ts#a refusal (Engine was stopped for quit or upgrade; restart refused) is said in plain words and the treatment stays usable",
        "modules/ui-web/src/boot/desktopEngineLoss.test.ts#a refusal (The previous supervision generation is finishing; try again) is said in plain words and the treatment stays usable",
        "modules/ui-web/src/boot/desktopEngineLoss.test.ts#rebinds as today: the session is dropped and the window reloads",
        "modules/ui-web/src/boot/desktopEngineLoss.test.ts#copies the supervisor facts as fixed fields and never its free text"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/boot/desktopEngineLoss.test.ts"
      ],
      "class": "New",
      "behaviour": "Preserved behaviour, desktop: a terminal event while the start-up frame still covers the mounted app gives the root to the recovery page and the app leaves; on Windows an upgrade stop after the app loaded keeps the app, tears nothing down and shows no treatment.",
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
      "evidence": "gates/test-intent/evidence/shell-s5c-desktop-preserved.json",
      "newChecks": [
        "modules/ui-web/src/boot/desktopEngineLoss.test.ts#before the app has loaded, the existing recovery page stays, also while the start-up frame still covers the app",
        "modules/ui-web/src/boot/desktopEngineLoss.test.ts#Windows keeps its installer hand-off: an upgrade stop shows no treatment and keeps the app"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.engineLoss-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "Real Chromium, real Shell, top bar, status pill, status store and Health over a private Vite server with a stubbed backend; the page clock passes the contact window and the pill's grace. At 1280x800, 900x800 and 600x400 in dark, light and forced colours, and with 200 % text at 600x400 (dark) and 900x800 (light): the treatment shows and holds focus; nothing in it or the top bar leaves the window sideways and the page does not scroll sideways; its actions do not overlap; the top bar's Settings control is not covered; every text of the treatment is at least 4.5:1 against what is painted behind it; Tab reaches Try again, Open Health and Copy report in that order, each inside the window with a visible focus ring; no animation runs under reduced motion. axe finds no violation inside the treatment in dark, light or forced colours. A draft in the text box survives two outages; each time focus moves into the treatment, a click on the dimmed window area does not reach it, and focus returns to the text box when the engine answers; nothing but the app's background activity and action-ledger reports is sent. At 600x400, Open Health turns the treatment into a band, Health's last-known view is visible and takes input, and nothing leaves the window sideways.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "SC1",
          "quote": "When the engine is confirmed unreachable after the app has loaded (after today's grace periods, so a short blip shows nothing), the work area dims behind \"JustSearch can't reach its engine\" with the explanation, Restart the engine, Open Health, Copy report, last connected and last tried. The top bar (pill, Settings, More, window controls) stays usable. Same from any area or page that was open.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "SC9",
          "quote": "Keyboard and assistive technology: focus moves into the treatment when it appears and back to a sensible place when it leaves, announced once; works at 600x400, enlarged text, forced colours and both themes; a second outage after recovery behaves the same.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "SC4",
          "quote": "Open Health shows the last known facts, each marked with when it was observed, and says the engine can't be reached; nothing on it claims live state.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "SC6",
          "quote": "When the engine comes back, the treatment leaves without a reload; a new engine identity rebinds as today and late answers from the old one are ignored; status pill, Search, Health and the treatment tell one consistent story throughout, and returning never announces old approvals or completions as new.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "SC7",
          "quote": "Drafts, the selected conversation and an interrupted request survive; nothing is resubmitted automatically. Partial degradation (worker recovering, missing AI model, stale observation) keeps keyword search and navigation usable and does not show this treatment. Polls failing while live streams continue is not an outage.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "X1",
          "quote": "Dark, light and Windows high contrast for every frame component; contrast ≥ 4.5:1 for text; colour never the only signal; reduced motion respected; no new hard-coded colours, sizes or z-indexes (ui-web gates).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "X2",
          "quote": "Sizes 1280×800, 900×800, 600×400: nothing overflows sideways; ui-a11y-gate shows no new violations; the proportion baseline is updated deliberately.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "The treatment's region takes focus without drawing a ring of its own (its heading names it; a ring around the whole panel would read as the protected security dialog's teal border); its actions keep their focus rings. The panel sits on the window surface over the dialog dim, so its text contrast does not depend on what is behind it; in forced colours the dim and panel are Canvas with CanvasText. The panel scrolls inside the window area when it is taller than the window.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5c-chromium.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.engineLoss-lockdown.test.ts#1280 x 800, 'dark', text x1: readable, inside the window, focus moved in, top bar usable",
        "modules/ui-web/src/shell-v0/chrome/Shell.engineLoss-lockdown.test.ts#1280 x 800, 'light', text x1: readable, inside the window, focus moved in, top bar usable",
        "modules/ui-web/src/shell-v0/chrome/Shell.engineLoss-lockdown.test.ts#1280 x 800, 'forced', text x1: readable, inside the window, focus moved in, top bar usable",
        "modules/ui-web/src/shell-v0/chrome/Shell.engineLoss-lockdown.test.ts#900 x 800, 'dark', text x1: readable, inside the window, focus moved in, top bar usable",
        "modules/ui-web/src/shell-v0/chrome/Shell.engineLoss-lockdown.test.ts#900 x 800, 'light', text x1: readable, inside the window, focus moved in, top bar usable",
        "modules/ui-web/src/shell-v0/chrome/Shell.engineLoss-lockdown.test.ts#900 x 800, 'forced', text x1: readable, inside the window, focus moved in, top bar usable",
        "modules/ui-web/src/shell-v0/chrome/Shell.engineLoss-lockdown.test.ts#600 x 400, 'dark', text x1: readable, inside the window, focus moved in, top bar usable",
        "modules/ui-web/src/shell-v0/chrome/Shell.engineLoss-lockdown.test.ts#600 x 400, 'light', text x1: readable, inside the window, focus moved in, top bar usable",
        "modules/ui-web/src/shell-v0/chrome/Shell.engineLoss-lockdown.test.ts#600 x 400, 'forced', text x1: readable, inside the window, focus moved in, top bar usable",
        "modules/ui-web/src/shell-v0/chrome/Shell.engineLoss-lockdown.test.ts#600 x 400, 'dark', text x2: readable, inside the window, focus moved in, top bar usable",
        "modules/ui-web/src/shell-v0/chrome/Shell.engineLoss-lockdown.test.ts#900 x 800, 'light', text x2: readable, inside the window, focus moved in, top bar usable",
        "modules/ui-web/src/shell-v0/chrome/Shell.engineLoss-lockdown.test.ts#adds no axe violation, in both themes and forced colours",
        "modules/ui-web/src/shell-v0/chrome/Shell.engineLoss-lockdown.test.ts#focus returns to the text box when the engine is back, the draft is kept, nothing is sent, twice",
        "modules/ui-web/src/shell-v0/chrome/Shell.engineLoss-lockdown.test.ts#Open Health at 600x400 steps aside to a band; Health is readable and takes input"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/boot/desktopRecoveryPolling.test.ts"
      ],
      "class": "Obsolete",
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
      "conflictSearch": "decision records searched for a conflict: none",
      "whatChanged": "The two 'stops all real store pollers with pending/scheduled requests' cases mounted the application and then sent an exhausted state, expecting the recovery page and every store poller aborted. SC2 makes an exhausted engine after the app has loaded keep the app (and its stores, which the in-shell treatment and Health need). The cases now send the Linux update hold ('stopped', reason 'upgrade', with the platform reported as Linux), which still replaces the loaded app per SC2 and FL6; every other part of the oracle is unchanged: no request aborted at 'restarting', the recovery page mounted, all five requests aborted, no further request and no timer after 30 s. afterEach now restores the platform spy. The other two cases (before the app mounts) are unchanged. All four cases pass on the candidate (gates/test-intent/evidence/shell-s5c-recovery-polling.json).",
      "evidence": "gates/test-intent/evidence/shell-s5c-recovery-polling.json"
    }
  ]
}
```

## Acceptance records
