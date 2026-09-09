/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.javalin.Javalin;
import io.justsearch.app.engine.EngineAdmissionController;
import io.justsearch.ui.api.mcp.McpProtocolHandler;
import io.justsearch.ui.api.mcp.McpToolSurface;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.json.JsonMapper;

/** Actual loopback HTTP plus the production filters, admission owner and MCP protocol encoder. */
@Timeout(30)
final class EngineAdmissionTransportTest {
  @Test
  void postSearchGetSuggestAndMcpReturnContextReasonAndHealthRemainsReachable() throws Exception {
    for (String route : List.of("/api/knowledge/search", "/api/knowledge/suggest", "/mcp")) {
      try (var fixture = new Fixture(2, 8)) {
        var first = fixture.send(route, "same", true);
        var second = fixture.send(route, "same", true);
        assertTrue(fixture.entered.await(5, TimeUnit.SECONDS));
        var refused = fixture.send(route, "same", false).get(5, TimeUnit.SECONDS);
        assertRefusal(refused, "ADMISSION_CONTEXT_LIMIT", route.equals("/mcp"));
        var health = fixture.send("/api/health", "same", false).get(5, TimeUnit.SECONDS);
        assertEquals(200, health.statusCode(), health.body());
        fixture.release.countDown();
        assertEquals(200, first.get(5, TimeUnit.SECONDS).statusCode());
        assertEquals(200, second.get(5, TimeUnit.SECONDS).statusCode());
        assertEquals(200, fixture.send(route, "same", false).get(5, TimeUnit.SECONDS).statusCode());
      }
    }
  }

  @Test
  void handlerThrownAdmissionRefusalsAreUnsafeAndEnterRouteOnce() throws Exception {
    try (var fixture = new Fixture(2, 8)) {
      for (var reason : List.of(
          io.justsearch.app.api.EngineAdmissionException.Reason.ENGINE_LIMIT,
          io.justsearch.app.api.EngineAdmissionException.Reason.FROZEN)) {
        int enteredBefore = fixture.lateAdmissionEntered.get();
        var response = fixture.sendLateAdmission(reason).get(5, TimeUnit.SECONDS);
        var body = JsonMapper.builder().build().readTree(response.body());

        assertEquals(
            reason == io.justsearch.app.api.EngineAdmissionException.Reason.FROZEN ? 503 : 429,
            response.statusCode(), response.body());
        assertFalse(body.get("retrySafe").asBoolean(), response.body());
        assertEquals(
            enteredBefore + 1,
            fixture.lateAdmissionEntered.get(),
            "a handler-thrown refusal must enter the route exactly once");
      }
    }
  }

  @Test
  void aggregateRefusalIsIndependentOfOneOrManyClientsWithFairnessNonBinding() throws Exception {
    for (boolean many : new boolean[] {false, true}) {
      try (var fixture = new Fixture(8, 2)) {
        var first = fixture.send("/api/knowledge/search", "a", true);
        var second = fixture.send("/api/knowledge/search", many ? "b" : "a", true);
        assertTrue(fixture.entered.await(5, TimeUnit.SECONDS));
        var refused = new ArrayList<HttpResponse<String>>();
        for (int i = 0; i < 3; i++) {
          refused.add(fixture.send("/api/knowledge/search", many ? "client-" + i : "a", false)
              .get(5, TimeUnit.SECONDS));
        }
        assertEquals(3, refused.size());
        for (var response : refused) assertRefusal(response, "ADMISSION_ENGINE_LIMIT", false);
        fixture.release.countDown();
        assertEquals(200, first.get(5, TimeUnit.SECONDS).statusCode());
        assertEquals(200, second.get(5, TimeUnit.SECONDS).statusCode());
      }
    }
  }

