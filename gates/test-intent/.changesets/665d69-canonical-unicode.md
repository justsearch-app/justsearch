---
schema: test-intent.v1
task: t-20261025-665d69
author-role: builder
author-session: 01a174a9-dd7d-77e2-be5b-a6fa8bc27300
---

# Canonical Unicode chunk stage

These are agent technical choices under the quoted owner delegation, not owner-adopted scenario labels or owner words taken from the orchestrator brief. This stage does not complete the separate legacy safeguards. No author acceptance is supplied.

The evidence generator ran in an isolated shared clone containing byte-identical candidate production/tests. Its temporary launcher substitutes shared clones for before-worktrees (the builder contract prohibits source-repository worktree mutations), removes only those temporary clones, and routes every Gradle invocation through js-gradle-slot. The generator itself writes the evidence, including its actual script digest; no evidence JSON is hand-edited. The launcher patch and initial failed attempt logs are retained in the assignment's scratch directory for review. Preservation checks also passed in the unchanged f8e16e326bf30acc885eebcca0064c0e0d91aaff shared clone; repaired checks require assertion failures there.

```json
{
  "entries": [
    {
      "items": [
        "modules/indexing/src/test/java/io/justsearch/indexing/chunking/ChunkSplitterCoreTest.java"
      ],
      "class": "New",
      "behaviour": "Both split interfaces preserve existing surrogate pairs in all six modes, on repeated emoji and supplementary ideographs with shifted ASCII prefixes, ordinary 500/50 and 501/50 budgets, hard cuts and tiny/extreme budgets. These delimiter-free cases also require progress, coverage and no contained suffix; unrelated CSV/JSON small-target gaps are not repaired or universally claimed gap-free.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; don't ask me for verdicts on technical work.",
          "delegationLocation": "task t-20261025-665d69 request",
          "delegationScope": "Technical choices within the remaining Unicode repair, with this builder bounded to canonical chunk splitting, replacement validation and versioned recovery. Legacy read/RMW/selection/rename safeguards remain a separate unfinished stage.",
          "choice": "Produce canonical chunks without cutting an existing high/low surrogate pair, validate every supplied plan before parent publication or child deletion, and bump the existing physical-shape algorithm input from v1 to v2 without changing ASCII/BMP behavior, token budgets or legacy safeguards.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/665d69-canonical-unicode-repair.json",
      "newChecks": [
        "modules/indexing/src/test/java/io/justsearch/indexing/chunking/ChunkSplitterCoreTest.java#supplementaryCharactersKeepWholeCodePointsInEveryMode"
      ]
    },
    {
      "items": [
        "modules/indexing/src/test/java/io/justsearch/indexing/chunking/ChunkSplitterCoreTest.java"
      ],
      "class": "New",
      "behaviour": "Preserve exact ASCII/BMP hard-cut offsets, UTF-16 units, original malformed source, and the existing plain trim versus metadata strip definitions. Existing preference/estimator tests remain unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; don't ask me for verdicts on technical work.",
          "delegationLocation": "task t-20261025-665d69 request",
          "delegationScope": "Technical choices within the remaining Unicode repair, with this builder bounded to canonical chunk splitting, replacement validation and versioned recovery. Legacy read/RMW/selection/rename safeguards remain a separate unfinished stage.",
          "choice": "Produce canonical chunks without cutting an existing high/low surrogate pair, validate every supplied plan before parent publication or child deletion, and bump the existing physical-shape algorithm input from v1 to v2 without changing ASCII/BMP behavior, token budgets or legacy safeguards.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/665d69-canonical-unicode-preservation.json",
      "newChecks": [
        "modules/indexing/src/test/java/io/justsearch/indexing/chunking/ChunkSplitterCoreTest.java#malformedSourceRemainsUnmodifiedAndTrimDefinitionsStayDistinct",
        "modules/indexing/src/test/java/io/justsearch/indexing/chunking/ChunkSplitterCoreTest.java#asciiAndBmpHardCutOffsetsRemainInUtf16Units"
      ]
    },
    {
      "items": [
        "modules/indexing/src/test/java/io/justsearch/indexing/chunking/ChunkTilingPropertyTest.java"
      ],
      "class": "New",
      "behaviour": "Generated supplementary-character windows preserve pair boundaries and exact source slices, advance without alignment gaps and do not emit contained suffixes. The prior generated conservation-law check is unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; don't ask me for verdicts on technical work.",
          "delegationLocation": "task t-20261025-665d69 request",
          "delegationScope": "Technical choices within the remaining Unicode repair, with this builder bounded to canonical chunk splitting, replacement validation and versioned recovery. Legacy read/RMW/selection/rename safeguards remain a separate unfinished stage.",
          "choice": "Produce canonical chunks without cutting an existing high/low surrogate pair, validate every supplied plan before parent publication or child deletion, and bump the existing physical-shape algorithm input from v1 to v2 without changing ASCII/BMP behavior, token budgets or legacy safeguards.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/665d69-canonical-unicode-repair.json",
      "newChecks": [
        "modules/indexing/src/test/java/io/justsearch/indexing/chunking/ChunkTilingPropertyTest.java#supplementaryWindowsPreservePairs"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/rag/ChunkDocumentWriterTest.java"
      ],
      "class": "New",
      "behaviour": "On real Lucene, reject malformed later plan entries (both endpoint cuts, mismatched text, wrong ordinal, blank/empty/out-of-range spans) before deleting existing child identities/text/status/retries/vectors; reject invalid parent plans before canonical content/hash/declaration or lexical publication; fresh and regenerated Unicode chunks reopen exact, pending with retry zero, fresh artifact ids and no old ordinal-vector reuse.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; don't ask me for verdicts on technical work.",
          "delegationLocation": "task t-20261025-665d69 request",
          "delegationScope": "Technical choices within the remaining Unicode repair, with this builder bounded to canonical chunk splitting, replacement validation and versioned recovery. Legacy read/RMW/selection/rename safeguards remain a separate unfinished stage.",
          "choice": "Produce canonical chunks without cutting an existing high/low surrogate pair, validate every supplied plan before parent publication or child deletion, and bump the existing physical-shape algorithm input from v1 to v2 without changing ASCII/BMP behavior, token budgets or legacy safeguards.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/665d69-canonical-unicode-repair.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/rag/ChunkDocumentWriterTest.java#invalidLaterPlanEntriesLeaveCommittedChildrenAndVectorsUntouched",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/rag/ChunkDocumentWriterTest.java#invalidPlanIsRefusedBeforeParentAndLexicalPublication",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/rag/ChunkDocumentWriterTest.java#supplementaryChunksAreExactAndPendingAfterFreshWriteRegenerationAndReopen"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/rag/ChunkDocumentWriterTest.java"
      ],
      "class": "New",
      "behaviour": "Declared partial sets remain incomplete after reopening; multi-chunk to short/single and empty replacements preserve empty-plan cleanup and complete zero-child declarations after reopening.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; don't ask me for verdicts on technical work.",
          "delegationLocation": "task t-20261025-665d69 request",
          "delegationScope": "Technical choices within the remaining Unicode repair, with this builder bounded to canonical chunk splitting, replacement validation and versioned recovery. Legacy read/RMW/selection/rename safeguards remain a separate unfinished stage.",
          "choice": "Produce canonical chunks without cutting an existing high/low surrogate pair, validate every supplied plan before parent publication or child deletion, and bump the existing physical-shape algorithm input from v1 to v2 without changing ASCII/BMP behavior, token budgets or legacy safeguards.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/665d69-canonical-unicode-preservation.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/rag/ChunkDocumentWriterTest.java#partialReplacementAndEmptyPlanCleanupRetainCompletenessAfterReopen"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduMutationCommitTest.java"
      ],
      "class": "New",
      "behaviour": "A real accepted VDU result stores supplementary text and publishes safe, exact, pending/retry-zero chunks that survive commit and reopening. Existing VDU durability cases remain unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; don't ask me for verdicts on technical work.",
          "delegationLocation": "task t-20261025-665d69 request",
          "delegationScope": "Technical choices within the remaining Unicode repair, with this builder bounded to canonical chunk splitting, replacement validation and versioned recovery. Legacy read/RMW/selection/rename safeguards remain a separate unfinished stage.",
          "choice": "Produce canonical chunks without cutting an existing high/low surrogate pair, validate every supplied plan before parent publication or child deletion, and bump the existing physical-shape algorithm input from v1 to v2 without changing ASCII/BMP behavior, token budgets or legacy safeguards.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/665d69-canonical-unicode-repair.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduMutationCommitTest.java#supplementaryVduReplacementIsExactAfterCommitAndReopen"
      ]
    },
    {
      "items": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/commit/FingerprintInputSourcesTest.java"
      ],
      "class": "New",
      "behaviour": "With explicitly unconfigured model roles, canonical fingerprint inputs contain algorithm v2 and their exact SHA-256 is the recorded digest, distinct from the same inputs with v1. This is the algorithm-version default upgrade; no token-size/threshold/estimator default changes.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; don't ask me for verdicts on technical work.",
          "delegationLocation": "task t-20261025-665d69 request",
          "delegationScope": "Technical choices within the remaining Unicode repair, with this builder bounded to canonical chunk splitting, replacement validation and versioned recovery. Legacy read/RMW/selection/rename safeguards remain a separate unfinished stage.",
          "choice": "Produce canonical chunks without cutting an existing high/low surrogate pair, validate every supplied plan before parent publication or child deletion, and bump the existing physical-shape algorithm input from v1 to v2 without changing ASCII/BMP behavior, token budgets or legacy safeguards.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/665d69-canonical-unicode-repair.json",
      "newChecks": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/commit/FingerprintInputSourcesTest.java#canonicalInputsAndDigestCarryTheV2ChunkAlgorithm"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/PreOpenSchemaMismatchBootTest.java"
      ],
      "class": "New",
      "behaviour": "A real model-free boot of a comparable recorded v1 shape takes existing BLUE_GREEN_MIGRATE, FAIL_CLOSED and REBUILD_BACKUP_FIRST paths under v2, preserving Blue or its backup. Existing matching/empty, policy, backup/refusal and brake cases are retained. Unknown or disabled shape evidence is not claimed universally migratable; Green promotion is unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; don't ask me for verdicts on technical work.",
          "delegationLocation": "task t-20261025-665d69 request",
          "delegationScope": "Technical choices within the remaining Unicode repair, with this builder bounded to canonical chunk splitting, replacement validation and versioned recovery. Legacy read/RMW/selection/rename safeguards remain a separate unfinished stage.",
          "choice": "Produce canonical chunks without cutting an existing high/low surrogate pair, validate every supplied plan before parent publication or child deletion, and bump the existing physical-shape algorithm input from v1 to v2 without changing ASCII/BMP behavior, token budgets or legacy safeguards.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/665d69-canonical-unicode-repair.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/PreOpenSchemaMismatchBootTest.java#chunkAlgorithmUpgradeUsesExistingBootRecoveryPolicies"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/WorkerBootFixture.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Extracted existing seed implementation into a private overload while keeping all existing callers identical. Added seedChunkAlgorithm to stamp comparable canonical inputs with a specified chunker algorithm and hash those exact bytes. It reuses the existing real Lucene seed path; all existing inputs, conditions and oracles remain unchanged."
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
  "digest": "sha256:cd63c435755802837835854a0d6013b2631e0e76aa8f379927d59b4a03d3b6f5",
  "note": "Non-author reviewed settled/challenged canonicalA1-A6 and legacyL1-L5, minimum4productionseams and durabletests. Pair-safe splitter+v2 fingerprint, whole-plan prepublication/predelete refusal, central unsafe-text read omission only; unchanged faithful matching-proof RMW/selectors/scheduler. New red checks have actual f8 assertion-failure evidence; same-analyzer Lucene postings/norms/dense preservation passes before/after, SPLADE reset exception explicit. Existing boot fixture helper instrumentation preserves alloldcallerinputs/oracles. Author160stageA plus40legacy/80adapter/12canonical evidence inspected; no selfacceptance or owner scenario adoption. Independent committed verification, full Green rebuild/promotion and remaining hardware proof are not inferred. Every new check kept as durable boundary/recovery/preservation regression coverage."
}
```
