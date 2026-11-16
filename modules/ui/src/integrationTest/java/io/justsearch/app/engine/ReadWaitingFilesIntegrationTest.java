/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.services.settings.UiSettingsStore;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S6c-1/4 end to end on the real Engine main: outside reading hours a queued file is held and the
 * status says so; "read waiting files now" through the real invoke route reads it, search finds it,
 * the saved rules are unchanged, the hold applies again, and a restarted Engine carries no release.
 */
final class ReadWaitingFilesIntegrationTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  @TempDir Path directory;

  @Test
  @Timeout(300)
  void outsideReadingHoursTheWaitingFileIsReadOnRequestAndTheRulesStay() throws Exception {
    Path corpus = Files.createDirectories(directory.resolve("corpus"));
    Path waiting = Files.writeString(corpus.resolve("waiting.txt"), "readnowwaitingmarker held by reading hours");
    Path later = Files.writeString(corpus.resolve("later.txt"), "readnowlatermarker queued after restart");
    int now = LocalDateTime.now().getHour() * 60 + LocalDateTime.now().getMinute();
    var schedule = Map.of("enabled", true, "days", List.of(1, 2, 3, 4, 5, 6, 7),
        "startMinute", (now + 120) % 1440, "endMinute", (now + 180) % 1440);
    JsonNode savedRules;
    try (var engine = new Engine("first")) {
      engine.awaitStatus(s -> s.path("indexAvailable").asBoolean());
      var rules = new java.util.HashMap<String, Object>();
      rules.put("schedule", schedule);
      rules.put("pauseOnBattery", false);
      engine.operation("core.set-indexing-rules", rules);
      engine.ingest(waiting);
      var held = engine.awaitStatus(s -> indexing(s).path("heldFilesWaiting").asBoolean()
          && "[\"schedule\"]".equals(indexing(s).path("readingRuleReasons").toString())
          && "PAUSED".equals(indexing(s).path("state").asText()));
      assertFalse(indexing(held).path("release").path("active").asBoolean(true));
      assertTrue(indexing(held).path("pendingJobs").asLong() > 0);
      assertEquals(0, engine.search("readnowwaitingmarker"), "a held file is not read");
      savedRules = settingsRules();

      var receipt = engine.operation("core.indexing-read-now", Map.of());
      assertEquals("started", receipt.path("outcome").asText(), receipt.toString());
      engine.awaitSearch("readnowwaitingmarker");
      var after = engine.awaitStatus(s -> indexing(s).path("pendingJobs").asLong(-1) == 0
          && !indexing(s).path("release").path("active").asBoolean(true)
          && "PAUSED".equals(indexing(s).path("state").asText()));
      assertEquals("[\"schedule\"]", indexing(after).path("readingRuleReasons").toString(),
          "the reading-hours rule holds again");
      assertFalse(indexing(after).path("heldFilesWaiting").asBoolean(true));
      assertEquals(savedRules, settingsRules(), "the saved reading rules are unchanged");
      assertEquals("nothing-waiting", engine.operation("core.indexing-read-now", Map.of()).path("outcome").asText());
    }
    try (var engine = new Engine("restarted")) {
      engine.awaitStatus(s -> s.path("indexAvailable").asBoolean());
      engine.ingest(later);
      var held = engine.awaitStatus(s -> indexing(s).path("heldFilesWaiting").asBoolean()
          && "PAUSED".equals(indexing(s).path("state").asText()));
      assertFalse(indexing(held).path("release").path("active").asBoolean(true), "no release survives a restart");
      Thread.sleep(3_000);
      assertEquals(0, engine.search("readnowlatermarker"), "after a restart the rule holds new files");
      assertEquals(savedRules, settingsRules());
    }
  }

  private static JsonNode indexing(JsonNode status) {
    return status.path("operating").path("indexing");
  }

  private JsonNode settingsRules() throws Exception {
    return JSON.readTree(Files.readString(directory.resolve("home/ui/settings.json")))
        .path("settings").path("indexingPause");
  }

  private final class Engine implements AutoCloseable {
    private final Path log;
    private final Process process;
    private final java.time.Instant birth;
    private final HttpClient http = HttpClient.newHttpClient();
    private int port;

    Engine(String name) throws Exception {
      Path home = Files.createDirectories(directory.resolve("home"));
      Path data = Files.createDirectories(directory.resolve("data"));
      Files.createDirectories(home.resolve("ui"));
      Files.deleteIfExists(data.resolve("runtime/manifest.json"));
      if (!Files.exists(home.resolve("ui/settings.json"))) {
        var store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, home.resolve("ui/settings.json"));
        var initial = new UiSettings();
        initial.setChatEnabled(false);
        store.replacePrepared(store.prepare(initial, new SettingsWitness(0, null)));
      }
      log = directory.resolve(name + ".log");
      var options = new java.util.ArrayList<>(List.of("--enable-native-access=ALL-UNNAMED", "-Xmx384m",
          "-Djustsearch.prod=false", "-Duser.home=" + home, "-Djustsearch.home=" + home,
          "-Djustsearch.data.dir=" + data, "-Djustsearch.index.base_path=" + data.resolve("index/default"),
          "-Djustsearch.api.port=0"));
      for (var key : List.of("justsearch.gpu.enabled", "justsearch.ai.embed.enabled", "justsearch.splade.enabled",
          "justsearch.ner.enabled", "justsearch.bgem3.enabled", "justsearch.rerank.enabled",
          "justsearch.citation.scorer.enabled")) {
        options.add("-D" + key + "=false");
      }
      options.addAll(List.of("-cp", System.getProperty("java.class.path"), "io.justsearch.ui.HeadlessApp"));
      Path arguments = directory.resolve(name + ".args");
      Files.writeString(arguments, options.stream().map(s -> "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
          .collect(java.util.stream.Collectors.joining("\n")));
      var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "@" + arguments)
          .redirectErrorStream(true).redirectOutput(log.toFile());
      var env = builder.environment();
      env.keySet().removeIf(k -> k.startsWith("JUSTSEARCH_") || List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS",
          "JAVA_OPTS", "_JAVA_OPTIONS", "DISPLAY", "WAYLAND_DISPLAY").contains(k));
      env.put("CUDA_VISIBLE_DEVICES", "-1");
      env.put("HOME", home.toString());
      process = builder.start();
      birth = process.info().startInstant().orElseThrow();
    }

    int port() throws Exception {
      if (port != 0) return port;
      var manifest = directory.resolve("data/runtime/manifest.json");
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
      while (process.isAlive() && System.nanoTime() < until) {
        if (Files.exists(manifest)) {
          try { port = JSON.readTree(Files.readString(manifest)).path("head").path("apiPort").asInt(); }
          catch (RuntimeException incomplete) { /* the publisher may still be writing */ }
          if (port > 0) return port;
        }
        Thread.sleep(10);
      }
      fail("no API port: " + Files.readString(log));
      return 0;
    }

    private HttpResponse<String> send(String path, String body) throws Exception {
      var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port() + path)).timeout(Duration.ofSeconds(15));
      return http.send(body == null ? request.GET().build()
          : request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
          HttpResponse.BodyHandlers.ofString());
    }

    JsonNode awaitStatus(Predicate<JsonNode> matches) throws Exception {
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
      JsonNode latest = null;
      do {
        var response = send("/api/status?fresh=true", null);
        if (response.statusCode() == 200) {
          latest = JSON.readTree(response.body());
          if (matches.test(latest)) return latest;
        }
        Thread.sleep(100);
      } while (process.isAlive() && System.nanoTime() < until);
      fail("status condition not reached: " + latest + "\n" + Files.readString(log));
      return latest;
    }

    JsonNode operation(String id, Map<String, Object> args) throws Exception {
      var body = JSON.writeValueAsString(Map.of("args", args, "idempotencyKey", OperationKeys.generate(Clock.systemUTC())));
      var response = send("/api/operations/" + id + "/invoke", body);
      assertEquals(200, response.statusCode(), id + " " + response.body());
      var result = JSON.readTree(response.body());
      assertTrue(result.path("success").asBoolean(), response.body());
      return result.path("structuredData");
    }

    /** The serving generation may become available just after the index reports available. */
    void ingest(Path file) throws Exception {
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
      while (true) {
        var body = JSON.writeValueAsString(Map.of("args", Map.of("paths", List.of(file.toString()), "collection", "default"),
            "idempotencyKey", OperationKeys.generate(Clock.systemUTC())));
        var response = send("/api/operations/core.ingest-files/invoke", body);
        if (response.statusCode() == 200 && JSON.readTree(response.body()).path("success").asBoolean()) return;
        if (!response.body().contains("Serving generation authority is unavailable") || System.nanoTime() > until) {
          fail("core.ingest-files " + response.statusCode() + " " + response.body());
        }
        Thread.sleep(250);
      }
    }

    int search(String query) throws Exception {
      var response = send("/api/knowledge/search", "{\"query\":\"" + query + "\",\"limit\":10}");
      assertEquals(200, response.statusCode(), response.body());
      return JSON.readTree(response.body()).path("results").size();
    }

    void awaitSearch(String query) throws Exception {
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
      while (System.nanoTime() < until) {
        if (search(query) > 0) return;
        Thread.sleep(200);
      }
      fail("search never found " + query + "\n" + Files.readString(log));
    }

    @Override public void close() throws Exception {
      try {
        if (process.isAlive()) {
          assertEquals(birth, process.info().startInstant().orElseThrow());
          process.destroy();
          if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
          }
        }
        http.close();
        if (port > 0) {
          assertThrows(java.io.IOException.class, () -> {
            try (var socket = new Socket()) { socket.connect(new InetSocketAddress("127.0.0.1", port), 200); }
          }, "owned Engine port is closed");
        }
      } finally {
        System.out.println("READNOW " + log.getFileName() + "\n" + Files.readString(log));
      }
    }
  }
}
