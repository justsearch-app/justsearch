---
title: Adapters-Lucene Deep Dive
type: explanation
status: stable
description: "HNSW search, SPLADE learned sparse retrieval, hybrid fusion, commit metadata system."
---

# Adapters-Lucene Deep Dive

The `modules/adapters-lucene` module provides the core search engine integration for JustSearch. It wraps Apache Lucene 10 with HNSW vector search, hybrid BM25+KNN fusion, and a sophisticated commit metadata system for schema evolution.

This document provides a deep architectural walkthrough for developers who need to understand, extend, or tune the search subsystem.

> **Decision register:** For settled findings, canonical baselines, and open questions about search quality, see `docs/reference/search-quality-register.md`.

## 1. Module Overview

### 1.1 Package Structure

```text
io.justsearch.adapters.lucene/
├── analyzers/          # Analyzer configuration, ICU tokenization, and synonym loading from SSOT catalogs
├── commit/             # Commit metadata fingerprinting (SHA-256) and schema parity validation on open
├── runtime/            # Core search engine runtime — facade, read/write paths, hybrid fusion, chunked RAG retrieval,
│                       #   query building, faceting, autocomplete, field mapping, NRT commit coordination, and pruning
└── (interfaces)        # Shard coordination contracts (single-shard implementation)
```

The `runtime/` package is the largest. It is organized around focused ops classes — `ReadPathOps`,
`WritePathOps`, `CommitOps`, `TextQueryOps`, `HybridSearchOps`, `ChunkSearchOps`, `DocumentQueryOps`,
`SuggestOps`, `PruneOps`, `FacetingEngine` — with `RunningRuntime`/`RuntimeSession` holding lifecycle.
(The former `LuceneIndexRuntime` facade was dissolved in tempdoc 320; the ops classes are now the API.)

### 1.3 Dependencies

- **Lucene 10.x**: Core search library (IndexWriter, IndexSearcher, HNSW)
- **configuration**: Field catalog definitions, `ResolvedConfig`/`ConfigStore` (config moved here in tempdoc 239; `RuntimeConfig` later deleted in 314)
- **indexing**: Schema fields, IndexDocument contracts
- **infra-core**: Telemetry, utilities

## 2. Lucene 10 Integration

### 2.1 Directory and IndexWriter

```java
// Default: MMapDirectory for memory-mapped I/O
Directory directory = new MMapDirectory(indexPath);

// IndexWriter with custom codec
IndexWriterConfig config = new IndexWriterConfig(indexAnalyzer);
config.setCodec(new JustSearchCodec(knnVectorsFormat));
config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);

// Soft deletes merge policy
config.setMergePolicy(new SoftDeletesRetentionMergePolicy(
    softDeleteField,
    retentionQuery,
    new TieredMergePolicy()
));
```

### 2.2 SearcherManager and CRTRT

Near Real-Time (NRT) search uses `ControlledRealTimeReopenThread`:

```java
SearcherManager searcherManager = new SearcherManager(writer, new SearcherFactory());

// CRTRT: background thread that refreshes searchers. Both construction sites — the
// initial open (ComponentsFactory) and the rebuild after a bulk-backfill suspend
// (CommitOps.resumeNrtRefresh) — go through NrtReopenThreads.create, which is the only
// place the ms→seconds conversion and Lucene's argument order live.
ControlledRealTimeReopenThread<IndexSearcher> crtrt =
    new ControlledRealTimeReopenThread<>(writer, searcherManager,
        nrtTargetMaxStaleMs / 1000.0,  // targetMaxStaleSec: background reopen target
        nrtHardMaxStaleMs / 1000.0     // targetMinStaleSec: reopen target while a caller waits
    );
```

**Configuration**:
- `index.nrt.target_max_stale_ms`: 500ms (default)
- `index.nrt.max_stale_ms`: 50ms (default)

Lucene rejects `targetMaxStaleSec < targetMinStaleSec`, so a configured `index.nrt.max_stale_ms`
larger than `index.nrt.target_max_stale_ms` is clamped to the target (with a WARN) rather than
failing the open.

**Knob-naming caveat.** `index.nrt.max_stale_ms` reads as the *larger* bound but is passed as
Lucene's `targetMinStaleSec` — the *tighter* reopen target used while a caller is waiting on a
generation. The field behind it is `nrtHardMaxStaleMs`. The names are backwards relative to
Lucene's; renaming the key would change the resolved-config surface, so the ordering is enforced
(the clamp above) while the naming is documented here rather than corrected.

### 2.2.1 Reopen strategy: `index.nrt.mode`

`index.nrt.mode` selects which component is responsible for making a newly written document
visible. Both arms are shipped; `continuous` is the default.

