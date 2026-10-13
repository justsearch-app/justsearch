/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.javalin.Javalin;
import io.justsearch.app.engine.EngineShutdownSequence;
import io.justsearch.app.engine.ShutdownRequest;
import io.justsearch.app.api.stream.StreamId;
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.OccurrenceLog;
import io.justsearch.app.observability.stream.SseStreamChannel;
import io.justsearch.core.execution.TestEngineExecutors;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real HTTP connections exercise each shared transport kind, including a blocked raw body. */
@Timeout(25)
final class SseTransportOwnerShutdownTest {
  @ParameterizedTest(name = "{displayName}: {arguments}")
  @ValueSource(strings = {"health", "snapshot", "event-only", "multiplexed", "run", "blocking",
      "blocked-snapshot", "blocked-multiplexed"})
  void normalShutdownEndsOpenStreamsBeforeGracefulDrain(String kind, @TempDir Path data)
      throws Exception {
    var transport = Javalin.create(config -> config.jetty.modifyServer(LocalApiServer::configureTransportShutdown));
    // The exact check also runs on the before-state, where there is no transport owner.
    try {
      Class.forName("io.justsearch.ui.api.SseAttachmentOwner")
          .getDeclaredMethod("install", Javalin.class).invoke(null, transport);
    } catch (ClassNotFoundException missingOwner) { /* before-state */ }
    var channel = new SseStreamChannel(StreamId.system("shutdown-" + kind));
    var changes = new HealthEventChangeRegistry(Executors.newVirtualThreadPerTaskExecutor());
    var runs = new io.justsearch.app.observability.stream.run.RunChannelRegistry();
    var run = runs.open(new io.justsearch.app.observability.stream.run.RunId("shutdown-run"),
        new io.justsearch.app.observability.stream.run.RunDescriptor("core.test", "", 0),
        io.justsearch.app.observability.stream.run.RunChannelPolicy.conversational());
    var rawRelease = new CountDownLatch(1);
    var rawExited = new CountDownLatch(1);
    var attached = new CountDownLatch(1);
    try (var executors = new TestEngineExecutors();
        var timer = Executors.newSingleThreadScheduledExecutor();
        var stopping = Executors.newVirtualThreadPerTaskExecutor();
        var client = HttpClient.newHttpClient()) {
      var health = new HealthEventStreamController(executors, new ConditionStore(),
          new OccurrenceLog(16), changes, null);
      java.util.function.Supplier<Map<String, Object>> blockedSnapshot = () -> {
        attached.countDown();
        try { rawRelease.await(); }
        catch (InterruptedException retired) { /* transport shutdown ends synchronous setup */ }
        finally { rawExited.countDown(); }
        return Map.of();
      };
      if (kind.equals("blocking")) {
        transport.get("/stream", ctx -> {
          new SseWriter(null).initSseHeaders(ctx, "/stream");
          new SseWriter(null).writeEvent(ctx, "connected", Map.of());
          attached.countDown();
          try { rawRelease.await(); }
          catch (InterruptedException retired) { /* the stream owner ends this request */ }
          finally { rawExited.countDown(); }
        });
      } else {
        transport.sse("/stream", sse -> {
          if (kind.startsWith("blocked-")) sse.sendEvent("opening", Map.of());
          switch (kind) {
            case "health" -> health.handle(sse);
            case "snapshot" -> SseEnvelopeWriter.attach(sse, channel, Map::of,
                Clock.systemUTC(), timer, 30);
            case "event-only" -> SseEnvelopeWriter.attachEventOnly(sse, channel,
                Clock.systemUTC(), timer, 30);
            case "multiplexed" -> MultiplexedSseWriter.attachAll(sse,
                List.of(new MultiplexedSseWriter.ChannelSource(channel, Map::of),
                    new MultiplexedSseWriter.ChannelSource(changes.channel(), null)),
                new SseStreamChannel(StreamId.system("timer")), Clock.systemUTC(), timer, 30);
            case "run" -> RunStreamWriter.attach(sse, run, timer, 30);
            case "blocked-snapshot" -> SseEnvelopeWriter.attach(sse, channel, blockedSnapshot,
                Clock.systemUTC(), timer, 30);
            case "blocked-multiplexed" -> MultiplexedSseWriter.attachAll(sse,
                List.of(new MultiplexedSseWriter.ChannelSource(channel, blockedSnapshot)),
                new SseStreamChannel(StreamId.system("timer")), Clock.systemUTC(), timer, 30);
            default -> throw new AssertionError(kind);
          }
          attached.countDown();
        });
      }
      transport.start(0);
      var response = client.send(HttpRequest.newBuilder(URI.create(
          "http://127.0.0.1:" + transport.port() + "/stream"))
          .header("Accept", "text/event-stream").timeout(Duration.ofSeconds(10)).build(),
          HttpResponse.BodyHandlers.ofInputStream());
      assertEquals(200, response.statusCode());
      assertTrue(attached.await(5, TimeUnit.SECONDS));
      var exit = new AtomicInteger(-1);
      var sequence = new EngineShutdownSequence(data, List.of(
          new EngineShutdownSequence.Step("http", reason -> {
            LocalApiServer.stopHttpTransport(transport); return null;
          }), new EngineShutdownSequence.Step(EngineShutdownSequence.INDEX_HALF_STEP,
              reason -> "GRACEFUL")), exit::set);
      var shutdown = stopping.submit(() -> sequence.runAndExit(ShutdownRequest.Reason.QUIT));
      var body = response.body();
      try (body) {
        if (kind.equals("blocking") || kind.startsWith("blocked-")) {
          assertTrue(rawExited.await(1, TimeUnit.SECONDS),
              "transport shutdown must release the blocking SSE body or setup");
        }
        assertTrue(waitForShutdown(shutdown), "open " + kind + " SSE must end before the drain budget");
        assertEquals(0, exit.get());
        assertTrue(sequence.resultIfRun().clean());
        assertEquals(0, channel.listenerCount());
        assertEquals(0, changes.channel().listenerCount());
        assertEquals(0, run.observerCount());
        assertTrue(transport.jettyServer().server().isStopped());
      } finally {
        rawRelease.countDown();
        health.shutdown();
        shutdown.get(10, TimeUnit.SECONDS);
      }
    } finally {
      rawRelease.countDown();
      if (transport.jettyServer().server().isRunning()) transport.stop();
    }
  }

  private static boolean waitForShutdown(java.util.concurrent.Future<?> shutdown) throws Exception {
    try { shutdown.get(3, TimeUnit.SECONDS); return true; }
    catch (java.util.concurrent.TimeoutException didNotRetireStreams) { return false; }
  }

  @Test
  void realTransportFailureSelectsUncleanNonzeroExit(@TempDir Path data) {
    var transport = org.mockito.Mockito.mock(Javalin.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    org.mockito.Mockito.when(transport.stop()).thenThrow(new IllegalStateException("real close failure"));
    var exit = new AtomicInteger(-1);
    var sequence = new EngineShutdownSequence(data, List.of(
        new EngineShutdownSequence.Step("http", reason -> {
          LocalApiServer.stopHttpTransport(transport); return null;
        }), new EngineShutdownSequence.Step(EngineShutdownSequence.INDEX_HALF_STEP,
            reason -> "GRACEFUL")), exit::set);
    sequence.runAndExit(ShutdownRequest.Reason.QUIT);
    assertEquals(1, exit.get());
    assertFalse(sequence.resultIfRun().clean());
    assertEquals(List.of("http"), sequence.resultIfRun().errors());
  }
}
