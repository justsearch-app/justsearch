/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.conversation.ContextInjector;
import io.justsearch.agent.api.conversation.ExecutionMode;
import io.justsearch.agent.api.conversation.IterationMode;
import io.justsearch.agent.api.conversation.PersistenceMode;
import io.justsearch.agent.api.conversation.SingleHopController;
import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.ConversationShape;
import io.justsearch.agent.api.registry.ConversationShapeCatalog;
import io.justsearch.agent.api.registry.ConversationShapeRef;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.RetrieveContextParams;
import io.justsearch.app.services.conversation.spi.RAGContext;
import io.justsearch.app.services.conversation.spi.UserPromptInjector;
import io.justsearch.core.context.EngineContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** WP4.2b NE3/NE4/D5 and F2: real injectors, engine and disk store; no model process. */
final class NoEvidencePersistenceTest {
  private static final ConversationShapeRef ASK = new ConversationShapeRef("core.rag-ask");
  private static final ConversationShapeRef MODEL = new ConversationShapeRef("core.free-chat");
  private Path directory;

  @BeforeEach
  void setup() throws Exception {
    Path root = Path.of("").toAbsolutePath();
    while (!Files.exists(root.resolve("settings.gradle.kts"))) {
      root = root.getParent();
      if (root == null) throw new IllegalStateException("Repository root not found");
    }
    directory = Files.createTempDirectory(Files.createDirectories(root.resolve("tmp/no-evidence-tests")), "engine-");
  }