| Mode | Background thread cadence | Foreground search behaviour |
| :--- | :--- | :--- |
| `continuous` (default) | `index.nrt.target_max_stale_ms` / `index.nrt.max_stale_ms` (500 ms / 50 ms) | acquires whatever the last background reopen produced |
| `on_demand` | `index.nrt.background_reopen_ms` (2000 ms), applied to both Lucene bounds and preserved across a bulk-backfill suspend/resume | refreshes the `SearcherManager` itself before acquiring |

In `on_demand` mode the foreground refresh is a four-way decision (`NrtOnDemandPolicy`):

1. **Skip** when no foreground RPC is in flight. Background enrichment reads reach Lucene through
   the same bridge a search does, so the mode alone cannot tell them apart — see below.
2. **Skip** when the writer's `getMaxCompletedSequenceNumber()` has not moved since the last
   reopen. Nothing has been added, updated or deleted, so there is nothing to see; age alone is
   never a reason to reopen.
3. **`maybeRefresh()`** (non-blocking) when there are new writes and the last reopen is within
   `index.nrt.on_demand_max_stale_ms` (1000 ms).
4. **`maybeRefreshBlocking()`** when there are new writes and the last reopen is older than that
   bound, so a query cannot silently return a view older than the configured limit.

The refresh lives in **one** seam — `SearcherBridge`, which every foreground read path (text
query, chunk search, suggest, facets, folder browse, document fetch, counts) already goes through
— rather than per RPC, so the mode is a property of the index runtime and not of whichever
service was updated last. The write path's read-modify-write reads opt out via
`withSearcherNoRefresh`: those run inside indexing, and not paying for reopens during indexing is
the point of the mode.

**The foreground gate is load-bearing, not a refinement.** `SearcherBridge` is the seam for *all*
reads, including the document fetches `CombinedEnrichmentBackfillOps` and `BgeM3BackfillOps` make
for every document they enrich. Because indexing is writing continuously, the freshness check in
step 2 almost always says "new writes", so without step 1 each backfill fetch reopened the
searcher: a measured 2.9x rise in reopen count and a 15% loss of indexing throughput. The gate is
a `BooleanSupplier` on `RuntimeSession`, wired by the Worker from `ForegroundLoad.inFlight() > 0`
— the same in-flight gauge the indexing duty cycle reacts to, and the only component that knows a
search-family RPC is running. An unwired runtime defaults to "always foreground", erring toward
freshness rather than toward serving a stale searcher; `continuous` never consults it.

**Honest limit — the gauge is process-wide, not per-call provenance.** While any search-family RPC
is in flight, a concurrent backfill fetch still takes the refresh path. The gate removes the reopen
storm of an *unattended* backfill (the measured case: an ingest-only run), not every background
reopen; a run that indexes and searches at the same time keeps some of it. Closing that needs the
foreground/background distinction threaded from the RPC layer down to the read, which is a larger
change than this seam. An ingest-only measurement cannot observe the residue, so the re-measure
this mode is waiting on has to include a search-load arm.

An idle Worker in `on_demand` mode performs no reopens at all: the background thread still wakes
every `background_reopen_ms`, but `DirectoryReader.openIfChanged` returns null on an unchanged
index, so no reader is swapped and `index.runtime.reopen_count` does not move.

### 2.2.2 Cadence instrumentation

Three gauges on `RuntimeGaugesSnapshot` (exported by `IndexRuntimeMetricCatalog` and archived to
RRD) describe the reopen/commit cadence:

| Metric | Meaning |
| :--- | :--- |
| `index.runtime.commit_count` | every `CommitOps.commitAndTrack` — the commit timer, gRPC deletes, prune and backfill included. Distinct from `worker.commits.total`, which counts only the `IndexingLoop`-attributed commits. |
| `index.runtime.reopen_count` | reopens that swapped in a new reader, across every reopen path (background thread, `CommitOps.maybeRefresh*`, the on-demand seam). |
| `index.runtime.segments_since_reopen` | `IndexWriter.getSegmentInfosCounter()` delta since the last reopen — the backlog of new segments the next reopen has to open. |

Alongside them, one reason-tagged **counter** says *which trigger* fired each commit:

| Metric | Meaning |
| :--- | :--- |
| `index.runtime.commit_total{reason}` | commits by `CommitReason` (`timer`, `indexing-loop/idle`, `backfill/ner`, …). Not a second authority for `commit_count`: both are written at the one funnel from the same reason, and `RuntimeSession.commitCount` (a `CommitCounters`) derives its total by **summing** its per-reason slots, so attribution cannot drift from the total. It differs only in lifetime — this counter accumulates across sessions, the gauge resets with the session. jseval surfaces it as `cadence.commit_by_reason`. |

