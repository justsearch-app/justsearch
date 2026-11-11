---
schema: test-intent.v1
task: t-20261103-shell
author-role: builder
author-session: s6-devtools-builder-d
---

# Test-intent entries

Candidate D implements the settled SD1–SD6 and SG6 amendments. Fail-before runs use a shared clone at 50c26d710c with its own dependency tree.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.viewerAudience.test.ts",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.search.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Enable the local developer-tools field before exercising the unchanged View tier controls and the unchanged search-result activation path. Existing audience selection, persistence and scroll assertions remain.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SD1 hides View tier until tools are shown. Preserve the existing control and search checks by enabling tools in their setup."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/UserStateDocument.profileSliceCoverage.test.ts"
      ],
      "class": "Structural rule kept",
      "rule": "Every non-metadata Profile field is emitted by flatSlicesFromProfile.",
      "whySurvives": "The Required<Profile> fixture gains showDeveloperTools so the existing bidirectional field-coverage loop continues to catch omitted slices.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SD3 requires profile isolation, including absent values. Extend the existing field-coverage fixture to the new optional preference."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.frame.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SD1 More Developer visibility follows the effective developer-tools preference independently of audience."
        }
      ],
      "conflictSearch": "Decision records searched for developer-tools visibility, viewer audience and profile persistence conflicts: none. Existing view-tier access semantics remain a view preference; SD1\u2013SD6 and the settled SG6 amendment govern these projections."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.frame.test.ts"
      ],
      "class": "New",
      "behaviour": "SD1 More Developer visibility follows the effective developer-tools preference independently of audience.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SD1 More Developer visibility follows the effective developer-tools preference independently of audience."
        }
      ],
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.frame.test.ts"
      ],
      "evidence": "gates/test-intent/evidence/s6-devtools.json"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/TopBar.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SD1 More Developer includes Logs, which emits the Logs navigation selection."
        }
      ],
      "conflictSearch": "Decision records searched for developer-tools visibility, viewer audience and profile persistence conflicts: none. Existing view-tier access semantics remain a view preference; SD1\u2013SD6 and the settled SG6 amendment govern these projections."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/TopBar.test.ts"
      ],
      "class": "New",
      "behaviour": "SD1 More Developer includes Logs, which emits the Logs navigation selection.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SD1 More Developer includes Logs, which emits the Logs navigation selection."
        }
      ],
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/TopBar.test.ts"
      ],
      "evidence": "gates/test-intent/evidence/s6-devtools.json"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/settingsRegister.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SD1 View tier search is hidden when developer tools are off and shown when on, independently of audience."
        }
      ],
      "conflictSearch": "Decision records searched for developer-tools visibility, viewer audience and profile persistence conflicts: none. Existing view-tier access semantics remain a view preference; SD1\u2013SD6 and the settled SG6 amendment govern these projections."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/settingsRegister.test.ts"
      ],
      "class": "New",
      "behaviour": "SD1 View tier search is hidden when developer tools are off and shown when on, independently of audience.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SD1 View tier search is hidden when developer tools are off and shown when on, independently of audience."
        }
      ],
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/settingsRegister.test.ts"
      ],
      "evidence": "gates/test-intent/evidence/s6-devtools.json"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.sections.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SD1 owns the Show developer tools addition to the SG2 section-order check and the replacement Developer anchor in the SG4 legacy-coordinate check. SD3 and amended SG6 own the replacement focus-rescue targets for gated content, navigation and search rows: each lands on the switch."
        }
      ],
      "conflictSearch": "Decision records searched for developer-tools visibility, viewer audience and profile persistence conflicts: none. Existing view-tier access semantics remain a view preference; SD1\u2013SD6 and the settled SG6 amendment govern these projections."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.sections.test.ts"
      ],
      "class": "New",
      "behaviour": "SD1 owns the Show developer tools addition to the SG2 section-order check and the replacement Developer anchor in the SG4 legacy-coordinate check. SD3 and amended SG6 own the replacement focus-rescue targets for gated content, navigation and search rows: each lands on the switch.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SD1 owns the Show developer tools addition to the SG2 section-order check and the replacement Developer anchor in the SG4 legacy-coordinate check. SD3 and amended SG6 own the replacement focus-rescue targets for gated content, navigation and search rows: each lands on the switch."
        }
      ],
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.sections.test.ts"
      ],
      "evidence": "gates/test-intent/evidence/s6-devtools.json"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/UserStateDocument.developerTools.test.ts"
      ],
      "class": "New",
      "behaviour": "SD5 parses and round-trips optional booleans, copies them for implicit and explicit user-state profile bases, leaves absent fields and future schemas untouched, and preserves the unknown-field contract.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SD5 parses and round-trips optional booleans, copies them for implicit and explicit user-state profile bases, leaves absent fields and future schemas untouched, and preserves the unknown-field contract."
        }
      ],
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/UserStateDocument.developerTools.test.ts"
      ],
      "evidence": "gates/test-intent/evidence/s6-devtools.json"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/developerToolsState.test.ts"
      ],
      "class": "New",
      "behaviour": "SD2 and SD3 expose only effective preference changes, including audience changes and profile switches with equal values; unsubscribing stops notifications.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SD2 and SD3 expose only effective preference changes, including audience changes and profile switches with equal values; unsubscribing stops notifications."
        }
      ],
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/developerToolsState.test.ts"
      ],
      "evidence": "gates/test-intent/evidence/s6-devtools.json"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.developerTools.test.ts"
      ],
      "class": "New",
      "behaviour": "SD1\u2013SD4 and SD6 expose a local switch in every audience and Session-only mode; cover absent/true/false audience and profile matrices, isolated writes, reload, storage adoption, failed saves, audience-independent plugin permissions, hidden-link reasons and focus rescue.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SD1\u2013SD4 and SD6 expose a local switch in every audience and Session-only mode; cover absent/true/false audience and profile matrices, isolated writes, reload, storage adoption, failed saves, audience-independent plugin permissions, hidden-link reasons and focus rescue."
        }
      ],
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.developerTools.test.ts"
      ],
      "evidence": "gates/test-intent/evidence/s6-devtools.json"
    },
    {
      "items": [
        "modules/ui-web/src/boot/factoryReset.developerTools.test.ts"
      ],
      "class": "New",
      "behaviour": "SD6 the existing full local-data deletion removes every profile developer-tools field before acknowledging cleanup.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SD6 the existing full local-data deletion removes every profile developer-tools field before acknowledging cleanup."
        }
      ],
      "newChecks": [
        "modules/ui-web/src/boot/factoryReset.developerTools.test.ts"
      ],
      "evidence": "gates/test-intent/evidence/s6-devtools.json"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.settingsRecovery.test.ts"
      ],
      "class": "New",
      "behaviour": "SD6 the existing backend Reset to defaults keeps explicit true and false local developer-tools values.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Keep backend reset semantics unchanged and prove that local preference storage is unaffected."
        }
      ],
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.settingsRecovery.test.ts"
      ],
      "evidence": "gates/test-intent/evidence/s6-devtools.json"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "SD1 and amended SD3/SG6 exercise the switch, view-tier gate, focus rescue, More visibility, System Logs routing and actual two-window storage adoption in installed Chromium over a private Vite server. The fixture declares the System member catalog needed to observe Logs routing. SD1 owns the renamed FR3 check: a legacy Settings › Developer link opens Advanced at Show developer tools, and reload keeps it while the engine is slow to answer. This supersedes the Settings-sections View tier anchor oracle.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Check the changed frame behavior through its real browser interface, including another same-origin window."
        }
      ],
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts"
      ],
      "evidence": "gates/test-intent/evidence/s6-devtools-browser.json"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SD1 makes the legacy Developer address land at the always reachable Show developer tools switch, including reload with delayed catalog delivery."
        }
      ],
      "conflictSearch": "Decision records searched for legacy Developer addressing conflicts: none. The settled SD1 placement and SD3 hidden-destination behavior supersede the former View tier anchor."
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "f3xlx3bo-verifier",
  "verdict": "accept",
  "digest": "sha256:d8299bcc80677ce47b76b1f1ebf5aefd60a37f3f9a01fffbabc5b9e47c4bb39e"
}
```
