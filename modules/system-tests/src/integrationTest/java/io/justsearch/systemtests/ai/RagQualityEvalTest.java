package io.justsearch.systemtests.ai;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.aibackend.backend.AiBackend;
import io.justsearch.aibackend.backend.BackendException;
import io.justsearch.aibackend.backend.AiBackend;
import io.justsearch.aibackend.backend.BackendRegistry;
import io.justsearch.aibackend.local.LocalIntentTranslatorConfig;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexerworker.services.AnswerSegmentation;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.reranker.CitationScorer;
import io.justsearch.systemtests.aijudge.KeywordPresenceChecker;
import io.justsearch.systemtests.aijudge.KeywordPresenceChecker.KeywordResult;
import io.justsearch.systemtests.aijudge.SemanticSimilarityChecker;
import io.justsearch.systemtests.aijudge.SemanticSimilarityChecker.SimilarityResult;
import io.justsearch.systemtests.corpus.FrozenEmbeddingBackend;
import io.justsearch.systemtests.relevance.RelevanceMetrics;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RAG Quality Evaluation Test.
 *
 * <p>Data-driven integration test that evaluates RAG answer quality across multiple metrics:
 *
 * <ul>
 *   <li><b>Retrieval Recall</b>: Did the retriever find the right source documents?
 *   <li><b>Fact Coverage</b>: Does the answer mention required facts?
 *   <li><b>Hallucination Rate</b>: Does the answer contain forbidden/fabricated facts?
 *   <li><b>Answer Similarity</b>: Semantic similarity to golden reference answer
 *   <li><b>Citation Accuracy</b>: Do [N] citation markers map to correct source docs?
 * </ul>
 *
 * <p>Uses a truth manifest ({@code rag-eval-truth.v1.json}) with Q&A pairs, each specifying
 * expected facts, forbidden facts, golden answer, and citation expectations.
 *
 * <p>Uses Qwen3-4B for generation quality sufficient to evaluate pipeline behavior.
 * Deterministic config (seed=42, temp=0.0, threads=1) ensures reproducible results.
 *
 * @see io.justsearch.systemtests.aijudge.KeywordPresenceChecker
 * @see io.justsearch.systemtests.aijudge.SemanticSimilarityChecker
 */
