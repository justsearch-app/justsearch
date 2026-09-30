/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;
import io.justsearch.core.context.EngineContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.javalin.Javalin;
import io.justsearch.app.api.IndexingService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 811 (C-2a) — {@code DELETE /api/indexing/collections} is the removal route the ingest tag
 * creates. Before 811 an out-of-root ad-hoc ingest was permanently unaddressable: unlabeled, and no
 * watched-root-prefix prune could reach it.
 */
@DisplayName("IndexingController delete-by-collection (contract)")
final class IndexingControllerDeleteCollectionTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private Javalin app;
  private HttpClient client;
  private RecordingIndexingService indexing;

  @BeforeEach
  void setup() {
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    indexing = new RecordingIndexingService();
    IndexingController controller =
        new IndexingController(
            () -> indexing,
            new io.justsearch.app.services.excludes.ExcludesServiceImpl(() -> indexing),
            null,
            null,
            io.justsearch.app.api.OperationLeaseService.noOp());
    app =
        Javalin.create(
                cfg -> {
                  cfg.showJavalinBanner = false;
                  cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
                })
            .delete("/api/indexing/collections", controller::handleDeleteCollection)
            .start(0);
  }

  @AfterEach
  void teardown() {
    if (app != null) {
      app.stop();
      app = null;
    }
  }

  private HttpResponse<String> delete(String body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + app.port() + "/api/indexing/collections"))
            .timeout(Duration.ofSeconds(5))
            .method("DELETE", HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json")
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  @DisplayName("deletes the named collection and reports the document count")
  void deletesNamedCollection() throws Exception {
    HttpResponse<String> resp = delete("{\"collection\":\"mcp-ingest\"}");
    assertEquals(200, resp.statusCode(), resp.body());
    var json = JSON.readTree(resp.body());
    assertEquals("ok", json.path("status").asString());
    assertEquals("mcp-ingest", json.path("collection").asString());
    assertEquals(7, json.path("deletedDocs").asInt());
    assertEquals(List.of("mcp-ingest"), indexing.deletedCollections);
  }

  @Test
  @DisplayName("refuses the reserved app-internal collections with a 400 naming the reason")
  void refusesReservedCollections() throws Exception {
    for (String reserved : List.of("agent-history", "justsearch-help", "Agent-History")) {
      HttpResponse<String> resp = delete("{\"collection\":\"" + reserved + "\"}");
      assertEquals(400, resp.statusCode(), "must refuse " + reserved + ": " + resp.body());
      assertTrue(
          resp.body().contains("app-internal"),
          "the 400 must name the reason: " + resp.body());
    }
    assertTrue(
        indexing.deletedCollections.isEmpty(),
        "a refused delete must never reach the Worker: " + indexing.deletedCollections);
  }

  @Test
  @DisplayName("refuses the untagged 'default' bucket — that would be a whole-index wipe")
  void refusesDefaultBucket() throws Exception {
    HttpResponse<String> resp = delete("{\"collection\":\"default\"}");
    assertEquals(400, resp.statusCode(), resp.body());
    assertTrue(indexing.deletedCollections.isEmpty());
  }

  @Test
  @DisplayName("a missing/blank collection is a 400, not a wildcard delete")
  void refusesBlank() throws Exception {
    assertEquals(400, delete("{}").statusCode());
    assertEquals(400, delete("{\"collection\":\"   \"}").statusCode());
    assertTrue(indexing.deletedCollections.isEmpty());
  }

  private static final class RecordingIndexingService implements IndexingService {
    final List<String> deletedCollections = new ArrayList<>();

    @Override
    public List<Path> getWatchedPaths(EngineContext engineContext) {
      return List.of();
    }

    @Override
    public void addWatchedPath(Path path, EngineContext engineContext) {
      throw new UnsupportedOperationException("not needed");
    }

    @Override
    public int removeWatchedPath(Path path, EngineContext engineContext) {
      throw new UnsupportedOperationException("not needed");
    }

    @Override
    public void flush(EngineContext engineContext) {
      // no-op
    }

    @Override
    public int deleteDocsByCollection(String collection, EngineContext engineContext) {
      deletedCollections.add(collection);
      return 7;
    }
  }
}
