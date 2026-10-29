/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;

import io.javalin.Javalin;
import io.justsearch.agent.api.registry.HandlerRegistry;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.settings.IndexingPausePolicy;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.engine.EngineAdmissionController;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.registry.executor.OperationExecutorImpl;
import io.justsearch.app.services.registry.operations.CoreOperationCatalog;
import io.justsearch.app.services.registry.operations.handlers.IndexingRulesHandler;
import io.justsearch.app.services.runtimestate.RuntimeSpecStore;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.core.scheduling.IndexingSchedule;
import io.justsearch.ui.json.Jackson3JsonMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** D1/D4/S38.2: real invoke route, schema binder, handler and recorded dispatcher refusals. */
@Timeout(30)
final class IndexingRulesRefusalHttpTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  @TempDir Path directory;

  @Test
  void wrongTypeBatteryNamesFieldAndPreservesSettings() throws Exception {
    refused("{\"schedule\":null,\"pauseOnBattery\":\"true\"}", "pauseOnBattery");
  }

  @Test
  void missingBatteryNamesFieldAndPreservesSettings() throws Exception {
    refused("{\"schedule\":null}", "pauseOnBattery");
  }

  @Test
  void missingScheduleNamesFieldAndPreservesSettings() throws Exception {
    refused("{\"pauseOnBattery\":false}", "schedule");
  }

  @Test
  void unknownMemberNamesFieldAndPreservesSettings() throws Exception {
    refused("{\"schedule\":null,\"pauseOnBattery\":false,\"unexpected\":true}", "unexpected");
  }

  @Test
  void nestedHandlerRefusalStillNamesFieldAndPreservesSettings() throws Exception {
    refused("""
        {"schedule":{"enabled":true,"days":true,"startMinute":600,"endMinute":660},
         "pauseOnBattery":false}
        """, "schedule.days");
  }

  private void refused(String arguments, String field) throws Exception {
    var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,
        directory.resolve("settings.json"));
    var initial = new UiSettings();
    initial.setIndexingPause(new IndexingPausePolicy(true, Long.MAX_VALUE, "USER_PAUSED",
        new IndexingSchedule(true, List.of(1, 7), 600, 660), true));
    settings.replacePrepared(settings.prepare(initial, new SettingsWitness(0, null)));
    var saved = settings.inspect();
    byte[] savedBytes = Files.readAllBytes(settings.settingsPath());
    var app = Javalin.create(config -> {
      config.showJavalinBanner = false;
      config.jsonMapper(new Jackson3JsonMapper());
    });
    try (var store = new SqliteOperationStore(directory.resolve("operations.db"));
        var client = HttpClient.newHttpClient()) {
      var runner = new OperationAttemptRunnerImpl(store, Clock.systemUTC(), Set.of());
      var spec = new RuntimeSpecStore(settings, runner);
      var handlers = new HandlerRegistry();
      handlers.register(CoreOperationCatalog.SET_INDEXING_RULES, new IndexingRulesHandler(() -> spec));
      var dispatcher = new OperationExecutorImpl(runner, new EngineAdmissionController(4, 4, 4), handlers);
      var controller = new OperationsController(List.of(new CoreOperationCatalog()), dispatcher);
      app.post("/api/operations/{id}/invoke", controller::handleInvoke).start("127.0.0.1", 0);
      var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port()
          + "/api/operations/core.set-indexing-rules/invoke"))
          .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString("{\"args\":" + arguments + ",\"idempotencyKey\":\""
              + OperationKeys.generate(Clock.systemUTC()) + "\"}")).build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(400, response.statusCode(), response.body());
      var body = JSON.readTree(response.body());
      assertFalse(body.path("success").asBoolean(), response.body());
      assertEquals("BAD_REQUEST", body.path("errorCode").asText(), response.body());
      assertTrue(body.path("message").asText().contains(field), "refusal message names " + field + ": " + response.body());
      assertTrue(body.path("errorDetails").toString().contains(field),
          "refusal details name " + field + ": " + response.body());
      assertEquals(saved.witness(), settings.inspect().witness(), "refused rules keep the accepted revision");
      assertEquals(JSON.valueToTree(saved.settings()), JSON.valueToTree(settings.load()));
      assertArrayEquals(savedBytes, Files.readAllBytes(settings.settingsPath()), "refusal never rewrites saved settings");
    } finally {
      app.stop();
    }
  }
}
