---
schema: test-intent.v1
task: t-20261026-query-classification-budget
author-role: builder
author-session: codex-expansion-cutoff-builder-20261027
---

# Test-intent entries

The original optional-budget repair and its preservation checks remain, with the preset inputs
using existing INFORMATIONAL classifier rules. The final cutoff repair additionally bounds the
original expansion completion at submission, even while QU or base retrieval is pending. Queued
work is abandoned at its cutoff before dispatch; running producers retain ownership until exit.
The submission-time cutoff uses the JDK delayed executor and `cancel(true)` to interrupt running
expansion producers. Cutoff cancellation inside the collector wait returns `TIMEOUT` with base
results; caller cancellation still propagates. Timer, collector and final cleanup claim
cancellation once, retaining `cancel(true)` and one producer abandonment. Successful expanded re-search keeps its independent
`min(now + 1500 ms, D)` deadline and base-result fallback.

Historical repair evidence retains fail-before ref b1462152. The new delayed-collection regression
uses 8afa15d777 as its fail-before state. Evidence is refreshed for the final test-source digest.
The controlled-completion checks do not calibrate the fixed reserve for real profiles. Classifier
rules and SIMPLE classification policy are preserved; assistance dispatch changes as described.
No independent acceptance is claimed.

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
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineExpansionBudgetTest.java"
      ],
      "class": "New",
      "behaviour": "E1/E2: while QU or base retrieval is held pending, the original expansion completion times out at its own cutoff before releasing the held stage; queued work never dispatches afterward and running work retains its owner until actual exit. Delayed collection returns base results with TIMEOUT and no re-search. Four durable schedules exercise both held stages and producer states. Cutoff, collector and cleanup claim cancellation once and retain cancel(true) interruption. A deterministic collector-wait race returns TIMEOUT with base results; caller cancellation still propagates. Existing expansion/QU budget and ownership checks remain.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task register accordingly for query classification, consider that you will be the orchestrator for this task. firstly proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task query-classification user turn owner-delegation (quoted verbatim in the final expansion-cutoff builder assignment, 2026-10-27); follow-up: proceed accordingly",
          "delegationScope": "Implement the assigned expansion-completion cutoff repair, its regression checks, and merge-safe budget documentation. Technical fixtures and test oracles are delegated agent choices; independent acceptance remains pending.",
          "choice": "Bound the original expansion future immediately after submission by min(searchStart+1500ms,D-500ms), recomputing remaining time after setup. Arm cancel(true) with the JDK delayed executor: queued work cannot dispatch after cutoff, running producers are interrupted and retain ownership until actual exit. Distinguish cutoff cancellation during the collector wait from caller cancellation, and claim abandonment once across timer, collector and cleanup to avoid duplicate producer callbacks. Preserve explicit collector cancel(true), final cleanup, TIMEOUT/FAILED/AI_UNAVAILABLE reasons, successful independent re-search budget and base fallback, and QU behavior.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/qc-expansion-cutoff-repair.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineExpansionBudgetTest.java#expansionCutoffAbandonsOriginalWhileSearchWaitsElsewhere",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineExpansionBudgetTest.java#cutoffCancellationDuringCollectorWaitReturnsBaseResultsWithTimeout"
      ],
      "repaired": true
    }
  ]
}
```

## Acceptance records

Pending independent acceptance of the final candidate.
