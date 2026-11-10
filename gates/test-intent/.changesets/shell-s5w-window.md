---
schema: test-intent.v1
task: t-20261103-shell
author-role: builder
author-session: shell-s5w-builder
---

# S5w: desktop window controls and moving in every window state

The desktop window has no system title bar (decorations off). One window layer beside `#root`,
created by `index.html`'s own script through the internal native bridge before any application
module, holds Minimize, Maximize/Restore and Close; the top area of every surface is a Tauri drag
region with every interactive element excluded; the main window's capability gains exactly the
four window permissions. The browser runtime has neither the layer nor drag attributes.

`src/boot/boot-frame-lockdown.test.ts` gains 55 checks (SW1–SW9 with X1/X2) against the
production build in real Chromium, with the native IPC double and Tauri's own drag-region script
from the locked `tauri` crate, as the desktop runtime injects it. 54 of them pin new behaviour and,
with the revised SA1, fail by assertion on `origin/main` (`shell-s5w-window-controls.json`); the
browser check SW6 preserves behaviour and passes on both (`shell-s5w-browser.json`). The first
fail-before run showed 24 checks (the SW5 room checks, SW9 and the SW2 after-hand-off check)
failing on `origin/main` with an exception: they clicked or measured a control that does not exist
there (locator timeout, null box). Each now first asserts that the page shows exactly the three
controls, as the other checks already do, so the before-state fails at that assertion; nothing
else in them changed.

Two existing assertions were revised, with their reasons:

- SA1 (“first paint precedes entry and is inert”) keeps every earlier assertion: `#root` holds no
  button, link, input, tab stop or top bar, and Tab never focuses inside `#boot-frame`. It adds that
  the page's only controls are the window layer's three, and that Tab reaches Minimize. The frame
  itself still has no controls; the layer lives beside it (SW2: the controls work on the start-up
  frame). This revised check fails by assertion on `origin/main`, so it is in the repaired evidence.
- SA6 (“rendered shell rejects input until frame hand-off”) asserted that focus is on `body` after
  the hand-off. With the window layer, Tab during the frame reaches the window controls, as SW2 and
  SW5 require, so focus before the hand-off is either `body` or a window control. The revised
  assertion keeps the substance — the hand-off moves no focus: the element focused before it is
  still focused after it — and adds that focus before it is only `body` or the layer. Every input
  assertion of SA6 (clicks, keys, menus, shell focus) is unchanged. It passes on `origin/main`
  (focus stays on `body` there), so it is in the non-repaired evidence with SW6.

The SW5 X1 X2 room checks cover the decision made after the first build round: the top bar
chooses its layout from the room it actually has after the window controls (a CSS container in
rem, so enlarged text picks a smaller layout), with a smaller layout below 30rem of room; the
three controls keep one fixed size at any text size, never below a 24 × 24 px target. With the
earlier viewport breakpoints the bar's own controls overlapped at 900 px and 600 px with 200 %
text (`Brain × Settings`, `Search, choose area × Settings`); with a text-scaled control size
(temporarily `2rem`) the fixed-size assertion failed (`64×64` against `32×32`).

The Rust check `capability_tests::sw8_window_controls_are_granted_to_the_main_window_only` reads
`tauri.conf.json` and every capability file as shipped: no inline capabilities, exactly one
capability file, only `default` holds a window-control permission, it is scoped to `["main"]`
with no webview, remote or platform widening, and its `core:window:*` set is exactly the four new
permissions plus the four it held before. `lib.rs` only declares the `#[cfg(test)]` module. It
fails by assertion on `origin/main` (`holders` is empty: `left: []`, `right: ["default"]`)
(`shell-s5w-capability.json`). Its before run overlays the candidate `lib.rs`, whose only
difference from the base is the two-line `#[cfg(test)] mod capability_tests;` declaration, so the
before-state keeps the base capability file and production code.

`token-names.generated.ts` is regenerated after adding four `--frame-window-control-*` tokens.

