/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.app.api.Mode;
import io.justsearch.app.api.SamplingParams;
import io.justsearch.app.inference.chat.GenerationTracker;
import io.justsearch.core.context.EngineContext;
import java.io.InputStream;
import java.lang.reflect.*;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.ObjectMapper;

/** V1-V5: actual proxy and internal transports over a concurrent fake llama endpoint. */
@Timeout(20)
class ManagedGenerationProxyTest {
  @Test void twoProxiesNeverOverlapTheirUpstreamExchanges() throws Exception {
    try (var f = new Fixture()) {
      var first = f.proxy("holder", new Work(true));
      assertTrue(f.started.await(3, TimeUnit.SECONDS));
      var second = f.proxy("second", new Work(true));
      Thread.sleep(150);
      assertEquals(1, f.requests.get(), "second must wait before opening the upstream connection");
      f.release.countDown(); first.get(3, TimeUnit.SECONDS); second.get(3, TimeUnit.SECONDS);
      assertEquals(1, f.maximum.get(), "at most one managed exchange is in flight");
      assertProxyErrorsUnchanged(f);
    }
  }

  @Test void proxyAndInternalChatShareTheSameLaneInBothDirections() throws Exception {
    for (boolean proxyFirst : new boolean[] {false, true}) {
      try (var f = new Fixture()) {
        Future<?> first = proxyFirst ? f.proxy("holder", new Work(true)) : f.chat("holder", new Work(false));
        assertTrue(f.started.await(3, TimeUnit.SECONDS));
        Future<?> second = proxyFirst ? f.chat("internal", new Work(true)) : f.proxy("proxy", new Work(true));
        Thread.sleep(150);
        assertEquals(1, f.requests.get(), "proxy/internal contender must wait");
        f.release.countDown(); first.get(3, TimeUnit.SECONDS); second.get(3, TimeUnit.SECONDS);
        assertEquals(1, f.maximum.get());
      }
    }
  }

  @Test void foregroundProxyPassesBackgroundWaiterBehindBackgroundInternalHolder() throws Exception {
    try (var f = new Fixture()) {
      var holder = f.chat("holder", new Work(false));
      assertTrue(f.started.await(3, TimeUnit.SECONDS));
      var background = f.chat("background", new Work(false));
      Thread.sleep(100);
      var foreground = f.proxy("foreground", new Work(true));
      Thread.sleep(100);
      f.release.countDown(); holder.get(3, TimeUnit.SECONDS); foreground.get(3, TimeUnit.SECONDS); background.get(3, TimeUnit.SECONDS);
      assertEquals(List.of("holder", "foreground", "background"), f.order);
      assertEquals(1, f.maximum.get());
    }
  }

  @Test void agedBackgroundPassesNewForegroundProxy() throws Exception {
    try (var f = new Fixture()) {
      var holder = f.chat("holder", new Work(false));
      assertTrue(f.started.await(3, TimeUnit.SECONDS));
      var background = f.chat("aged", new Work(false));
      Thread.sleep(2300);
      var foreground = f.proxy("foreground", new Work(true));
      Thread.sleep(100);
      f.release.countDown(); holder.get(3, TimeUnit.SECONDS); background.get(3, TimeUnit.SECONDS); foreground.get(3, TimeUnit.SECONDS);
      assertEquals(List.of("holder", "aged", "foreground"), f.order);
    }
  }

  @Test void durableProxyDisconnectBecomesBackgroundWhileWaiting() throws Exception {
    try (var f = new Fixture()) {
      var holder = f.chat("holder", new Work(false)); assertTrue(f.started.await(3, TimeUnit.SECONDS));
      var durable = new Work(true);
      var proxy = f.proxy("durable", durable); Thread.sleep(100);
      durable.handle.waitingClientGone();
      var foreground = f.chat("foreground", new Work(true)); Thread.sleep(100);
      f.release.countDown(); holder.get(3, TimeUnit.SECONDS); foreground.get(3, TimeUnit.SECONDS); proxy.get(3, TimeUnit.SECONDS);
      assertEquals(List.of("holder", "foreground", "durable"), f.order);
      assertTrue(durable.reason.get() == null);
    }
  }

