package io.justsearch.app.services.conversation.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.StreamConsumerResult;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.app.api.DocumentService.ContextCitation;
import io.justsearch.app.api.DocumentService.ContextInclusion;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RAGDoneEnricher} (slice 491 C3). */
final class RAGDoneEnricherTest {

  @Test
  @DisplayName("ID is stable and namespaced under core")
  void idIsCoreNamespaced() {
    assertEquals("core.rag-done-enricher", RAGDoneEnricher.ID);
  }

  @Test
  @DisplayName("onChunk returns empty — post-hoc only")
  void onChunkIsEmpty() {
    StreamConsumerResult r =
        RAGDoneEnricher.INSTANCE.onChunk("partial", stubCtxWithAttrs(Map.of()));
    assertTrue(r.events().isEmpty());
    assertTrue(r.messageDeltas().isEmpty());
    assertTrue(r.donePayloadEntries().isEmpty());
  }

  @Test
  @DisplayName("onDone projects citations into wire-shape maps")
  void onDoneProjectsCitations() {
    var citation =
        new ContextCitation(
            "doc-1", 0, 3, 100, 200, 0.85f, "the excerpt", 10, 12, "Heading", 2,
            ContextInclusion.ABSENT);
    var ctx =
        stubCtxWithAttrs(
            Map.of(
                RAGContext.ATTR_FILE_COUNT, 1,
                RAGContext.ATTR_USED_RAG, true,
                RAGContext.ATTR_CHUNKS_USED, 1,
                RAGContext.ATTR_CHUNKS_FOUND, 5,
                RAGContext.ATTR_CITATIONS, List.of(citation)));
    StreamConsumerResult r = RAGDoneEnricher.INSTANCE.onDone("answer text", ctx);

    Map<String, Object> entries = r.donePayloadEntries();
    assertEquals(1, entries.get("fileCount"));
    assertEquals(true, entries.get("usedRag"));
    assertEquals(1, entries.get("chunksUsed"));
    assertEquals(5, entries.get("chunksFound"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> citations = (List<Map<String, Object>>) entries.get("citations");
    assertEquals(1, citations.size());
    Map<String, Object> c = citations.get(0);
    assertEquals("doc-1", c.get("parentDocId"));
    assertEquals(0, c.get("chunkIndex"));
    assertEquals(3, c.get("chunkTotal"));
    assertEquals(100, c.get("startChar"));
    assertEquals(200, c.get("endChar"));
    assertEquals(0.85f, c.get("score"));
    assertEquals("the excerpt", c.get("excerpt"));
    assertEquals(10, c.get("startLine"));
    assertEquals(12, c.get("endLine"));
    assertEquals("Heading", c.get("headingText"));
    assertEquals(2, c.get("headingLevel"));
    // Tempdoc 849 — this citation was constructed ABSENT, so the persisted map must carry NO
    // inclusion key. The full round-trip through the store lives in CitationInclusionPersistenceTest.
    assertFalse(c.containsKey("contextInclusion"), c.toString());
  }

  @Test
  @DisplayName("849: a resolved inclusion rides the persisted citation map")
  void onDoneCarriesResolvedInclusion() {
    var citation =
        new ContextCitation(
            "doc-1", 0, 3, 100, 200, 0.85f, "the excerpt", 10, 12, "Heading", 2,
            ContextInclusion.dropped());
    var ctx = stubCtxWithAttrs(Map.of(RAGContext.ATTR_CITATIONS, List.of(citation)));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> citations =
        (List<Map<String, Object>>)
            RAGDoneEnricher.INSTANCE.onDone("answer", ctx).donePayloadEntries().get("citations");

    assertEquals("dropped", citations.get(0).get("contextInclusion"));
    assertEquals(0, citations.get(0).get("contextIncludedChars"));
  }

  @Test
  @DisplayName("onDone emits citations:[] when no citations stashed")
  void onDoneEmptyCitations() {
    var ctx =
        stubCtxWithAttrs(
            Map.of(
                RAGContext.ATTR_FILE_COUNT, 2,
                RAGContext.ATTR_USED_RAG, false,
                RAGContext.ATTR_CHUNKS_USED, 0,
                RAGContext.ATTR_CHUNKS_FOUND, 0,
                RAGContext.ATTR_CITATIONS, List.of()));
    StreamConsumerResult r = RAGDoneEnricher.INSTANCE.onDone("answer", ctx);
    assertEquals(List.of(), r.donePayloadEntries().get("citations"));
    assertEquals(false, r.donePayloadEntries().get("usedRag"));
  }

  @Test
  @DisplayName("onDone omits missing attributes (graceful when RAGContext fail-fasted)")
  void onDoneMissingAttributes() {
    StreamConsumerResult r = RAGDoneEnricher.INSTANCE.onDone("answer", stubCtxWithAttrs(Map.of()));
    Map<String, Object> entries = r.donePayloadEntries();
    assertFalse(entries.containsKey("fileCount"));
    assertFalse(entries.containsKey("usedRag"));
    assertFalse(entries.containsKey("chunksUsed"));
    // citations defaults to empty list (not omitted) — keeps the wire shape stable
    assertEquals(List.of(), entries.get("citations"));
  }

  private static ConversationContext stubCtxWithAttrs(Map<String, Object> attrs) {
    return new ConversationContext() {
      @Override
      public io.justsearch.core.context.EngineContext engineContext() {
        return io.justsearch.app.services.TestEngineContexts.internal();
      }

      private final Map<String, Object> a = new HashMap<>(attrs);

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
        return Map.of();
      }

      @Override
      public Map<String, Object> attributes() {
        return a;
      }
    };
  }
}
