---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: codex-forget-round10
---

# Forget UI module refusals and schema inventory

Agent-adopted under the owner delegation, bounded by the round 10 brief.
ExclusionSaveHttpTest is unchanged: its existing assertion is the preservation regression check.
The builder does not produce evidence or an acceptance record. Exact evidence arguments and
raw check results are in tmp/verify/forget-round10-report.md.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ExclusionSaveStateHttpTest.java"
      ],
      "class": "New",
      "behaviour": "Through the direct settings front, input capacity overflow, expanded capacity overflow and new glob syntax errors return 400 INVALID_REQUEST over real HTTP with the real operation runner. Settings bytes, witness, policy, durable directory files and SQLite operation/preparation counts stay unchanged, no recovery issue appears, and a following valid save completes and reloads. Preservation: run unchanged on pre-fix and main too.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "Round 10: preserve 400 INVALID_REQUEST for invalid exclusion saves, saved settings and keep-out semantics; assert both Forget schema names and count 23.",
          "choice": "Agent-adopted round 10 checks cover real loopback HTTP, the production core.reconfigure dispatcher and direct settings front, private settings and SQLite operations, byte-identical durable files after refusal, no operations/preparations or recovery issue, and a subsequent valid save and reload. The schema inventory retains every existing member assertion and adds both declared Forget schemas.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-forget-ui-state-preservation.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ExclusionSaveStateHttpTest.java#refusalsLeaveNoDurableStateAndFollowingValidSaveSucceeds"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ExclusionSaveStateHttpTest.java"
      ],
      "class": "New",
      "behaviour": "Through the production core.reconfigure dispatcher, all three exclusion validation refusals happen before durable operation/preparation acceptance. Complete settings, witness and durable file bytes remain unchanged, operation/preparation counts stay zero, no recovery issue appears, and a following valid save completes and reloads. The pre-fix check asserts durable-file equality before checking HTTP status, so a durable operation/journal mutation fails for the data-integrity reason, not merely an error-code mismatch.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "Round 10: preserve 400 INVALID_REQUEST for invalid exclusion saves, saved settings and keep-out semantics; assert both Forget schema names and count 23.",
          "choice": "Agent-adopted round 10 checks cover real loopback HTTP, the production core.reconfigure dispatcher and direct settings front, private settings and SQLite operations, byte-identical durable files after refusal, no operations/preparations or recovery issue, and a subsequent valid save and reload. The schema inventory retains every existing member assertion and adds both declared Forget schemas.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-forget-ui-state-repair.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ExclusionSaveStateHttpTest.java#productionRefusalsLeaveNoDurableStateAndFollowingValidSaveSucceeds"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/SchemaControllerTest.java"
      ],
      "class": "Structural rule kept",
      "rule": "servedNamesIncludeKnownSchemas enumerates every expected served schema and requires the exact inventory size.",
      "whySurvives": "The round 10 brief names the two new inventory members, forget-outcome.v1.json and forget-page.v1.json. All 21 existing name assertions remain, both named members are added, and the exact count becomes 23. No other test or oracle changes.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "Round 10: preserve 400 INVALID_REQUEST for invalid exclusion saves, saved settings and keep-out semantics; assert both Forget schema names and count 23.",
          "choice": "Agent-adopted round 10 checks cover real loopback HTTP, private settings and SQLite operations, byte-identical durable files after refusal, no operations/preparations or recovery issue, and a subsequent valid save and reload. The schema inventory retains every existing member assertion and adds both declared Forget schemas.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```
