package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import io.justsearch.app.engine.EngineAdmissionController;
import io.justsearch.telemetry.Telemetry;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;

/**
 * Tempdoc 374 alpha.17 R5 regression coverage for {@link OpenAiCompatController}.
 *
 * <p>Round-7 sandbox: {@code POST :8080/v1/chat/completions} returned an
 * empty body on JustSearch's documented loopback API server because no
 * handler was registered. Third-party agents using the standard OpenAI
 * shape against the published port had to discover the internal
 * llama-server port from {@code /api/inference/status} to get a working
 * response. This controller closes that gap by proxying.
 */
@DisplayName("OpenAiCompatController (tempdoc 374 alpha.17 R5)")
class OpenAiCompatControllerTest {

  @Test
  @Timeout(30)
  void streamingBodyKeepsPerContextAdmissionAfterHeaders() throws Exception {
    assertStreamingAdmission(1, 2, "first", "ADMISSION_CONTEXT_LIMIT");
  }

  @Test
  @Timeout(30)
  void streamingBodyKeepsAggregateAdmissionAfterHeaders() throws Exception {
    assertStreamingAdmission(2, 1, "second", "ADMISSION_ENGINE_LIMIT");
  }

  private void assertStreamingAdmission(
      int perContext, int aggregate, String secondClient, String refusalCode) throws Exception {
    var releaseUpstream = new CountDownLatch(1);
    var afterFilter = new CountDownLatch(1);
    var drained = new CountDownLatch(1);
    var generations = new AtomicInteger();
    var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    upstream.createContext("/v1/chat/completions", exchange -> {
      try (exchange) {
        exchange.getRequestBody().readAllBytes();
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        var output = exchange.getResponseBody();
        // Exceed the servlet buffer so the client receives headers before upstream completion.
        output.write(("data: " + "x".repeat(32768) + "\n\n").getBytes());
        output.flush();
        try {
          if (!releaseUpstream.await(10, TimeUnit.SECONDS)) return;
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return;
        }
        output.write("data: [DONE]\n\n".getBytes());
      }
    });
    var slowRequests = Executors.newSingleThreadExecutor();
    var admission = new EngineAdmissionController(perContext, aggregate, 1);
    var app = Javalin.create(config -> {
      config.showJavalinBanner = false;
      config.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
    });
    try (var http = HttpClient.newHttpClient()) {
      var controller = new OpenAiCompatController(http, () -> upstream.getAddress().getPort(), null,
          work -> {
            generations.incrementAndGet();
            return generations::decrementAndGet;
          });
      new ApiSecurityFilters(false, null, new EventBuffer(), slowRequests, null, null, admission)
          .install(app);
      app.post("/v1/chat/completions", controller::handleChatCompletions);
      app.after(ctx -> {
        var work = RequestEngineWork.get(ctx);
        if (work != null) {
          work.onCompletion(drained::countDown);
          afterFilter.countDown();
        }
      });
      upstream.start();
      app.start("127.0.0.1", 0);
      var uri = URI.create("http://127.0.0.1:" + app.port() + "/v1/chat/completions");
      var firstRequest = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
          .header("X-JustSearch-Client-Id", "first")
          .POST(HttpRequest.BodyPublishers.ofString("{\"stream\":true}")).build();
      var first = http.sendAsync(firstRequest, HttpResponse.BodyHandlers.ofInputStream())
          .get(5, TimeUnit.SECONDS);
      try (var body = first.body()) {
        assertEquals(200, first.statusCode());
        assertTrue(afterFilter.await(5, TimeUnit.SECONDS));
        assertEquals(1, admission.activeWorkCount());
        assertEquals(1, generations.get(), "the proxy must own pacing while its response remains open");
        var probe = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
            .header("X-JustSearch-Client-Id", secondClient)
            .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        var refused = http.send(probe, HttpResponse.BodyHandlers.ofString());
        assertEquals(429, refused.statusCode());
        assertTrue(refused.body().contains(refusalCode), refused.body());
        releaseUpstream.countDown();
        body.readAllBytes();
        assertTrue(drained.await(5, TimeUnit.SECONDS));
        assertEquals(0, admission.activeWorkCount());
        assertEquals(0, generations.get());
        assertEquals(200, http.send(probe, HttpResponse.BodyHandlers.ofString()).statusCode());
      }
    } finally {
      releaseUpstream.countDown();
      app.stop();
      upstream.stop(0);
      slowRequests.shutdownNow();
    }
  }