@Tag("ai")
@DisplayName("RAG Quality Evaluation")
class RagQualityEvalTest {
  private static final Logger log = LoggerFactory.getLogger(RagQualityEvalTest.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Regex to parse [N] citation markers from LLM output. */
  /**
   * Matches citation markers: [1], [Document1], [Document 1], or truncated [1 at end of text.
   * Groups: group(1) = the document number.
   */
  private static final Pattern CITATION_PATTERN =
      Pattern.compile("\\[(?:Document\\s?)?(\\d+)\\]?");
  private static final String FAITHFULNESS_MODE = "cross-encoder";
  private static final double FAITHFULNESS_THRESHOLD = 0.5;
  private static final long FAITHFULNESS_DEADLINE_MS = 5000;

  /**
   * Context format for RAG prompt assembly. Set via {@code -Drag.eval.context.format=...}.
   *
   * <ul>
   *   <li>{@code document-block} (default) — full doc content up to MAX_CONTEXT_CHARS_PER_DOC
   *   <li>{@code agent-kN-excM-budB} — agent-style format mimicking SearchTool.formatResults()
   *       with N results, M-char excerpts, and B-char total budget. Examples: {@code
   *       agent-k5-exc200-bud900} (current agent), {@code agent-k3-exc800-bud4000} (proposed).
   * </ul>
   */
  private static final String CONTEXT_FORMAT =
      System.getProperty("rag.eval.context.format", "document-block");

  private static Path tempDir;
  private static Path configFile;
  private static String previousConfig;
  private static String previousUserDir;
  private static RunningRuntime runtime;
  private static AiBackend llmBackend;
  private static String llmModelName;
  private static SemanticSimilarityChecker similarityChecker;
  private static CitationScorer citationScorer;
  private static FrozenEmbeddingBackend embeddingBackend;
  private static final KeywordPresenceChecker keywordChecker = new KeywordPresenceChecker();

  // Loaded from manifest
  private static List<RagQuery> ragQueries;
  private static Map<String, String> docContents; // docId -> content
  private static String corpusSha; // SHA-256 of corpus components (truth + vectors + docs)
  private static QualityThresholds qualityThresholds;
  private static int vectorDimension;

  @BeforeAll
  static void setup() throws Exception {
    log.info("Setting up RAG Quality Evaluation test...");

    // 1. Load manifest and corpus
    loadManifestAndCorpus();
    corpusSha = computeCorpusSha();

    // 2. Setup Lucene runtime
    setupLuceneRuntime();

    // 3. Load frozen embedding vectors for hybrid search
    loadFrozenVectors();

    // 4. Index corpus documents (with vectors for hybrid search)
    indexCorpus();

    // 5. Setup LLM backend
    setupLlmBackend();

    // 6. Setup similarity checker (with fallback to stub mode)
    similarityChecker = SemanticSimilarityChecker.createWithFallback();
    if (isRealEmbeddingRequested() && !similarityChecker.isAvailable()) {
      throw new IllegalStateException(
          "Real embedding mode requested via JUSTSEARCH_ENABLE_REAL_EMBEDDING=true, but "
              + "SemanticSimilarityChecker is in stub fallback mode. "
              + "Actual mode: stub-jaccard. "
              + "Run preflight: node scripts/verify-prerequisites.mjs. "
              + "If needed, set JUSTSEARCH_NATIVE_PATH and JUSTSEARCH_MODEL_PATH explicitly.");
    }

    // 7. Setup citation scorer (fail closed - no word-overlap fallback)
    setupCitationScorer();

    log.info(
        "RAG eval setup complete: {} queries, {} docs indexed", ragQueries.size(),
        docContents.size());
  }

  @AfterAll
  static void cleanup() throws Exception {
    log.info("Cleaning up RAG eval resources...");

    if (citationScorer != null) {
      citationScorer.close();
    }
    if (similarityChecker != null) {
      similarityChecker.close();
    }
    if (llmBackend != null) {
      llmBackend.close();
    }
    if (runtime != null) {
      runtime.close();
    }
    if (previousConfig == null) {
      System.clearProperty("justsearch.config");
    } else {
      System.setProperty("justsearch.config", previousConfig);
    }
    if (previousUserDir == null) {
      System.clearProperty("user.dir");
    } else {
      System.setProperty("user.dir", previousUserDir);
    }
    if (tempDir != null && Files.exists(tempDir)) {
      try (var walk = Files.walk(tempDir)) {
        walk.sorted(java.util.Comparator.reverseOrder())
            .forEach(
                p -> {
                  try {
                    Files.deleteIfExists(p);
                  } catch (IOException e) {
                    log.warn("Failed to delete: {}", p);
                  }
                });
      }
    }
    log.info("Cleanup complete");
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.MINUTES)
  @DisplayName("RAG answers meet quality thresholds across all queries")
  void ragQualityAcrossAllQueries() throws Exception {
    List<QueryEvalResult> results = new ArrayList<>();

    try (AiBackend.Session session = llmBackend.createSession()) {
      for (RagQuery query : ragQueries) {
        try {
          QueryEvalResult result = evaluateQuery(session, query);
          results.add(result);

          log.info(
              "Query [{}]: fact={}, forb={}, sim={}, faith={}, "
                  + "ret={}, cit_p={}, cit_r={}, cit_n={}",
              query.id,
              fmt(result.factCoverage),
              fmt(result.forbiddenFactRate),
              fmt(result.answerSimilarity),
              fmt(result.faithfulness),
              fmt(result.retrievalRecall),
              fmt(result.citationPrecision),
              fmt(result.citationRecall),
              result.citationsFound);
        } catch (Exception e) {
          log.error("Query [{}] failed: {} — recording empty result", query.id, e.getMessage());
          results.add(
              new QueryEvalResult(
                  query.id, query.question, "", List.of(), 0.0, 0.0, 0.0, 0.0, 0.0, 0,
                  List.of(), 0, query.requiredFacts.size(), "n/a", FAITHFULNESS_MODE, 0.0, 0.0));
        }
      }
    }

    // Compute aggregates
    AggregateMetrics agg = computeAggregates(results);
    logAggregates(agg);

    // Write results JSON (v1 + v2 dual-write for bench-suite migration)
    writeResultsJson(results, agg);
    writeResultsV2Json(results, agg);

    // Assertions — check against manifest thresholds, wrapped in assertAll for soft failures
    long nonEmptyAnswers = results.stream().filter(r -> !r.answerText.isBlank()).count();

    assertAll(
        "RAG quality thresholds",
        () ->
            assertTrue(
                nonEmptyAnswers > 0,
                "At least one query should produce a non-empty answer. Got 0/"
                    + results.size()),
        () ->
            assertTrue(
                agg.forbiddenFactRateMean <= qualityThresholds.forbiddenFactRateMax,
                "Forbidden fact rate "
                    + fmt(agg.forbiddenFactRateMean)
                    + " exceeds threshold "
                    + qualityThresholds.forbiddenFactRateMax),
        () ->
            assertTrue(
                agg.faithfulnessMean >= qualityThresholds.faithfulnessMin,
                "Faithfulness "
                    + fmt(agg.faithfulnessMean)
                    + " below threshold "
                    + qualityThresholds.faithfulnessMin),
        () ->
            assertTrue(
                agg.answerSimilarityMean >= qualityThresholds.answerSimilarityMin
                    || agg.answerSimilarityMean == 0.0, // stub mode may return 0
                "Answer similarity "
                    + fmt(agg.answerSimilarityMean)
                    + " below threshold "
                    + qualityThresholds.answerSimilarityMin),
        () ->
            assertTrue(
                agg.factCoverageMean >= qualityThresholds.factCoverageMin,
                "Fact coverage "
                    + fmt(agg.factCoverageMean)
                    + " below threshold "
                    + qualityThresholds.factCoverageMin));
  }

  // === Core evaluation logic ===

  private QueryEvalResult evaluateQuery(AiBackend.Session session, RagQuery query)
      throws Exception {
    // 1. Retrieve documents via TEXT search
    List<String> retrievedDocIds = retrieveDocuments(query.question, 5);

    // 2. Compute retrieval recall (tempdoc 664: reflects frozen-vector fusion/ranking-code
    // behavior, not real embedding-model retrieval quality — see RelevanceMetrics' Javadoc).
    double retrievalRecall =
        RelevanceMetrics.recallAtK(
            retrievedDocIds, new HashSet<>(query.expectedSourceDocs), 5);

    // 3. Build RAG prompt from retrieved (or expected) doc content
    // Use expected docs as context to isolate LLM quality from retrieval quality
    ContextResult ctx =
        CONTEXT_FORMAT.startsWith("agent-")
            ? buildAgentStyleContext(query.corpusDocs, parseAgentFormat(CONTEXT_FORMAT))
            : buildContext(query.corpusDocs);

    String ragPrompt = buildRagPrompt(ctx.text, query.question);

    // 4. Generate answer via LLM
    AiBackend.ChunkRequest request =
        new AiBackend.ChunkRequest(
            UUID.randomUUID(),
            0,
            ragPrompt,
            0,
            128,
            Locale.ENGLISH.toLanguageTag());

    AiBackend.ChunkResponse response = session.summarizeChunk(request);
    String answer = response.summaryText();
    if (answer == null) {
      answer = "";
    }

    log.debug("Q: {}", query.question);
    log.debug("A: {}", answer);

    // 5. Score fact coverage
    KeywordResult factResult =
        keywordChecker.checkWithVariants(answer, new HashSet<>(query.requiredFacts));
    double factCoverage = factResult.coverage();

    // 6. Score hallucination rate
    double forbiddenFactRate = 0.0;
    if (!query.forbiddenFacts.isEmpty()) {
      KeywordResult forbiddenResult =
          keywordChecker.checkWithVariants(answer, new HashSet<>(query.forbiddenFacts));
      forbiddenFactRate = forbiddenResult.coverage(); // Higher = worse
    }

    // 7. Score answer similarity
    SimilarityResult simResult = similarityChecker.compare(answer, query.goldenAnswer);
    double answerSimilarity = simResult.similarity();

    // 8. Score faithfulness (sentence grounding in context)
    double faithfulness = computeFaithfulness(answer, ctx.chunkTexts, ctx.chunkDocIds);

    // 9. Parse citation markers
    List<Integer> citations = parseCitationMarkers(answer);

    // 10. Compute citation precision and recall (validates both marker number AND source doc)
    double citationPrecision = 0.0;
    double citationRecall = 0.0;
    if (!query.expectedCitations.isEmpty()) {
      // Build expected: marker → sourceDoc
      Map<Integer, String> expectedMarkerDocs = new HashMap<>();
      for (ExpectedCitation ec : query.expectedCitations) {
        expectedMarkerDocs.put(ec.marker, ec.sourceDoc);
      }
      Set<Integer> foundMarkers = new HashSet<>(citations);
      // A citation is correct if the marker number matches AND points to the expected source doc
      long correctMarkers =
          foundMarkers.stream()
              .filter(
                  marker -> {
                    String expectedDoc = expectedMarkerDocs.get(marker);
                    String actualDoc = ctx.markerToDocId.get(marker);
                    return expectedDoc != null && expectedDoc.equals(actualDoc);
                  })
              .count();
      citationPrecision =
          foundMarkers.isEmpty() ? 0.0 : (double) correctMarkers / foundMarkers.size();
      citationRecall = (double) correctMarkers / expectedMarkerDocs.size();
    }

    return new QueryEvalResult(
        query.id,
        query.question,
        answer,
        retrievedDocIds,
        retrievalRecall,
        factCoverage,
        forbiddenFactRate,
        answerSimilarity,
        faithfulness,
        citations.size(),
        citations,
        factResult.found().size(),
        factResult.missing().size(),
        simResult.method(),
        FAITHFULNESS_MODE,
        citationPrecision,
        citationRecall);
  }

  // === Retrieval ===

  private List<String> retrieveDocuments(String queryText, int topK) {
    try {
      float[] queryVector = getQueryVector(queryText);
      LuceneRuntimeTypes.SearchResult hybridResult =
          runtime.hybridSearchOps().searchHybridFiltered(queryText, queryVector, topK, null, io.justsearch.core.context.EngineContext.Urgency.FOREGROUND);

      List<String> hybridDocIds =
          hybridResult.hits().stream()
              .map(LuceneRuntimeTypes.SearchHit::docId)
              .toList();
      if (!hybridDocIds.isEmpty()) {
        return hybridDocIds;
      }

      LuceneRuntimeTypes.SearchResult textResult = runtime.textQueryOps().searchText(queryText, topK, null);
      List<String> textDocIds =
          textResult.hits().stream()
          .map(LuceneRuntimeTypes.SearchHit::docId)
          .toList();
      if (!textDocIds.isEmpty()) {
        log.info(
            "Hybrid retrieval returned no hits for query='{}'; using text fallback with {} hits",
            queryText,
            textDocIds.size());
      }
      return textDocIds;
    } catch (Exception e) {
      log.warn("Retrieval failed for '{}': {}", queryText, e.getMessage());
      return List.of();
    }
  }

  // === Prompt building ===

  /**
   * Max characters per document in context. Keeps total prompt within the context window and
   * prompt eval time within the deadline.
   */
  private static final int MAX_CONTEXT_CHARS_PER_DOC = 1000;

  /**
   * Context result containing formatted text, marker mapping, and chunk evidence used for
   * faithfulness scoring.
   */
  record ContextResult(
      String text,
      Map<Integer, String> markerToDocId,
      List<String> chunkTexts,
      List<String> chunkDocIds) {}

  private ContextResult buildContext(List<String> docIds) {
    StringBuilder sb = new StringBuilder();
    Map<Integer, String> markerToDocId = new LinkedHashMap<>();
    List<String> chunkTexts = new ArrayList<>();
    List<String> chunkDocIds = new ArrayList<>();
    int docNumber = 0;
    for (String docId : docIds) {
      String content = docContents.get(docId);
      if (content != null) {
        docNumber++;
        markerToDocId.put(docNumber, docId);
        String trimmed = content.strip();
        if (trimmed.length() > MAX_CONTEXT_CHARS_PER_DOC) {
          trimmed = trimmed.substring(0, MAX_CONTEXT_CHARS_PER_DOC) + "...";
        }
        chunkTexts.add(trimmed);
        chunkDocIds.add(docId);
        sb.append("[Document ").append(docNumber).append("]\n");
        sb.append(trimmed).append("\n\n");
      }
    }
    return new ContextResult(sb.toString(), markerToDocId, chunkTexts, chunkDocIds);
  }

  /**
   * Builds context in the agent-style format that mirrors {@code SearchTool.formatResults()}.
   * Simulates the full agent experience: per-result formatting with excerpt truncation, result
   * count limit, and total budget truncation (mimicking {@code
   * AgentLoopService.truncateForContext()}).
   */
  private ContextResult buildAgentStyleContext(List<String> docIds, AgentFormatParams params) {
    StringBuilder sb = new StringBuilder();
    Map<Integer, String> markerToDocId = new LinkedHashMap<>();
    List<String> chunkTexts = new ArrayList<>();
    List<String> chunkDocIds = new ArrayList<>();
    int docNumber = 0;
    int resultLimit = Math.min(params.maxResults, docIds.size());
    for (int i = 0; i < resultLimit; i++) {
      String docId = docIds.get(i);
      String content = docContents.get(docId);
      if (content != null) {
        docNumber++;
        markerToDocId.put(docNumber, docId);

        // Derive filename/path from docId (corpus uses IDs like "cuisine-italian")
        String filename = docId + ".md";
        String path = "/corpus/" + filename;

        sb.append(String.format("[%d] %s (score: 1.00)%n", docNumber, filename));
        sb.append(String.format("    Path: %s%n", path));

        // Mimic SearchTool: truncate, strip newlines, quote (skip if excerpt=0)
        if (params.maxExcerptChars > 0) {
          String excerpt =
              content.strip().replace("\"", "'").replace("\n", " ").replace("\r", "");
          if (excerpt.length() > params.maxExcerptChars) {
            excerpt = excerpt.substring(0, params.maxExcerptChars) + "...";
          }
          chunkTexts.add(excerpt);
          sb.append(String.format("    Excerpt: \"%s\"%n", excerpt));
        } else {
          chunkTexts.add(""); // no content — simulates vector search zero-content
        }
        chunkDocIds.add(docId);
      }
    }
    sb.append(String.format("%nFound %d results.", docNumber));

    // Simulate AgentLoopService.truncateForContext() — brutal mid-text truncation
    String formatted = sb.toString();
    if (params.maxTotalChars > 0 && formatted.length() > params.maxTotalChars) {
      formatted = formatted.substring(0, params.maxTotalChars) + "...";
    }
    return new ContextResult(formatted, markerToDocId, chunkTexts, chunkDocIds);
  }

  /** Parsed agent format parameters from the format string. */
  record AgentFormatParams(int maxResults, int maxExcerptChars, int maxTotalChars) {}

  /**
   * Parses format string like {@code agent-k5-exc200-bud900} into structured params. Falls back to
   * current agent defaults (k=5, exc=200, bud=900) for missing or unparseable segments.
   */
  private static AgentFormatParams parseAgentFormat(String format) {
    int k = 5;
    int exc = 200;
    int bud = 0; // 0 = no total budget limit
    for (String segment : format.split("-")) {
      try {
        if (segment.startsWith("k")) {
          k = Integer.parseInt(segment.substring(1));
        } else if (segment.startsWith("exc")) {
          exc = Integer.parseInt(segment.substring(3));
        } else if (segment.startsWith("bud")) {
          bud = Integer.parseInt(segment.substring(3));
        }
      } catch (NumberFormatException e) {
        // skip unparseable segments
      }
    }
    return new AgentFormatParams(k, exc, bud);
  }

  private String buildRagPrompt(String context, String question) {
    return "<|im_start|>system\n"
        + "You are a helpful assistant. Answer questions using ONLY the provided context. "
        + "Use [N] citation markers to reference document numbers. "
        + "If the answer is not in the context, say \"not found\".\n"
        + "<|im_end|>\n"
        + "<|im_start|>user\n"
        + "Context:\n"
        + context
        + "\nQuestion: "
        + question
        + "\n<|im_end|>\n"
        + "<|im_start|>assistant\n";
  }

  // === Citation parsing ===

  /** Scores faithfulness via cross-encoder sentence-to-context matching. */
  private double computeFaithfulness(String answer, List<String> chunkTexts, List<String> chunkDocIds) {
    if (citationScorer == null || !citationScorer.isAvailable()) {
      throw new IllegalStateException(
          "faithfulness_mode=cross-encoder requested, but CitationScorer is unavailable");
    }
    if (answer == null || answer.isBlank()) {
      return 0.0;
    }
    List<String> sentences = splitSentences(answer);
    if (sentences.isEmpty()) {
      return 0.0;
    }
    CitationScorer.ScoringResult scoringResult =
        citationScorer.scoreAll(
            sentences,
            chunkTexts,
            chunkDocIds,
            FAITHFULNESS_THRESHOLD,
            FAITHFULNESS_DEADLINE_MS);
    if (scoringResult.sentencesTotal() == 0) {
      return 0.0;
    }
    return (double) scoringResult.sentencesMatched() / scoringResult.sentencesTotal();
  }

  /**
   * The PRODUCTION splitter, not a copy of it (tempdoc 847 S5). The faithfulness ratio below is
   * "how many of the answer's sentences a source supports", so it has to cut the answer into the
   * same sentences production scores — a local copy measured a segmentation the product stopped
   * using the moment either side changed.
   */
  static List<String> splitSentences(String text) {
    return AnswerSegmentation.splitSentences(text);
  }

  /** Extracts [N] citation markers from LLM output. Returns list of marker numbers. */
  static List<Integer> parseCitationMarkers(String text) {
    List<Integer> markers = new ArrayList<>();
    if (text == null || text.isBlank()) {
      return markers;
    }
    Matcher matcher = CITATION_PATTERN.matcher(text);
    while (matcher.find()) {
      try {
        markers.add(Integer.parseInt(matcher.group(1)));
      } catch (NumberFormatException e) {
        // Skip malformed markers
      }
    }
    return markers;
  }

  // === Results aggregation ===

  private AggregateMetrics computeAggregates(List<QueryEvalResult> results) {
    if (results.isEmpty()) {
      return new AggregateMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
    double factMean = results.stream().mapToDouble(r -> r.factCoverage).average().orElse(0);
    double hallMean = results.stream().mapToDouble(r -> r.forbiddenFactRate).average().orElse(0);
    double simMean = results.stream().mapToDouble(r -> r.answerSimilarity).average().orElse(0);
    double retMean = results.stream().mapToDouble(r -> r.retrievalRecall).average().orElse(0);
    double faithMean = results.stream().mapToDouble(r -> r.faithfulness).average().orElse(0);
    double citPrecMean =
        results.stream().mapToDouble(r -> r.citationPrecision).average().orElse(0);
    double citRecMean = results.stream().mapToDouble(r -> r.citationRecall).average().orElse(0);
    long citTotal = results.stream().mapToLong(r -> r.citationsFound).sum();
    return new AggregateMetrics(
        factMean, hallMean, simMean, retMean, faithMean, citPrecMean, citRecMean, citTotal,
        results.size());
  }

  private void logAggregates(AggregateMetrics agg) {
    log.info("=== RAG Quality Evaluation Summary ===");
    log.info("Queries evaluated: {}", agg.queryCount);
    log.info("Mean fact coverage: {}", fmt(agg.factCoverageMean));
    log.info("Mean forbidden fact rate: {}", fmt(agg.forbiddenFactRateMean));
    log.info("Mean answer similarity: {}", fmt(agg.answerSimilarityMean));
    log.info("Mean retrieval recall: {}", fmt(agg.retrievalRecallMean));
    log.info("Mean faithfulness: {}", fmt(agg.faithfulnessMean));
    log.info("Mean citation precision: {}", fmt(agg.citationPrecisionMean));
    log.info("Mean citation recall: {}", fmt(agg.citationRecallMean));
    log.info("Total citations found: {}", agg.totalCitations);
  }

  // === JSON output ===

  private void writeResultsJson(List<QueryEvalResult> results, AggregateMetrics agg)
      throws IOException {
    Map<String, Object> output = new LinkedHashMap<>();
    output.put("suite", "rag-eval");
    output.put("version", 1);
    output.put("timestamp", java.time.Instant.now().toString());

    // Run metadata for bench infrastructure compatibility
    Map<String, Object> runMetadata = new LinkedHashMap<>();
    runMetadata.put("model_name", llmModelName);
    runMetadata.put("similarity_mode", deriveSimilarityMode(results));
    runMetadata.put("faithfulness_mode", results.isEmpty() ? "unknown" : results.get(0).faithfulnessMode);
    runMetadata.put("retrieval_mode", "hybrid");
    runMetadata.put("context_chars_per_doc", MAX_CONTEXT_CHARS_PER_DOC);
    runMetadata.put("max_new_tokens", 128);
    runMetadata.put("context_format", CONTEXT_FORMAT);
    output.put("run_metadata", runMetadata);

    Map<String, Object> aggregate = new LinkedHashMap<>();
    aggregate.put("fact_coverage_mean", round(agg.factCoverageMean));
    aggregate.put("forbidden_fact_rate_mean", round(agg.forbiddenFactRateMean));
    aggregate.put("answer_similarity_mean", round(agg.answerSimilarityMean));
    aggregate.put("retrieval_recall_mean", round(agg.retrievalRecallMean));
    aggregate.put("faithfulness_mean", round(agg.faithfulnessMean));
    aggregate.put("citation_precision_mean", round(agg.citationPrecisionMean));
    aggregate.put("citation_recall_mean", round(agg.citationRecallMean));
    aggregate.put("total_citations", agg.totalCitations);
    aggregate.put("query_count", agg.queryCount);
    output.put("aggregate", aggregate);

    output.put("per_query", buildPerQueryList(results));

    // Write to project-relative build dir so results survive temp cleanup
    Path projectRoot = findProjectRoot();
    Path evalDir;
    if (projectRoot != null) {
      evalDir = projectRoot.resolve("build/test-results/rag-eval");
    } else {
      evalDir = tempDir.resolve("eval");
    }
    Files.createDirectories(evalDir);
    String outputFilename =
        CONTEXT_FORMAT.equals("document-block")
            ? "rag-eval-result.v1.json"
            : "rag-eval-result.v1." + CONTEXT_FORMAT + ".json";
    Path outputPath = evalDir.resolve(outputFilename);
    MAPPER.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), output);
    log.info("Results written to: {}", outputPath);
  }

