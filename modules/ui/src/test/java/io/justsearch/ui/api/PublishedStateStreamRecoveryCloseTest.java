/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.javalin.Javalin;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.engine.EngineRoot;
import io.justsearch.app.services.HeadAssembly;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.KnowledgeServerConfig;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.server.KnowledgeServer;
import io.justsearch.ui.json.Jackson3JsonMapper;
import io.justsearch.ui.runtime.RuntimeManifestPublisher;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The app window's server-sent event streams, connected through the real admission filters to
 * their real route handlers, while the Engine's own index recovery close runs against a mocked
 * physical index owner. A stream that only reads published state must not make the close refuse;
 * work that really uses the index still must.
 */
@Timeout(60)
final class PublishedStateStreamRecoveryCloseTest {
  static final String SHELL = "/api/shell-events/stream";

  @TempDir Path directory;

  @ParameterizedTest(name = "{arguments}")
  @ValueSource(strings = {SHELL, "/api/indexing-jobs/stream", "/api/health/events/stream",
      "/api/condition-recovery-index/stream", "/api/intent/stream",
      "/api/advisory/operation-completed/stream", "/api/advisory/health-recoverable/stream",
      "/api/advisory/authorization-pending/stream", "/api/action-ledger/stream",
      "/api/operation-history/stream", "/api/runtime-context/stream",
      "/api/runtime/manifest/stream", "/api/diagnostic-channels/engine-log/stream",
      "/api/metrics/worker.job_queue.depth/stream",
      "/api/metrics/worker.documents.indexed.rate_per_sec/stream",
      "/api/metrics/gpu.utilization.percent/stream",
      "/api/metrics/gpu.memory.utilization.percent/stream", "/infra/capabilities/stream"})
  void connectedPublishedStateStreamDoesNotRefuseTheRecoveryClose(String route) throws Exception {
    try (var fixture = new Fixture(directory)) {
      var stream = fixture.open(route);
      assertEquals(1, fixture.root.admission().activeWorkCount(), route + " holds its admission");
      var server = fixture.installServer();
      assertDoesNotThrow(fixture.root::closeForRecovery,
          "a connected " + route + " stream must not make the recovery close refuse");
      verify(server).closeForRecovery();
      assertFalse(stream.ended(), route + " stays connected across the recovery");
      assertEquals(1, fixture.root.admission().activeWorkCount(), route + " keeps its admission");
    }
  }

  @Test
  void severalWindowsReconnectsAndRepeatedRecoveriesDoNotBringTheRefusalBack() throws Exception {
    try (var fixture = new Fixture(directory)) {
      var windows = new ArrayList<Stream>();
      for (int i = 0; i < 3; i++) windows.add(fixture.open(SHELL));
      windows.add(fixture.open("/api/indexing-jobs/stream"));
      windows.add(fixture.open("/infra/capabilities/stream"));
      assertEquals(5, fixture.root.admission().activeWorkCount());

      // A window opens while the first recovery is closing the index.
      var openedDuringRecovery = new CompletableFuture<Stream>();
      var first = fixture.installServer();
      doAnswer(ignored -> {
        openedDuringRecovery.complete(fixture.open(SHELL));
        return null;
      }).when(first).closeForRecovery();
      assertDoesNotThrow(fixture.root::closeForRecovery);
      windows.add(openedDuringRecovery.get(10, TimeUnit.SECONDS));
      assertEquals(6, fixture.root.admission().activeWorkCount());

      // A window reconnects between recoveries.
      windows.removeFirst().close();
      windows.add(fixture.open(SHELL));

      for (int recovery = 0; recovery < 3; recovery++) {
        var server = fixture.installServer();
        assertDoesNotThrow(fixture.root::closeForRecovery, "recovery " + (recovery + 2));
        verify(server).closeForRecovery();
      }
      for (var window : windows) assertFalse(window.ended());
    }
  }

  @Test
  void workThatUsesTheIndexStillRefusesUntilReleasedWhileAStreamIsConnected() throws Exception {
    try (var fixture = new Fixture(directory)) {
      fixture.open(SHELL);
      var server = fixture.installServer();
      var work = fixture.root.admission().admit(context(), false);
      var refused = assertThrows(IllegalStateException.class, fixture.root::closeForRecovery);
      assertEquals("Live Engine work retains the index owner", refused.getMessage());
      verify(server, never()).closeForRecovery();
      work.close();
      assertDoesNotThrow(fixture.root::closeForRecovery);
      verify(server).closeForRecovery();
    }
  }

