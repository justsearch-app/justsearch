/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.justsearch.app.api.Mode;
import io.justsearch.app.api.chat.GenerationDeadline;
import io.justsearch.app.inference.chat.GenerationTracker;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.ObjectMapper;

/** V1, V3, V6, V7: real internal transports, using only a fake llama HTTP endpoint. */
@Timeout(20)
class GenerationAdmissionTest {
  private static final List<Map<String, Object>> MESSAGE = List.of(Map.of("role", "user", "content", "ok"));
  private static final String CONTEXT = "{\"error\":{\"code\":500,\"message\":\"Context size has been exceeded.\",\"type\":\"server_error\"}}";
  private static final String OK = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";

  @Test void expiredExchangeNeverDispatchesAndDoesNotCancelParent() throws Exception {
    try (var f = new Fixture()) {
      var holder = f.chat();
      assertTrue(f.started.await(3, TimeUnit.SECONDS));
      var parent = org.mockito.Mockito.mock(io.justsearch.app.api.EngineWorkHandle.class);
      org.mockito.Mockito.when(parent.retain()).thenReturn(parent);
      org.mockito.Mockito.when(parent.cancellationReason()).thenReturn(java.util.Optional.empty());
      org.mockito.Mockito.when(parent.context()).thenReturn(new io.justsearch.core.context.EngineContext(
          io.justsearch.core.context.EngineContext.ClientKind.INTERNAL, "test", java.util.Optional.empty(),
          java.util.Optional.empty(), "internal", "test", io.justsearch.core.context.EngineContext.Survival.DURABLE,
          io.justsearch.core.context.EngineContext.Urgency.FOREGROUND));
      org.mockito.Mockito.when(parent.onCancel(org.mockito.ArgumentMatchers.any())).thenReturn(() -> {});
      var waiter = GenerationDeadline.within(Duration.ofMillis(100), () ->
          f.ops.chatCompletion(MESSAGE, 8, null, parent, GenerationTracker.Kind.ASSIST));
      Throwable failure = assertThrows(ExecutionException.class, () -> waiter.get(2, TimeUnit.SECONDS)).getCause();
      assertInstanceOf(io.justsearch.app.api.EngineAdmissionException.class, failure);
      assertEquals(io.justsearch.app.api.EngineAdmissionException.Reason.INFERENCE_BUSY,
          ((io.justsearch.app.api.EngineAdmissionException) failure).reason());
      assertTrue(parent.cancellationReason().isEmpty(), "optional exchange cannot cancel its parent");
      f.release.countDown(); holder.get(3, TimeUnit.SECONDS);
      assertEquals("ok", f.chat().get(3, TimeUnit.SECONDS));
      assertEquals(2, f.requests.get(), "expired exchange must never reach the server later");
      org.mockito.Mockito.verify(parent, org.mockito.Mockito.never()).cancel(org.mockito.ArgumentMatchers.any());
    }
  }

  @Test void cancelledFutureWhileWaitingNeverDispatchesLater() throws Exception {
    try (var f = new Fixture()) {
      var holder = f.chat(); assertTrue(f.started.await(3, TimeUnit.SECONDS));
      var waiter = f.chat();
      Thread.sleep(100);
      waiter.cancel(false);
      f.release.countDown(); holder.get(3, TimeUnit.SECONDS);
      assertEquals("ok", f.chat().get(3, TimeUnit.SECONDS));
      assertEquals(2, f.requests.get(), "cancellation must remove a running lock waiter too");
    }
  }

  @Test void probeBlockedPastItsWindowIsDeferredAndNeverDispatched() throws Exception {
    try (var f = new Fixture()) {
      var holder = f.chat(); assertTrue(f.started.await(3, TimeUnit.SECONDS));
      Object result;
      // The boolean probe is the before-state seam; no production admission logic is overlaid.
      try {
        var probe = OnlineModeOps.class.getDeclaredMethod("probeOneTokenOutcome", Duration.class);
        result = probe.invoke(f.ops, Duration.ofMillis(100));
      } catch (NoSuchMethodException before) {
        result = f.ops.probeOneToken(Duration.ofMillis(100)) ? "PASSED" : "FAILED";
      }
      assertEquals("DEFERRED", result.toString());
      assertEquals(0, f.probes.get());
      f.release.countDown(); holder.get(3, TimeUnit.SECONDS);
    }
  }

  @Test void contextHttp500IsTypedAndUnrelated500KeepsItsBody() throws Exception {
    try (var f = new Fixture()) {
      f.release.countDown(); f.status = 500; f.body = CONTEXT;
      Throwable failure = assertThrows(ExecutionException.class, () -> f.chat().get()).getCause();
      assertTrue(hasCause(failure, "ContextExceededException"), failure.toString());
      f.body = "{\"error\":{\"code\":500,\"message\":\"unrelated\",\"type\":\"server_error\"}}";
      failure = assertThrows(ExecutionException.class, () -> f.chat().get()).getCause();
      assertTrue(hasCause(failure, "LlmServerException"));
      assertTrue(failure.getCause().getMessage().contains("unrelated"));
      measureUncontendedAdmission(f);
    }
  }

