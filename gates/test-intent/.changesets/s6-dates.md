---
schema: test-intent.v1
task: t-20261103-shell
author-role: builder
author-session: codex-s6-dates-builder
---

# Date display under SES

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/display/date-call-sites-lockdown.test.ts",
        "modules/ui-web/src/shell-v0/display/dateLocaleGuard.test.ts"
      ],
      "class": "New",
      "behaviour": "Under real SES lockdown, consent, provenance, ledger/effect tooltips and conversation creation retain local numeric date/time with seconds; snapshot labels, health transitions/tooltips/traces/events, recovery retries and demo events retain local time with seconds; the engine-loss retry retains two-digit hours/minutes. Check both en-US/New York and de-DE/Berlin. Lint rejects planted Date locale calls (direct, variable, optional and computed access), including JavaScript, while allowing number formatting.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "Keep each existing timestamp display’s requested local fields through SES lockdown, preserve saved labels and timestamp fallbacks, and prevent Date locale-method regressions with a type-aware lint rule that leaves number formatting available.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-dates-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/display/dateLocaleGuard.test.ts",
        "modules/ui-web/src/shell-v0/display/date-call-sites-lockdown.test.ts#formats consent timestamp as requested under SES lockdown",
        "modules/ui-web/src/shell-v0/display/date-call-sites-lockdown.test.ts#formats new snapshot label as requested under SES lockdown",
        "modules/ui-web/src/shell-v0/display/date-call-sites-lockdown.test.ts#formats transition tooltip as requested under SES lockdown",
        "modules/ui-web/src/shell-v0/display/date-call-sites-lockdown.test.ts#formats transition time as requested under SES lockdown",
        "modules/ui-web/src/shell-v0/display/date-call-sites-lockdown.test.ts#formats trace start as requested under SES lockdown",
        "modules/ui-web/src/shell-v0/display/date-call-sites-lockdown.test.ts#formats automatic retry as requested under SES lockdown",
        "modules/ui-web/src/shell-v0/display/date-call-sites-lockdown.test.ts#formats engine loss retry as requested under SES lockdown",
        "modules/ui-web/src/shell-v0/display/date-call-sites-lockdown.test.ts#formats ledger tooltip as requested under SES lockdown",
        "modules/ui-web/src/shell-v0/display/date-call-sites-lockdown.test.ts#formats effect tooltip as requested under SES lockdown",
        "modules/ui-web/src/shell-v0/display/date-call-sites-lockdown.test.ts#formats observation provenance as requested under SES lockdown",
        "modules/ui-web/src/shell-v0/display/date-call-sites-lockdown.test.ts#formats health event time as requested under SES lockdown",
        "modules/ui-web/src/shell-v0/display/date-call-sites-lockdown.test.ts#formats agent demo time as requested under SES lockdown",
        "modules/ui-web/src/shell-v0/display/date-call-sites-lockdown.test.ts#formats conversation creation time as requested under SES lockdown"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/display/date-call-sites-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "Existing snapshot labels retain their text; absent trace times retain the dash; invalid ledger timestamps retain their original text in tooltips.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "Keep each existing timestamp display’s requested local fields through SES lockdown, preserve saved labels and timestamp fallbacks, and prevent Date locale-method regressions with a type-aware lint rule that leaves number formatting available.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-dates-preserved.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/display/date-call-sites-lockdown.test.ts#preserves saved snapshot labels and missing or invalid timestamp fallbacks"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/uncountedIndex.test.ts",
        "modules/ui-web/src/shell-v0/views/HealthSurface.render.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Only the timestamp expression in existing assertions changes from Date#toLocaleString to Intl.DateTimeFormat with the same default numeric year/month/day/hour/minute/second fields. The inputs, preconditions, positive/negative expectations and observations remain unchanged; this also keeps test code compliant with the Date locale lint guard.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "Keep each existing timestamp display’s requested local fields through SES lockdown, preserve saved labels and timestamp fallbacks, and prevent Date locale-method regressions with a type-aware lint rule that leaves number formatting available.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```

## Acceptance records
