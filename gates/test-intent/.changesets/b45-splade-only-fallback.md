---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b45-splade-only-20261009
---

# SPLADE-only skip fallback

The existing tests and expectations are unchanged. New checks enter the real worker with a
nonblank SPLADE-only request; HTTP and MCP checks also enter the production preset mapper.
Only native encoders or the client transport are replaced. No Engine or parser subprocess boots.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSpladeOnlyFallbackTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/SpladeOnlyBoundaryFallbackTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "SPLADE-only queued semantic expiry returns keyword hits, SEMANTIC_BUDGET_EXCEEDED/contended, no encoder call and no models wait, for bound and starting encoders, deprecated worker mode, bare HTTP mode and bare MCP mode. MCP names the SPLADE budget miss and cause in its degradation note. Timely loading retains loading; null encoders and encoding failures retain the existing NO_EMBEDDING_SERVICE and EMBEDDING_GENERATION_FAILED producers with no false budget cause, while returning keyword hits. Each of these nine checks fails by assertion on 9fde46683 for the lost reason or note and passes on the candidate.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "a search that starts after its semantic deadline has passed in the queue takes the existing Spec D outcome at once without running inference: hybrid or SPLADE searches answer keyword-only with SEMANTIC_BUDGET_EXCEEDED and cause contended",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 agreement'; the text does not appear in the owner's turns of session 886833dd"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "If the existing behaviour for other SPLADE-only skips also drops results and reason, show it and fix them by the same rule only where the existing reason producers already define the outcome; do not invent new reason codes.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request'; the text does not appear in the owner's turns of session 886833dd"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "with the existing producers and fields (splade_skip_reason / splade_skip_cause, retrieval_mode_reason, McpToolSurface degradation note): keyword-only results with SEMANTIC_BUDGET_EXCEEDED and cause contended, and no encoder call.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b45-repaired.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSpladeOnlyFallbackTest.java#queuedSpladeOnlyWithBoundEncoderReturnsKeywordsAndContendedReason",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSpladeOnlyFallbackTest.java#queuedSpladeOnlyWithStartingEncoderReturnsKeywordsWithoutWaiting",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSpladeOnlyFallbackTest.java#queuedDeprecatedSpladeModeReturnsKeywordsAndContendedReason",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSpladeOnlyFallbackTest.java#timelySpladeOnlyLoadingReturnsKeywordsAndLoadingReason",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSpladeOnlyFallbackTest.java#absentSpladeOnlyReturnsKeywordsWithItsExistingNoServiceReason",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSpladeOnlyFallbackTest.java#queuedPermanentlyAbsentSpladeRetainsNoServiceReasonWithoutFalseBudgetCause",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSpladeOnlyFallbackTest.java#unavailableSpladeEncoderReturnsKeywordsWithItsExistingGenerationFailure",
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/SpladeOnlyBoundaryFallbackTest.java#bareHttpSpladeModeReturnsKeywordHitsWithBudgetAndContention",
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/SpladeOnlyBoundaryFallbackTest.java#bareMcpSpladeModeReturnsKeywordHitsAndDegradationNote"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSpladeOnlyFallbackTest.java"
      ],
      "class": "New",
      "behaviour": "Preservation controls pass before and after: sparse plus SPLADE queued expiry, successful SPLADE encoding, successful empty weights, and blank query. Successful SPLADE retains the existing TEXT effective-mode label, the SPLADE-only plan and no sparse retrieval. Empty weights do not add keyword fallback. No existing assertion or fixture is changed.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Preserve: SPLADE plus sparse, hybrid, AUTO, VECTOR typed 504, RAG, timely-seat loading cause, a permanently absent SPLADE model, and SPLADE searches whose deadline has not expired.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b45-preservation.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSpladeOnlyFallbackTest.java#sparsePlusSpladePreservesQueuedFallback",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSpladeOnlyFallbackTest.java#timelySpladeOnlyStillRunsOnlySplade",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSpladeOnlyFallbackTest.java#successfulEmptySpladeWeightsDoNotBecomeAKeywordFallback",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSpladeOnlyFallbackTest.java#blankQueryStillHasTheEmptyQueryDecision"
      ]
    }
  ]
}
```

## Acceptance records

Independent verifier acceptance:

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-splade-20261009",
  "verdict": "accept",
  "digest": "sha256:7b75bf129995a60941bf87faceeccd488b7119390ae69392f607a398f7d809b6",
  "note": "Test-intent acceptance only. The exact candidate sources compiled on 9fde46683 through an external init script, with nine intended assertion failures and four preservation passes; all thirteen pass on 681ee1da2. OwnSpladeOnlyExpiryProbe independently fails before and passes after, and its sparse control passes both. An independent actual-postings probe preserves successful SPLADE retrieval and successful empty weights on both and repairs the nonmatching fallback's lost reason after. Product/test files were not edited. Three pre-existing full UI failures, shared governance failures, excluded unsafe cleanup and the initial full UI process-audit gap remain separate from this acceptance. Evidence: F:/agent-sandbox/design-b6d536/codex/verify-splade/report.md, focused-summary.json and before/candidate-own-results.log."
}
```
