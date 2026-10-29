---
title: Run the Windows screen-reader checklist
type: how-to
status: stable
description: "Record owner-run NVDA and Narrator checks against the installed Windows app."
---

# Run the Windows screen-reader checklist

**No screen-reader run has been recorded yet.** This checklist does not establish
screen-reader support. Run it against the **installed build**, not a browser or
dev build, once with NVDA and separately with Narrator; keep only one reader on.
Copy this page for each reader/run and fill in the dated record and result cells.

Prepare an indexed test document with a distinctive phrase and enough text for a
cited answer, plus a saved test conversation. Record their names, a search phrase,
an answer question, reader mode and any changed shortcuts below. Use Tab and
Shift+Tab to reach controls, Enter to activate buttons, and the reader's reading
commands for text. Pass app keys through to the app when the reader intercepts
them. Expected speech below means content, role and state, not exact reader wording.
Bold names are how a control starts; readers also speak what follows it. Mode and
Effort menu items add a description (for example **Search** is followed by "Default
Lists matching documents as you type."), and saved conversations add their age
(for example "1m").

Mark every row **pass / fail / blocked / unrun** with notes; a blank is unrun.
Pass only when all parts were observed. Record the failing action and actual
speech/focus. A missing prerequisite is blocked; an unattempted check is unrun.
An unavailable AI blocks only rows marked **AI**. Keep testing the other rows and
the other reader. Repairs belong in follow-up work; publish no support claim
without a recorded pass for that reader and behavior.

| Step | What to do (keyboard) | What should be heard / checked | Result and notes |
| --- | --- | --- | --- |
| 1. Frameless window | Start the reader and installed JustSearch. Alt+Tab to its main window; Tab through controls. Reach the rail's **Search** button and press Enter. | App content and distinct control names/roles, including Search and the **Ask or search…** text field, can be read. This is the first gate: the shell uses `decorations: false`; see [Tauri #12901](https://github.com/tauri-apps/tauri/issues/12901). If this reader cannot read the window at all, mark this row fail and all its remaining rows blocked; run the other reader separately. | ____ |
| 2. Search bar and mode | Ctrl+L to the bar; type the test phrase. Tab to **Mode: [current value]**; Down Arrow opens its menu. Up/Down Arrow through it, Enter on **Search**. Reopen, then Escape. Ctrl+L returns to the bar. | **Ask or search…**, editable text and its value; Mode button with current value and expanded/collapsed state; radio menu items **Search**, **Ask**, **Structured**, **Delegate** and checked state. Selection and Escape return focus to the mode button. | ____ |
| 3. Effort | Tab to **Effort: [current value]**. Down Arrow, then Up/Down Arrow; Enter on **Standard**. Reopen and Escape; Ctrl+L back to the bar. No AI request is needed. | Effort button, current value and expanded/collapsed state; **Quick**, **Standard**, **Thorough** radio menu items and checked state. Selection and dismissal return focus to the effort button. | ____ |
| 4. Results | In Search mode, Ctrl+L, type the test phrase and Enter. Tab through **Actions for [result title]** buttons. Enter on one; Up/Down Arrow to **Open document**, Enter. Read the document, then Escape to close it; check where focus returns, then Ctrl+L. | **Search results**, result titles/path/snippet; the named Actions button and Open document menu item. The opened **Cited document** pane is readable. Closing leaves an identifiable, usable focus in Search; Ctrl+L announces the bar. | ____ |
| 5. Answer and Stop — **AI** | Choose **Ask** in Mode; Ctrl+L, type the answer question, Enter. Read during streaming. Tab to **Stop the response · Enter steers the running agent**, Enter; Ctrl+L back to the bar. | Answer text is readable as it grows; record whether updates are spoken automatically. Stop's name/role; **Stopping…** while awaiting confirmation, then **Stopped by you.** when confirmed (a quick confirmation may replace the interim status). Report an error as heard if stopping fails. The bar remains reachable. | ____ |
| 6. Citations and sources — **AI** | Ask again and let the cited answer finish. Tab to **Citation [number] — open the cited passage**, Enter. Read **Cited document**; Tab to the close button, Enter (also try Escape after reopening). It is **Close** in a wide window and **Close the cited document** in a narrow one; check both widths. Tab to the answer's **Sources: [count]** button, Enter; read and activate a source button. Close the document; return to Sources with Tab/Shift+Tab and Enter to collapse it. | Citation number/button and current state when selected; cited document content; the close button for the current width; Sources count and expanded/collapsed state; source excerpt/content and current state when selected. Check focus after each close: it must be identifiable and keyboard navigation must continue. If the answer has no citations/sources, mark blocked and record the fixture. | ____ |
| 7. Sessions sidebar | Tab to **Toggle sidebar**, Enter to open if collapsed. Tab to the saved conversation's title button, Enter; Ctrl+L. Return to Toggle sidebar and Enter to collapse. Also try a narrow window: reopen, then Escape. | Toggle sidebar button and pressed state, saved title and current conversation state, restored conversation text, then the bar. Wide-sidebar collapse leaves usable focus; narrow-sheet Escape returns focus to its opening control. | ____ |
| 8. Command palette | Tab to **Open command palette**, Enter. In **Type a command or search…**, type **Search everything**; Up/Down Arrow, Enter. Reopen from the same button and Escape. | **Command palette** dialog, named combobox, command option and active/selected state. Running Search everything returns to Search mode and the bar; dismissal returns to Open command palette. | ____ |
| 9. Settings and Desktop | Tab to the rail's **Settings**, Enter. Tab into categories; Up/Down Arrow to **Desktop**, Enter. Tab to **Global search shortcut**. Space toggles it off/on. Make sure it is on (**Active**), then press Alt+Shift+Space while Settings is open; if it cannot be made active, mark this subcheck blocked. Then restore its original choice. Escape closes Settings. | Settings dialog, Desktop category, Global search shortcut switch with on/off state and chord **Alt + Shift + Space**. Status changes are announced: **Turned off**, **Active**, or **Shortcut not active** with the reason and `--focus-search` alternative. Summon leaves Settings and its focus in place; closing returns to its invoker. | ____ |
| 10. Summon | From another foreground app, press Alt+Shift+Space with the shortcut active. Repeat using the installed executable with `--focus-search` via a Windows shortcut prepared from the [desktop shortcut guide](global-search-shortcut.md#windows-custom-shortcut). Also try while an answer streams (**AI** subcheck). | JustSearch comes forward; **Ask or search…** receives focus. Nothing is submitted or cleared; streaming continues. Record each entry point separately and block only the streaming subcheck without AI. If the installed version predates these entry points, mark unrun. | ____ |
| 11. Recovery | On a naturally occurring desktop recovery screen, read its heading/status; Tab/Shift+Tab through available buttons. Enter on **Try connecting again**, or **Restart JustSearch** when the engine has stopped. After recovery, Ctrl+L. Do not damage data or stop processes to induce this; mark unrun if not encountered. | The displayed recovery reason/status and named button, including unavailable state while busy. Recovery replaces the main view rather than opening a dismissible dialog; successful recovery returns to app content and a reachable bar. Record focus after the transition, or the spoken error if recovery fails. | ____ |

## Dated run record (empty template)

- Date/time and tester: ____
- Installed app version/build: ____
- Reader (NVDA or Narrator), version, language and mode: ____
- Windows version/build: ____
- Prerequisites: indexed fixture/title/phrase ____; saved conversation ____;
  AI model/readiness and answer question ____; shortcut state/custom binding ____.
- Per-step results: fill every cell above; record summon entry points and its AI
  subcheck separately. Actual speech, focus problems and follow-up references: ____.

Keep each completed copy with its date; retain separate records for both readers.