  @Test
  void cancellationClosesUpstreamButAdmissionWaitsForDownstreamClose() throws Exception {
    var admission = new EngineAdmissionController(1, 1, 1);
    var work = admission.admit(TestRequestContexts.browser(), false);
    var bodyClosed = new AtomicBoolean();
    var upstreamBody = new ByteArrayInputStream(new byte[] {1}) {
      @Override public void close() { bodyClosed.set(true); }
    };
    var http = mock(HttpClient.class);
    stubResponse(http, upstreamBody);
    var ctx = mockContext("POST", "/v1/chat/completions");
    when(ctx.attribute(RequestEngineWork.ATTRIBUTE)).thenReturn(work);
    var result = ArgumentCaptor.forClass(InputStream.class);
    when(ctx.result(result.capture())).thenReturn(ctx);
    var pacing = new AtomicInteger();
    new OpenAiCompatController(http, () -> 8081, null, owner -> {
      pacing.incrementAndGet();
      return pacing::decrementAndGet;
    }).handleChatCompletions(ctx);
    work.close();
    work.cancel("client-cancelled");
    assertTrue(bodyClosed.get(), "cancellation must close the actual upstream body");
    assertEquals(1, admission.activeWorkCount());
    assertEquals(1, pacing.get(), "the downstream still owns the foreground generation");
    result.getValue().close();
    result.getValue().close();
    assertEquals(0, admission.activeWorkCount());
    assertEquals(0, pacing.get(), "duplicate body close must release pacing exactly once");
  }

  @Test
  void modelMetadataDoesNotEnterGenerationLifetime() throws Exception {
    var http = mock(HttpClient.class);
    stubResponse(http, new ByteArrayInputStream(new byte[0]));
    var admission = new EngineAdmissionController(1, 1, 1);
    try (var work = admission.admit(TestRequestContexts.browser(), false)) {
      var ctx = mockContext("GET", "/v1/models");
      when(ctx.attribute(RequestEngineWork.ATTRIBUTE)).thenReturn(work);
      var result = ArgumentCaptor.forClass(InputStream.class);
      when(ctx.result(result.capture())).thenReturn(ctx);
      var generations = new AtomicInteger();
      new OpenAiCompatController(http, () -> 8081, null, owner -> {
        generations.incrementAndGet();
        return () -> {};
      }).handleModels(ctx);
      result.getValue().close();
      assertEquals(0, generations.get());
    }
    assertEquals(0, admission.activeWorkCount());
  }

  @Test
  void failedResponseHandoffClosesUpstreamAndRetainedAdmission() throws Exception {
    var admission = new EngineAdmissionController(1, 1, 1);
    try (var work = admission.admit(TestRequestContexts.browser(), false)) {
      var bodyClosed = new AtomicBoolean();
      var body = new ByteArrayInputStream(new byte[] {1}) {
        @Override public void close() { bodyClosed.set(true); }
      };
      var http = mock(HttpClient.class);
      stubResponse(http, body);
      var ctx = mockContext("POST", "/v1/chat/completions");
      when(ctx.attribute(RequestEngineWork.ATTRIBUTE)).thenReturn(work);
      when(ctx.result(any(InputStream.class))).thenThrow(new IllegalStateException("handoff failed"));
      assertThrows(IllegalStateException.class,
          () -> new OpenAiCompatController(http, () -> 8081, null).handleChatCompletions(ctx));
      assertTrue(bodyClosed.get());
    }
    assertEquals(0, admission.activeWorkCount());
  }