`IndexWriter` does not expose its segment count publicly (`getSegmentCount()` is
package-private), so the segment-naming counter is the readable proxy; `DirectoryReader.leaves()`
on an acquired searcher gives the complementary "segments currently visible" reading.

**All three are per-session and reset on a session swap** — they live on `RuntimeSession`, so
`DeferredRuntime.upgradeWriter`, a blue/green re-open and the corruption-recovery rebuild each
start them from zero. They are not process-monotonic. Where a reason-tagged histogram exists
(`index.runtime.commit_ms`) it accumulates across sessions and is the more complete figure; the
gauges are for within-run, within-session comparison.

### 2.3 Read-After-Write Consistency

For APIs that need immediate visibility after commit:

```java
void maybeRefreshBlockingIfCommittedSinceRefresh() {
    if (lastCommitNanos.get() > lastRefreshNanos.get()) {
        searcherManager.maybeRefresh();
        lastRefreshNanos.set(System.nanoTime());
    }
}
```

## 3. HNSW Vector Search

### 3.1 JustSearchCodec

Custom codec wrapping `Lucene104Codec` with pluggable vector format:

```java
public final class JustSearchCodec extends FilterCodec {
    // HNSW Parameters
    private static final int DEFAULT_M = 16;              // max connections per node
    private static final int DEFAULT_EF_CONSTRUCTION = 200; // build-time beam width

    // Float32 format (current default)
    public static KnnVectorsFormat float32Format() {
        return new Lucene99HnswVectorsFormat(DEFAULT_M, DEFAULT_EF_CONSTRUCTION);
    }

    // Int8 scalar-quantized format (~75% memory reduction)
    // Tested with Lucene 10.3.1 - enable via index.vector.quantization.enabled=true
    public static KnnVectorsFormat quantizedFormat() {
        return new Lucene104HnswScalarQuantizedVectorsFormat(DEFAULT_M, DEFAULT_EF_CONSTRUCTION);
    }
}
```

**Memory Impact**:
| Format | 768 dims | Savings |
|--------|----------|---------|
| Float32 | 3,072 bytes/doc | Baseline |
| Int8 | 768 bytes/doc | ~75% reduction |

### 3.2 KnnFloatVectorQuery Variants

**Unfiltered KNN**:

```java
KnnFloatVectorQuery query = new KnnFloatVectorQuery(
    SchemaFields.VECTOR, queryVector, limit
);
```

**Filtered KNN** (pre-filter reduces candidate set):

```java
Query filter = buildFilterQueryOnly(runtimeFilters);
KnnFloatVectorQuery query = new KnnFloatVectorQuery(
    SchemaFields.VECTOR, queryVector, limit, filter
);
```

**With `ef_search` oversampling** (improves recall):

```java
// Lucene 10.3.1 sizes the HNSW candidate queue to `k` (no separate efSearch knob).
// When index.vector.ef_search is set (>0), JustSearch runs the query with k=max(limit, ef_search)
// and then returns only `limit` hits.
int queryK = Math.max(limit, configuredEfSearch);
KnnFloatVectorQuery query = new KnnFloatVectorQuery(
    SchemaFields.VECTOR, queryVector, queryK, filter
);
```

**With `index.vector.exhaustive_search`** (exact, not approximate):

`ReadPathOps#buildKnnQuery` is the one site that builds every `KnnFloatVectorQuery` — both
document-level overloads and the chunk dense leg. `ef_search` widens the HNSW beam but cannot make
the result exact: `AbstractKnnVectorQuery.getLeafResults` has no exact branch for an *unfiltered*
query at any `k`. With a filter present it takes `exactSearch` once the filter's cost is within the
per-leaf top-k, so this switch raises `k` to at least `reader.maxDoc()` and substitutes a
`MatchAllDocsQuery` when the caller supplied no filter. Cost is O(vectors) per query — a
capture/diagnostic knob for reproducible runs, not a production default. In that mode the dense
leg's inflated `totalHits` is excluded from the chunk branch's candidate-budget saturation test
(`SearchExecutor#isCandidateBudgetSaturated`), so only the approximation changes, not which
branches the pipeline takes.

### 3.3 Field Separation Strategy

JustSearch uses separate vector fields to avoid filter overhead:

| Field | Documents | Purpose |
|-------|-----------|---------|
| `vector` | All documents | Parent-level embeddings |
| `chunk_vector` | Chunk documents only | Chunk-level embeddings |

**Why**: Querying `chunk_vector` avoids the `is_chunk=true` filter needed when searching `vector` for chunks. This saves ~17ms p95 overhead.

### 3.4 Configuration