Repair after the independent check (SW5, X1). With a saved frame copy from a pale palette in dark
mode (Registrar's Ledger), the window controls kept the dark-default white ink over the restored
pale frame: 1.08:1 on the start-up frame and 1.02:1 on the failure page. The layer sits beside
`#root` and could not see the saved colours, which were set on the frame element. The early script
now sets them on the document root; while `#root` holds the frame or the failure page, the layer
reads the same saved colours and the same frame defaults (the build's frame-token scope includes
the layer then); afterwards it reads the app's tokens. Hover and pressed use the other frame
surface as fill, and the focus ring uses the surface's primary text colour. No new colour literal.

`SW5 X1 window controls are legible over every surface with <palette> <mode>, and in forced
colours` (12 checks, one per shipped palette and mode, read from `public/themes/manifest.json`)
measures each control's icon, hover, pressed state and focus ring against what is painted behind
it. Against the unrepaired candidate (`204ea8a982`) 7 of the 12 failed by assertion: the reported
Registrar's Ledger dark case (1.08 and 1.02), every pale-palette-in-dark and dark-palette-in-light
frame and failure page, the hand-off without a saved copy (frame defaults beneath, palette ink on
the layer: down to 1.12), and the Sepia Focus focus ring (accent 2.41:1 on its own surface, also in
the running app and recovery). Forced colours already passed. On `origin/main` all 12 fail by
assertion because there is no layer (`shell-s5w-window-controls.json`, regenerated with the other
55). The two SA5 checks now read the restored accent from the root instead of the frame element;
nothing else in them changed (`shell-s5w-frame-colours.json`, candidate run).

Second repair after the independent check (SW5, X1). In forced colours, a keyboard-focused
control that was then hovered or pressed lost its focus ring: the ring was inset in `Highlight` on
a `Highlight` fill, 1.45:1 in dark and 1.57:1 in light, on all three controls and every surface.
The ring is now drawn just outside the control (a positive offset of one hairline), against the
surface, in every colour scheme. In forced colours it is `CanvasText`, and the hover and pressed
fill is the `SelectedItem` / `SelectedItemText` pair. `Highlight` cannot be the fill: in Chromium's
emulated dark scheme it is 2.41:1 against `CanvasText`, so no ring colour reaches 3:1 against both
it and the black surface. With the new pair, the ring is 5.36:1 (dark) and 3.92:1 (light) against
the fill and 21:1 against the surface, and the icon is 5.36:1 on the fill. No size or colour
literal was added. Normal colours keep the surface's primary text colour for the ring.

`controlContrasts`, used by the 12 `SW5 X1 window controls are legible…` checks, now also measures
focus kept while the pointer hovers (`focus ring + hover`) and presses (`focus ring + pressed`).
In both, the icon must be at least 4.5:1 against its fill and the ring at least 3:1 against both
the fill and the surface beside it; the ring is measured as painted (over the fill when inset,
over the surface when drawn outside). The earlier states keep their 4.5:1 thresholds. On the
unrepaired candidate (`11402bc1c3`) all 12 failed by assertion, with 72 forced-colour findings each
(`focus ring + hover` and `+ pressed` × 3 controls × 12 surfaces, 1.45 or 1.57 against the fill);
normal colours passed. The forced-colours case of the ten `SW5 X1 X2 … controls fit` checks
asserted the ring colour `Highlight`. It now asserts `CanvasText` and a positive offset; nothing
else in those checks changed. All three evidence files citing this test file were regenerated:
`shell-s5w-window-controls.json` (67 pass; 67 fail by assertion on `origin/main`),
`shell-s5w-browser.json` and `shell-s5w-frame-colours.json` (candidate runs).

Evidence used the unchanged official producer. Its before-state was a shared clone under TMPDIR,
not a worktree registered in the source repository: a temporary `git` wrapper on PATH turned the
producer's `worktree add --detach <dir> <ref>` into `git clone --shared --no-checkout` plus a
detached checkout of the same commit, and its `worktree remove` into removing that TMPDIR folder.
No test or result was intercepted. All browsers were headless. Checks owned their preview servers,
browser contexts and HTTP fixtures; no shared dev stack was started or used.

Rust baseline comparison: `cargo test --lib --locked --offline` fails 55 tests on both
`origin/main` (shared clone, own target folder) and this tree, with identical test names (most
are `spawn java failed`); none is new, and the new check passes. `cargo clippy --all-targets`
reports the same existing error (`updater_v2_tests.rs:187`) and the same warnings on both trees;
CI runs no clippy step.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "Desktop runtime, production build: on every surface (start-up frame; entry, evaluation and stalled failure pages; connecting; engine terminal, not ready and Linux update hold; reset busy, error and complete; running app; held hand-off with the app still unreachable) Minimize, Maximize and Close send exactly plugin:window|minimize, toggle_maximize and close by pointer and keyboard, Close only hides as the platform close does, and nothing reveals a hidden window. The empty top area of each surface starts a drag and a double-click maximizes or restores; every interactive control and menu in the top area is excluded. Controls and moving keep working after the hand-off. The maximize control shows Restore or Maximize from the window's real state, also when it starts maximized or changes by other means. Refused operations (tiling compositors) raise no error and change nothing. Each control is reachable in order with a visible 2 px focus ring; at 1280x800, 900x800 and 600x400, at 100 % and 200 % text, in both themes and forced colours: the layer stays inside the window, overlaps no other top-area control, the top bar's own controls do not overlap one another, recovery actions stay clear and reachable, the controls keep one size of at least 24 x 24 px, glyph contrast is at least 4.5:1, no transition runs under reduced motion, and the layer adds no axe violation. SA1 additionally requires that the start-up page's only controls are the layer's three and that Tab reaches Minimize. For every shipped palette (public/themes/manifest.json) in dark and light, and in forced colours, each control's icon, hover, pressed state and focus ring is at least 4.5:1 against what is painted behind it, and with keyboard focus kept while the pointer hovers or presses the icon stays at least 4.5:1 against its fill and the focus ring at least 3:1 against both the fill and the surface beside it, on the running app after the palette applied, the start-up frame and the failure page with the saved frame copy (styles loaded and blocked) and without it, the hand-off with and without the saved copy, connecting, engine recovery and factory reset.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "SW1",
          "quote": "Desktop app only: minimize, maximize/restore and close controls at the right end of the top area, and dragging any empty part of the top area moves the window; double-click there maximizes or restores. Interactive controls in the top bar (areas, status pill, Settings, more, their menus) never start a drag.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "SW2",
          "quote": "The same move and controls work in every window state: the start-up frame, the failure page (entry failure, evaluation failure, stalled), connecting, engine recovery (terminal, not ready, Linux update hold), factory-reset recovery (busy, error, complete), and the running app; also during the hand-off while input to the app is blocked, without that unblocking the app.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "SW3",
          "quote": "Close does exactly what the platform close does today: hide to the tray with the engine still running; tray Show, tray click, a second launch and search summon bring it back; tray Quit still quits.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "SW4",
          "quote": "Maximize and restore are a working pair and the control shows the real state, also when the window starts maximized from saved state or the state changes by other means (keyboard, desktop, double-click). Minimize is restored by tray, second launch and search summon.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "SW5",
          "quote": "Keyboard and assistive technology: each control is reachable, named, has a visible focus ring, works in forced colours and both themes, and the area stays usable at 600x400 and enlarged text without covering existing navigation or recovery actions.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "SW7",
          "quote": "Launch visibility: normal launch shows the window, a hidden (autostart) launch stays hidden, search summon and reset recovery still force it visible; no control or listener reveals a deliberately hidden window. Saved geometry (size, position, maximized) restores as today, also after factory reset; edge resizing keeps working.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "SW9",
          "quote": "Tiling compositors (for example Hyprland): an operation the compositor does not support does nothing harmful and raises no error.",
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
          "delegationScope": "The frame-build plan, S5w: desktop window controls and moving within the settled SW scenarios and the binding design (one persistent layer outside #root, drag regions on every top area, the hand-off guard passing only the layer, maximize state read from the window, exactly four permissions, nothing in the browser).",
          "choice": "Controls order Minimize, Maximize/Restore, Close, placed after More; the layer is a region named Window; its buttons are 32 x 32 px icon buttons whose size does not follow text size (at least the 24 x 24 px target); the maximize label and icon follow is_maximized, re-read on resize, focus and visibility changes; the top bar reserves the layer's fixed width and chooses its layout from the room left, in rem, with an icon-only area button below 30rem; SA1 and SA6 keep their inertness and input assertions while accepting the layer as the page's only controls.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "The frame-build plan, S5w repair after the independent check: the window layer takes its colours from the same source as the surface beneath it, the saved frame colours while the frame or failure page shows and the app's tokens once the app's theme applies, with no new hard-coded colours.",
          "choice": "The early script sets the saved frame colours on the document root instead of the frame element, so the frame, the failure page and the window layer beside #root all read them; while #root holds the frame or the failure page, the layer reads the same frame colours and frame defaults; its hover and pressed fill is the other frame surface (background on the raised frame bar, raised on the failure page); its focus ring uses the surface's primary text colour, because some palettes' accents are below 4.5:1 on their own surfaces.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "The frame-build plan, S5w second repair after the independent check: the focus indicator stays distinguishable from any fill the control can have, drawn outside the control against the surface in a system colour that differs from the fill, keeping the 32 px fixed size and no new hard-coded colours outside forced-colours system colours.",
          "choice": "The focus ring is drawn one hairline outside the control in every colour scheme; in forced colours it is CanvasText and the hover and pressed fill is SelectedItem with SelectedItemText icons, because Highlight is 2.41:1 against CanvasText in the emulated dark scheme.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s5w-window-controls.json",
      "newChecks": [
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SA1 SA6 first paint precedes entry and is inert",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW1 SW2 SW3 SW7 window controls send their command on start-up frame, by pointer and keyboard",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW1 SW2 SW3 SW7 window controls send their command on entry failure, by pointer and keyboard",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW1 SW2 SW3 SW7 window controls send their command on evaluation failure, by pointer and keyboard",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW1 SW2 SW3 SW7 window controls send their command on stalled failure, by pointer and keyboard",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW1 SW2 SW3 SW7 window controls send their command on connecting, by pointer and keyboard",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW1 SW2 SW3 SW7 window controls send their command on engine terminal, by pointer and keyboard",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW1 SW2 SW3 SW7 window controls send their command on engine not ready, by pointer and keyboard",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW1 SW2 SW3 SW7 window controls send their command on Linux update hold, by pointer and keyboard",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW1 SW2 SW3 SW7 window controls send their command on reset busy, by pointer and keyboard",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW1 SW2 SW3 SW7 window controls send their command on reset error, by pointer and keyboard",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW1 SW2 SW3 SW7 window controls send their command on reset complete, by pointer and keyboard",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW1 SW2 SW3 SW7 window controls send their command on running app, by pointer and keyboard",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW1 SW2 SW3 SW7 window controls send their command on held hand-off, by pointer and keyboard",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW1 SW2 top area of start-up frame moves the window; interactive controls never start a drag",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW1 SW2 top area of entry failure moves the window; interactive controls never start a drag",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW1 SW2 top area of evaluation failure moves the window; interactive controls never start a drag",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW1 SW2 top area of stalled failure moves the window; interactive controls never start a drag",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW1 SW2 top area of connecting moves the window; interactive controls never start a drag",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW1 SW2 top area of engine terminal moves the window; interactive controls never start a drag",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW1 SW2 top area of engine not ready moves the window; interactive controls never start a drag",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW1 SW2 top area of Linux update hold moves the window; interactive controls never start a drag",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW1 SW2 top area of reset busy moves the window; interactive controls never start a drag",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW1 SW2 top area of reset error moves the window; interactive controls never start a drag",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW1 SW2 top area of reset complete moves the window; interactive controls never start a drag",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW1 SW2 top area of running app moves the window; interactive controls never start a drag",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW1 SW2 top area of held hand-off moves the window; interactive controls never start a drag",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW2 controls and moving keep working after the hand-off releases the app",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW4 maximize control follows the real window state on running app",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW4 maximize control follows the real window state on entry failure",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW4 maximize control follows the real window state on start-up frame",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW9 refused window operations on start-up frame do nothing harmful and raise no error",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW9 refused window operations on entry failure do nothing harmful and raise no error",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW9 refused window operations on running app do nothing harmful and raise no error",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW5 every control is reachable by keyboard in order, with a visible focus ring, after the app and on the failure page",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW5 X1 X2 entry failure dark forced=false: controls fit at every size and enlarged text, axe clean",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW5 X1 X2 engine terminal dark forced=false: controls fit at every size and enlarged text, axe clean",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW5 X1 X2 reset complete dark forced=false: controls fit at every size and enlarged text, axe clean",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW5 X1 X2 running app dark forced=false: controls fit at every size and enlarged text, axe clean",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW5 X1 X2 start-up frame dark forced=false: controls fit at every size and enlarged text, axe clean",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW5 X1 X2 entry failure dark forced=true: controls fit at every size and enlarged text, axe clean",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW5 X1 X2 engine terminal dark forced=true: controls fit at every size and enlarged text, axe clean",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW5 X1 X2 reset complete dark forced=true: controls fit at every size and enlarged text, axe clean",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW5 X1 X2 running app dark forced=true: controls fit at every size and enlarged text, axe clean",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW5 X1 X2 start-up frame dark forced=true: controls fit at every size and enlarged text, axe clean",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW5 X1 X2 entry failure light forced=false: controls fit at every size and enlarged text, axe clean",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW5 X1 X2 engine terminal light forced=false: controls fit at every size and enlarged text, axe clean",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW5 X1 X2 reset complete light forced=false: controls fit at every size and enlarged text, axe clean",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW5 X1 X2 running app light forced=false: controls fit at every size and enlarged text, axe clean",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW5 X1 X2 start-up frame light forced=false: controls fit at every size and enlarged text, axe clean",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW5 X1 X2 entry failure light forced=true: controls fit at every size and enlarged text, axe clean",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW5 X1 X2 engine terminal light forced=true: controls fit at every size and enlarged text, axe clean",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW5 X1 X2 reset complete light forced=true: controls fit at every size and enlarged text, axe clean",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW5 X1 X2 running app light forced=true: controls fit at every size and enlarged text, axe clean",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW5 X1 X2 start-up frame light forced=true: controls fit at every size and enlarged text, axe clean",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW5 X1 window controls are legible over every surface with core.nord dark, and in forced colours",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW5 X1 window controls are legible over every surface with core.nord light, and in forced colours",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW5 X1 window controls are legible over every surface with core.sepia-focus dark, and in forced colours",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW5 X1 window controls are legible over every surface with core.sepia-focus light, and in forced colours",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW5 X1 window controls are legible over every surface with core.high-vis dark, and in forced colours",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW5 X1 window controls are legible over every surface with core.high-vis light, and in forced colours",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW5 X1 window controls are legible over every surface with core.registrars-ledger dark, and in forced colours",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW5 X1 window controls are legible over every surface with core.registrars-ledger light, and in forced colours",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW5 X1 window controls are legible over every surface with core.night-registry dark, and in forced colours",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW5 X1 window controls are legible over every surface with core.night-registry light, and in forced colours",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW5 X1 window controls are legible over every surface with core.cyanotype dark, and in forced colours",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW5 X1 window controls are legible over every surface with core.cyanotype light, and in forced colours"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "Browser runtime: on every surface there is no window layer, no Window region, no drag-region attribute in any tree, no reserved width for controls and no native window command or error. SA6 (desktop runtime): Tab during the held frame reaches only the desktop window controls or nothing, and the hand-off leaves focus on the element focused before it; every SA6 input assertion is unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "SW6",
          "quote": "Browser use: no window controls, no drag behaviour and no native-command errors, on every surface.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "SW2",
          "quote": "The same move and controls work in every window state: the start-up frame, the failure page (entry failure, evaluation failure, stalled), connecting, engine recovery (terminal, not ready, Linux update hold), factory-reset recovery (busy, error, complete), and the running app; also during the hand-off while input to the app is blocked, without that unblocking the app.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "SW5",
          "quote": "Keyboard and assistive technology: each control is reachable, named, has a visible focus ring, works in forced colours and both themes, and the area stays usable at 600x400 and enlarged text without covering existing navigation or recovery actions.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5w-browser.json",
      "newChecks": [
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SW6 browser runtime has no window layer, drag regions or native commands on any surface",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SA6 rendered shell rejects input until frame hand-off"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "SA5 (both checks) keeps every earlier assertion about the saved frame copy (mode, accent and the byte-identical cache across reloads) and now reads the restored accent from the document root, where the early script sets the saved frame colours so the window layer beside #root can read them; the frame still shows them.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "X1",
          "quote": "Dark, light and Windows high contrast for every frame component; contrast ≥ 4.5:1 for text; colour never the only signal; reduced motion respected; no new hard-coded colours, sizes or z-indexes (ui-web gates).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "The frame-build plan, S5w repair after the independent check: the window layer takes its colours from the same source as the surface beneath it, the saved frame colours while the frame or failure page shows and the app's tokens once the app's theme applies, with no new hard-coded colours.",
          "choice": "The early script sets the saved frame colours on the document root instead of the frame element, so the frame, the failure page and the window layer beside #root all read them; while #root holds the frame or the failure page, the layer reads the same frame colours and frame defaults; its hover and pressed fill is the other frame surface (background on the raised frame bar, raised on the failure page); its focus ring uses the surface's primary text colour, because some palettes' accents are below 4.5:1 on their own surfaces.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5w-frame-colours.json",
      "newChecks": [
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SA5 live mode contrast and presentation changes survive reload mid-boot",
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#SA5 profile switch and palette changes cache the resulting live colours"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/capability_tests.rs",
        "modules/shell/src-tauri/src/lib.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "The shipped configuration has no inline capabilities and exactly one capability file; only the default capability holds a window-control permission; it is granted to the main window only, with no webview, remote or platform widening; its core:window permissions are exactly allow-start-dragging, allow-minimize, allow-toggle-maximize and allow-close plus the four it held before (default, allow-show, allow-set-focus, allow-set-visible-on-all-workspaces). lib.rs only declares the test module.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "SW8",
          "quote": "Window permissions are confined to the main window; nothing else gains authority.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "The frame-build plan, S5w: desktop window controls and moving; binding design point 5: add exactly the four permissions to the main window's capability.",
          "choice": "Grant core:window:allow-start-dragging, allow-minimize, allow-toggle-maximize and allow-close in capabilities/default.json, and check every capability file and the configuration as shipped, so a new capability file or inline capability must be reviewed by this check.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s5w-capability.json",
      "newChecks": [
        "modules/shell/src-tauri/src/capability_tests.rs#capability_tests::sw8_window_controls_are_granted_to_the_main_window_only"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/themes/token-names.generated.ts"
      ],
      "class": "Incidental",
      "assessment": {
        "purpose": "Regenerated token-name union after adding --frame-window-control-size, --frame-window-control-glyph, --frame-window-control-gap and --frame-window-control-inset to styles/tokens.css: the desktop window controls' fixed px geometry, so they keep one size at any text size and use tokens rather than hard-coded sizes.",
        "callersAndStateOwners": "scripts/ci/gen-token-names.mjs writes it from tokens.css; tests and the theme editor import the union. No runtime state.",
        "indirectBehaviours": "Four names added; no name removed or renamed.",
        "evidence": "node scripts/ci/gen-token-names.mjs reported 250 tokens; gen-token-names --check, npm run typecheck and node scripts/ci/run-ui-web-gates.mjs (31/31, including the contrast matrix and the style-literal ratchet) pass.",
        "unresolvedRisks": "None known."
      }
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1c4cd-82b4-7160-ae39-0f08d218f7f8",
  "verdict": "accept",
  "digest": "sha256:3302bd6aa8010a8e619f1d093f2c76acc16f8580ab3a284edbf09749fff5b70c"
}
```
