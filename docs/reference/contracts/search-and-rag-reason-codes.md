---
title: Search & RAG Reason Codes (Degradation)
type: reference
status: stable
description: 'Degradation signaling contract for the search/RAG port responses and `rag_meta`.'
---

# Search & RAG Reason Codes (Degradation)

JustSearch surfaces explicit mode + reason metadata so clients can distinguish “keyword-only”, “semantic”, and fallback behavior without log-grepping or guesswork.

Worker-emitted reason codes are treated as a contract: they are allowlisted by `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceReasonCodeContractTest.java`. Head-side fallback reasons are Head-owned (not emitted by the Worker).

## Interactive search (`SearchServiceCalls#search`)

Degradation fields on `SearchResponse` (`modules/ipc-common/src/main/proto/indexing.proto`):

- `effective_mode`: `"TEXT" | "VECTOR" | "HYBRID"`
- `vector_blocked`: `true` when dense retrieval did not run, either because it was unavailable or
  because the planner deliberately omitted a redundant dense leg
- `vector_blocked_reason`: stable reason code for why dense retrieval did not run
- `hybrid_fallback`: `true` when HYBRID lost dense retrieval because query encoding failed
- `hybrid_fallback_reason`: stable encoding-failure reason; deliberate planner skips do not populate it

### Embedding compatibility reason codes (`EmbeddingCompatibilityController.reasonCode()`)

Used by VECTOR block and HYBRID fallback paths:

- `INITIALIZING`: compatibility state not yet computed (startup)
- `NO_EMBEDDING_MODEL`: no embedding model available on this host
- `NEW_INDEX_NO_FINGERPRINT`: new/empty index; fingerprint will be stamped on first commit
- `LEGACY_INDEX_NO_FINGERPRINT`: index has docs but no fingerprint; vector/hybrid blocked until forced reindex
- `FINGERPRINT_MATCH`: stored fingerprint matches current; vector/hybrid allowed
- `FINGERPRINT_MISMATCH`: stored fingerprint differs; vector/hybrid blocked until forced reindex
- `REBUILD_IN_PROGRESS`: forced rebuild/reindex in progress to realign embeddings
- `REBUILD_COMPLETED`: rebuild completed and fingerprint stamped
- `REBUILD_FAILED_NO_VECTORS`: rebuild drained (`pending_embedding == 0`) without a single successful embedding; the fingerprint was **refused**, not stamped, so vector/hybrid stays blocked. Terminal for the boot — the embedding runtime must be fixed and the worker restarted

### Search degradation reason codes (`WorkerSearchService`)

Used when HYBRID cannot run as requested (may also appear for VECTOR when the compatibility controller is absent):

- `UNKNOWN`: no compatibility controller available to explain the block
- `EMBEDDING_COMPATIBILITY_BLOCKED`: vector queries blocked but controller is unavailable
- `NO_EMBEDDING_SERVICE`: no embedding service available to generate a query vector
- `EMBEDDING_GENERATION_FAILED`: query embedding returned null/empty
- `EMBEDDING_EXCEPTION`: query embedding threw an exception
- `SKIPPED_SHORT_QUERY`: dense retrieval was runnable but omitted because the query was shorter than
  `index.hybrid.vector_skip_min_chars` and another retrieval leg could answer
- `SKIPPED_NO_DISCRIMINATIVE_TERM`: dense retrieval was runnable but omitted because every analyzed
  query term occurred in at least `index.hybrid.vector_skip_min_df_fraction` of content-bearing
  documents. This corpus-relative rule is disabled below 100 content-bearing documents.

Both planner skips apply only to multi-leg search. Dense-only search and the direct RAG retrieval
paths remain recall-first and always run a usable dense leg. The trace reports the dense stage as
`skipped` with the exact reason; neither reason masquerades as an embedding failure.

### Cross-encoder skip reason codes (`CrossEncoderSkipReason`, Head-owned)

