---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: codex-uiux-reset-merge-20261021
---

# Reset and shortcut merge

M1–M4 are agent-adopted combination scenarios, not owner words. The new checks
cite the already accepted underlying RS4 recovery-only rule and S14.8 preference
contract rather than relabelling the combination scenarios as owner-adopted.
The startup helper is used by the production setup path. A naive resolution that
runs initialization before checking reset status is exercised separately in a
shared clone inside the supplied temporary folder; both checks fail by assertion.
The merge commit is f047b040e; later tests and formatting remain uncommitted.
The shortcut source stays byte-identical to origin/uiux-shortcut; its existing
evidence remains valid. No acceptance record is authored by the builder.

```json
{
  "entries": [
    {
      "items": [
        "modules/shell/src-tauri/src/lib.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "M2/M3 combination checks: incomplete reset performs no shortcut preference read, write or registration; cancellation preserves the off choice for automatic restart. Disk reset removes the off preference, initialization waits for window storage cleanup, and a completed reset reaches initialization with no preference file (the preserved SC8 check separately establishes missing-file default-on).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RS4",
          "quote": "Finding 1 decided: while a reset is incomplete, the app does not start into normal use; it shows the reset as incomplete, what remains, and offers Retry or Cancel reset (Cancel keeps the remaining data as ordinary data). No new work exists during an incomplete reset, so retries cannot erase it. Verify failure -> restart -> retry and failure -> cancel.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "SC8",
          "quote": "**S14.8 On/off preference.**\n  - **Showing it.** Settings shows the shortcut (keys written out) and an on/off switch.\n  - **Switching.** Turning it off releases the chord at once, so another app can take it without a restart. Turning it on registers at once and reports failure as in S14.7.\n  - **Toggles that fail or overlap.** A failed save puts the switch back to the real state and says so. Slow or overlapping toggles never leave disk, shell and UI disagreeing, and reopening Settings or reloading the UI shows the shell's current state.\n  - **Boot and restart.** The choice survives restart, including autostart launches. It is applied before registration, so a chord turned off is never grabbed briefly at boot.\n  - **Writes and defaults.** A kill during a write leaves a complete, readable preference. A missing file (fresh install) gives the default (decision 5). After factory reset or \"delete all data\", the next start uses the default (reset runs in setup, before registration).\n  - **Unreadable or future-version file.** Decision 6. The recommendation: chord not registered, Settings explains, the file's bytes are kept until the user changes the setting.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-reset-shortcut-merge.json",
      "newChecks": [
        "modules/shell/src-tauri/src/lib.rs#tests::m2_incomplete_reset_never_initializes_shortcuts_and_cancel_preserves_saved_choice",
        "modules/shell/src-tauri/src/lib.rs#tests::m3_completed_reset_removes_shortcut_choice_before_initialization"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/lib.rs#cfg(test)"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "rustfmt only: line wrapping of existing Rust test expressions and argument lists in the touched lib.rs. Inputs, expected values, branches, conditions, ownership, faults and assertions are unchanged."
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-g9v0_p0p",
  "verdict": "accept",
  "digest": "sha256:582ba9ef144c17315d895a5140550730192c3efdf86af257f658cca291f71760",
  "note": "New combination checks follow the agreed recovery-only rule RS4 and preference contract SC8; both executed and passed in this independent session. Production startup and activation guards were reviewed against both branch states and exercised on the real private-Xvfb shell. Existing test changes are formatting-only adaptations; no oracle is weakened."
}
```
