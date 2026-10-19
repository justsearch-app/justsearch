/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.InjectorResult;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.DocumentService.CitationMatchEntry;
import io.justsearch.app.api.DocumentService.CitationMatchResult;
import io.justsearch.app.api.DocumentService.ContextCitation;
import io.justsearch.app.api.DocumentService.ContextInclusion;
import io.justsearch.app.api.DocumentService.ContextResult;
import io.justsearch.app.api.DocumentService.ContextSection;
import io.justsearch.app.api.DocumentService.DocumentRecord;
import io.justsearch.app.api.DocumentService.ScorerKind;
import io.justsearch.app.api.DocumentService.SourceCoverage;
import io.justsearch.app.api.DocumentService.TextSource;
import io.justsearch.app.api.DocumentService.VerificationSource;
import io.justsearch.indexing.rag.ContextBudgeter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * WP4.4 — retrieval publishes the passage text it put in front of the model as the turn's
 * verification sources, so an answer drawn from a short (chunkless) document is verified like one
 * drawn from a long document.
 *
 * <p>The seam under test is {@link RAGContext} → {@link StreamingCitationMatcher} → {@link
 * DocumentService#matchCitationsAgainst}. The document service here models the Worker's text
 * resolution exactly as {@code PassageWindows.prepare} does it — supplied text wins, a blank entry
 * is looked up by {@code (parentDocId, chunkIndex)} in the stored chunks, a negative ordinal is
 * never looked up — and scores a sentence as supported when every significant term of it appears
 * in the source text. The model of the index is what makes the before-state observable: a
 * chunkless document has no stored chunk, so a lookup finds nothing.
 */
final class RAGContextVerificationSourcesTest {

  private static final String SHORT_DOC = "d:/notes/short.md";
  private static final String LONG_DOC = "d:/notes/long.md";
  private static final String OTHER_LONG_DOC = "d:/notes/other-long.md";

  /** A fact placed at the very END of a short document, past the 240-char display excerpt. */
  private static final String TAIL_FACT = "The zircon lantern burns violet after midnight.";

  private static final String TAIL_SENTENCE = "The zircon lantern burns violet after midnight.";

  /** Supported by nothing any source contains. */
  private static final String UNSUPPORTED_SENTENCE = "Penguins operate the quarterly payroll.";

  // ==================== S7.1 / S7.3 / S7.6 ====================

  @ParameterizedTest(name = "{0} chars")
  @ValueSource(ints = {1999, 2000, 2001, 3400})
  @DisplayName("S7.1/S7.3/S7.6: a short document's passage is scored; its tail fact verifies, an"
      + " unsupported sentence does not")
  void shortDocumentPassageIsVerified(int length) {
    // Each length is modelled as one passage that retrieval synthesized from the whole document
    // (chunk 0 of 1). Which real lengths the indexer stores without chunks is pinned by the
    // Worker's ShortDocumentPassageVerificationTest, not here.
    String content = shortDocument(length);
    var worker = new WorkerModel();
    ContextResult retrieval =
        retrieval(List.of(new Passage(SHORT_DOC, 0, 1, content)));

    var ctx = turn();
    inject(worker, retrieval, ctx, 32768);
    Map<String, Object> payload = verify(worker, ctx, TAIL_SENTENCE + " " + UNSUPPORTED_SENTENCE);

    List<Map<String, Object>> matches = matchesOf(payload);
    assertEquals(1, matches.size(), "exactly the supported sentence verifies: " + payload);
    assertEquals(0, matches.get(0).get("sentenceIndex"));
    assertEquals(0, matches.get(0).get("sourceIndex"));
    assertEquals(SHORT_DOC, matches.get(0).get("parentDocId"));
    assertEquals("SUPPLIED", matches.get(0).get("textSource"));
    assertEquals(2, payload.get("sentencesScored"), "both sentences were examined");
    assertEquals(1, windowsConsidered(payload, 0), "the short document's text was examined");

    VerificationSource source = worker.lastSources.get(0);
    assertEquals(content, source.literalText(), "the evidence is the passage the model saw");
    assertFalse(
        source.literalText().equals(retrieval.citations().get(0).excerpt()),
        "never the display excerpt");
  }

  // ==================== S7.5 ====================

  @Test
  @DisplayName("S7.5: short and long passages in a reordered ranking each verify against their own"
      + " position")
  void mixedShortAndLongPassagesKeepTheirPositions() {
    String longChunk7 = "Glacier sensors report basalt temperature every hour.";
    String longChunk2 = "Harbor cranes unload copper ingots before sunrise.";
    String otherChunk0 = "Orchard bees prefer clover over lavender.";
    String shortContent = shortDocument(900);
    var worker = new WorkerModel();
    worker.storeChunk(LONG_DOC, 7, longChunk7);
    worker.storeChunk(LONG_DOC, 2, longChunk2);
    worker.storeChunk(OTHER_LONG_DOC, 0, otherChunk0);
    // Ranked order, not document order: chunk 7 before chunk 2, the short doc in between.
    ContextResult retrieval =
        retrieval(
            List.of(
                new Passage(LONG_DOC, 7, 12, longChunk7),
                new Passage(SHORT_DOC, 0, 1, shortContent),
                new Passage(LONG_DOC, 2, 12, longChunk2),
                new Passage(OTHER_LONG_DOC, 0, 4, otherChunk0)));

    var ctx = turn();
    inject(worker, retrieval, ctx, 32768);
    Map<String, Object> payload =
        verify(
            worker,
            ctx,
            "Harbor cranes unload copper ingots. "
                + TAIL_SENTENCE
                + " Glacier sensors report basalt temperature. "
                + "Orchard bees prefer clover.");

    Map<Integer, Integer> sourceBySentence = new HashMap<>();
    for (Map<String, Object> m : matchesOf(payload)) {
      sourceBySentence.put((Integer) m.get("sentenceIndex"), (Integer) m.get("sourceIndex"));
      int position = (Integer) m.get("sourceIndex");
      assertEquals(
          retrieval.citations().get(position).parentDocId(),
          m.get("parentDocId"),
          "a mark opens the source at its own citation position");
    }
    assertEquals(Map.of(0, 2, 1, 1, 2, 0, 3, 3), sourceBySentence, payload.toString());
    for (int i = 0; i < retrieval.sections().size(); i++) {
      assertEquals(retrieval.sections().get(i).content(), worker.lastSources.get(i).literalText());
      assertEquals(
          retrieval.citations().get(i).chunkIndex(),
          worker.lastSources.get(i).citation().chunkIndex(),
          "source " + i + " keeps its citation's identity");
    }
  }

  // ==================== N7.1 ====================

  @Test
  @DisplayName("N7.1: a long-document answer gets the same matches as the chunk lookup gave")
  void longDocumentMatchesAreUnchanged() {
    String chunk3 = "Glacier sensors report basalt temperature every hour.";
    String chunk9 = "Harbor cranes unload copper ingots before sunrise.";
    var worker = new WorkerModel();
    worker.storeChunk(LONG_DOC, 3, chunk3);
    worker.storeChunk(LONG_DOC, 9, chunk9);
    ContextResult retrieval =
        retrieval(
            List.of(new Passage(LONG_DOC, 9, 12, chunk9), new Passage(LONG_DOC, 3, 12, chunk3)));
    String answer =
        "Glacier sensors report basalt temperature. Harbor cranes unload copper ingots. "
            + UNSUPPORTED_SENTENCE;

    var ctx = turn();
    inject(worker, retrieval, ctx, 32768);
    Map<String, Object> published = verify(worker, ctx, answer);

    List<VerificationSource> lookupOnly =
        retrieval.citations().stream().map(c -> new VerificationSource(c, "")).toList();
    CitationMatchResult lookedUp = worker.match(answer, lookupOnly);

    assertEquals(lookedUp.sentencesScored(), published.get("sentencesScored"));
    List<Map<String, Object>> matches = matchesOf(published);
    assertEquals(lookedUp.matches().size(), matches.size());
    for (int i = 0; i < matches.size(); i++) {
      CitationMatchEntry expected = lookedUp.matches().get(i);
      assertEquals(expected.sentenceIndex(), matches.get(i).get("sentenceIndex"));
      assertEquals(expected.sourceIndex(), matches.get(i).get("sourceIndex"));
      assertEquals(expected.similarity(), (Double) matches.get(i).get("similarity"), 1e-9);
      assertEquals(expected.parentDocId(), matches.get(i).get("parentDocId"));
    }
  }

  // ==================== S7.7 ====================

  @Test
  @DisplayName("S7.7: a dropped passage claims no text, and a cut passage only the part the model"
      + " saw")
  void budgetCutLimitsEvidenceToThePrompt() {
    // Four passages that cannot all fit a 460-token budget: 0 included, 1 cut, 2 and 3 dropped.
    // Each passage's stored chunk holds its FULL text, so a lookup would claim all of it.
    List<Passage> passages = new ArrayList<>();
    String[] heads = {
      "Glacier sensors report basalt temperature.",
      "Harbor cranes unload copper ingots.",
      "Orchard bees prefer clover blossoms.",
      "Falcon trainers whistle twice."
    };
    String cutTail = "Midnight ferries carry walnut barrels.";
    var worker = new WorkerModel();
    for (int i = 0; i < heads.length; i++) {
      String body = heads[i] + " " + FILLER + (i == 1 ? " " + cutTail : "");
      worker.storeChunk(LONG_DOC, i + 4, body);
      passages.add(new Passage(LONG_DOC, i + 4, 12, body));
    }
    ContextResult retrieval = retrieval(passages);

    var ctx = turn();
    InjectorResult injected = inject(worker, retrieval, ctx, 4096, 3072);
    String prompt = promptOf(injected);
    // Preconditions: the shape the assertions rely on.
    assertTrue(prompt.contains(heads[1]), "passage 1 is partly in the prompt");
    assertFalse(prompt.contains(cutTail), "passage 1's tail was cut");
    assertFalse(prompt.contains(heads[2]), "passage 2 was dropped");

    Map<String, Object> payload =
        verify(
            worker,
            ctx,
            "Glacier sensors report basalt temperature. Harbor cranes unload copper ingots. "
                + "Midnight ferries carry walnut barrels. Orchard bees prefer clover blossoms.");

    Map<Integer, Integer> sourceBySentence = new HashMap<>();
    for (Map<String, Object> m : matchesOf(payload)) {
      sourceBySentence.put((Integer) m.get("sentenceIndex"), (Integer) m.get("sourceIndex"));
    }
    assertEquals(
        Map.of(0, 0, 1, 1),
        sourceBySentence,
        "only text the model saw can verify a sentence: " + payload);
    assertEquals(0, windowsConsidered(payload, 2), "a dropped passage contributes nothing");
    assertEquals(0, windowsConsidered(payload, 3), "a dropped passage contributes nothing");
    assertEquals(
        ContextCitation.CHUNK_INDEX_ABSENT,
        worker.lastSources.get(2).citation().chunkIndex(),
        "a dropped passage must not be looked up either");
    for (VerificationSource s : worker.lastSources) {
      assertTrue(prompt.contains(s.literalText()), "supplied text is prompt text: " + s);
    }
    assertEquals(4, worker.lastSources.size(), "every citation keeps its position");
  }

  @Test
  @DisplayName("S7.7: after a structure-blind cut only passages wholly in the prompt are evidence")
  void structureBlindCutSuppliesOnlyWhatSurvived() {
    // The non-additive estimator case (RAGContextTest.nonAdditiveEstimatorOvershootIsCaught):
    // the parts fit, the assembly does not, and the cut falls back to a head+tail trim.
    String sparseAscii = ("a".repeat(49) + " ").repeat(2);
    String denseCjk = "文".repeat(150);
    List<Passage> passages =
        List.of(
            new Passage("doc-0", 0, 4, sparseAscii),
            new Passage("doc-1", 1, 4, denseCjk),
            new Passage("doc-2", 2, 4, sparseAscii),
            new Passage("doc-3", 3, 4, denseCjk));
    var worker = new WorkerModel();
    for (Passage p : passages) {
      worker.storeChunk(p.docId(), p.chunkIndex(), p.text());
    }

    var ctx = turn();
    String prompt = promptOf(inject(worker, retrieval(passages), ctx, 4096, 3072));
    verify(worker, ctx, "Anything at all.");

    assertEquals(4, worker.lastSources.size());
    for (VerificationSource s : worker.lastSources) {
      if (s.suppliesText()) {
        assertTrue(prompt.contains(s.literalText()), "supplied text is prompt text: " + s);
      } else {
        assertEquals(
            ContextCitation.CHUNK_INDEX_ABSENT,
            s.citation().chunkIndex(),
            "a passage not wholly in the prompt is neither supplied nor looked up: " + s);
      }
    }
  }

  // ==================== S7.8 ====================

  @Test
  @DisplayName("S7.8: a document edited or removed after retrieval cannot change the evidence")
  void editedOrRemovedDocumentCannotSubstituteText() {
    String original = "Harbor cranes unload copper ingots before sunrise.";
    var worker = new WorkerModel();
    worker.storeChunk(LONG_DOC, 5, original);
    worker.storeChunk(OTHER_LONG_DOC, 1, "Orchard bees prefer clover over lavender.");
    ContextResult retrieval =
        retrieval(
            List.of(
                new Passage(LONG_DOC, 5, 9, original),
                new Passage(OTHER_LONG_DOC, 1, 3, "Orchard bees prefer clover over lavender.")));

    var ctx = turn();
    inject(worker, retrieval, ctx, 32768);
    // Between retrieval and matching: LONG_DOC is edited, OTHER_LONG_DOC is removed.
    worker.storeChunk(LONG_DOC, 5, "Falcon trainers whistle twice at dawn.");
    worker.removeDocument(OTHER_LONG_DOC);

    Map<String, Object> payload =
        verify(
            worker,
            ctx,
            "Harbor cranes unload copper ingots. Falcon trainers whistle twice. "
                + "Orchard bees prefer clover.");

    Map<Integer, Integer> sourceBySentence = new HashMap<>();
    for (Map<String, Object> m : matchesOf(payload)) {
      sourceBySentence.put((Integer) m.get("sentenceIndex"), (Integer) m.get("sourceIndex"));
    }
    assertEquals(Map.of(0, 0, 2, 1), sourceBySentence, payload.toString());
  }

  // ==================== preservation ====================

  @Test
  @DisplayName("a citations/sections length mismatch publishes no verification sources")
  void lengthMismatchPublishesNothing() {
    List<ContextCitation> citations =
        List.of(citation(LONG_DOC, 0, 2, "one"), citation(LONG_DOC, 1, 2, "two"));
    List<ContextSection> sections =
        List.of(
            new ContextSection(LONG_DOC, "one", false, 0, 0),
            new ContextSection(LONG_DOC, "two", false, 1, 1),
            new ContextSection(LONG_DOC, "three", false, 2, 2));
    var ctx = turn();
    inject(
        new WorkerModel(),
        new ContextResult("one", 2, 2, 1, citations, "BM25", "ok", false, sections),
        ctx,
        32768);

    assertNull(
        ctx.attributes().get(RAGContext.ATTR_VERIFICATION_SOURCES),
        "a suspect alignment must not pair text with the wrong citation");
  }

  @Test
  @DisplayName("a whole-document fallback (no citations) publishes no verification sources")
  void wholeDocumentFallbackPublishesNothing() {
    var worker = new WorkerModel();
    worker.batch.put(
        SHORT_DOC, new DocumentRecord(SHORT_DOC, "Some text.", Map.of()));
    var ctx = turn(List.of(SHORT_DOC));
    inject(
        worker,
        new ContextResult("", 0, 0, 0, List.of(), "BM25", "NO_CHUNKS_FOUND", false, List.of()),
        ctx,
        32768);

    assertEquals(List.of(), ctx.attributes().get(RAGContext.ATTR_CITATIONS));
    assertNull(ctx.attributes().get(RAGContext.ATTR_VERIFICATION_SOURCES));
  }

  // ==================== fixtures ====================

  private static final String FILLER = "lorem ipsum dolor sit amet consectetur ".repeat(30).trim();

  /** A chunkless document of exactly {@code length} chars whose LAST sentence is the tail fact. */
  private static String shortDocument(int length) {
    String seed = "Quarterly notes about the garden shed and its paint. ";
    StringBuilder sb = new StringBuilder();
    int bodyLength = length - TAIL_FACT.length();
    while (sb.length() < bodyLength) {
      sb.append(seed);
    }
    sb.setLength(bodyLength - 1);
    sb.append(' ').append(TAIL_FACT);
    assertEquals(length, sb.length());
    return sb.toString();
  }

  private record Passage(String docId, int chunkIndex, int chunkTotal, String text) {}

  private static ContextCitation citation(String docId, int chunkIndex, int total, String text) {
    // The Worker's 240-char display excerpt (RagContextOps.clampExcerptToWordBoundary).
    String excerpt = text.length() <= 240 ? text : text.substring(0, 240);
    return new ContextCitation(
        docId, chunkIndex, total, 0, text.length(), 0.9f, excerpt, 0, 0, "", 0,
        ContextInclusion.ABSENT);
  }

  /** Mirrors the Worker's ContextBudgeter output: index-aligned citations and sections. */
  private static ContextResult retrieval(List<Passage> passages) {
    List<ContextCitation> citations = new ArrayList<>();
    List<ContextSection> sections = new ArrayList<>();
    StringBuilder assembled = new StringBuilder();
    for (int i = 0; i < passages.size(); i++) {
      Passage p = passages.get(i);
      citations.add(citation(p.docId(), p.chunkIndex(), p.chunkTotal(), p.text()));
      sections.add(new ContextSection(p.docId(), p.text(), false, i, i));
      if (i > 0) assembled.append(ContextBudgeter.SECTION_SEPARATOR);
      assembled.append(ContextBudgeter.sectionHeader(i + 1, p.docId())).append(p.text());
    }
    return new ContextResult(
        assembled.toString(), passages.size(), passages.size(), passages.size(), citations,
        "HYBRID", "ok", false, sections);
  }

  private static InjectorResult inject(
      WorkerModel worker, ContextResult retrieval, ConversationContext ctx, int window) {
    return inject(worker, retrieval, ctx, window, 1024);
  }

  private static InjectorResult inject(
      WorkerModel worker,
      ContextResult retrieval,
      ConversationContext ctx,
      int window,
      int reserve) {
    worker.retrieval = retrieval;
    ctx.attributes().put(RAGContext.ATTR_COMPLETION_RESERVE_TOKENS, reserve);
    InjectorResult result =
        new RAGContext(worker, RAGContext.DEFAULT_TOP_K, () -> stubAi(window)).inject(ctx);
    assertTrue(result.terminalError().isEmpty(), "retrieval must not fail: " + result);
    return result;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> verify(
      WorkerModel worker, ConversationContext ctx, String answer) {
    var result = new StreamingCitationMatcher(worker).onDone(answer, ctx);
    assertFalse(result.events().isEmpty(), "the matcher must report a verification pass");
    return (Map<String, Object>) result.events().get(0).payload();
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> matchesOf(Map<String, Object> payload) {
    return (List<Map<String, Object>>) payload.get("matches");
  }

  @SuppressWarnings("unchecked")
  private static int windowsConsidered(Map<String, Object> payload, int sourceIndex) {
    for (Map<String, Object> c : (List<Map<String, Object>>) payload.get("sourceCoverage")) {
      if (Integer.valueOf(sourceIndex).equals(c.get("sourceIndex"))) {
        return (Integer) c.get("windowsConsidered");
      }
    }
    throw new AssertionError("no coverage for source " + sourceIndex + ": " + payload);
  }

  private static String promptOf(InjectorResult result) {
    String injected = (String) result.messages().get(0).get("content");
    int end = injected.indexOf("\n\nQuestion: ");
    return injected.substring("Documents:\n".length(), end);
  }

  /**
   * The Worker as seen across the matcher boundary: retrieval returns a fixed result, and
   * verification resolves each source's text the way {@code PassageWindows.prepare} does.
   */
  private static final class WorkerModel implements DocumentService {
    ContextResult retrieval;
    final Map<String, DocumentRecord> batch = new HashMap<>();
    private final Map<String, String> storedChunks = new HashMap<>();
    List<VerificationSource> lastSources = List.of();

    void storeChunk(String docId, int chunkIndex, String text) {
      storedChunks.put(docId + "#" + chunkIndex, text);
    }

    void removeDocument(String docId) {
      storedChunks.keySet().removeIf(k -> k.startsWith(docId + "#"));
    }

    @Override
    public CompletionStage<DocumentRecord> fetch(
        String docId, io.justsearch.core.context.EngineContext engineContext) {
      return CompletableFuture.completedFuture(batch.get(docId));
    }

    @Override
    public CompletionStage<Map<String, DocumentRecord>> fetchBatch(
        List<String> docIds, io.justsearch.core.context.EngineContext engineContext) {
      Map<String, DocumentRecord> out = new LinkedHashMap<>();
      for (String id : docIds) {
        if (batch.containsKey(id)) out.put(id, batch.get(id));
      }
      return CompletableFuture.completedFuture(out);
    }

    @Override
    public CompletionStage<ContextResult> retrieveContextWithMeta(
        String question,
        Set<String> docIds,
        int topK,
        int maxContextTokens,
        io.justsearch.core.context.EngineContext engineContext) {
      return CompletableFuture.completedFuture(retrieval);
    }

    @Override
    public CompletionStage<CitationMatchResult> matchCitationsAgainst(
        String answerText,
        List<VerificationSource> sources,
        double threshold,
        io.justsearch.core.context.EngineContext engineContext) {
      lastSources = List.copyOf(sources);
      return CompletableFuture.completedFuture(match(answerText, sources));
    }

    CitationMatchResult match(String answerText, List<VerificationSource> sources) {
      List<String> texts = new ArrayList<>();
      List<SourceCoverage> coverage = new ArrayList<>();
      for (int i = 0; i < sources.size(); i++) {
        VerificationSource s = sources.get(i);
        ContextCitation c = s.citation();
        String text =
            s.suppliesText()
                ? s.literalText()
                : c.chunkIndex() < 0
                    ? ""
                    : storedChunks.getOrDefault(c.parentDocId() + "#" + c.chunkIndex(), "");
        texts.add(text);
        int windows = text.isBlank() ? 0 : 1;
        coverage.add(new SourceCoverage(i, windows, windows));
      }
      boolean anyText = texts.stream().anyMatch(t -> !t.isBlank());
      String[] sentences = answerText.trim().split("(?<=[.!?])\\s+");
      List<CitationMatchEntry> matches = new ArrayList<>();
      for (int si = 0; si < sentences.length && anyText; si++) {
        Set<String> needed = terms(sentences[si]);
        for (int i = 0; i < texts.size(); i++) {
          if (!needed.isEmpty() && terms(texts.get(i)).containsAll(needed)) {
            matches.add(
                new CitationMatchEntry(
                    si,
                    sentences[si],
                    i,
                    1.0,
                    sources.get(i).citation().parentDocId(),
                    sources.get(i).suppliesText() ? TextSource.SUPPLIED : TextSource.CHUNK_LOOKUP));
            break;
          }
        }
      }
      return new CitationMatchResult(
          matches,
          sentences.length,
          matches.size(),
          1L,
          anyText ? sentences.length : 0,
          anyText ? ScorerKind.CROSS_ENCODER : ScorerKind.NONE,
          coverage);
    }

    private static Set<String> terms(String text) {
      Set<String> out = new HashSet<>();
      for (String raw : text.toLowerCase(Locale.ROOT).split("[^\\p{IsAlphabetic}\\p{IsDigit}]+")) {
        if (raw.length() >= 4) out.add(raw);
      }
      return out;
    }
  }

  private static ConversationContext turn() {
    return turn(List.of("scoped"));
  }

  private static ConversationContext turn(List<String> docIds) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("question", "what?");
    body.put("docIds", docIds);
    var engineContext = io.justsearch.app.services.TestEngineContexts.internal();
    Map<String, Object> attributes = new HashMap<>();
    return new ConversationContext() {
      @Override
      public io.justsearch.core.context.EngineContext engineContext() {
        return engineContext;
      }

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
        return body;
      }

      @Override
      public Map<String, Object> attributes() {
        return attributes;
      }
    };
  }

  private static io.justsearch.app.api.OnlineAiService stubAi(int window) {
    return new io.justsearch.app.api.OnlineAiService() {
      @Override
      public CompletableFuture<String> summarize(String content) {
        return CompletableFuture.completedFuture("");
      }

      @Override
      public CompletableFuture<String> askQuestion(String question, String context) {
        return CompletableFuture.completedFuture("");
      }

      @Override
      public boolean isAvailable() {
        return true;
      }

      @Override
      public boolean isStartingUp() {
        return false;
      }

      @Override
      public Integer llmContextTokens() {
        return window;
      }

      @Override
      public Integer configuredContextTokens() {
        return window;
      }
    };
  }
}
