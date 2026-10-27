---
schema: test-intent.v1
task: t-20261026-query-classification-integration
author-role: builder
author-session: codex-rescope-builder-20261026
---

# Test-intent entries

This narrowed candidate retains policy and diagnostics while preserving baseline classification.
The user delegation is quoted verbatim; all technical choices are agent choices. The historical
query-understanding wire ID remains the classifier projection and is not evidence of LLM dispatch.
Disabled classification has no raw type; effective INFORMATIONAL also applies to explicit pipelines
and LUCENE syntax. An explicit SIMPLE mode alone keeps classifier gating.

Kept checks cover bypass diagnostics, SIMPLE-only QU, LUCENE rerank eligibility, per-source expansion
skip, renderer wording and independent caller/stage authorities. They use readiness stand-ins and
controlled completions; they do not establish parser, real model, ranking, HTTP/MCP or full UI quality.
Classifier-rule broadening and its former quality claims are withdrawn.

Evidence is regenerated on the narrowed source. Before-state signature compatibility is declared in
`gates/test-intent/compatibility/qc-dispatch-before.patch`; it forwards to the old mapper unchanged.
The producer runs from `tmp/agent-tmp/rescope-builder/evidence-producer/scripts/governance/gates/test-intent/`, a scratch copy of the
repository producer. Its only launcher adaptations set the source root, run Gradle with
`--no-daemon` under `js-gradle-slot` (one slot for the serial evidence batch), and replace before-worktree management with retained shared
clones under TMPDIR. Assertion parsing, check selection, evidence format and digest generation are
unchanged. The before-renderer run uses `--copy-node-modules` for its own dependency tree.
The scratch copier preserves the installed tree's internal relative symlinks after resolving its
root link: the original dereferencing copier broke the Vitest executable's relative import. The
first failed launcher output is retained under `tmp/agent-tmp/rescope-builder/` as
`qc-dispatch-integration-repair-producer-attempt1.log` and the corresponding attempt1 JSON.
The earlier classifier jobs' launcher bytes are retained as `run-evidence-attempt1.mjs` in the
scratch producer directory and match their recorded digest; subsequent jobs use the corrected copier.
The recorded producer script digest is the actual adapted script. No repository gate
or test-selection logic is changed. These adaptations satisfy the assignment's no-worktree and
no-daemon constraints; `prepare-producer.py` and the adapted bytes remain alongside the raw logs. Independent acceptance remains pending.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineClassificationDispatchTest.java"
      ],
      "class": "New",
      "behaviour": "R2/R3/R5 policy and diagnostic repairs: no invented disabled raw type; captured raw diagnostic detail and bypass reasons with disabled > explicit > LUCENE precedence; independent SIMPLE-only QU gate including previously eligible LUCENE terms and explicit Library match-all; raw navigational LUCENE text reaches actual CE calls. All are local owner-level dispatch/diagnostic checks, not parser, HTTP, model-quality or ranking evidence.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task register accordingly for query classification, consider that you will be the orchestrator for this task. firstly proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task query-classification user turn owner-delegation (quoted verbatim in the rescope builder assignment, 2026-10-26); follow-up: proceed accordingly",
          "delegationScope": "Investigate, design and implement query-classification improvements autonomously within the assigned baseline-preservation and policy/diagnostics scope. Technical scenarios, fixtures and test oracles are delegated agent choices; independent acceptance remains pending.",
          "choice": "Retain captured raw/effective classification policy and bypass precedence DISABLED > EXPLICIT_PIPELINE > LUCENE_SYNTAX, SIMPLE-only LLM QU, raw retrieval/rerank text and other stage authorities, per-source expansion skip and renderer wording. Preserve baseline labels, including preset dispatch for mixed filenames and quoted paths. Use baseline-INFORMATIONAL questions for optional-budget and per-source schedules; restore the filename NAVIGATIONAL precedence check. No classifier broadening or quality claim.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/qc-dispatch-integration-repair.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineClassificationDispatchTest.java#disabledHasNoDetectedType",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineClassificationDispatchTest.java#bypassRetainsRawTypeAndReportsReason",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineClassificationDispatchTest.java#lucenePreviouslyEligibleQuNeverDispatches",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineClassificationDispatchTest.java#libraryMatchAllFacetLoaderLosesOnlyOptionalQu",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineClassificationDispatchTest.java#luceneNavigationalShapesReachCeWithRawText",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineClassificationDispatchTest.java#disabledAndInformationalAreDifferentEvenIfEffectivePolicyIsTheSame",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineClassificationDispatchTest.java#disabledDiagnosticUsesCapturedConfigEvenAfterPublicationChanges",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineClassificationDispatchTest.java#luceneQuotedShapesRemainCeEligibleWithoutQu"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/aggregate-substrate/strategies/searchTraceExplain.test.ts"
      ],
      "class": "New",
      "behaviour": "R3/R4 renderer wording: classifier raw detail and bypass reason both visible inside existing collapsed diagnostics; unknown historical classifier reasons/details stay visible; readable PER_SOURCE_RETRIEVAL remains diagnostic only. HYBRID + expansion TIMEOUT retains Hybrid search summary and says only AI expansion timed out. The existing TEXT timeout test is updated to the same neutral copy. No label, control, facet altitude or historical JSON change.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task register accordingly for query classification, consider that you will be the orchestrator for this task. firstly proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task query-classification user turn owner-delegation (quoted verbatim in the rescope builder assignment, 2026-10-26); follow-up: proceed accordingly",
          "delegationScope": "Investigate, design and implement query-classification improvements autonomously within the assigned baseline-preservation and policy/diagnostics scope. Technical scenarios, fixtures and test oracles are delegated agent choices; independent acceptance remains pending.",
          "choice": "Retain captured raw/effective classification policy and bypass precedence DISABLED > EXPLICIT_PIPELINE > LUCENE_SYNTAX, SIMPLE-only LLM QU, raw retrieval/rerank text and other stage authorities, per-source expansion skip and renderer wording. Preserve baseline labels, including preset dispatch for mixed filenames and quoted paths. Use baseline-INFORMATIONAL questions for optional-budget and per-source schedules; restore the filename NAVIGATIONAL precedence check. No classifier broadening or quality claim.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/qc-dispatch-integration-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/aggregate-substrate/strategies/searchTraceExplain.test.ts#words the user line: mode legibility + expansion honesty + degradation-in-words",
        "modules/ui-web/src/shell-v0/aggregate-substrate/strategies/searchTraceExplain.test.ts#shows raw classifier detail and explicit pipeline bypass together in diagnostics",
        "modules/ui-web/src/shell-v0/aggregate-substrate/strategies/searchTraceExplain.test.ts#shows raw classifier detail and Lucene bypass together in diagnostics",
        "modules/ui-web/src/shell-v0/aggregate-substrate/strategies/searchTraceExplain.test.ts#keeps per-source expansion skip readable and diagnostic without a degradation warning",
        "modules/ui-web/src/shell-v0/aggregate-substrate/strategies/searchTraceExplain.test.ts#does not claim keyword-only results when expansion times out over a preserved hybrid base",
        "modules/ui-web/src/shell-v0/aggregate-substrate/strategies/searchTraceExplain.test.ts#renders unknown historical classifier reasons alongside their unchanged details"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineClassificationDispatchTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineExpansionBudgetTest.java",
        "modules/ui-web/src/shell-v0/aggregate-substrate/strategies/searchTraceExplain.test.ts"
      ],
      "class": "New",
      "behaviour": "R1/R6 preservation intent relative to d036a07a9b9: SIMPLE mode/AUTO preset dispatch retains baseline raw labels (including quoted paths and malformed quotes), caller raw query bytes, filters/boosts, normalization, sort/cursor authority, captured configuration and QU dispatch without surfaced extraction. Paired SIMPLE text remains QU eligible under explicit policy. Optional-budget and retained-owner schedules use questions already INFORMATIONAL on the baseline, with the original deadline, abandonment, release and terminal-cause oracles. The existing renderer preserves disabled and legacy diagnostics. Per-source informational and quoted-exact controls preserve their skip reasons. Local dispatch/controlled-completion evidence does not establish real model quality or reserve calibration.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task register accordingly for query classification, consider that you will be the orchestrator for this task. firstly proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task query-classification user turn owner-delegation (quoted verbatim in the rescope builder assignment, 2026-10-26); follow-up: proceed accordingly",
          "delegationScope": "Investigate, design and implement query-classification improvements autonomously within the assigned baseline-preservation and policy/diagnostics scope. Technical scenarios, fixtures and test oracles are delegated agent choices; independent acceptance remains pending.",
          "choice": "Retain captured raw/effective classification policy and bypass precedence DISABLED > EXPLICIT_PIPELINE > LUCENE_SYNTAX, SIMPLE-only LLM QU, raw retrieval/rerank text and other stage authorities, per-source expansion skip and renderer wording. Preserve baseline labels, including preset dispatch for mixed filenames and quoted paths. Use baseline-INFORMATIONAL questions for optional-budget and per-source schedules; restore the filename NAVIGATIONAL precedence check. No classifier broadening or quality claim.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/qc-dispatch-integration-preservation.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineClassificationDispatchTest.java#simplePresetsKeepRawPolicyAndActualDispatch",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineClassificationDispatchTest.java#simpleQuKeepsItsIndependentAuthorities",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineClassificationDispatchTest.java#luceneFiltersAndFieldSortRemainOwnedByCaller",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineClassificationDispatchTest.java#actualQuMayDispatchWithoutSurfacedExtraction",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineClassificationDispatchTest.java#capturedEnabledClassificationDoesNotFollowLaterGlobalDisable",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineClassificationDispatchTest.java#pairedSimpleTextStillDispatchesQu",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineExpansionBudgetTest.java#presetQuBudgetAndOwnershipMatchControl",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineExpansionBudgetTest.java#presetExpansionBudgetAndOwnershipMatchControl",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineExpansionBudgetTest.java#presetTerminationMatchesControl",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineExpansionBudgetTest.java#perSourceInformationalQuestionSkipsUnconsumedExpansion",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineExpansionBudgetTest.java#perSourceQuotedExactKeepsQueryTypeReasonPrecedence",
        "modules/ui-web/src/shell-v0/aggregate-substrate/strategies/searchTraceExplain.test.ts#does not reinterpret a legacy reasonless classifier row as disabled or LLM dispatch",
        "modules/ui-web/src/shell-v0/aggregate-substrate/strategies/searchTraceExplain.test.ts#renders disabled classification without inventing a detected value"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeHttpApiAdapterHeadStagesTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "Existing buildHeadStages callers append the captured classifier bypass argument; original inputs, CE governed wire reasons, stage exhaustiveness/order and stable wire-ID assertions remain. A before-only overload ignores the extra context and delegates unchanged old semantics; no production compatibility shim exists in candidate.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task register accordingly for query classification, consider that you will be the orchestrator for this task. firstly proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task query-classification user turn owner-delegation (quoted verbatim in the rescope builder assignment, 2026-10-26); follow-up: proceed accordingly",
          "delegationScope": "Investigate, design and implement query-classification improvements autonomously within the assigned baseline-preservation and policy/diagnostics scope. Technical scenarios, fixtures and test oracles are delegated agent choices; independent acceptance remains pending.",
          "choice": "Retain captured raw/effective classification policy and bypass precedence DISABLED > EXPLICIT_PIPELINE > LUCENE_SYNTAX, SIMPLE-only LLM QU, raw retrieval/rerank text and other stage authorities, per-source expansion skip and renderer wording. Preserve baseline labels, including preset dispatch for mixed filenames and quoted paths. Use baseline-INFORMATIONAL questions for optional-budget and per-source schedules; restore the filename NAVIGATIONAL precedence check. No classifier broadening or quality claim.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineExpansionBudgetTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "split",
        "instrumentation"
      ],
      "whatMoved": "Existing deadline and termination checks delegate their original requests and unchanged assertions to shared helpers. Added preset schedules substitute baseline-INFORMATIONAL questions for inputs that depended on the rejected classifier rules. The original mixed-filename NAVIGATIONAL per-source precedence check is restored; the additional quoted EXACT_MATCH control remains. No deadline, cancellation, identity, fallback or retained-work assertion is weakened.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task register accordingly for query classification, consider that you will be the orchestrator for this task. firstly proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task query-classification user turn owner-delegation (quoted verbatim in the rescope builder assignment, 2026-10-26); follow-up: proceed accordingly",
          "delegationScope": "Investigate, design and implement query-classification improvements autonomously within the assigned baseline-preservation and policy/diagnostics scope. Technical scenarios, fixtures and test oracles are delegated agent choices; independent acceptance remains pending.",
          "choice": "Retain captured raw/effective classification policy and bypass precedence DISABLED > EXPLICIT_PIPELINE > LUCENE_SYNTAX, SIMPLE-only LLM QU, raw retrieval/rerank text and other stage authorities, per-source expansion skip and renderer wording. Preserve baseline labels, including preset dispatch for mixed filenames and quoted paths. Use baseline-INFORMATIONAL questions for optional-budget and per-source schedules; restore the filename NAVIGATIONAL precedence check. No classifier broadening or quality claim.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/aggregate-substrate/strategies/searchTraceExplain.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task register accordingly for query classification, consider that you will be the orchestrator for this task. firstly proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task query-classification user turn owner-delegation (quoted verbatim in the rescope builder assignment, 2026-10-26); follow-up: proceed accordingly",
          "delegationScope": "Investigate, design and implement query-classification improvements autonomously within the assigned baseline-preservation and policy/diagnostics scope. Technical scenarios, fixtures and test oracles are delegated agent choices; independent acceptance remains pending.",
          "choice": "Retain captured raw/effective classification policy and bypass precedence DISABLED > EXPLICIT_PIPELINE > LUCENE_SYNTAX, SIMPLE-only LLM QU, raw retrieval/rerank text and other stage authorities, per-source expansion skip and renderer wording. Preserve baseline labels, including preset dispatch for mixed filenames and quoted paths. Use baseline-INFORMATIONAL questions for optional-budget and per-source schedules; restore the filename NAVIGATIONAL precedence check. No classifier broadening or quality claim.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none in public docs/decisions for expansion keyword-only timeout wording. The old exact expected string AI expansion timed out — keyword results only is deliberately retired because a preserved HYBRID base can be returned. Other existing assertions and UI copy remain."
    }
  ]
}
```

## Acceptance records

Pending independent acceptance of the final candidate.
