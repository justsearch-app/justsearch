package io.justsearch.ui.api;
import io.justsearch.core.context.EngineContext;

import static org.junit.jupiter.api.Assertions.*;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.justsearch.app.api.DocumentService;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PreviewController Tests")
class PreviewControllerTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private Javalin app;
  private int port;
  private HttpClient client;

  @BeforeEach
  void startServer() {
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  }

  @AfterEach
  void stopServer() {
    if (app != null) {
      app.stop();
      app = null;
    }
  }

  @Test
  @DisplayName("Missing docId returns 400 + NO_DOC_ID")
  void missingDocIdReturns400() throws Exception {
    DocumentService docService = stubDocumentsAlwaysNotFound();
    PreviewController controller = new PreviewController(docService, Duration.ofSeconds(2));

    app = Javalin.create(cfg -> { cfg.showJavalinBanner = false; cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper()); })
        .get("/api/preview", controller::handlePreview)
        .start(0);
    port = app.port();

    HttpResponse<String> resp = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/preview"))
            .timeout(Duration.ofSeconds(3))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertEquals(400, resp.statusCode());
    JsonNode json = MAPPER.readTree(resp.body());
    assertEquals("NO_DOC_ID", json.path("errorCode").asText());
  }

  @Test
  @DisplayName("Found doc returns 200 with paging fields")
  void foundDocReturns200WithPaging() throws Exception {
    String docId = "D:\\tests\\txt\\example.txt";
    String content = "hello world";
    DocumentService docService = stubDocumentsWithContent(docId, content);
    PreviewController controller = new PreviewController(docService, Duration.ofSeconds(2));

    app = Javalin.create(cfg -> { cfg.showJavalinBanner = false; cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper()); })
        .get("/api/preview", controller::handlePreview)
        .start(0);
    port = app.port();

    String url =
        "http://localhost:" + port + "/api/preview?docId=" + enc(docId) + "&offsetChars=0&maxChars=5";
    HttpResponse<String> resp = client.send(
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(3))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertEquals(200, resp.statusCode());
    JsonNode json = MAPPER.readTree(resp.body());
    assertEquals(docId, json.path("docId").asText());
    assertEquals(docId, json.path("requestedDocId").asText());
    assertFalse(json.path("normalizedFromChunk").asBoolean());
    assertEquals(0, json.path("offsetChars").asInt());
    assertEquals(5, json.path("maxChars").asInt());
    assertEquals("hello", json.path("content").asText());
    assertTrue(json.path("truncated").asBoolean(), "Should be truncated when maxChars < content length");
    assertEquals(5, json.path("nextOffsetChars").asInt());
    assertEquals(content.length(), json.path("totalChars").asInt());
    assertEquals("text/plain", json.path("mime").asText());
  }

  @Test
  @DisplayName("Found doc exposes persisted extraction provenance")
  void foundDocExposesExtractionProvenance() throws Exception {
    String docId = "D:\\tests\\report.pdf";
    DocumentService docService =
        new DocumentService() {
          @Override
          public CompletableFuture<DocumentRecord> fetch(String ignored, EngineContext engineContext) {
            return CompletableFuture.completedFuture(null);
          }

          @Override
          public CompletableFuture<DocumentSlice> fetchSlice(
              String ignored, int offsetChars, int maxChars, EngineContext engineContext) {
            return CompletableFuture.completedFuture(
                new DocumentSlice(
                    docId,
                    "complete text",
                    Map.of("mime", "application/pdf", "content_sha256", "b".repeat(64)),
                    true,
                    false,
                    13,
                    13,
                    "SUCCESS_FULL",
                    false,
                    "policy-v3",
                    "tika-3.2",
                    "a".repeat(64),
                    null));
          }
        };
    PreviewController controller = new PreviewController(docService, Duration.ofSeconds(2));

    app =
        Javalin.create(
                cfg -> {
                  cfg.showJavalinBanner = false;
                  cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
                })
            .get("/api/preview", controller::handlePreview)
            .start(0);
    port = app.port();

    HttpResponse<String> resp =
        client.send(
            HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/api/preview?docId=" + enc(docId)))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(200, resp.statusCode());
    JsonNode json = MAPPER.readTree(resp.body());
    assertEquals(13, json.path("totalChars").asInt());
    assertEquals("SUCCESS_FULL", json.path("extractionStatus").asText());
    assertFalse(json.path("contentTruncated").asBoolean());
    assertEquals("policy-v3", json.path("extractionPolicyId").asText());
    assertEquals("tika-3.2", json.path("extractionParserId").asText());
    assertEquals("a".repeat(64), json.path("sourceSha256").asText());
    assertEquals("b".repeat(64), json.path("contentSha256").asText());
  }

  @Test
  @DisplayName("Eval document-ID route returns the Worker-backed parent-ID page")
  void evalDocumentIdsReturnsWorkerPage() throws Exception {
    DocumentService docService = documentIdService();
    PreviewController controller = new PreviewController(docService, Duration.ofSeconds(2));

    app =
        Javalin.create(
                cfg -> {
                  cfg.showJavalinBanner = false;
                  cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
                })
            .post(PreviewController.DOCUMENT_IDS_PATH, controller::handleListDocumentIds)
            .start(0);
    port = app.port();

    HttpResponse<String> resp = postDocumentIds("{\"offset\":0,\"limit\":50000}");

    assertEquals(200, resp.statusCode());
    JsonNode json = MAPPER.readTree(resp.body());
    assertEquals(2, json.path("docIds").size());
    assertEquals("C:/root/nested/b.txt", json.path("docIds").get(1).asText());
    assertEquals(2, json.path("totalCount").asLong());
    assertEquals(7, json.path("tookMs").asLong());
  }

  @Test
  @DisplayName("Eval document-ID route fails closed on malformed or over-ceiling pagination")
  void evalDocumentIdsRejectsInvalidPagination() throws Exception {
    PreviewController controller =
        new PreviewController(documentIdService(), Duration.ofSeconds(2));

    app =
        Javalin.create(
                cfg -> {
                  cfg.showJavalinBanner = false;
                  cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
                })
            .post(PreviewController.DOCUMENT_IDS_PATH, controller::handleListDocumentIds)
            .start(0);
    port = app.port();

    for (String body :
        new String[] {
          "{not-json",
          "{}",
          "{\"offset\":0,\"limit\":1,\"extra\":true}",
          "{\"offset\":-1,\"limit\":1}",
          "{\"offset\":1,\"limit\":1}",
          "{\"offset\":0,\"limit\":0}",
          "{\"offset\":0,\"limit\":50001}",
          "{\"offset\":49999,\"limit\":2}",
          "{\"offset\":0.5,\"limit\":1}"
        }) {
      HttpResponse<String> resp = postDocumentIds(body);
      assertEquals(400, resp.statusCode(), body + " -> " + resp.body());
      assertEquals("INVALID_REQUEST", MAPPER.readTree(resp.body()).path("errorCode").asText());
    }
  }

  private HttpResponse<String> postDocumentIds(String body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + PreviewController.DOCUMENT_IDS_PATH))
            .timeout(Duration.ofSeconds(3))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static DocumentService documentIdService() {
    return new DocumentService() {
      @Override
      public CompletableFuture<DocumentRecord> fetch(String docId, EngineContext engineContext) {
        return CompletableFuture.completedFuture(null);
      }

      @Override
      public CompletableFuture<DocumentIdPage> listAllDocumentIds(
          int offset, int limit, EngineContext engineContext) {
        return CompletableFuture.completedFuture(
            new DocumentIdPage(
                java.util.List.of("C:/root/a.txt", "C:/root/nested/b.txt"), 2, 7));
      }
    };
  }

  @Test
  @DisplayName("Real file path containing #chunk_ is NOT truncated (P0.8 regression)")
  void realPathWithHashChunkIsNotTruncated() throws Exception {
    // Regression test: a real file whose path contains "#chunk_" should NOT be truncated.
    // Prior to P0.8 fix, the #chunk_ substring would cause incorrect normalization.
    String realPath = "D:\\tests\\notes#chunk_discussion.txt";
    DocumentService docService = stubDocumentsWithContent(realPath, "real file content");
    PreviewController controller = new PreviewController(docService, Duration.ofSeconds(2));

    app = Javalin.create(cfg -> { cfg.showJavalinBanner = false; cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper()); })
        .get("/api/preview", controller::handlePreview)
        .start(0);
    port = app.port();

    String url = "http://localhost:" + port + "/api/preview?docId=" + enc(realPath);
    HttpResponse<String> resp = client.send(
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(3))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertEquals(200, resp.statusCode());
    JsonNode json = MAPPER.readTree(resp.body());
    // The docId should be passed through unchanged (no truncation at #chunk_)
    assertEquals(realPath, json.path("docId").asText());
    assertEquals(realPath, json.path("requestedDocId").asText());
    assertFalse(json.path("normalizedFromChunk").asBoolean(), "No normalization should occur");
    assertEquals("real file content", json.path("content").asText());
  }

  @Test
  @DisplayName("Opaque chunk ID (chunk:UUID) is passed through unchanged (will 404)")
  void opaqueChunkIdPassedThroughUnchanged() throws Exception {
    // Opaque chunk IDs are not normalized; they're passed through as-is.
    // Since chunks are excluded from search by default, these should rarely appear,
    // but if they do, they'll 404 (no document with that ID exists for preview).
    String opaqueChunkId = "chunk:abc-123-456";
    DocumentService docService = stubDocumentsAlwaysNotFound();
    PreviewController controller = new PreviewController(docService, Duration.ofSeconds(2));

    app = Javalin.create(cfg -> { cfg.showJavalinBanner = false; cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper()); })
        .get("/api/preview", controller::handlePreview)
        .start(0);
    port = app.port();

    String url = "http://localhost:" + port + "/api/preview?docId=" + enc(opaqueChunkId);
    HttpResponse<String> resp = client.send(
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(3))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());

    // Expected: 404 because no document exists with this chunk ID
    assertEquals(404, resp.statusCode());
    JsonNode json = MAPPER.readTree(resp.body());
    assertEquals("NOT_FOUND", json.path("errorCode").asText());
    // docId in response should be the opaque ID unchanged
    assertEquals(opaqueChunkId, json.path("docId").asText());
  }

  @Test
  @DisplayName("Not found returns 404 + NOT_FOUND")
  void notFoundReturns404() throws Exception {
    DocumentService docService = stubDocumentsAlwaysNotFound();
    PreviewController controller = new PreviewController(docService, Duration.ofSeconds(2));

    app = Javalin.create(cfg -> { cfg.showJavalinBanner = false; cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper()); })
        .get("/api/preview", controller::handlePreview)
        .start(0);
    port = app.port();

    String url = "http://localhost:" + port + "/api/preview?docId=" + enc("missing");
    HttpResponse<String> resp = client.send(
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(3))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertEquals(404, resp.statusCode());
    JsonNode json = MAPPER.readTree(resp.body());
    assertEquals("NOT_FOUND", json.path("errorCode").asText());
  }

  @Test
  @DisplayName("UnavailableException returns 503 + INDEX_UNAVAILABLE")
  void unavailableReturns503() throws Exception {
    DocumentService docService = stubDocumentsUnavailable();
    PreviewController controller = new PreviewController(docService, Duration.ofSeconds(2));

    app = Javalin.create(cfg -> { cfg.showJavalinBanner = false; cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper()); })
        .get("/api/preview", controller::handlePreview)
        .start(0);
    port = app.port();

    String url = "http://localhost:" + port + "/api/preview?docId=" + enc("any");
    HttpResponse<String> resp = client.send(
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(3))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertEquals(503, resp.statusCode());
    JsonNode json = MAPPER.readTree(resp.body());
    assertEquals("INDEX_UNAVAILABLE", json.path("errorCode").asText());
  }

  private static String enc(String raw) {
    return URLEncoder.encode(raw, StandardCharsets.UTF_8);
  }

  private static DocumentService stubDocumentsAlwaysNotFound() {
    return (docId, engineContext) -> CompletableFuture.completedFuture(null);
  }

  private static DocumentService stubDocumentsUnavailable() {
    return new DocumentService() {
      @Override
      public CompletableFuture<DocumentService.DocumentRecord> fetch(String docId, EngineContext engineContext) {
        return CompletableFuture.failedFuture(new DocumentService.UnavailableException("index unavailable"));
      }
    };
  }

  private static DocumentService stubDocumentsWithContent(String expectedDocId, String fullContent) {
    return stubDocumentsWithContentAndMetadata(expectedDocId, fullContent, Map.of("mime", "text/plain"));
  }

  private static DocumentService stubDocumentsWithContentAndMetadata(
      String expectedDocId, String fullContent, Map<String, Object> metadata) {
    return new DocumentService() {
      @Override
      public CompletableFuture<DocumentService.DocumentRecord> fetch(String docId, EngineContext engineContext) {
        if (!expectedDocId.equals(docId)) {
          return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.completedFuture(
            new DocumentService.DocumentRecord(docId, fullContent, metadata));
      }
    };
  }

  // ========== VDU Provenance Tests ==========

  @Test
  @DisplayName("VDU COMPLETED status returns textProvenance='vdu'")
  void vduCompletedReturnsVduProvenance() throws Exception {
    String docId = "D:\\docs\\scanned.pdf";
    Map<String, Object> metadata = Map.of(
        "mime", "application/pdf",
        "vdu_status", "COMPLETED",
        "vdu_processed", "true",
        "vdu_page_count", "3"
    );
    DocumentService docService = stubDocumentsWithContentAndMetadata(docId, "VDU extracted text", metadata);
    PreviewController controller = new PreviewController(docService, Duration.ofSeconds(2));

    app = Javalin.create(cfg -> { cfg.showJavalinBanner = false; cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper()); })
        .get("/api/preview", controller::handlePreview)
        .start(0);
    port = app.port();

    String url = "http://localhost:" + port + "/api/preview?docId=" + enc(docId);
    HttpResponse<String> resp = client.send(
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(3))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertEquals(200, resp.statusCode());
    JsonNode json = MAPPER.readTree(resp.body());
    assertEquals("vdu", json.path("textProvenance").asText());
    assertEquals("COMPLETED", json.path("vduStatus").asText());
    assertTrue(json.path("vduProcessed").asBoolean());
    assertEquals(3, json.path("vduPageCount").asInt());
  }

  @Test
  @DisplayName("VDU PENDING status returns textProvenance='vdu_pending'")
  void vduPendingReturnsVduPendingProvenance() throws Exception {
    String docId = "D:\\docs\\garbage.pdf";
    Map<String, Object> metadata = Map.of(
        "mime", "application/pdf",
        "vdu_status", "PENDING"
    );
    DocumentService docService = stubDocumentsWithContentAndMetadata(docId, "Garbage Tika text", metadata);
    PreviewController controller = new PreviewController(docService, Duration.ofSeconds(2));

    app = Javalin.create(cfg -> { cfg.showJavalinBanner = false; cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper()); })
        .get("/api/preview", controller::handlePreview)
        .start(0);
    port = app.port();

    String url = "http://localhost:" + port + "/api/preview?docId=" + enc(docId);
    HttpResponse<String> resp = client.send(
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(3))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertEquals(200, resp.statusCode());
    JsonNode json = MAPPER.readTree(resp.body());
    assertEquals("vdu_pending", json.path("textProvenance").asText());
    assertEquals("PENDING", json.path("vduStatus").asText());
    assertFalse(json.path("vduProcessed").asBoolean());
  }

  @Test
  @DisplayName("VDU FAILED status returns textProvenance='vdu_failed'")
  void vduFailedReturnsVduFailedProvenance() throws Exception {
    String docId = "D:\\docs\\corrupted.pdf";
    Map<String, Object> metadata = Map.of(
        "mime", "application/pdf",
        "vdu_status", "FAILED",
        "vdu_processed", "true"
    );
    DocumentService docService = stubDocumentsWithContentAndMetadata(docId, "Garbage text", metadata);
    PreviewController controller = new PreviewController(docService, Duration.ofSeconds(2));

    app = Javalin.create(cfg -> { cfg.showJavalinBanner = false; cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper()); })
        .get("/api/preview", controller::handlePreview)
        .start(0);
    port = app.port();

    String url = "http://localhost:" + port + "/api/preview?docId=" + enc(docId);
    HttpResponse<String> resp = client.send(
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(3))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertEquals(200, resp.statusCode());
    JsonNode json = MAPPER.readTree(resp.body());
    assertEquals("vdu_failed", json.path("textProvenance").asText());
    assertEquals("FAILED", json.path("vduStatus").asText());
    assertTrue(json.path("vduProcessed").asBoolean());
  }

  @Test
  @DisplayName("OCR extraction method returns textProvenance='ocr'")
  void ocrExtractionMethodReturnsOcrProvenance() throws Exception {
    String docId = "D:\\docs\\ocr.pdf";
    Map<String, Object> metadata = Map.of(
        "mime", "application/pdf",
        "extraction_method", "OCR_TIKA",
        "vdu_status", "NOT_NEEDED"
    );
    DocumentService docService = stubDocumentsWithContentAndMetadata(docId, "OCR text", metadata);
    PreviewController controller = new PreviewController(docService, Duration.ofSeconds(2));

    app = Javalin.create(cfg -> { cfg.showJavalinBanner = false; cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper()); })
        .get("/api/preview", controller::handlePreview)
        .start(0);
    port = app.port();

    String url = "http://localhost:" + port + "/api/preview?docId=" + enc(docId);
    HttpResponse<String> resp = client.send(
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(3))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertEquals(200, resp.statusCode());
    JsonNode json = MAPPER.readTree(resp.body());
    assertEquals("ocr", json.path("textProvenance").asText());
    assertEquals("NOT_NEEDED", json.path("vduStatus").asText());
  }

  @Test
  @DisplayName("Preview returns parsed visual extraction evidence")
  void previewReturnsParsedVisualExtractionEvidence() throws Exception {
    String docId = "D:\\docs\\ocr-evidence.pdf";
    Map<String, Object> metadata = Map.of(
        "mime", "application/pdf",
        "extraction_method", "OCR_TIKA",
        "vdu_status", "NOT_NEEDED",
        "visual_extraction_evidence",
            "{\"schemaVersion\":1,\"textQualityScore\":0.91,\"ocrLanguage\":\"eng\","
                + "\"ocrMeanConfidence\":0.82,\"ocrLowConfidenceWordCount\":1,"
                + "\"ocrWordCount\":12,\"contentTruncated\":true,"
                + "\"ocrFallbackRoute\":\"direct_tesseract\",\"ocrSkipReason\":\"timeout\","
                + "\"route\":\"ocr_full\"}"
    );
    DocumentService docService = stubDocumentsWithContentAndMetadata(docId, "OCR text", metadata);
    PreviewController controller = new PreviewController(docService, Duration.ofSeconds(2));

    app = Javalin.create(cfg -> { cfg.showJavalinBanner = false; cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper()); })
        .get("/api/preview", controller::handlePreview)
        .start(0);
    port = app.port();

    String url = "http://localhost:" + port + "/api/preview?docId=" + enc(docId);
    HttpResponse<String> resp = client.send(
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(3))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertEquals(200, resp.statusCode());
    JsonNode json = MAPPER.readTree(resp.body());
    assertEquals("eng", json.path("visualExtractionEvidence").path("ocrLanguage").asText());
    assertEquals(0.82d, json.path("visualExtractionEvidence").path("ocrMeanConfidence").asDouble());
    assertEquals(1, json.path("visualExtractionEvidence").path("ocrLowConfidenceWordCount").asInt());
    assertEquals(12, json.path("visualExtractionEvidence").path("ocrWordCount").asInt());
    assertTrue(json.path("visualExtractionEvidence").path("contentTruncated").asBoolean());
    assertEquals("direct_tesseract", json.path("visualExtractionEvidence").path("ocrFallbackRoute").asText());
    assertEquals("timeout", json.path("visualExtractionEvidence").path("ocrSkipReason").asText());
    assertEquals("ocr_full", json.path("visualExtractionEvidence").path("route").asText());
  }

  @Test
  @DisplayName("VDU COMPLETED_EMPTY status returns textProvenance='vdu_empty' (tempdoc 677: no longer falls through to base method)")
  void vduCompletedEmptyReturnsVduEmptyProvenance() throws Exception {
    String docId = "D:\\docs\\empty-vdu.pdf";
    Map<String, Object> metadata = Map.of(
        "mime", "application/pdf",
        "extraction_method", "OCR_TIKA",
        "vdu_status", "COMPLETED_EMPTY",
        "vdu_processed", "true"
    );
    DocumentService docService = stubDocumentsWithContentAndMetadata(docId, "OCR baseline text", metadata);
    PreviewController controller = new PreviewController(docService, Duration.ofSeconds(2));

    app = Javalin.create(cfg -> { cfg.showJavalinBanner = false; cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper()); })
        .get("/api/preview", controller::handlePreview)
        .start(0);
    port = app.port();

    String url = "http://localhost:" + port + "/api/preview?docId=" + enc(docId);
    HttpResponse<String> resp = client.send(
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(3))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertEquals(200, resp.statusCode());
    JsonNode json = MAPPER.readTree(resp.body());
    assertEquals("vdu_empty", json.path("textProvenance").asText());
    assertEquals("COMPLETED_EMPTY", json.path("vduStatus").asText());
    assertTrue(json.path("vduProcessed").asBoolean());
  }

  @Test
  @DisplayName("VDU REJECTED status returns textProvenance='vdu_rejected' (tempdoc 677 abstention gate)")
  void vduRejectedReturnsVduRejectedProvenance() throws Exception {
    String docId = "D:\\docs\\rejected-vdu.pdf";
    Map<String, Object> metadata = Map.of(
        "mime", "application/pdf",
        "extraction_method", "OCR_TIKA",
        "vdu_status", "REJECTED",
        "vdu_processed", "true",
        "vdu_enrichment", "{\"gate\":{\"stage\":\"logprob\",\"meanLogprob\":-0.6}}"
    );
    DocumentService docService = stubDocumentsWithContentAndMetadata(docId, "OCR baseline text", metadata);
    PreviewController controller = new PreviewController(docService, Duration.ofSeconds(2));

    app = Javalin.create(cfg -> { cfg.showJavalinBanner = false; cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper()); })
        .get("/api/preview", controller::handlePreview)
        .start(0);
    port = app.port();

    String url = "http://localhost:" + port + "/api/preview?docId=" + enc(docId);
    HttpResponse<String> resp = client.send(
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(3))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertEquals(200, resp.statusCode());
    JsonNode json = MAPPER.readTree(resp.body());
    assertEquals("vdu_rejected", json.path("textProvenance").asText());
    assertEquals("REJECTED", json.path("vduStatus").asText());
    assertTrue(json.path("vduProcessed").asBoolean());
    assertEquals(
        "{\"gate\":{\"stage\":\"logprob\",\"meanLogprob\":-0.6}}",
        json.path("vduEnrichment").asText());
  }

  @Test
  @DisplayName("VDU NOT_NEEDED status returns textProvenance='tika'")
  void vduNotNeededReturnsTikaProvenance() throws Exception {
    String docId = "D:\\docs\\clean.pdf";
    Map<String, Object> metadata = Map.of(
        "mime", "application/pdf",
        "vdu_status", "NOT_NEEDED"
    );
    DocumentService docService = stubDocumentsWithContentAndMetadata(docId, "Clean Tika text", metadata);
    PreviewController controller = new PreviewController(docService, Duration.ofSeconds(2));

    app = Javalin.create(cfg -> { cfg.showJavalinBanner = false; cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper()); })
        .get("/api/preview", controller::handlePreview)
        .start(0);
    port = app.port();

    String url = "http://localhost:" + port + "/api/preview?docId=" + enc(docId);
    HttpResponse<String> resp = client.send(
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(3))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertEquals(200, resp.statusCode());
    JsonNode json = MAPPER.readTree(resp.body());
    assertEquals("tika", json.path("textProvenance").asText());
    assertEquals("NOT_NEEDED", json.path("vduStatus").asText());
  }

  @Test
  @DisplayName("No VDU status returns textProvenance='tika' (default)")
  void noVduStatusReturnsTikaProvenance() throws Exception {
    String docId = "D:\\docs\\textfile.txt";
    Map<String, Object> metadata = Map.of("mime", "text/plain");
    DocumentService docService = stubDocumentsWithContentAndMetadata(docId, "Plain text", metadata);
    PreviewController controller = new PreviewController(docService, Duration.ofSeconds(2));

    app = Javalin.create(cfg -> { cfg.showJavalinBanner = false; cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper()); })
        .get("/api/preview", controller::handlePreview)
        .start(0);
    port = app.port();

    String url = "http://localhost:" + port + "/api/preview?docId=" + enc(docId);
    HttpResponse<String> resp = client.send(
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(3))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertEquals(200, resp.statusCode());
    JsonNode json = MAPPER.readTree(resp.body());
    assertEquals("tika", json.path("textProvenance").asText());
    assertTrue(json.path("vduStatus").isNull());
  }

  @Test
  @DisplayName("VDU PROCESSING status returns textProvenance='vdu_processing'")
  void vduProcessingReturnsVduProcessingProvenance() throws Exception {
    String docId = "D:\\docs\\processing.pdf";
    Map<String, Object> metadata = Map.of(
        "mime", "application/pdf",
        "vdu_status", "PROCESSING"
    );
    DocumentService docService = stubDocumentsWithContentAndMetadata(docId, "Temp content", metadata);
    PreviewController controller = new PreviewController(docService, Duration.ofSeconds(2));

    app = Javalin.create(cfg -> { cfg.showJavalinBanner = false; cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper()); })
        .get("/api/preview", controller::handlePreview)
        .start(0);
    port = app.port();

    String url = "http://localhost:" + port + "/api/preview?docId=" + enc(docId);
    HttpResponse<String> resp = client.send(
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(3))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertEquals(200, resp.statusCode());
    JsonNode json = MAPPER.readTree(resp.body());
    assertEquals("vdu_processing", json.path("textProvenance").asText());
    assertEquals("PROCESSING", json.path("vduStatus").asText());
  }
}
