---
schema: test-intent.v1
task: t-20261109-windowfix
author-role: builder
author-session: windowfix-w1-builder
---

# Library and AI Brain window repairs

These are agent technical choices under the quoted owner decision, not owner-adopted scenario labels or owner words taken from the orchestrator brief. The wholly new browser, label and SES deadline test files are gate-exempt isolated sources; the modified tests below record why their old expectations changed. Deadline formatting uses Intl.DateTimeFormat because SES lockdown replaces Date locale methods with raw strings. Full-file execution evidence for all three deadline test files is regenerated in gates/test-intent/evidence/windowfix-w1-deadlines.json; the regression tests also fail against a Date#toLocaleString implementation in an isolated shared clone.

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
          "label": "agent choice \u2014 replaces old expectations under the delegated window-issue fixes"
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
      "behaviour": "A retry wait reads 'Next retry at <local time>.' with hours and minutes, adding the short weekday only when the deadline is not today. Each Library folder row keeps its own 'Waiting to retry \u00b7 N remaining' status without the library-wide count or time; those appear once at library level, including while a reindex outcome is shown.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Issues in the windows (Recommended)",
          "delegationLocation": "task t-20261109-windowfix request",
          "delegationScope": "Recheck and fix the Library and AI Brain window issues listed in the relayed option.",
          "choice": "Scenario L1: the library-wide retry wait appears once at library level with an app-style local time, and folder rows show only their own status.",
          "label": "agent choice \u2014 builder implementation of delegated scenarios"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/windowfix-w1.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/indexingWaiting.test.ts#shows retry waiting with the backlog and deadline then follows the worker lifecycle",
        "modules/ui-web/src/shell-v0/state/indexingWaiting.test.ts#L1 uses hours and minutes without a weekday for a retry today",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.render.test.ts#L1 library retry waiting appears once outside folder rows"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/indexingProgress.pause.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Issues in the windows (Recommended)",
          "delegationLocation": "task t-20261109-windowfix request",
          "delegationScope": "Recheck and fix the Library and AI Brain window issues listed in the relayed option.",
          "choice": "The indexing pause's Until deadline uses the same local hours and minutes as the retry deadline, adding the short weekday only when it is not today. The raw date expectation is retired because SES lockdown ignores Date locale formatting options.",
          "label": "agent choice \u2014 shared local deadline formatting under delegated window-issue fixes"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none (docs/decisions, docs/reference and docs/explanation searched for the rebuild label and retry wording)"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/FailedFilesList.buttons-lockdown.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Issues in the windows (Recommended)",
          "delegationLocation": "task t-20261109-windowfix request",
          "delegationScope": "Recheck and fix the Library and AI Brain window issues listed in the relayed option.",
          "choice": "Narrowed L12: Cancel and Retry take only the nearby compact Try again text size, preserving the shared ActionButton border, background, padding and radius. Retire the full-height match, which required overriding the shared padding and broke the frame DP3 styling contract. Keep light, dark, forced-colors, narrow-window, wide-font, keyboard-focus and unrelated-ActionButton preservation checks.",
          "label": "agent choice \u2014 narrowed L12 repair under delegated window-issue fixes"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none (docs/decisions, docs/reference and docs/explanation searched for failed-file actions, compact buttons and shared ActionButton styling); the existing Shell.details-lockdown.test.ts DP3 styling contract is preserved."
    }
  ]
}
```

L12 now compares only text size with the compact Try again button, and compares border, background, padding and radius with an unrelated shared ActionButton. Full-file candidate execution evidence for all three theme cases is regenerated in gates/test-intent/evidence/windowfix-w1-buttons.json. In a private shared clone of e3a479db7d with the revised test overlaid, all three cases fail the text-size assertion (expected 13px, received 16px); on f68be006c6 before the padding repair, all three fail the shared-padding assertion (expected 8px 16px, received 6.4px 12px). Existing acceptance records are retained for independent re-review of this repair.

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "windowfix-w1-verifier-u11v9mt8",
  "verdict": "accept",
  "digest": "sha256:9e08dda44a84c5767860d6c2fd9612467ca48f5d2672103f3058bb89e15691a0"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1c273-39d9-7452-a8a1-a9306f821163",
  "verdict": "accept",
  "digest": "sha256:d0f0c8f5d0d94c67f7dce4335947325647d292f4775b8b71db51ed510f5a489c"
}
```
