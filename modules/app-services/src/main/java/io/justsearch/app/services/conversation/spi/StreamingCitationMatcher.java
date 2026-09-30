/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation.spi;

import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.agent.api.conversation.StreamConsumer;
import io.justsearch.agent.api.conversation.StreamConsumerResult;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.DocumentService.CitationMatchEntry;
import io.justsearch.app.api.DocumentService.CitationMatchResult;
import io.justsearch.app.api.DocumentService.ContextCitation;
import java.text.BreakIterator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Streaming citation matcher — emits per-sentence citation deltas during the LLM stream,
 * then runs authoritative embedding-based matching at stream end.
 *
 * <p>Replaces the post-hoc-only {@link CitationMatcher} with a two-phase approach:
 * <ol>
 *   <li><b>During stream</b> ({@link #onChunk}): accumulates streamed text, detects sentence
 *       boundaries via {@link BreakIterator}, matches each completed sentence against retrieved
 *       citations using fast lexical word-overlap, and emits {@code rag.citation_delta} SSE
 *       events per sentence. These are "draft" attributions — fast but approximate.</li>
 *   <li><b>At stream end</b> ({@link #onDone}): runs the existing Worker-side embedding
 *       similarity matching via {@link DocumentService#matchCitations} (the authoritative
 *       path) and emits the final {@code rag.citation_matches} event for backward compat.</li>
 * </ol>
 *
 * <p>Slice 493 — citation substrate. Uses the existing {@link StreamConsumer} SPI
 * (no new SPI category needed — the interface already supports mid-stream emission
 * via {@code onChunk}, confirmed by {@code ConversationEngine.streamLlm()} lines 338-348).
 */
public final class StreamingCitationMatcher implements StreamConsumer {

  private static final Logger LOG = LoggerFactory.getLogger(StreamingCitationMatcher.class);

  public static final String ID = "core.streaming-citation-matcher";

  static final Duration MATCH_TIMEOUT = Duration.ofSeconds(5);
  // Tempdoc 565 §15.A — the ONE answer↔source citation-grounding cutoff (shared with the agent path).
  static final double DEFAULT_THRESHOLD = DocumentService.DEFAULT_CITATION_SIMILARITY_THRESHOLD;
  static final int MIN_WORD_LENGTH = 4;
  static final int MIN_WORD_HITS = 2;

  private final DocumentService documents;
  private final Duration timeout;
  private final double threshold;

  public StreamingCitationMatcher(DocumentService documents) {
    this(documents, MATCH_TIMEOUT, DEFAULT_THRESHOLD);
  }

  /**
   * Configured-threshold constructor (tempdoc 799 N.2). Keeps {@link #MATCH_TIMEOUT} internal so
   * the composition root does not need visibility of it.
   */
  public StreamingCitationMatcher(DocumentService documents, double threshold) {
    this(documents, MATCH_TIMEOUT, threshold);
  }

  public StreamingCitationMatcher(
      DocumentService documents, Duration timeout, double threshold) {
    this.documents = Objects.requireNonNull(documents, "documents");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
    // Tempdoc 799 Q: the ONE normaliser, shared with the agent path. A local clamp here is what
    // let a configured 0 mean 0.01 on this path and 0.5 on the other.
    this.threshold = DocumentService.effectiveCitationThreshold(threshold);
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public StreamConsumerResult onChunk(String chunkText, ConversationContext ctx) {
    if (chunkText == null || chunkText.isEmpty()) {
      return StreamConsumerResult.empty();
    }
    @SuppressWarnings("unchecked")
    List<ContextCitation> citations =
        (List<ContextCitation>) ctx.attributes().get(RAGContext.ATTR_CITATIONS);
    if (citations == null || citations.isEmpty()) {
      return StreamConsumerResult.empty();
    }

    StringBuilder buffer = getOrCreateBuffer(ctx);
    buffer.append(chunkText);

    int prevSentenceCount = getSentenceCount(ctx);
    List<String> newSentences = extractCompleteSentences(buffer);
    if (newSentences.isEmpty()) {
      return StreamConsumerResult.empty();
    }

    List<SseEvent> events = new ArrayList<>();
    for (int i = 0; i < newSentences.size(); i++) {
      String sentence = newSentences.get(i);
      int sentenceIndex = prevSentenceCount + i;
      List<Map<String, Object>> matched = matchSentenceLexical(sentence, citations);
      if (!matched.isEmpty()) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sentenceIndex", sentenceIndex);
        payload.put("sentenceText", sentence);
        payload.put("citations", matched);
        events.add(new SseEvent("rag.citation_delta", Map.copyOf(payload)));
      }
    }
    setSentenceCount(ctx, prevSentenceCount + newSentences.size());

    return events.isEmpty()
        ? StreamConsumerResult.empty()
        : StreamConsumerResult.eventsOnly(events);
  }

  @Override
  public StreamConsumerResult onDone(String fullText, ConversationContext ctx) {
    var engineContext = ctx.engineContext();
    if (fullText == null || fullText.isBlank()) {
      return StreamConsumerResult.empty();
    }
    List<DocumentService.VerificationSource> sources = verificationSources(ctx);
    if (sources.isEmpty()) {
      return StreamConsumerResult.empty();
    }
    try {
      CitationMatchResult result =
          documents
              .matchCitationsAgainst(fullText, sources, threshold, engineContext)
              .toCompletableFuture()
              .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (result == null) {
        return StreamConsumerResult.empty();
      }
      // Tempdoc 836 S2S3-A.2 — a ZERO-MATCH result is still an answer to "what happened?", and it
      // is the case where the difference matters most: "nothing was examined" and "everything was
      // examined and supports nothing" are the same empty match list, told apart only by the
      // coverage facts this payload carries. Suppressing the event here (the pre-S2/S3 behaviour)
      // is what left the consumer with no choice but to render an evidence verdict over a pass
      // that may never have run.
      Map<String, Object> payload = toCitationMatchPayload(result);
      // Tempdoc 561 P-A (evidence non-divergence): emit the live SSE event AND contribute the matches
      // to the done-payload, so ConversationEngine persists the per-claim grounding ON the record. A
      // reloaded conversation then renders the same per-claim marks FROM the record (renderUnifiedItem),
      // not only the live path — the evidence record is total, the two render paths cannot diverge.
      return new StreamConsumerResult(
          List.of(new SseEvent("rag.citation_matches", payload)),
          List.of(),
          List.of(),
          Map.of("claimMatches", payload));
    } catch (Exception e) {
      LOG.debug("Authoritative citation matching failed (non-fatal): {}", e.getMessage());
      return StreamConsumerResult.empty();
    }
  }

  /**
   * The sources to verify against (tempdoc 836 §1.4).
   *
   * <p>An injector that holds the literal text it showed the model publishes {@link
   * RAGContext#ATTR_VERIFICATION_SOURCES}; retrieval publishes only {@link
   * RAGContext#ATTR_CITATIONS}, whose chunk ordinals are true, so those sources carry no text and
   * the Worker looks them up exactly as before.
   */
  @SuppressWarnings("unchecked")
  private static List<DocumentService.VerificationSource> verificationSources(
      ConversationContext ctx) {
    Object supplied = ctx.attributes().get(RAGContext.ATTR_VERIFICATION_SOURCES);
    if (supplied instanceof List<?> list && !list.isEmpty()) {
      return (List<DocumentService.VerificationSource>) list;
    }
    List<ContextCitation> citations =
        (List<ContextCitation>) ctx.attributes().get(RAGContext.ATTR_CITATIONS);
    if (citations == null || citations.isEmpty()) {
      return List.of();
    }
    return citations.stream()
        .map(c -> new DocumentService.VerificationSource(c, ""))
        .toList();
  }

  // -- Sentence segmentation --

  private static final String BUFFER_KEY = "streaming.citation.buffer";
  private static final String SENTENCE_COUNT_KEY = "streaming.citation.sentenceCount";

  private static StringBuilder getOrCreateBuffer(ConversationContext ctx) {
    Object existing = ctx.attributes().get(BUFFER_KEY);
    if (existing instanceof StringBuilder sb) {
      return sb;
    }
    StringBuilder sb = new StringBuilder();
    ctx.attributes().put(BUFFER_KEY, sb);
    return sb;
  }

  private static int getSentenceCount(ConversationContext ctx) {
    Object val = ctx.attributes().get(SENTENCE_COUNT_KEY);
    return val instanceof Integer i ? i : 0;
  }

  private static void setSentenceCount(ConversationContext ctx, int count) {
    ctx.attributes().put(SENTENCE_COUNT_KEY, count);
  }

  /**
   * Extracts complete sentences from the buffer, leaving any incomplete trailing
   * text in the buffer for the next chunk.
   */
  static List<String> extractCompleteSentences(StringBuilder buffer) {
    String text = buffer.toString();
    if (text.isBlank()) {
      return List.of();
    }

    BreakIterator bi = BreakIterator.getSentenceInstance(Locale.ENGLISH);
    bi.setText(text);

    List<String> sentences = new ArrayList<>();
    int start = bi.first();
    int end = bi.next();
    int lastCompleteEnd = 0;

    while (end != BreakIterator.DONE) {
      int nextEnd = bi.next();
      if (nextEnd == BreakIterator.DONE) {
        // Last segment — might be incomplete (no following sentence boundary).
        // Only include if it ends with sentence-terminal punctuation.
        String candidate = text.substring(start, end).trim();
        if (!candidate.isEmpty() && endsWithTerminal(candidate)) {
          sentences.add(candidate);
          lastCompleteEnd = end;
        }
        break;
      }
      String sentence = text.substring(start, end).trim();
      if (!sentence.isEmpty()) {
        sentences.add(sentence);
      }
      lastCompleteEnd = end;
      start = end;
      end = nextEnd;
    }

    if (sentences.isEmpty() && lastCompleteEnd == 0) {
      return List.of();
    }

    // Remove consumed text from buffer, keep the incomplete tail.
    buffer.delete(0, lastCompleteEnd);
    return sentences;
  }

  private static boolean endsWithTerminal(String s) {
    char last = s.charAt(s.length() - 1);
    return last == '.' || last == '!' || last == '?' || last == '\n';
  }

  // -- Lexical matching --

  static List<Map<String, Object>> matchSentenceLexical(
      String sentence, List<ContextCitation> citations) {
    String lower = sentence.toLowerCase(Locale.ROOT);
    List<Map<String, Object>> matched = new ArrayList<>();

    for (int i = 0; i < citations.size(); i++) {
      ContextCitation c = citations.get(i);
      String excerpt = c.excerpt();
      if (excerpt == null || excerpt.isBlank()) {
        continue;
      }
      String[] words = excerpt.toLowerCase(Locale.ROOT).split("\\s+");
      int hits = 0;
      int significantWords = 0;
      for (String w : words) {
        if (w.length() >= MIN_WORD_LENGTH) {
          significantWords++;
          if (lower.contains(w)) {
            hits++;
          }
        }
      }
      if (significantWords == 0) {
        continue;
      }
      double overlap = (double) hits / significantWords;
      boolean isMatch =
          hits >= MIN_WORD_HITS || (significantWords <= 3 && hits >= 1) || overlap >= 0.5;
      if (isMatch) {
        Map<String, Object> entry = new LinkedHashMap<>();
        // Tempdoc 822 §3b (the numbering contract) — a MATCH carries the source's POSITION in this
        // turn's `rag.citations` array (the loop index), never `c.chunkIndex()`, which is the chunk's
        // ordinal inside its parent document. Emitting the ordinal is what let a 5-source answer show
        // a "[59]" mark that deep-linked to source 1 through the resolver's old fallback.
        entry.put("sourceIndex", i);
        entry.put("parentDocId", c.parentDocId());
        entry.put("score", Math.round(overlap * 100.0) / 100.0);
        matched.add(Map.copyOf(entry));
      }
    }
    return matched;
  }

  // -- Payload formatting (matches legacy CitationMatcher) --

  /**
   * The Head-to-browser hop for the honesty fields (tempdoc 836 S2S3-A.0 gap 3).
   *
   * <p>Everything the response knows about HOW it was produced travels here, because this map is
   * both the live {@code rag.citation_matches} payload and the persisted {@code claimMatches} on
   * the record — so a reloaded conversation is judged by the same facts as the live one. Before
   * this, {@code scorer} and {@code sentencesScored} reached the Head and stopped: nothing a
   * browser could read said which producer wrote a similarity, which made the §4 provenance gate
   * unimplementable rather than merely deferred. No test asserted this hop, which is exactly how
   * that slipped — {@code StreamingCitationMatcherPayloadTest} now pins it.
   */
  static Map<String, Object> toCitationMatchPayload(CitationMatchResult result) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("sentencesTotal", result.sentencesTotal());
    out.put("sentencesMatched", result.sentencesMatched());
    out.put("tookMs", result.tookMs());
    out.put("scorer", result.scorer().name());
    out.put("sentencesScored", result.sentencesScored());
    List<Map<String, Object>> coverage = new ArrayList<>(result.sourceCoverage().size());
    for (DocumentService.SourceCoverage c : result.sourceCoverage()) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("sourceIndex", c.sourceIndex());
      entry.put("windowsConsidered", c.windowsConsidered());
      entry.put("windowsScored", c.windowsScored());
      coverage.add(Map.copyOf(entry));
    }
    out.put("sourceCoverage", coverage);
    List<Map<String, Object>> matches = new ArrayList<>(result.matches().size());
    for (CitationMatchEntry m : result.matches()) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("sentenceIndex", m.sentenceIndex());
      entry.put("sentenceText", m.sentenceText());
      entry.put("sourceIndex", m.sourceIndex());
      entry.put("similarity", m.similarity());
      entry.put("parentDocId", m.parentDocId());
      entry.put("textSource", m.textSource().name());
      matches.add(Map.copyOf(entry));
    }
    out.put("matches", matches);
    return Map.copyOf(out);
  }
}
