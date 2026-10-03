/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.concurrent.atomic.AtomicInteger;
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
  @DisplayName("every summarize selection source is checked before dispatch or truncation")
  void summarizeSelectionVariantsUseUntruncatedInputLimit() {
    String dense = "x".repeat(120);
    var docs =
        new StubDocs(
            Map.of(
                "/docs/a.md", new DocumentRecord("/docs/a.md", dense, Map.of()),
                "/docs/b.md", new DocumentRecord("/docs/b.md", dense, Map.of())));

    for (Map<String, Object> selection : summarySelections(dense)) {
      AtomicInteger reads = new AtomicInteger();
      var injector =
          new SelectionContextInjector(
              docs,
              () -> null,
              () -> {
                reads.incrementAndGet();
                return 2;
              });

      ConversationContext context =
          ctx(SummarizeShape.ID.value(), Map.of("selection", selection));
      InjectorResult result = injector.inject(context);

      var error = result.terminalError().orElseThrow();
      assertEquals("CONTEXT_TOO_LARGE", error.payload().get("errorCode"), selection.toString());
      assertEquals(2, error.payload().get("maxTokens"));
      if ("result-set".equals(selection.get("kind"))) {
        String exact =
            "Document: /docs/a.md\n\n" + dense
                + DocumentService.SECTION_SEPARATOR
                + "Document: /docs/b.md\n\n" + dense;
        assertEquals(
            io.justsearch.core.util.TokenEstimation.estimateTokens(exact),
            error.payload().get("estimatedTokens"),
            "result sets are estimated as their exact combined input, not per-doc estimates");
      } else if ("health-condition".equals(selection.get("kind"))) {
        String exact = "Health condition worker.ready (severity=warning):\n\n" + dense;
        assertEquals(
            io.justsearch.core.util.TokenEstimation.estimateTokens(exact),
            error.payload().get("estimatedTokens"),
            "health summary must estimate the exact constructed message");
      } else if ("search-trace".equals(selection.get("kind"))) {
        String exact =
            "Explain, in plain language, what this search did and why — based on the pipeline "
                + "trace below.\n\nSearch trace:\n" + dense;
        assertEquals(
            io.justsearch.core.util.TokenEstimation.estimateTokens(exact),
            error.payload().get("estimatedTokens"),
            "search trace must estimate the exact constructed message");
      }
      assertTrue(
          error.payload().get("error").toString()
              .contains("configured summary source-input limit of 2 tokens"));
      assertEquals(1, reads.get(), "one hot snapshot read for " + selection.get("kind"));
      assertTrue(result.messages().isEmpty());
      assertTrue(
          context.attributes().isEmpty(),
          "a refused selection must not publish citation/RAG attributes: " + selection.get("kind"));
    }
  }

  @Test
  @DisplayName("non-summary selection variants never consult the summary input limit")
  void nonSummarySelectionVariantsIgnoreSummaryLimit() {
    String dense = "x".repeat(120);
    var docs =
        new StubDocs(
            Map.of(
                "/docs/a.md", new DocumentRecord("/docs/a.md", dense, Map.of()),
                "/docs/b.md", new DocumentRecord("/docs/b.md", dense, Map.of())));
    var selections = summarySelections(dense);

    for (Map<String, Object> selection : selections) {
      AtomicInteger reads = new AtomicInteger();
      var injector =
          new SelectionContextInjector(
              docs,
              () -> null,
              () -> {
                reads.incrementAndGet();
                return 1;
              });

      InjectorResult result =
          injector.inject(ctx(ExtractShape.ID.value(), Map.of("selection", selection)));

      assertFalse(result.terminalError().isPresent(), selection.toString());
      assertEquals(0, reads.get(), "non-summary path read the summary limit: " + selection);
    }
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

  private static List<Map<String, Object>> summarySelections(String dense) {
    return List.of(
        Map.of(
            "kind", "text-range",
            "address",
                Map.of(
                    "coords", "canonical",
                    "docId", "/docs/a.md",
                    "startChar", 0,
                    "endChar", dense.length()),
            "selectionText", dense,
            "hostEntity", Map.of("kind", "doc", "id", "/docs/a.md")),
        Map.of(
            "kind", "item",
            "itemKind", "search-hit",
            "itemId", "/docs/a.md"),
        Map.of(
            "kind", "citation",
            "citation",
                Map.of(
                    "parentDocId", "/docs/a.md",
                    "startChar", 0,
                    "endChar", dense.length(),
                    "excerpt", dense)),
        Map.of(
            "kind", "citation",
            "citation",
                Map.of(
                    "parentDocId", "/docs/missing.md",
                    "startChar", 0,
                    "endChar", dense.length(),
                    "excerpt", dense)),
        Map.of(
            "kind", "result-set",
            "items",
                List.of(
                    Map.of("id", "/docs/a.md", "kind", "search-hit"),
                    Map.of("id", "/docs/b.md", "kind", "search-hit")),
            "query", "dense"),
        Map.of(
            "kind", "health-condition",
            "conditionId", "worker.ready",
            "severity", "warning",
            "summary", dense),
        Map.of("kind", "search-trace", "scope", "query", "summary", dense));
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
