package io.justsearch.app.services.conversation.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.StreamConsumerResult;
import io.justsearch.agent.api.registry.Audience;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link SummaryDoneEnricher} (tempdoc 491 §C2.1). */
final class SummaryDoneEnricherTest {

  @Test
  @DisplayName("onChunk returns empty (post-hoc only)")
  void onChunkIsEmpty() {
    StreamConsumerResult result =
        SummaryDoneEnricher.INSTANCE.onChunk("partial", stubCtx(Map.of("docId", "doc-1")));
    assertTrue(result.events().isEmpty());
    assertTrue(result.messageDeltas().isEmpty());
    assertTrue(result.donePayloadEntries().isEmpty());
  }

  @Test
  @DisplayName("onDone contributes docId, summary, hierarchical:false, fullCoverage:true")
  void onDoneEnrichesWithLegacyFields() {
    StreamConsumerResult result =
        SummaryDoneEnricher.INSTANCE.onDone(
            "the summary text", stubCtx(Map.of("docId", "doc-42", "content", "ignored")));

    Map<String, Object> entries = result.donePayloadEntries();
    assertEquals("doc-42", entries.get("docId"));
    assertEquals("the summary text", entries.get("summary"));
    assertEquals(false, entries.get("hierarchical"));
    assertEquals(true, entries.get("fullCoverage"));
  }

  @Test
  @DisplayName("onDone omits docId when not in body but always emits summary")
  void onDoneNoDocId() {
    StreamConsumerResult result =
        SummaryDoneEnricher.INSTANCE.onDone("content-only", stubCtx(Map.of()));
    Map<String, Object> entries = result.donePayloadEntries();
    assertFalse(entries.containsKey("docId"));
    assertEquals("content-only", entries.get("summary"));
  }

  @Test
  @DisplayName("onDone handles null fullText as empty summary")
  void onDoneNullText() {
    StreamConsumerResult result =
        SummaryDoneEnricher.INSTANCE.onDone(null, stubCtx(Map.of("docId", "doc-x")));
    assertEquals("", result.donePayloadEntries().get("summary"));
  }

  private static ConversationContext stubCtx(Map<String, Object> body) {
    return new ConversationContext() {
      @Override
      public io.justsearch.core.context.EngineContext engineContext() {
        return io.justsearch.app.services.TestEngineContexts.internal();
      }

      private final Map<String, Object> attrs = new HashMap<>();

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
        return Map.copyOf(body);
      }

      @Override
      public Map<String, Object> attributes() {
        return attrs;
      }
    };
  }
}
