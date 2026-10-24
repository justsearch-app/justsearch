/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.InjectorResult;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.DocumentService.ContextCitation;
import io.justsearch.app.api.DocumentService.DocumentRecord;
import io.justsearch.app.api.DocumentService.VerificationSource;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import io.justsearch.app.services.conversation.shapes.SummarizeShape;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class SummarizeHandoffTest {
  @Test
  void presentIndexedFileStillResolves(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
    var path = java.nio.file.Files.writeString(dir.resolve("present.md"), "current file");
    for (String shape : List.of("core.rag-ask", "core.summarize")) {
      var docs = new StubDocs(Map.of(path.toString(), new DocumentRecord(path.toString(), "current file", Map.of())));
      var result = new SelectionContextInjector(docs).inject(ctx(shape, Map.of("prompt", "Read this file", "selection",
          Map.of("kind", "result-set", "items", List.of(Map.of("id", path.toString(), "kind", "doc"))))));
      assertTrue(result.terminalError().isEmpty(), "an existing selected file must keep working");
    }
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "deletedIndexedFileIsRefusedBeforeFetching({0})")
  @org.junit.jupiter.params.provider.ValueSource(strings = {"core.rag-ask", "core.summarize"})
  void deletedIndexedFileIsRefusedBeforeFetching(String shape, @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
    var path = java.nio.file.Files.writeString(dir.resolve("chosen.md"), "DELETED-PRIVATE-RELIC-917");
    var reads = new AtomicInteger();
    DocumentService cached = new DocumentService() {
      @Override
      public CompletionStage<DocumentRecord> fetch(String docId, io.justsearch.core.context.EngineContext engineContext) {
        reads.incrementAndGet();
        return CompletableFuture.completedFuture(new DocumentRecord(docId, "DELETED-PRIVATE-RELIC-917", Map.of()));
      }
    };
    java.nio.file.Files.delete(path);
    var result = new SelectionContextInjector(cached).inject(ctx(shape, Map.of("prompt", "Read this file", "selection",
        Map.of("kind", "result-set", "items", List.of(Map.of("id", path.toString(), "kind", "doc"))))));
    assertTrue(result.terminalError().isPresent(), "a deleted selected file must refuse even when its index still has content");
    assertEquals(0, reads.get(), "a deleted file must not read cached index content");
  }

  @Test
  void unreadableAskSelectionIsRefused() {
    var result = new SelectionContextInjector(new StubDocs(Map.of())).inject(ctx("core.rag-ask", Map.of(
        "question", "What is in this file?", "selection", Map.of("kind", "result-set", "items", List.of(Map.of("id", "deleted.md", "kind", "doc"))))));
    assertEquals("DOC_UNAVAILABLE", result.terminalError().map(e -> e.payload().get("errorCode")).orElse(null));
  }

  @Test
  void unreadableDocumentIsRefused() {
    var result = new DocAccess(new StubDocs(Map.of())).inject(ctx(Map.of("docId", "deleted.md", "prompt", "Summarize the risks")));
    assertEquals("DOC_UNAVAILABLE", result.terminalError().map(e -> e.payload().get("errorCode")).orElse(null));
  }

  @Test
  void editedInstructionReachesModel() {
    var ctx = ctx(Map.of("content", "document text", "prompt", "Only describe the risks."));
    var messages = injectShape(ctx);
    assertTrue(messages.stream().anyMatch(m -> "Only describe the risks.".equals(m.get("content"))),
        "edited instruction must reach the model through the declared shape injectors");
  }

  @Test
  void promptlessContentKeepsDefaultTask() {
    var messages = injectShape(ctx(Map.of("content", "document text")));
    assertTrue(messages.stream().anyMatch(m -> m.get("content").toString().contains("Summarize the following document:")));
    assertTrue(messages.stream().anyMatch(m -> m.get("content").toString().contains("document text")));
  }

  @Test
  void promptlessDocumentKeepsDefaultTaskAndCitation() {
    var ctx = ctx(Map.of("docId", "a.md"));
    var messages = injectShape(ctx);
    assertTrue(messages.stream().anyMatch(m -> m.get("content").toString().contains("Summarize the following document:")));
    assertNotNull(ctx.attributes().get(RAGContext.ATTR_CITATIONS));
  }

  @Test
  void summarizeRefusesUnreadableMembers() {
    var result = selection(List.of("a.md", "missing.md"), "short");
    assertEquals("DOC_UNAVAILABLE", result.terminalError().map(e -> e.payload().get("errorCode")).orElse(null));
  }

  @Test
  void summarizeDeduplicatesDocuments() {
    var result = selection(List.of("a.md", "a.md"), "short");
    assertTrue(result.terminalError().isEmpty());
    assertEquals(1, ((List<?>) result.events().get(0).payload().get("citations")).size());
  }

  @Test
  void summarizeRefusesExcessDocuments() {
    var result = selection(List.of("a.md", "b.md", "c.md", "d.md", "e.md", "f.md"), "short");
    assertEquals("TOO_MANY_FILES", result.terminalError().map(e -> e.payload().get("errorCode")).orElse(null));
  }

  @Test
  void summarizeRefusesPerDocumentTruncationBelowTotalLimit() {
    var result = selection(List.of("a.md"), "x".repeat(3000));
    assertEquals("INPUT_TOO_LARGE", result.terminalError().map(e -> e.payload().get("errorCode")).orElse(null));
  }

  @Test
  void summarizeRefusesSelectionWindowTruncation() {
    var docs = new StubDocs(Map.of("a.md", new DocumentRecord("a.md", "x".repeat(100_000), Map.of())));
    var injector = new SelectionContextInjector(docs, java.time.Duration.ofSeconds(1), null, () -> 1_000_000);
    var result = injector.inject(ctx(Map.of("selection", Map.of("kind", "item", "itemKind", "browse-node", "itemId", "a.md"))));
    assertEquals("INPUT_TOO_LARGE", result.terminalError().map(e -> e.payload().get("errorCode")).orElse(null));
  }

  @Test
  void summarizeRefusesLegacyCharacterTruncation() {
    var result = new DocAccess(new StubDocs(Map.of()), () -> 1_000_000)
        .inject(ctx(Map.of("content", "x".repeat(200_001))));
    assertEquals("INPUT_TOO_LARGE", result.terminalError().map(e -> e.payload().get("errorCode")).orElse(null));
  }

  private static InjectorResult selection(List<String> ids, String content) {
    var records = new HashMap<String, DocumentRecord>();
    for (String id : ids) if (!id.equals("missing.md")) records.put(id, new DocumentRecord(id, content, Map.of()));
    return new SelectionContextInjector(new StubDocs(records)).inject(ctx(Map.of("selection", Map.of(
        "kind", "result-set", "items", ids.stream().map(id -> Map.of("id", id, "kind", "doc")).toList()))));
  }

  @Test
  void ragAskDeclaresSelectionBeforeRetrieval() {
    var injectors = io.justsearch.app.services.conversation.shapes.RAGAskShape.definition().contextInjectorIds();
    assertTrue(injectors.contains(SelectionContextInjector.ID), "the Ask shape must consume a passage hand-off");
    assertTrue(injectors.indexOf(SelectionContextInjector.ID) < injectors.indexOf(RAGContext.ID));
  }

  @Test
  void ragAskUsesSelectedPassageWithoutSearchingOtherDocuments() {
    var ctx = ctx("core.rag-ask", Map.of("question", "What does this mean?", "docIds", List.of("a.md"),
        "selection", Map.of("kind", "text-range", "address", Map.of("coords", "canonical", "docId", "a.md", "startChar", 2, "endChar", 7),
        "selectionText", "23456", "hostEntity", Map.of("kind", "doc", "id", "a.md"))));
    var docs = new StubDocs(Map.of("a.md", new DocumentRecord("a.md", "0123456789", Map.of())));
    var selected = new SelectionContextInjector(docs).inject(ctx);
    assertTrue(selected.messages().get(0).get("content").toString().contains("23456"));
    var result = new RAGContext(docs).inject(ctx);
    assertTrue(result.terminalError().isEmpty());
    assertEquals(List.of(Map.of("role", "user", "content", "What does this mean?")), result.messages(),
        "retrieval must not replace the selected passage with another chunk");
  }

  private static List<Map<String, Object>> injectShape(ConversationContext ctx) {
    var messages = new java.util.ArrayList<Map<String, Object>>();
    var docs = new StubDocs(Map.of("a.md", new DocumentRecord("a.md", "document text", Map.of())));
    for (String id : SummarizeShape.definition().contextInjectorIds()) {
      var result = switch (id) {
        case SelectionContextInjector.ID -> new SelectionContextInjector(docs).inject(ctx);
        case DocAccess.ID -> new DocAccess(docs).inject(ctx);
        case UserPromptInjector.ID -> UserPromptInjector.INSTANCE.inject(ctx);
        default -> throw new AssertionError("Unexpected injector " + id);
      };
      assertTrue(result.terminalError().isEmpty(), "promptless Summarize must remain valid: " + result.terminalError());
      messages.addAll(result.messages());
    }
    return messages;
  }

  private static final class StubDocs implements DocumentService {
    private final Map<String, DocumentRecord> byId;

    StubDocs(Map<String, DocumentRecord> byId) {
      this.byId = byId;
    }

    @Override
    public CompletionStage<DocumentRecord> fetch(String docId, io.justsearch.core.context.EngineContext engineContext) {
      return CompletableFuture.completedFuture(byId.get(docId));
    }
  }

  private static ConversationContext ctx(Map<String, Object> body) {
    return ctx("core.summarize", body);
  }

  private static ConversationContext ctx(String shape, Map<String, Object> body) {
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
      public String shapeId() {
        return shape;
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
}
