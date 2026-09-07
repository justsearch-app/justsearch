package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.SearchMode;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for QPP (Query Performance Prediction) signals returned in SearchResponse.
 *
 * <p>QPP signals (max_idf, avg_ictf, query_scope) are computed in SearchOrchestrator for TEXT and
 * HYBRID modes. They are always 0.0 for VECTOR mode and for empty queries.
 */
@DisplayName("WorkerSearchService QPP signals")
class WorkerSearchServiceQppTest {

  @TempDir Path tempDir;
  private RunningRuntime lifecycle;
  private WorkerSearchService service;

  @BeforeEach
  void setUp() throws Exception {
    System.clearProperty("justsearch.config");
    lifecycle = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(tempDir).open();
    service = new WorkerSearchService(lifecycle);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (lifecycle != null) {
      lifecycle.close();
    }
  }

  private void indexDoc(String docId, String content) throws Exception {
    lifecycle.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, docId,
                SchemaFields.DOC_UID, docId + "#0",
                SchemaFields.PATH, "C:/docs/" + docId + ".txt",
                SchemaFields.CONTENT, content)));
  }

  private void indexChunk(String parentDocId, int chunkIndex, String content) throws Exception {
    String chunkId = parentDocId + "#chunk_" + chunkIndex;
    lifecycle.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, chunkId,
                SchemaFields.DOC_UID, parentDocId + "#" + chunkIndex,
                SchemaFields.PARENT_DOC_ID, parentDocId,
                SchemaFields.IS_CHUNK, "true",
                SchemaFields.CHUNK_CONTENT, content)));
  }

  private SearchResponse search(String query, SearchMode mode) {
    SearchResponse result =
        service.search(
            SearchRequest.newBuilder().setQuery(query).setLimit(10).setMode(mode).build(),
            CallContext.none());
    assertNotNull(result);
    return result;
  }

  @Nested
  @DisplayName("empty index")
  class EmptyIndex {

    @Test
    @DisplayName("QPP signals are zero for empty index")
    void emptyIndex_qppIsZero() {
      SearchResponse resp = search("hello world", SearchMode.SEARCH_MODE_TEXT);
      assertEquals(0f, resp.getSearchTrace().getQpp().getMaxIdf(), 0.001f);
      assertEquals(0f, resp.getSearchTrace().getQpp().getAvgIctf(), 0.001f);
      assertEquals(0f, resp.getSearchTrace().getQpp().getQueryScope(), 0.001f);
    }
  }

  @Nested
  @DisplayName("TEXT mode QPP")
  class TextModeQpp {

    @Test
    @DisplayName("maxIdf is positive for a query that has matching terms")
    void textMode_maxIdf_isPositive() throws Exception {
      indexDoc("doc-1", "the quick brown fox jumps over the lazy dog");
      indexDoc("doc-2", "the cat sat on the mat");
      indexDoc("doc-3", "hello world from the index");
      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      SearchResponse resp = search("fox", SearchMode.SEARCH_MODE_TEXT);
      assertTrue(resp.getSearchTrace().getQpp().getMaxIdf() > 0f, "maxIdf must be positive for a term found in < all docs");
    }

    @Test
    @DisplayName("queryScope is between 0 and 1 inclusive")
    void textMode_queryScope_isInRange() throws Exception {
      indexDoc("doc-1", "the quick brown fox");
      indexDoc("doc-2", "the cat sat here");
      indexDoc("doc-3", "nothing relevant");
      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      SearchResponse resp = search("fox cat", SearchMode.SEARCH_MODE_TEXT);
      float qs = resp.getSearchTrace().getQpp().getQueryScope();
      assertTrue(qs >= 0f && qs <= 1f, () -> "queryScope out of range: " + qs);
    }

    @Test
    @DisplayName("common term has lower maxIdf than rare term")
    void textMode_rareTermHasHigherIdf() throws Exception {
      // "the" appears in all docs; "unique" appears in only one
      for (int i = 1; i <= 10; i++) {
        indexDoc("doc-" + i, "the common word repeated in document " + i);
      }
      indexDoc("doc-rare", "unique term only here");
      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      SearchResponse commonResp = search("the", SearchMode.SEARCH_MODE_TEXT);
      SearchResponse rareResp = search("unique", SearchMode.SEARCH_MODE_TEXT);

      assertTrue(
          rareResp.getSearchTrace().getQpp().getMaxIdf() > commonResp.getSearchTrace().getQpp().getMaxIdf(),
          () ->
              "Rare term must have higher maxIdf than common term. "
                  + "rare=" + rareResp.getSearchTrace().getQpp().getMaxIdf()
                  + " common=" + commonResp.getSearchTrace().getQpp().getMaxIdf());
    }

    @Test
    @DisplayName("queryScope is near 1.0 for a very common term")
    void textMode_commonTerm_queryScope_isHigh() throws Exception {
      for (int i = 1; i <= 5; i++) {
        indexDoc("doc-" + i, "the quick brown fox in document " + i);
      }
      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      // "the" appears in all 5 docs → queryScope should approach 1.0
      SearchResponse resp = search("the", SearchMode.SEARCH_MODE_TEXT);
      assertTrue(
          resp.getSearchTrace().getQpp().getQueryScope() > 0.8f,
          () -> "queryScope should be high for a term in all docs: " + resp.getSearchTrace().getQpp().getQueryScope());
    }

    @Test
    @DisplayName("avgIctf is positive for a query with matching terms")
    void textMode_avgIctf_isPositive() throws Exception {
      indexDoc("doc-1", "machine learning algorithms for classification");
      indexDoc("doc-2", "deep learning neural network architecture");
      indexDoc("doc-3", "natural language processing with transformers");
      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      SearchResponse resp = search("learning algorithms", SearchMode.SEARCH_MODE_TEXT);
      assertTrue(
          resp.getSearchTrace().getQpp().getAvgIctf() > 0f,
          () -> "avgIctf must be positive for terms found in the collection: " + resp.getSearchTrace().getQpp().getAvgIctf());
    }

    @Test
    @DisplayName("chunk documents do not inflate the content-field QPP denominator")
    void textMode_chunkDocsDoNotInflateFieldDocCount() throws Exception {
      indexDoc("doc-1", "common parent one");
      indexDoc("doc-2", "common parent two");
      for (int i = 0; i < 20; i++) {
        indexChunk("doc-1", i, "chunk-only material " + i);
      }
      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      SearchResponse resp = search("common", SearchMode.SEARCH_MODE_TEXT);
      assertEquals(
          0f,
          resp.getSearchTrace().getQpp().getMaxIdf(),
          0.001f,
          "both content-bearing parents contain the term, so field-local IDF is zero");
    }
  }

  @Nested
  @DisplayName("HYBRID mode QPP")
  class HybridModeQpp {

    @Test
    @DisplayName("HYBRID mode computes non-zero QPP signals for matching terms")
    void hybridMode_computesNonZeroQpp() throws Exception {
      indexDoc("doc-1", "the quick brown fox jumps over the lazy dog");
      indexDoc("doc-2", "a fast red fox ran through the forest");
      indexDoc("doc-3", "nothing here at all");
      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      // QPP is computed for TEXT and HYBRID modes — HYBRID uses the same term-stats path
      SearchResponse resp = search("fox", SearchMode.SEARCH_MODE_HYBRID);
      assertTrue(resp.getSearchTrace().getQpp().getMaxIdf() > 0f, "HYBRID mode: maxIdf must be positive for known term");
      assertTrue(resp.getSearchTrace().getQpp().getAvgIctf() > 0f, "HYBRID mode: avgIctf must be positive for known term");
      assertTrue(
          resp.getSearchTrace().getQpp().getQueryScope() > 0f, "HYBRID mode: queryScope must be positive for known term");
    }
  }

  @Nested
  @DisplayName("VECTOR mode QPP")
  class VectorModeQpp {

    /**
     * The test environment uses {@code FieldCatalogDef.forChunkTesting} which does not configure a
     * vector field, so a VECTOR-mode request is rejected at the service layer with INVALID_ARGUMENT.
     *
     * <p>QPP signals for VECTOR mode are always zero by the SearchOrchestrator code guard (line
     * ~441): {@code if (mode == TEXT || mode == HYBRID) { qpp = computeQpp(...); }}. Any other mode
     * unconditionally receives {@code QppMetrics.ZERO} in the response builder. This structural
     * invariant is verified by code inspection; the runtime path cannot be exercised in a
     * test environment without a vector-enabled field catalogue.
     */
    @Test
    @DisplayName("VECTOR mode without a configured vector field returns INVALID_ARGUMENT")
    void vectorMode_noVectorField_rejectsRequest() throws Exception {
      indexDoc("doc-1", "the quick brown fox jumps over the lazy dog");
      indexDoc("doc-2", "a fast red fox ran through the forest");
      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      WorkerServiceException error =
          assertThrows(
              WorkerServiceException.class,
              () ->
                  service.search(
                      SearchRequest.newBuilder()
                          .setQuery("fox")
                          .setLimit(10)
                          .setMode(SearchMode.SEARCH_MODE_VECTOR)
                          .build(),
                      CallContext.none()),
              "VECTOR mode without a vector field must return an error, not a successful response");
      assertNotNull(error);
    }
  }
}
