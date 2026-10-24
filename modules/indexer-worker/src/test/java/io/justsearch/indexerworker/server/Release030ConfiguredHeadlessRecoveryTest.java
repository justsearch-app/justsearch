/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static io.justsearch.indexerworker.server.Release030UpgradeCharacterizationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/** X4: real HeadlessApp/YAML membership survives the deferred producer replacement and restart. */
@Timeout(240)
final class Release030ConfiguredHeadlessRecoveryTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void configuredRootAndBundledHelpFinishRecoveryAcrossHeadlessRestart(@TempDir Path temporary)
      throws Exception {
    Path root = Release030Fixture.unpack("small", temporary);
    Path operator = Files.createDirectory(root.resolve("operator"));
    Path queued = Files.writeString(operator.resolve("queued.txt"), "configuredqueuedcobalt");
    Path added = Files.writeString(operator.resolve("offline-added.txt"), "configuredofflinejade");
    Files.writeString(root.resolve("collections.yaml"), "index:\n  collections:\n"
        + "    - name: default\n      roots:\n        - '" + operator.toString().replace("'", "''") + "'\n");
    sql(root, "INSERT INTO jobs(path,state,attempts,last_updated,collection,size_bytes) VALUES('"
        + key(queued) + "','PENDING',0," + System.currentTimeMillis() + ",'default'," + Files.size(queued) + ")");
    assertEquals(0, count(root, "path='" + key(added) + "'"), "the offline source has no queued job");
    String generation = state(root).active_generation();
    for (int boot = 0; boot < 2; boot++) {
      try (var engine = new Headless(root, boot)) {
        engine.awaitReady();
        await(() -> engine.has("configuredqueuedcobalt", queued), "the authentic v13 job is processed");
        await(() -> engine.has("configuredofflinejade", added),
            "X4: a configured root's offline-added source must be recovered through HeadlessApp");
        await(() -> !Files.exists(root.resolve("data/upgrade-030-recovery.properties")),
            "X4: configured and bundled-help root recovery must complete");
        await(() -> count(root, "path='" + key(added) + "' AND state='DONE'") == 1,
            "the recovered admission's queue acknowledgement must settle after search publication");
        assertTrue(engine.has("nebula", root.resolve("roots/alpha/file-01.txt")));
        assertEquals(generation, state(root).active_generation(), "no rebuild or full migration");
        assertEquals("IDLE", state(root).migration_state());
      }
    }
  }

  private static final class Headless implements AutoCloseable {
    private final Path root;
    private final Path output;
    private final Process process;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private int port;

    Headless(Path root, int boot) throws Exception {
      this.root = root;
      output = root.resolve("headless-" + boot + ".log");
      Path repo = Path.of(System.getProperty("user.dir")).toAbsolutePath();
      while (!Files.isDirectory(repo.resolve("SSOT"))) repo = repo.getParent();
      Path args = root.resolve("headless.args");
      String headlessClasspath = System.getProperty("justsearch.test.headless.classpath");
      if (headlessClasspath == null) throw new IllegalStateException("The isolated HeadlessApp runtime is required");
      String classpath = headlessClasspath + java.io.File.pathSeparator + System.getProperty("java.class.path");
      Files.writeString(args, "-cp\n\"" + classpath
          .replace("\\", "\\\\").replace("\"", "\\\"") + "\"\n");
      var command = new ArrayList<String>();
      command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
      command.addAll(java.util.List.of("-Xmx768m", "--enable-native-access=ALL-UNNAMED",
          "-Duser.home=" + root.resolve("home"), "-Djustsearch.ssot.path=" + repo.resolve("SSOT"),
          "@" + args, Child.class.getName(), root.toString()));
      var builder = new ProcessBuilder(command).directory(repo.toFile())
          .redirectErrorStream(true).redirectOutput(output.toFile());
      var env = builder.environment();
      env.keySet().removeIf(key -> key.startsWith("JUSTSEARCH_") || key.equals("JAVA_TOOL_OPTIONS"));
      env.putAll(Map.of("JUSTSEARCH_HOME", root.resolve("home").toString(),
          "JUSTSEARCH_DATA_DIR", root.resolve("data").toString(), "JUSTSEARCH_API_PORT", "0",
          "JUSTSEARCH_CONFIG", root.resolve("collections.yaml").toString(), "JUSTSEARCH_MODE", "mcp-lite",
          "JUSTSEARCH_REPO_ROOT", repo.toString(), "JUSTSEARCH_AI_DISABLED", "true",
          "JUSTSEARCH_LLM_ENABLED", "false", "JUSTSEARCH_GPU_ENABLED", "false"));
      env.putAll(Map.of("JUSTSEARCH_NER_ENABLED", "false", "JUSTSEARCH_SPLADE_ENABLED", "false",
          "JUSTSEARCH_AI_EMBED_ENABLED", "false"));
      env.put("JDK_JAVA_OPTIONS", "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir")
          + " -Djustsearch.test.noVolumeIdentity=" + Boolean.getBoolean("justsearch.test.noVolumeIdentity"));
      env.put("TMPDIR", System.getProperty("java.io.tmpdir"));
      process = builder.start();
    }

    void awaitReady() throws Exception {
      await(() -> {
        assertTrue(process.isAlive(), () -> "HeadlessApp exited: " + log());
        try {
          var manifest = JSON.readTree(root.resolve("data/runtime/manifest.json").toFile());
          port = manifest.path("head").path("apiPort").asInt();
          var response = http.send(HttpRequest.newBuilder(URI.create(base() + "/api/knowledge/status"))
              .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
          return response.statusCode() == 200 && JSON.readTree(response.body()).path("ready").asBoolean();
        } catch (java.io.IOException | tools.jackson.core.JacksonException unavailable) { return false; }
      }, "HeadlessApp must become ready: " + output, 90);
    }

    boolean has(String query, Path file) throws Exception {
      var body = JSON.writeValueAsString(Map.of("query", query, "limit", 100, "mode", "sparse"));
      var response = http.send(HttpRequest.newBuilder(URI.create(base() + "/api/knowledge/search"))
          .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode(), response.body());
      return response.body().replace("\\\\", "\\").contains(file.toString());
    }

    private String base() { return "http://127.0.0.1:" + port; }

    private String log() {
      try { return Files.readString(output); }
      catch (java.io.IOException failure) { return failure.toString(); }
    }

    @Override
    public void close() throws Exception {
      process.destroy();
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "owned HeadlessApp must exit");
      }
    }
  }

  /** Only model identity/volume lookup are injected; HeadlessApp and its Head/Worker wiring run. */
  public static final class Child {
    public static void main(String[] args) throws Exception {
      io.justsearch.indexerworker.services.NoVolumeIdentityExtension.installVolumeLookupIfAbsent();
      Path root = Path.of(args[0]);
      configure(root);
      var models = new ReleasedModelIdentities(root);
      try (models) {
        Class.forName("io.justsearch.ui.HeadlessApp").getMethod("main", String[].class)
            .invoke(null, (Object) new String[0]);
      }
    }
  }
}
