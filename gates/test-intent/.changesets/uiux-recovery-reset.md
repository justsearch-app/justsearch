---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: uiux-recovery-reset-builder-dtfir3ar
---

# Test-intent entries

CR37c is agent-adopted under delegated choice, never owner words or an owner-adopted scenario.
The contract quotes below are verbatim from the 2026-10-23 assignment. The quoted delegation
and its scope are distinct from the agent's choice.

`SettingsSurface.settingsRecovery.test.ts` is durable R1/R2/R3 coverage.
The seven updated recovery checks in `IndexingPause.test.ts` are durable R2 coverage;
the other expectations in that file are retained. Twelve repaired checks assertion-fail on
bcab79eeb and pass on the candidate. Three R3 preservation checks pass before and after.
The baseline shared clone, logs, private Engine/browser scripts, and R4 screenshots under
`tmp/recovery-reset/` are scaffolding. The evidence runner uses its default dependency-link
mode, with a temporary `npx` launcher adding `--cache=false --maxWorkers=2 --configLoader runner`
to avoid writes to the shared UI dependency tree. No acceptance record is authored here.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.settingsRecovery.test.ts"
      ],
      "class": "New",
      "behaviour": "CR37c R1/R2: expose the existing confirmed reset during recovery in every tier, and route all shared recovery controls directly to the actionable reset. R3: remove the exception when status clears, moving focus to the Settings heading only when the disappearing control held it.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "whatever you think is best",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-23 (delegation quoted in CR37c builder assignment)",
          "delegationScope": "WP3.7b settings-recovery reset visibility and direct recovery routes, under CR37c; UI-only exception to the existing pause reason.",
          "choice": "CR37c R1: While the Engine reports the settings_recovery hold, Settings shows \"Reset to defaults\" in every view tier, the default User tier included. It works the same as today: its existing confirmation, result and error handling, and clearing the hold.",
          "quote": "While the Engine reports the settings_recovery hold, Settings shows \"Reset to defaults\" in every view tier, the default User tier included. It works the same as today: its existing confirmation, result and error handling, and clearing the hold.",
          "location": "task t-20261019-bd718f agreement CR37c (2026-10-23)",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "whatever you think is best",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-23 (delegation quoted in CR37c builder assignment)",
          "delegationScope": "WP3.7b settings-recovery reset visibility and direct recovery routes, under CR37c; UI-only exception to the existing pause reason.",
          "choice": "CR37c R2: While the hold is shown, every place that routes the user to recovery goes straight to that control, in every tier. This covers the IndexingControls pause control and any other route WP3.7 added (Settings indexing section, Library, Search v3 pause notice; find them all). The \"set View tier to Operator\" wording and the View-tier detour are removed for this state. Focus lands on the control.",
          "quote": "While the hold is shown, every place that routes the user to recovery goes straight to that control, in every tier. This covers the IndexingControls pause control and any other route WP3.7 added (Settings indexing section, Library, Search v3 pause notice; find them all). The \"set View tier to Operator\" wording and the View-tier detour are removed for this state. Focus lands on the control.",
          "location": "task t-20261019-bd718f agreement CR37c (2026-10-23)",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "whatever you think is best",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-23 (delegation quoted in CR37c builder assignment)",
          "delegationScope": "WP3.7b settings-recovery reset visibility and direct recovery routes, under CR37c; UI-only exception to the existing pause reason.",
          "choice": "CR37c R3: Outside the hold, nothing changes: in User tier \"Reset to defaults\" stays hidden, and in Operator tier it shows as today. When the hold ends (after the reset, or the Engine clears it), the control returns to its normal audience without a reload. If it disappears while focused, focus goes to a sensible place.",
          "quote": "Outside the hold, nothing changes: in User tier \"Reset to defaults\" stays hidden, and in Operator tier it shows as today. When the hold ends (after the reset, or the Engine clears it), the control returns to its normal audience without a reload. If it disappears while focused, focus goes to a sensible place.",
          "location": "task t-20261019-bd718f agreement CR37c (2026-10-23)",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "whatever you think is best",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-23 (delegation quoted in CR37c builder assignment)",
          "delegationScope": "WP3.7b settings-recovery reset visibility and direct recovery routes, under CR37c; UI-only exception to the existing pause reason.",
          "choice": "CR37c R4: Keyboard, screen reader name, both themes, forced colors and narrow width hold for the newly visible control and routes.",
          "quote": "Keyboard, screen reader name, both themes, forced colors and narrow width hold for the newly visible control and routes.",
          "location": "task t-20261019-bd718f agreement CR37c (2026-10-23)",
          "label": "agent choice; agent-adopted (delegated-choice)"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-recovery-reset-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.settingsRecovery.test.ts#R1 exposes the existing reset in every tier only for the observed recovery hold",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.settingsRecovery.test.ts#R1 preserves confirmation, cancellation, errors and successful reset handling in User tier",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.settingsRecovery.test.ts#R2 focuses the actual reset control in every tier without a View-tier detour",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.settingsRecovery.test.ts#R1 R3 removes the exception on a remote clear and restores focus only when Reset had focus",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.settingsRecovery.test.ts#R1 R3 restores focus when pending confirmation blurs before the recovery hold clears",
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts#CR37c R2 jf-task-list routes recovery directly to Reset without offering Resume",
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts#CR37c R2 jf-status-deck routes recovery directly to Reset without offering Resume",
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts#CR37c R2 jf-health-surface routes recovery directly to Reset without offering Resume",
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts#CR37c R2 jf-library-surface routes recovery directly to Reset without offering Resume",
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts#CR37c R2 jf-settings-surface routes recovery directly to Reset without offering Resume",
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts#CR37c R2 jf-sv3-window routes recovery directly to Reset without offering Resume",
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts#CR37c R2 the indexing overlay and waiting folder rows direct recovery to Reset"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.settingsRecovery.test.ts"
      ],
      "class": "New",
      "behaviour": "R3 preservation: User-tier reset remains hidden outside settings recovery, including manual pause and automatic game-mode holds; Operator-tier reset remains available, and tier changes update without reload.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "whatever you think is best",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-23 (delegation quoted in CR37c builder assignment)",
          "delegationScope": "WP3.7b settings-recovery reset visibility and direct recovery routes, under CR37c; UI-only exception to the existing pause reason.",
          "choice": "CR37c R3: Outside the hold, nothing changes: in User tier \"Reset to defaults\" stays hidden, and in Operator tier it shows as today. When the hold ends (after the reset, or the Engine clears it), the control returns to its normal audience without a reload. If it disappears while focused, focus goes to a sensible place.",
          "quote": "Outside the hold, nothing changes: in User tier \"Reset to defaults\" stays hidden, and in Operator tier it shows as today. When the hold ends (after the reset, or the Engine clears it), the control returns to its normal audience without a reload. If it disappears while focused, focus goes to a sensible place.",
          "location": "task t-20261019-bd718f agreement CR37c (2026-10-23)",
          "label": "agent choice; agent-adopted (delegated-choice)"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-recovery-reset-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.settingsRecovery.test.ts#R3 preserves normal audience visibility outside recovery (null)",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.settingsRecovery.test.ts#R3 preserves normal audience visibility outside recovery (user_paused)",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.settingsRecovery.test.ts#R3 preserves normal audience visibility outside recovery (game_mode)"
      ],
      "repaired": false
    }
  ]
}
```
