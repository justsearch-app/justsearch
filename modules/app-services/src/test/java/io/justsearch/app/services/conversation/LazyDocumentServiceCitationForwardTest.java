/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.DocumentService.CitationMatchEntry;
import io.justsearch.app.api.DocumentService.CitationMatchResult;
import io.justsearch.app.api.DocumentService.ContextCitation;
import io.justsearch.app.api.DocumentService.ContextInclusion;
import io.justsearch.app.api.DocumentService.ScorerKind;
import io.justsearch.app.api.DocumentService.TextSource;
import io.justsearch.app.api.DocumentService.VerificationSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 836 §1.1 — the late-binding proxy must forward the literal-text overload.
 *
 * <p>Without the override the call lands on {@link DocumentService}'s empty default and returns a
 * zero result. Nothing fails: the build is green, the response is well-formed, and the seam is
 * silently inert — every selection is reported as ungrounded because its text never reached the
 * Worker. That is the specific failure this test makes unconstructible.
 */
@DisplayName("LazyDocumentService — literal-text citation forwarding")
final class LazyDocumentServiceCitationForwardTest {

  private static final ContextCitation CITATION =
      new ContextCitation(
          "/docs/a.md", 0, 1, 0, 40, 1.0f, "preview", 0, 0, "", 0, ContextInclusion.ABSENT);

  @Test
  @DisplayName("the supplied passage text reaches the delegate, not an empty default")
  void forwardsLiteralText() {
    AtomicReference<List<VerificationSource>> seen = new AtomicReference<>();
    var delegate =
        new RecordingDocs(
            seen,
            new CitationMatchResult(
                List.of(new CitationMatchEntry(0, "A sentence.", 0, 0.91, "/docs/a.md",
                    TextSource.SUPPLIED)),
                1,
                1,
                7L,
                1,
                ScorerKind.CROSS_ENCODER,
                List.of()));

    var result =
        new LazyDocumentService(() -> delegate)
            .matchCitationsAgainst(
                "A sentence.",
                List.of(new VerificationSource(CITATION, "the literal passage")),
                0.5,
                io.justsearch.app.services.TestEngineContexts.internal())
            .toCompletableFuture()
            .join();

    assertEquals(1, seen.get().size(), "the delegate must be called at all");
    assertEquals(
        "the literal passage",
        seen.get().get(0).literalText(),
        "the text must survive the proxy — an empty default here is the inert seam");
    assertEquals(1, result.matches().size(), "the delegate's result must come back");
    assertEquals(ScorerKind.CROSS_ENCODER, result.scorer());
    assertEquals(1, result.sentencesScored());
  }

  @Test
  @DisplayName("the citations-only overload still routes through the same delegate call")
  void citationOverloadDelegatesWithBlankText() {
    AtomicReference<List<VerificationSource>> seen = new AtomicReference<>();
    var delegate =
        new RecordingDocs(seen, new CitationMatchResult(List.of(), 1, 0, 1L, 1, ScorerKind.NONE, List.of()));

    new LazyDocumentService(() -> delegate)
        .matchCitations(
            "A sentence.", List.of(CITATION), 0.5,
            io.justsearch.app.services.TestEngineContexts.internal())
        .toCompletableFuture()
        .join();

    assertEquals(1, seen.get().size());
    assertTrue(
        seen.get().get(0).literalText().isBlank(),
        "a caller with no text must produce a blank entry, i.e. 'look this one up'");
    assertEquals(CITATION, seen.get().get(0).citation());
  }

  @Test
  @DisplayName("an unresolved delegate fails the future instead of returning a zero result")
  void unresolvedDelegateFails() {
    var future =
        new LazyDocumentService(() -> null)
            .matchCitationsAgainst(
                "A sentence.", List.of(new VerificationSource(CITATION, "text")), 0.5,
                io.justsearch.app.services.TestEngineContexts.internal())
            .toCompletableFuture();

    assertTrue(future.isCompletedExceptionally(), "an absent Worker must not read as 'no matches'");
  }

  @Test
  @DisplayName("the bounded document-ID page reaches the late-bound delegate")
  void forwardsDocumentIdEnumeration() {
    DocumentService delegate =
        new DocumentService() {
          @Override
          public CompletionStage<DocumentRecord> fetch(String docId, io.justsearch.core.context.EngineContext engineContext) {
            return CompletableFuture.completedFuture(null);
          }

          @Override
          public CompletionStage<DocumentIdPage> listAllDocumentIds(int offset, int limit, io.justsearch.core.context.EngineContext engineContext) {
            return CompletableFuture.completedFuture(
                new DocumentIdPage(List.of("C:/root/nested/a.txt"), 1, 4));
          }
        };

    var page =
        new LazyDocumentService(() -> delegate)
            .listAllDocumentIds(0, 50_000, io.justsearch.app.services.TestEngineContexts.internal())
            .toCompletableFuture()
            .join();

    assertEquals(List.of("C:/root/nested/a.txt"), page.docIds());
    assertEquals(1, page.totalCount());
  }