The cross-encoder is orchestrated in the Head (`KnowledgeSearchEngine`), so its skip vocabulary is Head-owned — unlike the codes above, which the Worker emits. The code is carried by the `cross-encoder` stage of the unified `searchTrace` (`reason`, alongside `status: "skipped"`). Three of the members (`DEADLINE_EXCEEDED`, `MODEL_NOT_LOADED`, `INFERENCE_FAILED`) originate in the Worker's `RerankResponse.skip_reason` and are normalised in through `CrossEncoderSkipReason.fromWorkerSkipReason` — an unrecognised Worker string becomes `UNKNOWN` rather than passing through raw, so no unworded code can reach a user.

`isDrop()` splits the vocabulary, and the split is what decides altitude:

**By-design skips** (`isDrop() == false`) — the pipeline chose not to rerank. Nothing degraded; diagnostic tier only:

- `NAVIGATIONAL_QUERY`: the query classified as navigational
- `DISABLED`: reranking switched off in configuration
- `BELOW_MIN_THRESHOLD`: fewer candidates than the configured minimum
- `DOCS_TOO_LONG`: average document length exceeds the configured cross-encoder ceiling
- `PIPELINE_NOT_ELIGIBLE`: the active preset has no cross-encoder stage
- `MODEL_NOT_CONFIGURED`: no reranker model configured on this host (an install/capability state, owned by the readiness-notice channel)
- `FUSION_CONFIDENT`: tempdoc 643 perf-shortcut — leg agreement alone was decisive, so the RPC was deliberately not paid

**Drops** (`isDrop() == true`) — the relevance model was supposed to run and did not. Results are still returned, ranked by fusion/LambdaMART instead. These are degradations and are worded at the **user tier** by `CROSS_ENCODER_SKIP_WORDING` in `searchTraceExplain.ts`:

- `DEADLINE_EXCEEDED`: a reranker budget **pre-check** declined to start inference — tokenization or tensor prep had already consumed the latency budget. Raising `justsearch.rerank.deadline_ms` is the knob that fixes this one.
- `RPC_FAILED`: the rerank RPC threw — transport, Worker error, or circuit breaker
- `MODEL_NOT_LOADED`: the Worker is configured for reranking but the model was not loaded when the RPC arrived
- `INFERENCE_FAILED`: inference was attempted and ONNX Runtime threw — memory-arena exhaustion, a dead session, a bad output shape. Register F-054 split this out of `DEADLINE_EXCEEDED`, which the Worker used to stamp on *any* reranker skip: a measured campaign found 199/200 "deadline misses" were BFCArena OOM, unfixable by any deadline value and fixed instantly by `JUSTSEARCH_RERANK_GPU_MEM_MB`. The Worker log names that remedy at the failure site.
- `UNKNOWN`: fall-through for an unrecognised **or unstated** Worker skip reason (a blank `skip_reason` is `UNKNOWN`, not a guessed deadline)

## RAG retrieval (`SearchServiceCalls#retrieveContext`)

Degradation fields on `RetrieveContextResponse` (`modules/ipc-common/src/main/proto/indexing.proto`):

- `retrieval_mode`: `"BM25" | "HYBRID" | "CHUNK_HYBRID" | "FULLTEXT_FALLBACK" | ""` (empty string is used for some short-circuit/error cases)
- `retrieval_mode_reason`: stable reason code explaining the chosen mode (or fallback)
- `context_truncated`: `true` when context assembly hit the budget

Allowlisted `retrieval_mode_reason` values:

- `EMPTY_REQUEST`: empty question and/or docIds
- `NO_CHUNKS_FOUND`: chunk search returned no hits; falls back to `FULLTEXT_FALLBACK`
- `BM25_CONFIGURED`: retrieval mode configured to BM25-only
- `HYBRID_AVAILABLE`: embeddings available; hybrid retrieval used
- `NO_EMBEDDING_SERVICE`: embedding service is null
- `EMBEDDING_UNAVAILABLE`: embedding service unavailable or blocked
- `EMBEDDING_EMPTY`: embedding returned an empty vector
- `EMBEDDING_GENERATION_FAILED`: embedding generation failed/errored
- `CHUNK_VECTOR_COVERAGE_INCOMPLETE`: chunk vectors enabled but coverage < 95%; falls back to doc-first hybrid (`HYBRID`)
- `CHUNKS_BELOW_THRESHOLD`: chunk search found hits but the assembled chunk context was blank; falls back to `FULLTEXT_FALLBACK`
- `FILTERED_EMPTY` / `NO_MATCHING_PARENTS`: document-level filters matched no parent documents
- `FULL_DOCUMENT_REQUESTED` / `FULL_DOCUMENT`: the `return_full_documents` request path returned whole-document context

