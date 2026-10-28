/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.settings.SettingsCommitCoordinator;
import io.justsearch.app.services.settings.SettingsServiceImpl;
import io.justsearch.app.services.settings.UiSettingsStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.IntStream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

final class ExclusionSaveStateHttpTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(strings = {"input-capacity", "expanded-capacity", "syntax"})
  void refusalsLeaveNoDurableStateAndFollowingValidSaveSucceeds(String kind) throws Exception {
    checkRefusal(kind, false);
  }

  @ParameterizedTest
  @ValueSource(strings = {"input-capacity", "expanded-capacity", "syntax"})
  void productionRefusalsLeaveNoDurableStateAndFollowingValidSaveSucceeds(String kind) throws Exception {
    checkRefusal(kind, true);
  }

  private void checkRefusal(String kind, boolean production) throws Exception {
    var json = JsonMapper.builder().build();
    var settingsFile = directory.resolve("settings.json");
    var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, settingsFile);
    var initial = settings.inspect().settings();
    initial.setIndexBasePath(Files.createDirectory(directory.resolve("index")).toString());
    initial.setExcludePatterns(List.of("*.keep"));
    settings.replacePrepared(settings.prepareExact(initial,
        new SettingsWitness(1, OperationKeys.generate(Clock.systemUTC()))));
    var database = directory.resolve("operations.db");
    try (var operations = new SqliteOperationStore(database)) {
      var config = new io.justsearch.configuration.resolved.ConfigStore(
          io.justsearch.app.services.config.ConfigStoreRebuilder.prepare(settings.inspect().settings()));
      var owner = new SettingsCommitCoordinator(settings, config, () -> {},
          candidate -> io.justsearch.agent.api.registry.OperationResult.success("Committed"));
      var runner = new OperationAttemptRunnerImpl(operations, Clock.systemUTC(),
          Set.of(io.justsearch.agent.api.registry.OperationKind.SETTINGS_APPLY,
              io.justsearch.agent.api.registry.OperationKind.RECONFIGURE), owner);
      var admission = new io.justsearch.app.engine.EngineAdmissionController();
      var service = new SettingsServiceImpl(settings, runner, () -> {}, admission);
      var handlers = new io.justsearch.agent.api.registry.HandlerRegistry();
      var operation = new io.justsearch.app.services.registry.operations.CoreOperationCatalog()
          .findById(io.justsearch.app.services.registry.operations.CoreOperationCatalog.RECONFIGURE).orElseThrow();
      handlers.register(operation.id(),
          new io.justsearch.app.services.registry.operations.handlers.ReconfigureHandler(() -> service));
      var dispatcher = new io.justsearch.app.services.registry.executor.OperationExecutorImpl(runner, admission, handlers);
      var controller = new SettingsController(settings, directory, null,
          service, production ? dispatcher : null, production ? operation : null);
      var server = io.javalin.Javalin.create(configure -> {
        configure.showJavalinBanner = false;
        configure.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
      }).post("/api/settings/v2", controller::handleUpdateSettingsV2).start("127.0.0.1", 0);
      try {
        var original = settings.inspect();
        var files = durableFiles();
        List<String> invalid = switch (kind) {
          case "input-capacity" -> IntStream.range(0, 513).mapToObj(i -> "file" + i + ".txt").toList();
          case "expanded-capacity" -> IntStream.range(0, 257).mapToObj(i -> "directory" + i).toList();
          default -> List.of("**/[broken");
        };
        var response = post(server.port(), json.writeValueAsString(Map.of(
            "ui", Map.of("excludePatterns", invalid), "witness", original.witness(),
            "operationKey", OperationKeys.generate(Clock.systemUTC()))));
        assertEquals(files, durableFiles(), "refusal cannot write settings, journals or recovery markers");
        assertEquals(400, response.statusCode(), response.body());
        assertEquals("INVALID_REQUEST", json.readTree(response.body()).path("errorCode").asString());
        assertEquals(original.witness(), settings.inspect().witness());
        assertEquals(json.valueToTree(original.settings()), json.valueToTree(settings.inspect().settings()));
        assertFalse(owner.recoveryIssue().toCompletableFuture().isDone());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var statement = connection.createStatement()) {
          for (String table : List.of("operations", "operation_preparations")) {
            try (var rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
              assertTrue(rows.next());
              assertEquals(0, rows.getInt(1), table);
            }
          }
        }
        var valid = post(server.port(), json.writeValueAsString(Map.of(
            "ui", Map.of("theme", "dark", "excludePatterns", List.of("*.keep", "*.tmp")),
            "witness", original.witness(), "operationKey", OperationKeys.generate(Clock.systemUTC()))));
        assertEquals(200, valid.statusCode(), valid.body());
        assertEquals("COMPLETE", json.readTree(valid.body()).path("state").asString());
        var reloaded = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, settingsFile).inspect();
        assertEquals(2, reloaded.witness().acceptedRevision());
        assertEquals("dark", reloaded.settings().getTheme());
        assertEquals(List.of("*.keep", "*.tmp"), reloaded.settings().getExcludePatterns());
        assertFalse(owner.recoveryIssue().toCompletableFuture().isDone());
      } finally {
        server.stop();
      }
    }
  }

  private Map<String, String> durableFiles() throws Exception {
    Map<String, String> result = new TreeMap<>();
    try (var files = Files.walk(directory)) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        // SQLite's shared-memory read marks are transient coordination, not durable state.
        if (!file.getFileName().toString().endsWith("-shm")) {
          result.put(directory.relativize(file).toString(),
              Base64.getEncoder().encodeToString(Files.readAllBytes(file)));
        }
      }
    }
    return result;
  }

  private static HttpResponse<String> post(int port, String body) throws Exception {
    return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
        URI.create("http://127.0.0.1:" + port + "/api/settings/v2"))
        .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
  }
}
