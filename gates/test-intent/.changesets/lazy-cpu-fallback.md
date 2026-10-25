---
schema: test-intent.v1
task: t-20261023-ee557f
author-role: builder
author-session: 01a174a4-77c8-77c3-b58a-887c115aab14
---

# Deferred CPU backup test intent

Exact baseline: f8e16e326bf30acc885eebcca0064c0e0d91aaff. K2-K15 are orchestrator choices.
Owner quotations supplied in the brief: "Work autonomously; don't ask me for verdicts on technical work." and "you are working on all of the mentioned remainng work right?"
No independent acceptance has been supplied. New isolated checks are not presented as accepted.

```json
{
  "entries": [
    {
      "items": [
        "modules/ort-common/src/test/java/io/justsearch/ort/ModelSessionPolicyResolverTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; don't ask me for verdicts on technical work.",
          "delegationLocation": "task t-20261023-ee557f request",
          "delegationScope": "Queued Q1 outcome taken over for cold-boot CPU-backup peak. The orchestrator supplied K1-K15 and the bounded design; these are agent technical choices, not owner-adopted scenarios. Builder implements only this bounded assignment and cannot self-accept.",
          "choice": "K2: defer actual resolved CUDA SPLADE/reranker CPU backups and omit unconditional post-ready CPU warming; preserve serving-device warmup, effective CPU eager behavior, GTE backup warming, NER and citation.",
          "label": "agent choice",
          "provenance": "Owner words relayed verbatim in this builder brief; original owner session not read. Actual author session is 01a174a4-77c8-77c3-b58a-887c115aab14."
        }
      ],
      "conflictSearch": "The supplied K2 design explicitly supersedes the former CUDA SPLADE eager-backup assertion. Searched the resolver tests, query creation tests and request contract; GPU no-query-create and non-deferred failed-CPU rejection stay preserved. Only SPLADE CUDA deferral expectation changes; existing retry, arena, identity and precision assertions remain. The replacement expectation and effective-CPU counterexample are independently executable in NativeSessionHandleDeferredCpuTest.onlyActuallyPlacedCudaBackupsAreDeferred. No owner adoption or independent acceptance is claimed."
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/QueryEncoderPrewarmCpuWarmTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; don't ask me for verdicts on technical work.",
          "delegationLocation": "task t-20261023-ee557f request",
          "delegationScope": "Queued Q1 outcome taken over for cold-boot CPU-backup peak. The orchestrator supplied K1-K15 and the bounded design; these are agent technical choices, not owner-adopted scenarios. Builder implements only this bounded assignment and cannot self-accept.",
          "choice": "K2: defer actual resolved CUDA SPLADE/reranker CPU backups and omit unconditional post-ready CPU warming; preserve serving-device warmup, effective CPU eager behavior, GTE backup warming, NER and citation.",
          "label": "agent choice",
          "provenance": "Owner words relayed verbatim in this builder brief; original owner session not read. Actual author session is 01a174a4-77c8-77c3-b58a-887c115aab14."
        }
      ],
      "conflictSearch": "K2 replaces the old all-GPU-roles backup-warming obligation. The old SPLADE CPU-warm-failure input is now BGE_M3 GPU; the already-CPU input moves to citation to keep roles distinct. Readiness-before-backup, unchanged GTE callback authority, failed warmup isolation and no duplicate CPU-primary warming checks remain. The new QueryEncoderPrewarmDeferredCpuTest pins absent SPLADE/reranker backup callbacks on repeated ready passes. This is an intentional supersession of the old SPLADE callback expectation, not a claim of input-preserving adaptation. Searched QueryEncoderPrewarm, KnowledgeServer callback wiring and search-and-rag-reason-codes; no conflicting owner decision was supplied."
    }
  ]
}
```

## Acceptance records

Pending independent verification on the committed candidate. The builder cannot commit or accept.

```json
{
  "kind": "acceptance",
  "role": "orchestrator",
  "session": "01a172e0-9080-7942-b8b0-0837c22cfb1e",
  "verdict": "accept",
  "digest": "sha256:174d488540858c5b580fedc4145e0fee179d8eaf849126f0372b580a1a142e1a",
  "note": "Non-author review of K1-K15 challenged technical contract and production/request/consumer changes. Effective CUDA-only CPU deferral intentionally replaces old eager SPLADE and all-role backup warming expectations; unchanged CPU-primary/GTE/NER/citation and readiness policy remain. New first-demand CPU permission never permits query GPU creation/retry or larger deadline, uses existing bounded native threads and exact owner accounting. Tiny native/synthetic author evidence inspected; independent committed verification and real selected-model cold/overlap/Engine proof still required before delivery. No owner scenario adoption or hardware pass inferred."
}
```
