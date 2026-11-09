---
schema: test-intent.v1
task: t-20261109-windowfix
author-role: builder
author-session: windowfix-w3-builder
---

# Test-intent entries

The sole quoted owner decision is the selection relayed in the task request. All technical
scenarios, design and test choices are delegated choices from the orchestrator brief.

The isolated `BrainSurface.removalBand-lockdown.test.ts` retains all 31 existing checks and adds
two durable D9 cases for polling that closes a focused confirmation without a row action or
removal outcome: not-downloaded with declined=false, and downloaded with removable=false.
The final focus fallback is the Downloaded files section with tabindex="-1". Nothing else in
the focus logic changes. D8 and the settings witness retain the unchanged unit-file coverage.

Execution evidence is regenerated for all 33 checks in the edited file. The original five
repair checks retain assertion fail-before evidence on 6e78066eaa. Both new cases fail at the
section-focus assertion on 1a0b2be2dc and pass after the fallback. The before states use shared
clones inside the session temporary directory with their own copied dependencies; automated
before worktrees are registered only in that clone. Scratch logs and axe/tab-order comparison
scaffolding are under `tmp/windowfix-w3/`. Adding tabindex="-1" leaves axe results and complete
keyboard tab cycles unchanged in downloaded, not-downloaded and not-removable fixture states.
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
      "behaviour": "D9: polling closes a focused confirmation onto the Downloaded files section when the package becomes not-downloaded with declined=false or remains downloaded with removable=false, and no row action or removal outcome exists. Both checks fail at the focus assertion on 1a0b2be2dc and pass after the section fallback.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Issues in the windows (Recommended)",
          "delegationLocation": "task t-20261109-windowfix request",
          "delegationScope": "The owner selected fixing issues encountered in Library and AI Brain, including similar recorded issues, with rechecks before fixing. The orchestrator bounded this repair to the final D9 focus fallback, preserving D1-D8 and all existing focus logic.",
          "choice": "Delegated design: make the Downloaded files section, with tabindex=\"-1\", the final focusPartAction fallback after enabled row actions and removal outcome. Add the two independently reported polling cases with focus on Keep it, no row action and no outcome; assert section focus, persistence on another refresh and no removal request. Scenarios and test choices are delegated choices, not owner-authored scenarios.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/windowfix-w3-brain-removal-section-focus.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.removalBand-lockdown.test.ts#D9 polling closes a focused band onto Downloaded files when not-downloaded leaves no action or outcome",
        "modules/ui-web/src/shell-v0/views/BrainSurface.removalBand-lockdown.test.ts#D9 polling closes a focused band onto Downloaded files when not-removable leaves no action or outcome"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.removalBand-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "Retain all 31 existing D1-D7/D9 Chromium checks without changing their expectations, including both D9 focus-preservation checks. Evidence reruns every check in the edited file (33 total), including the two new section-fallback cases. Existing D8 consent and settings-witness checks remain in the unchanged unit files covered above.",
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

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1c261-e2cd-7540-9e2c-b7b2ee1acaaa",
  "verdict": "accept",
  "digest": "sha256:4fc7536c4b7b2854d2ee99462d6004957d17e27c71776cf4850c5f27b30cdeb6"
}
```