  /**
   * Write bench-suite.v2 format alongside the v1 file. Enables lane-agnostic scorecard
   * aggregation and corpus SHA versioning.
   */
  private void writeResultsV2Json(List<QueryEvalResult> results, AggregateMetrics agg)
      throws IOException {
    String similarityMode = deriveSimilarityMode(results);
    String faithfulnessMode =
        results.isEmpty() ? "unknown" : results.get(0).faithfulnessMode;
    String suiteId =
        "rag-eval-" + similarityMode + "-" + faithfulnessMode + "-q" + agg.queryCount;

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("schema_family", "bench-suite");
    out.put("schema_version", 2);
    out.put("suite_kind", "rag-eval");
    out.put("suite_id", suiteId);
    out.put("captured_at", java.time.Instant.now().toString());

    Map<String, Object> runMetadata = new LinkedHashMap<>();
    runMetadata.put("source_schema", "rag-eval.v2-native");
    runMetadata.put("git_sha", null);
    runMetadata.put("machine_fingerprint", Map.of());
    runMetadata.put("model_name", llmModelName);
    runMetadata.put("similarity_mode", similarityMode);
    runMetadata.put("faithfulness_mode", faithfulnessMode);
    Map<String, Object> knobs = new LinkedHashMap<>();
    knobs.put("retrieval_mode", "hybrid");
    knobs.put("context_chars_per_doc", MAX_CONTEXT_CHARS_PER_DOC);
    knobs.put("max_new_tokens", 128);
    knobs.put("context_format", CONTEXT_FORMAT);
    runMetadata.put("knobs", knobs);
    out.put("run_metadata", runMetadata);

    Map<String, Object> workload = new LinkedHashMap<>();
    workload.put("query_count", agg.queryCount);
    workload.put("corpus_sha", corpusSha);
    workload.put("truth_manifest", "rag-eval-truth.v1.json");
    workload.put("vectors_file", "rag-eval-vectors.json");
    workload.put("corpus_doc_count", docContents.size());
    out.put("workload", workload);

    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("fact_coverage_mean", statConstMap(round(agg.factCoverageMean)));
    summary.put("forbidden_fact_rate_mean", statConstMap(round(agg.forbiddenFactRateMean)));
    summary.put("answer_similarity_mean", statConstMap(round(agg.answerSimilarityMean)));
    summary.put("faithfulness_mean", statConstMap(round(agg.faithfulnessMean)));
    summary.put("retrieval_recall_mean", statConstMap(round(agg.retrievalRecallMean)));
    summary.put("citation_precision_mean", statConstMap(round(agg.citationPrecisionMean)));
    summary.put("citation_recall_mean", statConstMap(round(agg.citationRecallMean)));
    summary.put("total_citations", statConstMap((double) agg.totalCitations));
    summary.put("query_count", statConstMap((double) agg.queryCount));

    Map<String, Object> measurements = new LinkedHashMap<>();
    measurements.put("summary", summary);
    measurements.put("samples", Map.of("per_query", buildPerQueryList(results)));
    measurements.put("main_score", round(agg.factCoverageMean));
    measurements.put("main_score_metric", "fact_coverage_mean");
    out.put("measurements", measurements);

    Map<String, Object> decisions = new LinkedHashMap<>();
    decisions.put("policy_version", null);
    decisions.put("comparable", true);
    decisions.put("gate_status", "unknown");
    decisions.put("regressions", List.of());
    out.put("decisions", decisions);

    out.put("extensions", Map.of());

    Path projectRoot = findProjectRoot();
    Path evalDir;
    if (projectRoot != null) {
      evalDir = projectRoot.resolve("build/test-results/rag-eval");
    } else {
      evalDir = tempDir.resolve("eval");
    }
    Files.createDirectories(evalDir);
    String v2Filename =
        CONTEXT_FORMAT.equals("document-block")
            ? "rag-eval-result.v2.json"
            : "rag-eval-result.v2." + CONTEXT_FORMAT + ".json";
    Path v2Path = evalDir.resolve(v2Filename);
    MAPPER.writerWithDefaultPrettyPrinter().writeValue(v2Path.toFile(), out);
    log.info("V2 results written to: {}", v2Path);
  }

