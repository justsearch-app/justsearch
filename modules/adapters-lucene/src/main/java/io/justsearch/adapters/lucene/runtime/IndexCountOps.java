/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.ChunkEmbeddingCounts;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.EmbeddingCounts;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SpladeFeatureCounts;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.ChunkVectorPresence;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RootCoverageCounts;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.StageCompletenessCounts;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.StageCounts;
import io.justsearch.indexing.SchemaFields;
import java.io.IOException;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.KnnVectorValues;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.FieldExistsQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.Weight;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Internal aggregate-query collaborator for {@link LuceneLifecycleManager}.
 *
 * <p>Encapsulates document count and corpus profiling operations: total doc count,
 * field-value counts, embedding status counts, SPLADE status counts, and the corpus profile.
 *
 * <p>Lifecycle: instances are created in {@code applyComponents()} and discarded on {@code
 * close()}. Access from the runtime must go through a volatile snapshot to ensure visibility
 * across threads.
 */
public final class IndexCountOps {
  private static final Logger log = LoggerFactory.getLogger(IndexCountOps.class);

  private final SearcherBridge bridge;
  private volatile CorpusProfile cachedProfile;
  private volatile long cachedProfileVersion = -1L;
  // Tempdoc 717: artifact-truthful chunk-vector presence, reader-version cached like the corpus
  // profile so the per-query serve-time gate does not re-iterate vectors on every search.
  private volatile ChunkVectorPresence cachedChunkVectorPresence;
  private volatile long cachedChunkVectorPresenceVersion = -1L;
  // Tempdoc 821 §3-C3: the completeness auditor's counts are refreshed on every /api/status poll
  // (jseval polls at 2s through the indexing window), so they get the same reader-version cache as
  // the corpus profile rather than re-issuing 16 counts per poll.
  private volatile StageCompletenessCounts cachedStageCompleteness;
  private volatile long cachedStageCompletenessVersion = -1L;
  // Tempdoc 813 §13: per-root coverage is refreshed on the Library live tick (seconds), so it gets
  // the same reader-version cache as the corpus profile — keyed by prefix because there is one
  // entry per watched root. Bounded so a caller passing unbounded distinct prefixes (an ad-hoc
  // path, a removed root) cannot grow this map without limit.
  private static final int MAX_CACHED_COVERAGE_PREFIXES = 64;
  private final ConcurrentHashMap<String, CachedCoverage> cachedRootCoverage =
      new ConcurrentHashMap<>();

  private record CachedCoverage(long readerVersion, RootCoverageCounts counts) {}

  IndexCountOps(SearcherBridge bridge) {
    this.bridge = bridge;
  }

