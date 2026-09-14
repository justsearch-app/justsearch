---
title: Search Execution OTel Span Attribute Contract
type: reference
status: stable
description: "Closed set of OpenTelemetry span attribute keys emitted by SearchExecutor and per-LegSet handlers, with value types and emission preconditions."
---

# Search Execution OTel Span Attribute Contract

The Worker-side `SearchExecutor` and its per-`LegSet` handlers emit a closed set
of OpenTelemetry span attribute keys per request. This document is the contract:
every key listed here is produced by code in `modules/worker-services/src/main/
java/io/justsearch/indexerworker/services/execute/`, and adding or removing a
key without updating this document is a contract violation.

Companion doc to `search-and-rag-reason-codes.md` (the reason-code allowlist
contract) and `search-pipeline-invariants.md` (the behavioural contract).

The structural guard for this contract is
`SearchExecutionSpanAttrsContractTest` (worker-services test sourceset): an
exhaustive pattern-match over `SearchDecision` variants that asserts each
variant produces the keys declared below.

## Span topology

```text
request span (the API front; since lane F stage A item A9 it is simply current on the thread)
├── search/retrieval                      — parent of all retrieval-phase spans
│   ├── search/branch{lexical}            — BM25 leg (3-way path)
│   ├── search/branch{dense}              — dense KNN leg (3-way path)
│   ├── search/branch{splade}             — SPLADE leg (3-way path)
│   └── search/fuse{algorithm=cc, branch_count=3}   — primary 3-way fusion
└── search/chunk_merge                    — sibling of retrieval under request
    ├── search/fuse{algorithm=cc, branch_count=3, retrieval.branch=chunk}
    └── search/fuse{algorithm=cc|rrf, branch_count=2}  — whole×chunk fusion
```

Verified against:

- `SearchExecutor.java:130, 261` — `search/retrieval`, `setParent(parentCtx)`
- `SearchExecutor.java:414, 660, 762, 944` — `search/fuse` (4 emission sites)
- `SearchExecutor.java:485` — `search/chunk_merge`, `setParent(parentCtx)`
- `SearchExecutor.java:964` — `search/branch` (helper method)

`search/chunk_merge` is a sibling of `search/retrieval` under the request span,
not a child of retrieval — preserved verbatim from the legacy
`SearchOrchestrator` topology. Downstream consumers (Layer-4 projections per
tempdoc 400 LR2-e.3) filter on `search.fusion.algorithm` +
`search.fusion.branch_count` and depend on this parent-child structure.

## Span attribute keys

### `search/retrieval` span

| Attribute | Type | Emission | Notes |
|---|---|---|---|
| `search.mode` | string | always | One of `TEXT`, `VECTOR`, `HYBRID`. Derived from `LegSet.effectiveModeLabel()` for multi-leg paths; literal `"TEXT"` for the `SparseShortcut` path. |
| `search.took_ms` | int64 | always | Retrieval phase elapsed milliseconds. Set in the `finally` block. |
| `search.searcher_generation` | string | best-effort | Active Lucene `IndexSearcher` generation ID. Omitted when the generation supplier returns null or throws. Source: `activeGenerationSupplier` on the facade. |
| `commit.*` | string | best-effort | Commit user-data passthrough via `CommitMetadataSpanAttrs.applyTo(...)`. Prefix-namespaced; exact keys come from the Lucene commit metadata map. Omitted when the supplier returns null or throws. |

### `search/branch` span (helper at `SearchExecutor.branchSpan`)

| Attribute | Type | Emission | Notes |
|---|---|---|---|
| `search.retrieval.branch` | string | always | Leg identifier. Standard values: `lexical`, `dense`, `splade`. Inside `search/chunk_merge`, the inner 3-way fuse sets this to `chunk` (line 765). |

### `search/fuse` span (4 emission sites)

| Attribute | Type | Emission | Notes |
|---|---|---|---|
| `search.fusion.algorithm` | string | always | `cc` for convex-combination fusion; `rrf` for reciprocal-rank-fusion. The primary 3-way path uses `cc`; `fuseLegs` (novel combinations) uses `rrf`; the whole×chunk branch-merge can be either depending on `hybridConfig.branchFusionStrategy()`. |
| `search.fusion.branch_count` | int64 | always | Number of inputs fused. `3` for the primary 3-way and the inner chunk 3-way; `2` for the whole×chunk branch-merge; variable for `fuseLegs` (matches the legs list size). |
| `search.retrieval.branch` | string | conditional | Set to `chunk` on the inner chunk-side 3-way fuse (line 765 in `SearchExecutor`). Absent on the primary 3-way fuse and the branch-merge fuse. |

