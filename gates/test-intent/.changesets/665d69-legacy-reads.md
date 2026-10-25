---
schema: test-intent.v1
task: t-20261025-665d69
author-role: builder
author-session: 01a174a9-dd7d-77e2-be5b-a6fa8bc27300
---

# Retained legacy chunk reads

These are agent technical choices under the quoted delegation, not owner-adopted scenario labels. Stage A evidence remains unchanged. Legacy rows remain v1; omission and metadata/status/rename RMW do not regenerate or repair their offsets. Comparable-shape rebuild/manual reindex remains the actual repair path; unknown/disabled metadata is not universally healed. Arbitrary trusted adapter chunk-content writes remain outside the canonical guarantee. Existing absent-parent/range/revision RMW refusal, rename limits and partial-batch policy remain limitations.

The TSV fixture was emitted by the unchanged committed-base splitter f8e16e326bf30acc885eebcca0064c0e0d91aaff from the test's well-formed parent (anchor plus 3000 emoji, with another anchor between repetitions). Its rows include safe siblings plus an unsafe end and unsafe start. The capture launcher and raw output are retained in the builder scratch directory. Evidence uses the prior disclosed scratch launcher adaptation for shared-clone before states and js-gradle-slot, with no source-repository launcher change. No author acceptance is supplied.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentRevisionRaceTest.java",
        "modules/worker-services/src/test/resources/unicode/v1-chunks.tsv"
      ],
      "class": "New",
      "behaviour": "Real Lucene legacy rows from unchanged v1 splitter geometry are omitted from single/batch/enrichment/projection/RAG text while safe siblings and parent reads remain. Standalone chunk embedding, combined enrichment, chunk-SPLADE and BGE-M3 never encode unsafe text; valid-proof failures reach retry3/FAILED and safe tails beyond batchSize complete. Separately, a geometry or parent-revision mutation after proof capture rejects conditional publication, leaves stored retry/status unchanged and claims no durable advancement; existing standalone failed/activity counters are not claimed durably accurate. Existing tests retain their input/oracles; their helper adds an opt-in legacy fixture mode, batchSize=1 and encoder input observation only for these new cases.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; don't ask me for verdicts on technical work.",
          "delegationLocation": "task t-20261025-665d69 request",
          "delegationScope": "Technical choices within the Unicode repair. The later bounded builder assignment settles legacy read omission while explicitly retaining unchanged matching-proof WritePathOps reconstruction and existing retry/conditional-publication behavior; it supersedes the earlier deferred legacy restriction and investigated refusal/cursor alternatives.",
          "choice": "Omit pair-bisecting legacy slices only at the existing central read seam. Preserve exact old substring reconstruction for matching proof and unchanged geometry so old postings/dense vectors survive status/retry/rename RMW. Use existing retry3->FAILED and proof checks to drain bad heads; no cursor, scheduler, RMW refusal, new state or universal regeneration claim.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/665d69-legacy-read-repair.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentRevisionRaceTest.java#legacyReadOmissionPreservesSafeSiblingsAndSearchableArtifacts",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentRevisionRaceTest.java#legacyFailuresDrainBadHeadsWithoutEncodingTheirText",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentRevisionRaceTest.java#legacyProofRaceCannotPublishFailure"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentRevisionRaceTest.java"
      ],
      "class": "New",
      "behaviour": "Preservation check: commit/reopen compares actual chunk-content terms, frequencies, positions and norms where available, and exact dense vector values across matching-proof retries/status updates and unchanged-geometry family rename. Existing reset-status policy drops omitted SPLADE weights and resets completed->pending explicitly. Retry3/FAILED remains durable. This check must pass on the unchanged before state too; it does not require the new read omission.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; don't ask me for verdicts on technical work.",
          "delegationLocation": "task t-20261025-665d69 request",
          "delegationScope": "Technical choices within the Unicode repair. The later bounded builder assignment settles legacy read omission while explicitly retaining unchanged matching-proof WritePathOps reconstruction and existing retry/conditional-publication behavior; it supersedes the earlier deferred legacy restriction and investigated refusal/cursor alternatives.",
          "choice": "Omit pair-bisecting legacy slices only at the existing central read seam. Preserve exact old substring reconstruction for matching proof and unchanged geometry so old postings/dense vectors survive status/retry/rename RMW. Use existing retry3->FAILED and proof checks to drain bad heads; no cursor, scheduler, RMW refusal, new state or universal regeneration claim.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/665d69-legacy-rmw-preservation.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentRevisionRaceTest.java#legacyMatchingProofRmwAndRenamePreservePostingsAndDenseVector"
      ]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "orchestrator",
  "session": "01a172e0-9080-7942-b8b0-0837c22cfb1e",
  "verdict": "accept",
  "digest": "sha256:a5d638efb408069b56e5de4b56d8aa520a783dad7d2d0f23f3385128effc255b",
  "note": "Non-author reviewed settled/challenged canonicalA1-A6 and legacyL1-L5, minimum4productionseams and durabletests. Pair-safe splitter+v2 fingerprint, whole-plan prepublication/predelete refusal, central unsafe-text read omission only; unchanged faithful matching-proof RMW/selectors/scheduler. New red checks have actual f8 assertion-failure evidence; same-analyzer Lucene postings/norms/dense preservation passes before/after, SPLADE reset exception explicit. Existing boot fixture helper instrumentation preserves alloldcallerinputs/oracles. Author160stageA plus40legacy/80adapter/12canonical evidence inspected; no selfacceptance or owner scenario adoption. Independent committed verification, full Green rebuild/promotion and remaining hardware proof are not inferred. Every new check kept as durable boundary/recovery/preservation regression coverage."
}
```
