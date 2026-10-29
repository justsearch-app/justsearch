package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RuntimeSearchFilters;
import java.util.List;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link QueryFilterBuilder#buildChunkFilterQuery} which builds Lucene filter queries
 * applicable to chunk documents: mime, fileKind, mimeBase, language, the collection scope, and the
 * two PATH-keyed scopes (pathPrefix, doc_ids) — skipping modifiedAt range, entity fields, metadata
 * fields, and IS_CHUNK exclusion, none of which chunk documents carry.
 *
 * <p>The collection scope joined that list in tempdoc 811 item 3, once {@code ChunkDocumentWriter}
 * started writing the parent's {@code collection} onto each chunk. Before that, the default
 * agent-history exclusion could not bind on the chunk branch at all.
 *
 * <p>The PATH-keyed pair used to be skipped too. That was wrong on the facts: {@code
 * ChunkDocumentWriter} writes {@code PATH = parentDocId} and {@code IndexingDocumentOps} writes the
 * parent's {@code DOC_ID = PATH = absolutePath}, so a chunk's PATH IS the parent file's path. The
 * end-to-end consequence (an out-of-prefix parent's chunk retrieved under a pathPrefix filter) is
 * pinned by {@code ChunkSearchIntegrationTest#chunkFilterScopesByPathPrefix}.
 */
final class QueryFilterBuilderTest {

  /**
   * The rendered chunk filter when NO chunk-applicable filter was requested: the default
   * agent-history exclusion plus its MatchAllDocs anchor, and nothing else. Asserting on this exact
   * string keeps "filter X is skipped on the chunk branch" a precise claim — a skipped filter that
   * quietly started contributing a clause would no longer be equal to it.
   */
  private static final String DEFAULT_CHUNK_SCOPE_ONLY = "-collection:agent-history +*:*";

  @Test
  void buildChunkFilterQuery_nullFiltersStillCarriesDefaultCollectionScope() {
    // Tempdoc 811: null filters mean the DEFAULT scope, never "no scope".
    Query q = QueryFilterBuilder.buildChunkFilterQuery(null);
    assertNotNull(q, "null filters must still yield the default agent-history exclusion");
    assertTrue(q.toString().contains("-collection:agent-history"), "excluded: " + q);
  }

  @Test
  void buildChunkFilterQuery_emptyFiltersStillCarriesDefaultCollectionScope() {
    RuntimeSearchFilters filters =
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
            .mime(List.of()).language(List.of()).fileKind(List.of()).mimeBase(List.of())
            .build();
    Query q = QueryFilterBuilder.buildChunkFilterQuery(filters);
    assertNotNull(q);
    assertTrue(q.toString().contains("-collection:agent-history"), "excluded: " + q);
  }

  @Test
  void buildChunkFilterQuery_explicitCollectionScopeIsAPositiveFilter() {
    RuntimeSearchFilters filters =
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
            .collection(List.of("agent-history"))
            .build();
    Query q = QueryFilterBuilder.buildChunkFilterQuery(filters);
    assertNotNull(q);
    String s = q.toString();
    assertTrue(s.contains("collection:agent-history"), "included: " + s);
    assertTrue(!s.contains("-collection:agent-history"), "not also excluded: " + s);
  }

  @Test
  void buildChunkFilterQuery_mimeFilterProducesQuery() {
    RuntimeSearchFilters filters =
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
            .mime(List.of("application/pdf"))
            .build();
    Query q = QueryFilterBuilder.buildChunkFilterQuery(filters);
    assertNotNull(q, "Should produce a filter for mime");
  }

  @Test
  void buildChunkFilterQuery_languageFilterProducesQuery() {
    RuntimeSearchFilters filters =
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
            .language(List.of("en"))
            .build();
    Query q = QueryFilterBuilder.buildChunkFilterQuery(filters);
    assertNotNull(q, "Should produce a filter for language");
  }

  @Test
  void buildChunkFilterQuery_fileKindFilterProducesQuery() {
    RuntimeSearchFilters filters =
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
            .fileKind(List.of("document"))
            .build();
    Query q = QueryFilterBuilder.buildChunkFilterQuery(filters);
    assertNotNull(q, "Should produce a filter for fileKind");
  }

  @Test
  void buildChunkFilterQuery_multipleFiltersProducesCombinedQuery() {
    RuntimeSearchFilters filters =
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
            .mime(List.of("text/plain"))
            .language(List.of("de"))
            .fileKind(List.of("document"))
            .mimeBase(List.of("text"))
            .build();
    Query q = QueryFilterBuilder.buildChunkFilterQuery(filters);
    assertNotNull(q, "Should produce combined filter");
    // 4 FILTER clauses + the always-present default collection exclusion (811 item 3).
    BooleanQuery bq = (BooleanQuery) q;
    assertEquals(
        5,
        bq.clauses().size(),
        "4 filter clauses (mime, language, fileKind, mimeBase) + the default collection exclusion");
  }

  @Test
  void buildChunkFilterQuery_resolvesDateRangeThroughParent() {
    // Chunks lack modified_at: resolve eligible parents before chunk candidate budgets.
    RuntimeSearchFilters filters =
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
            .modifiedFromMs(1000L)
            .modifiedToMs(2000L)
            .build();
    assertTrue(QueryFilterBuilder.buildChunkFilterQuery(filters).toString().contains("parentMetadata("));
    assertTrue(QueryFilterBuilder.buildChunkFilterQuery(filters).toString().contains("modified_at"));
  }

  @Test
  void buildChunkFilterQuery_appliesPathPrefix() {
    // A chunk's PATH holds the PARENT's absolute path (ChunkDocumentWriter writes
    // PATH = parentDocId; IndexingDocumentOps writes DOC_ID = PATH = absolutePath), so the same
    // PrefixQuery the whole-doc legs use is valid on chunks. Skipping it let the chunk branch
    // retrieve out-of-scope candidates.
    RuntimeSearchFilters filters =
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
            .pathPrefix("/some/path")
            .build();
    Query q = QueryFilterBuilder.buildChunkFilterQuery(filters);
    assertNotNull(q, "pathPrefix must scope the chunk branch");
    assertTrue(
        q.toString().contains("path:"),
        "the chunk filter must constrain the PATH field: " + q);
  }

  @Test
  void buildChunkFilterQuery_appliesDocIds() {
    RuntimeSearchFilters filters =
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
            .docIds(List.of("/path/to/doc1.txt"))
            .build();
    Query q = QueryFilterBuilder.buildChunkFilterQuery(filters);
    assertNotNull(q, "doc_ids must scope the chunk branch (PATH holds the parent path)");
    assertTrue(
        q.toString().contains("/path/to/doc1.txt"),
        "the chunk filter must name the requested document: " + q);
  }

  @Test
  void buildChunkFilterQuery_ignoresEntityFilters() {
    // Entity filters are NOT stored on chunks — should be ignored
    RuntimeSearchFilters filters =
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
            .entityPersons(List.of("Alice"))
            .entityOrganizations(List.of("Acme Corp"))
            .entityLocations(List.of("Berlin"))
            .build();
    assertEquals(
        DEFAULT_CHUNK_SCOPE_ONLY,
        QueryFilterBuilder.buildChunkFilterQuery(filters).toString(),
        "entity filters must add no chunk clause; only the default collection scope remains");
  }

  @Test
  void buildChunkFilterQuery_ignoresMetadataFilters() {
    // Metadata filters are NOT stored on chunks — should be ignored
    RuntimeSearchFilters filters =
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
            .metaSource(List.of("the verge"))
            .metaAuthor(List.of("stan choe"))
            .metaCategory(List.of("tech"))
            .build();
    assertEquals(
        DEFAULT_CHUNK_SCOPE_ONLY,
        QueryFilterBuilder.buildChunkFilterQuery(filters).toString(),
        "metadata filters must add no chunk clause; only the default collection scope remains");
  }

  @Test
  void buildFilterQueryOnly_includesMetadataTermFilters() {
    RuntimeSearchFilters filters =
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
            .metaSource(List.of("the verge"))
            .build();
    Query q = QueryFilterBuilder.buildFilterQueryOnly(filters);
    assertNotNull(q, "Should produce a filter for meta_source");
  }

  @Test
  void buildFilterQueryOnly_includesMetadataDateRange() {
    RuntimeSearchFilters filters =
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
            .metaPublishedFromMs(1000L)
            .metaPublishedToMs(2000L)
            .build();
    Query q = QueryFilterBuilder.buildFilterQueryOnly(filters);
    assertNotNull(q, "Should produce a filter for meta_published_at range");
  }

  @Test
  void buildChunkFilterQuery_mimeAndPathPrefixBothApply() {
    // Mixed: both mime and pathPrefix are enforceable on chunk documents.
    RuntimeSearchFilters filters =
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
            .mime(List.of("application/pdf"))
            .pathPrefix("/scoped/path")
            .entityPersons(List.of("Alice")) // not stored on chunks — still skipped
            .build();
    Query q = QueryFilterBuilder.buildChunkFilterQuery(filters);
    assertNotNull(q, "Should produce a filter for the applicable parts");
    BooleanQuery bq = (BooleanQuery) q;
    assertEquals(
        3,
        bq.clauses().size(),
        "mime + pathPrefix + the default collection exclusion; the entity filter stays skipped");
  }

  // ---- doc_ids filter tests (366 Phase 6) ----

  @Test
  void applyRuntimeFilters_docIdsAddsTermFilter() {
    RuntimeSearchFilters filters =
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
            .docIds(List.of("/path/to/doc1.txt", "/path/to/doc2.txt"))
            .build();
    Query q = QueryFilterBuilder.applyRuntimeFilters(
        new org.apache.lucene.search.MatchAllDocsQuery(), filters);
    assertNotNull(q);
    String queryStr = q.toString();
    assertTrue(queryStr.contains("/path/to/doc1.txt") || queryStr.contains("path"),
        "doc_ids should produce PATH filter clauses: " + queryStr);
  }

  @Test
  void buildFilterQueryOnly_docIdsProducesFilter() {
    RuntimeSearchFilters filters =
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
            .docIds(List.of("/single/doc.pdf"))
            .build();
    Query q = QueryFilterBuilder.buildFilterQueryOnly(filters);
    assertNotNull(q, "doc_ids alone should produce a filter");
  }

  // ---- Soft-boost filter tests (363) ----

  private static RuntimeSearchFilters boostWithSource(String... sources) {
    return LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
        .metaSource(List.of(sources))
        .build();
  }

  @Test
  void applyBoostFilters_nullFiltersAddsNoClauses() {
    BooleanQuery.Builder qb = new BooleanQuery.Builder();
    qb.add(new org.apache.lucene.search.MatchAllDocsQuery(), BooleanClause.Occur.MUST);
    QueryFilterBuilder.applyBoostFilters(qb, null, 0.5f);
    BooleanQuery bq = qb.build();
    assertEquals(1, bq.clauses().size(), "Only the MUST clause, no boost added");
  }

  @Test
  void applyBoostFilters_metaSourceAddsShouldClause() {
    BooleanQuery.Builder qb = new BooleanQuery.Builder();
    qb.add(new org.apache.lucene.search.MatchAllDocsQuery(), BooleanClause.Occur.MUST);
    QueryFilterBuilder.applyBoostFilters(qb, boostWithSource("the verge"), 0.5f);
    BooleanQuery bq = qb.build();
    assertEquals(2, bq.clauses().size(), "MUST + one SHOULD boost");
    BooleanClause boostClause = bq.clauses().get(1);
    assertEquals(BooleanClause.Occur.SHOULD, boostClause.occur(), "Boost is SHOULD, not FILTER");
    assertTrue(boostClause.query() instanceof org.apache.lucene.search.BoostQuery,
        "Wrapped in BoostQuery");
  }

  @Test
  void applyBoostFilters_multipleFieldsProduceMultipleShouldClauses() {
    RuntimeSearchFilters boostFilters = LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
        .entityPersons(List.of("elon musk"))
        .metaSource(List.of("the verge"))
        .metaCategory(List.of("technology"))
        .build();
    BooleanQuery.Builder qb = new BooleanQuery.Builder();
    qb.add(new org.apache.lucene.search.MatchAllDocsQuery(), BooleanClause.Occur.MUST);
    QueryFilterBuilder.applyBoostFilters(qb, boostFilters, 0.5f);
    BooleanQuery bq = qb.build();
    assertEquals(4, bq.clauses().size(), "MUST + 3 SHOULD boost clauses");
    long shouldCount = bq.clauses().stream()
        .filter(c -> c.occur() == BooleanClause.Occur.SHOULD)
        .count();
    assertEquals(3, shouldCount, "3 SHOULD clauses for 3 boost fields");
  }

  // ===== Tempdoc 585 §D Phase 4 (D4b) — the collection scope =====

  @Test
  void buildFilterQueryOnly_defaultExcludesAgentHistory() {
    // No explicit collection scope ⇒ the reserved agent-history collection is excluded by default
    // (a MUST_NOT), so indexed run transcripts never pollute normal document search.
    RuntimeSearchFilters filters =
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder().includeChunks(true).build();
    Query q = QueryFilterBuilder.buildFilterQueryOnly(filters);
    assertNotNull(q, "the default exclusion always produces a filter");
    String s = q.toString();
    // Lucene renders a MUST_NOT clause with a leading '-'.
    assertTrue(s.contains("-collection:agent-history"), "agent-history is MUST_NOT excluded: " + s);
    // And the pure-negative query is anchored so it does not match nothing.
    assertTrue(s.contains("*:*") || s.contains("MatchAllDocs"), "anchored with MatchAllDocs: " + s);
  }

  @Test
  void buildFilterQueryOnly_explicitAgentHistoryScopeIncludesIt() {
    // Scoping to the agent-history collection (the "Agent history" search scope) INCLUDES it (a
    // FILTER clause) and does NOT also exclude it.
    RuntimeSearchFilters filters =
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
            .includeChunks(true)
            .collection(List.of("agent-history"))
            .build();
    Query q = QueryFilterBuilder.buildFilterQueryOnly(filters);
    assertNotNull(q);
    String s = q.toString();
    assertTrue(s.contains("collection:agent-history"), "agent-history is included: " + s);
    assertTrue(!s.contains("-collection:agent-history"), "not also excluded: " + s);
  }

  @Test
  void applyRuntimeFilters_defaultExcludesAgentHistory() {
    // The keyword-search path (content query + filters) also excludes agent-history by default.
    RuntimeSearchFilters filters =
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder().includeChunks(true).build();
    Query content = new org.apache.lucene.search.MatchAllDocsQuery();
    Query q = QueryFilterBuilder.applyRuntimeFilters(content, filters);
    assertTrue(q.toString().contains("-collection:agent-history"), "excluded: " + q);
  }

  // ===== Tempdoc 811 D-1 — the `filters == null` bypass of the collection scope =====

  @Test
  void applyRuntimeFilters_nullFiltersStillExcludeAgentHistory() {
    // Before D-1 this returned right after the chunk exclusion, BEFORE addCollectionScope — so
    // every null-filters call site (HybridSearchOps.searchHybrid -> searchText(t, l, null) ->
    // buildTextQuery(text, null)) searched indexed agent transcripts.
    Query content = new org.apache.lucene.search.MatchAllDocsQuery();
    Query q = QueryFilterBuilder.applyRuntimeFilters(content, null);
    assertNotNull(q);
    String s = q.toString();
    assertTrue(s.contains("-collection:agent-history"), "excluded on the null path: " + s);
    assertTrue(s.contains("-is_chunk:true"), "the chunk exclusion is not lost: " + s);
  }

  @Test
  void buildFilterQueryOnly_nullFiltersStillExcludeAgentHistory() {
    // Same defect on the VECTOR/HYBRID filter-only path (RagContextOps union leg, the dense legs).
    Query q = QueryFilterBuilder.buildFilterQueryOnly(null);
    assertNotNull(q);
    String s = q.toString();
    assertTrue(s.contains("-collection:agent-history"), "excluded on the null path: " + s);
    assertTrue(s.contains("-is_chunk:true"), "the chunk exclusion is not lost: " + s);
    assertTrue(s.contains("*:*"), "the pure-negative query stays anchored: " + s);
  }
}
