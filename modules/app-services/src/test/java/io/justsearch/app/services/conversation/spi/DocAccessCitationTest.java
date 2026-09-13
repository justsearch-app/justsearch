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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 836 S2S3 stage 3 — the doc path publishes what it injected.
 *
 * <p>Before this, {@code DocAccess} returned messages only: no {@code ATTR_CITATIONS}, no {@code
 * rag.citations} event, no verification sources. The citation matcher was therefore STARVED on the
 * whole-document summarize path — it returned empty because nothing was ever stashed for it (833
 * finding 4), and the summarize surface could not show grounding no matter what the frontend did.
 *
 * <p>The §8.4 decision is pinned here too: the citation carries the ABSENT chunk sentinel, never a
 * {@code 0}. A whole document has no chunk ordinal, and {@code 0} is a claim that the text is the
 * document's first chunk — the fabrication the whole design exists to remove.
 */
@DisplayName("DocAccess — the doc path publishes its citation and verification source")
final class DocAccessCitationTest {

  private static final String DOC = "/docs/lucene.md";
  private static final String BODY =
      ("Pagination uses searchAfter with a cursor that encodes the sort key and a document "
              + "identifier, so the reader can resume exactly where the previous page stopped. ")
          .repeat(8);

  @Test
  @DisplayName("stashes the injected text as the verification source, not the excerpt")
  void suppliesInjectedText() {
    var ctx = ctx(Map.of("docId", DOC));

    InjectorResult result = injector().inject(ctx);

    VerificationSource source = onlySource(ctx);
    assertEquals(BODY, source.literalText(), "the text the model was shown is the text verified");
    assertTrue(
        result.messages().get(0).get("content").toString().contains(BODY),
        "and it is the same string that went into the user message");
    assertNotEquals(
        source.citation().excerpt(),
        source.literalText(),
        "the excerpt is a display preview; verifying against it would check a document against"
            + " 200 characters of itself");
    assertEquals(200, source.citation().excerpt().length());
  }

  @Test
  @DisplayName("the citation carries the ABSENT chunk sentinel, never a fabricated 0")
  void modelsTheAbsentChunkOrdinal() {
    var ctx = ctx(Map.of("docId", DOC));

    injector().inject(ctx);

    ContextCitation citation = onlySource(ctx).citation();
    assertEquals(
        ContextCitation.CHUNK_INDEX_ABSENT,
        citation.chunkIndex(),
        "a whole document has no chunk ordinal — 0 would claim it is the document's FIRST chunk");
    assertEquals(DOC, citation.parentDocId());
    assertEquals(0, citation.startChar());
    assertEquals(BODY.length(), citation.endChar(), "the citation spans the text that was injected");
  }

  @Test
  @DisplayName("emits rag.citations so the source is visible, and marks the turn as RAG")
  void emitsCitationsEvent() {
    var ctx = ctx(Map.of("docId", DOC));

    InjectorResult result = injector().inject(ctx);

    assertEquals(1, result.events().size());
    assertEquals("rag.citations", result.events().get(0).name());
    @SuppressWarnings("unchecked")
    var citations =
        (List<Map<String, Object>>) result.events().get(0).payload().get("citations");
    assertEquals(DOC, citations.get(0).get("parentDocId"));
    assertEquals(
        ContextCitation.CHUNK_INDEX_ABSENT,
        citations.get(0).get("chunkIndex"),
        "the event says the same thing the stashed citation does");
    assertEquals(Boolean.TRUE, ctx.attributes().get(RAGContext.ATTR_USED_RAG));
    assertNotNull(ctx.attributes().get(RAGContext.ATTR_CITATIONS));
  }

  @Test
  @DisplayName("inline content with no resolvable document mints NO citation")
  void inlineContentHasNoCitableIdentity() {
    // There is no document to name here, and naming one anyway is how a source that does not exist
    // acquires provenance. Messages only, exactly as before.
    var ctx = ctx(Map.of("content", "Some text pasted straight into the request."));

    InjectorResult result = injector().inject(ctx);

    assertFalse(result.messages().isEmpty());
    assertTrue(result.events().isEmpty());
    assertNull(ctx.attributes().get(RAGContext.ATTR_VERIFICATION_SOURCES));
    assertNull(ctx.attributes().get(RAGContext.ATTR_CITATIONS));
  }

  @Test
  @DisplayName("a docId whose fetch fails does not attribute the fallback text to that document")
  void failedFetchIsNotAttributed() {
    // The inline fallback is NOT this document's text. Stashing it under `docId` would report a
    // verification of a document against a string that never came from it.
    var ctx = ctx(Map.of("docId", "/docs/missing.md", "content", "Unrelated pasted text."));

    InjectorResult result = injector().inject(ctx);

    assertFalse(result.messages().isEmpty());
    assertTrue(result.events().isEmpty());
    assertNull(ctx.attributes().get(RAGContext.ATTR_VERIFICATION_SOURCES));
  }

  @Test
  @DisplayName("a typed selection still defers to the selection injector")
  void selectionStillDefers() {
    var ctx = ctx(Map.of("docId", DOC, "selection", Map.of("kind", "item")));

    InjectorResult result = injector().inject(ctx);

    assertTrue(result.messages().isEmpty(), "the selection injector owns that request");
    assertNull(ctx.attributes().get(RAGContext.ATTR_CITATIONS));
  }

  // ==================== fixtures ====================

  private static DocAccess injector() {
    return new DocAccess(new StubDocs(Map.of(DOC, new DocumentRecord(DOC, BODY, Map.of()))));
  }

  @SuppressWarnings("unchecked")
  private static VerificationSource onlySource(ConversationContext ctx) {
    var sources =
        (List<VerificationSource>) ctx.attributes().get(RAGContext.ATTR_VERIFICATION_SOURCES);
    assertNotNull(sources, "the doc path must publish a verification source");
    assertEquals(1, sources.size());
    return sources.get(0);
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
}