  @Test void cancelledProxyWaiterNeverDispatches() throws Exception {
    try (var f = new Fixture()) {
      var holder = f.chat("holder", new Work(false)); assertTrue(f.started.await(3, TimeUnit.SECONDS));
      var work = new Work(true); var waiter = f.proxy("cancelled", work); Thread.sleep(100);
      work.cancel();
      try { waiter.get(2, TimeUnit.SECONDS); }
      catch (ExecutionException failure) { assertInstanceOf(java.util.concurrent.CancellationException.class, failure.getCause()); }
      assertEquals(1, f.requests.get(), "cancelled waiter was never dispatched");
      f.release.countDown(); holder.get(3, TimeUnit.SECONDS);
      f.proxy("next", new Work(true)).get(3, TimeUnit.SECONDS);
      assertEquals(List.of("holder", "next"), f.order);
    }
  }

  @Test void expiredProxyWaiterIsBusyNeverDispatchedAndDoesNotCancelParent() throws Exception {
    try (var f = new Fixture()) {
      var holder = f.chat("holder", new Work(false)); assertTrue(f.started.await(3, TimeUnit.SECONDS));
      setIfPresent(f.controller, "wallDeadline", Duration.ofMillis(100));
      var work = new Work(true);
      var request = f.context("{\"messages\":[{\"content\":\"expired\"}]}", work);
      f.controller.handleChatCompletions(request.context);
      assertEquals(429, request.status.get());
      var payload = (Map<?, ?>) request.json.get();
      assertEquals("ADMISSION_INFERENCE_BUSY", payload.get("errorCode"));
      assertEquals(false, payload.get("retrySafe"));
      verify(request.context).header("Retry-After", "1");
      assertTrue(work.reason.get() == null); assertEquals(1, f.requests.get());
      f.release.countDown(); holder.get(3, TimeUnit.SECONDS);
      f.proxy("next", new Work(true)).get(3, TimeUnit.SECONDS);
      assertEquals(List.of("holder", "next"), f.order);
    }
  }

  @Test void proxyRejectsMultipleSequencesAndSlotPins() throws Exception {
    try (var f = new Fixture()) {
      for (String extra : List.of(",\"n\":2", ",\"id_slot\":0", ",\"id_slot\":null")) {
        var ctx = f.context("{\"messages\":[]" + extra + "}", new Work(true));
        f.controller.handleChatCompletions(ctx.context);
        assertEquals(400, ctx.status.get());
        assertNotNull(ctx.json.get());
      }
      assertEquals(0, f.requests.get());
    }
  }

  private static void assertProxyErrorsUnchanged(Fixture f) throws Exception {
    f.status = 500;
    f.body = "{\"error\":{\"code\":500,\"message\":\"Context size has been exceeded.\",\"type\":\"server_error\"}}";
    var response = f.proxy("oversized", new Work(true)).get(3, TimeUnit.SECONDS);
    assertEquals(500, response.status); assertEquals(f.body, response.body);
    f.status = 200; f.body = "data: " + f.body + "\n\n";
    response = f.proxy("sse", new Work(true)).get(3, TimeUnit.SECONDS);
    assertEquals(200, response.status); assertEquals(f.body, response.body);
  }

  @Test void cancellingHandedOffProxyBodyFreesLaneBeforeDownstreamClose() throws Exception {
    try (var f = new Fixture()) {
      f.streaming = true;
      var work = new Work(true);
      var ctx = f.context("{\"messages\":[{\"content\":\"holder\"}]}", work);
      f.controller.handleChatCompletions(ctx.context);
      assertNotNull(ctx.stream.get());
      work.cancel();
      assertEquals(0, f.tracker.inFlight(), "aborted upstream exchange releases its lane and lease independently of a parked downstream");
      f.release.countDown(); ctx.stream.get().close();
      for (int i = 0; i < 12; i++) f.proxy("next", new Work(true)).get(3, TimeUnit.SECONDS);
      assertEquals(0, f.tracker.inFlight()); assertEquals(0, f.tracker.queued());
    }
  }

