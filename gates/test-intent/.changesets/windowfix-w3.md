---
schema: test-intent.v1
task: t-20261109-windowfix
author-role: builder
author-session: windowfix-w3-builder
---

# Test-intent entries

The sole quoted owner decision is the selection relayed in the task request. All technical
scenarios, design and test choices are delegated choices from the orchestrator brief.

The isolated `BrainSurface.removalBand-lockdown.test.ts` retains its 24 D1-D7 checks and adds
seven durable D9 checks. The operation acknowledges running while the row stays disabled;
a later poll supplies success, partial removal or an outcome without a row action. Poll closure
and deliberate focus moves elsewhere are covered separately. No existing oracle was weakened.
D8 and the settings witness keep using the two unchanged files in the original entry.

Execution evidence is regenerated for all 31 checks in the edited file. The repair evidence
records five assertion failures on 6e78066eaa and five passes after the change; all 24 prior
checks and both focus-preservation cases also pass. The manual before-state was a shared clone
inside the session temporary directory with its own copied dependencies. Automated fail-before
evidence ran from that clone, keeping its temporary Git checkout registration out of the source
repository. `tmp/windowfix-w3/` holds command logs and the initial before/after reports.
No persisted-format changes. The acceptance record below is retained unchanged for independent
review; this repair makes it stale and the builder does not accept its own work.

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
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.removalBand-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "D9: delayed successful, partial and no-action outcomes focus the settled enabled row action or outcome; polling closes a focused band onto the replacement row or outcome. All five repair checks fail by assertion on 6e78066eaa and pass on the candidate.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Issues in the windows (Recommended)",
          "delegationLocation": "task t-20261109-windowfix request",
          "delegationScope": "The owner selected fixing issues encountered in Library and AI Brain, including similar recorded issues, with rechecks before fixing. The orchestrator bounded this repair to D9 focus after Downloaded files removal and poll closure, preserving D1-D8.",
          "choice": "Delegated D9 design: after removal settles, focus the enabled Remove files action, then Download again, otherwise the focusable outcome. When polling closes a band, restore focus only if it was inside that band; preserve a deliberate move elsewhere during removal. Test real Chromium with an operation acknowledging running while its row stays disabled, followed by a later terminal poll and another render. Keep D1 safe-answer/Escape/opener, D2 single-answer/key-repeat guards, D3 document replacement/window isolation, D4 preview details/geometry, D5 approved digest/refusal, D6 clearing on hide/disappearance and D7 outcomes/conflicting-action guards unchanged. D1-D9 and test choices are delegated choices, not owner-authored scenarios.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/windowfix-w3-brain-removal-focus-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.removalBand-lockdown.test.ts#D9 removed waits for a later removal poll and focuses the settled row action or outcome",
        "modules/ui-web/src/shell-v0/views/BrainSurface.removalBand-lockdown.test.ts#D9 partial waits for a later removal poll and focuses the settled row action or outcome",
        "modules/ui-web/src/shell-v0/views/BrainSurface.removalBand-lockdown.test.ts#D9 no-action waits for a later removal poll and focuses the settled row action or outcome",
        "modules/ui-web/src/shell-v0/views/BrainSurface.removalBand-lockdown.test.ts#D9 polling closes a focused band onto row without stealing focus elsewhere",
        "modules/ui-web/src/shell-v0/views/BrainSurface.removalBand-lockdown.test.ts#D9 polling closes a focused band onto outcome without stealing focus elsewhere"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.removalBand-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "Retain all 24 D1-D7 Chromium checks without changing their expectations, and add two D9 preservation checks: polling and delayed completion keep focus deliberately moved elsewhere. The evidence reruns every check in the edited file (31 total). Existing D8 consent and settings-witness checks remain in the unchanged unit files covered above.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Issues in the windows (Recommended)",
          "delegationLocation": "task t-20261109-windowfix request",
          "delegationScope": "The owner selected fixing issues encountered in Library and AI Brain, including similar recorded issues, with rechecks before fixing. The orchestrator bounded this repair to D9 focus after Downloaded files removal and poll closure, preserving D1-D8.",
          "choice": "Delegated D9 design: after removal settles, focus the enabled Remove files action, then Download again, otherwise the focusable outcome. When polling closes a band, restore focus only if it was inside that band; preserve a deliberate move elsewhere during removal. Test real Chromium with an operation acknowledging running while its row stays disabled, followed by a later terminal poll and another render. Keep D1 safe-answer/Escape/opener, D2 single-answer/key-repeat guards, D3 document replacement/window isolation, D4 preview details/geometry, D5 approved digest/refusal, D6 clearing on hide/disappearance and D7 outcomes/conflicting-action guards unchanged. D1-D9 and test choices are delegated choices, not owner-authored scenarios.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/windowfix-w3-brain-removal-focus.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.removalBand-lockdown.test.ts"
      ]
    }
  ]
}
```

## Acceptance records

Pending independent acceptance; the builder does not accept its own work.

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1c210-eda7-7153-a9ec-2aa730ce0b2d",
  "verdict": "accept",
  "digest": "sha256:bc70c19fd8cf2ca6e31dedf2be836073bd50bcc1d142ae2f8340984a53d38925"
}
```
