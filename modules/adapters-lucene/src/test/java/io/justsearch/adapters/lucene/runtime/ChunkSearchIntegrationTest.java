package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RuntimeSearchSort;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkIds;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.lucene.search.IndexSearcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for RAG (Retrieval-Augmented Generation) search functionality.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>{@link ChunkSearchOps#searchFullDocsForDocs} finds relevant full documents</li>
 *   <li>BM25 ranking works correctly for full document content</li>
 *   <li>Document ID filtering works (only searches within specified docs)</li>
 * </ul>
 *
 * <p>Note: Chunk search tests ({@code searchChunksForDocs}) require the full SSOT
 * field catalog with chunk-specific fields and are covered by integration tests
 * (HttpAiQualityTest) rather than unit tests.
 */
@DisplayName("RAG Document Retrieval")
class ChunkSearchIntegrationTest {

  private RunningRuntime runtime;
  private Path tempDir;
  private String prevConfig;
  private final Map<String, Map<String, Object>> parentDocuments = new HashMap<>();
  /** Chunk fixtures per parent, re-cut whenever that parent's content grows. */
  private final Map<String, List<Map<String, Object>>> chunkDocuments = new HashMap<>();

  @BeforeEach
  void setup() throws Exception {
    prevConfig = System.getProperty("justsearch.config");
    tempDir = Files.createTempDirectory("justsearch-rag-test-");

    // Create config
    String yaml = "app:\n  data_dir: " + tempDir.toString().replace("\\", "\\\\") + "\n" +
        "index:\n  collections:\n    - name: ragtest\n      roots: ['ignored']\n  vector:\n    dimension: 4\n";
    Path cfg = Files.createTempFile("justsearch-rag-config-", ".yaml");
    Files.writeString(cfg, yaml);
    System.setProperty("justsearch.config", cfg.toString());

    // Use chunk-aware testing catalog with 4-dim vectors
    runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(4)).ephemeral().open();
  }

  @AfterEach
  void cleanup() throws Exception {
    if (runtime != null) {
      runtime.close();
    }
    if (prevConfig == null) {
      System.clearProperty("justsearch.config");
    } else {
      System.setProperty("justsearch.config", prevConfig);
    }
  }

  @Test
  @DisplayName("searchFullDocsForDocs finds relevant full documents by BM25")
  void searchFullDocsForDocsFindsByBm25() throws Exception {
    // Index parent documents with content
    indexDoc("doc-1", "The quick brown fox jumps over the lazy dog");
    indexDoc("doc-2", "A fast red fox runs through the forest");
    indexDoc("doc-3", "Cats and dogs are popular pets");

    commitAndRefresh();

    // Search for "fox" within doc-1 and doc-2
    var result = runtime.chunkSearchOps().searchFullDocsForDocs("fox", Set.of("doc-1", "doc-2"), 5);

    assertNotNull(result);
    assertEquals(2, result.hits().size(), "Should find 2 docs containing 'fox'");

    // Both results should be from our filtered set
    for (var hit : result.hits()) {
      assertTrue(hit.docId().equals("doc-1") || hit.docId().equals("doc-2"),
          "Result should be from filtered doc set. Got: " + hit.docId());
    }
  }

  @Test
  @DisplayName("searchFullDocsForDocs filters by document IDs")
  void searchFullDocsFiltersToSpecifiedDocs() throws Exception {
    // Index docs all containing "machine"
    indexDoc("doc-1", "Machine learning basics");
    indexDoc("doc-2", "Machine learning advanced");
    indexDoc("doc-3", "Machine vision systems");

    commitAndRefresh();

    // Search only within doc-1 and doc-2 (exclude doc-3)
    var result = runtime.chunkSearchOps().searchFullDocsForDocs("machine", Set.of("doc-1", "doc-2"), 5);

    assertNotNull(result);
    assertEquals(2, result.hits().size(), "Should find exactly 2 docs");

    // Verify doc-3 is not in results
    for (var hit : result.hits()) {
      assertFalse("doc-3".equals(hit.docId()), "Should not include doc-3");
    }
  }

  @Test
  @DisplayName("searchFullDocsForDocs returns empty when query doesn't match")
  void searchFullDocsReturnsEmptyWhenNoMatch() throws Exception {
    indexDoc("doc-1", "Machine learning basics");

    commitAndRefresh();

    // Search for non-existent term
    var result = runtime.chunkSearchOps().searchFullDocsForDocs("quantum", Set.of("doc-1"), 5);

    assertNotNull(result);
    assertTrue(result.hits().isEmpty(), "Should return empty when query doesn't match");
  }

  @Test
  @DisplayName("searchFullDocsForDocs handles multiple doc IDs")
  void searchFullDocsHandlesMultipleDocIds() throws Exception {
    // Index 5 docs, all containing "python"
    for (int i = 1; i <= 5; i++) {
      indexDoc("doc-" + i, "Python programming tutorial part " + i);
    }

    commitAndRefresh();

    // Search for "python" in 3 of the 5 docs
    var result = runtime.chunkSearchOps().searchFullDocsForDocs("python", Set.of("doc-1", "doc-3", "doc-5"), 10);

    assertNotNull(result);
    assertEquals(3, result.hits().size(), "Should find exactly 3 docs");

    // Verify only our filtered docs are returned
    Set<String> expectedIds = Set.of("doc-1", "doc-3", "doc-5");
    for (var hit : result.hits()) {
      assertTrue(expectedIds.contains(hit.docId()),
          "Unexpected doc ID: " + hit.docId());
    }
  }

  @Test
  @DisplayName("searchFullDocsForDocs respects limit parameter")
  void searchFullDocsRespectsLimit() throws Exception {
    // Index 10 docs all matching "data"
    for (int i = 1; i <= 10; i++) {
      indexDoc("doc-" + i, "Data science and data analysis chapter " + i);
    }

    commitAndRefresh();

    // Create set of all doc IDs
    Set<String> allDocIds = Set.of("doc-1", "doc-2", "doc-3", "doc-4", "doc-5",
        "doc-6", "doc-7", "doc-8", "doc-9", "doc-10");

    // Search with limit of 3
    var result = runtime.chunkSearchOps().searchFullDocsForDocs("data", allDocIds, 3);

    assertNotNull(result);
    assertEquals(3, result.hits().size(), "Should return exactly 3 results (limited)");
  }

  @Test
  @DisplayName("searchFullDocsForDocs supports docId selections larger than IndexSearcher.getMaxClauseCount()")
  void searchFullDocsSupportsLargeDocIdSelections() throws Exception {
    indexDoc("doc-1", "The quick brown fox jumps over the lazy dog");
    commitAndRefresh();

    Set<String> docIds = largeDocIdSelectionIncluding("doc-1");
    var result = runtime.chunkSearchOps().searchFullDocsForDocs("fox", docIds, 5);

    assertNotNull(result);
    assertFalse(result.hits().isEmpty(), "Should return at least one result");
    assertTrue(result.hits().stream().anyMatch(h -> "doc-1".equals(h.docId())),
        "Expected doc-1 to be returned from large docId selection");
  }

  @Test
  @DisplayName("searchChunksForDocs supports docId selections larger than IndexSearcher.getMaxClauseCount()")
  void searchChunksSupportsLargeDocIdSelections() throws Exception {
    indexDoc("doc-1", "Parent document about machine learning");
    indexChunk("doc-1", 0, 2, "Neural networks overview");
    indexChunk("doc-1", 1, 2, "Deep learning fundamentals");
    commitAndRefresh();

    Set<String> docIds = largeDocIdSelectionIncluding("doc-1");
    var result = runtime.chunkSearchOps().searchChunksForDocs("neural", docIds, 5);

    assertNotNull(result);
    assertFalse(result.hits().isEmpty(), "Should find chunks matching 'neural'");
    for (var hit : result.hits()) {
      assertEquals("doc-1", hit.fields().get(SchemaFields.PARENT_DOC_ID),
          "All chunks should be filtered to parent doc-1");
    }
  }

  // ========== Chunk Search Tests (D1 / P1.4) ==========

  @Test
  @DisplayName("searchChunksForDocs finds chunks by BM25 on chunk_content")
  void searchChunksForDocsFindsByBm25() throws Exception {
    // Index a parent doc and its chunks
    indexDoc("doc-1", "Parent document about machine learning");
    indexChunk("doc-1", 0, 3, "Introduction to neural networks");
    indexChunk("doc-1", 1, 3, "Deep learning fundamentals");
    indexChunk("doc-1", 2, 3, "Conclusion and future work");

    commitAndRefresh();

    // Search for "neural" within doc-1
    var result = runtime.chunkSearchOps().searchChunksForDocs("neural", Set.of("doc-1"), 5);

    assertNotNull(result);
    assertFalse(result.hits().isEmpty(), "Should find chunks matching 'neural'");

    // The public hit map still carries the exact text even though the Lucene field is not stored.
    var neuralHit =
        result.hits().stream()
            .filter(
                hit -> {
                  String content = hit.fields().get(SchemaFields.CHUNK_CONTENT);
                  return content != null && content.toLowerCase(Locale.ROOT).contains("neural");
                })
            .findFirst()
            .orElseThrow(() -> new AssertionError("Should find chunk containing 'neural'"));
    assertEquals(
        "Introduction to neural networks",
        neuralHit.fields().get(SchemaFields.CHUNK_CONTENT));
  }

  @Test
  @DisplayName("generic ReadPath projection reconstructs non-stored chunk_content")
  void genericReadPathProjectionReconstructsChunkContent() throws Exception {
    indexDoc("doc-slices", "Parent prefix");
    String firstId = indexChunk("doc-slices", 0, 2, " first 🚀 slice\r\n");
    String secondId = indexChunk("doc-slices", 1, 2, "```java\r\nsecond();\r\n```");
    commitAndRefresh();
    assertChunkContentNotStored(firstId);
    assertChunkContentNotStored(secondId);

    var result =
        runtime
            .readPathOps()
            .search(
                new org.apache.lucene.search.TermQuery(
                    new org.apache.lucene.index.Term(
                        SchemaFields.PARENT_DOC_ID, "doc-slices")),
                10,
                Set.of(SchemaFields.CHUNK_CONTENT, SchemaFields.CHUNK_INDEX),
                RuntimeSearchSort.RELEVANCE,
                null);

    Map<String, String> contentById = new HashMap<>();
    for (var hit : result.hits()) {
      contentById.put(hit.docId(), hit.fields().get(SchemaFields.CHUNK_CONTENT));
      assertFalse(
          hit.fields().containsKey(SchemaFields.PARENT_DOC_ID),
          "internally fetched parent id must not leak through projection semantics");
      assertFalse(
          hit.fields().containsKey(SchemaFields.CHUNK_START_CHAR),
          "internally fetched slice geometry must not leak through projection semantics");
      assertFalse(
          hit.fields().containsKey(SchemaFields.CHUNK_END_CHAR),
          "internally fetched slice geometry must not leak through projection semantics");
    }

    assertEquals(" first 🚀 slice\r\n", contentById.get(firstId));
    assertEquals("```java\r\nsecond();\r\n```", contentById.get(secondId));
  }

  @Test
  @DisplayName("DocumentFieldOps reconstructs sibling chunks from one parent batch")
  void documentContentBatchReconstructsSiblingChunks() throws Exception {
    indexDoc("doc-batch", "Parent prefix");
    String firstId = indexChunk("doc-batch", 0, 2, "alpha slice");
    String secondId = indexChunk("doc-batch", 1, 2, "beta slice");
    commitAndRefresh();

    Map<String, String> content =
        runtime.documentFieldOps().getDocumentContentBatch(List.of(firstId, secondId));

    assertEquals("alpha slice", content.get(firstId));
    assertEquals("beta slice", content.get(secondId));
  }

  @Test
  @DisplayName("searchChunksForDocs filters by parent document IDs")
  void searchChunksFiltersToSpecifiedParents() throws Exception {
    // Index two parent docs with chunks
    indexDoc("doc-1", "Document about cats");
    indexChunk("doc-1", 0, 2, "Cats are wonderful pets");
    indexChunk("doc-1", 1, 2, "Cats need regular care");

    indexDoc("doc-2", "Document about dogs");
    indexChunk("doc-2", 0, 2, "Dogs are loyal companions");
    indexChunk("doc-2", 1, 2, "Dogs need exercise");

    commitAndRefresh();

    // Search for "pets" only within doc-1
    var result = runtime.chunkSearchOps().searchChunksForDocs("pets", Set.of("doc-1"), 5);

    assertNotNull(result);

    // All results should be from doc-1
    for (var hit : result.hits()) {
      String parentId = hit.fields().get(SchemaFields.PARENT_DOC_ID);
      assertEquals("doc-1", parentId, "All chunks should be from doc-1");
    }
  }

  @Test
  @DisplayName("searchChunksForDocs returns empty when no chunks match")
  void searchChunksReturnsEmptyWhenNoMatch() throws Exception {
    indexDoc("doc-1", "Parent about programming");
    indexChunk("doc-1", 0, 2, "Java programming basics");
    indexChunk("doc-1", 1, 2, "Python programming basics");

    commitAndRefresh();

    // Search for non-existent term
    var result = runtime.chunkSearchOps().searchChunksForDocs("quantum", Set.of("doc-1"), 5);

    assertNotNull(result);
    assertTrue(result.hits().isEmpty(), "Should return empty when no chunks match");
  }

  @Test
  @DisplayName("searchChunksForDocs returns chunk_index and chunk_total in fields")
  void searchChunksReturnsPositionMetadata() throws Exception {
    indexDoc("doc-1", "Parent about algorithms");
    indexChunk("doc-1", 0, 5, "Sorting algorithms overview");
    indexChunk("doc-1", 1, 5, "Bubble sort implementation");
    indexChunk("doc-1", 2, 5, "Quick sort implementation");
    indexChunk("doc-1", 3, 5, "Merge sort implementation");
    indexChunk("doc-1", 4, 5, "Performance comparison of sorting");

    commitAndRefresh();

    // Search for "sort"
    var result = runtime.chunkSearchOps().searchChunksForDocs("sort", Set.of("doc-1"), 5);

    assertNotNull(result);
    assertFalse(result.hits().isEmpty(), "Should find chunks about sorting");

    // Verify position metadata is included
    for (var hit : result.hits()) {
      String chunkIndex = hit.fields().get(SchemaFields.CHUNK_INDEX);
      String chunkTotal = hit.fields().get(SchemaFields.CHUNK_TOTAL);
      assertNotNull(chunkIndex, "chunk_index should be present");
      assertNotNull(chunkTotal, "chunk_total should be present");
      assertEquals("5", chunkTotal, "chunk_total should be 5");
    }
  }

  private static Set<String> largeDocIdSelectionIncluding(String requiredDocId) {
    int targetSize = IndexSearcher.getMaxClauseCount() + 1;
    Set<String> out = new HashSet<>(targetSize);
    out.add(requiredDocId);
    int i = 0;
    while (out.size() < targetSize) {
      out.add("dummy-doc-id-" + i++);
    }
    return out;
  }

  @Test
  @DisplayName("searchChunksForDocs respects limit parameter")
  void searchChunksRespectsLimit() throws Exception {
    indexDoc("doc-1", "Parent about data structures");
    for (int i = 0; i < 10; i++) {
      indexChunk("doc-1", i, 10, "Data structure chapter " + i);
    }

    commitAndRefresh();

    // Search with limit of 3
    var result = runtime.chunkSearchOps().searchChunksForDocs("data", Set.of("doc-1"), 3);

    assertNotNull(result);
    assertEquals(3, result.hits().size(), "Should return exactly 3 chunks (limited)");
  }

  // ========== Phase 6: Chunk-Level Hybrid (chunk vectors) ==========

  @Test
  @DisplayName("searchChunksHybrid (Phase 6) fuses by chunk doc_id (RRF id match)")
  void searchChunksHybridPhase6FusesByChunkDocId() throws Exception {
    indexDoc("doc-1", "Parent content");

    float[] v = new float[] {1f, 0f, 0f, 0f};
    String chunkId = indexChunkWithVector("doc-1", 0, 1, "apple", v);

    commitAndRefresh();

    var result = runtime.chunkSearchOps().searchChunksHybrid("apple", v, Set.of("doc-1"), 1, true, null);

    assertNotNull(result);
    assertEquals(1, result.hits().size(), "Should return exactly 1 fused hit");
    assertEquals(chunkId, result.hits().get(0).docId(), "Fused hit should use chunk doc_id (not parent doc_id)");
  }

  @Test
  @DisplayName("direct RAG chunk retrieval remains recall-first for short/common queries")
  void searchChunksHybridDirectRagDoesNotApplyPlannerSkip() throws Exception {
    indexDoc("doc-1", "Parent content");

    float[] vector = new float[] {1f, 0f, 0f, 0f};
    String chunkId =
        indexChunkWithVector("doc-1", 0, 1, "lexically unrelated material", vector);
    commitAndRefresh();

    var result =
        runtime
            .chunkSearchOps()
            .searchChunksHybrid("the", vector, Set.of("doc-1"), 1, true, null);

    assertEquals(1, result.hits().size(), "the direct RAG path must still execute chunk KNN");
    assertEquals(chunkId, result.hits().get(0).docId());
  }

  @Test
  @DisplayName("searchChunksHybrid (Phase 6) caps vector-only chunks on low-signal queries")
  void searchChunksHybridPhase6CapsVectorOnlyOnLowSignal() throws Exception {
    indexDoc("doc-1", "Parent content");

    // Index many chunks that do NOT match the BM25 query, but do have vectors.
    for (int i = 0; i < 10; i++) {
      indexChunkWithVector("doc-1", i, 10, "unrelated content " + i, new float[] {0f, 0f, 0f, i});
    }

    commitAndRefresh();

    // Use a far-away query vector to force low vector similarity (low-signal gating).
    float[] far = new float[] {1000f, 1000f, 1000f, 1000f};
    int limit = 10;
    int cap = runtime.resolvedConfig().hybridSearch().vectorOnlyCapLowSignal();

    var result = runtime.chunkSearchOps().searchChunksHybrid("nonmatching-query", far, Set.of("doc-1"), limit, true, null);

    assertNotNull(result);
    assertEquals(Math.min(cap, limit), result.hits().size(),
        "Low-signal gating should cap vector-only chunks");
  }

  // ========== Chunk Lifecycle Tests (P0.1/P0.2 regression) ==========

  @Test
  @DisplayName("deleteChunksForParentDocId removes only chunk docs for that parent")
  void deleteChunksForParentRemovesOnlyMatchingChunks() throws Exception {
    // Index a parent doc and its chunks
    indexDoc("parent-a", "Parent A content");
    indexChunk("parent-a", 0, 3, "Chunk 0 content");
    indexChunk("parent-a", 1, 3, "Chunk 1 content");
    indexChunk("parent-a", 2, 3, "Chunk 2 content");

    // Index another parent doc and its chunks
    indexDoc("parent-b", "Parent B content");
    indexChunk("parent-b", 0, 2, "B chunk 0 content");
    indexChunk("parent-b", 1, 2, "B chunk 1 content");

    commitAndRefresh();

    // Verify all docs are indexed
    assertEquals(7, countAllDocs(), "Should have 2 parents + 5 chunks");

    // Delete chunks for parent-a only
    deleteChunksForParent("parent-a");
    commitAndRefresh();

    // Parent-a should still exist
    assertTrue(docExists("parent-a"), "Parent-a should not be deleted");

    // Chunks for parent-a should be gone
    assertFalse(docExists("parent-a#chunk_0"), "parent-a chunk 0 should be deleted");
    assertFalse(docExists("parent-a#chunk_1"), "parent-a chunk 1 should be deleted");
    assertFalse(docExists("parent-a#chunk_2"), "parent-a chunk 2 should be deleted");

    // Parent-b and its chunks should still exist
    assertTrue(docExists("parent-b"), "Parent-b should not be deleted");
    assertTrue(docExists("parent-b#chunk_0"), "parent-b chunk 0 should not be deleted");
    assertTrue(docExists("parent-b#chunk_1"), "parent-b chunk 1 should not be deleted");
  }

  @Test
  @DisplayName("deleteChunksForParentDocId works with opaque chunk IDs (P0.8)")
  void deleteChunksWorksWithOpaqueIds() throws Exception {
    // Index parent and chunks using new opaque UUID format
    indexDoc("parent-opaque", "Parent with opaque chunks");
    indexChunkOpaque("parent-opaque", 0, 3, "Opaque chunk 0");
    indexChunkOpaque("parent-opaque", 1, 3, "Opaque chunk 1");
    indexChunkOpaque("parent-opaque", 2, 3, "Opaque chunk 2");

    commitAndRefresh();

    // Verify all docs indexed
    assertEquals(4, countAllDocs(), "Should have 1 parent + 3 opaque chunks");

    // Delete chunks using field-based method (should work regardless of doc_id format)
    deleteChunksForParent("parent-opaque");
    commitAndRefresh();

    // Parent should remain
    assertTrue(docExists("parent-opaque"), "Parent should not be deleted");

    // Should only have parent now (all chunks deleted via parent_doc_id field match)
    assertEquals(1, countAllDocs(), "Should have only parent remaining after chunk deletion");
  }

  @Test
  @DisplayName("Chunk shrink scenario: old chunks deleted when new count is smaller")
  void chunkShrinkDeletesOldChunks() throws Exception {
    // Initial index with 5 chunks
    indexDoc("doc-1", "Original content");
    for (int i = 0; i < 5; i++) {
      indexChunk("doc-1", i, 5, "Original chunk " + i);
    }
    commitAndRefresh();
    assertEquals(6, countAllDocs(), "Should have 1 parent + 5 chunks");

    // Simulate reindex with fewer chunks:
    // 1. Delete old chunks
    deleteChunksForParent("doc-1");
    // 2. Index new chunks (only 2 this time)
    indexChunk("doc-1", 0, 2, "New chunk 0");
    indexChunk("doc-1", 1, 2, "New chunk 1");
    commitAndRefresh();

    // Should have parent + 2 new chunks only
    assertEquals(3, countAllDocs(), "Should have 1 parent + 2 new chunks");

    // Old chunks should not exist
    assertFalse(docExists("doc-1#chunk_2"), "Old chunk 2 should be gone");
    assertFalse(docExists("doc-1#chunk_3"), "Old chunk 3 should be gone");
    assertFalse(docExists("doc-1#chunk_4"), "Old chunk 4 should be gone");

    // New chunks should exist
    assertTrue(docExists("doc-1#chunk_0"), "New chunk 0 should exist");
    assertTrue(docExists("doc-1#chunk_1"), "New chunk 1 should exist");
  }

  // ========== Doc-level union leg (tempdoc 749, option B) ==========

  @Test
  @DisplayName("findParentDocIdsWithChunks returns only candidates that have chunk docs")
  void findParentDocIdsWithChunksReturnsChunkedSubset() throws Exception {
    // Parent A has a chunk doc; parent B has none.
    indexDoc("parent-a", "Parent A about migratory birds");
    indexChunk("parent-a", 0, 1, "Migratory birds travel long distances");
    indexDoc("parent-b", "Parent B about desert reptiles");

    commitAndRefresh();

    Set<String> probed =
        runtime.chunkSearchOps().findParentDocIdsWithChunks(Set.of("parent-a", "parent-b"));
    assertEquals(Set.of("parent-a"), probed,
        "Only parent-a (which owns a chunk doc) should be reported as chunked");

    // Empty probe short-circuits to empty.
    assertTrue(runtime.chunkSearchOps().findParentDocIdsWithChunks(Set.of()).isEmpty(),
        "Empty candidate set returns empty");
  }

  @Test
  @DisplayName(
      "findParentDocIdsWithChunks: a parent with SEVERAL chunk docs is still classified "
          + "chunked, and a chunkless sibling among the same candidates is not (review Fix 2 — "
          + "per-parent existence probes, not a shared result-window)")
  void findParentDocIdsWithChunksHandlesMultiChunkParent() throws Exception {
    // Parent C owns 3 chunk docs; parent D has none.
    indexDoc("parent-c", "Parent C about renewable energy policy");
    indexChunk("parent-c", 0, 3, "Solar power adoption is accelerating");
    indexChunk("parent-c", 1, 3, "Wind energy capacity has doubled");
    indexChunk("parent-c", 2, 3, "Grid storage remains the bottleneck");
    indexDoc("parent-d", "Parent D about unrelated topic");

    commitAndRefresh();

    Set<String> probed =
        runtime.chunkSearchOps().findParentDocIdsWithChunks(Set.of("parent-c", "parent-d"));
    assertEquals(Set.of("parent-c"), probed,
        "parent-c (3 chunk docs) is classified chunked; chunkless parent-d is not");
  }

  @Test
  @DisplayName("searchFullDocs: unscoped finds all parents; scoped filters; excludes chunk docs")
  void searchFullDocsUnscopedScopedAndChunkExclusion() throws Exception {
    indexDoc("doc-p1", "The quick brown fox jumps");
    indexDoc("doc-p2", "A red fox runs through the forest");

    // A chunk doc that ALSO carries a CONTENT field mentioning the query term — this is exactly
    // what the IS_CHUNK MUST_NOT clause must keep out of full-doc results.
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, "chunk:fox-000",
        SchemaFields.DOC_UID, "chunk:fox-000#0",
        SchemaFields.IS_CHUNK, "true",
        SchemaFields.PARENT_DOC_ID, "doc-p1",
        SchemaFields.CHUNK_INDEX, "0",
        SchemaFields.CHUNK_TOTAL, "1",
        SchemaFields.CHUNK_CONTENT, "fox chunk content",
        SchemaFields.CONTENT, "fox also appears in this chunk document body",
        SchemaFields.PATH, "doc-p1")));

    commitAndRefresh();

    // Unscoped (empty docIds): both parents match, and the chunk doc is excluded despite its
    // CONTENT field containing "fox". null additionalFilter is accepted.
    var unscoped = runtime.chunkSearchOps().searchFullDocs("fox", Set.of(), 10, null);
    assertNotNull(unscoped);
    assertEquals(2, unscoped.hits().size(), "Unscoped search returns both parent docs");
    Set<String> unscopedIds =
        unscoped.hits().stream().map(h -> h.docId()).collect(java.util.stream.Collectors.toSet());
    assertEquals(Set.of("doc-p1", "doc-p2"), unscopedIds,
        "Only the two parents come back — never the chunk doc (IS_CHUNK MUST_NOT)");

    // Scoped to {doc-p2}: only doc-p2 comes back.
    var scoped = runtime.chunkSearchOps().searchFullDocs("fox", Set.of("doc-p2"), 10, null);
    assertEquals(1, scoped.hits().size(), "Scoped search returns exactly the in-scope parent");
    assertEquals("doc-p2", scoped.hits().get(0).docId());

    // Blank query is empty regardless of scope.
    var blank = runtime.chunkSearchOps().searchFullDocs("   ", Set.of(), 10, null);
    assertTrue(blank.hits().isEmpty(), "Blank query yields no full-doc hits");

    // Regression: searchFullDocsForDocs keeps its return-empty-on-empty-scope contract, which
    // is deliberately distinct from searchFullDocs' unscoped-means-all-docs behaviour above.
    var emptyScope = runtime.chunkSearchOps().searchFullDocsForDocs("fox", Set.of(), 5);
    assertTrue(emptyScope.hits().isEmpty(),
        "searchFullDocsForDocs still returns empty on empty docIds (contract unchanged)");
  }

  @Test
  @DisplayName("searchDocLevelUnion with null queryVector dispatches to the BM25 full-doc path")
  void searchDocLevelUnionNullVectorUsesBm25() throws Exception {
    indexDoc("doc-u1", "Coral reefs support diverse marine life");
    indexDoc("doc-u2", "Coral bleaching threatens reef ecosystems");

    commitAndRefresh();

    // Null queryVector forces the BM25 (searchFullDocs) branch; both parents match "coral".
    var result =
        runtime.chunkSearchOps().searchDocLevelUnion("coral reef", null, Set.of(), 10, null);
    assertNotNull(result);
    assertEquals(2, result.hits().size(), "BM25 union path returns both matching parents");
    Set<String> ids =
        result.hits().stream().map(h -> h.docId()).collect(java.util.stream.Collectors.toSet());
    assertEquals(Set.of("doc-u1", "doc-u2"), ids);
    // The hybrid (usable-queryVector) branch's RRF ranking is covered by the live R9 validation;
    // its symmetric IS_CHUNK exclusion is unit-tested below (searchDocLevelUnionHybridExcludesChunkDocs).
  }

  @Test
  @DisplayName("searchDocLevelUnion (hybrid branch) excludes chunk docs by construction (tempdoc 749 review PF-2)")
  void searchDocLevelUnionHybridExcludesChunkDocs() throws Exception {
    // Regular parent: matches the BM25 leg via content, carries no vector of its own.
    indexDoc("doc-1", "Coral reefs support diverse marine life");

    // A "poisoned" chunk doc that also happens to carry parent-level content + vector — the
    // latent double-surface trap this guard exists to prevent. A chunk doc must never be
    // eligible for the doc-level union, regardless of what fields it carries.
    float[] queryVector = new float[] {1f, 0f, 0f, 0f};
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, "doc-1#chunk_0",
        SchemaFields.DOC_UID, "doc-1#chunk_0#0",
        SchemaFields.IS_CHUNK, "true",
        SchemaFields.PARENT_DOC_ID, "doc-1",
        SchemaFields.CONTENT, "Coral reefs support diverse marine life",
        SchemaFields.VECTOR, queryVector,
        SchemaFields.PATH, "doc-1"
    )));

    commitAndRefresh();

    // Non-null, non-empty queryVector and no query-skip condition -> dispatches to the hybrid
    // branch (searchHybridFiltered), not the BM25 (searchFullDocs) fallback.
    var result =
        runtime.chunkSearchOps().searchDocLevelUnion("coral reef", queryVector, Set.of(), 10, null);

    assertNotNull(result);
    for (var hit : result.hits()) {
      assertFalse(
          "doc-1#chunk_0".equals(hit.docId()),
          "Chunk doc must never surface from the doc-level union hybrid branch, even though it "
              + "carries matching content + vector. Got: " + hit.docId());
    }
  }

  // ========== Chunk-branch filter scoping (human-validation finding 4) ==========

  /**
   * The chunk branch runs its legs under {@link QueryFilterBuilder#buildChunkFilterQuery}, which used
   * to drop {@code pathPrefix} on the premise that a chunk's {@code PATH} "stores parentDocId, not
   * the file path". It stores both: the parent's {@code DOC_ID} IS its absolute path. Dropping the
   * scope let the chunk branch retrieve candidates from outside the requested prefix, which enter
   * the fused candidate union the response reports as {@code totalHits} and — at a high enough fused
   * rank — leak into {@code results}.
   */
  @Test
  @DisplayName("chunk retrieval is scoped by pathPrefix (out-of-prefix parents must not come back)")
  void chunkFilterScopesByPathPrefix() throws Exception {
    String insideDir = QueryFilterBuilder.normalizePathPrefix("corpus/inside");
    String outsideDir = QueryFilterBuilder.normalizePathPrefix("corpus/outside");
    String insideDoc = insideDir + "note-a.md";
    String outsideDoc = outsideDir + "note-b.md";

    indexDoc(insideDoc, "parent inside the requested prefix");
    indexChunk(insideDoc, 0, 1, "neural networks overview");
    indexDoc(outsideDoc, "parent outside the requested prefix");
    indexChunk(outsideDoc, 0, 1, "neural networks overview");
    commitAndRefresh();

    var filters =
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
            .pathPrefix("corpus/inside")
            .build();
    var chunkFilter = QueryFilterBuilder.buildChunkFilterQuery(filters);
    var result = runtime.chunkSearchOps().searchChunksText("neural", 10, chunkFilter);

    assertNotNull(result);
    assertEquals(
        1,
        result.hits().size(),
        "the chunk leg must retrieve only chunks whose parent is under the requested pathPrefix; "
            + "an extra hit means the out-of-prefix parent's chunk was retrieved");
    assertEquals(
        insideDoc,
        result.hits().get(0).fields().get(SchemaFields.PARENT_DOC_ID),
        "the surviving chunk must belong to the in-prefix parent");
  }

  @Test
  @DisplayName("chunk retrieval is scoped by doc_ids (PATH holds the parent's absolute path)")
  void chunkFilterScopesByDocIds() throws Exception {
    String insideDir = QueryFilterBuilder.normalizePathPrefix("corpus/inside");
    String insideDoc = insideDir + "note-a.md";
    String otherDoc = insideDir + "note-b.md";

    indexDoc(insideDoc, "first parent");
    indexChunk(insideDoc, 0, 1, "neural networks overview");
    indexDoc(otherDoc, "second parent");
    indexChunk(otherDoc, 0, 1, "neural networks overview");
    commitAndRefresh();

    var filters =
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
            .docIds(List.of(insideDoc))
            .build();
    var chunkFilter = QueryFilterBuilder.buildChunkFilterQuery(filters);
    var result = runtime.chunkSearchOps().searchChunksText("neural", 10, chunkFilter);

    assertNotNull(result);
    assertEquals(1, result.hits().size(), "doc_ids must scope the chunk leg to the named documents");
    assertEquals(
        insideDoc,
        result.hits().get(0).fields().get(SchemaFields.PARENT_DOC_ID),
        "the surviving chunk must belong to the requested document");
  }

  @Test
  @DisplayName("an unfiltered request still produces a chunk filter: the default collection scope")
  void chunkFilterCarriesDefaultCollectionScopeWithoutFilters() {
    // Tempdoc 811 item 3 supersedes the previous contract (an all-default filter set produced NO
    // chunk filter). The default agent-history exclusion is part of the default scope, so it must
    // be present on the chunk branch too — otherwise agent-history chunks enter the fused union
    // whenever no pathPrefix/doc_ids filter is set.
    var filters = LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder().build();
    var q = QueryFilterBuilder.buildChunkFilterQuery(filters);
    assertNotNull(q, "the default agent-history exclusion always produces a chunk filter");
    String s = q.toString();
    assertTrue(s.contains("-collection:agent-history"), "agent-history is MUST_NOT excluded: " + s);
    assertTrue(s.contains("*:*"), "the pure-negative filter is anchored with MatchAllDocs: " + s);
  }

  // ========== Tempdoc 811 item 3 — collection scoping on the chunk branch ==========

  @Test
  @DisplayName("an agent-history parent's chunk is excluded from a default-scope chunk search")
  void chunkFilterDefaultExcludesAgentHistoryChunks() throws Exception {
    indexDocInCollection("agent-run.md", "transcript parent", SchemaFields.AGENT_HISTORY_COLLECTION);
    indexChunkInCollection(
        "agent-run.md", 0, 1, "neural networks overview", SchemaFields.AGENT_HISTORY_COLLECTION);
    indexDoc("user-note.md", "ordinary parent");
    indexChunk("user-note.md", 0, 1, "neural networks overview");
    commitAndRefresh();

    var chunkFilter =
        QueryFilterBuilder.buildChunkFilterQuery(
            LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder().build());
    var result = runtime.chunkSearchOps().searchChunksText("neural", 10, chunkFilter);

    assertNotNull(result);
    assertEquals(
        1,
        result.hits().size(),
        "the default scope must drop the agent-history parent's chunk; an extra hit means an "
            + "indexed transcript's chunk entered the candidate union");
    assertEquals(
        "user-note.md",
        result.hits().get(0).fields().get(SchemaFields.PARENT_DOC_ID),
        "the surviving chunk must belong to the ordinary parent");
  }

  @Test
  @DisplayName("an explicit agent-history scope includes that parent's chunks (and only those)")
  void chunkFilterExplicitAgentHistoryScopeIncludesChunks() throws Exception {
    indexDocInCollection("agent-run.md", "transcript parent", SchemaFields.AGENT_HISTORY_COLLECTION);
    indexChunkInCollection(
        "agent-run.md", 0, 1, "neural networks overview", SchemaFields.AGENT_HISTORY_COLLECTION);
    indexDoc("user-note.md", "ordinary parent");
    indexChunk("user-note.md", 0, 1, "neural networks overview");
    commitAndRefresh();

    var chunkFilter =
        QueryFilterBuilder.buildChunkFilterQuery(
            LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
                .collection(List.of(SchemaFields.AGENT_HISTORY_COLLECTION))
                .build());
    var result = runtime.chunkSearchOps().searchChunksText("neural", 10, chunkFilter);

    assertNotNull(result);
    assertEquals(
        1, result.hits().size(), "an explicit scope is a positive include filter, not an exclusion");
    assertEquals(
        "agent-run.md",
        result.hits().get(0).fields().get(SchemaFields.PARENT_DOC_ID),
        "the surviving chunk must belong to the agent-history parent");
  }

  @Test
  @DisplayName("a default-collection parent's chunks are unaffected by the collection scope")
  void chunkFilterLeavesUntaggedChunksAlone() throws Exception {
    indexDoc("user-note.md", "ordinary parent");
    indexChunk("user-note.md", 0, 1, "neural networks overview");
    indexDoc("other-note.md", "another ordinary parent");
    indexChunk("other-note.md", 0, 1, "neural networks overview");
    commitAndRefresh();

    var chunkFilter =
        QueryFilterBuilder.buildChunkFilterQuery(
            LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder().build());
    var result = runtime.chunkSearchOps().searchChunksText("neural", 10, chunkFilter);

    assertNotNull(result);
    assertEquals(
        2,
        result.hits().size(),
        "the MUST_NOT only matches docs carrying the agent-history tag — untagged chunks pass");
  }

  // ========== Tempdoc 811 D-1 — the null-filters bypass ==========

  @Test
  @DisplayName("a null-filters text search still excludes agent-history documents")
  void nullFiltersTextSearchExcludesAgentHistory() throws Exception {
    // The production chain: HybridSearchOps.searchHybrid -> textQueryOps.searchText(t, l, null)
    // -> buildTextQuery(text, null) -> applyRuntimeFilters(query, null). Before tempdoc 811 D-1
    // that path returned BEFORE addCollectionScope, so it searched indexed agent transcripts.
    indexDocInCollection("agent-run.md", "neural networks overview", SchemaFields.AGENT_HISTORY_COLLECTION);
    indexDoc("user-note.md", "neural networks overview");
    commitAndRefresh();

    var result = runtime.textQueryOps().searchText("neural", 10, null);

    assertNotNull(result);
    assertEquals(1, result.hits().size(), "null filters must mean the DEFAULT scope, not no scope");
    assertEquals("user-note.md", result.hits().get(0).docId());
  }

  @Test
  @DisplayName("an explicit agent-history scope still returns transcripts on the text path")
  void explicitAgentHistoryScopeTextSearchIncludesTranscripts() throws Exception {
    indexDocInCollection("agent-run.md", "neural networks overview", SchemaFields.AGENT_HISTORY_COLLECTION);
    indexDoc("user-note.md", "neural networks overview");
    commitAndRefresh();

    var filters =
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
            .collection(List.of(SchemaFields.AGENT_HISTORY_COLLECTION))
            .build();
    var result = runtime.textQueryOps().searchText("neural", 10, filters);

    assertNotNull(result);
    assertEquals(1, result.hits().size());
    assertEquals("agent-run.md", result.hits().get(0).docId());
  }

  // ========== Chunk-leg tie-break (lane F PR 0b) ==========

  /**
   * Lane F PR 0b — a chunk BM25 tie must break on a key that is stable ACROSS INDEX BUILDS.
   *
   * <p>Three chunks with byte-identical content tie exactly on BM25, and each is committed on its
   * own so each lands in its own segment. Two things could decide their order, and both are
   * per-build random:
   *
   * <ul>
   *   <li>Lucene's INTERNAL docId — what {@code searcher.search(query, n)} falls back to. It
   *       follows the commit order, which a different segment layout renumbers.
   *   <li>the chunk's {@code doc_id} — what the first version of this fix sorted on. On a chunk row
   *       that is {@code ChunkIds.newChunkDocId()} = {@code "chunk:" + UUID.randomUUID()}
   *       ({@code ChunkIds.java:51-53}), minted fresh per ingest and deliberately not derived from
   *       the parent or the chunk index. Stable within one index, uncorrelated between two.
   * </ul>
   *
   * <p>So the fixture mints REAL chunk ids through {@code ChunkIds.newChunkDocId()} and forces both
   * adversaries: the commit order is non-ascending, and the minted ids are re-drawn until their
   * lexicographic order disagrees with the chunk-index order. Under either of those two tie-breaks
   * this test fails deterministically — not with probability 5/6. What must hold is that both
   * builds return the chunks in {@code parent_doc_id} then {@code chunk_index} order, which two
   * ingests of the same corpus agree on.
   */
  @Test
  @DisplayName("chunk BM25 ties break on a key that is stable across index builds")
  void chunkBm25TiesBreakOnAKeyThatIsStableAcrossBuilds() throws Exception {
    // Each probe opens its OWN index; the @BeforeEach one holds the ephemeral write lock, and the
    // two probes must not overlap either, so they are opened and closed strictly in sequence.
    runtime.close();
    runtime = null;

    List<String> expected = List.of("0", "1", "2");

    // Build A: the discriminating layout — the HIGHEST chunk index is committed first, so the
    // internal-docId order disagrees with the chunk-index order.
    TieBreakProbe buildA = probeTiedChunkOrder(List.of(2, 0, 1));
    assertNotEquals(
        expected,
        buildA.rawInternalDocIdOrder(),
        "fixture precondition: the unsorted (internal-docId) order must differ from the "
            + "chunk-index order, otherwise this test would pass for the wrong reason");
    assertNotEquals(
        expected,
        buildA.chunkIndexOrderIfSortedByDocId(),
        "fixture precondition: the minted chunk UUIDs must not happen to sort into chunk-index "
            + "order, otherwise a doc_id tie-break would pass this test for the wrong reason");
    assertEquals(
        expected, buildA.sortedChunkIndexOrder(), "chunk BM25 ties must break on parent+index");

    // Build B: the same three chunks, opposite insertion order, and a FRESH draw of chunk UUIDs.
    TieBreakProbe buildB = probeTiedChunkOrder(List.of(0, 1, 2));
    assertNotEquals(
        expected,
        buildB.chunkIndexOrderIfSortedByDocId(),
        "fixture precondition: build B's minted UUIDs must not sort into chunk-index order either");
    assertEquals(
        expected, buildB.sortedChunkIndexOrder(), "chunk BM25 ties must break on parent+index");

    // The two builds drew different chunk ids — the point of the whole test. If this ever held,
    // the two builds would share a doc_id ordering and the assertions above would prove nothing
    // about cross-build stability.
    assertNotEquals(
        buildA.docIdOrder(),
        buildB.docIdOrder(),
        "the two builds must have drawn different chunk UUIDs");
    assertEquals(
        buildA.sortedChunkIndexOrder(),
        buildB.sortedChunkIndexOrder(),
        "two index builds of the same documents must return the same chunk order");
  }

  /**
   * Lane F PR 0b — the FIRST tie-break comparator, {@code parent_doc_id}, exercised on its own.
   *
   * <p>{@link #chunkBm25TiesBreakOnAKeyThatIsStableAcrossBuilds} uses one parent, so {@code
   * chunk_index} alone decides it and a {@code parent_doc_id} comparator that silently did nothing
   * — a missing docvalues column, a misspelled field — would still let it pass. Across parents
   * that hole is real: every chunk's index is 0, so with {@code parent_doc_id} inert the order
   * falls straight through to the per-ingest UUID, which is the defect this whole change exists to
   * remove.
   *
   * <p>Both adversaries are forced again: the LATER parent's chunk is committed first (so the
   * internal-docId order is b, a) and the two chunk ids are drawn until the LATER parent's sorts
   * first lexicographically (so a {@code doc_id} tie-break also says b, a). Only an effective
   * {@code parent_doc_id} comparator produces a, b.
   */
  @Test
  @DisplayName("chunk BM25 ties break on parent_doc_id before chunk_index")
  void chunkBm25TiesBreakOnTheParentPathAcrossParents() throws Exception {
    runtime.close();
    runtime = null;

    List<List<String>> observed = new java.util.ArrayList<>();
    for (int build = 0; build < 2; build++) {
      observed.add(probeTwoParentTieOrder());
    }
    assertEquals(
        List.of("tie-a", "tie-b"),
        observed.get(0),
        "a chunk tie across parents must order by the parent's deterministic path");
    assertEquals(
        observed.get(0),
        observed.get(1),
        "and two index builds with different chunk UUIDs must agree on it");
  }

  /**
   * Lane F PR 0b, second review B1 — the DENSE chunk leg, which the two tests above do not reach.
   *
   * <p>{@code searchChunksText}/{@code searchChunksSplade} go through {@code
   * searchChunksWithStableTieBreak}. {@code searchChunkVector} does not: it builds a {@link
   * org.apache.lucene.search.KnnFloatVectorQuery} and hands it to {@code ReadPathOps.search},
   * which until this change sorted by {@code buildRuntimeSort(RELEVANCE, doc_id)} — the DOCUMENT
   * tie-break, whose secondary key on a chunk row is the per-ingest {@code
   * ChunkIds.newChunkDocId()} UUID. So the leg the two tests above certify was fixed while the
   * dense leg beside it still ordered its ties at random per build.
   *
   * <p>Ties are not exotic here: two chunks with the same text get byte-identical vectors, so
   * their kNN similarity is exactly equal. Under {@code index.vector.exhaustive_search} the leg
   * returns the whole corpus, so every such tie is in the result rather than possibly beyond k.
   *
   * <p>The fixture forces both adversaries, as above: the chunks are committed in the REVERSE of
   * the expected order (so the internal-docId order is the opposite), and the minted ids are
   * assigned so that their lexicographic order is the opposite too. Only an effective {@code
   * parent_doc_id} then {@code chunk_index} sort produces the expected order.
   */
  @Test
  @DisplayName("chunk kNN ties break on parent_doc_id then chunk_index, not the per-ingest UUID")
  void chunkVectorTiesBreakOnAKeyThatIsStableAcrossBuilds() throws Exception {
    runtime.close();
    runtime = null;

    List<String> expected = List.of("tie-a/0", "tie-a/1", "tie-b/0", "tie-b/1");

    DenseTieProbe buildA = probeTiedChunkVectorOrder();
    assertNotEquals(
        expected,
        buildA.internalDocIdOrder(),
        "fixture precondition: the commit (internal-docId) order must differ from the expected "
            + "order, otherwise this test would pass for the wrong reason");
    assertNotEquals(
        expected,
        buildA.orderIfSortedByDocId(),
        "fixture precondition: the minted chunk UUIDs must not sort into the expected order, "
            + "otherwise the old doc_id tie-break would pass this test for the wrong reason");
    assertEquals(
        expected, buildA.observedOrder(), "chunk kNN ties must break on parent_doc_id then index");

    // A second build over the same documents, with a fresh draw of chunk UUIDs.
    DenseTieProbe buildB = probeTiedChunkVectorOrder();
    assertNotEquals(
        expected,
        buildB.orderIfSortedByDocId(),
        "fixture precondition: build B's minted UUIDs must not sort into the expected order");
    assertNotEquals(
        buildA.docIdOrder(),
        buildB.docIdOrder(),
        "the two builds must have drawn different chunk UUIDs");
    assertEquals(
        buildA.observedOrder(),
        buildB.observedOrder(),
        "two index builds of the same documents must return the same dense-leg chunk order");
  }

  /**
   * Lane F PR 0b, second review B1 — the two cursor branches the {@code Sort} override adds.
   *
   * <p>{@code SearchAfterCursorHelper} encodes and decodes against the {@code RuntimeSearchSort},
   * not against the Lucene {@code Sort} the search actually ran with. Combining the two would
   * therefore page through an order the cursor does not describe — silently. So an inbound cursor
   * is refused outright, and an outbound one is withheld. Both directions are asserted here
   * because only one of them is on the path the chunk legs take, and the untaken branch is the
   * one that would rot.
   */
  @Test
  @DisplayName("an explicit Sort override refuses an inbound cursor and mints no outbound one")
  void sortOverrideAndSearchAfterCursorsAreMutuallyExclusive() throws Exception {
    org.apache.lucene.search.Sort override =
        LuceneRuntimeUtils.buildChunkTieBreakSort(SchemaFields.DOC_ID);
    org.apache.lucene.search.Query matchAll = new org.apache.lucene.search.MatchAllDocsQuery();

    IllegalArgumentException refused =
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () ->
                runtime
                    .readPathOps()
                    .search(matchAll, 1, null, RuntimeSearchSort.RELEVANCE, "some-cursor",
                        override));
    assertTrue(
        refused.getMessage().contains("cursor"),
        "the refusal must name the cursor as the reason: " + refused.getMessage());

    // Two parents, so a limit of 1 leaves more to page through: without an override this search
    // mints a cursor, which is what makes the null below evidence rather than a vacuous pass.
    for (String docId : List.of("cursor-a", "cursor-b")) {
      Map<String, Object> parent = new LinkedHashMap<>();
      parent.put(SchemaFields.DOC_ID, docId);
      parent.put(SchemaFields.DOC_UID, docId + "#0");
      parent.put(SchemaFields.CONTENT, TIE_CHUNK_TEXT);
      parent.put(SchemaFields.PATH, docId);
      parent.put(SchemaFields.CONTENT_SHA256, ChunkParentRevision.sha256Hex(TIE_CHUNK_TEXT));
      runtime.indexingCoordinator().indexSingle(new IndexDocument(parent));
    }
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    assertNotNull(
        runtime.readPathOps().search(matchAll, 1, null, RuntimeSearchSort.RELEVANCE, null)
            .nextCursor(),
        "fixture precondition: the same search without an override must mint a cursor");
    assertNull(
        runtime
            .readPathOps()
            .search(matchAll, 1, null, RuntimeSearchSort.RELEVANCE, null, override)
            .nextCursor(),
        "a search ordered by an overriding Sort must not mint a cursor encoded against the "
            + "RuntimeSearchSort it did not use");
  }

  /**
   * What one dense tie-break probe build observed.
   *
   * @param observedOrder the kNN leg's hits as their {@code parent/index} keys, in returned order
   * @param docIdOrder the same hits' minted chunk ids, in returned order
   * @param orderIfSortedByDocId what a {@code doc_id} tie-break would have produced
   * @param internalDocIdOrder the commit order, which is the unsorted (internal-docId) order
   */
  private record DenseTieProbe(
      List<String> observedOrder,
      List<String> docIdOrder,
      List<String> orderIfSortedByDocId,
      List<String> internalDocIdOrder) {}

  /** The one vector every tie-break chunk carries: identical bytes mean an exactly equal score. */
  private static final float[] TIE_CHUNK_VECTOR = {0.25f, -0.5f, 0.75f, 1.0f};

  /** The dense probe's four chunks, in the order the tie-break must deliver them. */
  private static final List<String> DENSE_TIE_EXPECTED_KEYS =
      List.of("tie-a/0", "tie-a/1", "tie-b/0", "tie-b/1");

  /** The same four, reversed: the commit order, and the lexicographic order of the minted ids. */
  private static final List<String> DENSE_TIE_ADVERSARIAL_KEYS =
      List.of("tie-b/1", "tie-b/0", "tie-a/1", "tie-a/0");

  /**
   * Draws one real chunk id per key and assigns them so the ids' lexicographic order is {@link
   * #DENSE_TIE_ADVERSARIAL_KEYS} — the exact reverse of what the tie-break must produce.
   *
   * <p>Assigning a sorted draw rather than re-drawing until the permutation appears keeps this
   * deterministic: a 4-key re-draw loop would hit the wanted permutation with probability 1/24 per
   * attempt and so carry a real flake rate. The ids are still genuine {@code
   * ChunkIds.newChunkDocId()} values, freshly drawn per build.
   */
  private static Map<String, String> mintReverseOrderedChunkIds() {
    List<String> drawn = new java.util.ArrayList<>();
    for (int i = 0; i < DENSE_TIE_ADVERSARIAL_KEYS.size(); i++) {
      drawn.add(ChunkIds.newChunkDocId());
    }
    java.util.Collections.sort(drawn);
    Map<String, String> ids = new LinkedHashMap<>();
    for (int i = 0; i < DENSE_TIE_ADVERSARIAL_KEYS.size(); i++) {
      ids.put(DENSE_TIE_ADVERSARIAL_KEYS.get(i), drawn.get(i));
    }
    return ids;
  }

  /**
   * Opens a FRESH ephemeral index holding two parents with two identical-vector chunks each,
   * committed one per segment in the REVERSE of the expected order, and reports what the dense
   * chunk leg returned alongside the two orders that must NOT have decided it.
   */
  private DenseTieProbe probeTiedChunkVectorOrder() throws Exception {
    Map<String, String> chunkIds = mintReverseOrderedChunkIds();

    RunningRuntime probe =
        IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(4)).ephemeral().open();
    try {
      String parentContent = TIE_CHUNK_TEXT + " " + TIE_CHUNK_TEXT;
      for (String parentDocId : List.of("tie-a", "tie-b")) {
        Map<String, Object> parent = new LinkedHashMap<>();
        parent.put(SchemaFields.DOC_ID, parentDocId);
        parent.put(SchemaFields.DOC_UID, parentDocId + "#0");
        parent.put(SchemaFields.CONTENT, parentContent);
        parent.put(SchemaFields.PATH, parentDocId);
        parent.put(SchemaFields.CONTENT_SHA256, ChunkParentRevision.sha256Hex(parentContent));
        probe.indexingCoordinator().indexSingle(new IndexDocument(parent));
      }
      probe.commitOps().commitAndTrack();

      int span = TIE_CHUNK_TEXT.length();
      for (String key : DENSE_TIE_ADVERSARIAL_KEYS) {
        int slash = key.indexOf('/');
        String parentDocId = key.substring(0, slash);
        int index = Integer.parseInt(key.substring(slash + 1));
        int start = index * (span + 1);
        String chunkId = chunkIds.get(key);
        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put(SchemaFields.DOC_ID, chunkId);
        chunk.put(SchemaFields.DOC_UID, chunkId + "#0");
        chunk.put(SchemaFields.IS_CHUNK, "true");
        chunk.put(SchemaFields.PARENT_DOC_ID, parentDocId);
        chunk.put(SchemaFields.CHUNK_INDEX, String.valueOf(index));
        chunk.put(SchemaFields.CHUNK_TOTAL, "2");
        chunk.put(SchemaFields.CHUNK_CONTENT, TIE_CHUNK_TEXT);
        chunk.put(SchemaFields.CHUNK_START_CHAR, String.valueOf(start));
        chunk.put(SchemaFields.CHUNK_END_CHAR, String.valueOf(start + span));
        chunk.put(SchemaFields.CHUNK_VECTOR, TIE_CHUNK_VECTOR.clone());
        chunk.put(SchemaFields.PATH, parentDocId);
        probe.indexingCoordinator().indexSingle(new IndexDocument(chunk));
        // One commit per chunk: each lands in its own segment, so the internal docId order is the
        // commit order.
        probe.commitOps().commitAndTrack();
      }
      probe.commitOps().maybeRefreshBlocking();

      var hits =
          probe.chunkSearchOps().searchChunkVector(TIE_CHUNK_VECTOR.clone(), null, 10, null).hits();
      assertEquals(
          DENSE_TIE_EXPECTED_KEYS.size(),
          hits.size(),
          "fixture precondition: the dense leg must return every tied chunk, or the ordering "
              + "assertion would only cover a prefix");

      // Resolve each hit back to its parent/index key through the ids this build actually minted,
      // rather than through projected fields: the dense leg passes a null projection, so what
      // lands in fields() is whatever the catalog happens to store.
      Map<String, String> keyByChunkId = new HashMap<>();
      chunkIds.forEach((key, id) -> keyByChunkId.put(id, key));
      List<String> docIdOrder = hits.stream().map(LuceneRuntimeTypes.SearchHit::docId).toList();
      List<String> observed = docIdOrder.stream().map(keyByChunkId::get).toList();
      assertFalse(
          observed.contains(null),
          "every returned hit must be one of the four chunks this build indexed");

      // Derived from the drawn ids, not restated: this is what a doc_id tie-break would produce,
      // and the test asserts the observed order is NOT it.
      List<String> orderIfSortedByDocId =
          chunkIds.entrySet().stream()
              .sorted(Map.Entry.comparingByValue())
              .map(Map.Entry::getKey)
              .toList();
      return new DenseTieProbe(
          observed, docIdOrder, orderIfSortedByDocId, DENSE_TIE_ADVERSARIAL_KEYS);
    } finally {
      probe.close();
    }
  }

  /**
   * One parent-order probe: two parents, one identical-content chunk each at index 0, with the
   * commit order and the minted ids both arranged to disagree with the parent-path order.
   *
   * @return the returned hits' {@code parent_doc_id} values, in the order the BM25 leg produced
   */
  private List<String> probeTwoParentTieOrder() throws Exception {
    // Draw until the LATER parent's chunk id sorts FIRST, so a doc_id tie-break cannot produce the
    // expected order by luck. One draw in two already satisfies it; the loop makes it certain.
    String idForA;
    String idForB;
    int attempt = 0;
    do {
      idForA = ChunkIds.newChunkDocId();
      idForB = ChunkIds.newChunkDocId();
      attempt++;
    } while (idForB.compareTo(idForA) >= 0 && attempt < 100);
    assertTrue(
        idForB.compareTo(idForA) < 0,
        "fixture precondition: tie-b's chunk id must sort before tie-a's, or a doc_id tie-break "
            + "would produce the expected order for the wrong reason");

    RunningRuntime probe =
        IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(4)).ephemeral().open();
    try {
      // Commit tie-b's chunk FIRST so the internal-docId order is b, a.
      for (String parentDocId : List.of("tie-b", "tie-a")) {
        String sha = ChunkParentRevision.sha256Hex(TIE_CHUNK_TEXT);
        Map<String, Object> parent = new LinkedHashMap<>();
        parent.put(SchemaFields.DOC_ID, parentDocId);
        parent.put(SchemaFields.DOC_UID, parentDocId + "#0");
        parent.put(SchemaFields.CONTENT, TIE_CHUNK_TEXT);
        parent.put(SchemaFields.PATH, parentDocId);
        parent.put(SchemaFields.CONTENT_SHA256, sha);
        probe.indexingCoordinator().indexSingle(new IndexDocument(parent));

        String chunkId = "tie-a".equals(parentDocId) ? idForA : idForB;
        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put(SchemaFields.DOC_ID, chunkId);
        chunk.put(SchemaFields.DOC_UID, chunkId + "#0");
        chunk.put(SchemaFields.IS_CHUNK, "true");
        chunk.put(SchemaFields.PARENT_DOC_ID, parentDocId);
        chunk.put(SchemaFields.CHUNK_INDEX, "0");
        chunk.put(SchemaFields.CHUNK_TOTAL, "1");
        chunk.put(SchemaFields.CHUNK_CONTENT, TIE_CHUNK_TEXT);
        chunk.put(SchemaFields.CHUNK_START_CHAR, "0");
        chunk.put(SchemaFields.CHUNK_END_CHAR, String.valueOf(TIE_CHUNK_TEXT.length()));
        chunk.put(SchemaFields.CHUNK_PARENT_CONTENT_SHA256, sha);
        chunk.put(SchemaFields.PATH, parentDocId);
        probe.indexingCoordinator().indexSingle(new IndexDocument(chunk));
        probe.commitOps().commitAndTrack();
      }
      probe.commitOps().maybeRefreshBlocking();

      return probe.chunkSearchOps().searchChunksText("tiebreakalpha", 10, null).hits().stream()
          .map(h -> h.fields().get(SchemaFields.PARENT_DOC_ID))
          .toList();
    } finally {
      probe.close();
    }
  }

  /**
   * What one tie-break probe build observed. The chunk SPLADE leg ({@code searchChunksSplade}) and
   * the two other bare chunk searches share the one {@code searchChunksWithStableTieBreak} helper
   * this asserts, and the testing field catalog carries no {@code splade} FeatureField, so BM25 is
   * where the property is exercised.
   *
   * @param sortedChunkIndexOrder the leg's returned chunks, as their {@code chunk_index} values
   * @param docIdOrder the same hits' minted chunk ids, in returned order
   * @param chunkIndexOrderIfSortedByDocId what a {@code doc_id} tie-break would have produced
   * @param rawInternalDocIdOrder what an unsorted {@code searcher.search(query, n)} produces
   */
  private record TieBreakProbe(
      List<String> sortedChunkIndexOrder,
      List<String> docIdOrder,
      List<String> chunkIndexOrderIfSortedByDocId,
      List<String> rawInternalDocIdOrder) {}

  /** Content shared by every tie-break chunk — identical text means an identical BM25 score. */
  private static final String TIE_CHUNK_TEXT = "tiebreakalpha tiebreakbeta";

  /**
   * Mints one real chunk id per index in {@code 0..count-1}, re-drawing until their lexicographic
   * order disagrees with the index order.
   *
   * <p>Without the re-draw the test would be probabilistic: three random UUIDs already sort into
   * ascending-index order one time in six, and a green run would then say nothing about whether the
   * tie-break used {@code doc_id}. Forcing the disagreement makes the falsifier deterministic.
   */
  private static List<String> mintDiscriminatingChunkIds(int count) {
    for (int attempt = 0; attempt < 100; attempt++) {
      List<String> ids = new java.util.ArrayList<>(count);
      for (int i = 0; i < count; i++) {
        ids.add(ChunkIds.newChunkDocId());
      }
      List<String> byDocId = new java.util.ArrayList<>(ids);
      java.util.Collections.sort(byDocId);
      if (!byDocId.equals(ids)) {
        return List.copyOf(ids);
      }
    }
    throw new IllegalStateException(
        "could not mint chunk ids whose lexicographic order differs from the index order");
  }

  /**
   * Opens a FRESH ephemeral index, writes one parent plus three byte-identical chunks committing
   * each chunk on its own (one segment each, in {@code commitOrder}), and reports what the BM25 leg
   * returned alongside the two orders that must NOT have decided it.
   */
  private TieBreakProbe probeTiedChunkOrder(List<Integer> commitOrder) throws Exception {
    String parentDocId = "tie";
    String parentContent = TIE_CHUNK_TEXT + "\n" + TIE_CHUNK_TEXT + "\n" + TIE_CHUNK_TEXT;
    int span = TIE_CHUNK_TEXT.length();
    String parentSha = ChunkParentRevision.sha256Hex(parentContent);
    List<String> chunkIds = mintDiscriminatingChunkIds(commitOrder.size());

    RunningRuntime probe =
        IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(4)).ephemeral().open();
    try {
      Map<String, Object> parent = new LinkedHashMap<>();
      parent.put(SchemaFields.DOC_ID, parentDocId);
      parent.put(SchemaFields.DOC_UID, parentDocId + "#0");
      parent.put(SchemaFields.CONTENT, parentContent);
      parent.put(SchemaFields.PATH, parentDocId);
      parent.put(SchemaFields.CONTENT_SHA256, parentSha);
      probe.indexingCoordinator().indexSingle(new IndexDocument(parent));
      probe.commitOps().commitAndTrack();

      for (int index : commitOrder) {
        String chunkId = chunkIds.get(index);
        int start = index * (span + 1);
        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put(SchemaFields.DOC_ID, chunkId);
        chunk.put(SchemaFields.DOC_UID, chunkId + "#0");
        chunk.put(SchemaFields.IS_CHUNK, "true");
        chunk.put(SchemaFields.PARENT_DOC_ID, parentDocId);
        chunk.put(SchemaFields.CHUNK_INDEX, String.valueOf(index));
        chunk.put(SchemaFields.CHUNK_TOTAL, "3");
        chunk.put(SchemaFields.CHUNK_CONTENT, TIE_CHUNK_TEXT);
        chunk.put(SchemaFields.CHUNK_START_CHAR, String.valueOf(start));
        chunk.put(SchemaFields.CHUNK_END_CHAR, String.valueOf(start + span));
        chunk.put(SchemaFields.CHUNK_PARENT_CONTENT_SHA256, parentSha);
        chunk.put(SchemaFields.PATH, parentDocId);
        probe.indexingCoordinator().indexSingle(new IndexDocument(chunk));
        // One commit per chunk: each lands in its own segment, so the internal docId order is the
        // commit order and diverges from the chunk-index order for a non-ascending commitOrder.
        probe.commitOps().commitAndTrack();
      }
      probe.commitOps().maybeRefreshBlocking();

      var hits = probe.chunkSearchOps().searchChunksText("tiebreakalpha", 10, null).hits();
      List<String> indexOrder =
          hits.stream().map(h -> h.fields().get(SchemaFields.CHUNK_INDEX)).toList();
      List<String> docIdOrder = hits.stream().map(LuceneRuntimeTypes.SearchHit::docId).toList();

      // What a doc_id tie-break would have delivered, computed from the same three chunks.
      List<String> byDocId = new java.util.ArrayList<>(chunkIds);
      java.util.Collections.sort(byDocId);
      List<String> indexOrderIfSortedByDocId =
          byDocId.stream().map(id -> String.valueOf(chunkIds.indexOf(id))).toList();

      List<String> raw =
          probe
              .readPathOps()
              .withSearcher(
                  searcher -> {
                    assertTrue(
                        searcher.getIndexReader().leaves().size() >= 2,
                        "fixture precondition: the chunks must span at least two segments, got "
                            + searcher.getIndexReader().leaves().size());
                    var q =
                        new org.apache.lucene.search.BooleanQuery.Builder()
                            .add(
                                new org.apache.lucene.search.TermQuery(
                                    new org.apache.lucene.index.Term(
                                        SchemaFields.CHUNK_CONTENT, "tiebreakalpha")),
                                org.apache.lucene.search.BooleanClause.Occur.MUST)
                            .add(
                                new org.apache.lucene.search.TermQuery(
                                    new org.apache.lucene.index.Term(SchemaFields.IS_CHUNK, "true")),
                                org.apache.lucene.search.BooleanClause.Occur.FILTER)
                            .build();
                    var top = searcher.search(q, 10);
                    List<String> indices = new java.util.ArrayList<>();
                    for (var sd : top.scoreDocs) {
                      indices.add(
                          searcher
                              .storedFields()
                              .document(sd.doc)
                              .get(SchemaFields.CHUNK_INDEX));
                    }
                    return List.copyOf(indices);
                  });

      return new TieBreakProbe(indexOrder, docIdOrder, indexOrderIfSortedByDocId, raw);
    } finally {
      probe.close();
    }
  }

  // ========== Helper Methods ==========

  private void indexDoc(String docId, String content) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put(SchemaFields.DOC_ID, docId);
    fields.put(SchemaFields.DOC_UID, docId + "#0");
    fields.put(SchemaFields.CONTENT, content);
    fields.put(SchemaFields.PATH, docId);
    indexParent(fields);
  }

  /** Indexes a parent document carrying a collection tag (585 D4b). */
  private void indexDocInCollection(String docId, String content, String collection) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put(SchemaFields.DOC_ID, docId);
    fields.put(SchemaFields.DOC_UID, docId + "#0");
    fields.put(SchemaFields.CONTENT, content);
    fields.put(SchemaFields.PATH, docId);
    fields.put(SchemaFields.COLLECTION, collection);
    indexParent(fields);
  }

  /**
   * Indexes a chunk document carrying its PARENT's collection tag — what
   * {@code ChunkDocumentWriter} writes since tempdoc 811 item 3.
   */
  private void indexChunkInCollection(
      String parentDocId, int index, int total, String content, String collection) {
    String chunkId = parentDocId + "#chunk_" + index;
    int[] offsets = appendChunkToParent(parentDocId, content);
    indexChunkDoc(parentDocId, Map.ofEntries(
        Map.entry(SchemaFields.DOC_ID, chunkId),
        Map.entry(SchemaFields.DOC_UID, chunkId + "#0"),
        Map.entry(SchemaFields.IS_CHUNK, "true"),
        Map.entry(SchemaFields.PARENT_DOC_ID, parentDocId),
        Map.entry(SchemaFields.CHUNK_INDEX, String.valueOf(index)),
        Map.entry(SchemaFields.CHUNK_TOTAL, String.valueOf(total)),
        Map.entry(SchemaFields.CHUNK_CONTENT, content),
        Map.entry(SchemaFields.CHUNK_START_CHAR, String.valueOf(offsets[0])),
        Map.entry(SchemaFields.CHUNK_END_CHAR, String.valueOf(offsets[1])),
        Map.entry(SchemaFields.PATH, parentDocId),
        Map.entry(SchemaFields.COLLECTION, collection)
    ));
  }

  /**
   * Indexes a chunk document.
   *
   * <p>Uses legacy chunk ID format ({@code parentDocId + "#chunk_" + index}) for
   * backward compatibility with existing tests that check for specific IDs.
   * New production code uses opaque UUID-based chunk IDs.
   */
  private String indexChunk(String parentDocId, int index, int total, String content) {
    // Use legacy format for tests that verify specific chunk IDs
    String chunkId = parentDocId + "#chunk_" + index;
    int[] offsets = appendChunkToParent(parentDocId, content);
    indexChunkDoc(parentDocId, Map.of(
        SchemaFields.DOC_ID, chunkId,
        SchemaFields.DOC_UID, chunkId + "#0",
        SchemaFields.IS_CHUNK, "true",
        SchemaFields.PARENT_DOC_ID, parentDocId,
        SchemaFields.CHUNK_INDEX, String.valueOf(index),
        SchemaFields.CHUNK_TOTAL, String.valueOf(total),
        SchemaFields.CHUNK_CONTENT, content,
        SchemaFields.CHUNK_START_CHAR, String.valueOf(offsets[0]),
        SchemaFields.CHUNK_END_CHAR, String.valueOf(offsets[1]),
        SchemaFields.PATH, parentDocId
    ));
    return chunkId;
  }

  private String indexChunkWithVector(
      String parentDocId, int index, int total, String content, float[] vector) {
    String chunkId = parentDocId + "#chunk_" + index;
    int[] offsets = appendChunkToParent(parentDocId, content);
    indexChunkDoc(parentDocId, Map.ofEntries(
        Map.entry(SchemaFields.DOC_ID, chunkId),
        Map.entry(SchemaFields.DOC_UID, chunkId + "#0"),
        Map.entry(SchemaFields.IS_CHUNK, "true"),
        Map.entry(SchemaFields.PARENT_DOC_ID, parentDocId),
        Map.entry(SchemaFields.CHUNK_INDEX, String.valueOf(index)),
        Map.entry(SchemaFields.CHUNK_TOTAL, String.valueOf(total)),
        Map.entry(SchemaFields.CHUNK_CONTENT, content),
        Map.entry(SchemaFields.CHUNK_START_CHAR, String.valueOf(offsets[0])),
        Map.entry(SchemaFields.CHUNK_END_CHAR, String.valueOf(offsets[1])),
        Map.entry(SchemaFields.CHUNK_VECTOR, vector),
        Map.entry(SchemaFields.PATH, parentDocId)
    ));
    return chunkId;
  }

  /**
   * Indexes a chunk document with opaque UUID-based ID.
   *
   * <p>Uses the new production format (P0.8). Tests using this helper
   * cannot rely on doc_id patterns for verification.
   */
  private void indexChunkOpaque(String parentDocId, int index, int total, String content) {
    String chunkId = "chunk:" + java.util.UUID.randomUUID().toString();
    int[] offsets = appendChunkToParent(parentDocId, content);
    indexChunkDoc(parentDocId, Map.of(
        SchemaFields.DOC_ID, chunkId,
        SchemaFields.DOC_UID, chunkId + "#0",
        SchemaFields.IS_CHUNK, "true",
        SchemaFields.PARENT_DOC_ID, parentDocId,
        SchemaFields.CHUNK_INDEX, String.valueOf(index),
        SchemaFields.CHUNK_TOTAL, String.valueOf(total),
        SchemaFields.CHUNK_CONTENT, content,
        SchemaFields.CHUNK_START_CHAR, String.valueOf(offsets[0]),
        SchemaFields.CHUNK_END_CHAR, String.valueOf(offsets[1]),
        SchemaFields.PATH, parentDocId
    ));
  }

  private void indexParent(Map<String, Object> fields) {
    String docId = fields.get(SchemaFields.DOC_ID).toString();
    Map<String, Object> copy = new LinkedHashMap<>(fields);
    parentDocuments.put(docId, copy);
    writeParent(copy);
  }

  /** Appends without changing earlier offsets, then rewrites the parent with its full content. */
  private int[] appendChunkToParent(String parentDocId, String chunkContent) {
    Map<String, Object> parent = parentDocuments.get(parentDocId);
    if (parent == null) {
      throw new IllegalStateException("Chunk fixture has no parent document: " + parentDocId);
    }
    String oldContent = parent.getOrDefault(SchemaFields.CONTENT, "").toString();
    String separator = oldContent.isEmpty() ? "" : "\n";
    int start = oldContent.length() + separator.length();
    String newContent = oldContent + separator + chunkContent;
    int end = newContent.length();
    parent.put(SchemaFields.CONTENT, newContent);
    writeParent(parent);
    // The parent moved to a new revision, so the chunks already cut from it are stale — exactly the
    // state the read-path guard (tempdoc 931 §E item 5) refuses to reconstruct from. Production
    // regenerates them after a parent rewrite; this fixture re-cuts them the same way, so the
    // sibling chunks stay readable while a genuinely stale chunk still fails closed.
    for (Map<String, Object> chunk : chunkDocuments.getOrDefault(parentDocId, List.of())) {
      writeChunk(parentDocId, chunk);
    }
    return new int[] {start, end};
  }

  private void writeParent(Map<String, Object> parent) {
    Map<String, Object> copy = new LinkedHashMap<>(parent);
    copy.put(
        SchemaFields.CONTENT_SHA256,
        ChunkParentRevision.sha256Hex(copy.getOrDefault(SchemaFields.CONTENT, "").toString()));
    runtime.indexingCoordinator().indexSingle(new IndexDocument(copy));
  }

  /**
   * Indexes a chunk stamped with the revision of the parent content it was cut from — what
   * {@code ChunkDocumentWriter} writes since tempdoc 931 §C.1, and what the read path checks before
   * re-slicing the text out of the parent.
   */
  private void indexChunkDoc(String parentDocId, Map<String, Object> fields) {
    Map<String, Object> copy = new LinkedHashMap<>(fields);
    chunkDocuments.computeIfAbsent(parentDocId, k -> new java.util.ArrayList<>()).add(copy);
    writeChunk(parentDocId, copy);
  }

  /** Deletes a parent's chunk docs and forgets the fixtures, so nothing re-cuts a deleted chunk. */
  private void deleteChunksForParent(String parentDocId) {
    chunkDocuments.remove(parentDocId);
    runtime.indexingCoordinator().deleteChunksForParentDocId(parentDocId);
  }

  private void writeChunk(String parentDocId, Map<String, Object> chunk) {
    Map<String, Object> parent = parentDocuments.get(parentDocId);
    String parentContent =
        parent == null ? "" : parent.getOrDefault(SchemaFields.CONTENT, "").toString();
    chunk.put(
        SchemaFields.CHUNK_PARENT_CONTENT_SHA256, ChunkParentRevision.sha256Hex(parentContent));
    runtime.indexingCoordinator().indexSingle(new IndexDocument(new LinkedHashMap<>(chunk)));
  }

  private boolean docExists(String docId) {
    // Use search to check existence since getDocumentContent returns null for docs without 'content' field
    var result = runtime.readPathOps().search(
        new org.apache.lucene.search.TermQuery(
            new org.apache.lucene.index.Term(SchemaFields.DOC_ID, docId)),
        1, Set.of(), RuntimeSearchSort.RELEVANCE, null);
    return !result.hits().isEmpty();
  }

  private void assertChunkContentNotStored(String chunkId) throws Exception {
    runtime
        .readPathOps()
        .withSearcher(
            searcher -> {
              var result =
                  searcher.search(
                      new org.apache.lucene.search.TermQuery(
                          new org.apache.lucene.index.Term(SchemaFields.DOC_ID, chunkId)),
                      1);
              assertEquals(1, result.scoreDocs.length, "chunk fixture must exist");
              assertNull(
                  searcher
                      .storedFields()
                      .document(result.scoreDocs[0].doc)
                      .get(SchemaFields.CHUNK_CONTENT),
                  "chunk_content must be indexed but not stored");
              return null;
            });
  }

  private int countAllDocs() {
    return (int) runtime.indexCountOps().docCount();
  }

  private void commitAndRefresh() throws Exception {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }
}