  private static List<Map<String, Object>> buildPerQueryList(List<QueryEvalResult> results) {
    List<Map<String, Object>> perQuery = new ArrayList<>();
    for (QueryEvalResult r : results) {
      Map<String, Object> qr = new LinkedHashMap<>();
      qr.put("id", r.queryId);
      qr.put("question", r.question);
      qr.put("answer", r.answerText);
      qr.put("fact_coverage", round(r.factCoverage));
      qr.put("forbidden_fact_rate", round(r.forbiddenFactRate));
      qr.put("answer_similarity", round(r.answerSimilarity));
      qr.put("faithfulness", round(r.faithfulness));
      qr.put("retrieval_recall", round(r.retrievalRecall));
      qr.put("citations_found", r.citationsFound);
      qr.put("citation_markers", r.citationMarkers);
      qr.put("citation_precision", round(r.citationPrecision));
      qr.put("citation_recall", round(r.citationRecall));
      qr.put("facts_found", r.factsFound);
      qr.put("facts_missing", r.factsMissing);
      qr.put("similarity_method", r.similarityMethod);
      qr.put("faithfulness_mode", r.faithfulnessMode);
      qr.put("retrieved_docs", r.retrievedDocIds);
      perQuery.add(qr);
    }
    return perQuery;
  }

