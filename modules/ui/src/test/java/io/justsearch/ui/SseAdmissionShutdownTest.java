/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.javalin.Javalin;
import io.justsearch.app.api.EngineProcessResources;
import io.justsearch.app.api.OperationLeaseService;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.api.stream.StreamId;
import io.justsearch.app.engine.EngineAdmissionController;
import io.justsearch.app.engine.EngineShutdownSequence;
import io.justsearch.app.engine.ShutdownRequest;
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.OccurrenceLog;
import io.justsearch.app.observability.stream.SseStreamChannel;
import io.justsearch.app.observability.stream.run.RunChannelRegistry;
import io.justsearch.app.observability.stream.run.RunChannelPolicy;
import io.justsearch.app.observability.stream.run.RunDescriptor;
import io.justsearch.app.observability.stream.run.RunId;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.ui.api.HealthEventStreamController;
import io.justsearch.ui.api.LocalApiServer;
import io.justsearch.ui.api.MultiplexedSseWriter;
import io.justsearch.ui.api.RunStreamWriter;
import io.justsearch.ui.api.SseEnvelopeWriter;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real request admission and the production shutdown order, with three retained HTTP streams. */
@Timeout(30)
final class SseAdmissionShutdownTest {
  @ParameterizedTest(name = "{displayName}: {arguments}")
  @ValueSource(strings = {"health", "snapshot", "event-only", "multiplexed", "run", "blocking"})
  void streamsRelinquishAdmissionBeforeOrderedWorkDrain(String kind, @TempDir Path directory)
      throws Exception {
    var admission = new EngineAdmissionController(8, 16, 1);
    var attached = new CountDownLatch(3);
    var rawRelease = new CountDownLatch(1);
    var bodies = new ArrayList<InputStream>();
    var channel = new SseStreamChannel(StreamId.system("admission-" + kind));
    var changes = new HealthEventChangeRegistry();
    var runs = new RunChannelRegistry();
    var run = runs.open(new RunId("admission-run"), new RunDescriptor("core.test", "", 0),
        RunChannelPolicy.conversational());
    try (var executors = new TestEngineExecutors();
        var timer = Executors.newSingleThreadScheduledExecutor();
        var stopping = Executors.newVirtualThreadPerTaskExecutor();
        var client = HttpClient.newHttpClient()) {
      var server = LocalApiServer.builder(executors,
          new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY, directory.resolve("settings.json")),
          directory.resolve("index")).engineAdmission(admission).build();
      var health = new HealthEventStreamController(executors, new ConditionStore(),
          new OccurrenceLog(16), changes, null);
      Javalin app = null;
      try {
        var appField = LocalApiServer.class.getDeclaredField("app");
        appField.setAccessible(true);
        app = (Javalin) appField.get(server);
        if (kind.equals("blocking")) {
          app.get("/api/test-stream", ctx -> {
            // The raw writer is package-private; exercise the same implementation as its routes.
            var writerType = Class.forName("io.justsearch.ui.api.SseWriter");
            var constructor = writerType.getDeclaredConstructor(
                io.justsearch.app.services.observability.HeadApiMetricCatalog.class);
            constructor.setAccessible(true);
            var writer = constructor.newInstance((Object) null);
            var headers = writerType.getDeclaredMethod("initSseHeaders",
                io.javalin.http.Context.class, String.class);
            headers.setAccessible(true);
            headers.invoke(writer, ctx, "/api/test-stream");
            var event = writerType.getDeclaredMethod("writeEvent", io.javalin.http.Context.class,
                String.class, Map.class);
            event.setAccessible(true);
            event.invoke(writer, ctx, "connected", Map.of());
            attached.countDown();
            try { rawRelease.await(); }
            catch (InterruptedException retired) { /* shutdown ends the retained request */ }
          });
        } else {
          app.sse("/api/test-stream", sse -> {
            switch (kind) {
              case "health" -> health.handle(sse);
              case "snapshot" -> SseEnvelopeWriter.attach(sse, channel, Map::of,
                  Clock.systemUTC(), timer, 30);
              case "event-only" -> SseEnvelopeWriter.attachEventOnly(sse, channel,
                  Clock.systemUTC(), timer, 30);
              case "multiplexed" -> MultiplexedSseWriter.attachAll(sse,
                  List.of(new MultiplexedSseWriter.ChannelSource(channel, Map::of)),
                  new SseStreamChannel(StreamId.system("timer")), Clock.systemUTC(), timer, 30);
              case "run" -> RunStreamWriter.attach(sse, run, timer, 30);
              default -> throw new AssertionError(kind);
            }
            attached.countDown();
          });
        }
        for (int observer = 0; observer < 3; observer++) {
          var response = client.send(HttpRequest.newBuilder(URI.create(
              "http://127.0.0.1:" + server.getPort() + "/api/test-stream"))
              .header("Accept", "text/event-stream").timeout(Duration.ofSeconds(5)).build(),
              HttpResponse.BodyHandlers.ofInputStream());
          bodies.add(response.body());
          assertEquals(200, response.statusCode());
        }
        assertTrue(attached.await(5, TimeUnit.SECONDS));
        assertEquals(3, admission.activeWorkCount(), "each HTTP stream must really retain admitted work");
        var attempts = mock(OperationAttemptRunner.class);
        when(attempts.awaitDrained(any(Duration.class))).thenReturn(true);
        var exit = new AtomicInteger(-1);
        var sequence = new EngineShutdownSequence(directory,
            HeadlessApp.orderedShutdownSteps(server, null, null, null, null, null, null, null,
                mock(OperationLeaseService.class), admission, mock(EngineProcessResources.class),
                () -> null, mock(OperationStore.class), attempts, null), exit::set);
        var shutdown = stopping.submit(() -> sequence.runAndExit(ShutdownRequest.Reason.QUIT));
        try {
          shutdown.get(12, TimeUnit.SECONDS);
          assertEquals(0, exit.get(), "open SSE must relinquish admission before work drain: "
              + sequence.resultIfRun().errors());
          assertTrue(sequence.resultIfRun().clean());
          assertEquals(0, admission.activeWorkCount());
          assertEquals(0, changes.channel().listenerCount());
          assertEquals(0, channel.listenerCount());
          assertEquals(0, run.observerCount());
        } finally {
          rawRelease.countDown();
          shutdown.get(15, TimeUnit.SECONDS);
        }
      } finally {
        rawRelease.countDown();
        for (var body : bodies) body.close();
        health.shutdown();
        if (app == null || app.jettyServer().server().isRunning()) server.stop();
      }
    } finally {
      rawRelease.countDown();
    }
  }
}
