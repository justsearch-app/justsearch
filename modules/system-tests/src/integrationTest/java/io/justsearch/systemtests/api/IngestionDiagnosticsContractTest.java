package io.justsearch.systemtests.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.systemtests.harness.IsolatedBackendFixture;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Contract tests for the privacy-safe ingestion diagnostic surface (tempdoc 410 §12) plus
 * the scoped reverse-path-hash resolver (ADR-0028 / tempdoc 419 T5). Runs against an
 * isolated lite-mode backend so assertions never compete with shared dev state.
 *
 * <p>Pinned guarantees:
 *
 * <ol>
 *   <li>{@code GET /api/diagnostics/ingestion/recent} never echoes raw paths or path-derived
 *       substrings — paths are SHA-256 hashes only.
 *   <li>The honesty fields surfaced in {@code /api/knowledge/search} projections
 *       ({@code extraction_status}, {@code extraction_parser_id}, etc.) are queryable
 *       end-to-end (catalog drift catcher).
 *   <li>{@code POST /api/library/resolve-hash} resolves ledger-recorded hashes back to
 *       their original paths within retention.
 *   <li>The diagnostic export still does not leak paths even though the resolver IS wired
 *       on the same backend (runtime complement to the ArchUnit pin
 *       {@code LibraryResolveHashOnlyCallerPin}).
 * </ol>
 */
