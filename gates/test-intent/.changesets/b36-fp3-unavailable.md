---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b36-fp3-unavailable-20261008
---

# FP3 transient provider readiness

Existing checks and intent records are unchanged.

Evidence regenerated on b599d4d7e. The check file now uses the later provider-availability API, so
the fail-before on c8c7e1c58 applies `gates/test-intent/evidence/b36-fp3-unavailable-compat.patch`.
It adds only declarations: `EmbeddingProvider.Availability` (its state enum and the READY, STARTING
and ABSENT constants), a default `availability()` that returns null, and
`NoOpEmbeddingProvider.starting()`, which returns the existing `INSTANCE`. No production code at
c8c7e1c58 reads any of them. The nine repaired checks fail there by assertion; the seven others pass.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedUnavailableTest.java"
      ],
      "class": "New",
      "behaviour": "Real WorkerSearchService and temporary Lucene index, compatible CPU provider unavailable only until the closed latch opens, 20-second whole deadline and 8.5-second pre-worker queue time: HYBRID, AUTO and RAG retain SEMANTIC_BUDGET_EXCEEDED/contended, TEXT hits or BM25 chunks, with zero encoder or model-await calls. Queued VECTOR (2000 ms whole deadline, 1600 ms queue) retains typed DEADLINE_EXCEEDED with the same reason/cause and no wait or inference. Each fails by assertion on c8c7e1c58 and passes after. Additional cold-publication checks use the real compatibility controller with a matching installed fingerprint and the null-object provider/empty encoder bindings used before publication: HYBRID, AUTO, SPLADE, RAG and VECTOR retain the budget outcome with zero model waits. Each fails by assertion before.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "when the budget ran out in the queue, FP3's outcome (keyword-only, SEMANTIC_BUDGET_EXCEEDED, cause contended) applies whatever the provider's transient state, for HYBRID, SPLADE, AUTO and RAG; VECTOR-only keeps its typed 504 with SEMANTIC_BUDGET_EXCEEDED.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b36-fp3-unavailable.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedUnavailableTest.java#queuedHybridWithUnavailableProviderKeepsBudgetOutcome",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedUnavailableTest.java#queuedAutoWithUnavailableProviderKeepsBudgetOutcome",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedUnavailableTest.java#queuedRagWithUnavailableProviderKeepsBudgetOutcome",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedUnavailableTest.java#queuedUnavailableVectorKeepsTypedDeadline",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedUnavailableTest.java#queuedColdPlaceholderHybridKeepsBudgetOutcome",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedUnavailableTest.java#queuedColdPlaceholderAutoKeepsBudgetOutcome",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedUnavailableTest.java#queuedColdPlaceholderSpladeKeepsBudgetOutcome",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedUnavailableTest.java#queuedColdPlaceholderRagKeepsBudgetOutcome",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedUnavailableTest.java#queuedColdPlaceholderVectorKeepsTypedDeadline"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedUnavailableTest.java"
      ],
      "class": "New",
      "behaviour": "Preservation on both versions: bound SPLADE encoder with unavailable dense provider retains semantic skip/contended and keyword hits without encoding; timely HYBRID/AUTO wait once then report loading, and provider becomes available upon latch release; queued ready HYBRID/AUTO keep contended; permanent null-object provider keeps HYBRID NO_EMBEDDING_SERVICE/empty cause, AUTO no fallback, RAG NO_EMBEDDING_SERVICE/empty cause; unexpired unavailable provider with ready models keeps current HYBRID/AUTO routing. A real controller with no installed model fingerprint preserves NO_EMBEDDING_MODEL for HYBRID/RAG, no AUTO fallback and the existing absent-SPLADE reason.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "when the budget ran out in the queue, FP3's outcome (keyword-only, SEMANTIC_BUDGET_EXCEEDED, cause contended) applies whatever the provider's transient state, for HYBRID, SPLADE, AUTO and RAG; VECTOR-only keeps its typed 504 with SEMANTIC_BUDGET_EXCEEDED.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request'; the text does not appear in the owner's turns of session 886833dd"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Keep existing behavior where FP3 does not apply: a provider that is permanently absent (no embedding model configured or installed) keeps its existing outcome; a request that reaches its seat in time and then waits for loading models keeps cause loading; a request that is not expired keeps today's routing.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b36-fp3-unavailable.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedUnavailableTest.java#queuedSpladeWithUnavailableEmbeddingProviderKeepsBudgetOutcome",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedUnavailableTest.java#timelyHybridWithUnavailableProviderKeepsLoadingCause",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedUnavailableTest.java#timelyAutoWithUnavailableProviderKeepsLoadingCause",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedUnavailableTest.java#queuedReadyProviderKeepsContention",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedUnavailableTest.java#permanentlyAbsentProviderKeepsExistingOutcomes",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedUnavailableTest.java#unexpiredUnavailableProviderKeepsExistingRouting",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedUnavailableTest.java#queuedPermanentlyMissingModelKeepsCompatibilityOutcome"
      ]
    }
  ]
}
```

## Acceptance records

Pending independent verifier acceptance.

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS1-20261015",
  "verdict": "accept",
  "digest": "sha256:437e26b5327ce4ea1409d623d8c57a67309a88eb8040a924fe278cf49e2d57d6",
  "note": "Re-reviewed unavailable-provider routing, source attribution and the add-only compatibility declarations; candidate checks and the c8c7e1c58 replay match the recorded assertion failures and preservation outcomes."
}
```
