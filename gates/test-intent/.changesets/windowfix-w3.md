---
schema: test-intent.v1
task: t-20261109-windowfix
author-role: builder
author-session: windowfix-w3-builder
---

# Test-intent entries

The sole quoted owner decision is the selection relayed in the task request. All technical
scenarios, design and test choices are delegated choices from the orchestrator brief.

The new isolated `BrainSurface.removalBand-lockdown.test.ts` is durable Chromium coverage of
D1-D7 (24 checks), adapted from the discarded fixture draft. D8 and the settings witness use
the existing two files below. The new file is subject to isolated-file exemption and independent
verification; no author acceptance is claimed.

Execution evidence: `tmp/windowfix-w3/before-chromium.log` records 8 assertion failures for
D1, D3, D6 and shared-band geometry and 16 preservation passes on a shared clone of e3a479db7d.
`after-chromium.log` and `after-chromium.json` record all 24 passing. `before-unit.log` records
all 15 original checks passing on the baseline clone; `after-unit.log` and `after-unit.json`
record all 15 adapted checks passing. Scratch belongs to this worktree; before-state clone and
its separate dependencies/build cache belong to the session temporary directory.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.remove-files.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.remove-files-witness.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "split",
        "signature"
      ],
      "whatMoved": "The removal confirmation now composes jf-confirm-band: its named group, heading and deletion sentence live in its shadow root; answers use its jf-button activation callbacks and Keep it is the safe answer. Retain all 15 existing checks, their fixtures, operation arguments, deletion and kept-path assertions, refusal and focus assertions, Download again consent assertions, and settings-witness assertions. The old group accessible-name check now observes the shared band group; the original deletion sentence is asserted on the band consequence property.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Issues in the windows (Recommended)",
          "delegationLocation": "task t-20261109-windowfix request",
          "delegationScope": "The owner selected fixing issues encountered in Library and AI Brain, including similar recorded issues, with rechecks before fixing. The orchestrator bounded this assignment to the Downloaded files removal confirmation.",
          "choice": "Delegated design: adopt the existing jf-confirm-band, retain preview details directly above it, preserve the pending guard, request arguments, preview digest, outcomes and existing Download again consent. Adapt existing tests to the shared controls without dropping their safety or outcome expectations. D1-D8 and technical design are delegated choices, not owner-authored scenarios.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```

## Acceptance records

Pending independent acceptance; the builder does not accept its own work.
