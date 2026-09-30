/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.services.observability.HeadApiMetricCatalog;
import io.justsearch.app.services.observability.HeadApiTags.ApiRequestTags;
import io.justsearch.app.services.observability.HttpMethod;
import io.justsearch.app.services.observability.HttpStatusClass;
import io.justsearch.telemetry.Telemetry;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Preview endpoint for inspecting extracted/indexed text content.
 *
 * <p>IMPORTANT: This controller must never open Lucene files directly. It only uses {@link DocumentService},
 * which is backed by the Worker process in production.
 *
 * <p>GET /api/preview?docId=...&offsetChars=...&maxChars=...
 *
 * <p>POST /api/debug/eval/document-ids — token-protected, bounded parent-ID enumeration for
 * evaluation snapshots.
 */
public final class PreviewController {
  private static final Logger log = LoggerFactory.getLogger(PreviewController.class);

  private static final int DEFAULT_MAX_CHARS = 20_000;
  private static final int MAX_MAX_CHARS = 200_000;
  private static final int MAX_EVAL_DOCUMENTS = 50_000;
  private static final Set<String> DOCUMENT_ID_REQUEST_FIELDS = Set.of("offset", "limit");
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  public static final String DOCUMENT_IDS_PATH = "/api/debug/eval/document-ids";
  // Tempdoc 526 F2: same root cause as F1 — the 502 refactor captured DocumentService
  // by value at controller-ctor time, so the sentinel from b.appFacade.documents() pinned
  // for the process lifetime when the worker came up async. Capture by Supplier instead;
  // AppFacade.documents() already resolves laterally.
  private final Supplier<DocumentService> documentServiceSupplier;
  private final Duration timeout;
  private final Runnable recordUserActivity;
  private final Telemetry telemetry;
  private final HeadApiMetricCatalog apiCatalog;

  public PreviewController(DocumentService documentService) {
    this(() -> documentService, DEFAULT_TIMEOUT, null, null, null);
  }

  PreviewController(DocumentService documentService, Duration timeout) {
    this(() -> documentService, timeout, null, null, null);
  }

  PreviewController(DocumentService documentService, Duration timeout, Runnable recordUserActivity) {
    this(() -> documentService, timeout, recordUserActivity, null, null);
  }

  PreviewController(
      DocumentService documentService, Duration timeout, Runnable recordUserActivity, Telemetry telemetry) {
    this(() -> documentService, timeout, recordUserActivity, telemetry, null);
  }

  // Back-compat ctor — wraps the value with a fixed-supplier.
  PreviewController(
      DocumentService documentService,
      Duration timeout,
      Runnable recordUserActivity,
      Telemetry telemetry,
      HeadApiMetricCatalog apiCatalog) {
    this(() -> documentService, timeout, recordUserActivity, telemetry, apiCatalog);
  }

  // Canonical ctor — Supplier captures the late-binding, so AppFacadeBootstrap's swap of
  // its internal documentService field becomes visible without a setter.
  public PreviewController(
      Supplier<DocumentService> documentServiceSupplier,
      Duration timeout,
      Runnable recordUserActivity,
      Telemetry telemetry,
      HeadApiMetricCatalog apiCatalog) {
    this.documentServiceSupplier = documentServiceSupplier;
    this.timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
    this.recordUserActivity = recordUserActivity;
    this.telemetry = telemetry;
    this.apiCatalog = apiCatalog;
  }

  private DocumentService documentService() {
    return documentServiceSupplier.get();
  }

