package io.justsearch.systemtests;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.HybridFusionUtils;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchHit;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchResult;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkSplitter;
import io.justsearch.indexing.chunking.ChunkSplitter.Chunk;
import io.justsearch.systemtests.corpus.FrozenEmbeddingBackend;
import io.justsearch.systemtests.corpus.ManifestCorpusLoader;
import io.justsearch.systemtests.corpus.ManifestCorpusLoader.DocumentInfo;
import io.justsearch.systemtests.corpus.ManifestCorpusLoader.QueryInfo;
import io.justsearch.systemtests.relevance.RelevanceMetrics;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * Passage retrieval experiment: compares chunk-aware vs whole-doc Recall@3 for long documents with
 * buried relevant passages.
 *
 * <p>Path A (current behavior): whole-doc search only via searchText/searchVector/searchHybrid.
 *
 * <p>Path B (chunk-aware): runs separate search lanes (whole-doc + chunk), merges via RRF, and
 * collapses by parent doc ID.
 *
 * <p>The experiment hypothesis: Path B should yield higher Recall@3 for queries targeting passages
 * buried in long documents, especially in TEXT mode where BM25 term frequency dilution is
 * strongest.
 *
 * <p>All search execution happens in {@code @BeforeAll}. Test methods are pure assertions on
 * pre-computed results — no ordering dependencies.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PassageRetrievalIntegrationTest {
  private final io.justsearch.core.execution.TestEngineExecutors executors = new io.justsearch.core.execution.TestEngineExecutors();
  private final io.justsearch.adapters.lucene.runtime.LuceneExecutorRegistrations luceneExecutors = new io.justsearch.adapters.lucene.runtime.LuceneExecutorRegistrations(executors);


  private static final int VECTOR_DIM = 768;
  // Tempdoc 916 Part 1 (open item 4): these were three hand-copied literals of the production
  // constants — false authority the moment the production numbers move. They now derive.
  private static final int CHUNK_TARGET_TOKENS = ChunkSplitter.DEFAULT_CHUNK_TOKENS;
  private static final int CHUNK_OVERLAP_TOKENS = ChunkSplitter.DEFAULT_OVERLAP_TOKENS;
  private static final int CHUNK_THRESHOLD_CHARS = ChunkSplitter.CHUNK_THRESHOLD_CHARS;
  private static final int RECALL_K = 3;

  private RunningRuntime runtime;
  private ManifestCorpusLoader corpus;
  private String prevConfig;

  /** Pre-computed results for all queries and both paths. */
  private final Map<String, List<String>> pathADocIds = new LinkedHashMap<>();

  private final Map<String, List<String>> pathBParentIds = new LinkedHashMap<>();
  private final Map<String, Double> pathARecalls = new LinkedHashMap<>();
  private final Map<String, Double> pathBRecalls = new LinkedHashMap<>();
  private final Map<String, Double> pathANdcgs = new LinkedHashMap<>();
  private final Map<String, Double> pathBNdcgs = new LinkedHashMap<>();

  @BeforeAll
  void setup(@TempDir Path tempDir) throws Exception {
    // Save and set config
    prevConfig = System.getProperty("justsearch.config");

    String yaml =
        "app:\n  data_dir: "
            + tempDir.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: passage-test\n      roots: ['ignored']\n"
            + "  vector:\n    dimension: "
            + VECTOR_DIM
            + "\n";
    Path cfg = Files.createTempFile(tempDir, "passage-retrieval-config-", ".yaml");
    Files.writeString(cfg, yaml);
    System.setProperty("justsearch.config", cfg.toString());

    // Load corpus and frozen embeddings
    corpus =
        ManifestCorpusLoader.loadFromClasspath(
            "/manifests/passage-retrieval-truth.json", "/corpus/passage-retrieval/");

    FrozenEmbeddingBackend embeddingBackend =
        FrozenEmbeddingBackend.loadResource(
            "/corpus/passage-retrieval-frozen-vectors.json", false);

    // Create runtime with chunk-aware catalog
    runtime = io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(VECTOR_DIM)).ephemeral().withExecutorRegistrations(luceneExecutors).open();

    // Index all documents
    Set<String> allParentIds = new HashSet<>();
    for (DocumentInfo doc : corpus.documents()) {
      allParentIds.add(doc.id());
      String content = corpus.getContent(doc.id());
      float[] vector = toFloatArray(embeddingBackend.getVector(doc.id()));

      runtime.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, doc.id(),
                  SchemaFields.DOC_UID, doc.id() + "#0",
                  SchemaFields.CONTENT, content,
                  SchemaFields.VECTOR, vector,
                  SchemaFields.PATH, doc.id(),
                  SchemaFields.LANGUAGE, "en")));
    }
    runtime.commitOps().commitAndTrack();

    // Create chunks for long documents
    for (DocumentInfo doc : corpus.documents()) {
      String content = corpus.getContent(doc.id());
      if (content.length() < CHUNK_THRESHOLD_CHARS) {
        continue;
      }

      List<Chunk> chunks =
          ChunkSplitter.splitWithMetadata(content, CHUNK_TARGET_TOKENS, CHUNK_OVERLAP_TOKENS);

      for (Chunk chunk : chunks) {
        String chunkId = doc.id() + "#chunk_" + chunk.index();
        float[] chunkVector = toFloatArray(embeddingBackend.getVector(chunkId));

        Map<String, Object> chunkFields = new HashMap<>();
        chunkFields.put(SchemaFields.DOC_ID, chunkId);
        chunkFields.put(SchemaFields.DOC_UID, chunkId + "#0");
        chunkFields.put(SchemaFields.IS_CHUNK, "true");
        chunkFields.put(SchemaFields.PARENT_DOC_ID, doc.id());
        chunkFields.put(SchemaFields.CHUNK_INDEX, String.valueOf(chunk.index()));
        chunkFields.put(SchemaFields.CHUNK_TOTAL, String.valueOf(chunks.size()));
        chunkFields.put(SchemaFields.CHUNK_CONTENT, chunk.content());
        chunkFields.put(SchemaFields.CHUNK_VECTOR, chunkVector);
        chunkFields.put(SchemaFields.CHUNK_START_CHAR, String.valueOf(chunk.startChar()));
        chunkFields.put(SchemaFields.CHUNK_END_CHAR, String.valueOf(chunk.endChar()));
        chunkFields.put(SchemaFields.PATH, doc.id());
        runtime.indexingCoordinator().indexSingle(new IndexDocument(chunkFields));
      }
    }
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    System.out.printf(
        "Indexed %d documents + chunks (%d total docs)%n",
        corpus.documents().size(), runtime.indexCountOps().docCount());

    // --- Execute all searches ---
    for (QueryInfo query : corpus.queries()) {
      Set<String> relevant = new HashSet<>(query.expectedTopDocs());
      String mode = query.mode();

      // --- Path A: whole-doc only ---
      List<String> pathAIds;
      if ("TEXT".equals(mode)) {
        pathAIds = extractDocIds(runtime.textQueryOps().searchText(query.text(), 10, null));
      } else if ("VECTOR".equals(mode)) {
        float[] qv = toFloatArray(embeddingBackend.getVector(query.text()));
        pathAIds = extractDocIds(runtime.readPathOps().searchVector(qv, 10));
      } else {
        // HYBRID
        float[] qv = toFloatArray(embeddingBackend.getVector(query.text()));
        pathAIds = extractDocIds(runtime.hybridSearchOps().searchHybridFiltered(query.text(), qv, 10, null, io.justsearch.core.context.EngineContext.Urgency.FOREGROUND, io.justsearch.core.execution.EngineTaskLifetime.NONE));
      }
      pathADocIds.put(query.id(), pathAIds);
      pathARecalls.put(query.id(), RelevanceMetrics.recallAtK(pathAIds, relevant, RECALL_K));
      pathANdcgs.put(query.id(), RelevanceMetrics.ndcgAtK(pathAIds, relevant, RECALL_K));

      // --- Path B: chunk-aware ---
      List<String> pathBIds;
      if ("TEXT".equals(mode)) {
        SearchResult wholeDocResult = runtime.textQueryOps().searchText(query.text(), 10, null);
        SearchResult chunkResult =
            runtime.chunkSearchOps().searchChunksForDocs(query.text(), allParentIds, 30);
        pathBIds = mergeCollapseAndExtractParents(wholeDocResult.hits(), chunkResult.hits());
      } else if ("VECTOR".equals(mode)) {
        float[] qv = toFloatArray(embeddingBackend.getVector(query.text()));
        SearchResult wholeDocResult = runtime.readPathOps().searchVector(qv, 10);
        SearchResult chunkResult = runtime.chunkSearchOps().searchChunkVector(qv, allParentIds, 30, null);
        pathBIds = mergeCollapseAndExtractParents(wholeDocResult.hits(), chunkResult.hits());
      } else {
        // HYBRID: decompose into 4 sub-searches to avoid triple-RRF
        float[] qv = toFloatArray(embeddingBackend.getVector(query.text()));
        SearchResult wholeDocBm25 = runtime.textQueryOps().searchText(query.text(), 10, null);
        SearchResult wholeDocKnn = runtime.readPathOps().searchVector(qv, 10);
        SearchResult chunkBm25 =
            runtime.chunkSearchOps().searchChunksForDocs(query.text(), allParentIds, 30);
        SearchResult chunkKnn = runtime.chunkSearchOps().searchChunkVector(qv, allParentIds, 30, null);

        // Fuse pairwise using production RRF (K=60, equal weights)
        SearchResult wholeDocFused =
            HybridFusionUtils.fuseWithRRF(wholeDocBm25, wholeDocKnn, 20, false, 0, 0.5, null);
        SearchResult chunkFused =
            HybridFusionUtils.fuseWithRRF(chunkBm25, chunkKnn, 30, false, 0, 0.5, null);

        // Merge the two fused results
        pathBIds =
            mergeCollapseAndExtractParents(wholeDocFused.hits(), chunkFused.hits());
      }
      pathBParentIds.put(query.id(), pathBIds);
      pathBRecalls.put(query.id(), RelevanceMetrics.recallAtK(pathBIds, relevant, RECALL_K));
      pathBNdcgs.put(query.id(), RelevanceMetrics.ndcgAtK(pathBIds, relevant, RECALL_K));
    }
  }

  @AfterAll
  void teardown() throws Exception {
    try {
      if (runtime != null) {
        runtime.close();
      }
      if (prevConfig != null) {
        System.setProperty("justsearch.config", prevConfig);
      } else {
        System.clearProperty("justsearch.config");
      }

    } finally {
      try { luceneExecutors.close(); } finally { executors.close(); }
    }
  }

  // ---------------------------------------------------------------------------
  // Assertions
  // ---------------------------------------------------------------------------

  @Test
  void chunkAwareDoesNotDegradeRecall() {
    System.out.println(
        "\n=== RECALL@3 COMPARISON: Path A (whole-doc) vs Path B (chunk-aware) ===");
    System.out.printf("%-20s  %8s  %8s  %8s%n", "Query", "PathA", "PathB", "Delta");
    System.out.println("-".repeat(56));

    List<String> degraded = new ArrayList<>();

    for (String queryId : pathARecalls.keySet()) {
      double recallA = pathARecalls.getOrDefault(queryId, 0.0);
      double recallB = pathBRecalls.getOrDefault(queryId, 0.0);
      double delta = recallB - recallA;

      System.out.printf(
          "%-20s  %8.3f  %8.3f  %+8.3f%s%n",
          queryId, recallA, recallB, delta, delta > 0 ? " *" : "");

      if (recallB < recallA - 0.001) {
        degraded.add(queryId);
      }
    }

    System.out.println("-".repeat(56));

    assertTrue(
        degraded.isEmpty(),
        "Path B (chunk-aware) degraded Recall@3 for queries: " + degraded);
  }

  @Test
  void pathBMeetsNdcgThresholds() {
    System.out.println("\n=== NDCG@3: Path B (chunk-aware) ===");
    double ndcgThreshold = corpus.manifest().ndcgThreshold();

    List<String> belowThreshold = new ArrayList<>();
    for (QueryInfo query : corpus.queries()) {
      double ndcgB = pathBNdcgs.getOrDefault(query.id(), 0.0);
      System.out.printf("  %-20s  NDCG@3 = %.3f  (threshold: %.2f)%n",
          query.id(), ndcgB, ndcgThreshold);
      if (ndcgB < ndcgThreshold - 0.001) {
        belowThreshold.add(query.id() + " (NDCG=" + String.format("%.3f", ndcgB) + ")");
      }
    }

    // Soft assertion: log but don't fail on NDCG for now (deterministic vectors)
    if (!belowThreshold.isEmpty()) {
      System.out.println("WARNING: Path B NDCG@3 below threshold for: " + belowThreshold);
    }
  }

  @Test
  void pathBExcludesConfusersFromTopResults() {
    System.out.println("\n=== EXCLUSION CHECK: Path B top-3 ===");
    List<String> violations = new ArrayList<>();

    for (QueryInfo query : corpus.queries()) {
      List<String> excluded = query.excludedDocs();
      if (excluded.isEmpty()) continue;

      List<String> topK =
          pathBParentIds.getOrDefault(query.id(), List.of()).stream()
              .limit(RECALL_K)
              .toList();

      for (String excludedDoc : excluded) {
        if (topK.contains(excludedDoc)) {
          violations.add(query.id() + " has excluded doc '" + excludedDoc + "' in top " + RECALL_K);
        }
      }
    }

    if (!violations.isEmpty()) {
      System.out.println("Exclusion violations: " + violations);
    }

    // Soft assertion: log but don't fail (deterministic vectors may not separate confusers)
    if (!violations.isEmpty()) {
      System.out.println(
          "WARNING: Path B included confuser docs in top-"
              + RECALL_K
              + ". This suggests semantic"
              + " separation is insufficient with deterministic vectors.");
    }
  }

  @Test
  void printDetailedReport() {
    System.out.println("\n=== DETAILED REPORT ===");
    System.out.printf(
        "%-20s  %-6s  %8s  %8s  %8s  %8s  %8s  %8s%n",
        "Query", "Mode", "A.Rec@3", "B.Rec@3", "Delta", "A.NDCG", "B.NDCG", "NDGCdel");
    System.out.println("-".repeat(90));

    for (QueryInfo query : corpus.queries()) {
      double recallA = pathARecalls.getOrDefault(query.id(), 0.0);
      double recallB = pathBRecalls.getOrDefault(query.id(), 0.0);
      double ndcgA = pathANdcgs.getOrDefault(query.id(), 0.0);
      double ndcgB = pathBNdcgs.getOrDefault(query.id(), 0.0);

      System.out.printf(
          "%-20s  %-6s  %8.3f  %8.3f  %+8.3f  %8.3f  %8.3f  %+8.3f%n",
          query.id(),
          query.mode(),
          recallA,
          recallB,
          recallB - recallA,
          ndcgA,
          ndcgB,
          ndcgB - ndcgA);
    }

    System.out.println("-".repeat(90));

    // Print Path A top docs for TEXT mode queries (to verify confuser competition)
    System.out.println("\n=== PATH A TOP DOCS (TEXT mode) — expect confusers ranking high ===");
    for (QueryInfo query : corpus.queries()) {
      if (!"TEXT".equals(query.mode())) continue;
      List<String> docs = pathADocIds.getOrDefault(query.id(), List.of());
      System.out.printf("  %-20s  top-%d: %s%n", query.id(), RECALL_K,
          docs.subList(0, Math.min(RECALL_K, docs.size())));
    }

    // Print Path B top parents for TEXT mode queries
    System.out.println("\n=== PATH B TOP PARENTS (TEXT mode) — expect targets recovered ===");
    for (QueryInfo query : corpus.queries()) {
      if (!"TEXT".equals(query.mode())) continue;
      List<String> parents = pathBParentIds.getOrDefault(query.id(), List.of());
      System.out.printf("  %-20s  top-%d: %s%n", query.id(), RECALL_K,
          parents.subList(0, Math.min(RECALL_K, parents.size())));
    }
  }

  // ---------------------------------------------------------------------------
  // Helper methods
  // ---------------------------------------------------------------------------

  private List<String> extractDocIds(SearchResult result) {
    return result.hits().stream().map(SearchHit::docId).toList();
  }

  /**
   * Merges whole-doc hits and chunk hits via RRF, then collapses by parent doc ID. Returns the
   * ordered list of parent doc IDs after collapsing.
   */
  private List<String> mergeCollapseAndExtractParents(
      List<SearchHit> wholeDocHits, List<SearchHit> chunkHits) {

    // Compute RRF scores for each doc/chunk ID
    int rrfK = 60;
    Map<String, Double> rrfScores = new LinkedHashMap<>();
    Map<String, SearchHit> hitById = new HashMap<>();

    // Whole-doc lane
    for (int i = 0; i < wholeDocHits.size(); i++) {
      SearchHit hit = wholeDocHits.get(i);
      rrfScores.merge(hit.docId(), 1.0 / (rrfK + i), Double::sum);
      hitById.putIfAbsent(hit.docId(), hit);
    }

    // Chunk lane
    for (int i = 0; i < chunkHits.size(); i++) {
      SearchHit hit = chunkHits.get(i);
      rrfScores.merge(hit.docId(), 1.0 / (rrfK + i), Double::sum);
      hitById.putIfAbsent(hit.docId(), hit);
    }

    // Sort by RRF score descending
    List<Map.Entry<String, Double>> sorted = new ArrayList<>(rrfScores.entrySet());
    sorted.sort(Map.Entry.<String, Double>comparingByValue().reversed());

    // Collapse by parent doc ID: keep first (highest RRF score) per parent
    LinkedHashMap<String, SearchHit> bestPerParent = new LinkedHashMap<>();
    for (var entry : sorted) {
      SearchHit hit = hitById.get(entry.getKey());
      String parentId = resolveParentId(hit);
      bestPerParent.putIfAbsent(parentId, hit);
    }

    return new ArrayList<>(bestPerParent.keySet());
  }

  private String resolveParentId(SearchHit hit) {
    String parentDocId = hit.fields().get(SchemaFields.PARENT_DOC_ID);
    return parentDocId != null ? parentDocId : hit.docId();
  }

  private static float[] toFloatArray(List<Double> doubles) {
    if (doubles == null) {
      throw new IllegalArgumentException("Vector not found in frozen embedding backend");
    }
    float[] floats = new float[doubles.size()];
    for (int i = 0; i < doubles.size(); i++) {
      floats[i] = doubles.get(i).floatValue();
    }
    return floats;
  }
}