  /** Spellings the router serves from a marked route are the same published-state stream. */
  @ParameterizedTest(name = "{arguments}")
  @ValueSource(strings = {"/api/shell-events/stream/", "/api/shell-events/stream?since=",
      "/api/indexing-jobs/stream/", "/infra/capabilities/stream/"})
  void routerAcceptedSpellingOfAMarkedRouteIsClassifiedAsPublishedState(String route)
      throws Exception {
    try (var fixture = new Fixture(directory)) {
      fixture.open(route);
      assertEquals(1, fixture.root.admission().activeWorkCount(), route + " is admitted as work");
      var server = fixture.installServer();
      assertDoesNotThrow(fixture.root::closeForRecovery, route + " is published-state work");
      verify(server).closeForRecovery();
    }
  }

  /** Spellings the router does not serve are not admitted at all. */
  @ParameterizedTest(name = "{arguments}")
  @ValueSource(strings = {"/API/shell-events/stream", "//api/shell-events/stream",
      "/api/shell-events/stream/extra", "/api/shell-events/streams"})
  void unservedLookalikeIsNotAdmitted(String route) throws Exception {
    try (var fixture = new Fixture(directory)) {
      assertEquals(404, fixture.send("GET", route).statusCode(), route);
      fixture.awaitActive(0);
    }
  }

  /**
   * A HEAD request, and GET routes whose paths extend, prefix or embed a marked route, stay
   * ordinary work that refuses the recovery close while held.
   */
  @ParameterizedTest(name = "{0} {1}")
  @org.junit.jupiter.params.provider.CsvSource({
      "HEAD, /api/shell-events/stream",
      "HEAD, /infra/capabilities/stream",
      "GET, /api/shell-events/stream-held",
      "GET, /api/shell-events/stream/held",
      "GET, /api/shell-events",
      "GET, /test/api/shell-events/stream"})
  void lookalikeRouteIsClassifiedAsOrdinaryWork(String method, String route) throws Exception {
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    io.javalin.http.Handler held = ctx -> {
      entered.countDown();
      assertTrue(release.await(10, TimeUnit.SECONDS), "held request was not released");
      ctx.status(200);
    };
    try (var fixture = new Fixture(directory, app -> {
      if ("HEAD".equals(method)) app.head(route, held, ActivityRole.OBSERVATION);
      else app.get(route, held, ActivityRole.OBSERVATION);
    })) {
      var response = fixture.sendAsync(method, route);
      try {
        assertTrue(entered.await(10, TimeUnit.SECONDS), method + " " + route + " never reached its handler");
        assertEquals(1, fixture.root.admission().activeWorkCount());
        var server = fixture.installServer();
        var refused = assertThrows(IllegalStateException.class, fixture.root::closeForRecovery,
            method + " " + route + " is ordinary work");
        assertEquals("Live Engine work retains the index owner", refused.getMessage());
        verify(server, never()).closeForRecovery();
      } finally {
        release.countDown();
      }
      assertEquals(200, response.get(15, TimeUnit.SECONDS).statusCode());
      fixture.awaitActive(0);
    }
  }

