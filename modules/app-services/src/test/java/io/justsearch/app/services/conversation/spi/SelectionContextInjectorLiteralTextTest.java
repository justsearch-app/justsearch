/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.InjectorResult;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.DocumentService.DocumentRecord;
import io.justsearch.app.api.DocumentService.VerificationSource;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 836 §5.7 / §A.5 — every arm of the selection injector publishes the text it actually put
 * in the user message as the text a citation matcher should verify against.
 *
 * <p>The mistake these tests forbid is reaching for {@code citation.excerpt()} because it is the
 * field named "the text": the excerpt is a deliberate 200-char DISPLAY preview, so verifying
 * against it would score a 200 KB selection's summary against 200 characters and report the result
 * as verification.
 */
@DisplayName("SelectionContextInjector — the verification text is the injected text")
final class SelectionContextInjectorLiteralTextTest {

  private static final String DOC = "/docs/pagination.md";

  /** Long enough that the 200-char excerpt is unmistakably a different string. */
  private static final String BODY =
      ("Pagination uses searchAfter with a cursor that encodes the sort key and a document "
              + "identifier, so the reader can resume exactly where the previous page stopped. ")
          .repeat(8);

  @Test
  @DisplayName("text-range: the selected slice is the verification text, not the preview")
  void textRangeSuppliesSlice() {
    var ctx =
        ctx(
            Map.of(
                "selection",
                Map.of(
                    "kind", "text-range",
                    "address",
                        Map.of(
                            "coords", "canonical",
                            "docId", DOC,
                            "startChar", 100,
                            "endChar", 900),
                    "selectionText", "ignored",
                    "hostEntity", Map.of("kind", "doc", "id", DOC))));

    InjectorResult result = injector().inject(ctx);

    assertMessageMatchesVerificationText(result, ctx);
    assertEquals(
        BODY.substring(100, 900), onlySource(ctx).literalText(), "the slice the user selected");
  }

  @Test
  @DisplayName("item: the fetched document text is the verification text")
  void itemSuppliesDocumentText() {
    var ctx =
        ctx(
            Map.of(
                "selection",
                Map.of("kind", "item", "itemKind", "search-hit", "itemId", DOC)));

    InjectorResult result = injector().inject(ctx);

    assertMessageMatchesVerificationText(result, ctx);
    assertEquals(BODY, onlySource(ctx).literalText());
  }

  @Test
  @DisplayName("citation: the cited passage's slice is the verification text")
  void citationSuppliesSlice() {
    var ctx =
        ctx(
            Map.of(
                "selection",
                Map.of(
                    "kind", "citation",
                    "citation",
                        Map.of(
                            "parentDocId", DOC,
                            "chunkIndex", 3,
                            "startChar", 50,
                            "endChar", 600,
                            "excerpt", "a short preview"))));

    InjectorResult result = injector().inject(ctx);

    assertMessageMatchesVerificationText(result, ctx);
    assertEquals(BODY.substring(50, 600), onlySource(ctx).literalText());
  }

  @Test
  @DisplayName("inline excerpt: the excerpt IS the injected text when the doc cannot be fetched")
  void inlineExcerptSuppliesTheInlineText() {
    var ctx =
        ctx(
            Map.of(
                "selection",
                Map.of(
                    "kind", "citation",
                    "citation",
                        Map.of(
                            "parentDocId", "/docs/missing.md",
                            "chunkIndex", 0,
                            "startChar", 0,
                            "endChar", 10,
                            "excerpt", "the only text this arm ever had"))));

    InjectorResult result = new SelectionContextInjector(new StubDocs(Map.of())).inject(ctx);

    assertMessageMatchesVerificationText(result, ctx);
    assertEquals("the only text this arm ever had", onlySource(ctx).literalText());
  }

