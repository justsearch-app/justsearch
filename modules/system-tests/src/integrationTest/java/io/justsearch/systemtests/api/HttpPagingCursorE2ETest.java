package io.justsearch.systemtests.api;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.systemtests.harness.IsolatedBackendFixture;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@DisplayName("HTTP Paging Cursor E2E Tests")
final class HttpPagingCursorE2ETest {
  private static final Logger log = LoggerFactory.getLogger(HttpPagingCursorE2ETest.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static HttpClient client;
  private static int port;
  private static final IsolatedBackendFixture BACKEND = new IsolatedBackendFixture();

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration INDEXING_TIMEOUT = Duration.ofSeconds(30);

  @TempDir Path tempFolder;

  @BeforeAll
  static void setup() throws Exception {
    BACKEND.start();
    port = BACKEND.port();
    client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    HttpHealthAssertions.assertIndexReady(client, port);
  }

  @AfterAll
  static void teardown() {
    BACKEND.stop();
  }

  @AfterEach
  void cleanup() {
    try {
      removeRoot(tempFolder.toAbsolutePath().toString());
    } catch (Exception e) {
      // best-effort
      log.debug("Cleanup removeRoot failed: {}", e.getMessage());
    }
  }

  @Test
  @DisplayName("Cursor round-trip advances results with stable sort")
  void cursorRoundTripAdvancesResults() throws Exception {

    String marker = "CursorPaging-" + UUID.randomUUID().toString().substring(0, 8);

    // Create > limit documents so nextCursor is expected.
    int fileCount = 5;
    for (int i = 0; i < fileCount; i++) {
      Path p = tempFolder.resolve(String.format("cursor-%02d-%s.txt", i, marker));
      Files.writeString(p, "This document contains marker: " + marker);
    }

    long initialDocCount = getDocCount();
    assertTrue(addRoot(tempFolder.toAbsolutePath().toString()), "Should successfully add root folder");
    assertTrue(
        awaitIndexingComplete(initialDocCount + fileCount, INDEXING_TIMEOUT),
        "Indexing should complete within " + INDEXING_TIMEOUT.toSeconds() + " seconds");

    JsonNode page1 =
        knowledgeSearch(
            Map.of(
                "query", marker,
                "limit", 2,
                "sort", "path_asc"));

    JsonNode results1 = page1.path("results");
    assertTrue(results1.isArray(), "results must be an array");
    assertTrue(results1.size() > 0, "expected at least one result on page 1");

    String nextCursor = page1.path("nextCursor").asText("");
    assertFalse(nextCursor.isBlank(), "expected nextCursor to be present for multi-page results");

    Set<String> ids1 = extractIds(results1);
    assertTrue(ids1.size() > 0, "expected ids on page 1");

    JsonNode page2 =
        knowledgeSearch(
            Map.of(
                "query", marker,
                "limit", 2,
                "sort", "path_asc",
                "cursor", nextCursor));

    JsonNode results2 = page2.path("results");
    assertTrue(results2.isArray(), "results must be an array");
    assertTrue(results2.size() > 0, "expected at least one result on page 2");
    Set<String> ids2 = extractIds(results2);

    // The next page should not repeat ids from the first page.
    Set<String> intersection = new HashSet<>(ids1);
    intersection.retainAll(ids2);
    assertTrue(intersection.isEmpty(), "expected no overlap between page 1 and page 2 ids");
  }

  private static Set<String> extractIds(JsonNode results) {
    Set<String> ids = new HashSet<>();
    for (JsonNode hit : results) {
      String id = hit.path("id").asText("");
      if (!id.isBlank()) ids.add(id);
    }
    return ids;
  }

  private boolean addRoot(String path) throws Exception {
    String jsonBody = MAPPER.writeValueAsString(Map.of("path", path, "collection", "cursor-test"));
    var response =
        client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/indexing/roots"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(REQUEST_TIMEOUT)
                .build(),
            HttpResponse.BodyHandlers.ofString());
    return response.statusCode() == 200;
  }

  private void removeRoot(String path) throws Exception {
    String jsonBody = MAPPER.writeValueAsString(Map.of("path", path, "collection", "cursor-test"));
    client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/indexing/roots"))
            .header("Content-Type", "application/json")
            .method("DELETE", HttpRequest.BodyPublishers.ofString(jsonBody))
            .timeout(REQUEST_TIMEOUT)
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode knowledgeSearch(Map<String, Object> body) throws Exception {
    String jsonBody = MAPPER.writeValueAsString(body);
    var response =
        client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/knowledge/search"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(REQUEST_TIMEOUT)
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), "expected 200 from /api/knowledge/search");
    return MAPPER.readTree(response.body());
  }

  private JsonNode getKnowledgeStatus() throws Exception {
    var response =
        client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/knowledge/status"))
                .timeout(REQUEST_TIMEOUT)
                .build(),
            HttpResponse.BodyHandlers.ofString());
    return MAPPER.readTree(response.body());
  }

  private long getDocCount() throws Exception {
    JsonNode status = getKnowledgeStatus();
    return status.path("docCount").asLong(0);
  }

  private long getQueueDepth() throws Exception {
    JsonNode status = getKnowledgeStatus();
    return status.path("queueDepth").asLong(-1);
  }

  private boolean awaitIndexingComplete(long minDocCount, Duration timeout) {
    long deadline = System.currentTimeMillis() + timeout.toMillis();
    while (System.currentTimeMillis() < deadline) {
      try {
        long queueDepth = getQueueDepth();
        long docCount = getDocCount();
        if (queueDepth == 0 && docCount >= minDocCount) {
          return true;
        }
        Thread.sleep(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      } catch (Exception e) {
        try {
          Thread.sleep(500);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          return false;
        }
      }
    }
    return false;
  }
}
