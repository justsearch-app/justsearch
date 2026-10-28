/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static io.justsearch.indexerworker.server.Release030UpgradeCharacterizationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.ObjectMapper;

/** Installed jars over HTTP. Opt-in evidence reuses one fixed Engine folder across runs. */
@Tag("evidence")
@Timeout(360)
final class Release030RetirementHttpTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void packagedSwitchingUpgradeAndRestartKeepReplayedSearch() throws Exception {
    Path packaged = Path.of(System.getProperty("retirement.packaged.dir")).toAbsolutePath().normalize();
    Path scratch = Path.of(System.getenv("TMPDIR")).toAbsolutePath().normalize();
    Path root = Path.of(System.getProperty("retirement.engine.root", scratch.resolve("packaged-engine").toString()))
        .toAbsolutePath().normalize();
    assertTrue(root.startsWith(scratch), "the Engine evidence folder stays under TMPDIR");
    Path originalFile = root.resolve("http-original.properties");
    Properties original = new Properties();
    if (!Files.exists(originalFile)) {
      Release030Fixture.unpack("switching", root);
      var state = state(root);
      original.setProperty("active", state.active_generation());
      original.setProperty("building", state.building_generation());
      Path updated = Files.writeString(root.resolve("roots/alpha/pending.txt"), "httpreplayviolet");
      Files.delete(root.resolve("roots/beta/file-01.txt"));
      try (var queue = new SqliteJobQueue(root.resolve("data/jobs.db"))) {
        queue.open();
        assertTrue(queue.enqueueAndBufferFileForGeneration(state.building_generation(),
            JobQueue.EnqueueEntry.stat(updated).withinRoot(root.resolve("roots/alpha")), null));
      }
      try (var out = Files.newOutputStream(originalFile)) { original.store(out, "fixed reusable Engine fixture"); }
    } else try (var input = Files.newInputStream(originalFile)) { original.load(input); }
    for (int boot = 0; boot < 2; boot++) {
      try (var engine = new Engine(root, packaged, boot)) {
        engine.ready();
        await(() -> {
          Thread.sleep(450); // Avoid busy HTTP polling and per-request production log writes.
          return engine.has("httpreplayviolet", root.resolve("roots/alpha/pending.txt"))
              && !engine.has("nebula", root.resolve("roots/beta/file-01.txt"));
        },
            "packaged HTTP exposes committed replay effects", 120);
        assertTrue(engine.has("nebula", root.resolve("roots/alpha/file-01.txt")));
        await(() -> state(root).previous_generation() == null, "packaged replay retires B", 120);
        assertTrue(engine.has("copper", root.resolve("roots/alpha/file-00.txt")));
        assertEquals(original.getProperty("active"), state(root).active_generation());
        assertEquals("IDLE", state(root).migration_state());
        assertFalse(Files.exists(root.resolve("data/index/default/indices").resolve(original.getProperty("building"))));
        assertFalse(Files.exists(root.resolve("data/index/default/migration_progress.json")));
        System.out.println("PACKAGED_HTTP boot=" + boot + " old=present updated=present deleted=absent active="
            + state(root).active_generation() + " B=retired");
      }
    }
  }

  private static final class Engine implements AutoCloseable {
    private final Path root;
    private final Path output;
    private final Process process;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private int port;

    Engine(Path root, Path packaged, int boot) throws Exception {
      this.root = root;
      output = root.resolve("http-boot-" + boot + ".log");
      List<String> jars = new ArrayList<>();
      try (var files = Files.list(packaged)) {
        files.filter(path -> path.toString().endsWith(".jar")).sorted().forEach(path -> jars.add(path.toString()));
      }
      try (var files = Files.list(packaged.resolve("lib"))) {
        files.filter(path -> path.toString().endsWith(".jar")).sorted().forEach(path -> jars.add(path.toString()));
      }
      assertFalse(jars.isEmpty(), "headless distribution must be assembled");
      String classpath = String.join(java.io.File.pathSeparator, jars)
          + java.io.File.pathSeparator + System.getProperty("java.class.path");
      Path arguments = root.resolve("packaged.args");
      Files.writeString(arguments, "-cp\n\"" + classpath.replace("\\", "\\\\").replace("\"", "\\\"") + "\"\n");
      Path repo = Path.of(System.getProperty("user.dir")).toAbsolutePath();
      while (!Files.isDirectory(repo.resolve("SSOT"))) repo = repo.getParent();
      Path java = packaged.resolve("runtime/bin").resolve(
          System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
      assertTrue(Files.isExecutable(java), "the packaged runtime must be staged: " + java);
      var command = List.of(java.toString(),
          "-Xmx768m", "--enable-native-access=ALL-UNNAMED", "-Duser.home=" + root.resolve("home"),
          "-Djustsearch.ssot.path=" + repo.resolve("SSOT"), "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"),
          "-Djustsearch.test.noVolumeIdentity=" + Boolean.getBoolean("justsearch.test.noVolumeIdentity"),
          "@" + arguments, Child.class.getName(), root.toString(), packaged.toString());
      var builder = new ProcessBuilder(command).directory(repo.toFile())
          .redirectErrorStream(true).redirectOutput(output.toFile());
      var env = builder.environment();
      env.keySet().removeIf(key -> key.startsWith("JUSTSEARCH_") || key.equals("JAVA_TOOL_OPTIONS"));
      env.putAll(Map.of("JUSTSEARCH_HOME", root.resolve("home").toString(),
          "JUSTSEARCH_DATA_DIR", root.resolve("data").toString(), "JUSTSEARCH_API_PORT", "0",
          "JUSTSEARCH_MODE", "mcp-lite", "JUSTSEARCH_REPO_ROOT", repo.toString(),
          "JUSTSEARCH_AI_DISABLED", "true", "JUSTSEARCH_LLM_ENABLED", "false", "JUSTSEARCH_GPU_ENABLED", "false"));
      env.putAll(Map.of("JUSTSEARCH_NER_ENABLED", "false", "JUSTSEARCH_SPLADE_ENABLED", "false",
          "JUSTSEARCH_AI_EMBED_ENABLED", "false"));
      process = builder.start();
    }

    void ready() throws Exception {
      await(() -> {
        assertTrue(process.isAlive(), () -> "packaged Engine exited: " + log());
        try {
          var manifest = JSON.readTree(root.resolve("data/runtime/manifest.json").toFile());
          port = manifest.path("head").path("apiPort").asInt();
          var response = http.send(HttpRequest.newBuilder(URI.create(base() + "/api/knowledge/status"))
              .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
          return response.statusCode() == 200 && JSON.readTree(response.body()).path("ready").asBoolean();
        } catch (java.io.IOException | tools.jackson.core.JacksonException unavailable) { return false; }
      }, "packaged Engine becomes ready: " + output, 120);
    }

    boolean has(String query, Path file) throws Exception {
      String body = JSON.writeValueAsString(Map.of("query", query, "limit", 100, "mode", "text"));
      var response = http.send(HttpRequest.newBuilder(URI.create(base() + "/api/knowledge/search"))
          .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode(), response.body());
      var results = JSON.readTree(response.body()).path("results");
      assertTrue(results.isArray(), response.body());
      for (var hit : results) if (key(file).equals(hit.path("id").asText())) return true;
      return false;
    }

    private String base() { return "http://127.0.0.1:" + port; }
    private String log() {
      try { return Files.readString(output); } catch (java.io.IOException failure) { return failure.toString(); }
    }
    @Override public void close() throws Exception {
      process.destroy();
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly(); assertTrue(process.waitFor(10, TimeUnit.SECONDS));
      }
    }
  }

  public static final class Child {
    public static void main(String[] args) throws Exception {
      Path root = Path.of(args[0]), packaged = Path.of(args[1]);
      io.justsearch.indexerworker.services.NoVolumeIdentityExtension.installVolumeLookupIfAbsent();
      configure(root);
      var models = new ReleasedModelIdentities(root);
      try (models) {
        for (String type : List.of("io.justsearch.ui.HeadlessApp", KnowledgeServer.class.getName(),
            "io.justsearch.adapters.lucene.runtime.IndexMetadataParityGuard")) {
          Path loaded = Path.of(Class.forName(type).getProtectionDomain().getCodeSource().getLocation().toURI());
          assertTrue(loaded.startsWith(packaged), type + " must load from the assembled distribution: " + loaded);
        }
        Class.forName("io.justsearch.ui.HeadlessApp").getMethod("main", String[].class)
            .invoke(null, (Object) new String[0]);
        new java.util.concurrent.CountDownLatch(1).await(); // Keep the released model identities for the Engine lifetime.
      }
    }
  }
}
