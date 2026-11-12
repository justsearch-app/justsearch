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
          "kind": "delegated-choice",
          "delegationQuote": "for each release, owner attention should only be necessary, and thats for final validation. also, the release workflow doesnt need to have every issue fixed before being shipped, but only the critical ones every user would experience and that would block using justsearch in general",
          "delegationLocation": "task t-20261027-4e4d6b request",
          "delegationScope": "agents run releases and decide which defects found in release qualification are fixed before shipping",
          "choice": "restored Settings stays modal after hand-off; X and Escape close it",
          "label": "agent choice"
        },
        "reproduction": "Release qualification, hosted Linux run 43201993478, job J4 (restart): after relaunch the restored Settings window was not modal and its X lay under the window's Maximize control. Locally on a003b507f4 (shared before-worktree, candidate test overlaid) the new R1 check fails with an assertion: the hand-off removes jf-shell from #root (removed nodes ['div', 'jf-shell'] instead of ['div']), because desktopRecovery.ts re-inserts the application with root.replaceChildren(application)."
      },
      "reproduction": "Release qualification, hosted Linux run 43201993478, job J4 (restart): after relaunch the restored Settings window was not modal and its X lay under the window's Maximize control. Locally on a003b507f4 (shared before-worktree, candidate test overlaid) the new R1 check fails with an assertion: the hand-off removes jf-shell from #root (removed nodes ['div', 'jf-shell'] instead of ['div']), because desktopRecovery.ts re-inserts the application with root.replaceChildren(application).",
      "behaviour": "Settings opened while the start-up frame still covers the app (Settings restored at launch) is still a modal dialog after the hand-off: the app is no longer inert, the dialog matches :modal with a visible backdrop, its X is the topmost element at its own centre, and the X and Escape each close it.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "for each release, owner attention should only be necessary, and thats for final validation. also, the release workflow doesnt need to have every issue fixed before being shipped, but only the critical ones every user would experience and that would block using justsearch in general",
          "delegationLocation": "task t-20261027-4e4d6b request",
          "delegationScope": "agents run releases and decide which defects found in release qualification are fixed before shipping",
          "choice": "restored Settings stays modal after hand-off; X and Escape close it",
          "label": "agent choice"
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

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-settings-1794483268",
  "verdict": "reject",
  "digest": "sha256:d828bbdd90556709088a7d9e0f814babc6d5ef7a32d50746284d81f8c3e127c3",
  "note": "Reject: every source (and defectSource.stronger) is kind owner-words citing docs/explanation/10-ui-ux-design.md, whose cited lines came from agent co-authored PRs (#527, #1134, #1194); only the user's own words are owner-words, and no listed safeguard fits. Behaviour, evidence and fail-before hold (R1 fails on a003b507f4 by assertion; :modal alone also fails there). Clear by re-sourcing: a delegated-choice quoting the owner's release request verbatim with its task location and the choice 'restored Settings stays modal after hand-off; X and Escape close it', or an owner-adopted-scenario R1, used both as sources and as defectSource.stronger."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "acceptor-settings-2-1794483387",
  "verdict": "reject",
  "digest": "sha256:f885275774528fc00f4fae4c2a7179cbeb3e0b13da66a51896db40d7e5d40026",
  "note": "Reject: the delegated-choice is acceptable (owner's release request delegates fix-before-ship triage; cut-a-release.md section 4 confirms agents decide; no stronger source conflicts, base 10-ui-ux-design.md agrees Settings is modal and closed by Escape and X), and it validly serves as defectSource.stronger. Evidence and assertion fail-before hold. But R1 also asserts a mechanism the choice does not state: removedFromRoot equals ['div'] (the application never leaves #root at hand-off). The behaviour field lists it; no source supports it, and it would fail a fix that keeps Settings modal another way. Clear by either dropping that assertion (':modal' alone fails on a003b507f4 by assertion) and regenerating evidence, or widening the choice to state that the hand-off removes only the start-up cover and keeps the application attached to #root."
}
```
