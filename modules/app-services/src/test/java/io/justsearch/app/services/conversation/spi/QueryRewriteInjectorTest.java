package io.justsearch.app.services.conversation.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.InjectorResult;
import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.SamplingParams;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** Tempdoc 603 C2 — the query-decontextualization injector: rewrites a follow-up, total graceful fallback. */
final class QueryRewriteInjectorTest {

  private static final List<Map<String, String>> HISTORY =
      List.of(
          Map.of("role", "user", "content", "What TCP port does the API bind to?"),
          Map.of("role", "assistant", "content", "An ephemeral port (port 0)."));

  @Test
  void firstTurnSkipsTheRewrite() {
    StubAi ai = new StubAi(true, "REWRITTEN");
    var injector = new QueryRewriteInjector(() -> ai);
    var ctx = stubCtx(Map.of("question", "why was it designed that way?")); // no `context` → first turn
    InjectorResult r = injector.inject(ctx);
    assertEquals(0, ai.calls); // never pay the LLM cost on the first turn
    assertNull(ctx.attributes().get(QueryRewriteInjector.ATTR_STANDALONE_QUESTION));
    assertTrue(r.events().isEmpty());
  }

  @Test
  void followUpRewritesStashesAndEmitsEvent() {
    StubAi ai =
        new StubAi(true, "Why was the API designed to use an ephemeral port instead of a fixed one?");
    var injector = new QueryRewriteInjector(() -> ai);
    var ctx = stubCtx(Map.of("question", "why was it designed that way?", "context", HISTORY));
    InjectorResult r = injector.inject(ctx);
    assertEquals(1, ai.calls);
    assertEquals(
        "Why was the API designed to use an ephemeral port instead of a fixed one?",
        ctx.attributes().get(QueryRewriteInjector.ATTR_STANDALONE_QUESTION));
    assertEquals(1, r.events().size());
    SseEvent ev = r.events().get(0);
    assertEquals("rag.rewrite", ev.name());
    assertEquals("why was it designed that way?", ev.payload().get("original"));
    assertTrue(((String) ev.payload().get("standalone")).contains("ephemeral port"));
  }

  @Test
  void rewriteCallSuppressesThinking() {
    // Tempdoc 835 §10f: the rewrite is plumbing — its output feeds retrieval, never a reader. With
    // the server-wide reasoning budget on by default, a null enableThinking here would buy a full
    // reasoning pass per follow-up turn for a one-sentence rewrite.
    StubAi ai = new StubAi(true, "Why an ephemeral port and not a fixed one?");
    var injector = new QueryRewriteInjector(() -> ai);
    var ctx = stubCtx(Map.of("question", "why that?", "context", HISTORY));

    injector.inject(ctx);

    assertEquals(1, ai.calls);
    assertEquals(
        Boolean.FALSE,
        ai.lastSampling.enableThinking(),
        "query rewrite must send chat_template_kwargs.enable_thinking=false");
  }

  @Test
  void aiUnavailableFallsBackToRawQuestion() {
    StubAi ai = new StubAi(false, "REWRITTEN");
    var injector = new QueryRewriteInjector(() -> ai);
    var ctx = stubCtx(Map.of("question", "why that?", "context", HISTORY));
    InjectorResult r = injector.inject(ctx);
    assertEquals(0, ai.calls);
    assertNull(ctx.attributes().get(QueryRewriteInjector.ATTR_STANDALONE_QUESTION));
    assertTrue(r.events().isEmpty());
  }

  @Test
  void timeoutFallsBackWithoutThrowing() {
    StubAi ai = new StubAi(true, null); // never-completing future
    var injector = new QueryRewriteInjector(() -> ai, Duration.ofMillis(20));
    var ctx = stubCtx(Map.of("question", "why that?", "context", HISTORY));
    InjectorResult r = injector.inject(ctx); // must not throw
    assertNull(ctx.attributes().get(QueryRewriteInjector.ATTR_STANDALONE_QUESTION));
    assertTrue(r.events().isEmpty());
  }

  @Test
  void blankRewriteRejected() {
    StubAi ai = new StubAi(true, "   ");
    var injector = new QueryRewriteInjector(() -> ai);
    var ctx = stubCtx(Map.of("question", "why that?", "context", HISTORY));
    InjectorResult r = injector.inject(ctx);
    assertNull(ctx.attributes().get(QueryRewriteInjector.ATTR_STANDALONE_QUESTION));
    assertTrue(r.events().isEmpty());
  }

  @Test
  void alreadyStandaloneIsNotRecorded() {
    StubAi ai = new StubAi(true, "why that?"); // identical to the raw question
    var injector = new QueryRewriteInjector(() -> ai);
    var ctx = stubCtx(Map.of("question", "why that?", "context", HISTORY));
    InjectorResult r = injector.inject(ctx);
    assertNull(ctx.attributes().get(QueryRewriteInjector.ATTR_STANDALONE_QUESTION));
    assertTrue(r.events().isEmpty());
  }

  // --- stubs ---

  private static final class StubAi implements OnlineAiService {
    private final boolean available;
    private final String result; // null => never-completing future (simulates timeout)
    int calls = 0;
    SamplingParams lastSampling;

    StubAi(boolean available, String result) {
      this.available = available;
      this.result = result;
    }

    @Override
    public boolean isAvailable() {
      return available;
    }

    @Override
    public boolean isStartingUp() {
      return false;
    }

    @Override
    public CompletableFuture<String> summarize(String content) {
      return CompletableFuture.failedFuture(new UnsupportedOperationException("stub"));
    }

    @Override
    public CompletableFuture<String> askQuestion(String question, String context) {
      return CompletableFuture.failedFuture(new UnsupportedOperationException("stub"));
    }

    @Override
    public CompletableFuture<String> chatCompletion(
        List<Map<String, Object>> messages, int maxTokens, SamplingParams sampling) {
      calls++;
      lastSampling = sampling;
      return result == null ? new CompletableFuture<>() : CompletableFuture.completedFuture(result);
    }
  }

  private static ConversationContext stubCtx(Map<String, Object> body) {
    return new ConversationContext() {
      @Override
      public io.justsearch.core.context.EngineContext engineContext() {
        return io.justsearch.app.services.TestEngineContexts.internal();
      }

      private final Map<String, Object> a = new HashMap<>();
      private final Map<String, Object> b = new LinkedHashMap<>(body);

      @Override
      public List<Map<String, Object>> messages() {
        return List.of();
      }

      @Override
      public int iteration() {
        return 0;
      }

      @Override
      public Audience audience() {
        return Audience.USER;
      }

      @Override
      public String sessionId() {
        return null;
      }

      @Override
      public Map<String, Object> requestBody() {
        return b;
      }

      @Override
      public Map<String, Object> attributes() {
        return a;
      }
    };
  }
}