For current HNSW tuning parameters (M, efConstruction, efSearch), see [`docs/explanation/23-search-pipeline-overview.md` §Stage 8](23-search-pipeline-overview.md).

| Parameter | Purpose |
|-----------|---------|
| `index.vector.ef_search` | Query-time search breadth (oversampling k) |
| `index.vector.exhaustive_search` | Make every kNN query exact instead of approximate (default off) |
| `index.vector.hnsw.m` | Max connections per HNSW node |
| `index.vector.hnsw.ef_construction` | Build-time beam width |
| `index.vector.quantization.enabled` | Enable Int8 quantization |

## 4. Hybrid Search Architecture

### 4.1 Parallel Execution

Hybrid search runs BM25 and KNN in parallel using virtual threads:

```java
var executor = Executors.newVirtualThreadPerTaskExecutor();
try {
    var textFuture = CompletableFuture.supplyAsync(
        () -> searchText(query, textLimit, filters), executor);
    var vectorFuture = CompletableFuture.supplyAsync(
        () -> searchVector(vector, vectorLimit, filters), executor);

    textResult = textFuture.join();
    vectorResult = vectorFuture.join();
} finally {
    executor.close();
}
```

### 4.2 Over-Retrieval Strategy

To improve fusion quality, both paths over-retrieve candidates beyond the requested limit. Final results are trimmed to the requested limit after fusion. For current over-retrieval multipliers per path, see [`docs/explanation/23-search-pipeline-overview.md` §Stages 6-7](23-search-pipeline-overview.md).

### 4.3 RRF Fusion Algorithm

Reciprocal Rank Fusion combines rankings:

```text
score(doc) = Σ(weight / (K + rank))
           + bm25_boost_weight × raw_bm25_score
```

**Implementation** (`fuseWithRRF`):
1. Process BM25 results: `rrfScore = 1.0 / (K + rank)`
2. Process Vector results: `rrfScore = vectorWeight / (K + rank)`
3. Add BM25 boost: `finalScore += bm25BoostWeight × rawBm25Score`
4. Sort by fused score with tie-breakers (BM25 > vector > docId)

For current RRF constants (K, vectorWeight, bm25BoostWeight), see [`docs/explanation/23-search-pipeline-overview.md` §Stage 9](23-search-pipeline-overview.md).

### 4.4 Low-Signal Gating

Detects weak signal from either ranking and adjusts fusion. When low signal is detected, vector-only docs are capped and vector contribution is reduced. This prevents "semantic hijack" where weak vector matches dominate results.

For current low-signal detection thresholds and cap values, see [`docs/explanation/23-search-pipeline-overview.md` §Stage 10](23-search-pipeline-overview.md).

### 4.5 Planner-Owned Dense Skip

When another retrieval leg can run, the planner skips vector search for queries shorter than four
characters or queries whose analyzed terms are all common in the indexed `content` corpus. It never
applies this optimization to dense-only search or direct RAG. For current thresholds, see
[`docs/explanation/23-search-pipeline-overview.md` §Stage 11](23-search-pipeline-overview.md).

## 4B. SPLADE (Learned Sparse Retrieval)

### 4B.1 Indexing

SPLADE-v3 encodes documents as sparse term-weight vectors. `FieldMapper`
stores these as Lucene `FeatureField` entries — one per non-zero term:

```java
// FieldMapper.toDocument() — "splade" field type
for (var entry : sparseVec.entrySet()) {
    float weight = Math.min(entry.getValue(), 64.0f); // clamp outliers
    doc.add(new FeatureField(fieldId, entry.getKey(), weight));
}
```

Each document gets a variable number of `FeatureField` entries under the
`splade` field name. Weights are clamped to 64.0 to prevent single-token
dominance.

### 4B.2 Query-Time Search

SPLADE queries are also sparse weight vectors. Each token becomes a
`FeatureField.newLinearQuery` SHOULD clause in a `BooleanQuery`:

```java
// ChunkSearchOps.searchChunksSplade / LuceneIndexRuntime.searchSplade
BooleanQuery.Builder builder = new BooleanQuery.Builder();
for (var entry : queryWeights.entrySet()) {
    builder.add(
        FeatureField.newLinearQuery("splade", entry.getKey(), entry.getValue()),
        BooleanClause.Occur.SHOULD
    );
}
// + IS_CHUNK filter (true for chunk search, false/MUST_NOT for whole-doc)
```

Lucene's `FeatureField` scoring uses the stored weight directly — the
dot product of query weights and document weights produces the relevance
score, which is what makes SPLADE a *learned* sparse model (weights
come from the neural encoder, not BM25 TF-IDF).

### 4B.3 Whole-Doc vs Chunk SPLADE

