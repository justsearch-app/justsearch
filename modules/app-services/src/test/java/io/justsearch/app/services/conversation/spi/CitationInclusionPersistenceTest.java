/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.StreamConsumerResult;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.app.api.DocumentService.ContextCitation;
import io.justsearch.app.api.DocumentService.ContextInclusion;
import io.justsearch.app.services.conversation.FileConversationStore;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 849 §5.4 / review D-11 — the inclusion state must survive the RECORD, not just the live
 * stream.
 *
 * <p>This is the second, independent argument for putting inclusion on {@code ContextCitation}
 * rather than on {@code ragMeta}: {@code ragMeta} is not persisted, so a meta-level list would
 * evaporate on reload and the pane would be honest live and silent on the record it is most likely
 * inspected from. That is a claim about the STORE, so a stream-only assertion would prove the wrong
 * thing — this walks the real path: enricher projection → persisted assistant turn →
 * {@link FileConversationStore} JSON round-trip → reloaded history.
 */
@DisplayName("Citation inclusion — persistence round-trip (tempdoc 849)")
final class CitationInclusionPersistenceTest {

  @Test
  @DisplayName("849 D-11: a dropped citation is persisted and re-read with its state intact")
  void droppedSurvivesPersistAndReload(@TempDir Path storeRoot) {
    Map<String, Object> persisted =
        persistAssistantTurn(storeRoot, citation(ContextInclusion.dropped()));

    Map<String, Object> reloaded = firstCitationOf(persisted);
    assertEquals(
        "dropped",
        reloaded.get("contextInclusion"),
        "a reloaded conversation must still know which of its sources the model never saw");
    assertEquals(0, ((Number) reloaded.get("contextIncludedChars")).intValue());
  }

  @Test
  @DisplayName("849 D-11: a partial citation keeps its included-character count across the store")
  void partialSurvivesPersistAndReload(@TempDir Path storeRoot) {
    Map<String, Object> persisted =
        persistAssistantTurn(storeRoot, citation(ContextInclusion.partial(137)));

    Map<String, Object> reloaded = firstCitationOf(persisted);
    assertEquals("partial", reloaded.get("contextInclusion"));
    assertEquals(137, ((Number) reloaded.get("contextIncludedChars")).intValue());
  }

  @Test
  @DisplayName("849 D-11: a pre-849 (ABSENT) citation persists with NO inclusion key at all")
  void absentIsPersistedAsAbsence(@TempDir Path storeRoot) {
    Map<String, Object> persisted =
        persistAssistantTurn(storeRoot, citation(ContextInclusion.ABSENT));

    Map<String, Object> reloaded = firstCitationOf(persisted);
    // Absence must round-trip AS absence. Writing a fourth state (or defaulting to "included")
    // would retroactively describe every conversation saved before this field existed.
    assertFalse(reloaded.containsKey("contextInclusion"), reloaded.toString());
    assertFalse(reloaded.containsKey("contextIncludedChars"), reloaded.toString());
    // The rest of the record still made it, so this is absence — not a dropped citation.
    assertEquals("doc-1", reloaded.get("parentDocId"));
  }

  private static ContextCitation citation(ContextInclusion inclusion) {
    return new ContextCitation(
        "doc-1", 0, 3, 100, 200, 0.85f, "the excerpt", 10, 12, "Heading", 2, inclusion);
  }

  /**
   * Runs the real chain: {@code RAGDoneEnricher} projects the stashed citations onto the done
   * payload, {@code ConversationEngine} copies that entry onto the persisted assistant turn (see
   * {@code persistedAssistant}), and the store serialises it.
   */
  private static Map<String, Object> persistAssistantTurn(Path storeRoot, ContextCitation c) {
    ConversationContext ctx =
        stubCtx(
            Map.of(
                RAGContext.ATTR_FILE_COUNT, 1,
                RAGContext.ATTR_USED_RAG, true,
                RAGContext.ATTR_CHUNKS_USED, 1,
                RAGContext.ATTR_CHUNKS_FOUND, 3,
                RAGContext.ATTR_CITATIONS, List.of(c)));
    StreamConsumerResult done = RAGDoneEnricher.INSTANCE.onDone("the answer", ctx);

    Map<String, Object> assistant = new LinkedHashMap<>();
    assistant.put("role", "assistant");
    assistant.put("content", "the answer");
    assistant.put("citations", done.donePayloadEntries().get("citations"));

    var store = new FileConversationStore(storeRoot);
    store.appendMessage("session-849", "rag-ask", assistant);

    List<Map<String, Object>> history = store.loadHistory("session-849");
    assertEquals(1, history.size(), "exactly the one turn we wrote");
    return history.get(0);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> firstCitationOf(Map<String, Object> turn) {
    List<Map<String, Object>> citations = (List<Map<String, Object>>) turn.get("citations");
    assertTrue(citations != null && !citations.isEmpty(), "the turn lost its citations entirely");
    return citations.get(0);
  }

  private static ConversationContext stubCtx(Map<String, Object> attrs) {
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
        return "session-849";
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
