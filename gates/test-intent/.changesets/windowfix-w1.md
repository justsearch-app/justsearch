---
schema: test-intent.v1
task: t-20261109-windowfix
author-role: builder
author-session: windowfix-w1-builder
---

# Library and AI Brain window repairs

These are agent technical choices under the quoted owner decision, not owner-adopted scenario labels or owner words taken from the orchestrator brief. The three wholly new browser and label test files are gate-exempt isolated sources; the modified tests below record why their old expectations changed.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/indexingWaiting.test.ts",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.render.test.ts",
        "modules/ui-web/src/shell-v0/views/HealthSurface.render.test.ts",
        "modules/ui-web/src/shell-v0/views/HelpSurface.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Issues in the windows (Recommended)",
          "delegationLocation": "task t-20261109-windowfix request",
          "delegationScope": "Recheck and fix the Library and AI Brain window issues listed in the relayed option.",
          "choice": "The raw locale retry deadline and the per-row repetition of the library-wide retry text are retired for one library-level 'Next retry at <local time>.' line; the served rebuild label changes from 'Force Rebuild' to 'Rebuild search index', and Help quotes the served label.",
          "label": "agent choice — replaces old expectations under the delegated window-issue fixes"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none (docs/decisions, docs/reference and docs/explanation searched for the rebuild label and retry wording)"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/indexingWaiting.test.ts",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.render.test.ts"
      ],
      "class": "New",
      "behaviour": "A retry wait reads 'Next retry at <local time>.' with hours and minutes, adding the short weekday only when the deadline is not today. Each Library folder row keeps its own 'Waiting to retry · N remaining' status without the library-wide count or time; those appear once at library level, including while a reindex outcome is shown.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Issues in the windows (Recommended)",
          "delegationLocation": "task t-20261109-windowfix request",
          "delegationScope": "Recheck and fix the Library and AI Brain window issues listed in the relayed option.",
          "choice": "Scenario L1: the library-wide retry wait appears once at library level with an app-style local time, and folder rows show only their own status.",
          "label": "agent choice — builder implementation of delegated scenarios"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/windowfix-w1.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/indexingWaiting.test.ts#shows retry waiting with the backlog and deadline then follows the worker lifecycle",
        "modules/ui-web/src/shell-v0/state/indexingWaiting.test.ts#L1 uses hours and minutes without a weekday for a retry today",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.render.test.ts#L1 library retry waiting appears once outside folder rows"
      ]
    }
  ]
}
```

## Acceptance records