| Search Path | IS_CHUNK Filter | Owner |
|-------------|-----------------|-------|
| Whole-doc SPLADE | `MUST_NOT is_chunk=true` | `TextQueryOps.buildSpladeQuery` (via `SearchExecutor.searchSplade`) |
| Chunk SPLADE | `FILTER is_chunk=true` | `ChunkSearchOps` |

Both paths share the same `FeatureField` query building logic. The chunk
path additionally includes `PARENT_TOKEN_COUNT` in its stored-field
allowlist so downstream fusion can modulate SPLADE weight by parent
document length (see [23-search-pipeline-overview.md § Stage 13b](23-search-pipeline-overview.md)).

`rag.chunk_splade.enabled` controls the chunk-SPLADE stage on **both the
write side and the query-side leg** (tempdoc 712, 931 §E item 8): with the
flag off no backfill lane encodes chunk `splade` postings, `ChunkDocumentWriter`
writes no `splade_status` on new chunks, and `SearchExecutor` does not run the
chunk-SPLADE leg at all — so the leg cannot score against a partial population
left over from an earlier flag-on window. The whole-doc path is unaffected by
this flag.

### 4B.4 Known Limitation

SPLADE-v3 uses a BERT-base encoder with a 512-token max sequence length
(configurable via `JUSTSEARCH_SPLADE_MAX_SEQ_LEN`, default 512).
Documents longer than ~512 tokens lose body terms from the SPLADE
representation. The search pipeline compensates via parent-length
modulation in `HybridFusionUtils.spladeParentLengthMultiplier()`, which
tapers SPLADE weight linearly based on parent token count. For current
token-count thresholds, see [`docs/explanation/23-search-pipeline-overview.md` §Stage 13b](23-search-pipeline-overview.md).

## 5. Query Building

### 5.1 Filter Construction

Filters use `BooleanClause.Occur.FILTER` for non-scoring clauses:

```java
BooleanQuery.Builder builder = new BooleanQuery.Builder();

// Content query (scoring)
builder.add(contentQuery, BooleanClause.Occur.MUST);

// Chunk exclusion (non-scoring filter)
builder.add(new TermQuery(new Term("is_chunk", "true")), BooleanClause.Occur.MUST_NOT);

// MIME filter (non-scoring)
BooleanQuery.Builder mimeFilter = new BooleanQuery.Builder();
for (String mime : mimeTypes) {
    mimeFilter.add(new TermQuery(new Term("mime", mime)), BooleanClause.Occur.SHOULD);
}
builder.add(mimeFilter.build(), BooleanClause.Occur.FILTER);
```

### 5.2 Supported Filters

| Filter | Query Type | Purpose |
|--------|------------|---------|
| `mime` | TermQuery (OR) | MIME type filtering |
| `mimeBase` | TermQuery (OR) | MIME base category (e.g., "text") |
| `fileKind` | TermQuery (OR) | Document type bucket |
| `language` | TermQuery (OR) | Language codes |
| `pathPrefix` | PrefixQuery | Directory path prefix |
| `modifiedFromMs/ToMs` | NumericDocValuesField.newSlowRangeQuery | Date range |
| `includeChunks` | TermQuery (MUST_NOT) | Chunk doc visibility |

### 5.3 Query Syntax Modes

| Mode | Behavior |
|------|----------|
| `SIMPLE` | User input escaped (operators are literal text). Last term gets prefix expansion via `PrefixQuery` (min 3 chars, exact match boosted 2x). Uses `SCORING_BOOLEAN_REWRITE` for BM25 relevance ranking. |
| `LUCENE` | Full Lucene syntax (phrases, boolean, field qualifiers). No prefix expansion. |

Query building is centralized in `buildSimpleContentQuery()`, shared by both direct text search and filtered hybrid search paths. The method pipeline: escape → parse → `withPrefixExpansion()` → return query.

### 5.4 Search Correction Pipeline

When a SIMPLE-mode query returns zero hits, `GrpcSearchService` applies a two-stage correction pipeline:

1. **Zero-hit retry:** `buildFuzzyTextQuery()` resolves each query token to the closest indexed term via `resolveClosestTerm()` (Levenshtein distance + docFreq tiebreaker), then pipes the resolved terms through `buildSimpleContentQuery()` for score parity with normal queries.
2. **Per-term correction:** When total hits > 0 but some individual terms have zero `docFreq`, `buildPerTermFuzzyQuery()` replaces only the missing terms with their closest resolved equivalents, preserving exact terms.

Both paths set `correctionApplied = true` on the gRPC response and produce scores identical to equivalent exact queries (score parity via shared `buildSimpleContentQuery()` pipeline).

