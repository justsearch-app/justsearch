/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import io.justsearch.app.api.*;
import io.justsearch.app.api.chat.ContextExceededException;
import io.justsearch.app.inference.chat.GenerationTracker;
import io.justsearch.app.services.conversation.*;
import io.justsearch.app.services.conversation.shapes.FreeChatShape;
import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.core.context.EngineContext;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.ObjectMapper;

/** Real HTTP transport to the verbatim b8571 response recorded by verify-gl A8. */
@Timeout(20)
class NativeContextOverflowTest {
  private static final String BODY = "{\"error\":{\"code\":400,\"message\":\"request (36030 tokens) exceeds the available context size (32768 tokens), try increasing it\",\"type\":\"exceed_context_size_error\",\"n_prompt_tokens\":36030,\"n_ctx\":32768}}";
  private static final EngineContext CONTEXT = new EngineContext(EngineContext.ClientKind.INTERNAL,
      "native-overflow-test", Optional.empty(), Optional.empty(), "internal", "test",
      EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.FOREGROUND);

  @Test void native400ReachesInternalChatAsReadableContextExceeded() throws Exception {
    try (var f = new Fixture()) {
      var engine = new ConversationEngine(CoreConversationShapeCatalog.catalog(), List.of(),
          PromptContributorRegistry.of(List.of()), ContextInjectorRegistry.of(List.of(new io.justsearch.app.services.conversation.spi.ExternalContextInjector(() -> f.ai),
              io.justsearch.app.services.conversation.spi.UserPromptInjector.INSTANCE)),
          StreamConsumerRegistry.of(List.of(new io.justsearch.app.services.conversation.spi.MemoryExtractionConsumer(
              mock(io.justsearch.agent.api.memory.MemoryStore.class)))),
          IterationControllerRegistry.of(List.of(io.justsearch.agent.api.conversation.SingleHopController.INSTANCE)), () -> f.ai);
      var events = new ArrayList<SseEvent>();
      engine.run(FreeChatShape.ID, Map.of("prompt", "large input"), Audience.USER, events::add, CONTEXT);
      var error = events.stream().filter(e -> e.name().equals("error")).findFirst();
      assertTrue(error.isPresent(), events.toString());
      assertEquals("CONTEXT_EXCEEDED", error.orElseThrow().payload().get("errorCode"));
      assertTrue(error.orElseThrow().payload().get("error").toString().contains("context window"));
      assertFalse(events.stream().anyMatch(e -> e.name().equals("done")));
      assertEquals(1, f.attempts.get());
    }
  }

  @Test void native400AgentMakesExactlyOneUpstreamAttempt() throws Exception {
    try (var f = new Fixture()) {
      var session = new AgentSession(new ArrayList<>(List.of(Map.of("role", "user", "content", "large input"))),
          8000, CONTEXT);
      var caller = new AgentLlmCaller(f.ai, AgentTelemetry.noop(), new AgentContextCompressor(false, 200, 1));
      RuntimeException failure = assertThrows(RuntimeException.class,
          () -> caller.callLlmWithRetries(session, List.of(), ignored -> {}));
      assertEquals(1, f.attempts.get(), "overflow must not be retried upstream");
      assertTrue(ContextExceededException.causedBy(failure), failure.toString());
    }
  }

  private static final class Fixture implements AutoCloseable {
    final AtomicInteger attempts = new AtomicInteger();
    final ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();
    final io.justsearch.core.execution.TestEngineExecutors registry = new io.justsearch.core.execution.TestEngineExecutors();
    final HttpClient http = HttpClient.newHttpClient();
    final HttpServer server;
    final Object ops;
    final Class<?> type;
    final OnlineAiService ai = mock(OnlineAiService.class);
    Fixture() throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(handlers);
      server.createContext("/v1/chat/completions", exchange -> {
        exchange.getRequestBody().readAllBytes(); attempts.incrementAndGet();
        byte[] bytes = BODY.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(400, bytes.length);
        try (exchange) { exchange.getResponseBody().write(bytes); }
      });
      server.start();
      type = Class.forName("io.justsearch.app.inference.OnlineModeOps");
      var registrations = Class.forName("io.justsearch.app.inference.InferenceExecutorRegistrations").getDeclaredConstructors()[0];
      registrations.setAccessible(true);
      var ctor = Arrays.stream(type.getDeclaredConstructors()).filter(c -> c.getParameterCount() == 9).findFirst().orElseThrow();
      ctor.setAccessible(true);
      ops = ctor.newInstance(registrations.newInstance(registry), http, new ObjectMapper(),
          (java.util.function.Supplier<Mode>) () -> Mode.ONLINE,
          (java.util.function.Supplier<Integer>) () -> server.getAddress().getPort(),
          (java.util.function.Supplier<String>) () -> "test", (java.util.function.Supplier<String>) () -> "test", null,
          new GenerationTracker(ignored -> () -> {}, Runnable::run));
      var stream = type.getDeclaredMethod("stream", List.class, List.class, int.class,
          Consumer.class, Consumer.class, Consumer.class, Consumer.class, Consumer.class, Consumer.class,
          SamplingParams.class, boolean.class, EngineWorkHandle.class);
      stream.setAccessible(true);
      when(ai.isAvailable()).thenReturn(true);
      doAnswer(call -> {
        OnlineAiService.StreamRequest request = call.getArgument(0);
        OnlineAiService.StreamSink sink = call.getArgument(1);
        stream.invoke(ops, request.messages(), request.tools(), request.maxTokens(), sink.onContent(),
            sink.onReasoning(), sink.onToolCallDelta(), sink.onUsage(), sink.onComplete(), sink.onError(),
            request.sampling(), request.requireSentinel(), request.work());
        return null;
      }).when(ai).stream(any(OnlineAiService.StreamRequest.class), any(OnlineAiService.StreamSink.class));
    }
    @Override public void close() throws Exception {
      var shutdown = type.getDeclaredMethod("shutdown"); shutdown.setAccessible(true); shutdown.invoke(ops);
      http.shutdownNow(); server.stop(0); handlers.close(); registry.close();
    }
  }
}
