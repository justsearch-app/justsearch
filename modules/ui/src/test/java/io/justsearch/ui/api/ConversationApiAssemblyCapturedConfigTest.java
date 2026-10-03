/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.RetrieveContextParams;
import io.justsearch.app.services.conversation.ContextInjectorRegistry;
import io.justsearch.app.services.conversation.ConversationEngine;
import io.justsearch.app.services.conversation.StreamConsumerRegistry;
import io.justsearch.app.services.conversation.spi.DocAccess;
import io.justsearch.app.services.conversation.spi.RAGContext;
import io.justsearch.app.services.conversation.spi.StreamingCitationMatcher;
import io.justsearch.app.services.observability.HeadApiMetricCatalog;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.execution.TestEngineExecutors;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ConversationApiAssemblyCapturedConfigTest {

  @TempDir Path tempDir;

  @Test
  void composedOperationsFollowCapturedStoreAfterGlobalReplacement() throws Exception {
    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore captured = store(7, 0.61, 100);
    ConfigStore.setGlobal(captured);
    var docs = new CapturingDocuments();

    try (var executors = new TestEngineExecutors()) {
      var builder =
          LocalApiServer.builder(executors, null, tempDir.resolve("index"))
              .documentService(docs)
              .onlineAiService(new CompletingAi());
      var assembled =
          ConversationApiAssembly.assemble(
              builder, null, HeadApiMetricCatalog.noop(), false, () -> null);
      try {
        ConfigStore.setGlobal(store(19, 0.97, 999));
        captured.update(store(11, 0.83, 2).get());

        ConversationEngine engine = engineOf(assembled.chatController());
        RAGContext rag =
            (RAGContext)
                contextInjectorsOf(engine).findById(RAGContext.ID).orElseThrow();
        StreamingCitationMatcher matcher =
            (StreamingCitationMatcher)
                streamConsumersOf(engine)
                    .findById(StreamingCitationMatcher.ID)
                    .orElseThrow();
        DocAccess summary =
            (DocAccess)
                contextInjectorsOf(engine).findById(DocAccess.ID).orElseThrow();
        ConversationContext context = context(Map.of("question", "What is captured?"));

        rag.inject(context);
        matcher.onDone("Captured configuration applies.", context);
        var summaryResult = summary.inject(context(Map.of("content", "x".repeat(30))));

        assertEquals(11, docs.topK.get());
        assertEquals(0.83, docs.threshold.get());
        assertEquals(
            "CONTEXT_TOO_LARGE",
            summaryResult.terminalError().orElseThrow().payload().get("errorCode"));
        assertEquals(2, summaryResult.terminalError().orElseThrow().payload().get("maxTokens"));
      } finally {
        assembled.chatController().shutdown();
      }
    } finally {
      TestResolvedConfigHelper.restoreGlobal(previous);
    }
  }

  private static ConversationEngine engineOf(ChatController controller) throws Exception {
    Field field = ChatController.class.getDeclaredField("engine");
    field.setAccessible(true);
    return (ConversationEngine) field.get(controller);
  }

  private static ContextInjectorRegistry contextInjectorsOf(ConversationEngine engine)
      throws Exception {
    Field field = ConversationEngine.class.getDeclaredField("contextInjectors");
    field.setAccessible(true);
    return (ContextInjectorRegistry) field.get(engine);
  }

  private static StreamConsumerRegistry streamConsumersOf(ConversationEngine engine)
      throws Exception {
    Field field = ConversationEngine.class.getDeclaredField("streamConsumers");
    field.setAccessible(true);
    return (StreamConsumerRegistry) field.get(engine);
  }

  private static ConversationContext context(Map<String, Object> body) {
    return new ConversationContext() {
      private final Map<String, Object> requestBody = new LinkedHashMap<>(body);
      private final Map<String, Object> attributes = new HashMap<>();

      @Override
      public io.justsearch.core.context.EngineContext engineContext() {
        return TestRequestContexts.browser();
      }

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
        return requestBody;
      }

      @Override
      public Map<String, Object> attributes() {
        return attributes;
      }
    };
  }

  private static ConfigStore store(int topK, double threshold, int summaryMaxTokens) {
    return new ConfigStore(
        TestResolvedConfigHelper.fromEntries(
            Map.of(
                "justsearch.rag.top_k", Integer.toString(topK),
                "justsearch.citation.match_threshold", Double.toString(threshold),
                "justsearch.summary.max_tokens", Integer.toString(summaryMaxTokens))));
  }

  private static final class CompletingAi implements OnlineAiService {
    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public boolean isStartingUp() {
      return false;
    }

    @Override
    public CompletableFuture<String> summarize(String content) {
      return CompletableFuture.failedFuture(new AssertionError("Expected streaming request"));
    }

    @Override
    public CompletableFuture<String> askQuestion(String question, String context) {
      return CompletableFuture.failedFuture(new AssertionError("Expected streaming request"));
    }

    @Override
    public void stream(StreamRequest request, StreamSink sink) {
      sink.onContent().accept("Captured configuration applies.");
      sink.onComplete().accept("stop");
    }

    @Override
    public Integer llmContextTokens() {
      return 32_768;
    }

    @Override
    public Integer configuredContextTokens() {
      return 32_768;
    }
  }

  private static final class CapturingDocuments implements DocumentService {
    @Override
    public java.util.concurrent.CompletionStage<DocumentRecord> fetch(
        String docId, io.justsearch.core.context.EngineContext context) {
      return CompletableFuture.failedFuture(new AssertionError("Expected context retrieval"));
    }

    private final AtomicReference<Integer> topK = new AtomicReference<>();
    private final AtomicReference<Double> threshold = new AtomicReference<>();

    @Override
    public java.util.concurrent.CompletionStage<ContextResult> retrieveContext(
        RetrieveContextParams params, io.justsearch.core.context.EngineContext context) {
      topK.set(params.topK());
      var citation =
          new ContextCitation(
              "doc-1", 0, 1, 0, 20, 0.9f, "captured configuration applies", 0, 0, "", 0,
              ContextInclusion.ABSENT);
      return CompletableFuture.completedFuture(
          new ContextResult(
              "captured configuration applies", 1, 1, 1, List.of(citation), "BM25", "ok",
              false, List.of(new ContextSection("[doc-1]", "captured configuration applies", false, 0, 0))));
    }

    @Override
    public java.util.concurrent.CompletionStage<CitationMatchResult> matchCitationsAgainst(
        String answer,
        List<VerificationSource> sources,
        double similarityThreshold,
        io.justsearch.core.context.EngineContext context) {
      threshold.set(similarityThreshold);
      return CompletableFuture.completedFuture(
          new CitationMatchResult(List.of(), 0, 0, 0, 0, ScorerKind.NONE, List.of()));
    }
  }
}
