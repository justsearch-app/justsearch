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
No acceptance record is authored here. Evidence paths below are populated by the official formal producer, using the recorded
machine-rules adapter where indicated; pending paths are not execution claims.

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
      "behaviour": "The production process factory/shared settings/operation/index/queue owners retain all original exact removal, source/sibling, outside-FILE and completed-withdrawal assertions, including the unchanged R1 root-only preservation check. Historical control1/control2 were SETUP/INCONCLUSIVE. Exact committed72ef nonexcluded native control3 passed real certification/writable publication and same-profile boot; the first actual completed-Forget R2 target reached a genuine accepted native v3 UPSERT/DONE SKIPPED_POLICY/null-hash receipt and strict target zero, then failed the intended projection-certificate assertion. That before observation was neither a permanent-hold claim nor a setup/compile failure. The R2 control/target assertions remain, with raw actual Forget operation/settings/pointer output added for provenance. A new public settings-service HOT Save check removes the literal only after that real terminal skip, outside publication ownership; it requires a fresh actual unit/receipt/accepted order, real current-byte target publication and same-profile boot while preserving the original completed Forget receipt. This new check is a component public-service path, not degraded Head HTTP evidence. All source in this policy-receipt implementation phase is uncompiled/unexecuted and its unchanged formal record remains pending; earlier logs are historical only. Existing in_process fixture isolation and supported publicly requested SWITCHING after actual readiness remain; no default process isolation or automatic monitor timing claim is made. Final live fixture prerequisite correction: EngineRoot-only setup now performs real ordinary sibling extraction, public watchRoot and ordinary-work settlement before Forget/START. It does not inherit Head watcher initialization automatically. Original3/R1/Fixture bytes stay unchanged; all final paired before/after must use this same public-API setup against72ef. Historical target/control records are unchanged and supply no retroactive pass for the new setup.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "choice": "S55.1–S55.5, DF55 and WD55: exact removal uses the real owners, keeps original files, applies the outside-root FILE exception, and never overwrites completed work as cancelled. The independently challenged integration probe uses the mechanical Forget+main baseline, existing real owners and explicit setup assertions; the root-only control converged, so no failure or production repair is claimed for that case. R2 and N55.3, adopted after independent receipt challenge PASS1/PASS2: keep-out safety and native migration convergence both matter, with matching real control and specific admitted-target proof; R7/R6/R5 qualifications remain bounded as agreed. Adopted contract4 and implementation clarifications5: distinguish strict UI omission from current-policy normal readmission, retain exact queue/root/physical ownership and publication versions; real R2 is the repair discriminator while root-only R1 stays preservation.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-forget-current-java.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineForgetInProcessTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineForgetInProcessTest.java#S55CompletedForgetAllowsReturnedRootReplayWithoutPublishingExcludedSource",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineForgetInProcessTest.java#R2NonexcludedNativeMigrationControlPublishesCurrentBytesAndReopensSuccessor",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineForgetInProcessTest.java#R2CompletedWatchedFileForgetSurvivesLaterNativeMigrationAndSuccessorBoot",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineForgetInProcessTest.java#R9HotPublicSaveAfterNativePolicySkipReadmitsCurrentBytesAndKeepsCompletedForgetReceipt"
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
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.libraryRetry.test.ts"
      ],
      "class": "New",
      "behaviour": "Actual catalog-minted Stage, Library and Search v3 route confirmed Retry through the initial target-specific reconciliation, including retained/remounted, split and late-result owners. Exact key/path/scope mismatch stays unknown. Noncomplete outcomes, API-owner ABA, evicted origins, duplicate notifications, new unrelated draft/query/pane/Ask scope and saved turn scopes retain their boundaries. No global event bus, replay journal, durable UI event or cross-tab exactly-once guarantee.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "choice": "LR56 clarifies S56.3/S56.7: successful Library Retry reconciles the same affected current results/count/selection/one-send prepared Ask scope/document pane as initial completion, through the existing retained-surface owner. Wrong, stale, partial or unknown receipts cannot reconcile; unrelated queries/panes/drafts/conversations/saved turn scopes survive and late completion cannot widen context.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-forget-library-recovery-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.libraryRetry.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/WorkerMovePublicationFenceTest.java"
      ],
      "class": "New",
      "behaviour": "RF55 allowed and excluded physical MOVE preserve the shared destination-policy/identity/publication fence. The ordinary allowed move keeps its actual source/destination bytes, identity and indexed content.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "choice": "RF55: recheck live destination policy inside the same file fence as identity and Lucene publication; preserve ordinary allowed move semantics.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-forget-existing-repairs.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/WorkerMovePublicationFenceTest.java#RF55MoveDestinationPolicyAndIdentityAreRecheckedInsideThePublicationFence"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineForgetFolderIdentityTest.java"
      ],
      "class": "New",
      "behaviour": "Real prepared FOLDER Forget covers equality and separator descendants, including former-file proper chunks and exact queued work, without widening to sibling bc. Delete failures, ignored exact deletion and unknown absence remain incomplete; original-intent Retry converges and source files survive.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "choice": "FX55: FOLDER equality belongs to its exact target; COMPLETE requires exact and descendant rows/chunks/owned queue absence, with truthful partial/unknown results and same-intent recovery.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-forget-folder-identity-repair.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineForgetFolderIdentityTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineForgetMovePublicationTest.java"
      ],
      "class": "New",
      "behaviour": "Real physical MOVE/RENAME/undo with actual request expiry or cancellation cannot rekey or republish after outside-FILE Forget completes, including root retirement and delayed resumed publishers. An already admitted publisher keeps Forget incomplete until explicit Retry.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "choice": "MD55: original request cancellation or elapsed absolute deadline must be checked under the MOVE file fence before identity and rows; no permanent outside-FILE rule or unrelated cancellation.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-forget-move-deadline-repair.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineForgetMovePublicationTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineUnaryCancellationGapTest.java"
      ],
      "class": "New",
      "behaviour": "Existing Budget cancellation polling observes CANCELLED and EXPIRED unary outcomes before delayed signal handlers, preserving caller-first completion and handler notification order.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "choice": "MD55: existing outcome plus signal closes the cancellation-before-signal publication gap without a cancellation framework.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-forget-cancellation-gap-repair.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineUnaryCancellationGapTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/IndexingControllerForgetHttpContractTest.java"
      ],
      "class": "New",
      "behaviour": "Actual HTTP registrar/controller/schema distinguish exact bounded pages and missing-key UNKNOWN from unavailable/unsupported reads, reject non-WEBVIEW and malformed queries, and never trigger effects. A fixed read-owner proxy does not claim Engine deletion coverage.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "choice": "S55.12/S56.7/RF55: UI-only read contract preserves exact target identities and truthfully distinguishes absent intent from unavailable observation.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-forget-current-java.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/IndexingControllerForgetHttpContractTest.java"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/configuration/src/test/java/io/justsearch/configuration/excludes/LiteralExclusionPolicyTest.java"
      ],
      "class": "New",
      "behaviour": "Portable existing JSON/public policy interfaces cover exact Unix bytes, folder separator boundaries, explicit OS case rules, unchanged legacy globs, long literal root-plan projection, strict malformed objects and combined cap. The public saved-list method is invoked through its unchanged erased signature to compile against the old List<String> declaration; identical mixed inputs and assertions are retained.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "choice": "RF55/S55.2/S55.4: exact literal objects use the released JSON/public policy boundary, preserve legacy strings and malformed-object refusal, and count against the existing combined cap. New capability is demonstrated against compilable old interfaces, never absent types.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-forget-literal-policy-new.json",
      "newChecks": [
        "modules/configuration/src/test/java/io/justsearch/configuration/excludes/LiteralExclusionPolicyTest.java"
      ],
      "repaired": false
    }
  ]
}
```

## Acceptance records