  /** Wrap a single numeric value as a stat-const triple (median=min=max) for bench-suite.v2. */
  private static Map<String, Object> statConstMap(double v) {
    Map<String, Object> stat = new LinkedHashMap<>();
    stat.put("median", v);
    stat.put("min", v);
    stat.put("max", v);
    return stat;
  }

  /**
   * Compute a combined SHA-256 of all corpus components: truth manifest, frozen vectors,
   * and each corpus document. Used for non-comparability detection in the diff tool.
   */
  private static String computeCorpusSha() {
    try {
      MessageDigest combined = MessageDigest.getInstance("SHA-256");

      // Hash truth manifest
      combined.update(sha256OfResource("/manifests/rag-eval-truth.v1.json"));

      // Hash frozen vectors
      combined.update(sha256OfResource("/corpus/rag/rag-eval-vectors.json"));

      // Hash corpus doc contents in deterministic order (sorted by docId)
      List<String> sortedIds = new ArrayList<>(docContents.keySet());
      sortedIds.sort(java.util.Comparator.naturalOrder());
      for (String docId : sortedIds) {
        MessageDigest docDigest = MessageDigest.getInstance("SHA-256");
        combined.update(
            docDigest.digest(docContents.get(docId).getBytes(StandardCharsets.UTF_8)));
      }

      return bytesToHex(combined.digest());
    } catch (NoSuchAlgorithmException e) {
      log.warn("SHA-256 not available, corpus SHA will be null", e);
      return null;
    }
  }

