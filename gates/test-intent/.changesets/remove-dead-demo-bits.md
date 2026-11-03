---
schema: test-intent.v1
task: t-20261103-remove-dead-demo-bits
author-role: builder
author-session: oldleft-small-builder
---

# Test-intent entries

The owner approved removal of demo pages and Demo Echo Action, the empty TCK module,
and retired Ask/Agent stores on 2026-11-03. Compatibility navigation and saved data stay.

```json
{
  "entries": [
    {
      "items": [
        ".github/workflows/ci.yml",
        "modules/app-api-tck/build.gradle.kts",
        "scripts/ci/unit-test-shard-policy.v1.json",
        "settings.gradle.kts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "proceed with the rest as you suggested",
          "location": "task t-20261103-remove-dead-demo-bits user turn 2026-11-03"
        }
      ],
      "conflictSearch": "Searched docs/decisions for app-api-tck and TCK obligations: no requirement to retain the empty module. The quoted user approval covers its removal; only its task is removed from test selection. No conflict found."
    },
    {
      "items": [
        "modules/ui-web/vite.config.js",
        "modules/ui-web/src/boot/boot-lockdown.test.ts",
        "modules/ui-web/src/shell-v0/shell/Shell.test.ts",
        "modules/ui-web/src/shell-v0/shell/Shell.layoutRoundTrip.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "proceed with the rest as you suggested",
          "location": "task t-20261103-remove-dead-demo-bits user turn 2026-11-03"
        },
        {
          "kind": "removal",
          "removed": [
            "modules/ui-web/src/shell-v0/demo/shell-demo.ts",
            "modules/ui-web/src/shell-v0/demo/health-lit-demo.ts",
            "modules/ui-web/src/shell-v0/demo/health-lit-trends-demo.ts",
            "modules/ui-web/src/shell-v0/demo/presentation-demo.ts",
            "modules/ui-web/src/shell-v0/shell/Shell.ts",
            "modules/ui-web/src/shell-v0/shell/LitWidget.ts"
          ]
        }
      ],
      "conflictSearch": "Searched docs/decisions for demo and Lumino obligations: no conflict found. The approval covers demo pages and their unused shell. Only the demo-route loop leaves boot-lockdown; all desktop/browser boot and reset checks remain."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/themes/theme-coverage.test.ts"
      ],
      "class": "Structural rule kept",
      "rule": "I1–I5 continue to check catalog coverage, resolved fallback values, no orphan tokens, component layering and semantic resolution over every remaining renderer.",
      "whySurvives": "Only the deleted Lumino overlay leaves COVERED_FILES. Its component-only defaults are deleted with it; no predicate or exception list changes.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "proceed with the rest as you suggested",
          "location": "task t-20261103-remove-dead-demo-bits user turn 2026-11-03"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/router/sv3ConversationUrl.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "input re-encoding"
      ],
      "whatMoved": "The generic non-conversation projection control uses the retained Search query store instead of the removed Ask query store; fixture.ask-control becomes fixture.search-control and its schema binding becomes search. Both expose restore({query}) and query subscriptions. All inputs, history-stack assertions, replaceState/pushState observations, reload scenarios and fault conditions remain, with only the literal control-surface name re-encoded.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "proceed with the rest as you suggested",
          "location": "task t-20261103-remove-dead-demo-bits user turn 2026-11-03"
        }
      ]
    }
  ]
}
```

## Acceptance records
