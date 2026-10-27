---
schema: test-intent.v1
task: t-20261026-query-classification-budget
author-role: builder
author-session: codex-expansion-ownership-builder-20261027
---

# Test-intent entries

The original optional-budget repair and its preservation checks remain. Expansion cancellation now
uses one atomic ownership state: PENDING becomes CUTOFF, COLLECTING or CLOSED. The armed JDK timer
cancels only if it owns CUTOFF. A collector that owns COLLECTING uses its existing timed get and
cancel(true) timeout path; every cancellation observed inside get is foreign and propagates.
Cleanup claims CLOSED when collection never ran, or abandons unfinished collector-owned work.
Queued expansion still cannot dispatch after the cutoff while QU or base retrieval is pending.
Successful expanded re-search retains min(now + 1500 ms, D) and its base-result fallback.

Historical repair evidence retains fail-before ref b1462152. Delayed-collection E1 evidence retains
8afa15d777; E4/E5 ownership evidence fails on c24cb38cb9. Evidence is refreshed for the final test
source. The former cutoffCancellationDuringCollectorWaitReturnsBaseResultsWithTimeout test is
replaced: its assertion that the timer cancels a waiting collector contradicts the new ownership
contract. The controlled-completion checks do not calibrate the fixed reserve for real profiles.
Classifier policy and QU behavior are preserved. No independent acceptance is claimed.

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
      "behaviour": "E1/E2: while QU or base retrieval is held pending, the original expansion completion is cancelled at its own cutoff before releasing the held stage; queued work never dispatches afterward and running work retains its owner until actual exit. Delayed collection returns base results with TIMEOUT and no re-search. Four durable schedules exercise both held stages and producer states. Cutoff ownership retains cancel(true) interruption. Existing expansion/QU budget and ownership checks remain.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task register accordingly for query classification, consider that you will be the orchestrator for this task. firstly proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task query-classification user turn owner-delegation (quoted verbatim in the final expansion-cutoff builder assignment, 2026-10-27); follow-up: proceed accordingly",
          "delegationScope": "Implement the assigned expansion-completion cutoff repair, its regression checks, and merge-safe budget documentation. Technical fixtures and test oracles are delegated agent choices; independent acceptance remains pending.",
          "choice": "Bound the original expansion future immediately after submission by min(searchStart+1500ms,D-500ms), recomputing remaining time after setup. Arm cancel(true) with the JDK delayed executor: queued work cannot dispatch after cutoff, running producers are interrupted and retain ownership until actual exit. Timer and collector acquire exclusive ownership before cancellation or waiting. Preserve TIMEOUT/FAILED/AI_UNAVAILABLE reasons, successful independent re-search budget and base fallback, and QU behavior.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/qc-expansion-cutoff-repair.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineExpansionBudgetTest.java#expansionCutoffAbandonsOriginalWhileSearchWaitsElsewhere"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineExpansionBudgetTest.java"
      ],
      "class": "New",
      "behaviour": "E4/E5: a foreign cancellation after the old timer has checked pending state propagates with the original cancellation retained in its cause chain even when the timer then fires; once the collector owns expansion the timer does nothing and the collector timed get returns TIMEOUT, base results and exactly one cancel(true) abandonment. Controlled timer execution and latches make both schedules deterministic. The removed cutoffCancellationDuringCollectorWaitReturnsBaseResultsWithTimeout asserted timer cancellation inside get, which the ownership contract forbids.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task register accordingly for query classification, consider that you will be the orchestrator for this task. firstly proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task query-classification user turn owner-delegation (quoted verbatim in the final expansion-cutoff builder assignment, 2026-10-27); follow-up: proceed accordingly",
          "delegationScope": "Implement the assigned expansion ownership repair and deterministic E4/E5 regressions. Technical fixtures and test oracles are delegated agent choices; independent acceptance remains pending.",
          "choice": "Replace cancellation attribution with one atomic PENDING to CUTOFF, COLLECTING or CLOSED ownership transition. Only a timer owning CUTOFF cancels; a collector owning COLLECTING times out through its existing timed get and cancel(true). Foreign cancellation always propagates through EngineFutures.rethrowCancellation. Cleanup claims CLOSED only if collection never ran, or abandons still-pending collector-owned work. Replace the old timer-cancels-waiting-collector assertion with independent cancellation and collector timeout oracles.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/qc-expansion-ownership-repair.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineExpansionBudgetTest.java#foreignCancellationAfterTimerCheckStillPropagatesFromCollector",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineExpansionBudgetTest.java#timerLeavesWaitingCollectorToTimeoutAndAbandonExactlyOnce"
      ],
      "repaired": true
    }
  ]
}
```
## Acceptance records

Pending independent acceptance of the final candidate.

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "qc-final-verifier-kuq66jbr-20261027",
  "verdict": "accept",
  "digest": "sha256:269fc59a7e053b6974c39db40d8a96400157ec90bce04663b01b47d18c0e350e",
  "note": "Delegated deadline and owner-lifetime choices are within scope. Independent checks reproduce E1 on 8afa15d777 (five assertion failures), E4/E5 on c24cb38cb9 (four assertion failures), and 99 preservation cases on both. All settled candidate cases pass, including queued/running ownership and real Engine wire smoke. Acceptance covers the stated collector-wait cancellation contract; the pre-existing before-collection TIMEOUT fallback is separately reported. No reserve calibration or native-exit bound is claimed."
}
```
