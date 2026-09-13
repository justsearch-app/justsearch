package io.justsearch.ui.api;
import io.justsearch.core.context.EngineContext;

import static org.junit.jupiter.api.Assertions.*;

import tools.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.SearchRequest;
import io.justsearch.app.api.SearchResponse;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
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

@DisplayName("IndexingController excludes apply (contract)")
class IndexingControllerExcludesApplyTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  private Javalin app;
  private HttpClient client;
  private ConfigStore previousGlobal;

  @BeforeEach
  void setup() {
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    previousGlobal = ConfigStore.globalOrNull();
  }

  @AfterEach
  void teardown() {
    if (app != null) {
      app.stop();
      app = null;
    }
    TestResolvedConfigHelper.restoreGlobal(previousGlobal);
  }

  /**
   * Seeds the patterns the way production does: {@code UiSettings} → ordinal 300 → ConfigStore.
   *
   * <p>These cases used to set {@code -Djustsearch.ui.exclude_patterns} directly, which stopped
   * exercising the real path when tempdoc 883 decision 4 slice 2 repointed
   * {@code ExcludesServiceImpl} at {@code ResolvedConfig.Ui#excludePatterns}. Only the seeding
   * changed here — every assertion below is the one it always made.
   */
  private static void seedExcludePatterns(List<String> patterns) {
    UiSettings settings = new UiSettings();
    settings.setExcludePatterns(new ArrayList<>(patterns));
    ResolvedConfigBuilder builder = ResolvedConfig.builder();
    ConfigStoreRebuilder.contributeUiSettings(builder, settings);
    ConfigStore.setGlobal(new ConfigStore(builder.build()));
  }

  @Test
  @DisplayName("POST /api/indexing/excludes/apply deletes excluded dirs and files (delegated to IndexingService)")
  void applyExcludesDeletesExpectedPaths() throws Exception {
    Path root = Files.createTempDirectory("js-excludes-root").toAbsolutePath().normalize();
    Files.writeString(root.resolve("keep.txt"), "hello");

    Path nodeModules = Files.createDirectories(root.resolve("node_modules"));
    Files.writeString(nodeModules.resolve("a.js"), "x");
    Files.writeString(nodeModules.resolve("b.log"), "x");

    Path logs = Files.createDirectories(root.resolve("logs"));
    Path excludedLog = logs.resolve("x.log");
    Files.writeString(excludedLog, "x");

    seedExcludePatterns(List.of("**/node_modules/**", "**/*.log"));

    RecordingIndexingService indexing =
        new RecordingIndexingService(List.of(new IndexingService.WatchedRoot("default", root)));
    IndexingController controller =
        new IndexingController(
            () -> indexing,
            new io.justsearch.app.services.excludes.ExcludesServiceImpl(() -> indexing),
            null,
            null,
            io.justsearch.app.api.OperationLeaseService.noOp());

    app =
        Javalin.create(cfg -> { cfg.showJavalinBanner = false; cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper()); })
            .post("/api/indexing/excludes/apply", controller::handleApplyExcludes)
            .start(0);

    int port = app.port();
    HttpResponse<String> resp =
        client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/indexing/excludes/apply"))
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(200, resp.statusCode());

    var json = JSON.readTree(resp.body());
    assertEquals("ok", json.path("status").asText());
    assertEquals(2, json.path("patterns").asInt());
    assertEquals(1, json.path("rootsProcessed").asInt());

    // Directory patterns should use delete-by-prefix optimization.
    assertTrue(
        indexing.deletedByPathPrefixes.stream().anyMatch(p -> p.equals(nodeModules.toAbsolutePath().normalize())),
        "Expected deleteDocsByPathPrefix(node_modules) to be called");

    // File patterns should delete exact doc IDs.
    assertTrue(
        indexing.deletedDocIds.contains(excludedLog.toAbsolutePath().normalize().toString()),
        "Expected deleteDocById(x.log) to be called");

    // Should NOT attempt to delete every file under node_modules individually (subtree skipped).
    assertFalse(
        indexing.deletedDocIds.contains(nodeModules.resolve("a.js").toAbsolutePath().normalize().toString()),
        "node_modules subtree should be deleted by path prefix, not per-file");
    assertFalse(
        indexing.deletedDocIds.contains(nodeModules.resolve("b.log").toAbsolutePath().normalize().toString()),
        "node_modules subtree should be deleted by path prefix, not per-file");
  }

  @Test
  @DisplayName("POST /api/indexing/excludes/apply?dryRun=true counts matches without deleting")
  void dryRunCountsWithoutDeleting() throws Exception {
    Path root = Files.createTempDirectory("js-excludes-dryrun").toAbsolutePath().normalize();
    Files.writeString(root.resolve("keep.txt"), "hello");

    Path nodeModules = Files.createDirectories(root.resolve("node_modules"));
    Files.writeString(nodeModules.resolve("a.js"), "x");

    Path logs = Files.createDirectories(root.resolve("logs"));
    Files.writeString(logs.resolve("x.log"), "x");

    seedExcludePatterns(List.of("**/node_modules/**", "**/*.log"));

    RecordingIndexingService indexing =
        new RecordingIndexingService(List.of(new IndexingService.WatchedRoot("default", root)));
    IndexingController controller =
        new IndexingController(
            () -> indexing,
            new io.justsearch.app.services.excludes.ExcludesServiceImpl(() -> indexing),
            null,
            null,
            io.justsearch.app.api.OperationLeaseService.noOp());

    app =
        Javalin.create(cfg -> { cfg.showJavalinBanner = false; cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper()); })
            .post("/api/indexing/excludes/apply", controller::handleApplyExcludes)
            .start(0);

    int port = app.port();
    HttpResponse<String> resp =
        client.send(
            HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/api/indexing/excludes/apply?dryRun=true"))
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(200, resp.statusCode());

    var json = JSON.readTree(resp.body());
    assertEquals("ok", json.path("status").asText());
    assertTrue(json.path("dryRun").asBoolean(), "Should report dryRun=true");
    assertTrue(json.path("matchedFiles").asInt() > 0, "Should have matched files");
    assertTrue(json.path("perPattern").isArray(), "Should have perPattern array");
    assertEquals(2, json.path("perPattern").size(), "Should have 2 expanded patterns");

    // Dry run must NOT delete anything
    assertTrue(indexing.deletedByPathPrefixes.isEmpty(), "Dry run should not delete by path prefix");
    assertTrue(indexing.deletedDocIds.isEmpty(), "Dry run should not delete by doc ID");
  }

  @Test
  @DisplayName("dry-run perPattern counts attribute matches to the correct pattern index")
  void dryRunPerPatternCountsAreAttributedCorrectly() throws Exception {
    Path root = Files.createTempDirectory("js-excludes-perpattern").toAbsolutePath().normalize();
    Files.writeString(root.resolve("keep.txt"), "hello");

    // Pattern 0 fixture: 3 *.log files (root, logs/, nested/) - file pattern, counts files.
    Files.writeString(root.resolve("root.log"), "x");
    Path logs = Files.createDirectories(root.resolve("logs"));
    Files.writeString(logs.resolve("a.log"), "x");
    Path nested = Files.createDirectories(root.resolve("nested"));
    Files.writeString(nested.resolve("b.log"), "x");

    // Pattern 1 fixture: 1 *.tmp file - separates count cleanly from pattern 0.
    Files.writeString(root.resolve("scratch.tmp"), "x");

    seedExcludePatterns(List.of("**/*.log", "**/*.tmp"));

    RecordingIndexingService indexing =
        new RecordingIndexingService(List.of(new IndexingService.WatchedRoot("default", root)));
    IndexingController controller =
        new IndexingController(
            () -> indexing,
            new io.justsearch.app.services.excludes.ExcludesServiceImpl(() -> indexing),
            null,
            null,
            io.justsearch.app.api.OperationLeaseService.noOp());

    app =
        Javalin.create(cfg -> { cfg.showJavalinBanner = false; cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper()); })
            .post("/api/indexing/excludes/apply", controller::handleApplyExcludes)
            .start(0);

    int port = app.port();
    HttpResponse<String> resp =
        client.send(
            HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/api/indexing/excludes/apply?dryRun=true"))
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(200, resp.statusCode());
    var json = JSON.readTree(resp.body());
    var perPattern = json.path("perPattern");
    assertEquals(2, perPattern.size(), "Should have 2 patterns");

    // Match counts back to the patterns they describe (order is insertion order
    // from the request, but assert by the pattern field rather than the index
    // to stay robust against future reordering inside the controller).
    int logCount = -1;
    int tmpCount = -1;
    for (int i = 0; i < perPattern.size(); i++) {
      String pattern = perPattern.get(i).path("pattern").asText();
      int matches = perPattern.get(i).path("matches").asInt();
      if ("**/*.log".equals(pattern)) {
        logCount = matches;
      } else if ("**/*.tmp".equals(pattern)) {
        tmpCount = matches;
      }
    }
    assertEquals(3, logCount, "**/*.log should match exactly the 3 .log files");
    assertEquals(1, tmpCount, "**/*.tmp should match exactly the 1 .tmp file");
  }

  private static final class RecordingIndexingService implements IndexingService {
    private final List<WatchedRoot> roots;
    final List<Path> deletedByPathPrefixes = new ArrayList<>();
    final List<String> deletedDocIds = new ArrayList<>();

    RecordingIndexingService(List<WatchedRoot> roots) {
      this.roots = roots;
    }

    @Override
    public List<Path> getWatchedPaths(EngineContext engineContext) {
      return roots.stream().map(WatchedRoot::path).toList();
    }

    @Override
    public List<WatchedRoot> getWatchedRoots(EngineContext engineContext) {
      return roots;
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
    public int deleteDocsByPathPrefix(Path pathPrefix, EngineContext engineContext) {
      deletedByPathPrefixes.add(pathPrefix.toAbsolutePath().normalize());
      return 3;
    }

    @Override
    public boolean deleteDocById(String docId, EngineContext engineContext) {
      deletedDocIds.add(docId);
      return true;
    }
  }
}
