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
The main merge is committed as 05b381c4d; the RN3 fixture adaptation and refreshed evidence remain uncommitted for the orchestrator.
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
    },
    {
      "items": [
        "modules/ui-web/src/boot/boot-lockdown.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "The catalog stub became a valid normal-start catalog: the RegistryController envelope and a CoreSurfaceCatalog Library wire entry, checked against the catalog schema and generated Surface wire schema. Only GET /api/registry/surfaces receives the fixture. Every existing mount, lockdown, recovery, reload, debug-route assertion and time bound is unchanged."
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

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "a44gv5wx-verifier-2",
  "verdict": "accept",
  "digest": "sha256:5f725de1fcb153818dfeea5002b523cc79c7d93e47ef023d25e7c33e0acc21bd",
  "note": "M2/M3 expectations follow supplied agreed RS4 recovery-only and SC8 preference scenarios. The exact candidate and bbc028999 execute both combination checks; private-Xvfb real shells preserve reset gating and preference deletion/default-on through automatic process restart. Existing lib.rs tests retain their oracles under formatting. RN3 is a valid normal-start catalog input adaptation: compared against the real candidate Engine response and authoritative schema, with every assertion and 8-second/60-second deadline unchanged; named RN3 failed all three before runs and passed all three candidate runs under two loaded CPUs. This accepts test intent, not a claim that all live scenarios pass; the separate report identifies an inherited cancellation defect."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "da5t9n4v-verifier-3",
  "verdict": "accept",
  "digest": "sha256:93f662004f87bc8595822185bc69a0fac8c8b5f8b69b7ff38f7f42775b461545",
  "note": "Accept test intent on the refreshed digest. M2/M3 expectations remain derived from agreed RS4 and SC8. Both combination checks executed and passed on e4480da72 and 6617bfacd. Independent private-Xvfb shells show recovery-only activation with no shortcut file access, preference deletion on completion/default-on after automatic restart, and preservation of the off choice after Cancel. The lib.rs test adaptation preserves existing oracles. RN3 normal-start catalog is unchanged from e4480da72, schema-valid, and consistent with the real Engine catalog captured in this session; the 14 lockdown tests and 7147 default frontend tests pass on both. Main updater policy changes are retained, with only shared atomic-write visibility differing from main. No test oracle was weakened."
}
```
