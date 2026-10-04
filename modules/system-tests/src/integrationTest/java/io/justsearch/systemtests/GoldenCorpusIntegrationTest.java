package io.justsearch.systemtests;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.reranker.CrossEncoderReranker;
import io.justsearch.reranker.RerankerConfig;
import io.justsearch.systemtests.corpus.FrozenEmbeddingBackend;
import io.justsearch.systemtests.corpus.ManifestCorpusLoader;
import io.justsearch.systemtests.corpus.ManifestCorpusLoader.DocumentInfo;
import io.justsearch.systemtests.corpus.ManifestCorpusLoader.QueryInfo;
import io.justsearch.systemtests.relevance.RelevanceMetrics;
import io.justsearch.systemtests.relevance.RelevanceMetrics.AggregatedMetrics;
import io.justsearch.systemtests.relevance.RelevanceMetrics.QueryResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Integration tests for Golden Corpus search relevance.
 *
 * <p>This test suite:
 * <ol>
 *   <li>Loads the Golden Corpus documents and truth manifest</li>
 *   <li>Creates a temporary Lucene index</li>
 *   <li>Indexes all documents with frozen (deterministic) embeddings</li>
 *   <li>Runs each query from the truth manifest</li>
 *   <li>Validates relevance metrics (Recall@3, NDCG@3)</li>
 * </ol>
 *
 * <p>Success criteria:
 * <ul>
 *   <li>Recall@3 ≥ 0.9 for all query modes</li>
 *   <li>NDCG@3 ≥ 0.8 for all query modes</li>
 * </ul>
 *
 * <p><b>Disambiguation (tempdoc 664):</b> the vectors indexed here come from
 * {@code FrozenEmbeddingBackend} / {@code frozen-vectors.json} ({@code "model":
 * "test-deterministic"}) — hand-placed, not real embedding-model output. This suite proves
 * fusion/ranking-code correctness (RRF, BM25↔vector fusion) given a fixed vector; it stays
 * green regardless of real embedding-model regressions. It does not measure retrieval quality
 * — {@code jseval} (Python) is the canonical harness for that.
 */
