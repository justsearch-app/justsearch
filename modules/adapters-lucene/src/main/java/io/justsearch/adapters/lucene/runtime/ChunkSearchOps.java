/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static io.justsearch.adapters.lucene.runtime.HybridFusionUtils.fuseWithRRF;
import static io.justsearch.adapters.lucene.runtime.LuceneRuntimeUtils.classifyIOException;
import static io.justsearch.adapters.lucene.runtime.LuceneRuntimeUtils.termInSetFilter;
import io.justsearch.configuration.resolved.ResolvedConfig;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchHit;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchResult;
import io.justsearch.indexing.SchemaFields;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RuntimeSearchSort;
import java.util.stream.Collectors;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Internal chunk-search collaborator for {@link LuceneLifecycleManager}.
 *
 * <p>Encapsulates chunk BM25 search, chunk vector search, doc-first hybrid, and true chunk-level
 * hybrid (Phase 6). Facade callbacks for doc-level hybrid and general search are injected via
 * functional interfaces to avoid circular coupling.
 *
 * <p>Lifecycle: instances are created in {@code applyComponents()} and discarded on {@code close()}.
 * Access from the runtime must go through a volatile snapshot to ensure visibility across threads.
 */
public final class ChunkSearchOps {
  private static final Logger log = LoggerFactory.getLogger(ChunkSearchOps.class);

  private record PendingChunkHit(String docId, float score, Map<String, String> fields) {}

  private final RuntimeSession session;
  private final SearcherBridge bridge;
  private final HybridSearchOps hybridSearchOps;
  private final ReadPathOps readPathOps;
  private final String idField;

  ChunkSearchOps(
      RuntimeSession session,
      SearcherBridge bridge,
      HybridSearchOps hybridSearchOps,
      ReadPathOps readPathOps,
      String idField) {
    this.session = session;
    this.bridge = bridge;
    this.hybridSearchOps = hybridSearchOps;
    this.readPathOps = readPathOps;
    this.idField = idField;
  }

  /**
   * Runs a bare (non-kNN) chunk or full-document search with the SAME deterministic order the
   * whole-document read path already uses: score DESC, then the stable id ASC as the tie-break
   * ({@code LuceneRuntimeUtils.buildRuntimeSort(RELEVANCE, idField)} →
   * {@code new Sort(FIELD_SCORE, new SortField(idField, STRING, false))}).
   *
   * <p>Before this existed these legs called {@code searcher.search(query, n)}, whose only
   * tie-break is Lucene's INTERNAL docId — a number a different segment layout renumbers. Two
   * index builds of the same documents therefore returned different members of a BM25/SPLADE tie
   * group, and because {@code SearchExecutor#collapseChunkHitsToParents} is first-seen-wins and
   * the fused {@code totalHits} is the candidate-union size, one swapped chunk moved both the
   * evidence selection and the reported hit count (lane F PR 0b; the whole-document leg was
   * already sorted this way, which is why only the chunk legs drifted).
   *
   * <p>{@code doDocScores = true} keeps every returned score exactly what the bare overload
   * produced; the {@code totalHitsThreshold} is Lucene's same default for both collector
   * managers, so only the order of equal-scoring hits changes.
   */
  private org.apache.lucene.search.TopDocs searchWithStableTieBreak(
      org.apache.lucene.search.IndexSearcher searcher, Query query, int limit)
      throws IOException {
    return searcher.search(
        query, limit, LuceneRuntimeUtils.buildRuntimeSort(RuntimeSearchSort.RELEVANCE, idField), true);
  }

