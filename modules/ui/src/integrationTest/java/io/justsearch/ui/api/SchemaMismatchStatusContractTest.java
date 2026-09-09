package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.SearchRequest;
import io.justsearch.app.api.SearchResponse;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.KnowledgeServerConfig;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.app.services.settings.UiSettingsStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Disabled in CI: This test spawns servers and has environment dependencies
 * that don't work reliably on CI runners.
 */
@DisplayName("Schema mismatch contract: /api/status exposes reindexRequired (index_fingerprint)")
@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
// 120s, not 60s: setUp uses the Head's own bounded worker-start retry, whose worst case is three
// spawn+validate rounds. A budget that a legal slow path can exceed turns a diagnosable failure
// (with the engine log tail) into a bare timeout.
@Timeout(value = 120, unit = TimeUnit.SECONDS)
final class SchemaMismatchStatusContractTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @TempDir Path tmp;

  private String prevDataDir;
  private String prevApiPort;
  private String prevParityAllowMismatch;
  private ConfigStore prevConfigStore;
  private KnowledgeServerBootstrap bootstrap;
  private LocalApiServer server;
  private Path engineLogPath;

  @BeforeEach
  void setup() throws Exception {
    prevDataDir = System.getProperty("justsearch.data.dir");
    prevApiPort = System.getProperty("justsearch.api.port");
    prevParityAllowMismatch = System.getProperty("justsearch.index.parity.allow_mismatch");

    // Ensure PlatformPaths resolves into this test sandbox (no user profile writes).
    Path dataDir = tmp.resolve("data");
    Files.createDirectories(dataDir);
    System.setProperty("justsearch.data.dir", dataDir.toAbsolutePath().toString());
    // Force ephemeral port even if a developer has JUSTSEARCH_API_PORT set.
    System.setProperty("justsearch.api.port", "0");
    // Allow Worker startup even when commit-metadata parity is mismatched (we assert the surfaced mismatch via /api/status).
    // The operator escape, used here for what it is: this test deliberately opens an index whose
    // fingerprint does not match, to assert the STATUS surface reports it. Without the escape the
    // parity guard would (correctly) refuse the open and the status contract could never be
    // exercised. Nothing in production sets this (tempdoc 915 §C).
    System.setProperty("justsearch.index.parity.allow_mismatch", "true");

    // Initialize ConfigStore so that RerankerConfig.fromEnv() (called during LocalApiServer construction) resolves.
    prevConfigStore = ConfigStore.globalOrNull();
    TestResolvedConfigHelper.storeFromEnvironment();

    // Seed a legacy index with a mismatched stored schema fingerprint.
    seedLegacyIndexWithBogusSchemaFingerprint(dataDir);

    // Start a real index half via KnowledgeServerBootstrap (required for /api/status schema
    // fields). Lane F stage A item A9: it runs INSIDE this JVM now. Before A9 this spawned the
    // indexer-worker distribution and waited for it to publish a gRPC port; A9 deleted that
    // server, so the spawn path cannot come up at all. The property under test — a schema
    // mismatch surfaces on /api/status as reindexRequired — is unchanged and is now exercised
    // without a second process, which also removes this test's dependency on the
    // indexer-worker installDist and its Windows file-lock teardown dance.
    KnowledgeServerConfig config = KnowledgeServerConfig.load();
    // Item A16: one process, one log. This was <dataDir>/logs/worker.log, which nothing has
    // written since A11 deleted the Worker child — a failure tail read from it was empty.
    engineLogPath = config.dataDir().resolve("logs").resolve("engine.log");

    bootstrap =
        new KnowledgeServerBootstrap(new io.justsearch.core.execution.TestEngineExecutors(),
            config,
            null,
            new io.justsearch.app.services.lifecycle.WorkerCapability(),
            new io.justsearch.app.engine.EngineRoot(config.deadlineMs(), config.batchSize()));
    try {
      // Same bounded retry the Head uses: on a loaded dev machine a transient PID-validation
      // timeout must not read as a schema-contract failure. (This test never runs in CI — see the
      // @DisabledIfEnvironmentVariable above — so the budget below covers local load only.)
      bootstrap.startWithRetry();
    } catch (Exception e) {
      String tail = readTailBestEffort(engineLogPath, 12_000);
      throw new IllegalStateException(
          "Failed to start KnowledgeServerBootstrap. Engine log tail:\n" + tail, e);
    }

    UiSettingsStore settingsStore =
        new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY, tmp.resolve("settings.json"));
    Path indexBase = tmp.resolve("index");
    Files.createDirectories(indexBase);
    server = LocalApiServer.builder(settingsStore, indexBase)
        .knowledgeServer(bootstrap)
        .build();
  }

  @AfterEach
  void cleanup() throws Exception {
    if (server != null) {
      try {
        server.stop();
      } catch (Exception ignored) {
        // best-effort
      } finally {
        server = null;
      }
    }
    if (bootstrap != null) {
      try {
        bootstrap.close();
      } catch (Exception ignored) {
        // best-effort
      } finally {
        bootstrap = null;
      }
    }

    // Windows: the index half holds file locks on jobs.db (and, before item A11, the Worker
    // subprocess held one on worker.log) that the OS releases lazily (100-2000ms after close).
    // Poll-delete regular files so JUnit @TempDir cleanup only needs to remove empty directories.
    if (tmp != null) {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (System.nanoTime() < deadline) {
        if (tryDeleteRegularFiles(tmp)) break;
        try {
          Thread.sleep(100);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }

    engineLogPath = null;

    restoreProp("justsearch.data.dir", prevDataDir);
    restoreProp("justsearch.api.port", prevApiPort);
    restoreProp("justsearch.index.parity.allow_mismatch", prevParityAllowMismatch);
    TestResolvedConfigHelper.restoreGlobal(prevConfigStore);
  }

  @Test
  @DisplayName("/api/status surfaces schema mismatch via reindexRequired + reason")
  void statusSurfacesSchemaMismatch() throws Exception {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    String baseUrl = "http://127.0.0.1:" + server.getPort();

    StatusSnapshot status =
        awaitStatusReady(client, baseUrl, Duration.ofSeconds(30), Duration.ofMillis(500));
    assertEquals(200, status.statusCode, "Expected /api/status 200. Body: " + status.body);
    // Schema fingerprints live under the "schema" top-level group (since status v2).
    JsonNode schema = status.json.path("schema");

    assertTrue(
        schema.path("reindexRequired").asBoolean(false),
        "schema.reindexRequired must be true. Body: " + status.body);
    assertEquals(
        "schema_mismatch",
        schema.path("reindexRequiredReason").asText(""),
        "schema.reindexRequiredReason must be schema_mismatch. Body: " + status.body);

    String stored = schema.path("fpStored").asText("");
    String current = schema.path("fpCurrent").asText("");
    assertFalse(stored.isBlank(), "schema.fpStored must be present. Body: " + status.body);
    assertFalse(current.isBlank(), "schema.fpCurrent must be present. Body: " + status.body);
    assertNotEquals(
        current, stored, "Stored fingerprint must differ from current. Body: " + status.body);

    String compat = schema.path("compatState").asText("");
    // Canonical contract: BLOCKED_MISMATCH (legacy tolerated for older Workers).
    assertTrue(
        "BLOCKED_MISMATCH".equalsIgnoreCase(compat)
            || "MISMATCH".equalsIgnoreCase(compat)
            || compat.isBlank() /* older servers may omit */,
        "Unexpected schema.compatState: " + compat + ". Body: " + status.body);
  }

  private StatusSnapshot awaitStatusReady(
      HttpClient client, String baseUrl, Duration timeout, Duration pollInterval) throws Exception {
    long timeoutNanos = Math.max(1L, timeout.toNanos());
    long deadline = System.nanoTime() + timeoutNanos;
    long pollMs = Math.max(50L, pollInterval.toMillis());
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/api/status"))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();

    Integer lastStatusCode = null;
    String lastBody = "";
    String lastError = null;

    while (System.nanoTime() < deadline) {
      try {
        HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
        lastStatusCode = resp.statusCode();
        lastBody = resp.body();
        lastError = null;

        if (resp.statusCode() == 200) {
          JsonNode json = MAPPER.readTree(resp.body());
          // Schema fingerprints are nested under the "schema" top-level group (since status v2).
          JsonNode schema = json.path("schema");
          String stored = schema.path("fpStored").asText("");
          String current = schema.path("fpCurrent").asText("");
          if (!stored.isBlank() && !current.isBlank()) {
            return new StatusSnapshot(resp.statusCode(), resp.body(), json);
          }
        }
      } catch (Exception e) {
        lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
      }

      Thread.sleep(pollMs);
    }

    String engineTail = readTailBestEffort(engineLogPath, 12_000);
    fail(
        "Timed out waiting for /api/status readiness after "
            + timeout.toSeconds()
            + "s. Last statusCode="
            + (lastStatusCode == null ? "<none>" : lastStatusCode)
            + ", lastError="
            + (lastError == null ? "<none>" : lastError)
            + ", lastBody="
            + (lastBody == null ? "<none>" : lastBody)
            + "\nEngine log tail:\n"
            + engineTail);
    throw new IllegalStateException("unreachable");
  }

  private static final class StatusSnapshot {
    final int statusCode;
    final String body;
    final JsonNode json;

    StatusSnapshot(int statusCode, String body, JsonNode json) {
      this.statusCode = statusCode;
      this.body = body;
      this.json = json;
    }
  }

  private static void seedLegacyIndexWithBogusSchemaFingerprint(Path dataDir) throws Exception {
    // Worker default when index.collections is absent: collectionName=default.
    //
    // Important (Windows): avoid creating a "legacy" index directly under indexBasePath because
    // the Worker will try to import it by moving the directory, which can fail if any mmapped
    // handles are still being released. Instead, pre-create a generation directory under
    // indexBasePath/indices/ so the Worker adopts it without moving files.
    Path indexBasePath = dataDir.resolve("index").resolve("default");
    Path indicesDir = indexBasePath.resolve("indices");
    Path genPath = indicesDir.resolve("v0_imported");
    Files.createDirectories(genPath);

    String bogusFp = "0".repeat(64);

    // Load SSOT field catalog (same as production Worker).
    JustSearchConfigurationLoader loader = new JustSearchConfigurationLoader();
    io.justsearch.configuration.FieldCatalogDef catalog = loader.loadFieldCatalog();

    // Build a CommitMetadataSource dynamically so this test doesn't need a compile-time dependency
    // on the indexing module (integrationTest classpath vs IDE lints).
    Class<?> cms = Class.forName("io.justsearch.indexing.runtime.CommitMetadataSource");
    Object cmsProxy =
        Proxy.newProxyInstance(
            cms.getClassLoader(),
            new Class<?>[] {cms},
            (proxy, method, args) -> {
              if ("build".equals(method.getName()) && method.getParameterCount() == 0) {
                Map<String, Object> m = new HashMap<>(new SsotCommitMetadataSource().build());
                m.put("index_fingerprint", bogusFp);
                return Map.copyOf(m);
              }
              throw new UnsupportedOperationException("Unexpected CommitMetadataSource method: " + method);
            });
    Supplier<Object> metadataSupplier = () -> cmsProxy;

    // Build runtime via the IndexSchema/builder API with a custom metadata source supplier.
    @SuppressWarnings("unchecked")
    Supplier<io.justsearch.indexing.runtime.CommitMetadataSource> typedSupplier =
        (Supplier<io.justsearch.indexing.runtime.CommitMetadataSource>) (Supplier<?>) metadataSupplier;
    RunningRuntime runtime =
        io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(
                catalog,
                typedSupplier,
                new io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator())
            .atPath(genPath)
            .open();

    try {
      // One document, deliberately. A stale fingerprint on an index holding NOTHING is not a
      // mismatch: there is no content that could have been written under the wrong shape and the
      // next commit re-stamps the whole user-data map, so ParityDiagnostics excludes it and the
      // status surface reports COMPATIBLE through the same predicate (tempdoc 915 C.14). Committing
      // an empty index here made this contract test assert a state the product no longer produces.
      runtime
          .indexingCoordinator()
          .indexSingle(
              new io.justsearch.indexing.api.IndexDocument(
                  Map.of(
                      io.justsearch.indexing.SchemaFields.DOC_ID, "legacy-doc",
                      io.justsearch.indexing.SchemaFields.DOC_UID, "legacy-doc#0",
                      io.justsearch.indexing.SchemaFields.CONTENT,
                          "written under a shape this runtime does not produce")));
      runtime.commitOps().commitAndTrack();
    } finally {
      runtime.close();
    }
  }

  /** Try to delete all regular files under {@code dir}; returns true when none remain. */
  private static boolean tryDeleteRegularFiles(Path dir) {
    try (var stream = Files.walk(dir)) {
      return stream.allMatch(p -> {
        if (Files.isDirectory(p)) return true;
        try {
          Files.deleteIfExists(p);
          return true;
        } catch (IOException e) {
          return false;
        }
      });
    } catch (IOException e) {
      return false;
    }
  }

  private static void restoreProp(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }

  private static String readTailBestEffort(Path file, int maxChars) {
    try {
      if (file == null || !Files.exists(file)) {
        return "<missing: " + file + ">";
      }
      String s = Files.readString(file, StandardCharsets.UTF_8);
      if (s.length() <= maxChars) {
        return s;
      }
      return s.substring(s.length() - maxChars);
    } catch (Exception e) {
      return "<error reading " + file + ": " + e.getMessage() + ">";
    }
  }

}
