/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.api.DocumentService;
import io.justsearch.core.util.DocumentTypeDetector;
import io.justsearch.core.util.TokenEstimation;
import io.justsearch.telemetry.Telemetry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Debug endpoint controller for chunk inspection: {@code GET /api/debug/chunks?docId=...}.
 *
 * <p>Per tempdoc 491 §C5 (2026-05-12): {@code handleGetChunkInfo} was the only remaining
 * handler on {@code SummaryController} after C2/C3 migration. SummaryController is deleted
 * in this commit; the chunk-info handler moved here as its sole responsibility (kept under
 * {@code /api/debug/chunks} since it's a developer-only inspection tool, not part of the
 * substrate-driven chat namespace).
 *
 * <p>Uses the substrate-level {@link TokenEstimation} helper directly rather than the
 * deleted {@code TokenEstimationUtils} wrapper.
 */
public final class ChunkInfoController {

  private static final Logger log = LoggerFactory.getLogger(ChunkInfoController.class);
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);

  // Tempdoc 526 F2: supplier-capture (same fix as PreviewController + IndexingController).
  private final Supplier<DocumentService> documentServiceSupplier;
  private final Duration timeout;
  private final Telemetry telemetry;

  public ChunkInfoController(DocumentService documentService, Telemetry telemetry) {
    this(() -> documentService, DEFAULT_TIMEOUT, telemetry);
  }

  public ChunkInfoController(
      Supplier<DocumentService> documentServiceSupplier, Telemetry telemetry) {
    this(documentServiceSupplier, DEFAULT_TIMEOUT, telemetry);
  }

  public ChunkInfoController(DocumentService documentService, Duration timeout, Telemetry telemetry) {
    this(() -> documentService, timeout, telemetry);
  }

  public ChunkInfoController(
      Supplier<DocumentService> documentServiceSupplier, Duration timeout, Telemetry telemetry) {
    this.documentServiceSupplier = documentServiceSupplier;
    this.timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
    this.telemetry = telemetry;
  }

  private DocumentService documentService() {
    return documentServiceSupplier.get();
  }

  /** {@code GET /api/debug/chunks?docId=...} — return chunk-level info for a document. */
  public void handleGetChunkInfo(Context ctx) {
    String docId = ctx.queryParam("docId");
    if (docId == null || docId.isBlank()) {
      ctx.status(400)
          .json(
              ApiErrorHandler.toResponse(
                  ApiErrorCode.INVALID_REQUEST,
                  "docId query parameter required",
                  telemetry,
                  ApiErrorHandler.routeOf(ctx)));
      return;
    }

    try {
      Set<String> docIdSet = Set.of(docId);
      String broadQuery = "content text document";
      int maxChunks = 50;

      DocumentService.ContextResult result =
          documentService()
              .retrieveContextWithMeta(broadQuery, docIdSet, maxChunks, RequestEngineContext.get(ctx))
              .toCompletableFuture()
              .get(timeout.toMillis(), TimeUnit.MILLISECONDS);

      String context = result.context();
      List<DocumentService.ContextSection> sections = result.sections();
      int chunkCount =
          sections.isEmpty() ? (context.isBlank() ? 0 : result.chunksUsed()) : sections.size();
      int totalChars = context.length();
      int estimatedTokens = TokenEstimation.estimateTokens(context);

      String sampleContent = "";
      if (!sections.isEmpty()) {
        String firstContent = sections.get(0).content();
        sampleContent =
            firstContent.length() > 500 ? firstContent.substring(0, 500) + "..." : firstContent;
      } else if (!context.isBlank()) {
        sampleContent = context.length() > 500 ? context.substring(0, 500) + "..." : context;
      }

      Map<String, Object> response = new LinkedHashMap<>();
      response.put("docId", docId);
      response.put("filename", DocumentTypeDetector.extractFilename(docId));
      response.put("chunksFound", result.chunksFound());
      response.put("chunksUsed", result.chunksUsed());
      response.put("totalCharacters", totalChars);
      response.put("estimatedTokens", estimatedTokens);
      response.put("hasChunks", chunkCount > 0);
      response.put("sampleContent", sampleContent);
      response.put("retrievalMode", result.retrievalMode());

      if (!sections.isEmpty() && sections.size() > 1) {
        List<Map<String, Object>> chunkDetails = new ArrayList<>();
        for (int i = 0; i < Math.min(sections.size(), 10); i++) {
          DocumentService.ContextSection section = sections.get(i);
          String content = section.content();
          chunkDetails.add(
              Map.of(
                  "index",
                  i,
                  "sourceLabel",
                  section.sourceLabel(),
                  "characters",
                  content.length(),
                  "tokens",
                  TokenEstimation.estimateTokens(content),
                  "truncated",
                  section.truncated(),
                  "preview",
                  content.length() > 100 ? content.substring(0, 100) + "..." : content));
        }
        response.put("chunks", chunkDetails);
        if (sections.size() > 10) {
          response.put("note", "Only first 10 chunks shown");
        }
      }

      ctx.json(response);
    } catch (Exception e) {
      log.error("handleGetChunkInfo failed for docId={}", docId, e);
      Map<String, Object> err =
          ApiErrorHandler.toResponse(
              ApiErrorCode.INTERNAL_ERROR,
              "Failed to retrieve chunk info: "
                  + (e.getMessage() != null ? e.getMessage() : "Unknown error"),
              telemetry,
              ApiErrorHandler.routeOf(ctx));
      err.put("docId", docId);
      ctx.status(500).json(err);
    }
  }
}
