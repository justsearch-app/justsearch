package io.justsearch.app.services.conversation.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.StreamConsumerResult;
import io.justsearch.agent.api.registry.Audience;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link BatchSummaryDoneEnricher} (slice 491 C2.2). */
final class BatchSummaryDoneEnricherTest {

  @Test
  @DisplayName("ID is stable and namespaced under core")
  void idIsCoreNamespaced() {
    assertEquals("core.batch-summary-done-enricher", BatchSummaryDoneEnricher.ID);
  }

  @Test
  @DisplayName("onChunk returns empty — post-hoc only")
  void onChunkIsEmpty() {
    StreamConsumerResult r =
        BatchSummaryDoneEnricher.INSTANCE.onChunk("partial", stubCtxWithAttrs(Map.of()));
    assertTrue(r.events().isEmpty());
    assertTrue(r.messageDeltas().isEmpty());
    assertTrue(r.donePayloadEntries().isEmpty());
  }

  @Test
  @DisplayName("onDone reads batch.fileCount + batch.docIds from attributes")
  void onDoneReadsAttributes() {
    var ctx =
        stubCtxWithAttrs(
            Map.of("batch.fileCount", 3, "batch.docIds", List.of("a", "b", "c")));
    StreamConsumerResult r = BatchSummaryDoneEnricher.INSTANCE.onDone("summary", ctx);

    Map<String, Object> entries = r.donePayloadEntries();
    assertEquals(3, entries.get("fileCount"));
    assertEquals(List.of("a", "b", "c"), entries.get("docIds"));
    assertEquals(true, entries.get("fullCoverage"));
  }

  @Test
  @DisplayName("onDone omits fileCount + docIds when attributes empty (no injector populated them)")
  void onDoneWithMissingAttributes() {
    StreamConsumerResult r =
        BatchSummaryDoneEnricher.INSTANCE.onDone("text", stubCtxWithAttrs(Map.of()));
    Map<String, Object> entries = r.donePayloadEntries();
    assertFalse(entries.containsKey("fileCount"));
    assertFalse(entries.containsKey("docIds"));
    // fullCoverage:true is always emitted (it's the shape's identity)
    assertEquals(true, entries.get("fullCoverage"));
  }

  @Test
  @DisplayName("onDone is robust to non-List docIds attribute (typing mismatch)")
  void onDoneIgnoresMalformedDocIds() {
    var ctx = stubCtxWithAttrs(Map.of("batch.docIds", "not-a-list"));
    StreamConsumerResult r = BatchSummaryDoneEnricher.INSTANCE.onDone("text", ctx);
    assertFalse(r.donePayloadEntries().containsKey("docIds"));
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
