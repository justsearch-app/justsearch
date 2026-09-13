/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.DocumentService.ContextResult;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.RetrieveContextParams;
import io.justsearch.app.services.worker.ContextSufficiencyService;
import io.justsearch.app.services.worker.FilterNormalizationService;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controller for RAG context retrieval endpoints.
 *
 * <p>Provides HTTP endpoints for external agents (via MCP) and internal use to retrieve
 * assembled context from JustSearch's hybrid search pipeline, with quality signals
 * and citation metadata.
 */
public class RetrieveContextController {
  private static final Logger log = LoggerFactory.getLogger(RetrieveContextController.class);

  private static final long RETRIEVE_TIMEOUT_MS = 15_000;
  private static final long CITATIONS_TIMEOUT_MS = 10_000;

  // Tempdoc 526 F2: supplier-capture (same fix as PreviewController + ChunkInfoController).
  private final Supplier<DocumentService> documentServiceSupplier;
  private final ContextSufficiencyService sufficiencyService;
  private final FilterNormalizationService normService;
  private final Supplier<String> facetSnapshotSupplier;

  public RetrieveContextController(
      KnowledgeServerBootstrap knowledgeServer,
      DocumentService documentService,
      OnlineAiService onlineAiService,
      Supplier<String> facetSnapshotSupplier) {
    this(knowledgeServer, () -> documentService, onlineAiService, facetSnapshotSupplier);
  }

  // knowledgeServer is retained for the constructor-overload/wiring API; its backing field was
  // removed (write-only) and the sibling overload delegates through this one.
  @SuppressWarnings("PMD.UnusedFormalParameter")
  public RetrieveContextController(
      KnowledgeServerBootstrap knowledgeServer,
      Supplier<DocumentService> documentServiceSupplier,
      OnlineAiService onlineAiService,
      Supplier<String> facetSnapshotSupplier) {
    this.documentServiceSupplier = documentServiceSupplier;
    this.facetSnapshotSupplier = facetSnapshotSupplier != null ? facetSnapshotSupplier : () -> "";
    this.sufficiencyService = new ContextSufficiencyService(onlineAiService);
    this.normService = new FilterNormalizationService(onlineAiService);
  }

  private DocumentService documentService() {
    return documentServiceSupplier.get();
  }