  @Test
  @DisplayName("one operation keeps its bound Worker through retrieval, fallback, and citation")
  void operationBindingDoesNotFollowLateBoundReplacement() {
    var calls = new java.util.ArrayList<String>();
    var context = io.justsearch.app.services.TestEngineContexts.internal();
    DocumentService generationA = new GenerationDocs("A", calls, context);
    DocumentService generationB = new GenerationDocs("B", calls, context);
    AtomicReference<DocumentService> current = new AtomicReference<>(generationA);
    var documents = new LazyDocumentService(current::get);

    try (var ignored = documents.bind(context, generationA)) {
      current.set(generationB);
      CompletableFuture.runAsync(
              () -> {
                documents
                    .retrieveContext(
                        io.justsearch.app.api.RetrieveContextParams.of(
                            "question",
                            3,
                            256,
                            java.util.Set.of("doc"),
                            List.of(),
                            List.of()),
                        context)
                    .toCompletableFuture()
                    .join();
                documents.fetchBatch(List.of("doc"), context).toCompletableFuture().join();
                documents
                    .matchCitationsAgainst(
                        "answer",
                        List.of(new VerificationSource(CITATION, "text")),
                        0.5,
                        context)
                    .toCompletableFuture()
                    .join();
              })
          .join();
    }

    documents.fetch("doc", context).toCompletableFuture().join();
    assertEquals(
        List.of("A:retrieve", "A:fallback", "A:citation", "B:fetch"),
        calls,
        "publication may replace the late-bound service, but an accepted turn stays on A");
  }

  @Test
  void rebasedNestedContextRetainsTheAdmittedWorkBinding() {
    var initial = io.justsearch.app.services.TestEngineContexts.internal()
        .withWorkId(java.util.UUID.randomUUID());
    var nested = new io.justsearch.core.context.EngineContext(
        initial.clientKind(), initial.clientId(), java.util.Optional.of("nested"),
        initial.grantReference(), initial.sourceTier(), "WORKFLOW", initial.survival(),
        initial.urgency(), initial.workId());
    var calls = new java.util.ArrayList<String>();
    DocumentService a = new DocumentService() {
      @Override public CompletionStage<DocumentRecord> fetch(String id,
          io.justsearch.core.context.EngineContext context) {
        calls.add("A");
        return CompletableFuture.completedFuture(null);
      }
    };
    DocumentService b = new DocumentService() {
      @Override public CompletionStage<DocumentRecord> fetch(String id,
          io.justsearch.core.context.EngineContext context) {
        calls.add("B");
        return CompletableFuture.completedFuture(null);
      }
    };
    var current = new AtomicReference<>(a);
    var documents = new LazyDocumentService(current::get);
    try (var ignored = documents.bind(initial, a)) {
      current.set(b);
      documents.fetch("doc", nested).toCompletableFuture().join();
    }
    documents.fetch("doc", nested).toCompletableFuture().join();
    assertEquals(List.of("A", "B"), calls);
  }

  private record RecordingDocs(
      AtomicReference<List<VerificationSource>> seen, CitationMatchResult result)
      implements DocumentService {

    @Override
    public CompletionStage<DocumentRecord> fetch(String docId, io.justsearch.core.context.EngineContext engineContext) {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<CitationMatchResult> matchCitationsAgainst(
        String answerText, List<VerificationSource> sources, double threshold,
        io.justsearch.core.context.EngineContext engineContext) {
      seen.set(sources);
      return CompletableFuture.completedFuture(result);
    }
  }

  private record GenerationDocs(
      String generation,
      List<String> calls,
      io.justsearch.core.context.EngineContext expectedContext)
      implements DocumentService {
    @Override
    public CompletionStage<DocumentRecord> fetch(
        String docId, io.justsearch.core.context.EngineContext engineContext) {
      assertSame(expectedContext, engineContext);
      calls.add(generation + ":fetch");
      return CompletableFuture.completedFuture(new DocumentRecord(docId, "text", Map.of()));
    }

    @Override
    public CompletionStage<Map<String, DocumentRecord>> fetchBatch(
        List<String> docIds, io.justsearch.core.context.EngineContext engineContext) {
      assertSame(expectedContext, engineContext);
      calls.add(generation + ":fallback");
      return CompletableFuture.completedFuture(
          Map.of("doc", new DocumentRecord("doc", "text", Map.of())));
    }

    @Override
    public CompletionStage<ContextResult> retrieveContext(
        io.justsearch.app.api.RetrieveContextParams params,
        io.justsearch.core.context.EngineContext engineContext) {
      assertSame(expectedContext, engineContext);
      calls.add(generation + ":retrieve");
      return CompletableFuture.completedFuture(
          new ContextResult(
              "text", 1, 1, 1, List.of(CITATION), "test", "ok", false, List.of()));
    }

    @Override
    public CompletionStage<CitationMatchResult> matchCitationsAgainst(
        String answerText,
        List<VerificationSource> sources,
        double threshold,
        io.justsearch.core.context.EngineContext engineContext) {
      assertSame(expectedContext, engineContext);
      calls.add(generation + ":citation");
      return CompletableFuture.completedFuture(
          new CitationMatchResult(List.of(), 1, 0, 0, 1, ScorerKind.NONE, List.of()));
    }
  }
}