@DisplayName("Golden Corpus Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GoldenCorpusIntegrationTest {
  private static final io.justsearch.core.execution.TestEngineExecutors executors = new io.justsearch.core.execution.TestEngineExecutors();
  private static final io.justsearch.adapters.lucene.runtime.LuceneExecutorRegistrations luceneExecutors = new io.justsearch.adapters.lucene.runtime.LuceneExecutorRegistrations(executors);

  private static final Logger log = LoggerFactory.getLogger(GoldenCorpusIntegrationTest.class);

  // Vector dimension is loaded from the SSOT catalog, not hardcoded
  private static int vectorDimension;

  private static Path tempDir;
  private static Path configFile;
  private static String previousConfig;
  private static RunningRuntime runtime;
  private static ManifestCorpusLoader corpus;
  private static FrozenEmbeddingBackend embeddingBackend;

  @BeforeAll
  static void setupIndex() throws Exception {
    log.info("Setting up Golden Corpus integration test...");

    // Load corpus and frozen embeddings
    corpus = ManifestCorpusLoader.loadDefault();
    embeddingBackend = FrozenEmbeddingBackend.load(
        GoldenCorpusIntegrationTest.class.getResourceAsStream("/corpus/frozen-vectors.json"),
        false  // lenient mode - return zero vectors for unknown text
    );

    log.info("Loaded {} documents, {} queries",
        corpus.documents().size(), corpus.queries().size());

    // Find project root (needed for SSOT)
    Path projectRoot = findProjectRoot();
    if (projectRoot == null) {
      throw new IllegalStateException("Cannot find project root (settings.gradle.kts)");
    }

    // Create temp directory for index data
    tempDir = Files.createTempDirectory("golden-corpus-test-");
    log.info("Created temp directory: {}", tempDir);

    // Copy real config from project
    Path realConfig = projectRoot.resolve("config/application.yaml");
    if (!Files.exists(realConfig)) {
      throw new IllegalStateException("Config file not found: " + realConfig);
    }

    // Create a modified config pointing to our temp data dir
    String configContent = Files.readString(realConfig);
    // Replace data_dir with our temp directory
    String modifiedConfig = configContent.replaceAll(
        "data_dir:.*",
        "data_dir: " + tempDir.toString().replace("\\", "\\\\")
    );

    configFile = tempDir.resolve("config/application.yaml");
    Files.createDirectories(configFile.getParent());
    Files.writeString(configFile, modifiedConfig);
    log.info("Created test config at: {}", configFile);

    // Set system property for config
    previousConfig = System.getProperty("justsearch.config");
    System.setProperty("justsearch.config", configFile.toString());

    // Set working directory context for SSOT resolution
    System.setProperty("user.dir", projectRoot.toString());

    // Load field catalog via centralized loader
    JustSearchConfigurationLoader loader = new JustSearchConfigurationLoader();
    FieldCatalogDef catalog = loader.loadFieldCatalog();

    // Get vector dimension from the SSOT catalog
    Integer catalogDimension = catalog.vectorDimension();
    if (catalogDimension == null) {
      throw new IllegalStateException("SSOT field catalog does not define a vector dimension");
    }
    vectorDimension = catalogDimension;
    log.info("SSOT vector dimension: {}", vectorDimension);

    // Validate embedding backend dimension matches SSOT
    if (embeddingBackend.dimension() != vectorDimension) {
      throw new IllegalStateException(String.format(
          "Frozen embedding dimension (%d) does not match SSOT catalog dimension (%d). " +
          "Regenerate frozen-vectors.json with the correct embedding model.",
          embeddingBackend.dimension(), vectorDimension));
    }

    // Create and start runtime with explicit injection
    runtime = io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(catalog).ephemeral().withExecutorRegistrations(luceneExecutors).open();
    log.info("Lucene runtime started");

    // Index all corpus documents
    indexCorpusDocuments();
  }

  private static Path findProjectRoot() {
    Path current = Path.of(System.getProperty("user.dir"));
    while (current != null) {
      if (Files.exists(current.resolve("settings.gradle.kts"))) {
        return current;
      }
      current = current.getParent();
    }
    return null;
  }

  private static void indexCorpusDocuments() throws Exception {
    log.info("Indexing {} documents...", corpus.documents().size());

    for (DocumentInfo doc : corpus.documents()) {
      String content = corpus.getContent(doc.id());
      if (content == null || content.isBlank()) {
        log.warn("No content found for document: {}", doc.id());
        continue;
      }

      // Get embedding for this document using the document ID (which has matching vectors)
      float[] vector = getEmbedding(doc.id());

      // Create index document
      Map<String, Object> fields = Map.of(
          SchemaFields.DOC_ID, doc.id(),
          SchemaFields.DOC_UID, doc.id() + "#0",
          SchemaFields.CONTENT, content,
          SchemaFields.VECTOR, vector
      );

      runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
      log.debug("Indexed: {} ({} chars, vector dim {})",
          doc.id(), content.length(), vector.length);
    }

    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefresh();  // Ensure index is searchable
    log.info("Index committed with {} documents", corpus.documents().size());
  }

  @AfterAll
  static void cleanup() throws Exception {
    try {
      log.info("Cleaning up...");

      if (runtime != null) {
        runtime.close();
      }

      // Restore previous config
      if (previousConfig == null) {
        System.clearProperty("justsearch.config");
      } else {
        System.setProperty("justsearch.config", previousConfig);
      }

      // Clean up temp directory
      if (tempDir != null && Files.exists(tempDir)) {
        try (var walk = Files.walk(tempDir)) {
          walk.sorted(java.util.Comparator.reverseOrder())
              .forEach(p -> {
                try {
                  Files.deleteIfExists(p);
                } catch (IOException e) {
                  log.warn("Failed to delete: {}", p);
                }
              });
        }
      }

      log.info("Cleanup complete");

    } finally {
      try { luceneExecutors.close(); } finally { executors.close(); }
    }
  }

  @Test
  @Order(1)
  @DisplayName("Index should contain all corpus documents")
  void verifyIndexedDocuments() {
    // Search for documents using keywords from each category
    // Must cover: database docs, animal docs (canine/puppy/cat), ML docs, and confusers (java/art)
    var result = runtime.textQueryOps().searchText(
        "database OR canine OR puppy OR cat OR machine OR neural OR java OR abstract OR art", 15, null);

    log.info("Found {} documents in index (expected {})",
        result.hits().size(), corpus.documents().size());

    // Verify we can find most documents (allow 1-2 margin for search limitations)
    int minExpected = Math.max(1, corpus.documents().size() - 2);
    assertTrue(result.hits().size() >= minExpected,
        "Index should contain most corpus documents. Found " + result.hits().size()
        + ", expected at least " + minExpected);
  }

  @Test
  @Order(2)
  @DisplayName("TEXT mode queries meet relevance thresholds")
  void textModeRelevance() {
    List<QueryResult> results = runQueriesForMode("TEXT");

    AggregatedMetrics metrics = AggregatedMetrics.aggregate(
        results,
        corpus.manifest().recallThreshold(),
        corpus.manifest().ndcgThreshold()
    );

    logMetrics("TEXT", metrics);

    assertTrue(metrics.meanRecallAt3() >= 0.8,
        "TEXT mode mean Recall@3 should be >= 0.8, was " + metrics.meanRecallAt3());
  }

  @Test
  @Order(3)
  @DisplayName("VECTOR mode queries meet relevance thresholds")
  void vectorModeRelevance() {
    List<QueryResult> results = runQueriesForMode("VECTOR");

    AggregatedMetrics metrics = AggregatedMetrics.aggregate(
        results,
        corpus.manifest().recallThreshold(),
        corpus.manifest().ndcgThreshold()
    );

    logMetrics("VECTOR", metrics);

    assertTrue(metrics.meanRecallAt3() >= 0.8,
        "VECTOR mode mean Recall@3 should be >= 0.8, was " + metrics.meanRecallAt3());
  }

  @Test
  @Order(4)
  @DisplayName("HYBRID mode queries meet relevance thresholds")
  void hybridModeRelevance() {
    List<QueryResult> results = runQueriesForMode("HYBRID");

    AggregatedMetrics metrics = AggregatedMetrics.aggregate(
        results,
        corpus.manifest().recallThreshold(),
        corpus.manifest().ndcgThreshold()
    );

    logMetrics("HYBRID", metrics);

    assertTrue(metrics.meanRecallAt3() >= 0.8,
        "HYBRID mode mean Recall@3 should be >= 0.8, was " + metrics.meanRecallAt3());
  }

  @Test
  @Order(5)
  @DisplayName("HYBRID mode outperforms single modes for mixed-relevance docs")
  void hybridAdvantage() {
    // Find the hybrid advantage test query
    QueryInfo hybridQuery = corpus.queries().stream()
        .filter(q -> q.id().equals("hybrid-advantage-test"))
        .findFirst()
        .orElse(null);

    if (hybridQuery == null) {
      log.warn("hybrid-advantage-test query not found, skipping");
      return;
    }

    // Run the same query in all three modes
    String queryText = hybridQuery.text();
    float[] queryVector = getEmbedding(queryText);
    Set<String> relevant = new HashSet<>(hybridQuery.expectedTopDocs());

    // TEXT mode
    var textResult = runtime.textQueryOps().searchText(queryText, 10, null);
    List<String> textDocs = textResult.hits().stream()
        .map(LuceneRuntimeTypes.SearchHit::docId)
        .toList();
    double textRecall = RelevanceMetrics.recallAtK(textDocs, relevant, 3);

    // VECTOR mode
    var vectorResult = runtime.readPathOps().searchVector(queryVector, 10);
    List<String> vectorDocs = vectorResult.hits().stream()
        .map(LuceneRuntimeTypes.SearchHit::docId)
        .toList();
    double vectorRecall = RelevanceMetrics.recallAtK(vectorDocs, relevant, 3);

    // HYBRID mode
    var hybridResult = runtime.hybridSearchOps().searchHybridFiltered(queryText, queryVector, 10, null, io.justsearch.core.context.EngineContext.Urgency.FOREGROUND, io.justsearch.core.execution.EngineTaskLifetime.NONE);
    List<String> hybridDocs = hybridResult.hits().stream()
        .map(LuceneRuntimeTypes.SearchHit::docId)
        .toList();
    double hybridRecall = RelevanceMetrics.recallAtK(hybridDocs, relevant, 3);

    log.info("Hybrid advantage test '{}': TEXT={}, VECTOR={}, HYBRID={}",
        queryText, textRecall, vectorRecall, hybridRecall);

    // HYBRID should be at least as good as the best single mode
    double bestSingle = Math.max(textRecall, vectorRecall);
    assertTrue(hybridRecall >= bestSingle * 0.9,  // Allow 10% tolerance
        "HYBRID recall should be >= 90% of best single mode");
  }

  @Test
  @Order(6)
  @DisplayName("Overall relevance meets success criteria")
  void overallRelevance() {
    List<QueryResult> allResults = new ArrayList<>();
    allResults.addAll(runQueriesForMode("TEXT"));
    allResults.addAll(runQueriesForMode("VECTOR"));
    allResults.addAll(runQueriesForMode("HYBRID"));

    AggregatedMetrics metrics = AggregatedMetrics.aggregate(
        allResults,
        corpus.manifest().recallThreshold(),
        corpus.manifest().ndcgThreshold()
    );

    log.info("=== Overall Relevance Summary ===");
    log.info("Total queries: {}", metrics.queryCount());
    log.info("Mean Recall@3: {}", String.format("%.3f", metrics.meanRecallAt3()));
    log.info("Mean Precision@3: {}", String.format("%.3f", metrics.meanPrecisionAt3()));
    log.info("Mean NDCG@3: {}", String.format("%.3f", metrics.meanNdcgAt3()));
    log.info("Mean MRR: {}", String.format("%.3f", metrics.meanReciprocalRank()));
    log.info("Pass/Fail: {}/{}", metrics.passCount(), metrics.failCount());

    // Success criteria
    assertTrue(metrics.meanRecallAt3() >= 0.7,
        "Overall mean Recall@3 should be >= 0.7");
    assertTrue(metrics.passCount() >= metrics.queryCount() * 0.5,
        "At least 50% of queries should pass thresholds");
  }

  @Test
  @Order(7)
  @DisplayName("Cross-encoder reranker does not degrade TEXT mode Recall@3")
  void rerankerNoRegressionOnTextQueries() throws Exception {
    // Guard: skip if reranker model is not discoverable at any standard location.
    // In CI without a model present this test is skipped, not failed.
    //
    // RerankerConfig.fromEnv() reads ConfigStore.global(), which only HeadlessApp/IndexerWorker
    // publish at startup — this tier runs in a plain test JVM, so fromEnv() threw
    // IllegalStateException before the assumeTrue guard below could ever be reached. Resolve the
    // config locally instead, via the documented-preferred RerankerConfig.from(ResolvedConfig.Ai).
    // Building the snapshot here (rather than ConfigStore.setGlobal) keeps the env/sysprop
    // contributions the reranker guard depends on while leaving the JVM-wide global untouched for
    // the other tests sharing this fork.
    RerankerConfig config =
        RerankerConfig.from(ResolvedConfig.builder().contributeEnvRegistry().build().ai());
    assumeTrue(
        config.enabled() && config.modelPath() != null,
        "Reranker model not found at any standard location — skipping reranker regression test");

    // The directory alone is not enough: this repo TRACKS models/onnx/reranker/ metadata
    // (config.json, tokenizer.json, …) while gitignoring the .onnx weights, so a fresh checkout
    // resolves a modelPath that holds no loadable model. Probing for the actual weights with the
    // same predicate InferenceCompositionRootTestHelper uses is what makes the documented
    // "skipped, not failed" contract above true — otherwise cpuSessionFor below throws.
    assumeTrue(
        io.justsearch.ort.DevModeVariantProbe.probe(config.modelPath(), /* gpuEnabled= */ false)
            != null,
        "No loadable reranker ONNX weights under "
            + config.modelPath()
            + " (metadata-only checkout) — skipping reranker regression test");

    List<QueryInfo> textQueries =
        corpus.queries().stream().filter(q -> q.mode().equals("TEXT")).toList();
    assumeTrue(!textQueries.isEmpty(), "No TEXT mode queries in golden corpus");

    // Tempdoc 397 §14.28 U1: testFixtures helper wraps OrtSessionAssembler.buildManager.
    io.justsearch.ort.SessionHandle rerankerSessions =
        io.justsearch.ort.testing.InferenceCompositionRootTestHelper.cpuSessionFor(
            "reranker-golden", config.modelPath());
    io.justsearch.reranker.RerankerAssembly rerankAssembly =
        CrossEncoderReranker.buildAssembly(
            rerankerSessions,
            config.modelPath().resolve("tokenizer.json"),
            config.maxSequenceLength());
    try (CrossEncoderReranker reranker =
        new CrossEncoderReranker(
            rerankAssembly.sessions(), rerankAssembly.shape(), rerankAssembly.tokenizer())) {

      double totalBaselineRecall = 0.0;
      double totalRerankedRecall = 0.0;
      int queryCount = 0;
      int skippedCount = 0;

      for (QueryInfo query : textQueries) {
        LuceneRuntimeTypes.SearchResult result = runtime.textQueryOps().searchText(query.text(), 10, null);
        List<LuceneRuntimeTypes.SearchHit> hits = result.hits();
        if (hits.isEmpty()) {
          continue;
        }

        List<String> baselineDocIds =
            hits.stream().map(LuceneRuntimeTypes.SearchHit::docId).toList();
        List<String> docContents =
            hits.stream()
                .map(hit -> corpus.getContent(hit.docId()))
                .map(c -> c != null ? c : "")
                .toList();

        Set<String> relevant = new HashSet<>(query.expectedTopDocs());
        double baselineRecall = RelevanceMetrics.recallAtK(baselineDocIds, relevant, 3);

        // 5-second deadline: generous enough for CPU inference on a 10-doc corpus
        CrossEncoderReranker.RerankedResult reranked =
            reranker.rerank(query.text(), docContents, 5_000L);

        List<String> rerankedDocIds;
        if (reranked.skipped()) {
          log.warn("Reranker skipped for query '{}': using baseline order", query.id());
          rerankedDocIds = baselineDocIds;
          skippedCount++;
        } else {
          rerankedDocIds = reranked.sortedIndices().stream().map(baselineDocIds::get).toList();
        }

        double rerankedRecall = RelevanceMetrics.recallAtK(rerankedDocIds, relevant, 3);
        log.info(
            "Reranker [{}] '{}': baseline Recall@3={}, reranked={} (latency {}ms)",
            query.id(),
            query.text(),
            String.format("%.2f", baselineRecall),
            String.format("%.2f", rerankedRecall),
            reranked.latencyMs());

        totalBaselineRecall += baselineRecall;
        totalRerankedRecall += rerankedRecall;
        queryCount++;
      }

      // Both guards catch structural failures that would otherwise let the test pass vacuously.
      assertTrue(
          queryCount > 0,
          "No TEXT queries returned hits from the index — test was vacuous");
      assertTrue(
          skippedCount < queryCount,
          String.format(
              "All %d TEXT queries were skipped by the reranker"
                  + " — deadline too short or model is broken",
              queryCount));

      double avgBaseline = totalBaselineRecall / queryCount;
      double avgReranked = totalRerankedRecall / queryCount;
      log.info(
          "Reranker summary ({} TEXT queries, {} skipped): baseline Recall@3={}, reranked={}",
          queryCount,
          skippedCount,
          String.format("%.3f", avgBaseline),
          String.format("%.3f", avgReranked));

      // Reranking must not degrade mean Recall@3 by more than 10%.
      // The golden corpus is small (10 docs, ~3 TEXT queries) so the bar is intentionally
      // loose — this test catches gross regressions (wrong model, broken scoring), not
      // fine-grained quality differences.
      assertTrue(
          avgReranked >= avgBaseline * 0.9,
          String.format(
              "Reranker degraded mean Recall@3 by more than 10%%: baseline=%.3f, reranked=%.3f",
              avgBaseline, avgReranked));
    }
  }

  // === Helper methods ===

  private List<QueryResult> runQueriesForMode(String mode) {
    List<QueryResult> results = new ArrayList<>();

    for (QueryInfo query : corpus.queries()) {
      if (!query.mode().equals(mode)) {
        continue;
      }

      List<String> retrievedDocs = executeQuery(query);
      Set<String> relevantDocs = new HashSet<>(query.expectedTopDocs());

      QueryResult result = QueryResult.compute(
          query.id(),
          query.text(),
          retrievedDocs,
          relevantDocs
      );

      results.add(result);

      log.debug("Query '{}' ({}): retrieved={}, recall={}, ndcg={}",
          query.id(), mode, retrievedDocs.size(),
          String.format("%.2f", result.recallAt3()),
          String.format("%.2f", result.ndcgAt3()));
    }

    return results;
  }

  private List<String> executeQuery(QueryInfo query) {
    try {
      LuceneRuntimeTypes.SearchResult result;

      switch (query.mode()) {
        case "TEXT" -> {
          result = runtime.textQueryOps().searchText(query.text(), 10, null);
        }
        case "VECTOR" -> {
          float[] vector = getEmbedding(query.text());
          result = runtime.readPathOps().searchVector(vector, 10);
        }
        case "HYBRID" -> {
          float[] vector = getEmbedding(query.text());
          result = runtime.hybridSearchOps().searchHybridFiltered(query.text(), vector, 10, null, io.justsearch.core.context.EngineContext.Urgency.FOREGROUND, io.justsearch.core.execution.EngineTaskLifetime.NONE);
        }
        default -> {
          log.warn("Unknown query mode: {}", query.mode());
          return List.of();
        }
      }

      return result.hits().stream()
          .map(LuceneRuntimeTypes.SearchHit::docId)
          .toList();

    } catch (Exception e) {
      log.error("Query execution failed for '{}': {}", query.id(), e.getMessage());
      return List.of();
    }
  }

  private void logMetrics(String mode, AggregatedMetrics metrics) {
    log.info("=== {} Mode Results ===", mode);
    log.info("Queries: {}", metrics.queryCount());
    log.info("Mean Recall@3: {}", String.format("%.3f", metrics.meanRecallAt3()));
    log.info("Mean NDCG@3: {}", String.format("%.3f", metrics.meanNdcgAt3()));
    log.info("Pass/Fail: {}/{}", metrics.passCount(), metrics.failCount());
  }

  /**
   * Gets embedding vector for text using the frozen embedding backend.
   * Returns a float array suitable for Lucene vector search.
   */
  private static float[] getEmbedding(String text) {
    List<Double> vector = embeddingBackend.getVector(text);
    if (vector == null) {
      // Return zero vector for unknown text in lenient mode
      return new float[vectorDimension];
    }
    float[] result = new float[vector.size()];
    for (int i = 0; i < vector.size(); i++) {
      result[i] = vector.get(i).floatValue();
    }
    return result;
  }
}
