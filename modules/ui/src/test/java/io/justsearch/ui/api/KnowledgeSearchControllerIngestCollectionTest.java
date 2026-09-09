/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.javalin.Javalin;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.KnowledgeServerConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 811 (C-2a) — {@code POST /api/knowledge/ingest} accepts an optional {@code collection},
 * validated ON THE SERVER. The MCP tool schema is a convenience for well-behaved clients; this
 * endpoint is reachable directly, so the reserved-name guard must live here too.
 */
@DisplayName("POST /api/knowledge/ingest — collection validation")
final class KnowledgeSearchControllerIngestCollectionTest {

  private Javalin app;
  private HttpClient client;
  private io.justsearch.configuration.resolved.ConfigStore previousConfigStore;

  @BeforeEach
  void setup(@TempDir Path tempDir) {
    previousConfigStore =
        io.justsearch.configuration.resolved.ConfigStore.globalOrNull();
    io.justsearch.configuration.resolved.TestResolvedConfigHelper.storeWithDefaults();
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    // No worker is started: validation runs before any adapter/Worker call, and the watched-root
    // lookup is best-effort (an unstarted client yields an empty binding list).
    KnowledgeServerConfig config =
        new KnowledgeServerConfig(
            false,
            tempDir,
            tempDir,
            tempDir,
            5_000L,
            15_000L,
            3,
            5_000L,
            5_000L,
            300_000L,
            100,
            0L,
            0);
    KnowledgeSearchController controller =
        new KnowledgeSearchController(new KnowledgeServerBootstrap(new io.justsearch.core.execution.TestEngineExecutors(), config));
    app =
        Javalin.create(
                cfg -> {
                  cfg.showJavalinBanner = false;
                  cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
                })
            .post("/api/knowledge/ingest", controller::handleIngest)
            .start(0);
  }

  @AfterEach
  void teardown() {
    if (app != null) {
      app.stop();
      app = null;
    }
    io.justsearch.configuration.resolved.TestResolvedConfigHelper.restoreGlobal(
        previousConfigStore);
  }

  private HttpResponse<String> post(String body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + app.port() + "/api/knowledge/ingest"))
            .timeout(Duration.ofSeconds(5))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json")
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  @DisplayName("a reserved app-internal collection is a 400 naming the reason")
  void reservedCollectionRejected() throws Exception {
    for (String reserved : List.of("agent-history", "justsearch-help", "Agent-History")) {
      HttpResponse<String> resp =
          post("{\"paths\":[\"C:/tmp/notes\"],\"collection\":\"" + reserved + "\"}");
      assertEquals(400, resp.statusCode(), "must refuse " + reserved + ": " + resp.body());
      assertTrue(
          resp.body().contains("reserved"),
          "the 400 must name the reason: " + resp.body());
    }
  }

  @Test
  @DisplayName("a blank collection is a 400 (not silently treated as absent)")
  void blankCollectionRejected() throws Exception {
    HttpResponse<String> resp = post("{\"paths\":[\"C:/tmp/notes\"],\"collection\":\"   \"}");
    assertEquals(400, resp.statusCode(), resp.body());
    assertTrue(resp.body().contains("non-empty"), resp.body());
  }

  @Test
  @DisplayName("a non-string collection is a 400, not an unchecked cast further down")
  void nonStringCollectionRejected() throws Exception {
    HttpResponse<String> resp = post("{\"paths\":[\"C:/tmp/notes\"],\"collection\":42}");
    assertEquals(400, resp.statusCode(), resp.body());
    assertTrue(resp.body().contains("string"), resp.body());
  }

  @Test
  @DisplayName("paths is still required (pre-811 contract unchanged)")
  void pathsStillRequired() throws Exception {
    assertEquals(400, post("{\"collection\":\"notes\"}").statusCode());
  }

  @Test
  @DisplayName("an accepted collection passes validation (non-existent paths ⇒ accepted:0, not 400)")
  void acceptedCollectionPassesValidation() throws Exception {
    HttpResponse<String> resp =
        post("{\"paths\":[\"C:/tmp/does-not-exist-811\"],\"collection\":\"research\"}");
    assertEquals(
        200,
        resp.statusCode(),
        "a valid collection must not be rejected; the path simply matches nothing: " + resp.body());
    assertTrue(resp.body().contains("\"accepted\":0"), resp.body());
  }
}