  private void stubResponse(HttpClient http, InputStream body) throws Exception {
    @SuppressWarnings("unchecked")
    HttpResponse<InputStream> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body()).thenReturn(body);
    when(response.headers()).thenReturn(java.net.http.HttpHeaders.of(Map.of(), ALLOW_ALL_HEADERS));
    org.mockito.Mockito.doReturn(response).when(http).send(any(), any());
  }

  @Test
  @Timeout(20)
  void upstreamWildcardCannotOverrideProductionCorsPolicy() throws Exception {
    var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    upstream.createContext("/v1/models", exchange -> {
      try (exchange) {
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json");
        headers.set("Cache-Control", "no-store");
        headers.set("X-Accel-Buffering", "no");
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Credentials", "true");
        headers.set("Access-Control-Allow-Private-Network", "true");
        headers.set("Access-Control-Allow-Headers", "*");
        headers.set("Access-Control-Allow-Methods", "*");
        headers.set("Access-Control-Expose-Headers", "*");
        headers.set("Access-Control-Max-Age", "999999");
        headers.set("Set-Cookie", "inference-session=untrusted; Path=/");
        headers.set("Vary", "*");
        headers.set("X-Upstream-Unlisted", "untrusted");
        byte[] body = "{\"object\":\"list\",\"data\":[]}"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
      }
    });
    var slowRequests = Executors.newSingleThreadExecutor();
    var app = Javalin.create(config -> config.showJavalinBanner = false);
    try (var http = HttpClient.newHttpClient()) {
      var controller = new OpenAiCompatController(http, () -> upstream.getAddress().getPort(), null);
      new ApiSecurityFilters(true, "test-session-token", new EventBuffer(), slowRequests, null)
          .install(app);
      app.get("/v1/models", controller::handleModels);
      upstream.start();
      app.start("127.0.0.1", 0);
      var uri = URI.create("http://127.0.0.1:" + app.port() + "/v1/models");
      for (String origin : java.util.List.of("https://foreign.example", "tauri://localhost")) {
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
            .header("Origin", origin).GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        boolean shell = origin.equals("tauri://localhost");
        assertEquals(200, response.statusCode(), "exercise the token-exempt production GET");
        assertEquals("{\"object\":\"list\",\"data\":[]}", response.body());
        assertEquals(shell ? java.util.List.of(origin) : java.util.List.of(),
            response.headers().allValues("Access-Control-Allow-Origin"));
        assertEquals(shell ? java.util.List.of("GET,POST,DELETE,OPTIONS") : java.util.List.of(),
            response.headers().allValues("Access-Control-Allow-Methods"));
        assertEquals(shell ? java.util.List.of("Deprecation, Sunset, Link, Retry-After")
                : java.util.List.of(),
            response.headers().allValues("Access-Control-Expose-Headers"));
        for (String name : java.util.List.of("Access-Control-Allow-Credentials",
            "Access-Control-Allow-Private-Network", "Access-Control-Allow-Headers",
            "Access-Control-Max-Age", "Set-Cookie", "X-Upstream-Unlisted")) {
          assertEquals(java.util.List.of(), response.headers().allValues(name), name);
        }
        assertFalse(response.headers().allValues("Vary").contains("*"));
        if (shell) assertTrue(response.headers().allValues("Vary").contains("Origin"));
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("no", response.headers().firstValue("X-Accel-Buffering").orElseThrow());
      }
    } finally {
      app.stop();
      upstream.stop(0);
      slowRequests.shutdownNow();
    }
  }

  @Test
  void connectionNominatedStreamingHeadersAreNotForwarded() throws Exception {
    var http = mock(HttpClient.class);
    @SuppressWarnings("unchecked")
    HttpResponse<InputStream> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body()).thenReturn(new ByteArrayInputStream(new byte[0]));
    when(response.headers()).thenReturn(java.net.http.HttpHeaders.of(Map.of(
        "Content-Type", java.util.List.of("text/event-stream"),
        "Connection", java.util.List.of(" cAcHe-CoNtRoL, X-Accel-Buffering "),
        "Cache-Control", java.util.List.of("no-cache"),
        "X-Accel-Buffering", java.util.List.of("no")), ALLOW_ALL_HEADERS));
    org.mockito.Mockito.doReturn(response).when(http).send(any(), any());
    var ctx = mockContext("POST", "/v1/chat/completions");
    var result = ArgumentCaptor.forClass(InputStream.class);
    when(ctx.result(result.capture())).thenReturn(ctx);
    new OpenAiCompatController(http, () -> 8081, null).handleChatCompletions(ctx);
    verify(ctx).header("Content-Type", "text/event-stream");
    verify(ctx, org.mockito.Mockito.never()).header("Connection", " cAcHe-CoNtRoL, X-Accel-Buffering ");
    verify(ctx, org.mockito.Mockito.never()).header("Cache-Control", "no-cache");
    verify(ctx, org.mockito.Mockito.never()).header("X-Accel-Buffering", "no");
    result.getValue().close();
  }

  @Test
  @Timeout(15)
  void cancellationInterruptsUpstreamBeforeResponseHeaders() throws Exception {
    var admission = new EngineAdmissionController(1, 1, 1);
    var sending = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var finished = new CountDownLatch(1);
    var interrupted = new AtomicBoolean();
    var executor = Executors.newSingleThreadExecutor();
    var http = mock(HttpClient.class);
    when(http.send(any(), any())).thenAnswer(call -> {
      sending.countDown();
      try {
        release.await();
      } catch (InterruptedException cancellation) {
        interrupted.set(true);
        throw cancellation;
      }
      throw new java.io.IOException("test exchange released");
    });
    try (var work = admission.admit(TestRequestContexts.browser(), false)) {
      var ctx = mockContext("POST", "/v1/chat/completions");
      when(ctx.attribute(RequestEngineWork.ATTRIBUTE)).thenReturn(work);
      var request = executor.submit(() -> {
        try {
          new OpenAiCompatController(http, () -> 8081, null).handleChatCompletions(ctx);
        } finally {
          finished.countDown();
        }
      });
      assertTrue(sending.await(5, TimeUnit.SECONDS));
      work.cancel("test-cancelled");
      assertTrue(finished.await(5, TimeUnit.SECONDS));
      request.get(5, TimeUnit.SECONDS);
      assertTrue(interrupted.get(), "cancel must interrupt the active upstream send");
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
    assertEquals(0, admission.activeWorkCount());
  }

  @Test
  @DisplayName("port unset (== 0) → 503 SERVICE_UNAVAILABLE without contacting upstream")
  void portUnset_returnsAiOffline() {
    HttpClient httpClient = mock(HttpClient.class);
    Telemetry telemetry = null; // ApiErrorHandler.toResponse handles null telemetry.
    OpenAiCompatController ctrl =
        new OpenAiCompatController(httpClient, () -> 0, telemetry);

    Context ctx = mockContext("POST", "/v1/chat/completions");

    ctrl.handleChatCompletions(ctx);

    verify(ctx).status(503);
    var capturedBody = ArgumentCaptor.forClass(Object.class);
    verify(ctx).json(capturedBody.capture());
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) capturedBody.getValue();
    assertNotNull(body);
    assertEquals("SERVICE_UNAVAILABLE", body.get("errorCode"));
  }

  @Test
  @DisplayName("upstream connect refused → 503 SERVICE_UNAVAILABLE")
  void upstreamConnectRefused_returnsAiOffline() throws Exception {
    HttpClient httpClient = mock(HttpClient.class);
    when(httpClient.send(any(), any())).thenThrow(new ConnectException("connect refused"));

    OpenAiCompatController ctrl =
        new OpenAiCompatController(httpClient, () -> 8081, null);

    Context ctx = mockContext("POST", "/v1/chat/completions");

    ctrl.handleChatCompletions(ctx);

    verify(ctx).status(503);
    var capturedBody = ArgumentCaptor.forClass(Object.class);
    verify(ctx).json(capturedBody.capture());
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) capturedBody.getValue();
    assertEquals("SERVICE_UNAVAILABLE", body.get("errorCode"));
  }

  @Test
  @DisplayName("upstream success → forwards status, headers (filtered), and body stream")
  void upstreamOk_streamsBodyToClient() throws Exception {
    HttpClient httpClient = mock(HttpClient.class);
    @SuppressWarnings("unchecked")
    HttpResponse<InputStream> upstreamResp = mock(HttpResponse.class);
    when(upstreamResp.statusCode()).thenReturn(200);
    when(upstreamResp.body())
        .thenReturn(
            new ByteArrayInputStream(
                "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}".getBytes()));
    when(upstreamResp.headers())
        .thenReturn(
            java.net.http.HttpHeaders.of(
                Map.of(
                    "content-type", java.util.List.of("application/json"),
                    "transfer-encoding", java.util.List.of("chunked")),
                ALLOW_ALL_HEADERS));
    // Mockito's generic inference can't bind HttpResponse<InputStream> through
    // the wildcard; doReturn bypasses the strict type check.
    org.mockito.Mockito.doReturn(upstreamResp).when(httpClient).send(any(), any());

    OpenAiCompatController ctrl =
        new OpenAiCompatController(httpClient, () -> 8081, null);

    Context ctx = mockContext("POST", "/v1/chat/completions");

    ctrl.handleChatCompletions(ctx);

    verify(ctx).status(200);
    // Hop-by-hop transfer-encoding must NOT be forwarded; content-type must.
    verify(ctx).header("content-type", "application/json");
    verify(ctx, org.mockito.Mockito.never()).header("transfer-encoding", "chunked");
    verify(ctx).result(any(InputStream.class));
  }

  /** {@code java.net.http.HttpHeaders.of} requires a header-name filter. */
  private static final java.util.function.BiPredicate<String, String> ALLOW_ALL_HEADERS =
      (n, v) -> true;

  private static Context mockContext(String method, String path) {
    Context ctx = mock(Context.class);
    when(ctx.method()).thenReturn(HandlerType.valueOf(method));
    org.mockito.Mockito.lenient().when(ctx.path()).thenReturn(path);
    org.mockito.Mockito.lenient().when(ctx.attribute(RequestEngineContext.ATTRIBUTE))
        .thenReturn(TestRequestContexts.browser());
    when(ctx.bodyAsBytes()).thenReturn(new byte[0]);
    when(ctx.headerMap()).thenReturn(Map.of("content-type", "application/json"));
    when(ctx.header("content-type")).thenReturn("application/json");
    // ApiErrorHandler.routeOf may call ctx.endpointHandlerPath().
    when(ctx.endpointHandlerPath()).thenReturn(path);
    when(ctx.status(any(int.class))).thenReturn(ctx);
    return ctx;
  }

  /** Suppress the unused-import warning for {@link Predicate} / {@link Optional}. */
  @SuppressWarnings("unused")
  private void unused(Predicate<String> p, Optional<String> o, Map<?, ?> m) {
    assertTrue(true);
  }
}
