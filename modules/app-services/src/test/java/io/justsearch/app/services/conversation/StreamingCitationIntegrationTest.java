package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.ExecutionMode;
import io.justsearch.agent.api.conversation.InjectorResult;
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
import io.justsearch.app.services.conversation.spi.RAGContext;
import io.justsearch.app.services.conversation.spi.StreamingCitationMatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration test for slice 493: verifies that StreamingCitationMatcher emits
 * rag.citation_delta events during the LLM stream via the ConversationEngine's
 * onChunk flush path (ConversationEngine.streamLlm lines 338-348).
 */
final class StreamingCitationIntegrationTest {

  private static final ConversationShapeRef SHAPE_ID =
      new ConversationShapeRef("core.test-rag-stream");

  @Test
  @DisplayName(
      "rag.citation_delta events appear during stream, before done — slice 493 core contract")
  void citationDeltasDuringStream() {
    var docs = new StubDocumentService();
    var matcher = new StreamingCitationMatcher(docs);

    var citationInjector = new CitationStashingInjector();

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
            List.of(citationInjector.id()),
            List.of(matcher.id()),
            SingleHopController.ID,
            List.of(),
            true);

    var engine =
        new ConversationEngine(
            ConversationShapeCatalog.of("core", List.of(shape)),
            List.of(),
            PromptContributorRegistry.of(List.of()),
            ContextInjectorRegistry.of(List.of(citationInjector)),
            StreamConsumerRegistry.of(List.of(matcher)),
            IterationControllerRegistry.of(List.of()),
            () -> new WordByWordAi(
                "The grass is green in the field. JustSearch indexes your files locally. "));

    var events = new ArrayList<SseEvent>();
    engine.run(SHAPE_ID, Map.of(), Audience.USER, events::add, io.justsearch.app.services.TestEngineContexts.internal());

    // Verify rag.citation_delta events exist.
    List<SseEvent> deltas =
        events.stream()
            .filter(e -> "rag.citation_delta".equals(e.name()))
            .toList();
    assertTrue(deltas.size() > 0, "at least one rag.citation_delta during stream");

    // Verify the delta appeared BEFORE done.
    int firstDeltaIdx = -1;
    int doneIdx = -1;
    for (int i = 0; i < events.size(); i++) {
      if ("rag.citation_delta".equals(events.get(i).name()) && firstDeltaIdx < 0) {
        firstDeltaIdx = i;
      }
      if ("done".equals(events.get(i).name())) {
        doneIdx = i;
      }
    }
    assertTrue(firstDeltaIdx >= 0, "delta event must be present");
    assertTrue(doneIdx > firstDeltaIdx, "delta must precede done");

    // Verify delta payload structure.
    SseEvent delta = deltas.get(0);
    assertEquals(0, delta.payload().get("sentenceIndex"));
    assertTrue(
        delta.payload().containsKey("sentenceText"), "delta must contain sentenceText");
    assertTrue(
        delta.payload().containsKey("citations"), "delta must contain citations list");

