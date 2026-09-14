/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.services.settings.UiSettingsStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class SettingsPublicStorageFailureHttpTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @ParameterizedTest
  @ValueSource(strings = {"RUNNING", "COMPLETE"})
  void storageFailureRetainsAcceptedIdentityWithoutClaimingAnOutcome(String transition, @TempDir Path directory)
      throws Exception {
    var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, directory.resolve("settings.json"));
    Path database = directory.resolve("operations.db");
    try (var operations = new io.justsearch.app.observability.operations.SqliteOperationStore(database)) {
      var config = new io.justsearch.configuration.resolved.ConfigStore(
          io.justsearch.app.services.config.ConfigStoreRebuilder.prepare(settings.inspect().settings()));
      AtomicInteger restarts = new AtomicInteger();
      var owner = new io.justsearch.app.services.settings.SettingsCommitCoordinator(settings, config,
          restarts::incrementAndGet, candidate -> io.justsearch.agent.api.registry.OperationResult.success("Committed"));
      var runner = new io.justsearch.app.observability.operations.OperationAttemptRunnerImpl(operations,
          Clock.systemUTC(), java.util.Set.of(io.justsearch.agent.api.registry.OperationKind.SETTINGS_APPLY,
              io.justsearch.agent.api.registry.OperationKind.RECONFIGURE), owner);
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
          var statement = connection.createStatement()) {
        statement.execute("CREATE TRIGGER refuse_public_transition BEFORE UPDATE OF state ON operations "
            + "WHEN NEW.state = '" + transition + "' BEGIN SELECT RAISE(ABORT, 'fixture'); END");
      }
      var controller = new SettingsController(settings, directory, null,
          new io.justsearch.app.services.settings.SettingsServiceImpl(settings, runner));
      var server = io.javalin.Javalin.create(configure -> {
        configure.showJavalinBanner = false;
        configure.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
      }).post("/api/settings/v2", controller::handleUpdateSettingsV2).start(0);
      try {
        String key = OperationKeys.generate(Clock.systemUTC());
        String body = "{\"ui\":{\"theme\":\"dark\"},\"witness\":{\"acceptedRevision\":0,"
            + "\"lastCommittedOperationKey\":null},\"operationKey\":\"" + key + "\"}";
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/settings/v2"))
            .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(500, response.statusCode(), response.body());
        var data = JSON.readTree(response.body());
        assertEquals("OPERATION_STORAGE_FAILED", data.path("errorCode").asText());
        assertEquals("PERMANENT", data.path("errorClass").asText());
        assertFalse(data.path("retryable").asBoolean());
        var row = operations.find(key).orElseThrow();
        assertEquals(key, data.path("operationKey").asText());
        assertEquals(row.id(), data.path("operationRecordId").asLong());
        assertFalse(data.has("state"), "a failed transition cannot project a current terminal outcome");
        assertFalse(data.has("witness"), "an unacknowledged transition cannot claim a committed receipt");
        boolean committed = transition.equals("COMPLETE");
        assertEquals(committed ? OperationState.RUNNING : OperationState.ACCEPTED, row.state());
        assertEquals(committed ? 1 : 0, settings.inspect().witness().acceptedRevision());
        assertEquals(committed ? 1 : 0, restarts.get());
      } finally {
        server.stop();
      }
    }
  }
}