  /**
   * Returns the total number of documents in the index.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public long docCount() {
    try {
      return bridge.withSearcher(
          searcher -> (long) searcher.getIndexReader().numDocs());
    } catch (IOException e) {
      return 0;
    }
  }

  /**
   * Returns the index's {@code maxDoc} — live documents PLUS deleted-but-unmerged tombstones.
   *
   * <p>The gap against {@link #docCount()} is the merge debt a settle purges (tempdoc 931 §E item
   * 10): tombstones still contribute to BM25 collection statistics, so two indexes of the same
   * corpus with different tombstone counts score the same query differently.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public long maxDoc() {
    try {
      return bridge.withSearcher(searcher -> (long) searcher.getIndexReader().maxDoc());
    } catch (IOException e) {
      return 0;
    }
  }

  /**
   * Returns the number of leaf segments the current searcher reads.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public int segmentCount() {
    try {
      return bridge.withSearcher(searcher -> searcher.getIndexReader().leaves().size());
    } catch (IOException e) {
      return 0;
    }
  }

  /**
   * {@link #docCount()} without the swallow: propagates the reader {@link IOException} instead of
   * reporting 0.
   *
   * <p>Same rationale as {@link #countQueryOrThrow}: "the index is empty" and "the reader could not
   * be read" are the same value to a caller of {@link #docCount()}. A caller that makes a SAFETY
   * decision from emptiness — e.g. {@code EmbeddingCompatibilityController.refresh()}, which treats
   * {@code docCount == 0} as "new index, safe to stamp the embedding fingerprint" (tempdoc 819
   * defect B) — must be able to distinguish, so it calls this and fails closed.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public long docCountOrThrow() throws IOException {
    return bridge.withSearcher(searcher -> (long) searcher.getIndexReader().numDocs());
  }

  /**
   * Counts documents matching a specific field value.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public int countByField(String field, String value) {
    if (field == null || value == null) {
      return 0;
    }
    try {
      return bridge.withSearcher(searcher -> {
        Query query = new TermQuery(new Term(field, value));
        return searcher.count(query);
      });
    } catch (IOException e) {
      log.debug("Failed to count {}={}: {}", field, value, e.getMessage());
      return 0;
    }
  }

  /**
   * {@link #countByField} over whole documents only, excluding chunks.
   *
   * <p>The pair of {@code DocumentFieldOps.queryNonChunkDocIdsByField}: a lane that will not select
   * chunk documents must not gate itself on a count that includes them, or a permanently-parked
   * chunk population reads as perpetual outstanding work.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public int countNonChunkByField(String field, String value) {
    if (field == null || value == null) {
      return 0;
    }
    try {
      return bridge.withSearcher(searcher -> {
        Query query =
            new BooleanQuery.Builder()
                .add(new TermQuery(new Term(field, value)), BooleanClause.Occur.MUST)
                .add(
                    new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")),
                    BooleanClause.Occur.MUST_NOT)
                .build();
        return searcher.count(query);
      });
    } catch (IOException e) {
      log.debug("Failed to count non-chunk {}={}: {}", field, value, e.getMessage());
      return 0;
    }
  }

  /**
   * {@link #countByField} without the swallow: propagates the reader {@link IOException} to a caller
   * that must distinguish "no document matches" from "the reader could not be read".
   *
   * <p>Used by {@code EmbeddingCompatibilityController}'s rebuild certification (tempdoc 819 defect
   * B), where a swallowed error reading the COMPLETED-embedding count would read as "no embedding
   * ever succeeded" — or, worse, a swallowed doc-count error as "the index is empty" — and either
   * decides whether an attestation gets stamped.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public int countByFieldOrThrow(String field, String value) throws IOException {
    if (field == null || value == null) {
      return 0;
    }
    return bridge.withSearcher(searcher -> searcher.count(new TermQuery(new Term(field, value))));
  }

  /**
   * Counts documents matching all supplied exact field values.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public int countByFields(Map<String, String> filters) {
    if (filters == null || filters.isEmpty()) {
      return 0;
    }
    BooleanQuery.Builder builder = new BooleanQuery.Builder();
    for (Map.Entry<String, String> entry : filters.entrySet()) {
      String field = entry.getKey();
      String value = entry.getValue();
      if (field == null || value == null) {
        return 0;
      }
      builder.add(new TermQuery(new Term(field, value)), BooleanClause.Occur.FILTER);
    }
    try {
      return bridge.withSearcher(searcher -> searcher.count(builder.build()));
    } catch (IOException e) {
      log.debug("Failed to count filters {}: {}", filters, e.getMessage());
      return 0;
    }
  }

  /**
   * Counts the documents matching an arbitrary Lucene {@link Query} (exact, unbounded — Lucene's
   * {@code IndexSearcher.count} has no {@code totalHitsThreshold} early-termination, unlike the
   * scored retrieval path). Tempdoc 597: the search response's {@code matchCount} (the true
   * matched-document total) is computed via this, over the same chunk-excluded query the facets
   * scan — so the headline can read "Top N of M matches" and {@code matchCount >=} every facet
   * value by construction, instead of the bounded fused-candidate-union {@code totalHits}.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public int countQuery(Query query) {
    if (query == null) {
      return 0;
    }
    try {
      return countQueryOrThrow(query);
    } catch (IOException e) {
      log.debug("Failed to count query: {}", e.getMessage());
      return 0;
    }
  }

  /**
   * {@link #countQuery} without the swallow: propagates the reader {@link IOException} to a caller
   * that has a meaningful fallback.
   *
   * <p>Returning 0 on a transient IO error is a lie a caller cannot detect — "the query matched
   * nothing" and "the reader could not be read" are the same value. Callers that report a count to
   * a user surface (which renders a known 0 as "the index is empty") must be able to fall back
   * instead, so they call this and decide.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public int countQueryOrThrow(Query query) throws IOException {
    if (query == null) {
      return 0;
    }
    return bridge.withSearcher(searcher -> searcher.count(query));
  }

  /** Strict final-scope proof for a committed prefix delete; exceptions are certified by caller. */
  public int countPathPrefixExcludingAcceptedSurvivorsStrict(
      String prefix, List<String> fileIds, List<String> projectionIds) throws IOException {
    if (prefix == null || prefix.isBlank()) throw new IllegalArgumentException("Empty delete prefix");
    return countUnexpectedSurvivors(new PrefixQuery(new Term(SchemaFields.PATH,
        QueryFilterBuilder.normalizePathPrefix(prefix))), fileIds, projectionIds);
  }

  /** Strict final-scope proof for a committed collection delete, including parent and chunk docs. */
  public int countCollectionExcludingAcceptedSurvivorsStrict(
      String collection, List<String> fileIds, List<String> projectionIds) throws IOException {
    if (collection == null || collection.isBlank()) throw new IllegalArgumentException("Empty collection");
    return countUnexpectedSurvivors(new TermQuery(new Term(SchemaFields.COLLECTION, collection)),
        fileIds, projectionIds);
  }

