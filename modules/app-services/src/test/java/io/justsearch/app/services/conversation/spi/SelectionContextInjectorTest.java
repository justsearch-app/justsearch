/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.InjectorResult;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.ConversationShape;
import io.justsearch.agent.api.registry.EventDescriptor;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.DocumentService.DocumentRecord;
import io.justsearch.app.services.conversation.shapes.ExtractShape;
import io.justsearch.app.services.conversation.shapes.SummarizeShape;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The extract shape's document channel, and the requesting-shape-awareness the shared
 * {@link SelectionContextInjector} needs once more than one shape declares it.
 */
final class SelectionContextInjectorTest {

  @Test
  @DisplayName("ExtractShape declares the selection injector, before the user prompt")
  void extractDeclaresSelectionInjector() {
    List<String> injectors = ExtractShape.definition().contextInjectorIds();
    assertTrue(
        injectors.contains(SelectionContextInjector.ID),
        "ExtractShape must declare a document-bearing injector, else extraction runs the schema "
            + "constraint against the prompt and chat history alone: "
            + injectors);
    assertTrue(
        injectors.indexOf(SelectionContextInjector.ID) < injectors.indexOf("core.user-prompt"),
        "injected messages compose in declaration order — the document must precede the "
            + "extraction instruction: "
            + injectors);
  }

  @Test
  @DisplayName("ExtractShape declares the rag.citations event its selection injector emits")
  void extractDeclaresCitationsEvent() {
    List<String> declared =
        ExtractShape.definition().eventSchema().stream().map(EventDescriptor::name).toList();
    assertTrue(
        declared.contains("rag.citations"),
        "SelectionContextInjector emits rag.citations for the item/text-range/citation arms; "
            + "the shape's declared event vocabulary must match what it actually emits: "
            + declared);
  }

  @Test
  @DisplayName("An item selection injects the real document content the extraction runs on")
  void itemSelectionInjectsDocumentContent() {
    var injector =
        new SelectionContextInjector(
            new StubDocs(
                Map.of("/docs/invoice.md", new DocumentRecord("/docs/invoice.md", "Total: 42 EUR", Map.of()))));

    InjectorResult result =
        injector.inject(
            ctx(
                ExtractShape.ID.value(),
                Map.of(
                    "selection",
                    Map.of("kind", "item", "itemKind", "search-hit", "itemId", "/docs/invoice.md"))));

    assertEquals(1, result.messages().size());
    String content = (String) result.messages().get(0).get("content");
    assertNotNull(content);
    assertTrue(content.contains("Total: 42 EUR"), "document content must reach the LLM: " + content);
  }

  @Test
  @DisplayName("The text-range prefix follows the requesting shape, not a hardcoded summarize verb")
  void textRangePrefixFollowsRequestingShape() {
    var docs =
        new StubDocs(
            Map.of("/docs/a.md", new DocumentRecord("/docs/a.md", "0123456789abcdefghij", Map.of())));
    Map<String, Object> body =
        Map.of(
            "selection",
            Map.of(
                "kind",
                "text-range",
                "address",
                Map.of("coords", "canonical", "docId", "/docs/a.md", "startChar", 0, "endChar", 10),
                "selectionText",
                "0123456789",
                "hostEntity",
                Map.of("kind", "doc", "id", "/docs/a.md")));

    String extractContent =
        (String)
            new SelectionContextInjector(docs)
                .inject(ctx(ExtractShape.ID.value(), body))
                .messages()
                .get(0)
                .get("content");
    assertTrue(
        extractContent.startsWith("Use the following selected passage as context:"),
        "an extraction must not be told to summarize its input: " + extractContent);

    String summarizeContent =
        (String)
            new SelectionContextInjector(docs)
                .inject(ctx(SummarizeShape.ID.value(), body))
                .messages()
                .get(0)
                .get("content");
    assertTrue(
        summarizeContent.startsWith("Summarize the following selection:"),
        "SummarizeShape declares no core.user-prompt injector, so this prefix is its only "
            + "instruction and must be preserved: "
            + summarizeContent);
  }

  @Test
  @DisplayName("The injector's summarize-shape literal still matches SummarizeShape.ID")
  void summarizeShapeIdLiteralHasNotDrifted() {
    // The injector lives in `spi` and deliberately does not import `shapes` (the dependency runs
    // shapes -> spi). This assertion is what keeps the literal honest instead.
    assertEquals(SummarizeShape.ID.value(), SelectionContextInjector.SUMMARIZE_SHAPE_ID);
  }

  @Test
  @DisplayName("Every injector ExtractShape declares is a real, registered id")
  void extractInjectorIdsAreReal() {
    ConversationShape extract = ExtractShape.definition();
    for (String id : extract.contextInjectorIds()) {
      assertTrue(
          List.of(
                  SelectionContextInjector.ID,
                  ExternalContextInjector.ID,
                  UserPromptInjector.INSTANCE.id())
              .contains(id),
          "unknown context injector id declared by ExtractShape: " + id);
    }
  }

  // ---- fixtures ----

  private static ConversationContext ctx(String shapeId, Map<String, Object> body) {
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
        return shapeId;
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