**Key methods in `TextQueryOps`:** `resolveClosestTerm()`, `levenshteinDistance()`, `buildFuzzyTextQuery()`, `buildPerTermFuzzyQuery()`. The facade retains thin delegation stubs for `buildFuzzyTextQuery` and `buildPerTermFuzzyQuery`.

## 6. Pagination and Cursors

### 6.1 Search-After Pattern

Cursor-based pagination using Lucene's `searchAfter`:

```java
// Cursor format: "safter-v1:" + sortKey + ":" + docIdB64 + ":" + score + ":" + modified + ":" + size
String cursor = SEARCH_AFTER_CURSOR_PREFIX +
    sortKey + ":" +
    SEARCH_AFTER_B64.encodeToString(docId.getBytes()) + ":" +
    score + ":" + modifiedAt + ":" + sizeBytes;
```

### 6.2 Sort Modes

| Mode | Sort Fields | Tie-breaker |
|------|-------------|-------------|
| `RELEVANCE` | score DESC | docId |
| `MODIFIED_DESC` | modified_at DESC | docId |
| `MODIFIED_ASC` | modified_at ASC | docId |
| `SIZE_DESC` | size_bytes DESC | docId |
| `SIZE_ASC` | size_bytes ASC | docId |
| `PATH_ASC` | docId ASC | - |
| `PATH_DESC` | docId DESC | - |

The `docId` tie-breaker above is the **stored** `doc_id` field, not Lucene's internal ordinal, and
on a whole-document row it is the normalized absolute path — deterministic across index builds.

**Chunk rows need a different tie-breaker.** A chunk's `doc_id` is `ChunkIds.newChunkDocId()` =
`"chunk:" + UUID.randomUUID()`, minted fresh per ingest and deliberately not derived from the parent
or the chunk index. It is unique within one index and uncorrelated between two, so breaking a score
tie on it gives an order that is stable per index and random per build. The bare chunk searches in
`ChunkSearchOps` therefore sort by score, then `parent_doc_id` (the parent's normalized absolute
path), then `chunk_index`, then `doc_id` as the final total-order comparator
(`LuceneRuntimeUtils#buildChunkTieBreakSort`). Two ingests of the same corpus agree on that key.