    // Verify done event is still present.
    SseEvent done =
        events.stream()
            .filter(e -> "done".equals(e.name()))
            .findFirst()
            .orElseThrow();
    assertEquals(1, done.payload().get("iterationsUsed"));
  }

  @Test
  @DisplayName("rag.citations event emitted by injector before first chunk")
  void ragCitationsEmittedBeforeChunks() {
    var docs = new StubDocumentService();
    var matcher = new StreamingCitationMatcher(docs);
    var citationInjector = new CitationStashingInjector();

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
            List.of(citationInjector.id()),
            List.of(matcher.id()),
            SingleHopController.ID,
            List.of(),
            true);

    var engine =
        new ConversationEngine(
            ConversationShapeCatalog.of("core", List.of(shape)),
            List.of(),
            PromptContributorRegistry.of(List.of()),
            ContextInjectorRegistry.of(List.of(citationInjector)),
            StreamConsumerRegistry.of(List.of(matcher)),
            IterationControllerRegistry.of(List.of()),
            () -> new WordByWordAi("Answer text here. "));

    var events = new ArrayList<SseEvent>();
    engine.run(SHAPE_ID, Map.of(), Audience.USER, events::add, io.justsearch.app.services.TestEngineContexts.internal());

    int ragCitationsIdx = -1;
    int firstChunkIdx = -1;
    for (int i = 0; i < events.size(); i++) {
      if ("rag.citations".equals(events.get(i).name()) && ragCitationsIdx < 0) {
        ragCitationsIdx = i;
      }
      if ("chunk".equals(events.get(i).name()) && firstChunkIdx < 0) {
        firstChunkIdx = i;
      }
    }
    assertTrue(ragCitationsIdx >= 0, "rag.citations event must be emitted");
    assertTrue(firstChunkIdx > ragCitationsIdx, "rag.citations must precede first chunk");
  }

  // ---------- Test fixtures ----------

  /**
   * ContextInjector that stashes mock citations and emits rag.citations event,
   * mimicking RAGContext's behavior.
   */
  private static final class CitationStashingInjector
      implements io.justsearch.agent.api.conversation.ContextInjector {

    @Override
    public String id() {
      return "core.test-citation-injector";
    }

    @Override
    public InjectorResult inject(ConversationContext ctx) {
      var citation =
          new DocumentService.ContextCitation(
              "doc-1", 0, 1, 0, 100, 0.9f,
              "The grass is green in the field nearby",
              0, 5, "Introduction", 1,
              DocumentService.ContextInclusion.ABSENT);

      ctx.attributes().put(RAGContext.ATTR_CITATIONS, List.of(citation));
      ctx.attributes().put(RAGContext.ATTR_USED_RAG, true);

      List<Map<String, Object>> citationMaps = new ArrayList<>();
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("parentDocId", citation.parentDocId());
      m.put("chunkIndex", citation.chunkIndex());
      m.put("excerpt", citation.excerpt());
      m.put("score", citation.score());
      m.put("startLine", citation.startLine());
      m.put("endLine", citation.endLine());
      m.put("startChar", citation.startChar());
      m.put("endChar", citation.endChar());
      m.put("headingText", citation.headingText());
      m.put("headingLevel", citation.headingLevel());
      citationMaps.add(Map.copyOf(m));

      return InjectorResult.of(
          List.of(Map.of("role", "user", "content", "What is the grass?")),
          List.of(new SseEvent("rag.citations", Map.of("citations", citationMaps))));
    }
  }

  /** OnlineAiService that streams text word-by-word to exercise onChunk sentence detection. */
  private static final class WordByWordAi implements OnlineAiService {
    private final String text;

    WordByWordAi(String text) {
      this.text = text;
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
      return CompletableFuture.failedFuture(new UnsupportedOperationException());
    }

    @Override
    public CompletableFuture<String> askQuestion(String question, String context) {
      return CompletableFuture.failedFuture(new UnsupportedOperationException());
    }

    @Override
    public void stream(StreamRequest request, StreamSink sink) {
      String[] words = text.split("(?<=\\s)");
      for (String word : words) {
        sink.onContent().accept(word);
      }
      sink.onComplete().accept(text);
    }
  }

  /** DocumentService stub — matchCitations returns empty (authoritative matching not exercised). */
  private static final class StubDocumentService implements DocumentService {

    @Override
    public CompletionStage<DocumentRecord> fetch(String docId, io.justsearch.core.context.EngineContext engineContext) {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<CitationMatchResult> matchCitations(
        String answerText,
        List<DocumentService.ContextCitation> citations,
        double threshold,
        io.justsearch.core.context.EngineContext engineContext) {
      return CompletableFuture.completedFuture(
          new CitationMatchResult(
              List.of(), 0, 0, 0, 0, DocumentService.ScorerKind.NONE, List.of()));
    }
  }
}
