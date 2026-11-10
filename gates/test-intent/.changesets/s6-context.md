---
schema: test-intent.v1
task: t-20261103-shell
author-role: builder
author-session: s6-context-builder-20261110
---

# Context-window home

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.contextWindow.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "Reading memory in AI Brain Advanced Answers is the sole context-window editor. Settings retains a searchable navigation row that unfolds Answers, focuses and briefly highlights the control. Preserve Automatic, positive values with the 512 minimum, narrow witnessed saves and visible refusals; move the running-context readout beside the editor and explain a pending saved choice.",
          "label": "agent choice"
        },
        {
          "kind": "removal",
          "removed": [
            "modules/ui-web/src/shell-v0/views/SettingsSurface.ts#renderContextWindow",
            "modules/ui-web/src/shell-v0/views/SettingsSurface.ts#contextOverrideTokens",
            "modules/ui-web/src/shell-v0/views/SettingsSurface.ts#saveContextOverride"
          ]
        }
      ],
      "conflictSearch": "Decision records searched for a conflict with retiring the duplicate Settings editor: rg -n -i 'context.window|contextLength|Reading memory' docs/decisions. ADR-0047 requires Automatic derivation and explicit overrides honoured or failing loud; it does not require a Settings editor. Those semantics and the running readout remain in AI Brain. No conflicting decision found."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/ContextWindow.home.test.ts"
      ],
      "class": "New",
      "behaviour": "One Reading memory editor, Settings navigation/search and cold/live arrival focus; preserved saved 0/512/196608, minimum 512 narrow save, visible failed/conflicting saves; running versus saved explanation including return to Automatic, derivation, unknown/offline/stale observations. The suite has eleven new assertion failures and six preservation passes at origin/main, seventeen passes after.",
      "repaired": true,
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "Reading memory in AI Brain Advanced Answers is the sole context-window editor. Settings retains a searchable navigation row that unfolds Answers, focuses and briefly highlights the control. Preserve Automatic, positive values with the 512 minimum, narrow witnessed saves and visible refusals; move the running-context readout beside the editor and explain a pending saved choice.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-context/unit.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/ContextWindow.home.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/ContextWindow.home-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "Real headless Chromium through the Shell, router and Settings dialog: cold and Memory-tab arrivals close Settings, address the runtime tab, unfold Answers, focus and scroll Reading memory into view, briefly highlight it, and do not replay focus or highlight on normal return. Executed by the existing CI lockdown config.",
      "repaired": true,
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "Reading memory in AI Brain Advanced Answers is the sole context-window editor. Settings retains a searchable navigation row that unfolds Answers, focuses and briefly highlights the control. Preserve Automatic, positive values with the 512 minimum, narrow witnessed saves and visible refusals; move the running-context readout beside the editor and explain a pending saved choice.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-context/unit.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/ContextWindow.home-lockdown.test.ts"
      ]
    }
  ]
}
```

## Acceptance records
