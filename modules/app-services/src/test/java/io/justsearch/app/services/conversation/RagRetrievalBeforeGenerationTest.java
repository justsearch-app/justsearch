/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import io.justsearch.core.context.EngineContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Spec D (item 5a) test R3: a RAG turn's retrieval, which carries the turn's query encodes,
 * completes before the turn's first generation call, so a query encode never runs while the turn
 * is generating (S1 for chat turns). Preservation: this holds before and after the change.
 *
 * <p>Real seam: the production {@link ConversationEngine} running a one-shot shape with the
 * production {@link RAGContext} injector. Only the edges are recorded: the document service, whose
 * retrieval answers late on another thread, and the generation client.
 */
final class RagRetrievalBeforeGenerationTest {

  private static final ConversationShapeRef SHAPE_ID =
      new ConversationShapeRef("core.test-rag-order");

  @Test
  void everyRetrievalCompletesBeforeTheFirstGenerationCall() {
    List<String> calls = new CopyOnWriteArrayList<>();
    var documents = new RecordingDocuments(calls);
    var injector = new RAGContext(documents);
    var shape =
        new ConversationShape(
            SHAPE_ID,
            new Presentation(
                new I18nKey("test.label"),
                new I18nKey("test.desc"),
                Optional.empty(),
                Optional.empty()),
            Audience.USER,
            Provenance.core("v1"),
            ExecutionMode.SUBSTRATE_DRIVEN,
            IterationMode.ONE_SHOT,
            PersistenceMode.EPHEMERAL,
            List.of(),
            List.of(injector.id()),
            List.of(),
            SingleHopController.ID,
            List.of(),
            true);
    var engine =
        new ConversationEngine(
            ConversationShapeCatalog.of("core", List.of(shape)),
            List.of(),
            PromptContributorRegistry.of(List.of()),
            ContextInjectorRegistry.of(List.of(injector)),
            StreamConsumerRegistry.of(List.of()),
            IterationControllerRegistry.of(List.of()),
            () -> new RecordingGeneration(calls));

    var events = new ArrayList<SseEvent>();
    engine.run(
        SHAPE_ID,
        Map.of("question", "What does the probe say?", "docIds", List.of("doc-1")),
        Audience.USER,
        events::add,
        io.justsearch.app.services.TestEngineContexts.internal());

    assertTrue(calls.contains("generate"), () -> "the turn generated: " + calls + " " + events);
    int firstGeneration = calls.indexOf("generate");
    assertEquals(1, documents.retrievals, "the turn retrieved once");
    assertTrue(
        calls.indexOf("retrieval-complete") >= 0
            && calls.indexOf("retrieval-complete") < firstGeneration,
        () -> "every retrieval completes before the first generation call: " + calls);
    assertEquals(
        calls.indexOf("retrieval-start"),
        calls.lastIndexOf("retrieval-start"),
        () -> "no retrieval starts after generation: " + calls);
  }

  /** Retrieval that answers late, on another thread, as the worker does. */
  private static final class RecordingDocuments implements DocumentService {
    private final List<String> calls;
    volatile int retrievals;

    RecordingDocuments(List<String> calls) {
      this.calls = calls;
    }

    @Override
    public CompletionStage<ContextResult> retrieveContext(
        RetrieveContextParams params, EngineContext engineContext) {
      retrievals++;
      calls.add("retrieval-start");
      var citation =
          new ContextCitation(
              "doc-1", 0, 1, 0, 40, 0.9f, "The probe says the budget held.", 1, 1, "", 0,
              ContextInclusion.ABSENT);
      var section = new ContextSection("doc-1", "The probe says the budget held.", false, 0, 0);
      return CompletableFuture.supplyAsync(
          () -> {
            calls.add("retrieval-complete");
            return new ContextResult(
                "The probe says the budget held.", 1, 1, 0, List.of(citation), "BM25",
                "BM25_CONFIGURED", false, List.of(section));
          },
          CompletableFuture.delayedExecutor(200, TimeUnit.MILLISECONDS));
    }

    @Override
    public CompletionStage<DocumentRecord> fetch(String docId, EngineContext engineContext) {
      return CompletableFuture.completedFuture(null);
    }
  }

  /** A generation client that records when the turn's generation is called. */
  private static final class RecordingGeneration implements OnlineAiService {
    private final List<String> calls;

    RecordingGeneration(List<String> calls) {
      this.calls = calls;
    }

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
      calls.add("generate");
      return CompletableFuture.failedFuture(new UnsupportedOperationException());
    }

    @Override
    public CompletableFuture<String> askQuestion(String question, String context) {
      calls.add("generate");
      return CompletableFuture.failedFuture(new UnsupportedOperationException());
    }

    @Override
    public void stream(StreamRequest request, StreamSink sink) {
      calls.add("generate");
      sink.onContent().accept("The budget held.");
      sink.onComplete().accept("The budget held.");
    }
  }
}
