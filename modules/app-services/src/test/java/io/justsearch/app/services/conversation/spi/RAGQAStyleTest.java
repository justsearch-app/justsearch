package io.justsearch.app.services.conversation.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.PromptFragment;
import io.justsearch.agent.api.registry.Audience;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RAGQAStyle} (slice 491 C3). */
final class RAGQAStyleTest {

  @Test
  @DisplayName("ID is stable and namespaced under core")
  void idIsCoreNamespaced() {
    assertEquals("core.rag-qa-style", RAGQAStyle.ID);
  }

  @Test
  @DisplayName("Singleton instance is reused")
  void singletonInstance() {
    assertNotNull(RAGQAStyle.INSTANCE);
    assertEquals(RAGQAStyle.INSTANCE, RAGQAStyle.INSTANCE);
  }

  @Test
  @DisplayName("contribute returns the RAG-ask prompt with priority 10")
  void contributeReturnsPromptAtPriority10() {
    PromptFragment fragment = RAGQAStyle.INSTANCE.contribute(stubCtx()).orElseThrow();
    assertEquals(10, fragment.priority());
    assertTrue(fragment.text().contains("excerpts"), "should reference the retrieved excerpts");
    assertTrue(
        fragment.text().toLowerCase().contains("do not appear to cover it"),
        "should instruct on missing-answer behavior");
  }

  /**
   * Tempdoc 845 defect 2 — the prompt must say whose documents these are.
   *
   * <p>Without it a thinking pass inferred the excerpts were system documentation rather than the
   * user's indexed files and prepended a false denial of file access. This asserts the property
   * that fixed it (ownership is stated), not the sentence that happens to state it.
   */
  @Test
  @DisplayName("prompt attributes the excerpts to the user's own indexed files")
  void promptNamesTheExcerptsAsTheUsersOwnFiles() {
    String text = RAGQAStyle.INSTANCE.contribute(stubCtx()).orElseThrow().text().toLowerCase();
    assertTrue(text.contains("user's own files"), "must say the files belong to the user");
    assertTrue(
        text.contains("retrieved from those files"),
        "must say the excerpts came from those files, so they are not read as generic samples");
    assertTrue(
        text.contains("not examples or system"),
        "must rule out the specific wrong inference the probe caught");
  }

  /**
   * Tempdoc 845 defect 2 — the say-so clause is scoped to answer CONTENT, never to access.
   *
   * <p>The regression is a model that answers "I don't have access to your files". The prompt must
   * both offer the honest alternative (coverage) and forbid the denial explicitly.
   */
  @Test
  @DisplayName("missing-answer instruction is about coverage, and access-denial is forbidden")
  void missingAnswerClauseIsScopedToCoverageNotAccess() {
    String text = RAGQAStyle.INSTANCE.contribute(stubCtx()).orElseThrow().text().toLowerCase();
    assertTrue(
        text.contains("do not say you lack access"),
        "must forbid claiming a lack of access to the user's files");
    assertTrue(
        text.contains("do not appear to cover it"),
        "must give coverage as the honest way to report a missing answer");
  }

  /**
   * Tempdoc 845 defect 2 — honesty in the other direction: the excerpts are a top-K retrieval and
   * may be trimmed to fit the window, so the prompt must not present them as the whole corpus.
   */
  @Test
  @DisplayName("prompt does not over-claim that the excerpts are every file")
  void promptDoesNotClaimCompleteCoverage() {
    String text = RAGQAStyle.INSTANCE.contribute(stubCtx()).orElseThrow().text().toLowerCase();
    assertTrue(
        text.contains("not necessarily every file"),
        "must not imply the excerpts are the user's complete corpus");
  }

  @Test
  @DisplayName("contribute is stateless — same result regardless of request body")
  void stateless() {
    var ctxA = stubCtxWithBody(Map.of("question", "foo"));
    var ctxB = stubCtxWithBody(Map.of("question", "bar", "extra", "data"));
    assertEquals(
        RAGQAStyle.INSTANCE.contribute(ctxA).orElseThrow().text(),
        RAGQAStyle.INSTANCE.contribute(ctxB).orElseThrow().text());
  }

  private static ConversationContext stubCtx() {
    return stubCtxWithBody(Map.of());
  }

  private static ConversationContext stubCtxWithBody(Map<String, Object> body) {
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
