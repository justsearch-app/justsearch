/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.javalin.Javalin;
import io.justsearch.app.api.IndexingService;
import io.justsearch.core.context.EngineContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** S34.2 / N34.2: preview explains nesting before Add without redefining exact membership. */
final class IndexingControllerRootPreviewTest {
  @TempDir Path tempDir;
  private Javalin app;
  private Path watched;
  private List<Path> paths;
  private final ObjectMapper json = new ObjectMapper();

  @BeforeEach
  void setup() throws Exception {
    watched = Files.createDirectories(tempDir.resolve("docs"));
    paths = List.of(watched);
    IndexingService indexing = new IndexingService() {
      @Override public List<Path> getWatchedPaths(EngineContext context) { return paths; }
      @Override public void addWatchedPath(Path path, EngineContext context) {
        throw new AssertionError("preview must not add a root");
      }
      @Override public int removeWatchedPath(Path path, EngineContext context) {
        throw new AssertionError("preview must not remove a root");
      }
      @Override public void flush(EngineContext context) {
        throw new AssertionError("preview must not flush");
      }
    };
    IndexingController controller = new IndexingController(() -> indexing, null, null, null);
    app = Javalin.create(cfg -> {
      cfg.showJavalinBanner = false;
      cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
    }).post("/api/indexing-roots/preview", controller::handlePreviewRoot).start(0);
  }

  @AfterEach
  void teardown() { if (app != null) app.stop(); }

  private JsonNode preview(Path path) throws Exception {
    try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
      var response = client.send(HttpRequest.newBuilder(
          URI.create("http://localhost:" + app.port() + "/api/indexing-roots/preview"))
          .header("Content-Type", "application/json").timeout(Duration.ofSeconds(5))
          .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
              java.util.Map.of("path", path.toString())))).build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode(), response.body());
      return json.readTree(response.body());
    }
  }

  @Test
  void explainsInsideOverlapBeforeAdd() throws Exception {
    var response = preview(Files.createDirectory(watched.resolve("child")));
    assertEquals("inside", response.path("overlap").path("relationship").asString());
    assertEquals(watched.toString(), response.path("overlap").path("path").asString());
    assertTrue(response.path("exists").asBoolean());
    assertTrue(response.path("isDir").asBoolean());
    assertFalse(response.path("alreadyWatched").asBoolean());
  }

  @Test
  void explainsContainingOverlapBeforeAdd() throws Exception {
    var response = preview(tempDir);
    assertEquals("containing", response.path("overlap").path("relationship").asString());
    assertEquals(watched.toString(), response.path("overlap").path("path").asString());
    assertFalse(response.path("alreadyWatched").asBoolean());
  }

  @Test
  void exactReaddKeepsMembershipWithoutOverlapEvenWithLegacyNesting() throws Exception {
    paths = List.of(tempDir, watched.resolve("child"), watched);
    var response = preview(watched.resolve(".." ).resolve("docs"));
    assertTrue(response.path("alreadyWatched").asBoolean());
    assertFalse(response.has("overlap"));
  }

  @Test
  void siblingPrefixIsNotAnOverlapAndOldFieldsRemain() throws Exception {
    var response = preview(Files.createDirectory(tempDir.resolve("docs-other")));
    assertFalse(response.path("alreadyWatched").asBoolean());
    assertFalse(response.has("overlap"));
    assertEquals(0, response.path("fileCount").asLong());
    assertFalse(response.path("capped").asBoolean());
  }
}