  private static byte[] sha256OfResource(String resourcePath)
      throws NoSuchAlgorithmException {
    try (InputStream is = RagQualityEvalTest.class.getResourceAsStream(resourcePath)) {
      if (is == null) {
        return MessageDigest.getInstance("SHA-256").digest(new byte[0]);
      }
      return MessageDigest.getInstance("SHA-256").digest(is.readAllBytes());
    } catch (IOException e) {
      log.warn("Failed to hash resource: {}", resourcePath, e);
      return MessageDigest.getInstance("SHA-256").digest(new byte[0]);
    }
  }

  private static String bytesToHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }

  private static String deriveSimilarityMode(List<QueryEvalResult> results) {
    for (QueryEvalResult result : results) {
      String method = result.similarityMethod;
      if (method != null && !method.isBlank() && !"n/a".equalsIgnoreCase(method)) {
        return method;
      }
    }
    return "unknown";
  }

  // === Setup helpers ===

  private static void loadManifestAndCorpus() throws IOException {
    try (InputStream is =
        RagQualityEvalTest.class.getResourceAsStream(
            "/manifests/rag-eval-truth.v1.json")) {
      if (is == null) {
        throw new IOException("RAG eval manifest not found on classpath");
      }
      JsonNode root = MAPPER.readTree(is);
      String baseDir = root.path("corpus").path("base_dir").asText("corpus/rag/");

      // Load document contents
      docContents = new HashMap<>();
      for (JsonNode docNode : root.path("corpus").path("documents")) {
        String id = docNode.path("id").asText();
        String file = docNode.path("file").asText();
        String resourcePath = "/" + baseDir + file;
        try (InputStream docIs =
            RagQualityEvalTest.class.getResourceAsStream(resourcePath)) {
          if (docIs == null) {
            throw new IOException("Corpus document not found on classpath: " + resourcePath);
          }
          docContents.put(id, new String(docIs.readAllBytes(), StandardCharsets.UTF_8));
        }
      }

      // Parse queries
      ragQueries = new ArrayList<>();
      for (JsonNode qNode : root.path("queries")) {
        List<String> corpusDocs = new ArrayList<>();
        for (JsonNode d : qNode.path("corpus_docs")) {
          corpusDocs.add(d.asText());
        }
        List<String> expectedSourceDocs = new ArrayList<>();
        for (JsonNode d : qNode.path("expected_source_docs")) {
          expectedSourceDocs.add(d.asText());
        }
        List<String> requiredFacts = new ArrayList<>();
        for (JsonNode f : qNode.path("required_facts")) {
          requiredFacts.add(f.asText());
        }
        List<String> forbiddenFacts = new ArrayList<>();
        for (JsonNode f : qNode.path("forbidden_facts")) {
          forbiddenFacts.add(f.asText());
        }

        List<ExpectedCitation> expectedCitations = new ArrayList<>();
        for (JsonNode c : qNode.path("expected_citations")) {
          expectedCitations.add(
              new ExpectedCitation(c.path("marker").asInt(), c.path("source_doc").asText()));
        }

        ragQueries.add(
            new RagQuery(
                qNode.path("id").asText(),
                qNode.path("question").asText(),
                corpusDocs,
                expectedSourceDocs,
                qNode.path("golden_answer").asText(),
                requiredFacts,
                forbiddenFacts,
                expectedCitations));
      }

      // Parse quality thresholds
      JsonNode tNode = root.path("thresholds");
      qualityThresholds =
          new QualityThresholds(
              tNode.path("fact_coverage_min").asDouble(0.0),
              tNode.path("forbidden_fact_rate_max").asDouble(1.0),
              tNode.path("answer_similarity_min").asDouble(0.0),
              tNode.path("faithfulness_min").asDouble(0.0));
    }
    log.info("Loaded {} corpus docs, {} queries", docContents.size(), ragQueries.size());
  }

  private static void setupLuceneRuntime() throws Exception {
    Path projectRoot = findProjectRoot();
    if (projectRoot == null) {
      throw new IllegalStateException("Cannot find project root (settings.gradle.kts)");
    }

    tempDir = Files.createTempDirectory("rag-eval-test-");
    log.info("Created temp directory: {}", tempDir);

    // Copy and modify config
    Path realConfig = projectRoot.resolve("config/application.yaml");
    if (!Files.exists(realConfig)) {
      throw new IllegalStateException("Config file not found: " + realConfig);
    }
    String configContent = Files.readString(realConfig);
    String modifiedConfig =
        configContent.replaceAll(
            "data_dir:.*",
            "data_dir: " + tempDir.toString().replace("\\", "\\\\"));

    configFile = tempDir.resolve("config/application.yaml");
    Files.createDirectories(configFile.getParent());
    Files.writeString(configFile, modifiedConfig);

    previousConfig = System.getProperty("justsearch.config");
    previousUserDir = System.getProperty("user.dir");
    System.setProperty("justsearch.config", configFile.toString());
    System.setProperty("user.dir", projectRoot.toString());

    // Load field catalog
    JustSearchConfigurationLoader loader = new JustSearchConfigurationLoader();
    FieldCatalogDef catalog = loader.loadFieldCatalog();
    Integer dim = catalog.vectorDimension();
    vectorDimension = (dim != null) ? dim : 768;

    runtime = io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(catalog).ephemeral().open();
    log.info("Lucene runtime started (vector dim: {})", vectorDimension);
  }

  private static void loadFrozenVectors() {
    embeddingBackend =
        FrozenEmbeddingBackend.loadResource("/corpus/rag/rag-eval-vectors.json", false);
    log.info("Loaded frozen embedding vectors for hybrid search");
  }

  private static void indexCorpus() throws Exception {
    log.info("Indexing {} RAG corpus documents...", docContents.size());

    for (Map.Entry<String, String> entry : docContents.entrySet()) {
      String docId = entry.getKey();
      String content = entry.getValue();
      float[] docVector = getDocVector(docId);

      Map<String, Object> fields =
          Map.of(
              SchemaFields.DOC_ID, docId,
              SchemaFields.DOC_UID, docId + "#0",
              SchemaFields.CONTENT, content,
              SchemaFields.VECTOR, docVector);

      runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
    }

    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefresh();
    log.info("Corpus indexed and committed");
  }

  /** Returns the frozen embedding vector for a corpus document, or a zero vector if not found. */
  private static float[] getDocVector(String docId) {
    List<Double> vec = embeddingBackend.getVector(docId);
    if (vec == null) {
      log.warn("No frozen vector for doc '{}', using zero vector", docId);
      return new float[vectorDimension];
    }
    return toFloatArray(vec);
  }

  /** Returns the frozen embedding vector for a query, or a zero vector if not found. */
  private static float[] getQueryVector(String questionText) {
    List<Double> vec = embeddingBackend.getVector(questionText);
    if (vec == null) {
      log.warn("No frozen vector for query '{}', using zero vector", questionText);
      return new float[vectorDimension];
    }
    return toFloatArray(vec);
  }

  private static float[] toFloatArray(List<Double> doubles) {
    float[] result = new float[doubles.size()];
    for (int i = 0; i < doubles.size(); i++) {
      result[i] = doubles.get(i).floatValue();
    }
    return result;
  }

  private static void setupLlmBackend() throws BackendException {
    Path modelPath = AiQualityTestConfig.findQwenModel();
    if (!Files.exists(modelPath)) {
      throw new IllegalStateException("Qwen model not found at " + modelPath);
    }
    if (!AiQualityTestConfig.isNativeAvailable()) {
      throw new IllegalStateException(
          "Native library not available. Build: ./gradlew :modules:ai-engine-native:buildBridge");
    }

    LocalIntentTranslatorConfig config =
        AiQualityTestConfig.deterministicConfig(modelPath)
            .contextLength(2048)
            .maxNewTokens(128)
            .deadlineMs(180_000) // 3 min per inference — multi-doc queries need longer prompt eval
            .build();

    llmBackend = new BackendRegistry().resolve("deterministic", config).orElseThrow(() -> new IllegalStateException("No backend provider available")).create(config);
    String fileName = modelPath.getFileName().toString();
    llmModelName = fileName.endsWith(".gguf") ? fileName.substring(0, fileName.length() - 5) : fileName;
    log.info("LLM backend initialized: {}", modelPath);
  }

  private static boolean isRealEmbeddingRequested() {
    String value = System.getenv("JUSTSEARCH_ENABLE_REAL_EMBEDDING");
    if (value == null) {
      return false;
    }
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    return "true".equals(normalized) || "1".equals(normalized);
  }

  private static void setupCitationScorer() {
    Path projectRoot = findProjectRoot();
    List<Path> checkedModelDirs = new ArrayList<>();
    Path modelDir = resolveCitationModelDir(projectRoot, checkedModelDirs);
    Path modelOnnx = modelDir.resolve("model.onnx");
    Path tokenizer = modelDir.resolve("tokenizer.json");

    if (!Files.exists(modelOnnx) || !Files.exists(tokenizer)) {
      throw new IllegalStateException(
          "faithfulness_mode="
              + FAITHFULNESS_MODE
              + " requested, but citation scorer assets are missing. "
              + "Checked directories: "
              + checkedModelDirs
              + ". Expected files: "
              + modelOnnx
              + ", "
              + tokenizer
              + ". Run preflight: node scripts/verify-prerequisites.mjs. "
              + "If needed, set JUSTSEARCH_CITATION_SCORER_MODEL_PATH and "
              + "onnxruntime.native.path.");
    }

    try {
      // Tempdoc 397 §14.28 U1: testFixtures helper wraps OrtSessionAssembler.buildManager.
      io.justsearch.ort.SessionHandle sessions =
          io.justsearch.ort.testing.InferenceCompositionRootTestHelper.cpuSessionFor(
              "citation-rag-eval", modelDir);
      io.justsearch.reranker.RerankerAssembly assembly =
          CitationScorer.buildAssembly(sessions, tokenizer, 512);
      citationScorer =
          new CitationScorer(assembly.sessions(), assembly.shape(), assembly.tokenizer());
      log.info("Citation scorer initialized for faithfulness: modelDir={}", modelDir);
    } catch (Exception e) {
      throw new IllegalStateException(
          "Failed to initialize citation scorer for faithfulness_mode="
              + FAITHFULNESS_MODE
              + ". Checked directories: "
              + checkedModelDirs
              + ". Run preflight: node scripts/verify-prerequisites.mjs. "
              + "If needed, set JUSTSEARCH_CITATION_SCORER_MODEL_PATH and "
              + "onnxruntime.native.path.",
          e);
    }
  }

  private static Path resolveCitationModelDir(Path projectRoot, List<Path> checkedModelDirs) {
    String explicitModelDir = System.getenv("JUSTSEARCH_CITATION_SCORER_MODEL_PATH");
    if (explicitModelDir == null || explicitModelDir.isBlank()) {
      explicitModelDir = System.getProperty("justsearch.citation.scorer.model_path");
    }

    if (explicitModelDir != null && !explicitModelDir.isBlank()) {
      Path explicitPath = Path.of(explicitModelDir).toAbsolutePath().normalize();
      checkedModelDirs.add(explicitPath);
      return explicitPath;
    }

    Path root = projectRoot != null ? projectRoot : Path.of(System.getProperty("user.dir"));
    Path fallbackPath =
        root.resolve("models").resolve("citation-scorer").resolve("ms-marco-MiniLM-L2-v2");
    checkedModelDirs.add(fallbackPath);
    return fallbackPath;
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

  private static String fmt(double value) {
    return String.format("%.3f", value);
  }

  private static double round(double value) {
    return Math.round(value * 1000.0) / 1000.0;
  }

  // === Data records ===

  record ExpectedCitation(int marker, String sourceDoc) {}

  record RagQuery(
      String id,
      String question,
      List<String> corpusDocs,
      List<String> expectedSourceDocs,
      String goldenAnswer,
      List<String> requiredFacts,
      List<String> forbiddenFacts,
      List<ExpectedCitation> expectedCitations) {}

  record QueryEvalResult(
      String queryId,
      String question,
      String answerText,
      List<String> retrievedDocIds,
      double retrievalRecall,
      double factCoverage,
      double forbiddenFactRate,
      double answerSimilarity,
      double faithfulness,
      int citationsFound,
      List<Integer> citationMarkers,
      int factsFound,
      int factsMissing,
      String similarityMethod,
      String faithfulnessMode,
      double citationPrecision,
      double citationRecall) {}

  record AggregateMetrics(
      double factCoverageMean,
      double forbiddenFactRateMean,
      double answerSimilarityMean,
      double retrievalRecallMean,
      double faithfulnessMean,
      double citationPrecisionMean,
      double citationRecallMean,
      long totalCitations,
      int queryCount) {}

  record QualityThresholds(
      double factCoverageMin,
      double forbiddenFactRateMax,
      double answerSimilarityMin,
      double faithfulnessMin) {}
}