## Head-side fallback reasons (REST/SSE callers)

The Head may fall back to a full-document fetch when the `retrieveContext` port call fails (`modules/app-services/src/main/java/io/justsearch/app/services/worker/RemoteDocumentService.java`):

- `GRPC_FAILED`: the retrieval call failed; Head used full-document fallback with a character budget. **The ID keeps its historical spelling** — it is a wire-visible string emitted verbatim at `RemoteDocumentService.java:535` and consumed by FE/MCP surfaces, so it outlived the gRPC channel it was named for. Renaming it is a breaking change, not a cleanup.
- `FALLBACK_FAILED`: both the retrieval call and the fallback failed (context is empty)

## SSE: `rag_meta` (UI streaming endpoints)

`SummaryController` emits a `rag_meta` Server-Sent Event before streaming the final text for:

- `POST /api/ask/stream`
- `POST /api/summarize/batch/stream` (and related summarize flows)

Payload shape:

```json
{
  "retrieval_mode": "HYBRID",
  "retrieval_mode_reason": "HYBRID_AVAILABLE",
  "context_truncated": false,
  "chunks_used": 5,
  "chunks_found": 12
}
```

## Reason-code governance

Lifecycle / readiness reason codes are validated by the CI check `scripts/ci/check-readiness-reason-codes.mjs`, which cross-checks `LifecycleReasonCode.java` against its FE consumer (`readinessNotice.ts`).

The **search-degradation** vocabularies (`SearchReasonCode.java`, `CrossEncoderSkipReason.java`) have no such offline check: the `search-degradation-reason-codes` register and its script were retired in tempdoc 930 because they ran in no workflow. The surviving authority is the colocated FE unit test `searchTraceExplain.test.ts`, which pins `DEGRADATION_REASON_WORDING` and `CROSS_ENCODER_SKIP_WORDING` against declared code lists in both directions — nothing declared goes unworded (so a degraded search-explain line never shows a raw `(CODE)`), and no worded key is dead. Honest limit: those lists are hand-kept mirrors of the Java enums, because the generated wire schema types the trace's `reason` fields as plain `string`; adding a producer code means updating the mirror in the same change. On the producer side, `CrossEncoderSkipReason.isDrop()` is an exhaustive `switch`, so a new member cannot silently join the worded or the unworded class without a compile error.

`check-readiness-reason-codes.mjs` additionally enforces a **producer direction** (tempdoc 837): every `LifecycleReasonCode` member must be referenced by at least one `modules/**/src/main` Java source outside the enum's own file — by enum name or quoted code string, matched after comment-stripping. A code nothing can emit is a phantom: its wording row is unreachable UI and the vocabulary claims a state the system cannot report. The direction runs with no exemption list; adding a code with no emit site fails the build. Honest limit: a *reference* is not an *emission*, so the check catches the zero-reference class rather than proving every code is reachable.

**Case convention:** Java source uses `UPPER_CASE` IDs; FE/wire equivalents use `lower_snake_case` (`no_embedding_service` ↔ `NO_EMBEDDING_SERVICE`). The mapping is a trivial case-fold. The contract test allowlists in `WorkerSearchServiceReasonCodeContractTest` serve as the compile-time safety net.

**Category design:** the search-routing partition has seven codes: five execution failures and two
planner-owned dense-skip decisions. The embedding-compatibility partition covers lifecycle states
from `EmbeddingCompatibilityController`, plus an explicit unknown-string fall-through.
