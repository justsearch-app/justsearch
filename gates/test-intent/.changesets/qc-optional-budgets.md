---
schema: test-intent.v1
task: t-20261026-query-classification-budget
author-role: builder
author-session: codex-rescope-builder-20261026
---

# Test-intent entries

These tests and production budget behavior were already present on the rescope baseline d036a07a9b9.
The rescope preserves the original optional-budget and cancellation assertions. Only preset inputs
that depended on rejected classifier rules are replaced with baseline-INFORMATIONAL questions.
The mixed-filename NAVIGATIONAL reason-precedence test is restored, alongside the additional quoted
EXACT_MATCH control. This is preservation of the prior budget repair, not a new budget change.

Evidence files are refreshed for the changed test-source digest. Their historical fail-before ref
b1462152 remains the pre-budget implementation, not the rescope baseline. The provisional reserve
is not calibrated by these controlled-completion tests. No independent acceptance is claimed.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/QueryUnderstandingServiceTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineExpansionBudgetTest.java"
      ],
      "class": "New",
      "behaviour": "Historical budget repair preserved by R4/R6: skip unconsumed per-source expansion with PER_SOURCE_RETRIEVAL, bound original QU completion and forward projection cancellation, apply absolute Head completion budgets before/after setup, keep queued work from dispatching after abandonment, retain running work until one actual-exit release, abandon pending expansion on base failure/interruption, and propagate cancellation/interruption/refusal rather than ordinary fallback. All tests are durable.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task register accordingly for query classification, consider that you will be the orchestrator for this task. firstly proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task query-classification user turn owner-delegation (quoted verbatim in the rescope builder assignment, 2026-10-26); follow-up: proceed accordingly",
          "delegationScope": "Investigate, design and implement query-classification improvements autonomously within the assigned baseline-preservation and policy/diagnostics scope. Technical scenarios, fixtures and test oracles are delegated agent choices; independent acceptance remains pending.",
          "choice": "Preserve the optional-assistance budget and abandonment behavior already present at d036a07a9b9. QU remains bounded by min(start+8000ms,D-1500ms provisional base reserve-500ms response margin); expansion completion by min(searchStart+1500ms,D-500ms), with existing cancellation and successful re-search ownership. The 1500ms reserve remains uncalibrated for real profiles. Restore the mixed-filename NAVIGATIONAL precedence oracle; retain the additional quoted EXACT_MATCH control.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/qc-optional-budgets-repair.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/QueryUnderstandingServiceTest.java#cancellingProjectionAbandonsOriginalQueuedCompletionBeforeItCanDispatch",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/QueryUnderstandingServiceTest.java#cancellingProjectionRetainsRunningCompletionUntilActualExit",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/QueryUnderstandingServiceTest.java#originalCallerCancellationIsNotAnOrdinaryExtractionFailure",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineExpansionBudgetTest.java#stalledQuLeavesReservedTimeForResponsiveBaseAndAbandonsQueuedRoot",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineExpansionBudgetTest.java#stalledQuReturnsBaseWhileRunningProducerRetainsWorkUntilExit",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineExpansionBudgetTest.java#stalledExpansionReturnsResponsiveBaseInsideShortCallerDeadline",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineExpansionBudgetTest.java#stalledExpansionRetainsRunningProducerUntilActualExit",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineExpansionBudgetTest.java#spentCallerBudgetDoesNotSubmitExpansion",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineExpansionBudgetTest.java#quWithNoPositiveAssistBudgetDoesNotSubmit",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineExpansionBudgetTest.java#quRootTimeoutUsesOriginalCallerMinusBaseReserveAndResponseMargin",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineExpansionBudgetTest.java#quRecomputesRemainingBudgetAfterSynchronousSubmission",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineExpansionBudgetTest.java#expansionRecomputesWaitAfterSynchronousSubmissionSpendsItsCutoff",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineExpansionBudgetTest.java#expansionCannotLaunchAfterItsAssistCutoffEvenBeforeCallerDeadline",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineExpansionBudgetTest.java#baseRetrievalFailureAbandonsPendingExpansionBeforeItCanDispatch",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineExpansionBudgetTest.java#interruptedExpansionCollectorAbandonsRootAndPreservesInterruption",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineExpansionBudgetTest.java#interruptedQuCollectorAbandonsOriginalQueuedRoot",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineExpansionBudgetTest.java#perSourceTextRetrievalDoesNotLaunchUnconsumedExpansion",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineExpansionBudgetTest.java#optionalCallerTerminationAndExecutorRefusalNeverBecomeOrdinaryFallback"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/QueryUnderstandingServiceTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineExpansionBudgetTest.java"
      ],
      "class": "New",
      "behaviour": "R6 preservation: no-Head QU retains its 8000ms root bound and successful boost projection; executor refusal identity survives; cursor and whole-quoted exact-match reasons keep precedence; a fast expansion can use the existing independent D-bound re-search beyond the completion cutoff, retaining base fallback. Original budget/cancellation assertions remain; the mixed filename NAVIGATIONAL control is restored and the additional quoted EXACT_MATCH control remains. All added tests are durable.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task register accordingly for query classification, consider that you will be the orchestrator for this task. firstly proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task query-classification user turn owner-delegation (quoted verbatim in the rescope builder assignment, 2026-10-26); follow-up: proceed accordingly",
          "delegationScope": "Investigate, design and implement query-classification improvements autonomously within the assigned baseline-preservation and policy/diagnostics scope. Technical scenarios, fixtures and test oracles are delegated agent choices; independent acceptance remains pending.",
          "choice": "Preserve the optional-assistance budget and abandonment behavior already present at d036a07a9b9. QU remains bounded by min(start+8000ms,D-1500ms provisional base reserve-500ms response margin); expansion completion by min(searchStart+1500ms,D-500ms), with existing cancellation and successful re-search ownership. The 1500ms reserve remains uncalibrated for real profiles. Restore the mixed-filename NAVIGATIONAL precedence oracle; retain the additional quoted EXACT_MATCH control.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/qc-optional-budgets-preservation.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/QueryUnderstandingServiceTest.java#executorRefusalKeepsItsIdentityThroughProjection",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/QueryUnderstandingServiceTest.java#noHeadEntrypointKeepsEightSecondRootBudgetAndSuccessfulProjection",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineExpansionBudgetTest.java#perSourceCursorKeepsPaginationReasonPrecedence",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineExpansionBudgetTest.java#perSourceQuotedExactKeepsQueryTypeReasonPrecedence",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineExpansionBudgetTest.java#fastCompletionMayUseExistingReSearchBudgetBeyondCompletionCutoffWithBaseFallback"
      ]
    }
  ]
}
```

## Acceptance records

Pending independent acceptance of the final candidate.
