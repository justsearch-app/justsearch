package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.services.settings.UiSettingsStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 805 G.1 — the normal-quit leg. The shell asks Head to run its own ordered shutdown before
 * force-killing it; the ordered close deletes the runtime manifest, so a clean quit stops leaving
 * the residue that stranded the next boot's binding (R11-F2).
 */
final class LifecycleShutdownContractTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void shutdownRouteAcknowledgesWith202AndInvokesTheOrderedShutdown(@TempDir Path tmp)
      throws Exception {
    var invoked = new CountDownLatch(1);
    LocalApiServer server =
        LocalApiServer.builder(new io.justsearch.core.execution.TestEngineExecutors(),
                new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY),
                tmp.resolve("index"))
            .lifecycleShutdownAction(invoked::countDown)
            .build();
    try {
      HttpResponse<String> accepted = post(HttpClient.newHttpClient(), server);

      assertEquals(202, accepted.statusCode());
      assertTrue(JSON.readTree(accepted.body()).get("shutdownAccepted").asBoolean());
      assertTrue(
          invoked.await(2, TimeUnit.SECONDS), "the route must invoke the ordered-shutdown action");
    } finally {
      server.stop();
    }
  }

  @Test
  void acknowledgementIsWrittenBeforeTheShutdownActionRuns(@TempDir Path tmp) throws Exception {
    // The shell is waiting on this response, and the real action tears down the very server that
    // would write it — so the ack must be flushed first (mirrors commitShutdown's ordering).
    var blocked = new CountDownLatch(1);
    LocalApiServer server =
        LocalApiServer.builder(new io.justsearch.core.execution.TestEngineExecutors(),
                new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY),
                tmp.resolve("index"))
            .lifecycleShutdownAction(
                () -> {
                  try {
                    blocked.await(5, TimeUnit.SECONDS);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                })
            .build();
    try {
      HttpResponse<String> accepted = post(HttpClient.newHttpClient(), server);
      assertEquals(202, accepted.statusCode());
    } finally {
      blocked.countDown();
      server.stop();
    }
  }

  @Test
  void bridgeIsSingleInstallAndFailsClosedUntilInstalled() {
    var bridge = new LifecycleShutdownBridge();
    assertThrows(IllegalStateException.class, bridge::run);

    var calls = new AtomicInteger();
    bridge.install(calls::incrementAndGet);
    assertThrows(IllegalStateException.class, () -> bridge.install(calls::incrementAndGet));

    bridge.run();
    assertEquals(1, calls.get());
  }

  /**
   * Spec F §4.3: the body names the quit's deadline, the incarnation it addresses and a stop detail,
   * and they reach the ordered shutdown through the production bridge, so the close is budgeted to
   * finish before the supervisor's force. Reached reflectively so this compiles on the before-state.
   */
  @Test
  void quitBodyReachesTheOrderedShutdownThroughTheBridge(@TempDir Path tmp) throws Exception {
    Class<?> actionType = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
        () -> Class.forName("io.justsearch.ui.api.LifecycleShutdownAction"),
        "the lifecycle route has no way to hand its body to the ordered shutdown");
    var received = new java.util.concurrent.CompletableFuture<Object>();
    Object action = java.lang.reflect.Proxy.newProxyInstance(actionType.getClassLoader(),
        new Class<?>[] {actionType}, (proxy, method, args) -> switch (method.getName()) {
          case "shutdown" -> {
            received.complete(args[0]);
            yield null;
          }
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          case "toString" -> "recording-action";
          default -> null;
        });
    var bridge = new LifecycleShutdownBridge();
    LifecycleShutdownBridge.class.getMethod("install", actionType).invoke(bridge, action);
    LocalApiServer server =
        LocalApiServer.builder(new io.justsearch.core.execution.TestEngineExecutors(),
                new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY),
                tmp.resolve("index"))
            .lifecycleShutdownAction(bridge)
            .build();
    try {
      HttpResponse<String> accepted = post(HttpClient.newHttpClient(), server,
          "{\"reason\":\"quit\",\"deadlineAtEpochMs\":1725000008000,"
              + "\"incarnation\":\"run-q/2\",\"detail\":\"session_end\"}");
      assertEquals(202, accepted.statusCode());
      Object request = received.get(2, TimeUnit.SECONDS);
      assertEquals(1_725_000_008_000L, request.getClass().getMethod("deadlineAtEpochMs").invoke(request));
      assertEquals("run-q/2", request.getClass().getMethod("incarnation").invoke(request));
      assertEquals("session_end", request.getClass().getMethod("detail").invoke(request));
    } finally {
      server.stop();
    }
  }

  /** Today's shell posts {@code {}}; a body naming nothing, or a malformed one, still quits. */
  @Test
  void aBodyNamingNothingStillQuits(@TempDir Path tmp) throws Exception {
    var invoked = new AtomicInteger();
    var done = new CountDownLatch(3);
    LocalApiServer server =
        LocalApiServer.builder(new io.justsearch.core.execution.TestEngineExecutors(),
                new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY),
                tmp.resolve("index"))
            .lifecycleShutdownAction(() -> {
              invoked.incrementAndGet();
              done.countDown();
            })
            .build();
    try {
      var client = HttpClient.newHttpClient();
      for (String body : new String[] {"{}", "not json", "{\"deadlineAtEpochMs\":\"soon\"}"}) {
        assertEquals(202, post(client, server, body).statusCode());
      }
      assertTrue(done.await(2, TimeUnit.SECONDS));
      assertEquals(3, invoked.get());
    } finally {
      server.stop();
    }
  }

  @Test
  void moduleOwnsExactlyTheShutdownRoute() {
    var module = new LifecycleApiModule(() -> {});
    assertEquals(java.util.Set.of("/api/lifecycle/shutdown"), module.ownedRoutePaths());
    assertFalse(module.ownedRoutePaths().contains("/api/upgrade/commit-shutdown"));
  }

  private static HttpResponse<String> post(HttpClient client, LocalApiServer server)
      throws Exception {
    return post(client, server, "{}");
  }

  private static HttpResponse<String> post(HttpClient client, LocalApiServer server, String body)
      throws Exception {
    return client.send(
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + server.getPort() + "/api/lifecycle/shutdown"))
            .timeout(Duration.ofSeconds(3))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
