---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: uiux-recovery-reset-builder-59pqifis
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

Continuation after independent check of 53aecead5: seven durable R2 checks cover pending
focus and its cancellation. Four repair checks assertion-fail on 53aecead5; three cancellation
checks pass before and after. The real-interface reproduction delays the first /api/status
response from a private Engine; on 53aecead5 Reset appears while focus stays on Close Settings.
The repaired interface focuses Reset, and preserves user focus after movement or leaving Settings.
Browser evidence, screenshots and launchers under `tmp/recovery-reset-focus/` are scaffolding.
The two original evidence files below are refreshed against bcab79eeb for the updated test-file digest.
No acceptance record is authored by the builder.

Continuation after independent check of 25ea79f4a: six durable R2 Shell checks cover both
close controls during bootstrap, fresh recovery URLs, one-shot browser/journal history, and a false
URL flag. Five repair checks assertion-fail before; the false-flag preservation check passes before
and after. Boot URL/journal restore yields to a navigation already handled in-app. URL string `true`
uses the existing cancellable focus walk. The focus flag is consumed on handoff in both browser URL
and journal: Settings remains a destination while reload/Back/Forward cannot repeat a focus action.
Real Engine delayed-status, fresh-page, history, timing and User/Operator visual/reset evidence is
scaffolding under `tmp/recovery-reset-url/`. Chromium alone uses its explicitly requested short
`/tmp/jsv-XXXXXX` temporary profile; all private processes are owned and stopped. No acceptance
record is authored by the builder.

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
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.settingsRecovery.test.ts"
      ],
      "class": "New",
      "behaviour": "R2 retains the existing recovery route focus intent until Reset renders, including an unconfirmed recovery observation; user focus movement cancels an asynchronous focus walk. A repeated route registers its intent before another window render, so intervening focus movement cancels it.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "whatever you think is best",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-23 (delegation quoted in CR37c builder assignment)",
          "delegationScope": "CR37c R2 recovery routing, including the delayed-first-status focus repair requested after independent checking of 53aecead5.",
          "choice": "If Reset appears while that intent is still pending, focus moves to it.",
          "quote": "If Reset appears while that intent is still pending, focus moves to it.",
          "location": "task t-20261019-bd718f agreement R2 delayed-status focus continuation (2026-10-23)",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "whatever you think is best",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-23 (delegation quoted in CR37c builder assignment)",
          "delegationScope": "CR37c R2 recovery routing, including the delayed-first-status focus repair requested after independent checking of 53aecead5.",
          "choice": "The intent is dropped when the user moves focus or leaves Settings, or when the status shows no recovery hold. Never pull focus away from somewhere the user put it.",
          "quote": "The intent is dropped when the user moves focus or leaves Settings, or when the status shows no recovery hold. Never pull focus away from somewhere the user put it.",
          "location": "task t-20261019-bd718f agreement R2 delayed-status focus continuation (2026-10-23)",
          "label": "agent choice; agent-adopted (delegated-choice)"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-recovery-reset-focus-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.settingsRecovery.test.ts#R2 retains recovery route focus until the first status reveals the hold",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.settingsRecovery.test.ts#R2 retains recovery intent through an unconfirmed recovery observation until Reset appears",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.settingsRecovery.test.ts#R2 never steals focus moved during the existing asynchronous focus walk",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.settingsRecovery.test.ts#R2 cancels a repeated recovery route when the user moves focus before the window updates"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.settingsRecovery.test.ts"
      ],
      "class": "New",
      "behaviour": "R2 drops pending intent when focus moves (even if it returns), Settings closes and reopens normally, or an observed status explicitly reports no recovery hold.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "whatever you think is best",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-23 (delegation quoted in CR37c builder assignment)",
          "delegationScope": "CR37c R2 recovery routing, including the delayed-first-status focus repair requested after independent checking of 53aecead5.",
          "choice": "If Reset appears while that intent is still pending, focus moves to it.",
          "quote": "If Reset appears while that intent is still pending, focus moves to it.",
          "location": "task t-20261019-bd718f agreement R2 delayed-status focus continuation (2026-10-23)",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "whatever you think is best",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-23 (delegation quoted in CR37c builder assignment)",
          "delegationScope": "CR37c R2 recovery routing, including the delayed-first-status focus repair requested after independent checking of 53aecead5.",
          "choice": "The intent is dropped when the user moves focus or leaves Settings, or when the status shows no recovery hold. Never pull focus away from somewhere the user put it.",
          "quote": "The intent is dropped when the user moves focus or leaves Settings, or when the status shows no recovery hold. Never pull focus away from somewhere the user put it.",
          "location": "task t-20261019-bd718f agreement R2 delayed-status focus continuation (2026-10-23)",
          "label": "agent choice; agent-adopted (delegated-choice)"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-recovery-reset-focus-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.settingsRecovery.test.ts#R2 drops pending recovery focus when the user moves focus, even if they return",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.settingsRecovery.test.ts#R2 drops pending recovery focus when Settings closes and is reopened normally",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.settingsRecovery.test.ts#R2 drops pending recovery focus when the first status reports no recovery hold"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.test.ts"
      ],
      "class": "New",
      "behaviour": "R2 Settings stays closed after Escape or Close before delayed URL bootstrap; fresh URL strings use the existing pending focus walk. Both URL and in-app requests are consumed before browser and journal history can replay them.",
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
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "whatever you think is best",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-23 (delegation quoted in CR37c builder assignment)",
          "delegationScope": "CR37c R2 recovery routing, including the two routing failures reported after independent checking of 25ea79f4a.",
          "choice": "a closed Settings stays closed. A recovery route acts once, when it is followed. A later status or re-render must never re-apply it.",
          "quote": "a closed Settings stays closed. A recovery route acts once, when it is followed. A later status or re-render must never re-apply it.",
          "location": "task t-20261019-bd718f R2 URL continuation after independent check of 25ea79f4a (2026-10-23)",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "whatever you think is best",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-23 (delegation quoted in CR37c builder assignment)",
          "delegationScope": "CR37c R2 recovery routing, including the two routing failures reported after independent checking of 25ea79f4a.",
          "choice": "the same landing as following the route inside a running app. Focus goes to Reset once the hold is known, with the same cancellation rules.",
          "quote": "the same landing as following the route inside a running app. Focus goes to Reset once the hold is known, with the same cancellation rules.",
          "location": "task t-20261019-bd718f R2 URL continuation after independent check of 25ea79f4a (2026-10-23)",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "whatever you think is best",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-23 (delegation quoted in CR37c builder assignment)",
          "delegationScope": "Continuation explicitly delegates whether the focus flag stays in the URL after it has been acted on.",
          "choice": "Consume the focus flag in browser history and the navigation journal when handing it to Settings; Back, Forward and reload restore the Settings destination without re-firing the one-shot focus request.",
          "label": "agent choice; agent-adopted (delegated-choice)"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-recovery-reset-url-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.test.ts#R2 keeps Settings closed after Escape during delayed source bootstrap",
        "modules/ui-web/src/shell-v0/chrome/Shell.test.ts#R2 keeps Settings closed after Close during delayed source bootstrap",
        "modules/ui-web/src/shell-v0/chrome/Shell.test.ts#R2 forwards a fresh recovery URL to the existing pending focus walk",
        "modules/ui-web/src/shell-v0/chrome/Shell.test.ts#R2 consumes recovery focus ('in-app boolean') before Back and Forward",
        "modules/ui-web/src/shell-v0/chrome/Shell.test.ts#R2 consumes recovery focus ('URL string') before Back and Forward"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.test.ts"
      ],
      "class": "New",
      "behaviour": "R2 preserves a normal Settings landing when the URL focus flag is false.",
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
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-recovery-reset-url-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.test.ts#R2 does not focus Reset for a false recovery URL flag"
      ],
      "repaired": false
    }
  ]
}
```