  @Test void sseContextErrorAfterRoleContentReasoningAndToolsIsTyped() throws Exception {
    for (String delta : List.of("{\"role\":\"assistant\"}", "{\"content\":\"partial\"}",
        "{\"reasoning_content\":\"thinking\"}", "{\"tool_calls\":[{\"index\":0}]}")) {
      try (var f = new Fixture()) {
        f.release.countDown();
        f.body = "data: {\"choices\":[{\"delta\":" + delta + "}]}\n\n"
            + "data: " + CONTEXT + "\n\ndata: [DONE]\n\n";
        var terminal = new CompletableFuture<Throwable>();
        f.ops.stream(MESSAGE, null, 8, ignored -> {}, ignored -> {}, ignored -> {}, null,
            ignored -> terminal.complete(new AssertionError("false success")), terminal::complete, null, true, null);
        assertTrue(hasCause(terminal.get(3, TimeUnit.SECONDS), "ContextExceededException"), delta);
      }
    }
  }

  @Test void lifecycleClosureRefusesWaiterWithoutCountingItAsGenerating() throws Exception {
    try (var f = new Fixture()) {
      var holder = f.chat(); assertTrue(f.started.await(3, TimeUnit.SECONDS));
      var waiter = f.chat(); Thread.sleep(100);
      assertEquals(1, f.tracker.inFlight(), "waiter holds no GENERATE lease");
      try (var closed = f.tracker.closeNow()) {
        Throwable failure = assertThrows(ExecutionException.class, () -> waiter.get(1, TimeUnit.SECONDS)).getCause();
        assertTrue(hasCause(failure, "GenerationUnavailableException"), failure.toString());
        f.release.countDown(); holder.get(3, TimeUnit.SECONDS);
        assertEquals(1, f.requests.get());
      }
      assertEquals("ok", f.chat().get(3, TimeUnit.SECONDS));
      assertEquals(0, f.tracker.inFlight());
      assertEquals(0, f.tracker.queued());
    }
  }

  private static void measureUncontendedAdmission(Fixture f) throws Exception {
    f.status = 200; f.body = OK;
    Object lane = f.tracker.getClass().getMethod("lane").invoke(f.tracker);
    var acquire = lane.getClass().getMethod("acquire", io.justsearch.app.api.EngineWorkHandle.class,
        long.class, java.util.function.BooleanSupplier.class);
    long[] samples = new long[64];
    for (int step = 0; step < samples.length; step++) {
      Object permit = acquire.invoke(lane, null, System.nanoTime() + Duration.ofSeconds(3).toNanos(),
          (java.util.function.BooleanSupplier) () -> false);
      try (var held = (AutoCloseable) permit;
          var lease = f.tracker.admit(GenerationTracker.Kind.GENERATE, null)) {
        samples[step] = (long) permit.getClass().getMethod("waitNanos").invoke(permit);
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"
            + f.server.getAddress().getPort() + "/v1/chat/completions"))
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"messages\":[]}"))
            .timeout(Duration.ofSeconds(3)).build();
        assertEquals(200, f.client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode());
      }
    }
    long[] sorted = samples.clone(); java.util.Arrays.sort(sorted);
    String report = "Uncontended admission across 64 sequential simulated agent-step HTTP exchanges: p50="
        + sorted[31] + " ns; max=" + sorted[63] + " ns; samples=" + java.util.Arrays.toString(samples);
    System.out.println(report);
    java.nio.file.Files.createDirectories(java.nio.file.Path.of("build"));
    java.nio.file.Files.writeString(java.nio.file.Path.of("build/gen-lane-admission-overhead.txt"), report + "\n");
  }

  private static boolean hasCause(Throwable failure, String type) {
    for (int depth = 0; failure != null && depth < 32; depth++, failure = failure.getCause()) {
      if (failure.getClass().getSimpleName().equals(type)) return true;
    }
    return false;
  }

  private static final class Fixture implements AutoCloseable {
    final CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
    final AtomicInteger requests = new AtomicInteger(), probes = new AtomicInteger();
    final GenerationTracker tracker = new GenerationTracker(ignored -> () -> {}, Runnable::run);
    final HttpServer server;
    final ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();
    final io.justsearch.core.execution.TestEngineExecutors registry = new io.justsearch.core.execution.TestEngineExecutors();
    final HttpClient client = HttpClient.newHttpClient();
    final OnlineModeOps ops;
    volatile int status = 200;
    volatile String body = OK;
    Fixture() throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(handlers);
      server.createContext("/v1/chat/completions", exchange -> {
        exchange.getRequestBody().readAllBytes();
        if (requests.incrementAndGet() == 1) {
          started.countDown();
          try { release.await(10, TimeUnit.SECONDS); }
          catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (exchange) { exchange.getResponseBody().write(bytes); }
      });
      server.createContext("/completion", exchange -> {
        probes.incrementAndGet(); exchange.getRequestBody().readAllBytes();
        exchange.sendResponseHeaders(200, -1); exchange.close();
      });
      server.start();
      ops = new OnlineModeOps(new InferenceExecutorRegistrations(registry), client, new ObjectMapper(),
          () -> Mode.ONLINE, () -> server.getAddress().getPort(), () -> "test", () -> "test", null, tracker);
    }
    CompletableFuture<String> chat() { return ops.chatCompletion(MESSAGE, 8); }
    @Override public void close() {
      release.countDown(); ops.shutdown(); client.shutdownNow(); server.stop(0); handlers.close(); registry.close();
    }
  }
}