  /**
   * Finds parent document IDs that match a filter query.
   *
   * <p>Used for two-stage RAG retrieval: first find matching parent docs via document-level
   * filters (entity, metadata, path, date), then scope chunk search to those docs.
   * Excludes chunk documents from the search.
   *
   * @param filter Lucene filter query (from {@code QueryFilterBuilder.buildFilterQueryOnly()})
   * @param maxDocs maximum number of parent doc IDs to return
   * @return set of matching parent document IDs (may be empty, never null)
   */
  public Set<String> findMatchingParentDocIds(Query filter, int maxDocs) {
    BooleanQuery q = new BooleanQuery.Builder()
        .add(filter, BooleanClause.Occur.MUST)
        .add(new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")),
            BooleanClause.Occur.MUST_NOT)
        .build();
    SearchResult result = readPathOps.search(q, maxDocs, Set.of(SchemaFields.DOC_ID),
        RuntimeSearchSort.RELEVANCE, null);
    return result.hits().stream()
        .map(SearchHit::docId)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /**
   * Searches for relevant chunks within the specified document IDs using BM25.
   *
   * <p>Used for RAG context retrieval. Searches the chunk_content field and filters by
   * parent_doc_id and is_chunk=true.
   */
  public SearchResult searchChunksForDocs(String queryText, Set<String> docIds, int limit) {
    if (queryText == null || queryText.isBlank() || docIds == null || docIds.isEmpty()) {
      return new SearchResult(List.of(), 0, 0);
    }
    final int effectiveLimit = limit <= 0 ? 5 : limit;

    Query contentQuery;
    try {
      org.apache.lucene.queryparser.classic.QueryParser parser =
          new org.apache.lucene.queryparser.classic.QueryParser(
              SchemaFields.CHUNK_CONTENT, session.snapshot.indexAnalyzer());
      parser.setDefaultOperator(
          org.apache.lucene.queryparser.classic.QueryParser.Operator.OR);
      contentQuery =
          parser.parse(
              org.apache.lucene.queryparser.classic.QueryParser.escape(queryText));
    } catch (org.apache.lucene.queryparser.classic.ParseException e) {
      log.warn("Failed to parse chunk query", e);
      log.debug("Failed query text: {}", queryText);
      return new SearchResult(List.of(), 0, 0);
    }

    long startTime = System.currentTimeMillis();
    try {
      return bridge.withSearcher(
          searcher -> {
            Query docFilter = termInSetFilter(SchemaFields.PARENT_DOC_ID, docIds);

            BooleanQuery.Builder queryBuilder = new BooleanQuery.Builder();
            queryBuilder.add(contentQuery, BooleanClause.Occur.MUST);
            queryBuilder.add(docFilter, BooleanClause.Occur.FILTER);
            queryBuilder.add(
                new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")),
                BooleanClause.Occur.FILTER);

            org.apache.lucene.search.TopDocs topDocs =
                searchWithStableTieBreak(searcher, queryBuilder.build(), effectiveLimit);

            return buildChunkHits(searcher, topDocs, startTime);
          });
    } catch (IOException e) {
      throw new IndexRuntimeIOException(classifyIOException(e), "Chunk search failed", e);
    }
  }

  /**
   * Searches chunks using BM25 with optional doc_id scoping and runtime filters.
   *
   * <p>When docIds is non-empty, restricts to chunks within those documents.
   * When docIds is empty, searches all chunks (open retrieval).
   * The additionalFilter is combined as a FILTER clause (e.g., entity/path/date filters
   * built by {@link QueryFilterBuilder}).
   *
   * @param queryText the query text for BM25 ranking
   * @param docIds parent document IDs to scope (empty = search all)
   * @param limit maximum number of chunk results
   * @param additionalFilter optional Lucene filter query (may be null)
   */
  public SearchResult searchChunksFiltered(
      String queryText, Set<String> docIds, int limit, Query additionalFilter) {
    if (queryText == null || queryText.isBlank()) {
      return new SearchResult(List.of(), 0, 0);
    }
    final int effectiveLimit = limit <= 0 ? 5 : limit;

    Query contentQuery;
    try {
      org.apache.lucene.queryparser.classic.QueryParser parser =
          new org.apache.lucene.queryparser.classic.QueryParser(
              SchemaFields.CHUNK_CONTENT, session.snapshot.indexAnalyzer());
      parser.setDefaultOperator(
          org.apache.lucene.queryparser.classic.QueryParser.Operator.OR);
      contentQuery =
          parser.parse(
              org.apache.lucene.queryparser.classic.QueryParser.escape(queryText));
    } catch (org.apache.lucene.queryparser.classic.ParseException e) {
      log.warn("Failed to parse chunk query", e);
      return new SearchResult(List.of(), 0, 0);
    }

    long startTime = System.currentTimeMillis();
    try {
      return bridge.withSearcher(
          searcher -> {
            BooleanQuery.Builder queryBuilder = new BooleanQuery.Builder();
            queryBuilder.add(contentQuery, BooleanClause.Occur.MUST);
            queryBuilder.add(
                new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")),
                BooleanClause.Occur.FILTER);

            // Doc_id scoping (optional — empty means search all chunks)
            if (docIds != null && !docIds.isEmpty()) {
              queryBuilder.add(
                  termInSetFilter(SchemaFields.PARENT_DOC_ID, docIds),
                  BooleanClause.Occur.FILTER);
            }

            // Additional runtime filters (entity, path, date, file_kind)
            if (additionalFilter != null) {
              queryBuilder.add(additionalFilter, BooleanClause.Occur.FILTER);
            }

            org.apache.lucene.search.TopDocs topDocs =
                searchWithStableTieBreak(searcher, queryBuilder.build(), effectiveLimit);
            return buildChunkHits(searcher, topDocs, startTime);
          });
    } catch (IOException e) {
      throw new IndexRuntimeIOException(classifyIOException(e), "Filtered chunk search failed", e);
    }
  }

  /**
   * Searches all chunks using BM25, without scoping to specific parent documents.
   *
   * <p>Used for interactive chunk-aware search. Searches the chunk_content field with an
   * is_chunk=true filter but no parent_doc_id constraint.
   *
   * <p>Query syntax (tempdoc 821 §P): this leg treats ALL query text as literal — operators are
   * escaped, never parsed — and deliberately has no LUCENE branch, unlike the doc-level lexical leg
   * ({@code TextQueryOps#searchText}), which honours the request's {@code query_syntax}. That is not
   * a gap: a {@code query_syntax: "lucene"} request never reaches this method, because the planner
   * skips chunk merge outright for LUCENE requests ({@code SearchPlanner#planChunkMerge} →
   * {@code SKIPPED_QUERY_SYNTAX}), and the only caller is that chunk-merge path
   * ({@code SearchExecutor#executeChunkBranchFusion}). Adding a parse branch here would be
   * unreachable code
   * pretending to be a feature. If chunk merge is ever made LUCENE-eligible, this single-field
   * escape is the site to revisit.
   *
   * @param queryText the query text for BM25 ranking
   * @param limit maximum number of chunk results
   * @param additionalFilter optional Lucene filter query (e.g. mime/language constraints)
   */
  public SearchResult searchChunksText(String queryText, int limit, Query additionalFilter) {
    if (queryText == null || queryText.isBlank()) {
      return new SearchResult(List.of(), 0, 0);
    }
    final int effectiveLimit = limit <= 0 ? 10 : limit;

    Query contentQuery;
    try {
      org.apache.lucene.queryparser.classic.QueryParser parser =
          new org.apache.lucene.queryparser.classic.QueryParser(
              SchemaFields.CHUNK_CONTENT, session.snapshot.indexAnalyzer());
      parser.setDefaultOperator(
          org.apache.lucene.queryparser.classic.QueryParser.Operator.OR);
      contentQuery =
          parser.parse(
              org.apache.lucene.queryparser.classic.QueryParser.escape(queryText));
    } catch (org.apache.lucene.queryparser.classic.ParseException e) {
      log.warn("Failed to parse chunk query", e);
      log.debug("Failed query text: {}", queryText);
      return new SearchResult(List.of(), 0, 0);
    }

    long startTime = System.currentTimeMillis();
    try {
      return bridge.withSearcher(
          searcher -> {
            BooleanQuery.Builder queryBuilder = new BooleanQuery.Builder();
            queryBuilder.add(contentQuery, BooleanClause.Occur.MUST);
            queryBuilder.add(
                new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")),
                BooleanClause.Occur.FILTER);
            if (additionalFilter != null) {
              queryBuilder.add(additionalFilter, BooleanClause.Occur.FILTER);
            }

            org.apache.lucene.search.TopDocs topDocs =
                searchWithStableTieBreak(searcher, queryBuilder.build(), effectiveLimit);

            return buildChunkHits(searcher, topDocs, startTime);
          });
    } catch (IOException e) {
      throw new IndexRuntimeIOException(classifyIOException(e), "Chunk text search failed", e);
    }
  }

  /**
   * Searches all chunks using SPLADE sparse vectors (Score-max pattern).
   *
   * <p>Builds a FeatureField query on the {@code splade} field (which exists on chunk documents
   * via backfill), filtered by {@code is_chunk=true}. Used for chunk-level SPLADE retrieval;
   * results are typically collapsed by parent document via {@code collapseByParent()} to achieve
   * Score-max aggregation.
   *
   * @param queryWeights SPLADE sparse vector mapping tokens to weights
   * @param limit maximum number of chunk results
   * @param additionalFilter optional Lucene filter query (e.g. mime/language constraints)
   * @return chunk search results ordered by SPLADE score
   */
  public SearchResult searchChunksSplade(
      Map<String, Float> queryWeights, int limit, Query additionalFilter) {
    if (queryWeights == null || queryWeights.isEmpty()) {
      return new SearchResult(List.of(), 0, 0);
    }
    final int effectiveLimit = limit <= 0 ? 10 : limit;

    var spladeBuilder = new BooleanQuery.Builder();
    for (var entry : queryWeights.entrySet()) {
      spladeBuilder.add(
          org.apache.lucene.document.FeatureField.newLinearQuery(
              SchemaFields.SPLADE, entry.getKey(), entry.getValue()),
          BooleanClause.Occur.SHOULD);
    }
    Query spladeQuery = spladeBuilder.build();

    long startTime = System.currentTimeMillis();
    try {
      return bridge.withSearcher(
          searcher -> {
            BooleanQuery.Builder queryBuilder = new BooleanQuery.Builder();
            queryBuilder.add(spladeQuery, BooleanClause.Occur.MUST);
            queryBuilder.add(
                new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")),
                BooleanClause.Occur.FILTER);
            if (additionalFilter != null) {
              queryBuilder.add(additionalFilter, BooleanClause.Occur.FILTER);
            }

            org.apache.lucene.search.TopDocs topDocs =
                searchWithStableTieBreak(searcher, queryBuilder.build(), effectiveLimit);

            return buildChunkHits(searcher, topDocs, startTime);
          });
    } catch (IOException e) {
      throw new IndexRuntimeIOException(classifyIOException(e), "Chunk SPLADE search failed", e);
    }
  }

  /** Extracts chunk metadata, then reconstructs text with one stored-content read per parent. */
  private SearchResult buildChunkHits(
      org.apache.lucene.search.IndexSearcher searcher,
      org.apache.lucene.search.TopDocs topDocs,
      long startTime)
      throws IOException {
    org.apache.lucene.index.StoredFields storedFields = searcher.storedFields();

    List<SearchHit> hits = new ArrayList<>();
    List<PendingChunkHit> pending = new ArrayList<>(topDocs.scoreDocs.length);
    Map<String, DocumentFieldOps.ChunkSlice> chunkSlices = new LinkedHashMap<>();
    Set<String> storedAllowlist =
        Set.of(
            SchemaFields.DOC_ID,
            SchemaFields.PARENT_DOC_ID,
            SchemaFields.CHUNK_INDEX,
            SchemaFields.CHUNK_TOTAL,
            SchemaFields.CHUNK_START_CHAR,
            SchemaFields.CHUNK_END_CHAR,
            // Tempdoc 931 §E item 5: the parent revision the offsets address, so the text
            // reconstruction below can refuse a parent that has been rewritten since.
            SchemaFields.CHUNK_PARENT_CONTENT_SHA256,
            SchemaFields.CHUNK_START_LINE,
            SchemaFields.CHUNK_END_LINE,
            SchemaFields.CHUNK_HEADING_TEXT,
            SchemaFields.CHUNK_HEADING_LEVEL,
            SchemaFields.PARENT_TOKEN_COUNT);
    for (org.apache.lucene.search.ScoreDoc scoreDoc : topDocs.scoreDocs) {
      Map<String, String> docFields =
          SearchResultFormatter.extractFromStoredFields(
              storedFields, scoreDoc.doc, false, storedAllowlist);
      String chunkDocId =
          docFields.getOrDefault(SchemaFields.DOC_ID, "chunk-" + scoreDoc.doc);

      Map<String, String> fields = new HashMap<>();
      fields.put(SchemaFields.DOC_ID, chunkDocId);
      fields.put(
          SchemaFields.PARENT_DOC_ID,
          docFields.getOrDefault(SchemaFields.PARENT_DOC_ID, ""));
      fields.put(
          SchemaFields.CHUNK_INDEX, docFields.getOrDefault(SchemaFields.CHUNK_INDEX, "0"));
      fields.put(
          SchemaFields.CHUNK_TOTAL, docFields.getOrDefault(SchemaFields.CHUNK_TOTAL, "1"));
      fields.put(
          SchemaFields.CHUNK_START_CHAR,
          docFields.getOrDefault(SchemaFields.CHUNK_START_CHAR, "0"));
      fields.put(
          SchemaFields.CHUNK_END_CHAR,
          docFields.getOrDefault(SchemaFields.CHUNK_END_CHAR, "0"));
      fields.put(
          SchemaFields.CHUNK_START_LINE,
          docFields.getOrDefault(SchemaFields.CHUNK_START_LINE, "0"));
      fields.put(
          SchemaFields.CHUNK_END_LINE,
          docFields.getOrDefault(SchemaFields.CHUNK_END_LINE, "0"));
      fields.put(
          SchemaFields.CHUNK_HEADING_TEXT,
          docFields.getOrDefault(SchemaFields.CHUNK_HEADING_TEXT, ""));
      fields.put(
          SchemaFields.CHUNK_HEADING_LEVEL,
          docFields.getOrDefault(SchemaFields.CHUNK_HEADING_LEVEL, "0"));
      String parentTokenCount = docFields.get(SchemaFields.PARENT_TOKEN_COUNT);
      if (parentTokenCount != null && !parentTokenCount.isBlank()) {
        fields.put(SchemaFields.PARENT_TOKEN_COUNT, parentTokenCount);
      }

      DocumentFieldOps.ChunkSlice slice = DocumentFieldOps.chunkSliceFrom(docFields);
      if (slice != null) chunkSlices.put(chunkDocId, slice);
      pending.add(new PendingChunkHit(chunkDocId, scoreDoc.score, fields));
    }

    Map<String, String> contentByChunk =
        DocumentFieldOps.resolveChunkContents(
            searcher, idField, chunkSlices, Map.of(), session.telemetryEvents);
    for (PendingChunkHit item : pending) {
      item.fields().put(
          SchemaFields.CHUNK_CONTENT, contentByChunk.getOrDefault(item.docId(), ""));
      hits.add(new SearchHit(item.docId(), item.score(), item.fields()));
    }

    long tookMs = System.currentTimeMillis() - startTime;
    return new SearchResult(hits, topDocs.totalHits.value(), tookMs);
  }

  /**
   * Searches for relevant full documents (non-chunks) within the specified document IDs using BM25.
   *
   * <p>Used as fallback for RAG when no chunks are indexed. Searches the content field and filters
   * by doc_id and NOT is_chunk.
   */
  public SearchResult searchFullDocsForDocs(String queryText, Set<String> docIds, int limit) {
    return searchFullDocsForDocs(queryText, docIds, limit, null);
  }

  /**
   * Filter-carrying sibling of {@link #searchFullDocsForDocs(String, Set, int)} (tempdoc 821 §3-C2).
   *
   * <p>Keeps the return-empty-on-empty-scope contract — the distinction from {@link #searchFullDocs}
   * that tempdoc 749 made deliberate — while letting the RAG whole-document legs apply the same
   * doc-level filter (notably the collection scope) their chunk sibling already applies. Without it
   * the FULLTEXT_FALLBACK and return_full_documents legs answered from outside the requested scope.
   */
  public SearchResult searchFullDocsForDocs(
      String queryText, Set<String> docIds, int limit, Query additionalFilter) {
    if (queryText == null || queryText.isBlank() || docIds == null || docIds.isEmpty()) {
      return new SearchResult(List.of(), 0, 0);
    }
    return searchFullDocs(queryText, docIds, limit, additionalFilter);
  }

  /**
   * Searches full documents (non-chunks) using BM25; an empty {@code docIds} means unscoped.
   *
   * <p>Unscoped-capable sibling of {@link #searchFullDocsForDocs}, which keeps its
   * return-empty-on-empty-scope contract for the fallback path (tempdoc 749 — the two
   * empty-scope semantics are deliberately distinct). {@code additionalFilter} carries
   * doc-level user filters (mime, language, ...); may be null.
   */
  public SearchResult searchFullDocs(
      String queryText, Set<String> docIds, int limit, Query additionalFilter) {
    if (queryText == null || queryText.isBlank()) {
      return new SearchResult(List.of(), 0, 0);
    }
    final int effectiveLimit = limit <= 0 ? 5 : limit;

    Query contentQuery;
    try {
      org.apache.lucene.queryparser.classic.QueryParser parser =
          new org.apache.lucene.queryparser.classic.QueryParser(
              SchemaFields.CONTENT, session.snapshot.indexAnalyzer());
      parser.setDefaultOperator(
          org.apache.lucene.queryparser.classic.QueryParser.Operator.OR);
      contentQuery =
          parser.parse(
              org.apache.lucene.queryparser.classic.QueryParser.escape(queryText));
    } catch (org.apache.lucene.queryparser.classic.ParseException e) {
      log.warn("Failed to parse full doc query", e);
      log.debug("Failed query text: {}", queryText);
      return new SearchResult(List.of(), 0, 0);
    }

    long startTime = System.currentTimeMillis();
    try {
      return bridge.withSearcher(
          searcher -> {
            BooleanQuery.Builder queryBuilder = new BooleanQuery.Builder();
            queryBuilder.add(contentQuery, BooleanClause.Occur.MUST);
            if (docIds != null && !docIds.isEmpty()) {
              queryBuilder.add(termInSetFilter(idField, docIds), BooleanClause.Occur.FILTER);
            }
            if (additionalFilter != null) {
              queryBuilder.add(additionalFilter, BooleanClause.Occur.FILTER);
            }
            queryBuilder.add(
                new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")),
                BooleanClause.Occur.MUST_NOT);

            org.apache.lucene.search.TopDocs topDocs =
                searchWithStableTieBreak(searcher, queryBuilder.build(), effectiveLimit);

            org.apache.lucene.index.StoredFields storedFields = searcher.storedFields();

            List<SearchHit> hits = new ArrayList<>();
            for (org.apache.lucene.search.ScoreDoc scoreDoc : topDocs.scoreDocs) {
              Document doc = storedFields.document(scoreDoc.doc);
              String docId = doc.get(idField);
              if (docId == null) {
                docId = doc.get(session.uidField);
              }

              Map<String, String> fields =
                  SearchResultFormatter.extractFromDocument(doc, true);
              hits.add(new SearchHit(docId, scoreDoc.score, fields));
            }

            long tookMs = System.currentTimeMillis() - startTime;
            return new SearchResult(hits, topDocs.totalHits.value(), tookMs);
          });
    } catch (IOException e) {
      throw new IndexRuntimeIOException(classifyIOException(e), "Full doc search failed", e);
    }
  }

  /**
   * Searches chunk vectors with optional parent doc scope filter.
   *
   * <p>Uses field separation: chunk_vector field only exists on chunk docs, so no is_chunk=true
   * filter needed.
   *
   * @param queryVector the query embedding
   * @param parentDocIds optional parent doc IDs to scope search (null = all chunks)
   * @param limit max results
   * @param additionalFilter optional Lucene filter query (e.g. mime/language constraints)
   */
  public SearchResult searchChunkVector(
      float[] queryVector, Set<String> parentDocIds, int limit, Query additionalFilter) {
    if (queryVector == null || queryVector.length == 0) {
      throw new IllegalArgumentException("queryVector must not be null or empty");
    }
    int effectiveLimit = limit <= 0 ? 10 : limit;

    Query filter = null;
    if (parentDocIds != null && !parentDocIds.isEmpty()) {
      filter = termInSetFilter(SchemaFields.PARENT_DOC_ID, parentDocIds);
    }
    if (additionalFilter != null) {
      if (filter != null) {
        filter =
            new BooleanQuery.Builder()
                .add(filter, BooleanClause.Occur.FILTER)
                .add(additionalFilter, BooleanClause.Occur.FILTER)
                .build();
      } else {
        filter = additionalFilter;
      }
    }

    int queryK = readPathOps.resolveVectorQueryK(effectiveLimit);
    KnnFloatVectorQuery knnQuery =
        new KnnFloatVectorQuery(SchemaFields.CHUNK_VECTOR, queryVector, queryK, filter);

    return readPathOps.search(knnQuery, effectiveLimit, null,
        RuntimeSearchSort.RELEVANCE, null);
  }

  /**
   * Doc-first hybrid chunk search for RAG retrieval.
   *
   * <p>Uses a two-phase approach: (1) doc-level hybrid search to select semantically relevant
   * parent docs, (2) BM25 chunk search within those selected docs.
   */
  public SearchResult searchChunksHybrid(
      String queryText, float[] queryVector, Set<String> docIds, int limit,
      Query additionalFilter) {
    if (queryText == null || queryText.isBlank() || docIds == null || docIds.isEmpty()) {
      return new SearchResult(List.of(), 0, 0);
    }
    int effectiveLimit = limit <= 0 ? 5 : limit;

    if (queryVector == null || queryVector.length == 0) {
      log.debug("searchChunksHybrid: no vector provided, falling back to BM25");
      return searchChunksFiltered(queryText, docIds, effectiveLimit, additionalFilter);
    }

    long startTime = System.currentTimeMillis();

    int docCandidateLimit = Math.min(docIds.size(), effectiveLimit * 2);
    Query docFilter = termInSetFilter(SchemaFields.DOC_ID, docIds);
    SearchResult docResults =
        hybridSearchOps.searchHybridFiltered(queryText, queryVector, docCandidateLimit, docFilter);

    Set<String> selectedDocIds =
        docResults.hits().stream()
            .map(SearchHit::docId)
            .collect(Collectors.toCollection(LinkedHashSet::new));

    if (log.isDebugEnabled()) {
      log.debug(
          "searchChunksHybrid doc-first (filtered): query='{}' hybridHits={}"
              + " inScope={} selectedDocs={}",
          queryText,
          docResults.hits().size(),
          docIds.size(),
          selectedDocIds.size());
    }

    if (selectedDocIds.isEmpty()) {
      log.debug("searchChunksHybrid: no filtered matches, falling back to full BM25");
      return searchChunksFiltered(queryText, docIds, effectiveLimit, additionalFilter);
    }

    SearchResult chunkResults =
        searchChunksFiltered(queryText, selectedDocIds, effectiveLimit, additionalFilter);

    long tookMs = System.currentTimeMillis() - startTime;
    return new SearchResult(chunkResults.hits(), chunkResults.totalHits(), tookMs);
  }

  /**
   * True chunk-level hybrid search (Phase 6).
   *
   * <p>When chunkVectorsEnabled is true, performs parallel BM25 + kNN over chunks, fused with RRF.
   * Falls back to doc-first hybrid when chunk vectors are disabled or no vector provided.
   */
  public SearchResult searchChunksHybrid(
      String queryText,
      float[] queryVector,
      Set<String> docIds,
      int limit,
      boolean chunkVectorsEnabled,
      Query additionalFilter) {

    if (!chunkVectorsEnabled || queryVector == null || queryVector.length == 0) {
      return searchChunksHybrid(queryText, queryVector, docIds, limit, additionalFilter);
    }

    if (queryText == null || queryText.isBlank() || docIds == null || docIds.isEmpty()) {
      return new SearchResult(List.of(), 0, 0);
    }
    int effectiveLimit = limit <= 0 ? 5 : limit;

    long startTime = System.currentTimeMillis();

    int candidateLimit = Math.min(effectiveLimit * 3, 100);

    SearchResult bm25Result;
    SearchResult knnResult;

    var executor = Executors.newVirtualThreadPerTaskExecutor();
    try {
      var bm25Future =
          CompletableFuture.supplyAsync(
              () -> searchChunksFiltered(queryText, docIds, candidateLimit, additionalFilter),
              executor);
      var knnFuture =
          CompletableFuture.supplyAsync(
              () -> searchChunkVector(queryVector, docIds, candidateLimit, additionalFilter),
              executor);

      bm25Result = bm25Future.join();
      knnResult = knnFuture.join();
    } finally {
      executor.close();
    }

    if (log.isDebugEnabled()) {
      log.debug(
          "searchChunksHybrid (Phase 6): query='{}' bm25Hits={} knnHits={} scope={}",
          queryText,
          bm25Result.hits().size(),
          knnResult.hits().size(),
          docIds.size());
    }

    HybridSearchOps.LowSignalGating gating =
        hybridSearchOps.computeLowSignalGating(
            bm25Result, knnResult, queryText, "searchChunksHybrid (Phase 6) gating");

    ResolvedConfig rc = session.resolvedConfig;
    SearchResult fused =
        fuseWithRRF(
            bm25Result,
            knnResult,
            effectiveLimit,
            false,
            gating.vectorOnlyCap(),
            gating.vectorWeight(),
            rc);

    long tookMs = System.currentTimeMillis() - startTime;
    return new SearchResult(fused.hits(), fused.totalHits(), tookMs);
  }

  // ==========================================================================
  // Doc-level union leg (tempdoc 749, option B)
  // ==========================================================================

  /**
   * Returns the subset of {@code candidateDocIds} that have at least one chunk document.
   *
   * <p>Used by the RAG doc-level union leg (tempdoc 749): a parent returned here already
   * competes through its real chunks and must not be double-surfaced as a synthesized
   * whole-doc chunk; a parent absent here has no chunk documents at all and is invisible
   * to every {@code is_chunk=true} query in this class.
   *
   * @param candidateDocIds parent doc IDs to probe (bounded — callers pass a top-N hit set)
   * @return the subset of candidates with at least one chunk doc (never null)
   */
  public Set<String> findParentDocIdsWithChunks(Set<String> candidateDocIds) {
    if (candidateDocIds == null || candidateDocIds.isEmpty()) {
      return Set.of();
    }
    // One bounded existence probe per candidate — bounded by the top-N candidate set, immune to
    // per-parent chunk-count skew (a single query with a shared result-window limit could push a
    // heavily-chunked parent's own chunks outside the window and misclassify it as chunkless).
    LinkedHashSet<String> withChunks = new LinkedHashSet<>();
    for (String docId : candidateDocIds) {
      BooleanQuery q = new BooleanQuery.Builder()
          .add(new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")),
              BooleanClause.Occur.MUST)
          .add(new TermQuery(new Term(SchemaFields.PARENT_DOC_ID, docId)),
              BooleanClause.Occur.FILTER)
          .build();
      SearchResult probe = readPathOps.search(q, 1, Set.of(SchemaFields.PARENT_DOC_ID),
          RuntimeSearchSort.RELEVANCE, null);
      if (!probe.hits().isEmpty()) {
        withChunks.add(docId);
      }
    }
    return withChunks;
  }

  /**
   * Doc-level retrieval leg for the RAG union (tempdoc 749): hybrid (BM25 + KNN over parent
   * {@code content}/{@code vector}) when a query vector is usable, BM25-only otherwise.
   * An empty {@code docIds} means unscoped; {@code additionalFilter} carries doc-level user
   * filters and may be null.
   */
  public SearchResult searchDocLevelUnion(
      String queryText, float[] queryVector, Set<String> docIds, int limit,
      Query additionalFilter) {
    if (queryText == null || queryText.isBlank()) {
      return new SearchResult(List.of(), 0, 0);
    }
    boolean vectorUsable = queryVector != null && queryVector.length > 0;
    if (!vectorUsable) {
      return searchFullDocs(queryText, docIds, limit, additionalFilter);
    }
    Query scope = (docIds == null || docIds.isEmpty())
        ? null
        : termInSetFilter(SchemaFields.DOC_ID, docIds);
    // Exclude chunk docs by construction (symmetric with searchFullDocs) — tempdoc 749 review PF-2.
    // MatchAllDocsQuery is the REQUIRED positive clause: a pure MUST_NOT filter matches nothing and
    // would break the unscoped union (no scope/user filter present).
    BooleanQuery.Builder cb = new BooleanQuery.Builder()
        .add(new org.apache.lucene.search.MatchAllDocsQuery(), BooleanClause.Occur.FILTER)
        .add(new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")), BooleanClause.Occur.MUST_NOT);
    if (scope != null) {
      cb.add(scope, BooleanClause.Occur.FILTER);
    }
    if (additionalFilter != null) {
      cb.add(additionalFilter, BooleanClause.Occur.FILTER);
    }
    Query combined = cb.build();
    return hybridSearchOps.searchHybridFiltered(queryText, queryVector, limit, combined);
  }
}
