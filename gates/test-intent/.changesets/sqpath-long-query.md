---
schema: test-intent.v1
task: t-20261105-sqpath
author-role: builder
author-session: 01a1b288-fcc0-7141-9214-225fdc2545b7
---

# Test-intent entries

The three adaptations preserve the complete existing oracle. The original files at
`0c0d8786e406f12e4038e1947f89a4c2b3ccc2af` passed 62 checks without skips
(ClassificationDispatch 54, Paging 4, RerankDeadline 4); the adapted files passed
the same 62 checks without skips on the C5 candidate. Both runs used
`js-gradle-slot ./gradlew :modules:app-services:test` with the three named class
filters, `--max-workers=6 --rerun --no-build-cache -PskipWebBuild=true`.
Replacing `.rerankSearch(` with `.rerank(` in each candidate file reproduces its
exact baseline bytes. SHA-256 content signatures for independent review:

| Test class | Baseline SHA-256 | Candidate SHA-256 |
| --- | --- | --- |
| KnowledgeSearchEngineClassificationDispatchTest | `cc5f88c6ac673c8f2a45a18af273ab1bf619cc48667d43944100bfa1c42ab3e0` | `9df57c63708213ccfc74395bb1a73e9cdd325dc494194d082de2b9231c99b9b5` |
| KnowledgeSearchEnginePagingTest | `268cd839453c78ccac437a3fcaf99200f7922d0bd36e585e8532ebb862a2b67b` | `aebcc3e06099c4f1efde397d5bd9d11acf5e5f125cf22720df33ea86a2abbae0` |
| KnowledgeSearchEngineRerankDeadlineTest | `46c0e787a68cd3bb71507c217c46454c3d14367411f079f0c15328df0f1f1575` | `9dc93105179c79db385b6579e83b879c696a3aa504a51d9fec3b37e656cb306e` |

The New H4 evidence runs the whole existing enum test class (seven checks),
because the existing display names replace method names in JUnit XML. The new
expectation is the explicit by-design classification in `dropClassIsDeclared`;
all pre-existing classifications remain unchanged.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineClassificationDispatchTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEnginePagingTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineRerankDeadlineTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "rename",
        "instrumentation"
      ],
      "whatMoved": "Only the head CE mock and negative-interaction target changes from KnowledgeClient.rerank(String,List,long,EngineContext) to search-only rerankSearch(String,List,long,EngineContext). All inputs, preconditions, observation, fault injection, assertions, expected outcomes, tags and conditions remain unchanged. Generic rerank retains default-off behavior. Exact baseline: 0c0d8786e406f12e4038e1947f89a4c2b3ccc2af. Removing the Search suffix from each changed call target reproduces each original test file byte-for-byte."
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/knowledge/CrossEncoderSkipReasonTest.java"
      ],
      "class": "New",
      "behaviour": "QUERY_TOO_LONG is a by-design skip (isDrop=false); every pre-existing reason keeps its classification.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Reconcile the records with actual worktrees, processes and unread results, register yourself as owner, and continue autonomously. Do not wait for the unavailable predecessor.",
          "delegationLocation": "task t-20261105-sqpath user turn takeover-2026-11-06",
          "delegationScope": "Continue the existing approved search-quality path, including adopted #5 search-only long-query guard while preserving generic GPL/RAG/citation behavior.",
          "choice": "Use stable QUERY_TOO_LONG as a by-design skip with isDrop=false and a truthful skipped search trace; retain all older reason classifications. This is bounded by the settled H4 contract.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/sqpath-long-query-reasons.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/knowledge/CrossEncoderSkipReasonTest.java"
      ]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1b296-7b08-7710-8296-b1467b23a7fb",
  "verdict": "accept",
  "digest": "sha256:341821d412d487dca895c3bfff0962bf100d14eb0fc9d8a8ad9766a568703d4f"
}
```