  @Test void finiteWallDeadlineFreesLaneForSlowProxyReader() throws Exception {
    try (var f = new Fixture()) {
      f.streaming = true;
      // The before-state is exercised unchanged when it has no wall-deadline field or watchdog.
      setIfPresent(f.controller, "wallDeadline", Duration.ofMillis(300));
      try { f.controller.getClass().getDeclaredMethod("setWallScheduler", ScheduledExecutorService.class).invoke(f.controller, f.scheduler); }
      catch (NoSuchMethodException before) { /* before-state has no wall watchdog */ }
      var ctx = f.context("{\"messages\":[{\"content\":\"holder\"}]}", new Work(true));
      f.controller.handleChatCompletions(ctx.context);
      assertNotNull(ctx.stream.get());
      Thread.sleep(700); // Deliberately never drain the handed-off response.
      assertEquals(0, f.tracker.inFlight(), "finite wall deadline must release a slow reader's generation lease");
      f.release.countDown(); ctx.stream.get().close();
      f.proxy("next", new Work(true)).get(3, TimeUnit.SECONDS);
      assertEquals(0, f.tracker.inFlight());
    }
  }

  @Test void drainingRefusesProxyWaitersAndRepeatedExchangesLeakNothing() throws Exception {
    try (var f = new Fixture()) {
      var holder = f.chat("holder", new Work(false)); assertTrue(f.started.await(3, TimeUnit.SECONDS));
      var proxy = f.proxy("waiter", new Work(true)); Thread.sleep(100);
      try (var drain = f.tracker.closeNow()) {
        var response = proxy.get(1, TimeUnit.SECONDS);
        assertEquals(503, response.status); assertEquals(1, f.requests.get());
        f.release.countDown(); holder.get(3, TimeUnit.SECONDS);
      }
      for (int i = 0; i < 12; i++) f.proxy("repeat", new Work(true)).get(3, TimeUnit.SECONDS);
      assertEquals(0, f.tracker.inFlight()); assertEquals(0, f.tracker.queued());
    }
  }