  @AfterEach
  void cleanup() throws Exception {
    try (var paths = Files.walk(directory)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void storesExplicitScopeOnBothNoContentOutcomesWithoutChangingTheError(boolean scoped) {
    var docs = new Documents();
    var engine = engine(docs, new Model());
    String id = scoped ? "scoped" : "unscoped";
    var events = new ArrayList<SseEvent>();
    engine.run(ASK, Map.of("conversationId", id, "question", "missing", "docIds",
        scoped ? List.of("empty.md") : List.of()), Audience.USER, events::add,
        io.justsearch.app.services.TestEngineContexts.internal());
    var history = new FileConversationStore(directory).loadHistory(id);
    assertEquals(2, history.size(), history.toString());
    assertEquals("NO_CONTENT", history.get(1).get("errorCode"));
    assertEquals(scoped, history.get(1).get("retrievalScoped"), "D3: scope is a fact on the error");
    assertEquals("NO_CONTENT", events.getLast().payload().get("errorCode"));
    assertEquals(scoped ? "No content in selected files" : "No matching documents found in the index",
        history.get(1).get("content"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"RETRIEVAL_TIMEOUT", "RETRIEVAL_FAILED", "FETCH_FAILED"})
  void storesTimeoutFailureAndFetchFailureWithTheOriginalQuestionClassificationAndExplanation(String code) {
    var docs = new Documents(); docs.outcome = code;
    var events = new ArrayList<SseEvent>();
    engine(docs, new Model()).run(ASK, Map.of("conversationId", code, "question", "keep my question"),
        Audience.USER, events::add, io.justsearch.app.services.TestEngineContexts.internal());
    assertEquals(code, events.getLast().payload().get("errorCode"), events.toString());
    var history = new FileConversationStore(directory).loadHistory(code);
    assertEquals(2, history.size(), "D5: do not lose the question when retrieval supplies no metadata");
    assertEquals("keep my question", history.getFirst().get("content"));
    assertEquals(code, history.getLast().get("errorCode"));
    assertEquals(events.getLast().payload().get("error"), history.getLast().get("content"));
    assertEquals(true, history.getLast().get("terminalError"));
  }

  @Test
  void allHiddenSelectedFilesKeepTheirExistingNoContentOutcomeAndAreNeverFetched() {
    var docs = new Documents();
    var store = new FileConversationStore(directory);
    store.createSession("hidden", ASK.value());
    store.excludeSource("hidden", "empty.md" + (char) 0x1f + "0", true);
    var events = new ArrayList<SseEvent>();
    engine(docs, new Model()).run(ASK,
        Map.of("conversationId", "hidden", "question", "keep hidden files hidden", "docIds", List.of("empty.md")),
        Audience.USER, events::add, io.justsearch.app.services.TestEngineContexts.internal());
    assertEquals("NO_CONTENT", events.getLast().payload().get("errorCode"));
    assertEquals("No content in selected files", events.getLast().payload().get("error"));
    assertEquals(0, docs.fetches, "the whole-document fallback must not re-inject hidden content");
    assertEquals("No content in selected files", new FileConversationStore(directory).loadHistory("hidden").getLast().get("content"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"model failed", "context window exceeded"})
  void savesModelFailureAndOverflowAsAnErrorRatherThanACompletedEmptyAnswer(String error) {
    var model = new Model(); model.error = error;
    var events = new ArrayList<SseEvent>();
    engine(new Documents(), model).run(MODEL, Map.of("sessionId", "model", "prompt", "same question"),
        Audience.USER, events::add, io.justsearch.app.services.TestEngineContexts.internal());
    var history = new FileConversationStore(directory).loadHistory("model");
    assertEquals(true, history.getLast().get("terminalError"), "F2: failed model answer is saved as failed");
    assertEquals("LLM_ERROR", history.getLast().get("errorCode"));
    assertEquals(error, history.getLast().get("content"));
    assertTrue(events.stream().anyMatch(event -> event.name().equals("error")));
    assertFalse(events.stream().anyMatch(event -> event.name().equals("done")));
  }

  @Test
  void aSynchronousModelFailureKeepsItsExceptionAndSavesTheFailedAttempt() {
    var model = new Model(); model.error = "context window exceeded"; model.synchronous = true;
    var events = new ArrayList<SseEvent>();
    var failure = assertThrows(IllegalStateException.class, () -> engine(new Documents(), model).run(MODEL,
        Map.of("sessionId", "sync-model", "prompt", "keep the overflow question"),
        Audience.USER, events::add, io.justsearch.app.services.TestEngineContexts.internal()));
    assertEquals("context window exceeded", failure.getMessage(), "keep the existing adapter failure contract");
    var history = new FileConversationStore(directory).loadHistory("sync-model");
    assertEquals(2, history.size());
    assertEquals(true, history.getLast().get("terminalError"));
    assertEquals("LLM_ERROR", history.getLast().get("errorCode"));
  }

  private ConversationEngine engine(Documents docs, Model model) {
    var rag = new RAGContext(docs);
    List<ContextInjector> injectors = List.of(rag, UserPromptInjector.INSTANCE);
    return new ConversationEngine(ConversationShapeCatalog.of("core", List.of(
        shape(ASK, PersistenceMode.EPHEMERAL, rag.id()),
        shape(MODEL, PersistenceMode.PERSISTENT, UserPromptInjector.ID))), List.of(),
        PromptContributorRegistry.of(List.of()), ContextInjectorRegistry.of(injectors),
        StreamConsumerRegistry.of(List.of()), IterationControllerRegistry.of(List.of()),
        () -> model, new FileConversationStore(directory));
  }

  private static ConversationShape shape(ConversationShapeRef id, PersistenceMode mode, String injector) {
    return new ConversationShape(id,
        new Presentation(new I18nKey("test.label"), new I18nKey("test.description"), Optional.empty(), Optional.empty()),
        Audience.USER, Provenance.core("v1"), ExecutionMode.SUBSTRATE_DRIVEN, IterationMode.ONE_SHOT,
        mode, List.of(), List.of(injector), List.of(), SingleHopController.ID, List.of(), true);
  }

  private static final class Documents implements DocumentService {
    String outcome = "NO_CONTENT";
    int fetches;
    @Override
    public CompletionStage<ContextResult> retrieveContext(RetrieveContextParams params, EngineContext context) {
      if (outcome.equals("RETRIEVAL_TIMEOUT")) return CompletableFuture.failedFuture(new java.util.concurrent.TimeoutException());
      if (outcome.equals("RETRIEVAL_FAILED")) return CompletableFuture.failedFuture(new IllegalStateException("retrieval failed"));
      return CompletableFuture.completedFuture(new ContextResult("", 0, 0, 0, List.of(), "BM25",
          outcome.equals("FETCH_FAILED") ? "FALLBACK_FAILED" : "BM25_CONFIGURED", false, List.of()));
    }
    @Override
    public CompletionStage<DocumentRecord> fetch(String id, EngineContext context) {
      fetches++;
      return CompletableFuture.completedFuture(null);
    }
  }

  private static final class Model implements OnlineAiService {
    String error;
    boolean synchronous;
    @Override public boolean isAvailable() { return true; }
    @Override public boolean isStartingUp() { return false; }
    @Override public CompletableFuture<String> summarize(String content) { throw new UnsupportedOperationException(); }
    @Override public CompletableFuture<String> askQuestion(String question, String context) { throw new UnsupportedOperationException(); }
    @Override public void stream(StreamRequest request, StreamSink sink) {
      if (synchronous) throw new IllegalStateException(error);
      if (error != null) sink.onError().accept(new IllegalStateException(error));
      else { sink.onContent().accept("model answer"); sink.onComplete().accept("stop"); }
    }
  }
}