  /** Mirrors exact deletion's identity and chunk ownership scope without changing the writer. */
  public int countByIdAndChunksStrict(String id) throws IOException {
    if (id == null || id.isBlank()) throw new IllegalArgumentException("Empty document identity");
    var chunks = new BooleanQuery.Builder()
        .add(new TermQuery(new Term(SchemaFields.PARENT_DOC_ID, id)), BooleanClause.Occur.FILTER)
        .add(new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")), BooleanClause.Occur.FILTER);
    return countQueryOrThrow(new BooleanQuery.Builder()
        .add(new TermQuery(new Term(SchemaFields.DOC_ID, id)), BooleanClause.Occur.SHOULD)
        .add(chunks.build(), BooleanClause.Occur.SHOULD).build());
  }

  private int countUnexpectedSurvivors(Query scope, List<String> fileIds,
      List<String> projectionIds) throws IOException {
    var query = new BooleanQuery.Builder().add(scope, BooleanClause.Occur.FILTER);
    var ids = new java.util.ArrayList<String>(fileIds);
    ids.addAll(projectionIds);
    if (!ids.isEmpty()) {
      query.add(identityTerms(SchemaFields.DOC_ID, ids), BooleanClause.Occur.MUST_NOT);
    }
    if (!fileIds.isEmpty()) {
      // A no-file projection may carry arbitrary parent/chunk fields. Its reserved projection:
      // identity cannot masquerade as an actual Worker chunk in the permanent chunk: namespace.
      var chunks = new BooleanQuery.Builder()
          .add(identityTerms(SchemaFields.PARENT_DOC_ID, fileIds), BooleanClause.Occur.FILTER)
          .add(new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")), BooleanClause.Occur.FILTER)
          .add(new PrefixQuery(new Term(SchemaFields.DOC_ID,
              io.justsearch.indexing.chunking.ChunkIds.CHUNK_PREFIX)), BooleanClause.Occur.FILTER);
      query.add(chunks.build(), BooleanClause.Occur.MUST_NOT);
    }
    return countQueryOrThrow(query.build());
  }

  private static Query identityTerms(String field, List<String> ids) {
    return new TermInSetQuery(field, ids.stream().map(BytesRef::new).toList());
  }

  /**
   * Queries chunk embedding status counts for the status endpoint.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public ChunkEmbeddingCounts queryChunkEmbeddingCounts() {
    try {
      return bridge.withSearcher(searcher -> {
        Query chunksQuery = new TermQuery(new Term(SchemaFields.IS_CHUNK, "true"));
        int total = searcher.count(chunksQuery);

        Query completedQuery = new BooleanQuery.Builder()
            .add(chunksQuery, BooleanClause.Occur.FILTER)
            .add(new TermQuery(new Term(
                    SchemaFields.CHUNK_EMBEDDING_STATUS,
                    SchemaFields.EMBEDDING_STATUS_COMPLETED)),
                BooleanClause.Occur.FILTER)
            .build();
        int completed = searcher.count(completedQuery);

        Query pendingQuery = new BooleanQuery.Builder()
            .add(chunksQuery, BooleanClause.Occur.FILTER)
            .add(new TermQuery(new Term(
                    SchemaFields.CHUNK_EMBEDDING_STATUS,
                    SchemaFields.EMBEDDING_STATUS_PENDING)),
                BooleanClause.Occur.FILTER)
            .build();
        int pending = searcher.count(pendingQuery);

        Query failedQuery = new BooleanQuery.Builder()
            .add(chunksQuery, BooleanClause.Occur.FILTER)
            .add(new TermQuery(new Term(
                    SchemaFields.CHUNK_EMBEDDING_STATUS,
                    SchemaFields.EMBEDDING_STATUS_FAILED)),
                BooleanClause.Occur.FILTER)
            .build();
        int failed = searcher.count(failedQuery);

        return new ChunkEmbeddingCounts(total, completed, pending, failed);
      });
    } catch (IOException e) {
      log.debug("Failed to query chunk embedding counts: {}", e.getMessage());
      return new ChunkEmbeddingCounts(0, 0, 0, 0);
    }
  }

  /**
   * Counts live chunk documents that actually carry a {@code chunk_vector} value (tempdoc 717) —
   * the artifact-truthful complement to {@link #queryChunkEmbeddingCounts()}, which counts the
   * {@code chunk_embedding_status} bookkeeping field and can therefore read COMPLETED while the
   * vector is absent (the F-032 "status lies" class). Reader-version cached like {@link
   * #getOrComputeCorpusProfile()} so the per-query serve-time gate does not re-iterate vectors on
   * every search.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public ChunkVectorPresence queryChunkVectorPresenceCount() {
    long currentVersion = getReaderVersion();
    ChunkVectorPresence cached = cachedChunkVectorPresence;
    if (cached != null && cachedChunkVectorPresenceVersion == currentVersion) {
      return cached;
    }
    ChunkVectorPresence computed = computeChunkVectorPresence();
    cachedChunkVectorPresence = computed;
    cachedChunkVectorPresenceVersion = currentVersion;
    return computed;
  }

  /**
   * Enumerates each leaf's {@code chunk_vector} {@link FloatVectorValues}, filtering deleted docs
   * via {@code liveDocs}. The KNN structures are not purged of tombstones until merge, so {@code
   * FloatVectorValues.size()} would overcount deleted-but-unmerged docs (tempdoc 717 §Derisk U2) —
   * a per-doc {@code liveDocs} check is required. {@code chunk_vector} is written only on chunk
   * docs, so a doc carrying it is a live chunk doc with a vector. Same complexity class as the
   * status {@code TermQuery} counts (walks only vector-bearing docs per segment, not all docs).
   */
  private ChunkVectorPresence computeChunkVectorPresence() {
    try {
      return bridge.withSearcher(searcher -> {
        int totalChunks = searcher.count(new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")));
        int vectorsPresent = 0;
        for (LeafReaderContext leaf : searcher.getIndexReader().leaves()) {
          FloatVectorValues values = leaf.reader().getFloatVectorValues(SchemaFields.CHUNK_VECTOR);
          if (values == null) {
            continue; // this segment indexed no chunk_vector
          }
          Bits liveDocs = leaf.reader().getLiveDocs();
          KnnVectorValues.DocIndexIterator iter = values.iterator();
          for (int doc = iter.nextDoc();
              doc != DocIdSetIterator.NO_MORE_DOCS;
              doc = iter.nextDoc()) {
            if (liveDocs == null || liveDocs.get(doc)) {
              vectorsPresent++;
            }
          }
        }
        return new ChunkVectorPresence(totalChunks, vectorsPresent);
      });
    } catch (IOException e) {
      log.debug("Failed to count chunk vector presence: {}", e.getMessage());
      return new ChunkVectorPresence(0, 0);
    }
  }

  /**
   * Queries doc-level embedding status counts for whole (non-chunk) documents.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public EmbeddingCounts queryEmbeddingCounts() {
    try {
      return bridge.withSearcher(searcher -> {
        Query wholeDocsQuery =
            new BooleanQuery.Builder()
                .add(new MatchAllDocsQuery(), BooleanClause.Occur.FILTER)
                .add(new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")),
                    BooleanClause.Occur.MUST_NOT)
                .build();
        int total = searcher.count(wholeDocsQuery);

        Query completedQuery = new BooleanQuery.Builder()
            .add(wholeDocsQuery, BooleanClause.Occur.FILTER)
            .add(new TermQuery(new Term(
                    SchemaFields.EMBEDDING_STATUS,
                    SchemaFields.EMBEDDING_STATUS_COMPLETED)),
                BooleanClause.Occur.FILTER)
            .build();
        int completed = searcher.count(completedQuery);

        Query pendingQuery = new BooleanQuery.Builder()
            .add(wholeDocsQuery, BooleanClause.Occur.FILTER)
            .add(new TermQuery(new Term(
                    SchemaFields.EMBEDDING_STATUS,
                    SchemaFields.EMBEDDING_STATUS_PENDING)),
                BooleanClause.Occur.FILTER)
            .build();
        int pending = searcher.count(pendingQuery);

        Query failedQuery = new BooleanQuery.Builder()
            .add(wholeDocsQuery, BooleanClause.Occur.FILTER)
            .add(new TermQuery(new Term(
                    SchemaFields.EMBEDDING_STATUS,
                    SchemaFields.EMBEDDING_STATUS_FAILED)),
                BooleanClause.Occur.FILTER)
            .build();
        int failed = searcher.count(failedQuery);

        return new EmbeddingCounts(total, completed, pending, failed);
      });
    } catch (IOException e) {
      log.debug("Failed to query embedding counts: {}", e.getMessage());
      return new EmbeddingCounts(0, 0, 0, 0);
    }
  }

  /**
   * Queries SPLADE feature extraction status counts for whole (non-chunk) documents.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public SpladeFeatureCounts querySpladeFeatureCounts() {
    try {
      return bridge.withSearcher(searcher -> {
        Query wholeDocsQuery =
            new BooleanQuery.Builder()
                .add(new MatchAllDocsQuery(), BooleanClause.Occur.FILTER)
                .add(new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")),
                    BooleanClause.Occur.MUST_NOT)
                .build();
        int total = searcher.count(wholeDocsQuery);

        // SPLADE's terminal-success vocabulary is two-valued: COMPLETED (postings written) and
        // COMPLETED_EMPTY (encode ran fine, produced no materialisable weight). Both mean "SPLADE is
        // done for this document", so coverage must sum them — counting only COMPLETED would leave
        // spladeCoveragePercent permanently below the 99.9 readiness bar every consumer gates on
        // (scripts/jseval/jseval/readiness.py) once pending hits zero. Mirrors the NER precedent in
        // IndexStatusOps#buildEnrichment.
        Query completedQuery = new BooleanQuery.Builder()
            .add(wholeDocsQuery, BooleanClause.Occur.FILTER)
            .add(new BooleanQuery.Builder()
                    .add(new TermQuery(new Term(
                            SchemaFields.SPLADE_STATUS,
                            SchemaFields.SPLADE_STATUS_COMPLETED)),
                        BooleanClause.Occur.SHOULD)
                    .add(new TermQuery(new Term(
                            SchemaFields.SPLADE_STATUS,
                            SchemaFields.SPLADE_STATUS_COMPLETED_EMPTY)),
                        BooleanClause.Occur.SHOULD)
                    .build(),
                BooleanClause.Occur.FILTER)
            .build();
        int completed = searcher.count(completedQuery);

        Query pendingQuery = new BooleanQuery.Builder()
            .add(wholeDocsQuery, BooleanClause.Occur.FILTER)
            .add(new TermQuery(new Term(
                    SchemaFields.SPLADE_STATUS,
                    SchemaFields.SPLADE_STATUS_PENDING)),
                BooleanClause.Occur.FILTER)
            .build();
        int pending = searcher.count(pendingQuery);

        Query failedQuery = new BooleanQuery.Builder()
            .add(wholeDocsQuery, BooleanClause.Occur.FILTER)
            .add(new TermQuery(new Term(
                    SchemaFields.SPLADE_STATUS,
                    SchemaFields.SPLADE_STATUS_FAILED)),
                BooleanClause.Occur.FILTER)
            .build();
        int failed = searcher.count(failedQuery);

        return new SpladeFeatureCounts(total, completed, pending, failed);
      });
    } catch (IOException e) {
      log.debug("Failed to query SPLADE feature counts: {}", e.getMessage());
      return new SpladeFeatureCounts(0, 0, 0, 0);
    }
  }

  /**
   * Queries SPLADE status counts for CHUNK documents (tempdoc 931 §E item 8) — the chunk-scoped twin
   * of {@link #querySpladeFeatureCounts()}, which counts whole documents only ({@code IS_CHUNK}
   * MUST_NOT) and therefore said nothing about the population the chunk-SPLADE retrieval leg scores
   * against.
   *
   * <p>The denominator is the chunk documents that CARRY {@code splade_status}, not every chunk:
   * with {@code rag.chunk_splade.enabled} off, {@code ChunkDocumentWriter} writes no status at all,
   * and an absent status field means the stage does not apply to that document (the post-798
   * convention {@link #countWithField} already encodes for every other stage). Counting every chunk
   * instead would pin coverage below 100% forever for chunks no lane can ever select.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public SpladeFeatureCounts queryChunkSpladeCounts() {
    try {
      return bridge.withSearcher(searcher -> {
        Query chunkDocs =
            new BooleanQuery.Builder()
                .add(new MatchAllDocsQuery(), BooleanClause.Occur.FILTER)
                .add(new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")),
                    BooleanClause.Occur.FILTER)
                .build();
        int total = countWithField(searcher, chunkDocs, SchemaFields.SPLADE_STATUS);
        // Terminal success is two-valued, exactly as in querySpladeFeatureCounts: COMPLETED_EMPTY
        // means the encode ran and produced no materialisable weight, which is still "done".
        int completed = countSettled(searcher, chunkDocs, SchemaFields.SPLADE_STATUS,
            SchemaFields.SPLADE_STATUS_COMPLETED, SchemaFields.SPLADE_STATUS_COMPLETED_EMPTY);
        int pending = countSettled(searcher, chunkDocs, SchemaFields.SPLADE_STATUS,
            SchemaFields.SPLADE_STATUS_PENDING);
        int failed = countSettled(searcher, chunkDocs, SchemaFields.SPLADE_STATUS,
            SchemaFields.SPLADE_STATUS_FAILED);
        return new SpladeFeatureCounts(total, completed, pending, failed);
      });
    } catch (IOException e) {
      log.debug("Failed to query chunk SPLADE counts: {}", e.getMessage());
      return new SpladeFeatureCounts(0, 0, 0, 0);
    }
  }

  /**
   * Queries enrichment coverage for documents under one watched root's path prefix (tempdoc 813
   * §1c) — the per-folder counterpart of {@link #queryEmbeddingCounts()} / {@link
   * #querySpladeFeatureCounts()} / {@link #queryChunkEmbeddingCounts()}, so a Library row can read
   * "N% enriched" instead of only the index-wide number.
   *
   * <p>Boundary safety: the prefix is normalized through {@link
   * QueryFilterBuilder#normalizePathPrefix(String)} — the same normalization the production
   * path-prefix search filter uses (lowercase on Windows, trailing separator) — so {@code
   * C:\foo} cannot match the sibling {@code C:\foobar}. A blank prefix is rejected up front
   * rather than normalized, because normalization would turn it into a bare separator that
   * matches the whole index.
   *
   * <p>Chunk documents are counted by the same PATH prefix: a chunk's {@code path} field is its
   * parent's normalized absolute file path ({@code ChunkDocumentWriter} writes {@code
   * SchemaFields.PATH = parentDocId}), not an opaque id.
   *
   * <p>Reader-version cached per prefix (see {@link #getOrComputeCorpusProfile()}). Honest
   * accounting of what that buys: the reader version changes on every NRT reopen, not only on
   * commit — the reopen thread runs on a 50-500 ms target ({@code CommitOps#resumeNrtRefresh}) —
   * so during active ingest this cache mostly MISSES and nearly every Library tick recomputes.
   * It is kept because the measured cost of a recompute is small (warm medians &lt; 0.3 ms at
   * 200k docs, 813 §13) and because the cache does pay off in the steady state the Library
   * mostly shows: an idle index, where the reader version is stable between commits.
   *
   * <p>Only successful computations are cached — a failed query returns the EMPTY sentinel
   * without poisoning the entry for this reader version, so a transient IO failure cannot pin a
   * folder at "0 enriched" until the next reopen.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public RootCoverageCounts queryRootCoverageCounts(String pathPrefix) {
    if (pathPrefix == null || pathPrefix.isBlank()) {
      return RootCoverageCounts.EMPTY;
    }
    String normalized = QueryFilterBuilder.normalizePathPrefix(pathPrefix);
    long currentVersion = getReaderVersion();
    CachedCoverage cached = cachedRootCoverage.get(normalized);
    if (cached != null && cached.readerVersion() == currentVersion) {
      return cached.counts();
    }
    RootCoverageCounts computed = computeRootCoverage(normalized);
    if (computed == null) {
      // The query failed; report absence and leave the cache alone.
      return RootCoverageCounts.EMPTY;
    }
    if (cachedRootCoverage.size() >= MAX_CACHED_COVERAGE_PREFIXES
        && !cachedRootCoverage.containsKey(normalized)) {
      // Coarse bound: watched roots number in the tens, so eviction is a cold-path event. Dropping
      // the whole map costs one recompute per live root instead of carrying an LRU.
      cachedRootCoverage.clear();
    }
    cachedRootCoverage.put(normalized, new CachedCoverage(currentVersion, computed));
    return computed;
  }

  /** @return the counts, or {@code null} when the query failed (never a zeroed stand-in). */
  private RootCoverageCounts computeRootCoverage(String normalizedPrefix) {
    try {
      return bridge.withSearcher(searcher -> {
        Query prefixQuery = new PrefixQuery(new Term(SchemaFields.PATH, normalizedPrefix));
        Query isChunk = new TermQuery(new Term(SchemaFields.IS_CHUNK, "true"));

        Query parentDocs = new BooleanQuery.Builder()
            .add(prefixQuery, BooleanClause.Occur.FILTER)
            .add(isChunk, BooleanClause.Occur.MUST_NOT)
            .build();

        Query chunkDocs = new BooleanQuery.Builder()
            .add(prefixQuery, BooleanClause.Occur.FILTER)
            .add(isChunk, BooleanClause.Occur.FILTER)
            .build();

        // Each stage's denominator is the documents that CARRY its status field — an absent
        // status field means the stage does not apply to that document (post-798), and a
        // document the backfill can never select must not sit in a denominator forever.
        int embeddingTotal = countWithField(searcher, parentDocs, SchemaFields.EMBEDDING_STATUS);
        int spladeTotal = countWithField(searcher, parentDocs, SchemaFields.SPLADE_STATUS);
        int nerTotal = countWithField(searcher, parentDocs, SchemaFields.NER_STATUS);
        int chunkTotal = countWithField(searcher, chunkDocs, SchemaFields.CHUNK_EMBEDDING_STATUS);

        // embedding_status has no COMPLETED_EMPTY member (SchemaFields declares PENDING /
        // COMPLETED / FAILED only), so its terminal set is two-valued; splade_status and
        // ner_status both add COMPLETED_EMPTY as a terminal success.
        int parentEmbedding = countSettled(searcher, parentDocs, SchemaFields.EMBEDDING_STATUS,
            SchemaFields.EMBEDDING_STATUS_COMPLETED, SchemaFields.EMBEDDING_STATUS_FAILED);
        int parentSplade = countSettled(searcher, parentDocs, SchemaFields.SPLADE_STATUS,
            SchemaFields.SPLADE_STATUS_COMPLETED, SchemaFields.SPLADE_STATUS_COMPLETED_EMPTY,
            SchemaFields.SPLADE_STATUS_FAILED);
        int parentNer = countSettled(searcher, parentDocs, SchemaFields.NER_STATUS,
            SchemaFields.NER_STATUS_COMPLETED, SchemaFields.NER_STATUS_COMPLETED_EMPTY,
            SchemaFields.NER_STATUS_FAILED);
        int chunkSettled = countSettled(searcher, chunkDocs, SchemaFields.CHUNK_EMBEDDING_STATUS,
            SchemaFields.EMBEDDING_STATUS_COMPLETED, SchemaFields.EMBEDDING_STATUS_FAILED);

        return new RootCoverageCounts(
            embeddingTotal, parentEmbedding,
            spladeTotal, parentSplade,
            nerTotal, parentNer,
            chunkTotal, chunkSettled);
      });
    } catch (IOException e) {
      log.debug("Failed to compute root coverage counts: {}", e.getMessage());
      return null;
    }
  }

  /**
   * Index-wide per-stage completeness counts (tempdoc 821 §3-C3) — the whole-index counterpart of
   * {@link #queryRootCoverageCounts(String)}'s per-root numbers, reusing the same {@link
   * #countWithField}/{@link #countSettled} primitives so the two cannot drift.
   *
   * <p>Every count comes from ONE searcher acquisition, so all four stages describe the same
   * reader snapshot and their buckets can be subtracted from one another. The whole result is
   * reader-version cached (like {@link #getOrComputeCorpusProfile()}) because the status path is
   * polled every couple of seconds during exactly the window this measures.
   *
   * <p><b>Scoping — the property that makes the numbers subtractable.</b> {@code expected} is the
   * documents that CARRY the stage's status field, not every document: an absent status field
   * means the stage does not apply to that document (post-798), and a document the backfill can
   * never select must not sit in a denominator forever. {@code artifactPresent} is counted over
   * that SAME population — a doc carrying the artifact but no status field is outside the stage
   * entirely, and counting it would let an installed-base index written before the status field
   * existed report more present than expected.
   *
   * <p>{@code artifactPresent} additionally excludes terminal-FAILED documents. A FAILED write is
   * an RMW that resets the status while LEAVING the vector in place ({@code EmbeddingBackfillOps}),
   * so "has an artifact" and "is failed" genuinely overlap on disk; counting such a doc in both
   * would make {@code expected - present - failed} understate the repair backlog by one per
   * overlap. With the exclusion, and because a status field is single-valued, {@code
   * artifactPresent}, {@code failed} and the remainder PARTITION {@code expected} exactly — so the
   * projection needs no clamp, and a violation cannot hide behind a floor.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   *
   * @return the counts, or {@link StageCompletenessCounts#EMPTY} when the reader could not be read
   */
  public StageCompletenessCounts queryStageCompletenessCounts() {
    long currentVersion = getReaderVersion();
    StageCompletenessCounts cached = cachedStageCompleteness;
    if (cached != null && cachedStageCompletenessVersion == currentVersion) {
      return cached;
    }
    StageCompletenessCounts computed = computeStageCompleteness();
    if (computed == null) {
      // The query failed; report absence without poisoning the entry for this reader version.
      return StageCompletenessCounts.EMPTY;
    }
    cachedStageCompleteness = computed;
    cachedStageCompletenessVersion = currentVersion;
    return computed;
  }

  /** @return the counts, or {@code null} when the query failed (never a zeroed stand-in). */
  private StageCompletenessCounts computeStageCompleteness() {
    try {
      return bridge.withSearcher(searcher -> {
        Query isChunk = new TermQuery(new Term(SchemaFields.IS_CHUNK, "true"));
        Query parentDocs = new BooleanQuery.Builder()
            .add(new MatchAllDocsQuery(), BooleanClause.Occur.FILTER)
            .add(isChunk, BooleanClause.Occur.MUST_NOT)
            .build();
        Query chunkDocs = new BooleanQuery.Builder()
            .add(new MatchAllDocsQuery(), BooleanClause.Occur.FILTER)
            .add(isChunk, BooleanClause.Occur.FILTER)
            .build();

        // embedding_status has no COMPLETED_EMPTY member (SchemaFields declares PENDING /
        // COMPLETED / FAILED only), so its terminal-success set is one-valued; splade_status and
        // ner_status both add COMPLETED_EMPTY as a terminal success.
        StageCounts embedding = new StageCounts(
            countWithField(searcher, parentDocs, SchemaFields.EMBEDDING_STATUS),
            countSettled(searcher, parentDocs, SchemaFields.EMBEDDING_STATUS,
                SchemaFields.EMBEDDING_STATUS_COMPLETED),
            countSettled(searcher, parentDocs, SchemaFields.EMBEDDING_STATUS,
                SchemaFields.EMBEDDING_STATUS_FAILED),
            countArtifactPresent(searcher, parentDocs, SchemaFields.EMBEDDING_STATUS,
                SchemaFields.EMBEDDING_STATUS_FAILED, SchemaFields.VECTOR));
        StageCounts splade = new StageCounts(
            countWithField(searcher, parentDocs, SchemaFields.SPLADE_STATUS),
            countSettled(searcher, parentDocs, SchemaFields.SPLADE_STATUS,
                SchemaFields.SPLADE_STATUS_COMPLETED, SchemaFields.SPLADE_STATUS_COMPLETED_EMPTY),
            countSettled(searcher, parentDocs, SchemaFields.SPLADE_STATUS,
                SchemaFields.SPLADE_STATUS_FAILED),
            // No countable artifact: the `splade` field is a postings/feature field with
            // docValues:false (fields.v1.json), so FieldExistsQuery cannot see it. The consumer
            // declares the weaker STATUS tier rather than implying a verification it cannot do.
            0);
        StageCounts ner = new StageCounts(
            countWithField(searcher, parentDocs, SchemaFields.NER_STATUS),
            countSettled(searcher, parentDocs, SchemaFields.NER_STATUS,
                SchemaFields.NER_STATUS_COMPLETED, SchemaFields.NER_STATUS_COMPLETED_EMPTY),
            countSettled(searcher, parentDocs, SchemaFields.NER_STATUS,
                SchemaFields.NER_STATUS_FAILED),
            0); // NER writes no countable per-document artifact.
        StageCounts chunkEmbedding = new StageCounts(
            countWithField(searcher, chunkDocs, SchemaFields.CHUNK_EMBEDDING_STATUS),
            countSettled(searcher, chunkDocs, SchemaFields.CHUNK_EMBEDDING_STATUS,
                SchemaFields.EMBEDDING_STATUS_COMPLETED),
            countSettled(searcher, chunkDocs, SchemaFields.CHUNK_EMBEDDING_STATUS,
                SchemaFields.EMBEDDING_STATUS_FAILED),
            countArtifactPresent(searcher, chunkDocs, SchemaFields.CHUNK_EMBEDDING_STATUS,
                SchemaFields.EMBEDDING_STATUS_FAILED, SchemaFields.CHUNK_VECTOR));

        return new StageCompletenessCounts(embedding, splade, ner, chunkEmbedding);
      });
    } catch (IOException e) {
      log.debug("Failed to compute stage completeness counts: {}", e.getMessage());
      return null;
    }
  }

  /**
   * Counts docs in {@code scope} that carry {@code statusField}, are NOT at {@code failedValue},
   * and actually carry {@code artifactField} — the artifact-truthful numerator for a stage whose
   * artifact is countable (tempdoc 821 §3-C3, the F-032 "status lies" containment).
   *
   * <p>{@link FieldExistsQuery} sees a KNN vector field (Lucene 10 supports doc values, points,
   * norms and vectors), and {@code IndexSearcher.count} intersects with {@code liveDocs} — so a
   * deleted-but-unmerged doc cannot inflate this even though the KNN structures keep its vector
   * until merge (tempdoc 717 §Derisk U2). A segment that never indexed the field matches nothing.
   */
  private static int countArtifactPresent(
      IndexSearcher searcher,
      Query scope,
      String statusField,
      String failedValue,
      String artifactField)
      throws IOException {
    return searcher.count(new BooleanQuery.Builder()
        .add(scope, BooleanClause.Occur.FILTER)
        .add(new FieldExistsQuery(statusField), BooleanClause.Occur.FILTER)
        .add(new FieldExistsQuery(artifactField), BooleanClause.Occur.FILTER)
        .add(new TermQuery(new Term(statusField, failedValue)), BooleanClause.Occur.MUST_NOT)
        .build());
  }

  /**
   * Counts docs matching {@code scope} that carry {@code statusField} at all. Every status field
   * is docValues-backed (the {@code fields.v1.json} catalog), which is what makes {@link
   * FieldExistsQuery} usable here; a segment that never saw the field contributes zero.
   */
  private static int countWithField(IndexSearcher searcher, Query scope, String statusField)
      throws IOException {
    return searcher.count(new BooleanQuery.Builder()
        .add(scope, BooleanClause.Occur.FILTER)
        .add(new FieldExistsQuery(statusField), BooleanClause.Occur.FILTER)
        .build());
  }

  /** Counts docs matching {@code scope} whose {@code statusField} holds any terminal value. */
  private static int countSettled(
      IndexSearcher searcher,
      Query scope,
      String statusField,
      String... terminalValues)
      throws IOException {
    BooleanQuery.Builder terminal = new BooleanQuery.Builder();
    for (String value : terminalValues) {
      terminal.add(new TermQuery(new Term(statusField, value)), BooleanClause.Occur.SHOULD);
    }
    return searcher.count(new BooleanQuery.Builder()
        .add(scope, BooleanClause.Occur.FILTER)
        .add(terminal.build(), BooleanClause.Occur.FILTER)
        .build());
  }

  /**
   * Returns the cached corpus profile, recomputing when the index version changes.
   *
   * <p>Uses {@link DirectoryReader#getVersion()} for O(1) staleness detection — the profile is
   * automatically invalidated after any commit without requiring explicit cache clearing.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public CorpusProfile getOrComputeCorpusProfile() {
    long currentVersion = getReaderVersion();
    CorpusProfile p = cachedProfile;
    if (p != null && cachedProfileVersion == currentVersion) {
      return p;
    }
    p = computeCorpusProfile();
    cachedProfile = p;
    cachedProfileVersion = currentVersion;
    return p;
  }

  private long getReaderVersion() {
    try {
      return bridge.withSearcher(
          s -> {
            if (s.getIndexReader() instanceof DirectoryReader dr) {
              return dr.getVersion();
            }
            return -1L;
          });
    } catch (IOException e) {
      return -1L;
    }
  }

  /**
   * Computes a corpus-level profile from {@code parent_token_count} DocValues. Iterates all
   * non-chunk parent documents in the index, building a bucket histogram of token counts. Used to
   * gate chunk-aware merge for short-document corpora where chunks ≈ documents and branch fusion
   * injects noise (tempdoc 309 §26).
   *
   * <p>Cost: O(N parent docs), typically 5-20 ms for 100K documents. Should be cached.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public CorpusProfile computeCorpusProfile() {
    try {
      return bridge.withSearcher(searcher -> {
        IndexReader reader = searcher.getIndexReader();

        // Count chunk documents
        long chunkCount = searcher.count(
            new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")));

        // Build a query for non-chunk documents only
        Query nonChunkQuery = new BooleanQuery.Builder()
            .add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST)
            .add(new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")),
                BooleanClause.Occur.MUST_NOT)
            .build();
        Query rewritten = searcher.rewrite(nonChunkQuery);
        Weight weight = searcher.createWeight(rewritten, ScoreMode.COMPLETE_NO_SCORES, 1.0f);

        long parentCount = 0;
        long totalTokens = 0;
        long docsWithTokens = 0;
        int[] buckets = new int[CorpusProfile.BUCKET_BOUNDARIES.length + 1];

        for (LeafReaderContext leaf : reader.leaves()) {
          var scorer = weight.scorer(leaf);
          if (scorer == null) continue;

          Bits liveDocs = leaf.reader().getLiveDocs();
          NumericDocValues ptcDv =
              leaf.reader().getNumericDocValues(SchemaFields.PARENT_TOKEN_COUNT);

          var twoPhase = scorer.twoPhaseIterator();
          DocIdSetIterator it =
              (twoPhase == null) ? scorer.iterator() : twoPhase.approximation();
          int doc;
          while ((doc = it.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
            // Skip deleted-but-unmerged docs. This raw scorer loop does not go through
            // IndexSearcher.searchLeaf (which applies liveDocs), so without this check parentCount
            // and the token buckets would include deleted parents while chunkCount above
            // (searcher.count) already excludes them — inflating parentCount, deflating chunkRate,
            // and skewing the token median → the short/long mis-classification class tempdoc 717
            // fixed, here triggered by deletions instead of the SPLADE-load race (717 followup).
            if (liveDocs != null && !liveDocs.get(doc)) continue;
            if (twoPhase != null && !twoPhase.matches()) continue;
            parentCount++;
            if (ptcDv != null && ptcDv.advanceExact(doc)) {
              long tc = ptcDv.longValue();
              totalTokens += tc;
              docsWithTokens++;
              buckets[CorpusProfile.bucketFor(tc)]++;
            }
          }
        }

        return new CorpusProfile(parentCount, chunkCount, totalTokens, docsWithTokens, buckets);
      });
    } catch (IOException e) {
      log.debug("Failed to compute corpus profile: {}", e.getMessage());
      return CorpusProfile.EMPTY;
    }
  }
}