  private record Response(int status, String body) {}
  private record Request(Context context, AtomicInteger status, AtomicReference<InputStream> stream, AtomicReference<Object> json) {}
  private static void setIfPresent(Object target, String name, Object value) throws Exception {
    try { var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
    catch (NoSuchFieldException before) { /* before-state seam */ }
  }

  private static final class Work {
    final EngineWorkHandle handle = mock(EngineWorkHandle.class);
    final AtomicBoolean foreground;
    final AtomicReference<String> reason = new AtomicReference<>();
    final List<Consumer<String>> cancellations = new CopyOnWriteArrayList<>();
    Work(boolean fg) {
      foreground = new AtomicBoolean(fg);
      when(handle.retain()).thenReturn(handle);
      when(handle.cancellationReason()).thenAnswer(ignored -> Optional.ofNullable(reason.get()));
      when(handle.context()).thenAnswer(ignored -> new EngineContext(EngineContext.ClientKind.INTERNAL, "lane-test",
          Optional.empty(), Optional.empty(), "internal", "test", EngineContext.Survival.DURABLE,
          foreground.get() ? EngineContext.Urgency.FOREGROUND : EngineContext.Urgency.BACKGROUND));
      when(handle.onCancel(any())).thenAnswer(call -> {
        Consumer<String> callback = call.getArgument(0); cancellations.add(callback);
        if (reason.get() != null) callback.accept(reason.get());
        return (EngineWorkHandle.Registration) () -> cancellations.remove(callback);
      });
      doAnswer(ignored -> { foreground.set(false); return null; }).when(handle).waitingClientGone();
    }
    void cancel() { reason.set("test-cancelled"); cancellations.forEach(c -> c.accept(reason.get())); }
  }

  private static final class Fixture implements AutoCloseable {
    final CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
    final AtomicInteger requests = new AtomicInteger(), active = new AtomicInteger(), maximum = new AtomicInteger();
    final List<String> order = new CopyOnWriteArrayList<>();
    final GenerationTracker tracker = new GenerationTracker(ignored -> () -> {}, Runnable::run);
    final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    final io.justsearch.core.execution.TestEngineExecutors registry = new io.justsearch.core.execution.TestEngineExecutors();
    final HttpClient http = HttpClient.newHttpClient();
    final HttpServer server;
    final OpenAiCompatController controller;
    final Object internal;
    final Class<?> internalClass;
    volatile boolean streaming;
    volatile int status = 200;
    volatile String body = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";
    Fixture() throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(workers);
      server.createContext("/v1/chat/completions", exchange -> {
        String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String name = new ObjectMapper().readTree(request).path("messages").path(0).path("content").asText("empty");
        order.add(name); maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
        int number = requests.incrementAndGet();
        try (exchange) {
          if (streaming && number == 1) {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("data: started\n\n".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
          }
          if (number == 1) {
            started.countDown();
            try { release.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
          }
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          if (!streaming || number != 1) exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
        } finally { active.decrementAndGet(); }
      });
      server.start();
      controller = new OpenAiCompatController(http, () -> server.getAddress().getPort(), null, tracker::admit);
      // Existing internal constructor is accessed reflectively because OnlineModeOps is package-private.
      internalClass = Class.forName("io.justsearch.app.inference.OnlineModeOps");
      var registrationsClass = Class.forName("io.justsearch.app.inference.InferenceExecutorRegistrations");
      var registrationCtor = registrationsClass.getDeclaredConstructors()[0]; registrationCtor.setAccessible(true);
      Object registrations = registrationCtor.newInstance(registry);
      var ctor = Arrays.stream(internalClass.getDeclaredConstructors()).filter(c -> c.getParameterCount() == 9).findFirst().orElseThrow();
      ctor.setAccessible(true);
      internal = ctor.newInstance(registrations, http, new ObjectMapper(), (java.util.function.Supplier<Mode>) () -> Mode.ONLINE,
          (java.util.function.Supplier<Integer>) () -> server.getAddress().getPort(),
          (java.util.function.Supplier<String>) () -> "test", (java.util.function.Supplier<String>) () -> "test", null, tracker);
      try {
        Object lane = GenerationTracker.class.getMethod("lane").invoke(tracker);
        controller.getClass().getMethod("setGenerationLane", lane.getClass()).invoke(controller, lane);
      } catch (NoSuchMethodException before) { /* baseline has no shared lane; exercise unchanged real transports */ }
    }
    CompletableFuture<String> chat(String message, Work work) throws Exception {
      var method = internalClass.getDeclaredMethod("chatCompletion", List.class, int.class, SamplingParams.class,
          EngineWorkHandle.class, GenerationTracker.Kind.class); method.setAccessible(true);
      @SuppressWarnings("unchecked") var future = (CompletableFuture<String>) method.invoke(internal,
          List.of(Map.of("role", "user", "content", message)), 8, null, work.handle, GenerationTracker.Kind.GENERATE);
      return future;
    }
    Request context(String body, Work work) {
      var ctx = mock(Context.class); var status = new AtomicInteger(200);
      var stream = new AtomicReference<InputStream>(); var json = new AtomicReference<Object>();
      when(ctx.method()).thenReturn(HandlerType.POST); when(ctx.path()).thenReturn("/v1/chat/completions");
      when(ctx.bodyAsBytes()).thenReturn(body.getBytes(StandardCharsets.UTF_8)); when(ctx.headerMap()).thenReturn(Map.of());
      when(ctx.attribute(RequestEngineWork.ATTRIBUTE)).thenReturn(work.handle);
      when(ctx.attribute(RequestEngineContext.ATTRIBUTE)).thenAnswer(ignored -> work.handle.context());
      when(ctx.status(anyInt())).thenAnswer(call -> { status.set(call.getArgument(0)); return ctx; });
      when(ctx.json(any())).thenAnswer(call -> { json.set(call.getArgument(0)); return ctx; });
      when(ctx.result(any(InputStream.class))).thenAnswer(call -> { stream.set(call.getArgument(0)); return ctx; });
      return new Request(ctx, status, stream, json);
    }
    Future<Response> proxy(String name, Work work) {
      return workers.submit(() -> {
        var request = context("{\"messages\":[{\"content\":\"" + name + "\"}]}", work);
        controller.handleChatCompletions(request.context);
        String body = "";
        if (request.stream.get() != null) try (var response = request.stream.get()) {
          body = new String(response.readAllBytes(), StandardCharsets.UTF_8);
        }
        return new Response(request.status.get(), body);
      });
    }
    @Override public void close() throws Exception {
      release.countDown(); var shutdown = internalClass.getDeclaredMethod("shutdown"); shutdown.setAccessible(true); shutdown.invoke(internal);
      controller.close(); http.shutdownNow(); scheduler.shutdownNow(); server.stop(0); workers.close(); registry.close();
    }
  }
}
