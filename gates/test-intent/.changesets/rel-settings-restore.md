---
schema: test-intent.v1
task: rel-settings-restore
author-role: builder
author-session: 82667d8b-774f-4c82-aee6-7844f5983d8f
---

# Restored Settings stays modal after the start-up hand-off

One check is added to `modules/ui-web/src/boot/boot-frame-lockdown.test.ts`; no existing check
in the file changes. It pins the repair in `modules/ui-web/src/boot/desktopRecovery.ts`: the
hand-off removes only the start-up cover instead of re-inserting the application with
`root.replaceChildren(application)`, which disconnected `jf-shell` and took a Settings dialog
opened beneath the frame out of the top layer.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts"
      ],
      "class": "Defect pin",
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "owner-words",
          "location": "docs/explanation/10-ui-ux-design.md:224-226",
          "quote": "a native `<dialog>` driven by `ModalController` supplies focus-trap, `inert`, and Top Layer; ESC, backdrop-click, and an X button close it."
        },
        "reproduction": "Release qualification, hosted Linux run 43201993478, job J4 (restart): after relaunch the restored Settings window was not modal and its X lay under the window's Maximize control. Locally on a003b507f4 (shared before-worktree, candidate test overlaid) the new R1 check fails with an assertion: the hand-off removes jf-shell from #root (removed nodes ['div', 'jf-shell'] instead of ['div']), because desktopRecovery.ts re-inserts the application with root.replaceChildren(application)."
      },
      "reproduction": "Release qualification, hosted Linux run 43201993478, job J4 (restart): after relaunch the restored Settings window was not modal and its X lay under the window's Maximize control. Locally on a003b507f4 (shared before-worktree, candidate test overlaid) the new R1 check fails with an assertion: the hand-off removes jf-shell from #root (removed nodes ['div', 'jf-shell'] instead of ['div']), because desktopRecovery.ts re-inserts the application with root.replaceChildren(application).",
      "behaviour": "Settings opened while the start-up frame still covers the app (Settings restored at launch) is still a modal dialog after the hand-off: the application element never leaves #root (only the cover is removed), the app is no longer inert, the dialog matches :modal with a visible backdrop, its X is the topmost element at its own centre, and the X and Escape each close it.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "docs/explanation/10-ui-ux-design.md:215-216",
          "quote": "**Settings** is a modal window opened from the top bar's Settings button, the command palette or a deep link."
        },
        {
          "kind": "owner-words",
          "location": "docs/explanation/10-ui-ux-design.md:224-226",
          "quote": "a native `<dialog>` driven by `ModalController` supplies focus-trap, `inert`, and Top Layer; ESC, backdrop-click, and an X button close it."
        },
        {
          "kind": "owner-words",
          "location": "docs/explanation/10-ui-ux-design.md:175-176",
          "quote": "While a modal dialog such as Settings is open, the controls wait until it closes."
        },
        {
          "kind": "owner-words",
          "location": "docs/explanation/10-ui-ux-design.md:160-164",
          "quote": "holds Minimize, Maximize/Restore and Close at the right end of the top area in every window state: the start-up frame, the failure page, connecting, engine and reset recovery, and the running app, including the hand-off while the app beneath the frame is still blocked."
        },
        {
          "kind": "owner-words",
          "location": "docs/explanation/10-ui-ux-design.md:193-196",
          "quote": "closing Settings (Escape, the close button, the backdrop or Back) returns to it once"
        }
      ],
      "evidence": "gates/test-intent/evidence/rel-settings-restore.json",
      "newChecks": [
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts#R1 Settings opened beneath the start-up frame stays modal after hand-off and closes by X and Escape"
      ],
      "repaired": true
    }
  ]
}
```

## Acceptance records