  @Test
  @DisplayName("result-set: the arm stashes at all, with one source per injected document")
  void resultSetStashesEveryDocument() {
    String other = "Sourdough starters need a warm kitchen and a patient baker.";
    var docs =
        new StubDocs(
            Map.of(
                DOC, new DocumentRecord(DOC, BODY, Map.of()),
                "/docs/baking.md", new DocumentRecord("/docs/baking.md", other, Map.of())));
    var ctx =
        ctx(
            Map.of(
                "selection",
                Map.of(
                    "kind", "result-set",
                    "query", "pagination",
                    "items",
                        List.of(
                            Map.of("id", DOC, "kind", "search-hit"),
                            Map.of("id", "/docs/baking.md", "kind", "search-hit")))));

    InjectorResult result = new SelectionContextInjector(docs).inject(ctx);

    assertNotNull(
        ctx.attributes().get(RAGContext.ATTR_CITATIONS),
        "this arm emitted a rag.citations event but stashed nothing, so its citations were "
            + "invisible to every downstream consumer");
    assertEquals(true, ctx.attributes().get(RAGContext.ATTR_USED_RAG));

    List<VerificationSource> sources = sources(ctx);
    assertEquals(2, sources.size(), "one source per injected document");
    assertEquals(DOC, sources.get(0).citation().parentDocId());
    assertEquals(BODY, sources.get(0).literalText());
    assertEquals(other, sources.get(1).literalText());

    String content = (String) result.messages().get(0).get("content");
    assertTrue(content.contains(other), "the injected message carries both documents");
  }

  @Test
  @DisplayName("the citations attribute is derived from the same list, so the two cannot drift")
  void citationsAreDerivedFromTheSources() {
    var ctx =
        ctx(Map.of("selection", Map.of("kind", "item", "itemKind", "search-hit", "itemId", DOC)));

    injector().inject(ctx);

    @SuppressWarnings("unchecked")
    List<DocumentService.ContextCitation> citations =
        (List<DocumentService.ContextCitation>) ctx.attributes().get(RAGContext.ATTR_CITATIONS);
    List<VerificationSource> sources = sources(ctx);

    assertEquals(sources.size(), citations.size());
    for (int i = 0; i < sources.size(); i++) {
      assertEquals(sources.get(i).citation(), citations.get(i), "position " + i + " must agree");
    }
  }

  // ---- shared assertions ----

  /**
   * The invariant: whatever went into the user message is what a matcher verifies against. Also
   * asserts the verification text is NOT the excerpt, which is the specific wrong reach.
   */
  private static void assertMessageMatchesVerificationText(
      InjectorResult result, ConversationContext ctx) {
    String content = (String) result.messages().get(0).get("content");
    VerificationSource source = onlySource(ctx);

    assertTrue(source.suppliesText(), "the arm must supply text, not leave the matcher to look up");
    assertTrue(
        content.endsWith(source.literalText()),
        "the verification text must be the string placed in the user message");
    if (source.literalText().length() > 200) {
      assertFalse(
          source.literalText().equals(source.citation().excerpt()),
          "the 200-char excerpt is a display preview, never the verification payload");
    }
  }

  private static VerificationSource onlySource(ConversationContext ctx) {
    List<VerificationSource> sources = sources(ctx);
    assertEquals(1, sources.size(), "one selection yields one source");
    return sources.get(0);
  }

  @SuppressWarnings("unchecked")
  private static List<VerificationSource> sources(ConversationContext ctx) {
    Object stashed = ctx.attributes().get(RAGContext.ATTR_VERIFICATION_SOURCES);
    assertNotNull(stashed, "the injector must publish the text it injected");
    return (List<VerificationSource>) stashed;
  }

  private static SelectionContextInjector injector() {
    return new SelectionContextInjector(
        new StubDocs(Map.of(DOC, new DocumentRecord(DOC, BODY, Map.of()))));
  }

  // ---- fixtures ----

  private static ConversationContext ctx(Map<String, Object> body) {
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
        return "core.summarize";
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
