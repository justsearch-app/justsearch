/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.javalin.Javalin;
import io.justsearch.agent.api.registry.HandlerRegistry;
import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.engine.EngineAdmissionController;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.registry.executor.OperationExecutorImpl;
import io.justsearch.app.services.registry.operations.CoreOperationCatalog;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.core.scheduling.IndexingHold;
import io.justsearch.core.scheduling.IndexingSchedule;
import io.justsearch.ipc.MigrationStatus;
import io.justsearch.ipc.StatusResponse;
import io.justsearch.ui.json.Jackson3JsonMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S6c-1/2/5/7 (C2'): "read waiting files now" through the real invoke route, catalog, executor and
 * recorded dispatcher: started, joined, nothing-waiting and not-held outcomes; a keyed replay
 * returns the recorded receipt and never installs the release again.
 */
@Timeout(60)
final class ReadWaitingFilesHttpTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String ID = "core.indexing-read-now";
  @TempDir Path directory;

  private static Object call(Object target, String name, Object... args) {
    return assertDoesNotThrow(() -> {
      for (var method : target.getClass().getMethods()) {
        if (method.getName().equals(name) && method.getParameterCount() == args.length) {
          return method.invoke(target, args);
        }
      }
      throw new NoSuchMethodException(name);
    }, "IndexingHold." + name);
  }

  private final class Route implements AutoCloseable {
    final IndexingHold hold = new IndexingHold();
    final AtomicLong ready = new AtomicLong();
    private final Javalin app = Javalin.create(config -> {
      config.showJavalinBanner = false;
      config.jsonMapper(new Jackson3JsonMapper());
    });
    private final SqliteOperationStore store;
    private final HttpClient http = HttpClient.newHttpClient();

    Route() throws Exception {
      hold.setLocalTime(() -> new IndexingHold.LocalMinute(1, 500));
      var client = mock(KnowledgeClient.class);
      when(client.indexingHold()).thenReturn(hold);
      when(client.getStatus(any())).thenAnswer(ignored -> StatusResponse.newBuilder()
          .setMigration(MigrationStatus.newBuilder().setPendingReadyJobsCount(ready.get())).build());
      store = new SqliteOperationStore(directory.resolve("operations-" + System.nanoTime() + ".db"));
      var runner = new OperationAttemptRunnerImpl(store, Clock.systemUTC(), Set.of());
      var handlers = new HandlerRegistry();
      var ref = new OperationRef(ID);
      var handler = (OperationHandler) assertDoesNotThrow(() -> Class.forName(
          "io.justsearch.app.services.registry.operations.handlers.ReadWaitingFilesHandler")
          .getConstructor(Supplier.class).newInstance((Supplier<KnowledgeClient>) () -> client),
          "the read-now handler exists");
      handlers.register(ref, handler);
      var dispatcher = new OperationExecutorImpl(runner, new EngineAdmissionController(4, 4, 4), handlers);
      var controller = new OperationsController(List.of(new CoreOperationCatalog()), dispatcher);
      app.post("/api/operations/{id}/invoke", controller::handleInvoke).start("127.0.0.1", 0);
    }

    JsonNode invoke(String key) throws Exception {
      var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port()
          + "/api/operations/" + ID + "/invoke"))
          .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString("{\"args\":{},\"idempotencyKey\":\"" + key + "\"}")).build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode(), response.body());
      var body = JSON.readTree(response.body());
      assertTrue(body.path("success").asBoolean(), response.body());
      return body.path("structuredData");
    }

    JsonNode invoke() throws Exception {
      return invoke(OperationKeys.generate(Clock.systemUTC()));
    }

    @Override public void close() throws Exception {
      app.stop();
      http.close();
      store.close();
    }
  }

  @Test
  void startedThenJoinedAndAReplayNeverReinstalls() throws Exception {
    try (var route = new Route()) {
      route.hold.setPolicy(false, null, null, new IndexingSchedule(true, List.of(1), 600, 660), false);
      route.ready.set(3);
      String key = OperationKeys.generate(Clock.systemUTC());
      assertEquals("started", route.invoke(key).path("outcome").asText());
      assertFalse((Boolean) call(route.hold, "ingestionHeld"), "file reading is released");
      assertEquals(List.of("SCHEDULE"), route.hold.snapshot().reasons(), "the rules are unchanged");
      assertEquals("joined", route.invoke().path("outcome").asText(), "another window joins it");
      call(route.hold, "endRelease", call(route.hold, "activeRelease"));
      assertTrue((Boolean) call(route.hold, "ingestionHeld"), "drained");
      route.invoke(key);
      assertTrue((Boolean) call(route.hold, "ingestionHeld"),
          "a lost response's keyed replay is answered from the record and never installs it again");
    }
  }

  @Test
  void nothingWaitingAndNotHeldInstallNothing() throws Exception {
    try (var route = new Route()) {
      route.hold.setOnBattery(true);
      route.hold.setPolicy(false, null, null, null, true);
      route.ready.set(0);
      assertEquals("nothing-waiting", route.invoke().path("outcome").asText(), "a stale click finds nothing");
      assertTrue((Boolean) call(route.hold, "ingestionHeld"));

      route.ready.set(2);
      route.hold.setOnBattery(false);
      var free = route.invoke();
      assertEquals("not-held", free.path("outcome").asText());
      assertEquals("not-held", free.path("reason").asText());

      route.hold.setOnBattery(true);
      route.hold.pause(null, "USER_PAUSED");
      var paused = route.invoke();
      assertEquals("not-held", paused.path("outcome").asText(), "manual Pause is not overridden");
      assertEquals("paused", paused.path("reason").asText());
      assertTrue((Boolean) call(route.hold, "ingestionHeld"));

      route.hold.pause(null, "SETTINGS_RECOVERY");
      var recovery = route.invoke();
      assertEquals("not-held", recovery.path("outcome").asText());
      assertEquals("recovery", recovery.path("reason").asText());
      assertTrue((Boolean) call(route.hold, "ingestionHeld"));
    }
  }
}