  /**
   * POST /api/knowledge/retrieve-context
   *
   * <p>Retrieves assembled context from the hybrid search pipeline for RAG use.
   * Returns context string, chunk metadata for citations, and quality signals.
   */
  @SuppressWarnings("unchecked")
  public void handleRetrieveContext(Context ctx) {

    Map<String, Object> body = ctx.bodyAsClass(Map.class);
    String question = (String) body.get("query");
    if (question == null || question.isBlank()) {
      ctx.status(400).json(Map.of("error", "query is required"));
      return;
    }

    int topK = getInt(body, "top_k", 5);
    int maxTokens = getInt(body, "max_tokens", 4096);
    boolean autoEntityExtract = getBoolean(body, "auto_entity_extract", true);
    String formatStr = getString(body, "context_format", "labeled");

    // Parse filters
    Map<String, Object> filters = (Map<String, Object>) body.get("filters");
    String pathPrefix = "";
    List<String> fileKind = List.of();
    List<String> entityPersons = List.of();
    List<String> entityOrganizations = List.of();
    List<String> entityLocations = List.of();
    RetrieveContextParams.TimeRange modifiedAt = RetrieveContextParams.TimeRange.UNSET;
    List<String> metaSource = List.of();
    List<String> metaAuthor = List.of();
    List<String> metaCategory = List.of();
    RetrieveContextParams.TimeRange metaPublishedAt = RetrieveContextParams.TimeRange.UNSET;
    // Tempdoc 821 §3-C2 — the collection scope. Same wire key as the search endpoint's filter block
    // (`collection`, see KnowledgeSearchController#parseFilters), so one scope name spans both
    // surfaces. Absent/empty = the default scope, not "match nothing".
    List<String> collection = List.of();

    if (filters != null) {
      pathPrefix = getString(filters, "path_prefix", "");
      collection = getStringList(filters, "collection");
      fileKind = getStringList(filters, "file_kind");
      entityPersons = getStringList(filters, "entity_persons");
      entityOrganizations = getStringList(filters, "entity_organizations");
      entityLocations = getStringList(filters, "entity_locations");

      String modAfter = getString(filters, "modified_after", "");
      String modBefore = getString(filters, "modified_before", "");
      if (!modAfter.isEmpty() || !modBefore.isEmpty()) {
        long fromMs = modAfter.isEmpty() ? 0 : parseIso8601ToMs(modAfter);
        long toMs = modBefore.isEmpty() ? 0 : parseIso8601ToMs(modBefore);
        modifiedAt = new RetrieveContextParams.TimeRange(fromMs, toMs);
      }

      // Lowercase metadata filters to match index-time normalization (366 Phase 5 parity)
      metaSource = lowerList(getStringList(filters, "meta_source"));
      metaAuthor = lowerList(getStringList(filters, "meta_author"));
      metaCategory = lowerList(getStringList(filters, "meta_category"));
      String pubAfter = getString(filters, "meta_published_after", "");
      String pubBefore = getString(filters, "meta_published_before", "");
      if (!pubAfter.isEmpty() || !pubBefore.isEmpty()) {
        long pubFrom = pubAfter.isEmpty() ? 0 : parseIso8601ToMs(pubAfter);
        long pubTo = pubBefore.isEmpty() ? 0 : parseIso8601ToMs(pubBefore);
        metaPublishedAt = new RetrieveContextParams.TimeRange(pubFrom, pubTo);
      }
    }

    // Parse doc_ids if present (for scoped retrieval).
    // When empty, the service layer will do a pre-search to find relevant documents.
    Set<String> docIds = new HashSet<>(getStringList(body, "doc_ids"));

    RetrieveContextParams.ContextFormat format = switch (formatStr.toLowerCase()) {
      case "xml" -> RetrieveContextParams.ContextFormat.XML;
      case "plain" -> RetrieveContextParams.ContextFormat.PLAIN;
      default -> RetrieveContextParams.ContextFormat.LABELED;
    };

    boolean returnFullDocuments = getBoolean(body, "return_full_documents", false);

    // 366 Phase 6: Async filter normalization (parity with search path).
    // Build a temporary Filters object for normalization, then extract results.
    boolean hasMetadataFilters = !metaSource.isEmpty() || !metaAuthor.isEmpty()
        || !metaCategory.isEmpty();
    java.util.concurrent.CompletableFuture<FilterNormalizationService.NormResult> normFuture = null;
    if (hasMetadataFilters && normService.isDeterministicAvailable()) {
      var tempFilters = io.justsearch.app.api.knowledge.KnowledgeSearchRequestFiltersBuilder.builder()
          .metaSource(metaSource)
          .metaAuthor(metaAuthor)
          .metaCategory(metaCategory)
          .build();
      normFuture = normService.normalize(tempFilters, facetSnapshotSupplier.get(), RequestEngineContext.get(ctx));
    }

    // Collect normalization result
    if (normFuture != null) {
      try {
        FilterNormalizationService.NormResult normResult = normFuture.get();
        if (normResult != null && normResult.normalizedFilters() != null) {
          var nf = normResult.normalizedFilters();
          metaSource = nf.metaSource();
          metaAuthor = nf.metaAuthor();
          metaCategory = nf.metaCategory();
          log.debug("Answer filter normalization applied (source={}, latency={}ms)",
              normResult.source(), normResult.latencyMs());
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (java.util.concurrent.ExecutionException e) {
        log.debug("Answer filter normalization failed: {}", e.getCause().getMessage());
      }
    }

    var params = new RetrieveContextParams(
        question, docIds, topK, maxTokens,
        entityPersons, entityOrganizations, entityLocations,
        modifiedAt, false, pathPrefix, fileKind,
        autoEntityExtract, format,
        metaSource, metaAuthor, metaCategory, metaPublishedAt, returnFullDocuments,
        List.of(), collection);

    try {
      ContextResult result = documentService()
          .retrieveContext(params, RequestEngineContext.get(ctx))
          .toCompletableFuture()
          .get(RETRIEVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);

      Map<String, Object> response = new HashMap<>();
      response.put("ok", true);
      response.put("context", result.context());
      response.put("total_found", result.chunksFound());

      // Chunk metadata for citations
      List<Map<String, Object>> chunks = result.citations().stream()
          .map(c -> {
            Map<String, Object> m = new HashMap<>();
            m.put("parent_doc_id", c.parentDocId());
            m.put("chunk_index", c.chunkIndex());
            m.put("chunk_total", c.chunkTotal());
            m.put("start_char", c.startChar());
            m.put("end_char", c.endChar());
            m.put("start_line", c.startLine());
            m.put("end_line", c.endLine());
            m.put("heading", c.headingText());
            m.put("score", c.score());
            m.put("excerpt", c.excerpt());
            return m;
          })
          .toList();
      response.put("chunks", chunks);

      // Quality signals
      var q = result.quality();
      Map<String, Object> quality = new HashMap<>();
      quality.put("best_score", q.bestChunkScore());
      quality.put("score_gap", q.scoreGap());
      quality.put("coverage", q.retrievalCoverage());
      quality.put("retrieval_mode", result.retrievalMode());
      quality.put("chunks_considered", q.chunksConsidered());
      quality.put("chunks_included", q.chunksIncluded());
      quality.put("truncated", result.contextTruncated());

      // 363 Track C: context sufficiency classification.
      // Runs the LLM on the assembled context to detect unanswerable queries.
      // Non-blocking with 5s deadline — surfaces null on timeout/error so agents
      // can distinguish "insufficient" from "check didn't run" (366 Phase 6d).
      if (sufficiencyService.isAvailable()
          && result.context() != null
          && !result.context().isBlank()) {
        try {
          ContextSufficiencyService.SufficiencyResult sr =
              sufficiencyService
                  .classify(question, result.context(), RequestEngineContext.get(ctx))
                  .toCompletableFuture()
                  .get(5, TimeUnit.SECONDS);
          quality.put("context_sufficient", sr != null ? sr.sufficient() : null);
        } catch (Exception e) {
          io.justsearch.core.execution.EngineFutures.rethrowExecutorRefusal(e);
          log.debug("Sufficiency check timed out or failed: {}", e.getMessage());
          quality.put("context_sufficient", null);
        }
      } else {
        quality.put("context_sufficient", null);
      }

      response.put("quality", quality);

      ctx.json(response);
    } catch (Exception e) {
      if (ApiErrorHandler.writeExecutorRefusal(ctx, e, null)) return;
      log.error("Failed to retrieve context", e);
      ctx.status(500).json(Map.of("ok", false, "error", e.getMessage()));
    }
  }

  /**
   * POST /api/knowledge/match-citations
   *
   * <p>Matches sentences in a generated answer to source chunks for grounded citations.
   */
  @SuppressWarnings("unchecked")
  public void handleMatchCitations(Context ctx) {
    Map<String, Object> body = ctx.bodyAsClass(Map.class);
    String answerText = (String) body.get("answer_text");
    if (answerText == null || answerText.isBlank()) {
      ctx.status(400).json(Map.of("error", "answer_text is required"));
      return;
    }

    List<Map<String, Object>> chunkRefs =
        (List<Map<String, Object>>) body.get("chunk_refs");
    if (chunkRefs == null || chunkRefs.isEmpty()) {
      ctx.status(400).json(Map.of("error", "chunk_refs is required"));
      return;
    }

    double threshold = getDouble(body, "threshold", 0.5);

    // Convert chunk_refs to verification sources. Tempdoc 836 §1.4: a ref may carry the literal
    // `passage_text` it wants verified, instead of only the (parent_doc_id, chunk_index) key the
    // Worker would look up. Omitting it keeps the historical lookup behaviour exactly.
    List<DocumentService.VerificationSource> sources = chunkRefs.stream()
        .map(ref -> new DocumentService.VerificationSource(
            new DocumentService.ContextCitation(
                (String) ref.get("parent_doc_id"),
                getInt(ref, "chunk_index", 0),
                1, 0, 0, 0f, "", 0, 0, "", 0,
                DocumentService.ContextInclusion.ABSENT),
            ref.get("passage_text") instanceof String s ? s : ""))
        .toList();

    try {
      var result = documentService()
          .matchCitationsAgainst(answerText, sources, threshold, RequestEngineContext.get(ctx))
          .toCompletableFuture()
          .get(CITATIONS_TIMEOUT_MS, TimeUnit.MILLISECONDS);

      Map<String, Object> response = new HashMap<>();
      response.put("ok", true);
      response.put("sentences_total", result.sentencesTotal());
      response.put("sentences_matched", result.sentencesMatched());
      // Tempdoc 836 §3.6 / §4 — how much was actually scored, and which producer scored it. The
      // two producers write `similarity` on measurably incomparable scales, so a reader of these
      // numbers needs to know which one arrived; and a matched/total ratio that silently omits a
      // budget shortfall attributes a deadline to the evidence.
      response.put("sentences_scored", result.sentencesScored());
      response.put("scoring_incomplete", result.scoringIncomplete());
      response.put("scorer", result.scorer().name());
      // Tempdoc 836 S2S3-A.1 — the TEXT-coverage axis, beside the sentence axis above. This
      // endpoint already published `sentences_scored`; publishing only that would make it the one
      // surface reporting "complete" over a pass that read a fraction of the caller's text, which
      // is the half-truth the amendment exists to remove. `windows_considered > 0 &&
      // windows_scored == 0` is the starved source: never examined, NOT unsupported.
      response.put(
          "source_coverage",
          result.sourceCoverage().stream()
              .map(
                  c ->
                      Map.of(
                          "source_index", c.sourceIndex(),
                          "windows_considered", c.windowsConsidered(),
                          "windows_scored", c.windowsScored()))
              .toList());
      response.put("text_coverage_complete", result.textCoverageComplete());
      response.put("starved_sources", result.starvedSources());
      response.put("took_ms", result.tookMs());

      List<Map<String, Object>> matches = result.matches().stream()
          .map(m -> {
            Map<String, Object> entry = new HashMap<>();
            entry.put("sentence_index", m.sentenceIndex());
            entry.put("sentence_text", m.sentenceText());
            // Tempdoc 822 §3b — the match carries the POSITION of the matched entry in the request's
            // `chunk_refs` array, not a document-relative chunk ordinal. It was published under the
            // input field's name (`chunk_index`), which invited exactly that misreading.
            entry.put("source_index", m.sourceIndex());
            entry.put("parent_doc_id", m.parentDocId());
            entry.put("similarity", m.similarity());
            // Which TEXT this score is about: the caller's literal passage, or a re-fetched chunk.
            entry.put("text_source", m.textSource().name());
            return entry;
          })
          .toList();
      response.put("matches", matches);

      ctx.json(response);
    } catch (Exception e) {
      log.error("Failed to match citations", e);
      ctx.status(500).json(Map.of("ok", false, "error", e.getMessage()));
    }
  }

  // --- Parsing helpers ---

  private static int getInt(Map<String, Object> map, String key, int defaultVal) {
    Object v = map.get(key);
    if (v instanceof Number n) return n.intValue();
    return defaultVal;
  }

  private static double getDouble(Map<String, Object> map, String key, double defaultVal) {
    Object v = map.get(key);
    if (v instanceof Number n) return n.doubleValue();
    return defaultVal;
  }

  private static boolean getBoolean(Map<String, Object> map, String key, boolean defaultVal) {
    Object v = map.get(key);
    if (v instanceof Boolean b) return b;
    return defaultVal;
  }

  private static String getString(Map<String, Object> map, String key, String defaultVal) {
    Object v = map.get(key);
    if (v instanceof String s) return s;
    return defaultVal;
  }

  @SuppressWarnings("unchecked")
  private static List<String> getStringList(Map<String, Object> map, String key) {
    Object v = map.get(key);
    if (v instanceof List<?> list) {
      return list.stream()
          .filter(String.class::isInstance)
          .map(String.class::cast)
          .toList();
    }
    return List.of();
  }

  private static long parseIso8601ToMs(String iso) {
    try {
      return java.time.Instant.parse(iso).toEpochMilli();
    } catch (Exception e) {
      return 0;
    }
  }

  /** Lowercases non-blank strings for case-insensitive metadata matching. */
  private static List<String> lowerList(List<String> values) {
    if (values == null || values.isEmpty()) return List.of();
    return values.stream()
        .filter(v -> v != null && !v.isBlank())
        .map(v -> v.toLowerCase(java.util.Locale.ROOT))
        .toList();
  }
}
