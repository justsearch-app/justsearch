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

/** Native b8571 context error transport and classification. */
@Timeout(20)
class NativeContextOverflowTest {
  private static final List<Map<String, Object>> MESSAGE = List.of(Map.of("role", "user", "content", "ok"));
  private static final String OK = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";
  private static final String NATIVE_CONTEXT = "{\"error\":{\"code\":400,\"message\":\"request (36030 tokens) exceeds the available context size (32768 tokens), try increasing it\",\"type\":\"exceed_context_size_error\",\"n_prompt_tokens\":36030,\"n_ctx\":32768}}";

  @Test void native400ClassifierRecognizesBothInputShapesAndSse() {
    assertInstanceOf(io.justsearch.app.api.chat.ContextExceededException.class,
        ContextOverflow.http(400, NATIVE_CONTEXT));
    String unsplit = NATIVE_CONTEXT.replace(
        "request (36030 tokens) exceeds the available context size (32768 tokens), try increasing it",
        "input (36030 tokens) is larger than the max context size (32768 tokens). skipping");
    assertInstanceOf(io.justsearch.app.api.chat.ContextExceededException.class, ContextOverflow.http(400, unsplit));
    assertThrows(io.justsearch.app.api.chat.ContextExceededException.class,
        () -> ContextOverflow.checkStream(new ObjectMapper().readTree(NATIVE_CONTEXT)));
    assertThrows(io.justsearch.app.api.chat.ContextExceededException.class,
        () -> ContextOverflow.checkStream(new ObjectMapper().readTree(unsplit)));
    String unrelated = "{\"error\":{\"code\":400,\"message\":\"bad field\",\"type\":\"invalid_request_error\"}}";
    var ordinary = assertInstanceOf(LlmServerException.class, ContextOverflow.http(400, unrelated));
    assertTrue(ordinary.getMessage().contains("bad field"));
    assertInstanceOf(LlmServerException.class, ContextOverflow.http(500, NATIVE_CONTEXT));
  }

  @Test void native400InternalHttpAndPartialSseAreTyped() throws Exception {
    try (var f = new Fixture()) {
      f.release.countDown(); f.status = 400; f.body = NATIVE_CONTEXT;
      Throwable failure = assertThrows(ExecutionException.class, () -> f.chat().get()).getCause();
      assertTrue(hasCause(failure, "ContextExceededException"), failure.toString());
      f.status = 200;
      f.body = "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"
          + "data: " + NATIVE_CONTEXT + "\n\ndata: [DONE]\n\n";
      var terminal = new CompletableFuture<Throwable>();
      f.ops.stream(MESSAGE, null, 8, ignored -> {}, ignored -> {}, ignored -> {}, null,
          ignored -> terminal.complete(new AssertionError("false success")), terminal::complete, null, true, null);
      assertTrue(hasCause(terminal.get(3, TimeUnit.SECONDS), "ContextExceededException"));
    }
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