### `search/chunk_merge` span

| Attribute | Type | Emission | Notes |
|---|---|---|---|
| (no `search.*` keys directly on the chunk_merge span) | — | — | Per-leg and per-stage timing is attached to the inner `search/fuse` children and to the response's `ComponentTiming` field rather than to this span itself. Future bookkeeping can attach `search.chunk_merge_ms`, `search.chunk_count`, etc. here; the contract test will catch additions. |

### OpenInference projection (tempdoc 553 Phase A)

Every `search/*` span also carries OpenInference attributes **projected from the leg/fusion
`SearchResult` it produced** by `OpenInferenceSpanProjection` (worker-services `execute` package) —
a single pure deriver, so the span tree is a projection of the canonical execution slice (553 pillar
b), not a second hand-authored record. This gives Phoenix/Tempo retriever + reranker interop.

| Attribute | Type | Spans | Notes |
|---|---|---|---|
| `openinference.span.kind` | string | all `search/*` | `RETRIEVER` on `search/retrieval` + `search/branch`; `RERANKER` on `search/fuse`; `CHAIN` on `search/chunk_merge`. |
| `retrieval.documents.{i}.document.id` | string | RETRIEVER | Per-document id, indexed `i` (0-based), bounded to 16 docs. |
| `retrieval.documents.{i}.document.score` | double | RETRIEVER | Per-document score. |
| `retrieval.documents.{i}.document.content` | string | RETRIEVER | Per-document stored content (CONTENT → CONTENT_PREVIEW → TITLE fallback), bounded to 1024 chars. |
| `reranker.output_documents.{i}.document.{id,score,content}` | string/double/string | RERANKER | The fused output documents (same bounds). |
| `reranker.model_name` | string | RERANKER | The fusion algorithm (`cc` / `rrf`). |
| `reranker.input_branch_count` | int64 | RERANKER | Number of inputs fused (mirrors `search.fusion.branch_count`). |

The dynamic `retrieval.documents.*` / `reranker.output_documents.*` keys are allowlisted **by prefix**
in both the contract test and `NdjsonSpanExporter`.

## Privacy guarantees

No span attribute in this contract carries:

- Query text (use the redacted MDC `decision_kind` / `decision_effective_mode`
  keys for log correlation, or the wire-emitted `searchTrace` (tempdoc 549) for
  typed FE consumption — neither of which echoes the user's query).
- Filter values.

**Per-hit document fields — amended (tempdoc 553 Phase A, explicit owner decision).** The
OpenInference RETRIEVER/RERANKER projection (above) deliberately carries per-document `id`, `score`,
and bounded `content` on `search/*` spans — the same per-hit facts already present on the wire
`Hit.trace` response. This **supersedes the prior guarantee** that spans carry no document content /
per-hit fields. Rationale: document-level OpenInference interop (Phoenix/Tempo eval) requires the
documents on the span. Operational note: spans can be exported to external tracing backends, so
operators who enable tracing (`JUSTSEARCH_INDEX_TRACING_LEVEL` ≠ `none`, **default off**) accept that
document ids/scores/content appear in telemetry. Content is bounded (16 docs × 1024 chars) and query
text / filter values remain excluded.

`commit.*` attrs are repository-level identity (commit fingerprints, generation
IDs) — not user input. The `searchTrace` CORRECTION stage's `detail` (the corrected
query) is the only place user input echoes back to the wire; span attrs never carry it.

## Adding a new attribute

The contract is closed at the test level — adding a new attribute requires
updating both the producer (`SearchExecutor` or a handler class) AND this
document AND the `SearchExecutionSpanAttrsContractTest` allowlist in the same
slice.

A new `SearchDecision` variant entering the executor's pattern-match must
declare its span attributes in this document and in the contract test. Compiler
errors will flag the missing handler dispatch; the contract test catches
silent attr omissions.

## Reference cases

- **Tempdoc 400 LR2-e.3** — primary 3-way `search/fuse` with
  `algorithm=cc` + `branch_count=3` (the canonical example).
- **`SearchExecutorOtelTopologyTest`** (worker-services test sourceset) —
  asserts the parent-child topology using the OTel SDK test exporter. The
  attr contract test is a peer; one asserts shape, the other asserts content.
- **Tempdoc 525** — introduced this contract as supporting move A on top of
  the tempdoc 517 refactor (data-only `SearchDecision` sealed sum).
