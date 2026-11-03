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
      "rule": "I1\u2013I5 continue to check catalog coverage, resolved fallback values, no orphan tokens, component layering and semantic resolution over every remaining renderer.",
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
        "input re-encoding",
        "instrumentation"
      ],
      "whatMoved": "The generic non-conversation projection control uses the retained Search query store instead of the removed Ask query store; fixture.ask-control becomes fixture.search-control and its schema binding becomes search. Both expose restore({query}) and query subscriptions. All inputs, history-stack assertions, replaceState/pushState observations, reload scenarios and fault conditions remain, with only the literal control-surface name re-encoded. Teardown now calls resetSearchState after stopping URL ingress and deactivating projection, cancelling the Search adapter debounce and aborting any pending request before happy-dom is torn down. The fixture owns the transient Search state; cancellation does not change inputs, assertions or failure handling and no unhandled-rejection suppression is installed.",
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
        "gates/dead-code/baseline.txt"
      ],
      "class": "Structural rule kept",
      "rule": "The dead-code gate continues to reject new unused exports relative to the baseline for every retained module.",
      "whySurvives": "Only three allowance rows leave the baseline: the two for the deleted retired chat stores (src/shell-v0/state/askChatState.ts, agentChatState.ts) and the one for the deleted Lumino shell (src/shell-v0/shell/Shell.ts). Their files are absent at HEAD; no allowance for any retained file, unused-export threshold or gate predicate changes. Removing obsolete allowances narrows the baseline."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/HealthLitView.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Only a setup explanation comment changes: it now states that HealthLitView does not start the AI singleton rather than referring to a deleted debug entry point. The backendReachable assignment, SSE open/error sequence, debounce advance, badge assertions and every other test statement are unchanged."
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1a20b-560e-7bc1-9b3d-f6a78d4aeaad",
  "verdict": "accept",
  "digest": "sha256:fd7d5e0b3008afd8746c2fb1836ac9c97178250628d2cf7809b9319729b5ddb3",
  "note": "Codex verifier acceptor (verifier-small-4): all six entries accepted"
}
```
