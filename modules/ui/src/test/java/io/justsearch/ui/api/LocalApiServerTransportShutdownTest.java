/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.javalin.Javalin;
import io.justsearch.app.engine.EngineShutdownSequence;
import io.justsearch.app.engine.EngineSupervisionPolicy;
import io.justsearch.app.engine.ShutdownRequest.Reason;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(25)
class LocalApiServerTransportShutdownTest {
  @Test
  void selectorTeardownThatNeverReturnsCannotConsumeWholeGrace() throws Exception {
    var release = new CountDownLatch(1);
    var entered = new CountDownLatch(1);
    var transport = org.mockito.Mockito.mock(Javalin.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    org.mockito.Mockito.when(transport.stop()).thenAnswer(call -> {
      entered.countDown();
      awaitIgnoringInterrupts(release);
      return transport;
    });
    try (var executors = new io.justsearch.core.execution.TestEngineExecutors()) {
      var owner = executors.register(new io.justsearch.core.execution.EngineExecutorSpec(
          "test.http-stop", io.justsearch.core.execution.EngineExecutorSpec.Kind.BACKGROUND,
          io.justsearch.core.execution.EngineExecutorSpec.Mode.PLATFORM, 1, 1, 1));
      var executor = owner.open(Thread.ofPlatform().daemon().factory());
      owner.close();
      assertTrue(executor.isShutdown(), "stop must survive executor closure");
      try {
        long started = System.nanoTime();
        LocalApiServer.stopHttpTransport(transport);
        assertEquals(0L, entered.getCount());
        assertEquals(1L, release.getCount(), "teardown must still be wedged when caller returns");
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            < EngineSupervisionPolicy.GRACEFUL_STOP_DEADLINE_MS);
      } finally { release.countDown(); }
    }
  }

  @Test
  void neverStartedTransportStopIsNoOp() {
    var transport = Javalin.create(c -> c.jetty.modifyServer(LocalApiServer::configureTransportShutdown));
    var stoppingEvents = new java.util.concurrent.atomic.AtomicInteger();
    transport.events(events -> events.serverStopping(stoppingEvents::incrementAndGet));
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> LocalApiServer.stopHttpTransport(transport));
    assertTrue(transport.jettyServer().server().isStopped());
    assertEquals(0, stoppingEvents.get());
  }

  @Test
  void alreadyStoppedTransportStopIsNoOp() {
    var transport = Javalin.create(c -> c.jetty.modifyServer(LocalApiServer::configureTransportShutdown));
    var stoppingEvents = new java.util.concurrent.atomic.AtomicInteger();
    transport.events(events -> events.serverStopping(stoppingEvents::incrementAndGet));
    transport.start(0);
    try {
      LocalApiServer.stopHttpTransport(transport);
      assertTrue(transport.jettyServer().server().isStopped());
      assertEquals(1, stoppingEvents.get());
      org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> LocalApiServer.stopHttpTransport(transport));
      assertEquals(1, stoppingEvents.get(), "already-stopped transport must not stop again");
    } finally {
      if (transport.jettyServer().server().isRunning()) transport.stop();
    }
  }

  @Test
  void wedgedRequestStillReachesOrderedHeadIndexCloseAndExit(@TempDir Path data) throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var transport = Javalin.create(c -> c.jetty.modifyServer(LocalApiServer::configureTransportShutdown));
    transport.get("/wedge", ctx -> {
      entered.countDown();
      awaitIgnoringInterrupts(release);
      ctx.result("released");
    });
    transport.start(0);
    try (var client = HttpClient.newHttpClient()) {
      var request = client.sendAsync(HttpRequest.newBuilder(
          URI.create("http://127.0.0.1:" + transport.port() + "/wedge")).build(),
          HttpResponse.BodyHandlers.ofString());
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var closed = new ArrayList<String>();
        var sequence = new EngineShutdownSequence(data, List.of(
            new EngineShutdownSequence.Step("local-api", reason -> {
              LocalApiServer.stopHttpTransport(transport);
              closed.add("http"); return null;
            }),
            new EngineShutdownSequence.Step("head-assembly", reason -> {
              closed.add("head"); return null;
            }),
            new EngineShutdownSequence.Step(EngineShutdownSequence.INDEX_HALF_STEP, reason -> {
              closed.add("index"); return "GRACEFUL";
            })), code -> closed.add("exit"));
        long started = System.nanoTime();
        sequence.runAndExit(Reason.HANG);
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            < EngineSupervisionPolicy.GRACEFUL_STOP_DEADLINE_MS);
        assertEquals(List.of("http", "head", "index", "exit"), closed);
        assertEquals(1L, release.getCount(), "handler must still be wedged at ordered completion");
      } finally {
        release.countDown();
        request.cancel(true);
      }
    } finally {
      release.countDown();
      if (transport.jettyServer().server().isRunning()) transport.stop();
    }
  }

  @Test
  void ordinaryInflightRequestDrainsBeforeStopReturns() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var stopStarted = new CountDownLatch(1);
    var transport = Javalin.create(c -> c.jetty.modifyServer(LocalApiServer::configureTransportShutdown));
    transport.events(events -> events.serverStopping(stopStarted::countDown));
    transport.get("/drain", ctx -> { entered.countDown(); release.await(); ctx.result("done"); });
    transport.start(0);
    try (var client = HttpClient.newHttpClient()) {
      var response = client.sendAsync(HttpRequest.newBuilder(
          URI.create("http://127.0.0.1:" + transport.port() + "/drain")).build(), HttpResponse.BodyHandlers.ofString());
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      var stopping = java.util.concurrent.CompletableFuture.runAsync(
          () -> LocalApiServer.stopHttpTransport(transport));
      try {
        assertTrue(stopStarted.await(5, TimeUnit.SECONDS));
        org.junit.jupiter.api.Assertions.assertFalse(stopping.isDone());
        release.countDown();
        assertEquals("done", response.get(5, TimeUnit.SECONDS).body());
        stopping.get(EngineSupervisionPolicy.GRACEFUL_STOP_DEADLINE_MS, TimeUnit.MILLISECONDS);
      } finally { release.countDown(); }
    } finally {
      release.countDown();
      if (transport.jettyServer().server().isRunning()) transport.stop();
    }
  }

  private static void awaitIgnoringInterrupts(CountDownLatch release) {
    boolean done = false;
    while (!done) {
      try { release.await(); done = true; }
      catch (InterruptedException ignored) { /* Fault survives Jetty's pool interruption. */ }
    }
  }
}