  /**
   * Enumerates a bounded page of parent document IDs through the Worker-owned index.
   *
   * <p>The route is deliberately a POST: the production security filter therefore requires the
   * per-boot session token even though enumeration itself is read-only. A request cannot page past
   * the 50,000-document evaluation cohort ceiling.
   */
  public void handleListDocumentIds(Context ctx) {
    try {
      JsonNode body;
      try {
        body = MAPPER.readTree(ctx.body());
      } catch (Exception malformedJson) {
        rejectDocumentIdRequest(ctx, "Request body must be valid JSON");
        return;
      }
      if (body == null || !body.isObject()) {
        rejectDocumentIdRequest(ctx, "Request body must be a JSON object");
        return;
      }
      var fieldNames = new java.util.HashSet<String>();
      fieldNames.addAll(body.propertyNames());
      if (!fieldNames.equals(DOCUMENT_ID_REQUEST_FIELDS)) {
        rejectDocumentIdRequest(ctx, "Request body must contain only offset and limit");
        return;
      }

      JsonNode offsetNode = body.get("offset");
      JsonNode limitNode = body.get("limit");
      if (!isIntNode(offsetNode) || !isIntNode(limitNode)) {
        rejectDocumentIdRequest(ctx, "offset and limit must be integers");
        return;
      }

      long offsetLong = offsetNode.longValue();
      long limitLong = limitNode.longValue();
      if (offsetLong != 0) {
        rejectDocumentIdRequest(
            ctx, "offset must be zero because the evaluation export is a one-shot snapshot");
        return;
      }
      if (limitLong <= 0 || limitLong > MAX_EVAL_DOCUMENTS) {
        rejectDocumentIdRequest(
            ctx, "limit must be between 1 and the 50000-document evaluation ceiling");
        return;
      }

      DocumentService.DocumentIdPage page =
          documentService()
              .listAllDocumentIds((int) offsetLong, (int) limitLong, RequestEngineContext.get(ctx))
              .toCompletableFuture()
              .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (page == null) {
        throw new DocumentService.UnavailableException("Worker returned no document ID page");
      }
      if (page.docIds().size() > limitLong) {
        throw new IllegalStateException("Worker returned more document IDs than requested");
      }

      Map<String, Object> response = new LinkedHashMap<>();
      response.put("docIds", page.docIds());
      response.put("totalCount", page.totalCount());
      response.put("tookMs", page.tookMs());
      ctx.json(response);
    } catch (TimeoutException e) {
      ctx.status(504)
          .json(
              ApiErrorHandler.toResponse(
                  ApiErrorCode.TIMEOUT,
                  "Document ID enumeration timed out",
                  telemetry,
                  ApiErrorHandler.routeOf(ctx)));
    } catch (ExecutionException e) {
      handleDocumentIdFailure(ctx, e.getCause() == null ? e : e.getCause());
    } catch (DocumentService.UnavailableException e) {
      handleDocumentIdFailure(ctx, e);
    } catch (Exception e) {
      handleDocumentIdFailure(ctx, e);
    }
  }

  private static boolean isIntNode(JsonNode node) {
    return node != null && node.isIntegralNumber() && node.canConvertToInt();
  }

  private void rejectDocumentIdRequest(Context ctx, String message) {
    ctx.status(400)
        .json(
            ApiErrorHandler.toResponse(
                ApiErrorCode.INVALID_REQUEST,
                message,
                telemetry,
                ApiErrorHandler.routeOf(ctx)));
  }

  private void handleDocumentIdFailure(Context ctx, Throwable failure) {
    Throwable cause = failure == null ? new IllegalStateException("unknown failure") : failure;
    boolean unavailable =
        cause instanceof DocumentService.UnavailableException
            || cause instanceof UnsupportedOperationException;
    ApiErrorCode code = unavailable ? ApiErrorCode.INDEX_UNAVAILABLE : ApiErrorCode.INTERNAL_ERROR;
    int status = unavailable ? 503 : 500;
    String detail =
        cause.getMessage() == null || cause.getMessage().isBlank()
            ? "Document ID enumeration failed"
            : "Document ID enumeration failed: "
                + ApiErrorHandler.sanitizeMessage(cause.getMessage());
    log.error("Document ID enumeration failed", cause);
    ctx.status(status)
        .json(ApiErrorHandler.toResponse(code, detail, telemetry, ApiErrorHandler.routeOf(ctx)));
  }