**Pruning is retained; only a `totalHits` margin moves.** A leading score comparator does not cost
WAND/block-max pruning. `TopFieldCollector`'s constructor sets `scoreMode = TOP_SCORES` and
`canSetMinScore = true` whenever `firstComparator.getClass() ==
FieldComparator.RelevanceComparator.class && reverseMul[0] == 1 && totalHitsThreshold !=
Integer.MAX_VALUE` — all three hold for these sorts (`SortField.FIELD_SCORE` is a non-reversed
relevance comparator, and the threshold is Lucene's default 1000). So there is no latency
regression, and the whole-document read path was never paying one either.

What does change, besides the order of equal-score hits, is a small margin in the reported
`totalHits` past the threshold. `updateMinCompetitiveScore` passes the bottom entry's **raw** score
to `setMinCompetitiveScore` with no `Math.nextUp`, because with a secondary comparator a later doc
that ties the bottom score can still win on the tie-break and so must not be skipped. Docs equal to
the bottom score are therefore visited and counted, where a docId-tie-broken `TopScoreDocCollector`
would have excluded them. (Both statements verified against the shipped lucene-core 10.4.0
bytecode.)

### 6.3 Lookahead Strategy

Request `limit + 1` documents to determine `hasMore` without extra query:

```java
TopDocs topDocs = searcher.searchAfter(after, query, limit + 1, sort);
boolean hasMore = topDocs.scoreDocs.length > limit;
```

## 7. Field Mapping

### 7.1 Type Conversion

`FieldMapper.toDocument()` converts IndexDocument fields to Lucene fields:

| Type | Lucene Field | DocValues |
|------|--------------|-----------|
| `text` | TextField | - |
| `keyword` | StringField | SortedDocValuesField |
| `long` | StoredField | NumericDocValuesField |
| `boolean` | StoredField (0/1) | NumericDocValuesField |
| `vector` | KnnFloatVectorField | - |

### 7.2 Field Roles

| Role | Behavior |
|------|----------|
| `id` | Primary key, must have DocValues |
| `filter` | Enables inverted index for O(log n) TermQuery |

### 7.3 Key Schema Fields

| Field | Type | Purpose |
|-------|------|---------|
| `doc_id` / `_id` | keyword | Primary key |
| `doc_uid` | keyword | Stable document identity and tiebreaker for search-after |
| `vector` | vector | Document embeddings |
| `chunk_vector` | vector | Chunk-level embeddings |
| `parent_doc_id` | keyword | Links chunks to parent |
| `is_chunk` | keyword | Marks chunk vs full doc |
| `_soft_delete` | keyword | Soft deletion marker |

## 8. Analyzer System

### 8.1 SsotAnalyzerRegistry

Loads analyzers from SSOT catalogs with fingerprinting:

```java
// Analyzer providers
Map<String, Function<Locale, Analyzer>> providers = Map.of(
    "icu+synonyms", locale -> buildIcuWithSynonyms(locale),
    "icu", locale -> buildIcu(locale),
    "keyword", locale -> new KeywordAnalyzer()
);
```

### 8.2 ICU Tokenizer Pipeline

```text
Input Text
    ↓
ICUTokenizer (Unicode-aware word breaking)
    ↓
ICUNormalizer2Filter (Unicode normalization: NFC)
    ↓
LowerCaseFilter
    ↓
SynonymGraphFilter (optional, locale-specific)
    ↓
Token Stream
```

### 8.3 Synonym Loading

Synonyms loaded from SSOT catalogs:
- `SSOT/catalogs/synonyms.en.v1.txt`
- `SSOT/catalogs/synonyms.de.v1.txt`

Format: comma-separated bidirectional expansion

```text
car,automobile,vehicle
quick,fast,rapid
```

### 8.4 Per-Field Analyzers

```java
Map<String, String> fieldToAnalyzerKey = Map.of(
    "content", "icu+synonyms",
    "title", "icu",
    "tags", "keyword"
);
Analyzer perField = analyzerRegistry.buildPerFieldAnalyzer(fieldToAnalyzerKey);
```

## 9. Commit Metadata

### 9.1 Fingerprinting Strategy

`SsotCommitMetadataSource` stamps observability fields plus the two parity keys (tempdoc 915 —
`schema_ver`, `index_schema_fp`, and `analyzer_fp` as a standalone key were retired and folded into
`index_fingerprint`):

```java
Map<String, String> metadata = new HashMap<>();
// Observability (never compared for parity):
metadata.put("schema_fp", sha256(canonicalJson(fieldsCatalog)));
metadata.put("synonyms_hash", sha256(concat(synonymFiles)));
metadata.put("similarity_fp", sha256(bm25Descriptor));
metadata.put("grammar_hash", sha256(intentGrammar));
// ... plus grammar_ver, template_ver, prompt_pack_hash, vector_format, build_state,
// commit_id, commit_time, embedding_model_sha256, splade_model_sha256.

// Parity keys — the ONLY two compared by IndexMetadataParityGuard:
indexFingerprint(fieldsCatalog, analyzerDefs, vectorFormat, hnswParams, chunking,
        embeddingModelSha, spladeModelSha)   // IndexFingerprint.compute(...)
    .ifPresent(fp -> metadata.put("index_fingerprint", fp));   // omitted, never guessed, if indeterminate
metadata.put("index_fingerprint_inputs", canonicalJson(...));  // ALWAYS stamped; the digest's own inputs,
                                                               // compared (minus the ambiguous model keys)
                                                               // whenever EITHER side lacks a digest
metadata.put("boosts_fp", sha256(boostsConfig));               // benign — never a rebuild trigger
```

### 9.2 Parity Guards

`IndexMetadataParityGuard` validates only the two parity keys (`ParityDiagnostics.PARITY_KEYS =
{index_fingerprint, boosts_fp}`) on open — everything else in commit metadata is observability and is
never compared:

```java
void checkOnOpen(Path indexPath, Map<String, Object> expected) {
    Map<String, String> stored = readCommitMetadata(indexPath);
    // blank stored/expected => skip, never "mismatch" — except that a blank digest on EITHER side falls
    // back to comparing index_fingerprint_inputs minus the model keys neither side can state
    // unambiguously, reported under the index_fingerprint key
    // (docs/explanation/11-index-schema-migration.md).
    List<Diff> diffs = ParityDiagnostics.diff(stored, expected);
    if (diffs.isEmpty()) {
        return;
    }
    if (allowMismatch()) {
        return; // operator escape hatch, WARN-only
    }
    if (ParityDiagnostics.requiresRebuild(diffs)) {   // true iff index_fingerprint is among the diffs
        throw new IndexRuntimeIOException(SCHEMA_MISMATCH, ...);
    }
    throw new IllegalStateException("Shard is read-only due to parity mismatch"); // boosts_fp-only diff
}
```

### 9.3 Schema Mismatch Policies

| Policy | Behavior |
|--------|----------|
| `FAIL_CLOSED` | Refuse startup |
| `REBUILD_BACKUP_FIRST` | Backup then rebuild (**dev default**) |
| `BLUE_GREEN_MIGRATE` | Read-only + background rebuild (**production default**, tempdoc 915) |

## 10. Configuration Reference

### 10.1 Hybrid Search Tuning

JustSearch supports two fusion strategies: CC (Convex Combination, default) and RRF (Reciprocal Rank Fusion). CC fusion operates at two levels: 3-way within-branch fusion (BM25 + KNN + SPLADE) and 2-way branch fusion (whole-doc vs chunk branch). RRF provides an alternative rank-based fusion with configurable K constant, vector weight, and BM25 boost factor.

For current CC fusion weights and branch fusion parameters, see [`docs/reference/configuration/environment-variables.md`](../reference/configuration/environment-variables.md). For RRF constants, see [`docs/explanation/23-search-pipeline-overview.md` §Stage 9](23-search-pipeline-overview.md).

**Low-signal gating:** For current threshold and cap values, see [`docs/explanation/23-search-pipeline-overview.md` §Stage 10](23-search-pipeline-overview.md).

### 10.2 Vector Search Tuning

For current HNSW parameter values and ranges, see [`docs/explanation/23-search-pipeline-overview.md` §Stage 8](23-search-pipeline-overview.md).

### 10.3 BM25 Tuning

| Parameter | Default | Purpose |
|-----------|---------|---------|
| `index.similarity.text.k1` | 0.9 | Term frequency saturation |
| `index.similarity.text.b` | 0.4 | Length normalization |

## 11. Performance Optimizations

### 11.1 Virtual Thread Parallelization

BM25 and KNN execute in parallel using `Executors.newVirtualThreadPerTaskExecutor()`:
- No thread pool overhead
- True parallelism for I/O-bound operations
- Automatic cleanup via try-with-resources

### 11.2 Field Separation

Chunk vectors in separate field (`chunk_vector`) avoids `is_chunk` filter:
- Saves ~17ms p95 overhead
- No false positives from parent docs

### 11.3 FILTER vs MUST Clauses

Non-scoring filters use `BooleanClause.Occur.FILTER`:
- Lucene skips scoring calculation
- Significant speedup for complex filter trees

### 11.4 Prefetching

Stored fields are pre-loaded for I/O batching:

```java
// Group disk accesses for multiple hits
searcher.storedFields().prefetch(docIds);
```

### 11.5 Two-Phase Iterator Handling

Facet computation correctly handles query approximations:

```java
TwoPhaseIterator twoPhase = scorer.twoPhaseIterator();
if (twoPhase != null) {
    // Use approximation, then confirm matches
}
```

## 12. Runtime Concurrency Model

`LuceneIndexRuntime` is accessed from multiple threads (gRPC handlers, commit scheduler, close). The following patterns ensure thread safety without heavy locking:

### 12.1 Volatile Snapshot Accessors

The `facetingEngine` and `folderBrowseEngine` fields are declared `volatile`. Public methods access them through null-guard accessors (`facetingOps()`, `folderBrowseOps()`) that take a local snapshot before use:

```java
private FacetingEngine facetingOps() {
  FacetingEngine ops = this.facetingEngine; // local snapshot of volatile
  if (ops == null) throw new IllegalStateException("FacetingEngine not available");
  return ops;
}
```

The same pattern applies to `readOps()`, `writeOps()`, and `indexingCoordinator()`. `close()` nulls all volatile fields after shutting down components.

### 12.2 Lambda Capture Safety

`FacetingEngine` and `FolderBrowseEngine` receive `Supplier<IndexSearcher>` and `Consumer<IndexSearcher>` lambdas for searcher lifecycle. These lambdas capture `searcherManager` via a local snapshot (not a direct field reference) to prevent NPE if `close()` nulls the field concurrently:

```java
() -> {
  SearcherManager mgr = searcherManager; // local snapshot
  if (mgr == null) throw new IllegalStateException("SearcherManager not available");
  return mgr.acquire();
}
```

### 12.3 WritePathOps Null-Guard

`WritePathOps` receives a `Supplier<IndexWriter>` that captures the runtime's volatile writer field. Both `indexDocument()` and `applyBatch()` null-check the supplier result before use, throwing `IllegalStateException` if the runtime has been closed.

### 12.4 Thread-Safety Annotations

`LuceneIndexRuntime`, `ReadPathOps`, `WritePathOps`, and `FacetingEngine` are annotated with `@ThreadSafe` from `net.jcip.annotations`. `InferenceLifecycleManager` additionally uses `@GuardedBy("lock")` on its `currentMode` field.

## 13. Related Documentation

- `docs/explanation/04-storage-engine.md` - Storage layer overview
- `docs/explanation/17-ai-bridge-deep-dive.md` - AI/embedding integration
- `docs/explanation/11-index-schema-migration.md` - Blue/green migration
- Historical Lucene JNI bridge analysis was removed from the public canonical corpus.
