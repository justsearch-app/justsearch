---
schema: test-intent.v1
task: t-20261103-shell
author-role: builder
author-session: s6-search-builder
---

# Test-intent entries

Sources below record the delegated choices for SR1–SR5. Independent acceptance remains with the verifier.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/SettingsNav.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Only the ArrowDown roving-key fixture query changes from a to data to provide at least two search results under word-start matching. The query only feeds the list the keys move through; every keyboard assertion is unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Keep the roving keyboard check intact while its fixture supplies multiple results under SR1 word-start matching; orchestrator explicitly authorized this query adaptation."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "SR2 opens containing folds, scrolls, focuses and marks the chosen search row until the next key or pointer interaction. SR5 preserves Escape behavior and visibility at 600x400 with 200 percent text, reduced motion, and light/dark forced colours.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Exercise row-level Settings search and arrival through the real Shell with the SR1\u2013SR5 contract."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-search-browser.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts#SR2 SR5 control-word search arrives visibly and clears on interaction at 600x400, 200 percent text, reduced motion and light forced colours",
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts#SR2 SR5 control-word search arrives visibly and clears on interaction at 600x400, 200 percent text, reduced motion and dark forced colours",
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts#SR2 search opens containing folds before focusing the row, and pointer interaction removes the mark"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/settingsRegister.searchRows.test.ts"
      ],
      "class": "New",
      "behaviour": "SR1 matches row/control labels and keywords at word starts with every query word required and label-first stable ranking. SR3 names external destinations. SR4 retains platform, audience, developer-tool and saved-settings gates and omits hidden conditional controls.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SR1 matches row/control labels and keywords at word starts with every query word required and label-first stable ranking. SR3 names external destinations. SR4 retains platform, audience, developer-tool and saved-settings gates and omits hidden conditional controls."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-search-rows.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/settingsRegister.searchRows.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.searchRows.test.ts"
      ],
      "class": "New",
      "behaviour": "SR2 selects, unfolds, scrolls, focuses and marks a row, clears the mark on interaction and teardown; SR3 opens Library indexing hours and AI Brain reading memory with their arrival requests.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SR2 selects, unfolds, scrolls, focuses and marks a row, clears the mark on interaction and teardown; SR3 opens Library indexing hours and AI Brain reading memory with their arrival requests."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-search-rows.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.searchRows.test.ts"
      ]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "s6-verifier-taz2_vmu",
  "verdict": "accept",
  "digest": "sha256:a1769202798689006336a1fcab68cf43f333bfb2b704facac48ace662ca3c1ba"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "s6-verifier-taz2_vmu",
  "verdict": "accept",
  "digest": "sha256:82ea920a61538e2f27d18b0aad664a41e5443fb59b5eb28ae884d07df984e15e"
}
```
