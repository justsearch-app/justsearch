---
schema: test-intent.v1
task: t-20261023-ee557f
author-role: builder
author-session: 01a174a4-77c8-77c3-b58a-887c115aab14
---

# Deferred CPU backup test intent

Exact baseline: f8e16e326bf30acc885eebcca0064c0e0d91aaff. K2-K15 are orchestrator choices.
Owner quotations supplied in the brief: "Work autonomously; don't ask me for verdicts on technical work." and "you are working on all of the mentioned remainng work right?"
Prior independent acceptance covered the earlier K2 golden update. The COLD1/K3 expectation adaptation changes author intent and awaits renewed root review. New isolated checks are not presented as accepted.

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
    },
    {
      "items": [
        "modules/ort-common/src/test/resources/policy-snapshot.json"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; don't ask me for verdicts on technical work.",
          "delegationLocation": "task t-20261023-ee557f request",
          "delegationScope": "Queued Q1 K2 defers actual CUDA SPLADE/reranker CPU backups. This narrow continuation authorizes only their two golden lifecycle booleans and truthful intent coverage, with no production, assertion, other field/layout, test-selection or caller changes. The policy choice is the orchestrator's technical decision, not owner-adopted scenarios.",
          "choice": "Update only models.SPLADE.lifecycle.deferCpuSession and models.RERANKER.lifecycle.deferCpuSession from false to true in policy-snapshot.json, matching the explicitly authorized K2 CUDA deferral contract. Preserve the byte-equality assertion and every other golden field and byte layout.",
          "label": "agent choice",
          "provenance": "Owner words relayed verbatim in this builder brief; original owner session not read. Actual author session is 01a174a4-77c8-77c3-b58a-887c115aab14."
        }
      ],
      "conflictSearch": "Decision records searched for a conflict: the supplied continuation explicitly supersedes only the two old false lifecycle values under K2. Read PolicySnapshotSerializationTest.matchesGoldenFixture, its FP16/CUDA fixture setup and the existing K2 delegated-choice entries. This is an intentional expectation adaptation, not old-contract preservation and not the gate's oracle-preserving Adaptation class. The old false expectations are superseded; both become true. No blanket regeneration: all other JSON fields, bytes, serialization equality assertions and test selection remain intact. Independent acceptance of the earlier digest is removed as stale; root review must supply fresh acceptance."
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerRerankSessionCreationTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; don't ask me for verdicts on technical work.",
          "delegationLocation": "task t-20261023-ee557f request",
          "delegationScope": "The orchestrator explicitly settled COLD1-COLD3 after source comparison showed the original cold fixture was itself an allowed K3 deferred CPU-demand owner. This is a delegated technical contract decision, not owner adoption. Production and all old ready-CPU, citation, no-query-GPU-create and budget controls must remain unchanged.",
          "choice": "K3 and COLD1/COLD2: admitted rerank may prepare an explicitly deferred CPU backup while GPU query creation/retry remains forbidden. Restrict the legacy cold MODEL_LOADING/no-creation assertion to a genuinely non-deferred owner; add a separate real-ORT public-worker first-use/reuse check for the intentionally permitted deferred CPU-demand route.",
          "label": "agent choice",
          "provenance": "Owner words relayed verbatim in this builder brief; original owner session not read. Actual author session is 01a174a4-77c8-77c3-b58a-887c115aab14."
        }
      ],
      "conflictSearch": "Read the original b87-ix-search no-session-creation entry, the actual failed CI XML supplied in the brief, WorkerSearchService.rerank, CrossEncoderReranker admitted overload and NativeSessionHandle.cpuSessionFor. The original explicit deferred reranker owner now deliberately permits bounded CPU preparation under K3, so its unconditional all-session-no-creation expectation is obsolete for that route. COLD1 explicitly uses non-deferred policy and models no ready CPU after eager bootstrap, retaining ownership for close; constructor interactions are verified and cleared before the unchanged query no-interactions oracle. The query still skips MODEL_LOADING. Ready-CPU/citation/short-budget assertions remain. COLD2 independently proves actual CPU output, single creation generation, reuse and forbidden GPU retry using real ORT and the public service. Main a5ff is the deliberate before-policy comparison, not a new defect pinned to changed production. Earlier claims this exact class passed44dc/534 were unsubstantiated: admitted selections contained no XML for it. No owner adoption, source-preserving adaptation, self-acceptance or hardware equivalence is claimed."
    }
  ]
}
```

## Acceptance records

Pending renewed independent root review. The COLD1/K3 expectation adaptation changes this
changeset's digest; the prior acceptance is stale and removed. The author cannot self-accept.

```json
{
  "kind": "acceptance",
  "role": "orchestrator",
  "session": "01a172e0-9080-7942-b8b0-0837c22cfb1e",
  "verdict": "accept",
  "digest": "sha256:00cc74e04ba350ef3b80a5e840e355314c5fe47bd3dcf355c6b63a09d1b8fd5d",
  "note": "Renewed non-author review of K2 and COLD1-COLD3: explicit deferred CPU demand may prepare while query GPU creation/retry remains forbidden. The old unconditional cold oracle is deliberately obsolete for that route. Nondeferred loading/no-query-native-create is preserved with verified eager bootstrap ownership; a separate real ORT public-worker test fails main with MODEL_LOADING and passes current with finite scores, one creation and reuse. Existing ready CPU, citation, budgets and retirement controls retained. All production files remain92b; no owner adoption or unexecuted hardware claim. Earlier unsupported same-class pass assumption corrected and original CI failure retained. Independent exact-candidate verification remains required."
}
```