  @Test
  void manualRecoveryCapacityReachesInstalledHttpExceptionMapping() throws Exception {
    try (var fixture = new Fixture(2, 8);
        var registry = org.mockito.Mockito.spy(new io.justsearch.app.engine.DefaultEngineExecutorRegistry())) {
      var scheduler = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ScheduledExecutorService>();
      org.mockito.Mockito.doAnswer(invocation -> {
        var owner = (io.justsearch.core.execution.EngineExecutorRegistry.Registration) invocation.callRealMethod();
        var observed = org.mockito.Mockito.spy(owner);
        org.mockito.Mockito.doAnswer(open -> {
          var executor = owner.openScheduled(open.getArgument(0));
          scheduler.set(executor);
          return executor;
        }).when(observed).openScheduled(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doAnswer(close -> { owner.close(); return null; }).when(observed).close();
        return observed;
      }).when(registry).register(org.mockito.ArgumentMatchers.any());
      var bootstrap = mock(io.justsearch.app.services.worker.KnowledgeServerBootstrap.class);
      org.mockito.Mockito.when(bootstrap.workerCapability())
          .thenReturn(new io.justsearch.app.services.lifecycle.WorkerCapability());
      try (var monitor = new io.justsearch.app.services.worker.KnowledgeServerHealthMonitor(
          registry, bootstrap, 60_000)) {
        monitor.start();
        int capacity = registry.limits(io.justsearch.core.execution.EngineExecutorSpec.Kind.BACKGROUND).maxQueue();
        for (int i = 1; i < capacity; i++) {
          var _ = scheduler.get().schedule(() -> {}, 1, TimeUnit.DAYS);
        }
        assertEquals(capacity, registry.snapshot().timerRegistrations());
        var handlers = new InferenceHandlers(mock(io.justsearch.app.api.OnlineAiService.class),
            null, mock(io.justsearch.gpu.GpuCapabilitiesService.class),
            mock(io.justsearch.app.api.EnterprisePolicyService.class),
            mock(io.justsearch.app.services.settings.UiSettingsStore.class), null, null, null);
        handlers.setWorkerRecovery(monitor);
        fixture.app.post("/api/worker/restart", handlers::handleRestartWorker);
        var request = HttpRequest.newBuilder(URI.create(
            "http://127.0.0.1:" + fixture.app.port() + "/api/worker/restart"))
            .timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        var response = fixture.client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(429, response.statusCode(), response.body());
        assertEquals(String.valueOf(registry.retryAfterSeconds()), response.headers().firstValue("Retry-After").orElseThrow());
        var body = JsonMapper.builder().build().readTree(response.body());
        assertEquals("ADMISSION_ENGINE_LIMIT", body.get("errorCode").asText());
        assertFalse(body.get("retrySafe").asBoolean(), "handler entered before refusal");
      }
    }
  }

  @Test
  void upgradeFreezeKeepsItsDistinctStatusAndControlRoutesCanReleaseIt() throws Exception {
    try (var fixture = new Fixture(2, 8)) {
      var frozen = fixture.admission.freezeAdmission("upgrade");
      var mutation = fixture.send("/api/knowledge/search", "a", false).get(5, TimeUnit.SECONDS);
      assertEquals(503, mutation.statusCode(), mutation.body());
      assertTrue(mutation.body().contains("UPGRADE_PREPARING"), mutation.body());
      assertTrue(
          JsonMapper.builder().build().readTree(mutation.body()).get("retrySafe").asBoolean(),
          mutation.body());
      assertFalse(mutation.headers().firstValue("Retry-After").isPresent());
      var mcp = fixture.send("/mcp", "a", false).get(5, TimeUnit.SECONDS);
      assertEquals(503, mcp.statusCode(), mcp.body());
      var mcpBody = JsonMapper.builder().build().readTree(mcp.body());
      assertEquals("request-17", mcpBody.path("id").asText(), mcp.body());
      assertEquals(-32000, mcpBody.path("error").path("code").asInt());
      assertEquals("UPGRADE_PREPARING", mcpBody.path("error").path("data").path("errorCode").asText());
      var notification = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + fixture.app.port() + "/mcp"))
          .timeout(Duration.ofSeconds(5))
          .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"method\":\"ping\"}"))
          .build();
      var silent = fixture.client.send(notification, HttpResponse.BodyHandlers.ofString());
      assertEquals(503, silent.statusCode());
      assertEquals("", silent.body(), "a refused MCP notification must remain silent");
      var read = fixture.send("/api/knowledge/suggest", "a", false).get(5, TimeUnit.SECONDS);
      assertEquals(503, read.statusCode());
      assertTrue(read.body().contains("UPGRADE_PREPARING"), read.body());
      assertTrue(
          JsonMapper.builder().build().readTree(read.body()).get("retrySafe").asBoolean(),
          read.body());
      assertEquals(
          200, fixture.send("/api/upgrade/status", "a", false).get(5, TimeUnit.SECONDS).statusCode());
      assertEquals(200, fixture.send("/api/health", "a", false).get(5, TimeUnit.SECONDS).statusCode());
      fixture.admission.releaseAdmission(frozen.preparationId());
      assertEquals(
          200, fixture.send("/api/knowledge/search", "a", false).get(5, TimeUnit.SECONDS).statusCode());
    }
  }

  private static void assertRefusal(HttpResponse<String> response, String code, boolean mcp) {
    assertEquals(429, response.statusCode(), response.body());
    assertEquals("2", response.headers().firstValue("Retry-After").orElseThrow());
    var body = JsonMapper.builder().build().readTree(response.body());
    if (mcp) {
      assertEquals("request-17", body.get("id").asText());
      assertEquals(code, body.get("error").get("data").get("errorCode").asText());
    } else {
      assertEquals(code, body.get("errorCode").asText());
      assertEquals("TRANSIENT", body.get("errorClass").asText());
      assertTrue(body.get("retryable").asBoolean());
      assertTrue(body.get("retrySafe").asBoolean(), response.body());
    }
  }

  private static final class Fixture implements AutoCloseable {
    final EngineAdmissionController admission;
    final CountDownLatch entered = new CountDownLatch(2);
    final CountDownLatch release = new CountDownLatch(1);
    final AtomicInteger lateAdmissionEntered = new AtomicInteger();
    final java.util.concurrent.ExecutorService events = Executors.newSingleThreadExecutor();
    final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    final Javalin app;

    Fixture(int perContext, int aggregate) {
      admission = new EngineAdmissionController(perContext, aggregate, 2);
      app = Javalin.create(config -> config.showJavalinBanner = false);
      // Same body-preserving HTTP exception mapping as LocalApiServer's composition.
      app.exception(io.javalin.http.HttpResponseException.class, (failure, ctx) -> ctx.status(failure.getStatus()));
      new ApiSecurityFilters(false, null, new EventBuffer(), events, null, admission, admission).install(app);
      var protocol = new McpProtocolHandler(mock(McpToolSurface.class), List.of());
      app.before(ctx -> {
        if ("true".equals(ctx.queryParam("block")) && RequestEngineWork.get(ctx) != null) {
          entered.countDown();
          if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test release missing");
        }
      });
      app.post("/api/knowledge/search", ctx -> ctx.json(java.util.Map.of("ok", true)));
      app.get("/api/knowledge/suggest", ctx -> ctx.json(java.util.Map.of("ok", true)));
      app.get("/api/health", ctx -> ctx.json(java.util.Map.of("ready", true)));
      app.get(
          "/api/upgrade/status",
          ctx -> ctx.json(java.util.Map.of("frozen", admission.snapshot().admissionFrozen())));
      app.post(
          "/api/test/late-admission",
          ctx -> {
            lateAdmissionEntered.incrementAndGet();
            var reason =
                "FROZEN".equals(ctx.queryParam("reason"))
                    ? io.justsearch.app.api.EngineAdmissionException.Reason.FROZEN
                    : io.justsearch.app.api.EngineAdmissionException.Reason.ENGINE_LIMIT;
            throw new io.justsearch.app.api.EngineAdmissionException(reason, 2);
          });
      app.post("/mcp", protocol::handlePost);
      app.start("127.0.0.1", 0);
    }

    java.util.concurrent.CompletableFuture<HttpResponse<String>> send(
        String route, String clientId, boolean block) {
      var builder =
          HttpRequest.newBuilder(
                  URI.create("http://127.0.0.1:" + app.port() + route + "?block=" + block))
              .timeout(Duration.ofSeconds(15))
              .header("X-JustSearch-Client-Id", clientId)
              .header("X-JustSearch-Client-Kind", "MCP_CLIENT");
      if (route.equals("/mcp")) builder.POST(HttpRequest.BodyPublishers.ofString(
          "{\"jsonrpc\":\"2.0\",\"id\":\"request-17\",\"method\":\"ping\"}"));
      else if (route.equals("/api/knowledge/search")) builder.POST(HttpRequest.BodyPublishers.ofString("{}"));
      else builder.GET();
      return client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    java.util.concurrent.CompletableFuture<HttpResponse<String>> sendLateAdmission(
        io.justsearch.app.api.EngineAdmissionException.Reason reason) {
      var request =
          HttpRequest.newBuilder(
                  URI.create(
                      "http://127.0.0.1:"
                          + app.port()
                          + "/api/test/late-admission?reason="
                          + reason.name()))
          .timeout(Duration.ofSeconds(15))
          .header("X-JustSearch-Client-Id", "late-admission-test")
          .header("X-JustSearch-Client-Kind", "MCP_CLIENT")
          .POST(HttpRequest.BodyPublishers.ofString("{}"))
          .build();
      return client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    @Override public void close() {
      release.countDown();
      app.stop();
      client.close();
      events.shutdownNow();
    }
  }
}