  public void handlePreview(Context ctx) {
    try {
      long startNs = System.nanoTime();
      try {
    String rawDocId = ctx.queryParam("docId");
    if (rawDocId == null || rawDocId.isBlank()) {
      ctx.status(400).json(ApiErrorHandler.toResponse(ApiErrorCode.NO_DOC_ID, "docId query parameter required", telemetry, ApiErrorHandler.routeOf(ctx)));
      return;
    }

        // Foreground responsiveness: record interactive preview as user activity for the Head's
        // own idle checks (VDU pacing); the Worker observes its own foreground load.
        // Best-effort only; preview must not fail if this throws.
        try {
          if (recordUserActivity != null) {
            recordUserActivity.run();
          }
        } catch (Exception ignored) {
          // best-effort
        }

    // Treat docId as opaque - no normalization.
    // With P0.8, chunk docs are excluded from search results by default, so chunk IDs
    // should never reach this endpoint. If an opaque chunk ID (chunk:UUID) is passed,
    // log a warning; it will likely 404 which is acceptable behavior.
    // Legacy #chunk_ normalization removed - real file paths can contain this substring.
    String docId = rawDocId;
    boolean normalizedFromChunk = false; // No longer normalize; always false
    if (rawDocId.startsWith(CHUNK_PREFIX)) {
      log.warn("Preview requested for opaque chunk ID (will likely 404): {}", rawDocId);
    }

    int offsetChars = clampNonNegative(parseIntOrNull(ctx.queryParam("offsetChars")), 0);
    int maxChars = parseIntOrNull(ctx.queryParam("maxChars")) == null ? DEFAULT_MAX_CHARS : parseIntOrNull(ctx.queryParam("maxChars"));
    if (maxChars <= 0) maxChars = DEFAULT_MAX_CHARS;
    if (maxChars > MAX_MAX_CHARS) maxChars = MAX_MAX_CHARS;

    try {
      DocumentService.DocumentSlice slice =
          documentService()
              .fetchSlice(docId, offsetChars, maxChars, RequestEngineContext.get(ctx))
              .toCompletableFuture()
              .get(timeout.toMillis(), TimeUnit.MILLISECONDS);

      if (slice == null || !slice.found()) {
        String errorMsg = slice == null ? "not_found" : (slice.error() == null ? "not_found" : slice.error());
        Map<String, Object> resp = new LinkedHashMap<>(ApiErrorHandler.toResponse(ApiErrorCode.NOT_FOUND, errorMsg, telemetry, ApiErrorHandler.routeOf(ctx)));
        resp.put("docId", docId);
        resp.put("requestedDocId", rawDocId);
        resp.put("normalizedFromChunk", normalizedFromChunk);
        ctx.status(404).json(resp);
        return;
      }

      Map<String, Object> metadata = slice.metadata() == null ? Map.of() : slice.metadata();
      Object mime = metadata.get("mime");
      Object title = metadata.get("title");
      Object path = metadata.get("path");
      Object source = metadata.get("contentSource");
      Object extractionMethod = metadata.get("extraction_method");

      // VDU-related metadata for provenance tracking
      Object vduStatus = metadata.get("vdu_status");
      Object vduProcessed = metadata.get("vdu_processed");
      Object vduPageCount = metadata.get("vdu_page_count");
      Object vduEnrichment = metadata.get("vdu_enrichment");
      Object visualEvidence = metadata.get("visual_extraction_evidence");

      // Compute textProvenance based on the active extraction method plus VDU status.
      String textProvenance = computeTextProvenance(extractionMethod, vduStatus);

      Map<String, Object> response = new HashMap<>();
      response.put("docId", docId);
      response.put("requestedDocId", rawDocId);
      response.put("normalizedFromChunk", normalizedFromChunk);
      response.put("offsetChars", offsetChars);
      response.put("maxChars", maxChars);
      response.put("nextOffsetChars", slice.nextOffsetChars());
      response.put("totalChars", slice.totalChars());
      response.put("truncated", slice.truncated());
      response.put("content", slice.content());
      response.put("mime", mime instanceof String ? mime : null);
      response.put("title", title instanceof String ? title : null);
      response.put("path", path instanceof String ? path : null);
      response.put("source", source instanceof String ? source : null);
      response.put("extractionStatus", slice.extractionStatus());
      response.put("contentTruncated", slice.contentTruncated());
      response.put("extractionPolicyId", slice.extractionPolicyId());
      response.put("extractionParserId", slice.extractionParserId());
      response.put("sourceSha256", slice.sourceSha256());
      response.put("contentSha256", metadata.get("content_sha256"));

      // Add textProvenance and VDU status fields
      response.put("textProvenance", textProvenance);
      response.put("vduStatus", vduStatus instanceof String ? vduStatus : null);
      response.put("vduProcessed", "true".equalsIgnoreCase(String.valueOf(vduProcessed)));
      if (vduPageCount instanceof String s) {
        try {
          response.put("vduPageCount", Integer.parseInt(s));
        } catch (NumberFormatException ignored) {
          response.put("vduPageCount", null);
        }
      } else {
        response.put("vduPageCount", null);
      }
      response.put("vduEnrichment", vduEnrichment instanceof String ? vduEnrichment : null);
      response.put(
          "visualExtractionEvidence",
          visualEvidence instanceof String s ? parseVisualExtractionEvidence(s) : null);

      ctx.json(response);

    } catch (TimeoutException e) {
      ctx.status(504).json(ApiErrorHandler.toResponse(ApiErrorCode.TIMEOUT, "Preview timed out", telemetry, ApiErrorHandler.routeOf(ctx)));
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof DocumentService.UnavailableException) {
        String msg = cause.getMessage();
        ctx.status(503)
            .json(
                ApiErrorHandler.toResponse(
                    ApiErrorCode.INDEX_UNAVAILABLE,
                    msg == null || msg.isBlank()
                        ? "Index unavailable"
                        : ApiErrorHandler.sanitizeMessage(msg),
                    telemetry, ApiErrorHandler.routeOf(ctx)));
        return;
      }
      // Fall through to generic handling with root cause for better debugging.
      handlePreviewFailure(ctx, cause == null ? e : cause);
    } catch (DocumentService.UnavailableException e) {
      String msg = e.getMessage();
      ctx.status(503)
          .json(
              ApiErrorHandler.toResponse(
                  ApiErrorCode.INDEX_UNAVAILABLE,
                  msg == null || msg.isBlank() ? "Index unavailable" : ApiErrorHandler.sanitizeMessage(msg),
                  telemetry, ApiErrorHandler.routeOf(ctx)));
    } catch (Exception e) {
      handlePreviewFailure(ctx, e);
        }
      } finally {
        recordRequestMetricsBestEffort(ctx, startNs);
      }
    } catch (Exception ignored) {
      // best-effort
    }
  }

  private void recordRequestMetricsBestEffort(Context ctx, long startNs) {
    if (telemetry == null || ctx == null) {
      return;
    }
    try {
      if (apiCatalog == null) {
        return;
      }
      int status = ctx.res() == null ? 0 : ctx.res().getStatus();
      if (status <= 0) {
        return;
      }
      long durMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
      apiCatalog.requestMs.record(
          Math.max(0, durMs),
          new ApiRequestTags(
              "/api/preview",
              HttpMethod.GET,
              Integer.toString(status),
              HttpStatusClass.forStatus(status)));
    } catch (Exception ignored) {
      // best-effort
    }
  }

  private void handlePreviewFailure(Context ctx, Throwable t) {
    Exception ex = (t instanceof Exception e) ? e : new RuntimeException(t);
    ApiErrorCode code = ApiErrorHandler.resolve(ex);
    String message = "Preview failed";
    if (t != null && t.getMessage() != null && !t.getMessage().isBlank()) {
      message = "Preview failed: " + ApiErrorHandler.sanitizeMessage(t.getMessage());
    }

    int status =
        switch (code) {
          case INVALID_REQUEST -> 400;
          case NOT_FOUND -> 404;
          case TIMEOUT -> 504;
          case INDEX_UNAVAILABLE, SERVICE_UNAVAILABLE -> 503;
          default -> 500;
        };

    log.error("Preview failed: {}", message, t);
    ctx.status(status).json(ApiErrorHandler.toResponse(code, message, telemetry, ApiErrorHandler.routeOf(ctx)));
  }

  private static Integer parseIntOrNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static int clampNonNegative(Integer value, int fallback) {
    if (value == null) {
      return fallback;
    }
    return Math.max(0, value);
  }

  // ========== Chunk ID constants (P0.8) ==========

  /** Prefix for new opaque chunk IDs. */
  private static final String CHUNK_PREFIX = "chunk:";

  // ========== VDU Provenance ==========

  /**
   * Computes text provenance from the active extraction method plus VDU status.
   *
   * <p>Provenance values:
   * <ul>
   *   <li>{@code "vdu"} - VDU extraction completed, text is VDU-processed</li>
   *   <li>{@code "vdu_pending"} - VDU is pending, showing Tika output (may be garbage)</li>
   *   <li>{@code "vdu_processing"} - VDU is currently processing</li>
   *   <li>{@code "vdu_failed"} - VDU failed, showing Tika output (may be garbage)</li>
   *   <li>{@code "vdu_empty"} - VDU ran and found no text, showing the base extraction method's
   *       output (tempdoc 677: previously fell through silently, hiding that VDU ran)</li>
   *   <li>{@code "vdu_rejected"} - VDU ran but the tempdoc-677 abstention gate rejected the output
   *       as untrustworthy (or skipped the call on an illegible input); baseline content is
   *       retained and shown</li>
   *   <li>{@code "ocr"} - OCR baseline extraction</li>
   *   <li>{@code "tika"} - Normal Tika extraction (VDU not needed or not applicable)</li>
   * </ul>
   *
   * @param extractionMethod active extraction method from document metadata (may be null)
   * @param vduStatus the VDU status from metadata (may be null)
   * @return the computed text provenance string
   */
  private static String computeTextProvenance(Object extractionMethod, Object vduStatus) {
    // Determine provenance based on VDU status
    if (vduStatus instanceof String status) {
      return switch (status.toUpperCase(java.util.Locale.ROOT)) {
        case "COMPLETED" -> "vdu";
        case "PENDING" -> "vdu_pending";
        case "PROCESSING" -> "vdu_processing";
        case "FAILED" -> "vdu_failed";
        case "COMPLETED_EMPTY" -> "vdu_empty";
        case "REJECTED" -> "vdu_rejected";
        default -> baseTextProvenance(extractionMethod);
      };
    }

    // Default: normal Tika extraction (VDU not applicable or status unknown)
    return baseTextProvenance(extractionMethod);
  }

  private static String baseTextProvenance(Object extractionMethod) {
    if (extractionMethod instanceof String method
        && "OCR_TIKA".equalsIgnoreCase(method.trim())) {
      return "ocr";
    }
    if (extractionMethod instanceof String method
        && "VDU".equalsIgnoreCase(method.trim())) {
      return "vdu";
    }
    return "tika";
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parseVisualExtractionEvidence(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      Object parsed = MAPPER.readValue(json, Map.class);
      if (parsed instanceof Map<?, ?> map) {
        Map<String, Object> out = new HashMap<>();
        map.forEach((key, value) -> {
          if (key instanceof String s) {
            out.put(s, value);
          }
        });
        return out;
      }
    } catch (Exception ignored) {
      // Evidence is explanatory only; malformed old metadata should not break preview.
    }
    return null;
  }
}
