package io.justsearch.app.services.conversation.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.InjectorResult;
import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.DocumentService.DocumentRecord;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link BatchDocAccess} (tempdoc 491 §C2.2). */
final class BatchDocAccessTest {

  @Test
  @DisplayName("Empty docIds returns a NO_FILES terminal error")
  void emptyDocIds() {
    var injector = new BatchDocAccess(new StubDocs(Map.of()));
    InjectorResult result = injector.inject(stubCtx(Map.of()));

    assertTrue(result.terminalError().isPresent());
    SseEvent err = result.terminalError().get();
    assertEquals("error", err.name());
    assertEquals("NO_FILES", err.payload().get("errorCode"));
    assertTrue(result.messages().isEmpty());
  }

  @Test
  @DisplayName("Multiple docs are concatenated with the legacy delimiter")
  void multiDocConcat() {
    var docs =
        new StubDocs(
            Map.of(
                "a.txt", new DocumentRecord("a.txt", "alpha content", Map.of()),
                "b.txt", new DocumentRecord("b.txt", "beta content", Map.of())));
    var injector = new BatchDocAccess(docs);

    InjectorResult result =
        injector.inject(stubCtx(Map.of("docIds", List.of("a.txt", "b.txt"))));

    assertFalse(result.terminalError().isPresent());
    assertEquals(1, result.messages().size());
    String content = (String) result.messages().get(0).get("content");
    assertNotNull(content);
    assertTrue(content.contains("--- File: a.txt ---"));
    assertTrue(content.contains("alpha content"));
    assertTrue(content.contains("--- File: b.txt ---"));
    assertTrue(content.contains("beta content"));
  }

  @Test
  @DisplayName("Progress event is emitted with totalFiles before the LLM call")
  void progressEventEmitted() {
    var docs = new StubDocs(Map.of("doc", new DocumentRecord("doc", "x", Map.of())));
    var injector = new BatchDocAccess(docs);

    InjectorResult result = injector.inject(stubCtx(Map.of("docIds", List.of("doc"))));

    assertEquals(1, result.events().size());
    SseEvent progress = result.events().get(0);
    assertEquals("progress", progress.name());
    assertEquals("files", progress.payload().get("phase"));
    assertEquals(1, progress.payload().get("totalFiles"));
  }

  @Test
  @DisplayName("Empty-content docs are skipped; NO_CONTENT terminal error when all empty")
  void allEmptyContent() {
    var docs =
        new StubDocs(
            Map.of(
                "doc", new DocumentRecord("doc", "", Map.of())));
    var injector = new BatchDocAccess(docs);

    InjectorResult result = injector.inject(stubCtx(Map.of("docIds", List.of("doc"))));

    assertTrue(result.terminalError().isPresent());
    assertEquals("NO_CONTENT", result.terminalError().get().payload().get("errorCode"));
  }

  @Test
  @DisplayName("Content is truncated to MAX_CONTENT_CHARS")
  void truncation() {
    String huge = "x".repeat(BatchDocAccess.MAX_CONTENT_CHARS * 2);
    var docs = new StubDocs(Map.of("doc", new DocumentRecord("doc", huge, Map.of())));
    var injector = new BatchDocAccess(docs);

    InjectorResult result = injector.inject(stubCtx(Map.of("docIds", List.of("doc"))));

    String content = (String) result.messages().get(0).get("content");
    // The 200K cap is on the concatenated content (post-delimiter); message text adds the
    // "Summarize the following documents:\n\n" prefix.
    assertTrue(
        content.length() <= BatchDocAccess.MAX_CONTENT_CHARS + 100,
        "content should be truncated near MAX_CONTENT_CHARS");
  }

  @Test
  @DisplayName("docIds + fileCount are stored in ctx.attributes() for downstream consumers")
  void attributesPopulated() {
    var docs = new StubDocs(Map.of("doc", new DocumentRecord("doc", "x", Map.of())));
    var injector = new BatchDocAccess(docs);
    var ctx = stubCtx(Map.of("docIds", List.of("doc")));

    injector.inject(ctx);

    assertEquals(List.of("doc"), ctx.attributes().get("batch.docIds"));
    assertEquals(1, ctx.attributes().get("batch.fileCount"));
  }

  // ---- fixtures ----

  private static ConversationContext stubCtx(Map<String, Object> body) {
    return new ConversationContext() {
      @Override
      public io.justsearch.core.context.EngineContext engineContext() {
        return io.justsearch.app.services.TestEngineContexts.internal();
      }

      private final Map<String, Object> attrs = new HashMap<>();
      private final Map<String, Object> bodyCopy = new LinkedHashMap<>(body);

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
        return bodyCopy;
      }

      @Override
      public Map<String, Object> attributes() {
        return attrs;
      }
    };
  }

  private static final class StubDocs implements DocumentService {
    private final Map<String, DocumentRecord> docs;

    StubDocs(Map<String, DocumentRecord> docs) {
      this.docs = docs;
    }

    @Override
    public CompletionStage<DocumentRecord> fetch(String docId, io.justsearch.core.context.EngineContext engineContext) {
      return CompletableFuture.completedFuture(docs.get(docId));
    }

    @Override
    public CompletionStage<Map<String, DocumentRecord>> fetchBatch(List<String> docIds, io.justsearch.core.context.EngineContext engineContext) {
      Map<String, DocumentRecord> out = new LinkedHashMap<>();
      for (String id : docIds) {
        DocumentRecord r = docs.get(id);
        if (r != null) {
          out.put(id, r);
        }
      }
      return CompletableFuture.completedFuture(out);
    }
  }
}
