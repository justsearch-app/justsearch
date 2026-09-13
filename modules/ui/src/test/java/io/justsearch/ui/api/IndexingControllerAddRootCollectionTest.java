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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code POST /api/indexing/roots} takes a caller-supplied {@code collection} that tags every
 * document the root's scan admits, so it is subject to the SAME reserved-name guard the ad-hoc
 * ingest surfaces enforce ({@code IngestCollectionPolicy}). It previously had none: a root could be
 * registered as {@code agent-history}, letting arbitrary user content inherit the search posture of
 * the transcript corpus the default scope excludes.
 */
@DisplayName("POST /api/indexing/roots — collection validation")
final class IndexingControllerAddRootCollectionTest {

  @TempDir Path tempDir;

  private Javalin app;
  private HttpClient client;
  private RecordingIndexingService indexing;
  private Path root;

  @BeforeEach
  void setup() throws Exception {
    root = Files.createDirectories(tempDir.resolve("watched"));
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
            .post("/api/indexing/roots", controller::handleAddRoot)
            .start(0);
  }

  @AfterEach
  void teardown() {
    if (app != null) {
      app.stop();
      app = null;
    }
  }

  private HttpResponse<String> post(String body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/api/indexing/roots"))
            .timeout(Duration.ofSeconds(5))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json")
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private String jsonPath() {
    return root.toAbsolutePath().toString().replace("\\", "\\\\");
  }

  @Test
  @DisplayName("a reserved app-internal collection is a 400 naming the reason, and never reaches the service")
  void reservedCollectionRejected() throws Exception {
    for (String reserved : List.of("agent-history", "justsearch-help", "Agent-History", " agent-history ")) {
      HttpResponse<String> resp =
          post("{\"path\":\"" + jsonPath() + "\",\"collection\":\"" + reserved + "\"}");
      assertEquals(400, resp.statusCode(), "must refuse " + reserved + ": " + resp.body());
      assertTrue(resp.body().contains("reserved"), "the 400 must name the reason: " + resp.body());
    }
    assertTrue(
        indexing.added.isEmpty(),
        "a refused root must never be registered: " + indexing.added);
  }

  @Test
  @DisplayName("an ordinary collection is accepted and passed through trimmed")
  void ordinaryCollectionAccepted() throws Exception {
    HttpResponse<String> resp = post("{\"path\":\"" + jsonPath() + "\",\"collection\":\" my-notes \"}");
    assertEquals(200, resp.statusCode(), resp.body());
    assertEquals(1, indexing.added.size());
    assertEquals("my-notes", indexing.added.get(0));
  }

  @Test
  @DisplayName("an absent collection is unchanged — the index-default shape")
  void absentCollectionUnchanged() throws Exception {
    HttpResponse<String> resp = post("{\"path\":\"" + jsonPath() + "\"}");
    assertEquals(200, resp.statusCode(), resp.body());
    assertEquals(1, indexing.added.size());
    org.junit.jupiter.api.Assertions.assertNull(indexing.added.get(0));
  }

  @Test
  @DisplayName("a blank collection is unchanged — this route has always treated it as 'no label'")
  void blankCollectionUnchanged() throws Exception {
    HttpResponse<String> resp = post("{\"path\":\"" + jsonPath() + "\",\"collection\":\"   \"}");
    assertEquals(200, resp.statusCode(), resp.body());
    assertEquals(1, indexing.added.size());
    assertEquals("   ", indexing.added.get(0));
  }

  @Test
  @DisplayName("path validation still runs first (pre-existing contract unchanged)")
  void pathStillRequired() throws Exception {
    assertEquals(400, post("{\"collection\":\"notes\"}").statusCode());
    assertEquals(
        400,
        post("{\"path\":\"" + jsonPath() + "-missing\",\"collection\":\"notes\"}").statusCode());
    assertTrue(indexing.added.isEmpty());
  }

  /** Records the collection each accepted add-root carried. */
  private static final class RecordingIndexingService implements IndexingService {
    final List<String> added = new ArrayList<>();

    @Override
    public List<Path> getWatchedPaths(EngineContext engineContext) {
      return List.of();
    }

    @Override
    public void addWatchedPath(Path path, EngineContext engineContext) {
      added.add(null);
    }

    @Override
    public void addWatchedRoot(String collection, Path path, EngineContext engineContext) {
      added.add(collection);
    }

    @Override
    public int removeWatchedPath(Path path, EngineContext engineContext) {
      throw new UnsupportedOperationException("not needed");
    }

    @Override
    public void flush(EngineContext engineContext) {
      // no-op
    }
  }
}