@DisplayName("Ingestion Diagnostics Contract Tests")
@Timeout(value = 2, unit = TimeUnit.MINUTES)
class IngestionDiagnosticsContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final IsolatedBackendFixture BACKEND = new IsolatedBackendFixture();
  private static final HttpClient CLIENT =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration SEARCHABLE_TIMEOUT = Duration.ofSeconds(45);
  private static final Duration POLL_INTERVAL = Duration.ofMillis(250);

  @BeforeAll
  static void startBackend() throws Exception {
    BACKEND.start();
  }

  @AfterAll
  static void stopBackend() {
    BACKEND.stop();
  }

  // ------------------------------------------------------------------------------
  // T6.3 — Privacy invariant + honesty fields
  // ------------------------------------------------------------------------------

  @Test
  @DisplayName("Recent events never expose path-derivable content after ingest")
  void recentEventsContainNoPathDerivableContentAfterIngest() throws Exception {
    String dirMarker = "secretdir" + uniqueSuffix();
    String fileMarker = "secretfile" + uniqueSuffix();
    Path corpus = Files.createDirectory(BACKEND.dataDir().resolve(dirMarker));
    Path docPath = corpus.resolve(fileMarker + "-doc.txt");
    Path dataPath = corpus.resolve(fileMarker + "-data.md");
    Files.writeString(docPath, "alpha beta gamma " + fileMarker);
    Files.writeString(dataPath, "delta epsilon zeta " + fileMarker);

    ingestSync(corpus.toAbsolutePath().toString());
    awaitLedgerHasOutcomeForSize(Files.size(docPath), SEARCHABLE_TIMEOUT);
    awaitLedgerHasOutcomeForSize(Files.size(dataPath), SEARCHABLE_TIMEOUT);

    String body = httpGet("/api/diagnostics/ingestion/recent?limit=100");
    String lower = body.toLowerCase();
    String userName = System.getProperty("user.name", "");
    List<String> forbidden =
        List.of(
            dirMarker,
            fileMarker,
            ".txt",
            ".md",
            "/Users/",
            "/home/",
            "C:\\Users",
            "C:/Users");
    for (String token : forbidden) {
      assertFalse(
          lower.contains(token.toLowerCase()),
          "Diagnostic export leaked '" + token + "' — privacy contract violation");
    }
    if (!userName.isBlank()) {
      assertFalse(
          lower.contains(userName.toLowerCase()),
          "Diagnostic export leaked OS username '" + userName + "'");
    }

    Pattern sha256 = Pattern.compile("^[0-9a-f]{64}$");
    JsonNode events = MAPPER.readTree(body).path("events");
    int checked = 0;
    for (JsonNode event : events) {
      String hash = event.path("pathHash").asText("");
      assertTrue(
          sha256.matcher(hash).matches(),
          "pathHash must be 64-char SHA-256 hex; got '" + hash + "'");
      checked++;
    }
    assertTrue(
        checked >= 2, "Expected at least 2 ledger events from the ingested corpus, got " + checked);
  }

  @Test
  @DisplayName("Search projection exposes extraction provenance fields end-to-end")
  void searchProjectionExposesExtractionProvenanceFields() throws Exception {
    String marker = "honesty" + uniqueSuffix();
    Path corpus = Files.createDirectory(BACKEND.dataDir().resolve(marker + "-corpus"));
    Path docPath = corpus.resolve("doc.txt");
    Files.writeString(docPath, "honesty marker " + marker);
    ingestSync(corpus.toAbsolutePath().toString());
    awaitLedgerHasOutcomeForSize(Files.size(docPath), SEARCHABLE_TIMEOUT);
    // First search after backend boot loads the embedding ONNX model on the worker.
    // Loading exceeds the 5s gRPC search deadline, which trips the circuit breaker;
    // retry until the model is warm and search returns results.
    awaitSearchHit(marker, SEARCHABLE_TIMEOUT);

    String requestBody =
        MAPPER.writeValueAsString(
            Map.of(
                "query",
                marker,
                "limit",
                5,
                "projection",
                List.of(
                    "extraction_status",
                    "extraction_reason_code",
                    "extraction_policy_id",
                    "extraction_parser_id",
                    "content_truncated",
                    "parser_warnings_count")));
    JsonNode resp = MAPPER.readTree(httpPost("/api/knowledge/search", requestBody));
    JsonNode hits = resp.path("results");
    assertTrue(hits.isArray() && !hits.isEmpty(), "Expected at least one search hit for marker");
    JsonNode fields = hits.get(0).path("fields");
    assertFalse(
        fields.path("extraction_status").isMissingNode(),
        "extraction_status must be queryable (catalog drift catcher)");
    assertEquals(
        "SUCCESS_FULL",
        fields.path("extraction_status").asText(""),
        "extraction_status for a clean text doc must be SUCCESS_FULL");
    assertTrue(
        fields.path("extraction_parser_id").asText("").contains("tika-policy"),
        "extraction_parser_id must surface the production parser id; got '"
            + fields.path("extraction_parser_id").asText("") + "'");
  }

  // ------------------------------------------------------------------------------
  // T6.4 — Resolver wire-level
  // ------------------------------------------------------------------------------

  @Test
  @DisplayName("resolve-hash returns the path for a freshly ingested file")
  void resolveHashReturnsPathForFreshlyIngestedFile() throws Exception {
    String marker = "resolve" + uniqueSuffix();
    Path corpus = Files.createDirectory(BACKEND.dataDir().resolve(marker + "-corpus"));
    Path probeFile = corpus.resolve("probe.txt");
    Files.writeString(probeFile, "content for resolver probe " + marker);

    ingestSync(corpus.toAbsolutePath().toString());
    long expectedSize = Files.size(probeFile);
    awaitLedgerHasOutcomeForSize(expectedSize, SEARCHABLE_TIMEOUT);
    JsonNode events =
        MAPPER.readTree(httpGet("/api/diagnostics/ingestion/recent?limit=50")).path("events");
    String foundHash = null;
    for (JsonNode event : events) {
      if (event.path("sourceSizeBytes").asLong(-1L) == expectedSize
          && "SUCCESS_FULL".equals(event.path("outcomeClass").asText(""))) {
        foundHash = event.path("pathHash").asText("");
        break;
      }
    }
    assertNotNull(
        foundHash,
        "Expected a ledger event with sourceSizeBytes=" + expectedSize + " and outcomeClass=SUCCESS_FULL");

    JsonNode resolved =
        MAPPER.readTree(
            httpPost(
                "/api/library/resolve-hash",
                MAPPER.writeValueAsString(Map.of("pathHash", foundHash))));
    assertTrue(
        resolved.path("found").asBoolean(false),
        "Resolver must return found=true for a freshly admitted file; body: " + resolved);
    String returnedPath = resolved.path("path").asText("");
    assertTrue(
        returnedPath.toLowerCase().endsWith("probe.txt"),
        "Resolver must return a path ending in our probe filename; got '" + returnedPath + "'");
  }

  @Test
  @DisplayName("Diagnostic export does not leak paths even with the resolver wired")
  void diagnosticExportDoesNotLeakPathsEvenWithResolverWired() throws Exception {
    String marker = "leak" + uniqueSuffix();
    Path corpus = Files.createDirectory(BACKEND.dataDir().resolve(marker + "-corpus"));
    Path docPath = corpus.resolve("a.txt");
    Files.writeString(docPath, "alpha " + marker);
    ingestSync(corpus.toAbsolutePath().toString());
    awaitLedgerHasOutcomeForSize(Files.size(docPath), SEARCHABLE_TIMEOUT);

    JsonNode events =
        MAPPER.readTree(httpGet("/api/diagnostics/ingestion/recent?limit=10")).path("events");
    assertTrue(!events.isEmpty(), "Ledger must contain events for the ingested corpus");

    String body =
        httpGet("/api/diagnostics/ingestion/recent?limit=50")
            + httpGet("/api/diagnostics/ingestion/summary?since=0");
    String lower = body.toLowerCase();
    List<String> forbidden =
        List.of("a.txt", marker + "-corpus", "C:\\Users", "C:/Users", "/Users/", "/home/");
    for (String token : forbidden) {
      assertFalse(
          lower.contains(token.toLowerCase()),
          "Diagnostic body leaked '" + token + "' (resolver-wired backend regression)");
    }
  }

  // ------------------------------------------------------------------------------
  // Helpers
  // ------------------------------------------------------------------------------

  private static String uniqueSuffix() {
    return UUID.randomUUID().toString().substring(0, 8);
  }

  private static JsonNode ingestSync(String absPath) throws Exception {
    String body = MAPPER.writeValueAsString(Map.of("paths", List.of(absPath)));
    String response = httpPost("/api/knowledge/ingest", body);
    JsonNode parsed = MAPPER.readTree(response);
    if (!parsed.path("success").asBoolean()
        || parsed.path("structuredData").path("operationKey").asText("").isBlank()) {
      throw new IllegalStateException("Ingest operation was not accepted: " + response);
    }
    return parsed;
  }

  /**
   * Waits for a SUCCESS_FULL ledger event whose {@code sourceSizeBytes} matches the given
   * value. Avoids hitting {@code /api/knowledge/search}, which depends on the embedding
   * model being warm and thus races with the worker's first-search ONNX load.
   */
  private static void awaitLedgerHasOutcomeForSize(long sizeBytes, Duration timeout)
      throws Exception {
    long deadline = System.currentTimeMillis() + timeout.toMillis();
    Throwable lastError = null;
    while (System.currentTimeMillis() < deadline) {
      try {
        JsonNode events =
            MAPPER.readTree(httpGet("/api/diagnostics/ingestion/recent?limit=200"))
                .path("events");
        for (JsonNode event : events) {
          if (event.path("sourceSizeBytes").asLong(-1L) == sizeBytes
              && "SUCCESS_FULL".equals(event.path("outcomeClass").asText(""))) {
            return;
          }
        }
      } catch (Exception e) {
        lastError = e;
      }
      Thread.sleep(POLL_INTERVAL.toMillis());
    }
    String hint = lastError == null ? "" : " (last error: " + lastError + ")";
    throw new IllegalStateException(
        "Ledger never reported SUCCESS_FULL for sourceSizeBytes=" + sizeBytes + " within "
            + timeout.toMillis() + "ms" + hint);
  }

  /**
   * Polls {@code /api/knowledge/search} for the marker until at least one hit is returned.
   * Tolerates 503 (circuit breaker open) and 504 (worker DEADLINE_EXCEEDED) responses while
   * the embedding ONNX session warms up — these reliably resolve once the model is loaded.
   */
  private static JsonNode awaitSearchHit(String marker, Duration timeout) throws Exception {
    long deadline = System.currentTimeMillis() + timeout.toMillis();
    String requestBody = MAPPER.writeValueAsString(Map.of("query", marker, "limit", 5));
    String lastBody = "<no response>";
    Throwable lastError = null;
    while (System.currentTimeMillis() < deadline) {
      HttpRequest req =
          HttpRequest.newBuilder(
                  URI.create("http://localhost:" + BACKEND.port() + "/api/knowledge/search"))
              .timeout(REQUEST_TIMEOUT)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(requestBody))
              .build();
      try {
        HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        lastBody = resp.body();
        if (resp.statusCode() == 200) {
          JsonNode hits = MAPPER.readTree(lastBody).path("results");
          if (hits.isArray() && !hits.isEmpty()) {
            return hits;
          }
        }
      } catch (java.io.IOException ioe) {
        // First search after backend boot races with the embedding ONNX session load:
        // the worker exceeds its 5s gRPC deadline, HttpTimeoutException (extends
        // IOException) bubbles up, and the next-3-failures circuit breaker opens. Both
        // resolve once the model warms; keep polling.
        lastError = ioe;
      }
      Thread.sleep(POLL_INTERVAL.toMillis());
    }
    String hint = lastError == null ? "" : " (last error: " + lastError + ")";
    throw new IllegalStateException(
        "/api/knowledge/search returned no hits for marker '" + marker + "' within "
            + timeout.toMillis() + "ms. Last body: " + lastBody + hint);
  }

  private static String httpGet(String path) throws Exception {
    HttpRequest req =
        HttpRequest.newBuilder(URI.create("http://localhost:" + BACKEND.port() + path))
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build();
    HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() != 200) {
      throw new IllegalStateException(
          "GET " + path + " returned " + resp.statusCode() + ": " + resp.body());
    }
    return resp.body();
  }

  private static String httpPost(String path, String jsonBody) throws Exception {
    HttpRequest req =
        HttpRequest.newBuilder(URI.create("http://localhost:" + BACKEND.port() + path))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build();
    HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() != 200) {
      throw new IllegalStateException(
          "POST " + path + " returned " + resp.statusCode() + ": " + resp.body());
    }
    return resp.body();
  }
}