  /**
   * A marked stream that ends exceptionally releases its slot and mark exactly once: independent
   * physical work is still counted (no undercount), and nothing is counted after it is released.
   */
  @ParameterizedTest(name = "{arguments}")
  @ValueSource(strings = {"before-first-frame", "after-first-frame"})
  void markedStreamEndingExceptionallyLeavesNoMarkBehind(String when) throws Exception {
    try (var fixture = new Fixture(directory, app -> { })) {
      // A separate server for the failing stream: the real route module owns the marked paths.
      var failing = Javalin.create(config -> {
        config.showJavalinBanner = false;
        config.jsonMapper(new Jackson3JsonMapper());
      });
      var events = Executors.newSingleThreadExecutor();
      try {
        SseAttachmentOwner.install(failing);
        new ApiSecurityFilters(false, null, new EventBuffer(), events, null, null,
            fixture.root.admission()).install(failing);
        failing.sse(SHELL, client -> {
          if ("after-first-frame".equals(when)) client.sendEvent("frame", "first");
          throw new IllegalStateException("stream attachment failed");
        }, ActivityRole.OBSERVATION);
        failing.start("127.0.0.1", 0);
        var work = fixture.root.admission().admit(context(), false);
        try (var http = HttpClient.newHttpClient()) {
          for (int i = 0; i < 3; i++) {
            var response = http.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + failing.port() + SHELL))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(response.statusCode() == 200 || response.statusCode() == 500,
                "status " + response.statusCode());
          }
        }
        fixture.awaitActive(1);
        var server = fixture.installServer();
        assertThrows(IllegalStateException.class, fixture.root::closeForRecovery,
            "the independent work is still counted after the failed streams");
        verify(server, never()).closeForRecovery();
        work.close();
        fixture.awaitActive(0);
        assertDoesNotThrow(fixture.root::closeForRecovery, "no mark or slot leaked");
        verify(server).closeForRecovery();
        var later = fixture.root.admission().admit(context(), false);
        try {
          fixture.installServer();
          assertThrows(IllegalStateException.class, fixture.root::closeForRecovery,
              "later work is counted exactly");
        } finally {
          later.close();
        }
      } finally {
        failing.stop();
        events.shutdownNow();
      }
    }
  }

  @Test
  void streamConnectedBeforeAFailedInitialStartDoesNotPreventItsCleanupOrRetry() throws Exception {
    var previous = io.justsearch.configuration.resolved.ConfigStore.globalOrNull();
    // The Engine captures its exact start configuration before the injected failure.
    io.justsearch.configuration.resolved.ConfigStore.setGlobal(
        new io.justsearch.configuration.resolved.ConfigStore(
            io.justsearch.configuration.resolved.ResolvedConfig.builder().contributeEnvRegistry().build()));
    try (var fixture = new Fixture(directory)) {
      fixture.open(SHELL);
      // Two injected boot failures: the first attempt's cleanup must let the second attempt run.
      var bootstrap = new KnowledgeServerBootstrap(fixture.root.executors(),
          new KnowledgeServerConfig(false, directory, directory, directory, 5000, 2000, 3, 2000,
              1000, 300000, 100, 0, 2),
          null, fixture.root.components(), fixture.root.indexComponent(), fixture.root, false,
          fixture.root.publicationLock());
      var failure = assertThrows(IOException.class, () -> bootstrap.startWithRetry(2, 0));
      assertEquals("Injected test-only Engine index boot failure", failure.getMessage(),
          () -> "cause: " + failure.getCause());
      for (var suppressed : failure.getSuppressed()) {
        assertFalse(String.valueOf(suppressed.getMessage()).contains("Startup cleanup refused"),
            "the first attempt's cleanup was refused: " + suppressed);
      }
      Field remaining = KnowledgeServerBootstrap.class.getDeclaredField("bootFaultsRemaining");
      remaining.setAccessible(true);
      assertEquals(0, ((java.util.concurrent.atomic.AtomicInteger) remaining.get(bootstrap)).get(),
          "the retry after the cleaned-up first attempt ran");
      assertEquals(1, fixture.root.admission().activeWorkCount(), "the stream is still connected");
    } finally {
      if (previous != null) {
        io.justsearch.configuration.resolved.ConfigStore.setGlobal(previous);
      } else {
        var clear = io.justsearch.configuration.resolved.ConfigStore.class.getDeclaredMethod("clearGlobal");
        clear.setAccessible(true);
        clear.invoke(null);
      }
    }
  }

  private static EngineContext context() {
    return new EngineContext(EngineContext.ClientKind.MCP_CLIENT, "agent", Optional.of("session"),
        Optional.empty(), "UNTRUSTED", "MCP", EngineContext.Survival.INTERACTIVE,
        EngineContext.Urgency.FOREGROUND);
  }

  /** One connected stream: its first frame has arrived, so its real handler has attached. */
  record Stream(InputStream body, CompletableFuture<Void> end) implements AutoCloseable {
    boolean ended() { return end.isDone(); }

    @Override public void close() throws IOException { body.close(); }
  }

  /** The real admission filters and route module over an Engine root with a mocked index owner. */
  static final class Fixture implements AutoCloseable {
    final EngineRoot root;
    final Javalin app;
    private final AutoCloseable processResources;
    private final TestEngineExecutors headExecutors = new TestEngineExecutors();
    private final HeadAssembly head;
    private final ResourceApiModule resources;
    private final java.util.concurrent.ExecutorService events = Executors.newSingleThreadExecutor();
    private final java.util.concurrent.ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<Stream> streams = new ArrayList<>();
    private final String previousDataDir;

    Fixture(Path directory) throws Exception {
      this(directory, app -> {});
    }

    /** {@code extraRoutes} registers test routes beside the real route module. */
    Fixture(Path directory, java.util.function.Consumer<Javalin> extraRoutes) throws Exception {
      previousDataDir = System.getProperty("justsearch.data.dir");
      System.setProperty("justsearch.data.dir", directory.resolve("data").toString());
      root = new EngineRoot(mock(OperationStore.class), mock(OperationAttemptRunner.class), 1_000, 100);
      processResources = root.processResources();
      var operations = mock(OperationStore.class);
      when(operations.subscribeCompletions(any())).thenReturn(() -> {});
      head = HeadAssembly.bootForSearchPortOnly(operations, mock(OperationAttemptRunner.class),
          headExecutors, (intent, context) -> {
            throw new AssertionError("a published-state stream reached search");
          }, () -> {}, root.admission());
      resources = new ResourceApiModule(head, () -> {},
          new RuntimeManifestPublisher(directory.resolve("runtime")), directory.resolve("index"),
          root.admission());
      app = Javalin.create(config -> {
        config.showJavalinBanner = false;
        config.jsonMapper(new Jackson3JsonMapper());
      });
      SseAttachmentOwner.install(app);
      new ApiSecurityFilters(false, null, new EventBuffer(), events, null, null, root.admission(),
          resources::admissionOperation).install(app);
      resources.register(app);
      extraRoutes.accept(app);
      app.start("127.0.0.1", 0);
    }

    Stream open(String route) throws Exception {
      HttpResponse<InputStream> response = client.sendAsync(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + route))
              .header("Accept", "text/event-stream").GET().build(),
          HttpResponse.BodyHandlers.ofInputStream()).get(10, TimeUnit.SECONDS);
      assertEquals(200, response.statusCode(), route);
      var firstFrame = new CompletableFuture<Void>();
      var end = new CompletableFuture<Void>();
      readers.execute(() -> {
        try (var reader = new BufferedReader(
            new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
          String line;
          while ((line = reader.readLine()) != null) {
            if (line.startsWith("data:")) firstFrame.complete(null);
          }
        } catch (IOException closed) {
          // The client closed its own body.
        } finally {
          firstFrame.completeExceptionally(new IllegalStateException(route + " ended before a frame"));
          end.complete(null);
        }
      });
      firstFrame.get(10, TimeUnit.SECONDS);
      var stream = new Stream(response.body(), end);
      streams.add(stream);
      return stream;
    }

    HttpResponse<String> send(String method, String route) throws Exception {
      return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + route))
          .method(method, HttpRequest.BodyPublishers.noBody()).timeout(Duration.ofSeconds(10)).build(),
          HttpResponse.BodyHandlers.ofString());
    }

    CompletableFuture<HttpResponse<String>> sendAsync(String method, String route) {
      return client.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + route))
          .method(method, HttpRequest.BodyPublishers.noBody()).timeout(Duration.ofSeconds(15)).build(),
          HttpResponse.BodyHandlers.ofString());
    }

    void awaitActive(int expected) throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (root.admission().activeWorkCount() != expected && System.nanoTime() < deadline) {
        Thread.sleep(5);
      }
      assertEquals(expected, root.admission().activeWorkCount());
    }

    KnowledgeServer installServer() throws Exception {
      var server = mock(KnowledgeServer.class);
      when(server.awaitClosed(anyLong())).thenReturn(true);
      Field field = EngineRoot.class.getDeclaredField("server");
      field.setAccessible(true);
      field.set(root, server);
      return server;
    }

    @Override
    public void close() throws Exception {
      try {
        for (var stream : streams) {
          try { stream.close(); } catch (IOException ignored) { /* already closed */ }
        }
        app.stop();
        resources.shutdown();
        head.close();
        readers.shutdownNow();
        events.shutdownNow();
        client.close();
        headExecutors.close();
      } finally {
        try {
          root.close();
        } finally {
          processResources.close();
          if (previousDataDir == null) System.clearProperty("justsearch.data.dir");
          else System.setProperty("justsearch.data.dir", previousDataDir);
        }
      }
    }
  }
}
