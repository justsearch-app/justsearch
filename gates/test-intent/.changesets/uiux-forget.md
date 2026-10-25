---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: codex-uiux-forget-builder
---

# Test-intent entries

These choices are agent-adopted under the owner's design delegation, not owner-authored scenarios.
The quoted delegation and its exact scope are reproduced separately in every warrant. The settled
WP5.5/WP5.6 scenarios include RF55/DF55/WD55/Q55/MD55 and the honest-incomplete amendment.
No acceptance record is authored here. Evidence paths below are populated only by the unchanged
formal producer; pending paths are not execution claims.

Existing v0–v5 compatibility cases are retained. The final builder report separately inventories
unchanged-before preservation, version-6 current-writer assertions, and the Library fixture adaptation.
Isolated new files remain subject to independent scenario and execution review even when gate-exempt.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-agent/src/test/java/io/justsearch/agent/ReadDocumentToolTest.java"
      ],
      "class": "New",
      "behaviour": "An absolute indexed document ID retains every Unix trailing-space/backslash byte at the fetch boundary. Relative document input retains the existing whitespace convenience; no existing reader oracle changes.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "choice": "RF55.4 and S55.1/S55.2: exact literal path identity must reach document reads without stripping bytes, while ordinary relative-input semantics remain.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-forget-existing-repairs.json",
      "newChecks": [
        "modules/app-agent/src/test/java/io/justsearch/agent/ReadDocumentToolTest.java#absoluteIndexedIdsPreserveTrailingSpaceAndBackslash"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/schema/WireRecordSchemaGenTest.java",
        "modules/app-api/src/test/java/io/justsearch/app/api/schema/WireSchemaConfig.java"
      ],
      "class": "New",
      "behaviour": "Canonical ForgetOutcome/Page schemas retain explicit unknown/incomplete states and exact nullable targets; mixed exclusion items remain unchanged legacy strings or strict version-1 literal objects. Existing wire-record generation cases remain.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "choice": "Approved WP5.5/WP5.6 design: Java wire authorities generate exact typed mixed settings and bounded honest public receipts, with no handwritten alternate authority.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-forget-current-java.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/schema/WireRecordSchemaGenTest.java#forgetOutcome",
        "modules/app-api/src/test/java/io/justsearch/app/api/schema/WireRecordSchemaGenTest.java#forgetPage",
        "modules/app-api/src/test/java/io/justsearch/app/api/schema/WireRecordSchemaGenTest.java#settingsV2"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/app-launcher/src/test/java/io/justsearch/app/launcher/OperationStoreArchitectureTest.java"
      ],
      "class": "Structural rule kept",
      "rule": "Only OperationAttemptRunner writes operational state or enters settings commitment; only SettingsCommitCoordinator and its narrow ForgetProjection own nonterminal settings arm/settle/publication/release.",
      "whySurvives": "All original predicates remain. Additional FORGET mutation methods are guarded, with only the approved existing runner/coordinator and its nested projection admitted; no production caller is generally exempted. String-based nested-type identification keeps the architecture check portable to the before-state.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "choice": "RF55/WD55/Q55: retain the existing durable operation/settings owners and add only a narrow nonterminal FORGET settings projection; notifications follow outer-owner release.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/app-observability/src/test/java/io/justsearch/app/observability/operations/OperationHistoryPendingTest.java",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/operations/OperationHistoryPolicyTest.java",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/operations/OperationPreparationStoreTest.java",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/operations/SqliteOperationStoreStartupContentionTest.java",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/operations/SqliteOperationStoreTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/UiSettingsStoreContextLengthMigrationTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/UiSettingsStorePersistenceModeTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/UiSettingsStoreRevisionTest.java"
      ],
      "class": "New",
      "behaviour": "The current operations/settings envelope is semantic version 6. Existing migration, contention, preservation, recovery, context-length and revision assertions remain, including legacy v0–v5 fixtures. A new actual v5 operations reopen asserts row and sqlite_sequence preservation across the semantic migration. Only current-writer version expectations advance from 5 to 6.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "choice": "RF55 and approved design: actual operations/settings semantic v6 migrations protect typed literal ownership and FORGET recovery interpretation; old supported data and unrelated settings retain their meanings.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-forget-current-java.json",
      "newChecks": [
        "modules/app-observability/src/test/java/io/justsearch/app/observability/operations/OperationHistoryPendingTest.java",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/operations/OperationHistoryPolicyTest.java",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/operations/OperationPreparationStoreTest.java",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/operations/SqliteOperationStoreStartupContentionTest.java",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/operations/SqliteOperationStoreTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/UiSettingsStoreContextLengthMigrationTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/UiSettingsStorePersistenceModeTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/UiSettingsStoreRevisionTest.java"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/operations/CoreOperationCatalogTest.java"
      ],
      "class": "New",
      "behaviour": "The exact core catalog gains only core.forget-indexed-path, core.retry-forget and core.withdraw-forget. Every existing catalog expectation remains.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "choice": "S55.12, RF55 and WD55: expose the precise operation and explicit original-intent Retry/Withdraw through the existing UI-only operation catalog, with no agent/MCP offer.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-forget-current-java.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/operations/CoreOperationCatalogTest.java"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineForgetInProcessTest.java"
      ],
      "class": "New",
      "behaviour": "The production process factory and shared settings/operation/index/queue owners remove the exact parent and proper chunks while preserving sibling/source bytes and durable precise policy. Outside-root FILE completion has no permanent rule; an unrestricted new writer may publish later. Completion-winning withdrawal preserves its immutable receipt. The direct writer case is not evidence of a newly accepted INGEST.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "choice": "S55.1–S55.5, DF55 and WD55: exact removal uses the real owners, keeps original files, applies the outside-root FILE exception, and never overwrites completed work as cancelled.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-forget-current-java.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineForgetInProcessTest.java"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/operations/ForgetHandlersTest.java"
      ],
      "class": "New",
      "behaviour": "Real handlers freeze exact consent and original-key retry/withdraw decisions, distinguish whitespace/control/format bytes in presentation, explain the outside-FILE exception and retained copies, and reject malformed/coerced/duplicate/oversized or non-WEBVIEW calls before effects.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "choice": "S56.2/S55.12, RF55/DF55/WD55: explicit exact named consent, bounded trust-boundary validation, private target ownership and no agent/MCP execution.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-forget-current-java.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/operations/ForgetHandlersTest.java"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/ForgetSettingsProjectionTest.java"
      ],
      "class": "New",
      "behaviour": "Real files/config/SQLite show a fresh unrelated-settings/query-role/foreign-rule preserving nonterminal install and exact-token withdrawal. File-replace crash recovery resolves only the armed settings step by exact bytes; altered or ambiguous bytes remain unknown and cannot complete the FORGET.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "choice": "RF55.1, S55.8 as amended for honest incomplete, and WD55: no retry recreates a removed/replaced token, fresh projection preserves unrelated policy/settings, and ambiguous withdrawal prevents destructive retry.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-forget-current-java.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/ForgetSettingsProjectionTest.java"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/commands/searchResultActions.test.ts"
      ],
      "class": "New",
      "behaviour": "Only a capable host callback gains the file/folder verbs; help/history and unsupported old hosts retain the original three verbs. Every existing ordinary-action assertion remains.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "choice": "S56.1/S56.6/S56.9 and N56.1/N56.3: keyboard/pointer shared results host capability and protected-source restrictions preserve ordinary result verbs.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-forget-ui-compatibility.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/commands/searchResultActions.test.ts"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.settings-attempt.test.ts"
      ],
      "class": "New",
      "behaviour": "The exact rows visibly distinguish Unix slash/backslash, trailing whitespace, controls, NBSP and format characters; glob edits retain literal bytes and witnessed removal drops only the chosen literal. Existing retained-base/settings-attempt assertions remain. The fixture now explicitly answers the independent unfinished-forgets GET with a complete empty page; it does not consume or replace any original settings request/response or expectation.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "choice": "RF55.1/RF55.4 and S55.4/S56.2: exact policy rows are separate from unchanged legacy glob editing, explicit removal uses the existing witnessed Save, and exact named consent remains distinguishable.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-forget-ui-compatibility.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.settings-attempt.test.ts"
      ],
      "repaired": false
    }
  ]
}
```

## Acceptance records
