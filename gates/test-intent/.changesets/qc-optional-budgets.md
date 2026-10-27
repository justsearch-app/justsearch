---
schema: test-intent.v1
task: t-20261026-query-classification-budget
author-role: builder
author-session: codex-builder-20261026T150105Z
---

# Test-intent entries

Unaccepted builder checkpoint. Owner delegation is quoted separately from agent technical choices.
Existing tests are preserved. Every added check is durable; temporary producer controls and the
shared before clone are execution scaffolding, not product options or quality/calibration evidence.

Official evidence is produced by the unchanged scoped runner. Its child-only HOME/profile
isolation and automatically managed before worktree are expressly authorized for this evidence.
Earlier raw command/output/JUnit XML and snapshots remain under `tmp/qc-builder-budget/evidence`.
The expansion cancellation check asserts CancellationException and the original cause in its chain;
it does not require the JDK's propagated exception to be the same object. No independent acceptance
is claimed.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/QueryUnderstandingServiceTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineExpansionBudgetTest.java"
      ],
      "class": "New",
      "behaviour": "Q7/Q8: skip unconsumed per-source expansion with PER_SOURCE_RETRIEVAL, bound original QU completion and forward projection cancellation, apply absolute Head completion budgets before/after setup, keep queued work from dispatching after abandonment, retain running work until one actual-exit release, abandon pending expansion on base failure/interruption, and propagate cancellation/interruption/refusal rather than ordinary fallback. All tests are durable.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly, only use codex agents",
          "delegationLocation": "task query-classification user turn (owner delegation quoted verbatim in builder assignment, 2026-10-26)",
          "delegationScope": "Proceed with the settled query-classification outcome using only Codex agents; this first local slice repairs existing optional assistance budgets/abandonment before any classifier broadening. No publication/global changes; separate author and checker. Scenarios and numeric budget policy are agent-settled technical choices, not owner-authored scenario words.",
          "choice": "Implement the settled Q7/Q8 deadline and abandonment design in the two existing services, and preserve P3/P4/P5 stage/caller/config authority. QU ends by min(start+8000ms,D-1500ms provisional base reserve-500ms response margin); expansion completion ends by min(searchStart+1500ms,D-500ms), preserving cancel(true), per-source skip precedence, and the independent successful re-search budget. The 1500ms base reserve remains uncalibrated for real profiles.",
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
      "behaviour": "P3/P4/P5 and Q8 preservation: no-Head QU retains its 8000ms root bound and successful boost projection; executor refusal identity survives; cursor and filename reasons keep precedence; a fast expansion can use the existing independent D-bound re-search beyond the completion cutoff, retaining base fallback. Existing tests remain unchanged. All added tests are durable.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly, only use codex agents",
          "delegationLocation": "task query-classification user turn (owner delegation quoted verbatim in builder assignment, 2026-10-26)",
          "delegationScope": "Proceed with the settled query-classification outcome using only Codex agents; this first local slice repairs existing optional assistance budgets/abandonment before any classifier broadening. No publication/global changes; separate author and checker. Scenarios and numeric budget policy are agent-settled technical choices, not owner-authored scenario words.",
          "choice": "Implement the settled Q7/Q8 deadline and abandonment design in the two existing services, and preserve P3/P4/P5 stage/caller/config authority. QU ends by min(start+8000ms,D-1500ms provisional base reserve-500ms response margin); expansion completion ends by min(searchStart+1500ms,D-500ms), preserving cancel(true), per-source skip precedence, and the independent successful re-search budget. The 1500ms base reserve remains uncalibrated for real profiles.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/qc-optional-budgets-preservation.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/QueryUnderstandingServiceTest.java#executorRefusalKeepsItsIdentityThroughProjection",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/QueryUnderstandingServiceTest.java#noHeadEntrypointKeepsEightSecondRootBudgetAndSuccessfulProjection",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineExpansionBudgetTest.java#perSourceCursorKeepsPaginationReasonPrecedence",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineExpansionBudgetTest.java#perSourceFilenameKeepsNavigationalReasonPrecedence",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineExpansionBudgetTest.java#fastCompletionMayUseExistingReSearchBudgetBeyondCompletionCutoffWithBaseFallback"
      ]
    }
  ]
}
```

## Acceptance records
