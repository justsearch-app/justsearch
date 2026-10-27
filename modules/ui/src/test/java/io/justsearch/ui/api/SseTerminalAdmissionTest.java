/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;

import io.javalin.Javalin;
import io.javalin.http.sse.SseClient;
import io.javalin.http.sse.SseHandler;
import io.justsearch.app.api.stream.StreamId;
import io.justsearch.app.engine.EngineAdmissionController;
import io.justsearch.app.observability.stream.SseStreamChannel;
import io.justsearch.app.services.lease.OperationLeaseServiceImpl;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual managed writer/future failure after a real HTTP client departs before its first frame. */
@Timeout(25)
final class SseTerminalAdmissionTest {
  @ParameterizedTest(name = "departedClientExceptionalFirstPrefixReleasesOnlyItsRequest({0})")
  @ValueSource(booleans = {false, true})
  void departedClientExceptionalFirstPrefixReleasesOnlyItsRequest(boolean multiplex) throws Exception {
    var admission = new EngineAdmissionController(4, 8, 1);
    var leases = OperationLeaseServiceImpl.processLocal();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var finished = new CountDownLatch(1);
    var failure = new AtomicReference<Throwable>();
    var managed = new AtomicReference<SseClient>();
    var first = new SseStreamChannel(StreamId.surface("terminal-first"));
    var second = new SseStreamChannel(StreamId.surface("terminal-second"));
    var heartbeat = new SseStreamChannel(StreamId.system("terminal-heartbeat"));
    var app = Javalin.create(config -> {
      config.showJavalinBanner = false;
      config.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
    });
    try (var events = Executors.newSingleThreadExecutor();
        var timer = Executors.newSingleThreadScheduledExecutor();
        var client = HttpClient.newHttpClient();
        var independent = admission.admit(TestRequestContexts.internal(), false)) {
      SseAttachmentOwner.install(app);
      new ApiSecurityFilters(false, null, new EventBuffer(), events, null, leases, admission).install(app);
      java.util.function.Supplier<Map<String, Object>> snapshot = () -> {
        entered.countDown();
        try {
          if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("snapshot gate not released");
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new AssertionError(interrupted);
        }
        return Map.of("fixture", "first-real-frame");
      };
      app.post("/api/test/departed-sse", new SseHandler(sse -> {
        managed.set(sse);
        try {
          if (multiplex) MultiplexedSseWriter.attachAll(sse, List.of(
              new MultiplexedSseWriter.ChannelSource(first, snapshot),
              new MultiplexedSseWriter.ChannelSource(second, Map::of)), heartbeat, Clock.systemUTC(), timer, 1);
          else SseEnvelopeWriter.attach(sse, first, snapshot, Clock.systemUTC(), timer, 1);
        } catch (RuntimeException | Error problem) {
          failure.set(problem);
          throw problem;
        } finally {
          finished.countDown();
        }
      }));
      app.get("/api/test/ordinary", ctx -> ctx.result("ordinary"));
      app.start("127.0.0.1", 0);
      int baseline = admission.activeWorkCount();
      try (var socket = new Socket("127.0.0.1", app.port())) {
        socket.setSoTimeout(5000);
        socket.getOutputStream().write(("POST /api/test/departed-sse HTTP/1.1\r\nHost: 127.0.0.1:"
            + app.port() + "\r\nAccept: text/event-stream\r\nContent-Length: 2\r\n\r\n{}")
            .getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        var headers = new StringBuilder();
        while (!headers.toString().endsWith("\r\n\r\n")) {
          int next = socket.getInputStream().read();
          assertNotEquals(-1, next, "actual SSE headers arrive before any frame");
          headers.append((char) next);
          assertTrue(headers.length() < 16384);
        }
        assertTrue(headers.toString().startsWith("HTTP/1.1 200"), headers.toString());
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertEquals(baseline + 1, admission.activeWorkCount());
        assertEquals(1, leases.snapshot().activeLeases().size());
        socket.setSoLinger(true, 0); // Real TCP RST, not a direct SseClient.close shortcut.
      }
      release.countDown();
      assertTrue(finished.await(5, TimeUnit.SECONDS));
      assertTrue(managed.get().terminated(), "the actual writer observes the departed client's failed prefix");
      assertNotNull(failure.get(), "require the genuinely exceptional managed attachment path, not normal completion");
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while ((admission.activeWorkCount() != baseline || !leases.snapshot().activeLeases().isEmpty())
          && System.nanoTime() < until) Thread.sleep(5);
      assertEquals(baseline, admission.activeWorkCount(), "only departed request work releases");
      assertTrue(leases.snapshot().activeLeases().isEmpty(), "exceptional SSE POST lease releases");
      assertEquals(0, first.listenerCount());
      assertEquals(0, second.listenerCount());
      assertTrue(independent.cancellationReason().isEmpty());
      var next = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port()
              + "/api/test/ordinary")).timeout(Duration.ofSeconds(5)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(200, next.statusCode(), "same server remains usable without a quota change or restart");
    } finally {
      release.countDown();
      app.stop();
      first.close();
      second.close();
      heartbeat.close();
    }
  }
}
