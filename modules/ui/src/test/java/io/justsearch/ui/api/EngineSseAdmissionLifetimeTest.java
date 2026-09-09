/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.javalin.Javalin;
import io.javalin.http.sse.SseClient;
import io.javalin.http.sse.SseHandler;
import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.app.engine.EngineAdmissionController;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Real managed-SSE admission lifetime and durable foreground ownership regressions. */
@DisplayName("Engine admission lifetime for managed SSE")
@Timeout(30)
final class EngineSseAdmissionLifetimeTest {
  private static final String SSE_PATH = "/api/test/managed-sse";

  @Test
  @DisplayName("managed SSE retains admission through callback and releases after the frame")
  void managedSseRetainsAdmissionUntilCallbackFinishes() throws Exception {
    try (var fixture = new SseFixture(true)) {
      HttpRequest request =
          HttpRequest.newBuilder(fixture.uri(SSE_PATH))
              .header("Accept", "text/event-stream")
              .header("X-JustSearch-Client-Id", "sse-lifetime-test")
              .header("X-JustSearch-Client-Kind", "MCP_CLIENT")
              .timeout(Duration.ofSeconds(15))
              .POST(HttpRequest.BodyPublishers.ofString("{}"))
              .build();
      CompletableFuture<HttpResponse<InputStream>> opening =
          fixture.client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());

      HttpResponse<InputStream> response = opening.get(5, TimeUnit.SECONDS);
      assertEquals(200, response.statusCode());
      assertTrue(fixture.callbackEntered.await(5, TimeUnit.SECONDS));
      assertNull(fixture.callbackFailure.get(), "managed callback failed before it could retain work");
      assertNotNull(fixture.callbackWork.get(), "the managed callback must see the admitted work");
      assertNotNull(
          fixture.callbackRetained.get(), "the managed callback must retain work before blocking");

      // The callback is blocked after the SSE response has opened. Its retained reference keeps
      // the one-slot engine full even if the front's after filter has already run.
      assertEquals(429, fixture.postProbe().statusCode());
      assertEquals(200, fixture.getHealth().statusCode());

      fixture.callbackRelease.countDown();
      assertTrue(fixture.frameSent.await(5, TimeUnit.SECONDS));
      try (var body = response.body()) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        assertEquals("event: ready", reader.readLine());
        assertEquals("data: first", reader.readLine());
      }
      assertTrue(fixture.callbackFinished.await(5, TimeUnit.SECONDS));
      assertTrue(fixture.afterFilterReached.await(5, TimeUnit.SECONDS));
      assertTrue(
          fixture.callbackEnteredBeforeAfter.get(),
          "the front after filter must not invalidate work before the managed callback enters");
      assertEquals(200, fixture.postProbe().statusCode());
    }
  }

  @Test
  @DisplayName("managed SSE keepAlive holds admission until the actual client closes")
  void managedSseKeepAliveHoldsAdmissionUntilClientClose() throws Exception {
    try (var fixture = new SseFixture(false)) {
      HttpRequest request =
          HttpRequest.newBuilder(fixture.uri(SSE_PATH))
              .header("Accept", "text/event-stream")
              .header("X-JustSearch-Client-Id", "sse-keep-alive-test")
              .header("X-JustSearch-Client-Kind", "MCP_CLIENT")
              .timeout(Duration.ofSeconds(15))
              .POST(HttpRequest.BodyPublishers.ofString("{}"))
              .build();
      HttpResponse<InputStream> response =
          fixture.client
              .sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
              .get(5, TimeUnit.SECONDS);

      assertEquals(200, response.statusCode());
      assertTrue(fixture.callbackEntered.await(5, TimeUnit.SECONDS));
      assertNull(fixture.callbackFailure.get(), "keepAlive callback failed");
      assertNotNull(fixture.managedClient.get(), "the fixture must expose the actual managed client");
      assertNull(
          fixture.callbackRetained.get(),
          "this case must prove front ownership without a callback-side retain");
      assertEquals(
          1,
          fixture.afterFilterReached.getCount(),
          "Javalin must defer the front after filter while keepAlive keeps the stream open");
      assertTrue(fixture.callbackFinished.await(5, TimeUnit.SECONDS));

      // The callback has returned, but keepAlive keeps the HTTP stream open. Admission must remain
      // occupied until the actual managed SseClient is closed.
      assertEquals(429, fixture.postProbe().statusCode());
      assertEquals(200, fixture.getHealth().statusCode());

      fixture.managedClient.get().close();
      assertTrue(fixture.afterFilterReached.await(5, TimeUnit.SECONDS));
      assertTrue(
          fixture.callbackEnteredBeforeAfter.get(),
          "after timing must show the managed callback entered first");
      HttpResponse<String> available = null;
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (System.nanoTime() < deadline) {
        available = fixture.postProbe();
        if (available.statusCode() == 200) break;
        Thread.sleep(25L);
      }
      assertNotNull(available);
      assertEquals(200, available.statusCode(), "closing the actual SSE client releases admission");
      response.body().close();
    }
  }

  private static final class SseFixture implements AutoCloseable {
    final EngineAdmissionController admission = new EngineAdmissionController(1, 1, 1);
    final CountDownLatch callbackEntered = new CountDownLatch(1);
    final CountDownLatch callbackStarted = new CountDownLatch(1);
    final CountDownLatch callbackRelease = new CountDownLatch(1);
    final CountDownLatch frameSent = new CountDownLatch(1);
    final CountDownLatch callbackFinished = new CountDownLatch(1);
    final CountDownLatch afterFilterReached = new CountDownLatch(1);
    final AtomicBoolean callbackEnteredBeforeAfter = new AtomicBoolean();
    final AtomicReference<EngineWorkHandle> callbackWork = new AtomicReference<>();
    final AtomicReference<EngineWorkHandle> callbackRetained = new AtomicReference<>();
    final AtomicReference<SseClient> managedClient = new AtomicReference<>();
    final AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
    final java.util.concurrent.ExecutorService events = Executors.newSingleThreadExecutor();
    final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    final Javalin app;
    private final boolean blockCallback;

    SseFixture() {
      this(true);
    }

    SseFixture(boolean blockCallback) {
      this.blockCallback = blockCallback;
      app = Javalin.create(config -> config.showJavalinBanner = false);
      app.exception(
          io.javalin.http.HttpResponseException.class,
          (failure, ctx) -> ctx.status(failure.getStatus()));
      new ApiSecurityFilters(false, null, new EventBuffer(), events, null, admission, admission)
          .install(app);
      app.after(
          ctx -> {
            if (SSE_PATH.equals(ctx.path())) {
              callbackEnteredBeforeAfter.set(callbackStarted.getCount() == 0);
              afterFilterReached.countDown();
            }
          });
      app.post(SSE_PATH, new SseHandler(this::managedCallback));
      app.post("/api/test/probe", ctx -> ctx.json(Map.of("ok", true)));
      app.get("/api/health", ctx -> ctx.json(Map.of("ready", true)));
      app.start("127.0.0.1", 0);
    }

    URI uri(String path) {
      return URI.create("http://127.0.0.1:" + app.port() + path);
    }

    HttpResponse<String> postProbe() throws Exception {
      var request =
          HttpRequest.newBuilder(uri("/api/test/probe"))
              .header("X-JustSearch-Client-Id", "sse-lifetime-test")
              .header("X-JustSearch-Client-Kind", "MCP_CLIENT")
              .timeout(Duration.ofSeconds(15))
              .POST(HttpRequest.BodyPublishers.ofString("{}"))
              .build();
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> getHealth() throws Exception {
      var request =
          HttpRequest.newBuilder(uri("/api/health"))
              .timeout(Duration.ofSeconds(15))
              .GET()
              .build();
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void managedCallback(SseClient client) {
      try {
        callbackStarted.countDown();
        managedClient.set(client);
        EngineWorkHandle work = RequestEngineWork.get(client.ctx());
        callbackWork.set(work);
        if (blockCallback) callbackRetained.set(work.retain());
        callbackEntered.countDown();
        if (!blockCallback) {
          client.keepAlive();
          return;
        }
        if (!callbackRelease.await(10, TimeUnit.SECONDS)) {
          throw new IllegalStateException("callback release missing");
        }
        client.sendEvent("ready", "first");
        frameSent.countDown();
      } catch (Throwable failure) {
        callbackFailure.set(failure);
        callbackEntered.countDown();
      } finally {
        EngineWorkHandle retained = callbackRetained.getAndSet(null);
        if (retained != null) retained.close();
        if (blockCallback) client.close();
        callbackFinished.countDown();
      }
    }

    @Override
    public void close() {
      callbackRelease.countDown();
      app.stop();
      client.close();
      events.shutdownNow();
    }
  }
}
