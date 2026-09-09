/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import io.justsearch.core.context.EngineContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Stable surface for retrieving persisted documents by id.
 *
 * <p>The desktop UI relies on this service to hydrate summaries with the full record instead of the
 * compact snippet returned by search responses.
 */
public interface DocumentService {

  /** Separator between context sections (mirrors indexing module constant). */
  String SECTION_SEPARATOR = "\n\n---\n\n";

  /**
   * Tempdoc 565 §15.A — the ONE answer↔source citation-grounding cutoff. Below this cosine
   * similarity an answer sentence is not counted as grounded by a source chunk. Both the RAG matcher
   * ({@code StreamingCitationMatcher}) and the agent matcher ({@code AgentCitationResolver}) read THIS
   * value: the §15.G de-risk proved both call the same {@link #matchCitations} scorer on the same
   * [0,1] scale, so the former 0.45 (agent) / 0.5 (RAG) split was drift, not calibration. The numeric
   * value stays an evidence-backed calibration — but it lives in exactly one place now.
   */
  double DEFAULT_CITATION_SIMILARITY_THRESHOLD = 0.5;

  /**
   * Normalises a configured citation cutoff to its effective value — the ONE place that decides
   * what an out-of-range setting means.
   *
   * <p>Tempdoc 799 §Q: when {@code justsearch.citation.match_threshold} was wired, each matcher
   * clamped it locally and the two clamps disagreed — the RAG path floored at {@code 0.01} while
   * the agent path fell back to the default. A configured {@code 0} therefore produced an
   * effective {@code 0.01} on one path and {@code 0.5} on the other: a WIDER divergence than the
   * 0.45/0.5 drift §15.A above was written to remove, reintroduced by the change that claimed to
   * make divergence impossible. Sharing the constant was not enough — the interpretation of
   * out-of-range values has to be shared too.
   *
   * <p>Out-of-range resolves to the default rather than to a silent floor: a nonsensical cutoff
   * should behave like "unset", not like "cite almost everything".
   *
   * @param configured the raw configured value
   * @return {@code configured} when in {@code (0,1]}, otherwise
   *     {@link #DEFAULT_CITATION_SIMILARITY_THRESHOLD}
   */
  static double effectiveCitationThreshold(double configured) {
    return configured > 0.0 && configured <= 1.0
        ? configured
        : DEFAULT_CITATION_SIMILARITY_THRESHOLD;
  }

  /**
   * Fetch the full document content for the supplied identifier.
   *
   * @param docId canonical document identifier
   * @return async stage containing the resolved document payload
   */
  CompletionStage<DocumentRecord> fetch(String docId, EngineContext engineContext);

  /**
   * List parent document identifiers from the Worker-owned index.
   *
   * <p>This optional seam exists for bounded evaluation snapshots. The default preserves this
   * interface's single abstract method, so simple {@code DocumentService} lambdas remain valid.
   */
  default CompletionStage<DocumentIdPage> listAllDocumentIds(int offset, int limit, EngineContext engineContext) {
    return CompletableFuture.failedFuture(
        new UnsupportedOperationException("Document ID enumeration is not configured"));
  }

  /**
   * Fetch multiple documents by their identifiers in a single batch operation.
   *
   * @param docIds list of canonical document identifiers
   * @return async stage containing a map of docId to resolved document payload
   */
  default CompletionStage<Map<String, DocumentRecord>> fetchBatch(List<String> docIds, EngineContext engineContext) {
    if (docIds == null || docIds.isEmpty()) {
      return CompletableFuture.completedFuture(Map.of());
    }
    List<String> requestedIds = new ArrayList<>(docIds);

    // Start every fetch immediately through its own implementation. The allOf continuation only
    // joins after every stage has completed, so this default composition never blocks a common-pool
    // worker while waiting for another document.
    List<CompletableFuture<DocumentRecord>> fetches = new ArrayList<>(requestedIds.size());
    for (String docId : requestedIds) {
      try {
        CompletionStage<DocumentRecord> stage = fetch(docId, engineContext);
        if (stage == null) {
          throw new NullPointerException("fetch returned null stage");
        }
        fetches.add(stage.handle((record, failure) -> {
          if (failure == null) {
            return record;
          }
          if (failure instanceof Error error) {
            throw error;
          }
          return failedRecord(docId, failure);
        }).toCompletableFuture());
      } catch (Exception e) {
        fetches.add(CompletableFuture.completedFuture(failedRecord(docId, e)));
      } catch (Error error) {
        fetches.add(CompletableFuture.failedFuture(error));
      }
    }

    CompletableFuture<?>[] all = fetches.toArray(CompletableFuture<?>[]::new);
    return CompletableFuture.allOf(all)
        .thenApply(ignored -> {
          Map<String, DocumentRecord> results = new LinkedHashMap<>();
          for (int i = 0; i < requestedIds.size(); i++) {
            results.put(requestedIds.get(i), fetches.get(i).join());
          }
          return results;
        });
  }

  private static DocumentRecord failedRecord(String docId, Throwable failure) {
    Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
    String errorType = cause.getClass().getSimpleName();
    String errorMsg = cause.getMessage() != null ? cause.getMessage() : "unknown";
    return new DocumentRecord(docId, "", Map.of("error", errorMsg, "errorType", errorType));
  }

  /**
   * Retrieves relevant context for Q&A using RAG (Retrieval-Augmented Generation).
   *
   * <p>Uses BM25 search to find the most relevant chunks from the specified documents.
   * Falls back to full document content if chunks are not indexed.
   *
   * @param question the user's question
   * @param docIds set of document IDs to search within
   * @param topK number of chunks to retrieve (default: 5)
   * @return formatted context string containing relevant chunks
   */

  /**
   * Retrieves relevant context for Q&A using RAG (Retrieval-Augmented Generation),
   * with metadata about whether chunks were actually used.
   *
   * <p>Uses BM25 search to find the most relevant chunks from the specified documents.
   * Falls back to full document content if chunks are not indexed.
   *
   * @param question the user's question
   * @param docIds set of document IDs to search within
   * @param topK number of chunks to retrieve (default: 5)
   * @return result containing context string and chunk usage metadata
   */
  default CompletionStage<ContextResult> retrieveContextWithMeta(String question, Set<String> docIds, int topK, EngineContext engineContext) {
    return retrieveContextWithMeta(question, docIds, topK, 0, engineContext);
  }

  /**
   * Retrieves relevant context for Q&A using RAG with token budget.
   *
   * <p>Phase 6 (Gap 6): When maxContextTokens > 0, the Worker uses token-aware budgeting
   * to avoid over-fetching context that would be truncated by the Head. This eliminates
   * the double-truncation problem where Worker returns 200K chars that Head truncates to 3K tokens.
   *
   * @param question the user's question
   * @param docIds set of document IDs to search within
   * @param topK number of chunks to retrieve (default: 5)
   * @param maxContextTokens token budget (0 = use character budget fallback)
   * @return result containing context string and chunk usage metadata
   */
  default CompletionStage<ContextResult> retrieveContextWithMeta(String question, Set<String> docIds, int topK, int maxContextTokens, EngineContext engineContext) {
    // Default: fall back to batch fetch and concatenate (no RAG - chunksUsed=0)
    return fetchBatch(List.copyOf(docIds), engineContext)
        .thenApply(docs -> {
          StringBuilder sb = new StringBuilder();
          // Mirrors ContextBudgeter.sectionHeader ("[n] label\n") — app-api cannot depend on
          // :modules:indexing, the same constraint SECTION_SEPARATOR above is mirrored under. The
          // 1-based ordinal is the one the prompt asks the model to cite (tempdoc 822 §3a).
          int sectionNumber = 0;
          for (var entry : docs.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().content().isBlank()) {
              if (sb.length() > 0) {
                sb.append(SECTION_SEPARATOR);
              }
              sectionNumber++;
              sb.append('[').append(sectionNumber).append("] ")
                  .append(extractFilename(entry.getKey())).append('\n');
              sb.append(entry.getValue().content());
            }
          }
          // Default impl uses full docs, not chunks
          return new ContextResult(sb.toString(), 0, 0, docs.size(), List.of(),
              "FULLTEXT_FALLBACK", "DEFAULT_IMPL_NO_CHUNKS", false, List.of());
        });
  }

  /**
   * Immutable carrier for RAG context retrieval results.
   *
   * <p>Includes metadata to distinguish between:
   * <ul>
   *   <li>Actual chunk-based RAG (chunksUsed > 0)</li>
   *   <li>Fallback to full documents (chunksUsed == 0, docsUsed > 0)</li>
   *   <li>No content available (both zero)</li>
   * </ul>
   *
   * @param context the formatted context string
   * @param chunksUsed number of chunks included in the context (0 = fallback to full docs)
   * @param chunksFound total chunks found by search (may be > chunksUsed due to limits)
   * @param docsUsed number of full documents used (when chunksUsed == 0)
   * @param citations structured chunk span metadata for click-to-verify UI (empty for fallback)
   * @param retrievalMode retrieval mode used: BM25, HYBRID, FULLTEXT_FALLBACK
   * @param retrievalModeReason stable reason code explaining the mode choice
   * @param contextTruncated true if context was truncated due to token budget
   * @param sections structured sections linking content to citations (Phase 4)
   */
  record ContextResult(
      String context,
      int chunksUsed,
      int chunksFound,
      int docsUsed,
      List<ContextCitation> citations,
      String retrievalMode,
      String retrievalModeReason,
      boolean contextTruncated,
      List<ContextSection> sections,
      QualitySignals quality) {
    public ContextResult {
      context = context == null ? "" : context;
      chunksUsed = Math.max(0, chunksUsed);
      chunksFound = Math.max(0, chunksFound);
      docsUsed = Math.max(0, docsUsed);
      citations = citations == null ? List.of() : List.copyOf(citations);
      retrievalMode = retrievalMode == null ? "" : retrievalMode;
      retrievalModeReason = retrievalModeReason == null ? "" : retrievalModeReason;
      sections = sections == null ? List.of() : List.copyOf(sections);
      quality = quality == null ? QualitySignals.EMPTY : quality;
    }

    /** Backward-compatible constructor without quality signals. */
    public ContextResult(
        String context, int chunksUsed, int chunksFound, int docsUsed,
        List<ContextCitation> citations, String retrievalMode,
        String retrievalModeReason, boolean contextTruncated,
        List<ContextSection> sections) {
      this(context, chunksUsed, chunksFound, docsUsed, citations,
          retrievalMode, retrievalModeReason, contextTruncated, sections,
          QualitySignals.EMPTY);
    }

    /** Returns true if actual chunk-based RAG was used (not fallback). */
    public boolean usedChunks() {
      return chunksUsed > 0;
    }
  }

  /** Retrieval quality signals for CRAG-style confidence assessment. */
  record QualitySignals(
      float bestChunkScore,
      float scoreGap,
      float retrievalCoverage,
      int chunksConsidered,
      int chunksIncluded) {

    static final QualitySignals EMPTY = new QualitySignals(0f, 0f, 0f, 0, 0);
  }

  /**
   * Retrieves relevant context for Q&A using RAG with full filter support.
   *
   * <p>This is the rich alternative to the positional-parameter overloads.
   * Supports entity filters, temporal filters, content filters, auto entity
   * extraction, and context format selection.
   *
   * @param params retrieval parameters
   * @return result containing context string, chunk metadata, and quality signals
   */
  default CompletionStage<ContextResult> retrieveContext(RetrieveContextParams params, EngineContext engineContext) {
    // Default: delegate to legacy method (ignoring new filter params)
    return retrieveContextWithMeta(
        params.question(), params.docIds(), params.topK(), params.maxContextTokens(), engineContext);
  }

  /**
   * Structured citation metadata for a chunk used in RAG context.
   *
   * <p>Offsets are 0-based character offsets into the parent document's extracted text.
   */
  record ContextCitation(
      String parentDocId,
      int chunkIndex,
      int chunkTotal,
      int startChar,
      int endChar,
      float score,
      String excerpt,
      // F8 Tier 2: In-document navigation fields
      int startLine,
      int endLine,
      String headingText,
      int headingLevel,
      // Tempdoc 849 §5.3 — retrieved-vs-received, resolved at the head's cut (never at construction)
      ContextInclusion inclusion) {

    /**
     * The absence of a chunk ordinal (tempdoc 836 §8.4). A whole-document source has no chunk
     * position, and {@code 0} is not "unknown" — it is a claim that the text is the document's
     * FIRST chunk, which is the fabrication that made the 836 defect constructible. This is the
     * same sentinel the agent tier ({@code AgentSession.DOC_LEVEL_SENTINEL}) and the frontend
     * ({@code DOC_LEVEL_CHUNK_SENTINEL}) already use, so the absence is modelled once, not thrice.
     */
    public static final int CHUNK_INDEX_ABSENT = -1;

    public ContextCitation {
      parentDocId = parentDocId == null ? "" : parentDocId;
      // Any negative ordinal collapses to the ABSENT sentinel; a valid ordinal is kept. The old
      // `Math.max(0, …)` silently converted "no chunk" into "chunk 0" — it destroyed the very
      // distinction the sentinel exists to carry (it also erased the agent tier's document-level
      // sources on their way into a match request).
      chunkIndex = Math.max(CHUNK_INDEX_ABSENT, chunkIndex);
      chunkTotal = Math.max(1, chunkTotal);
      startChar = Math.max(0, startChar);
      endChar = Math.max(0, endChar);
      excerpt = excerpt == null ? "" : excerpt;
      startLine = Math.max(0, startLine);
      endLine = Math.max(0, endLine);
      headingText = headingText == null ? "" : headingText;
      headingLevel = Math.max(0, headingLevel);
      // Tempdoc 849 §5.3 — a citation is CONSTRUCTED absent. Inclusion is not knowable where the
      // record is minted (the Worker does not run the head's cut), so null collapses to the same
      // explicit ABSENT the CHUNK_INDEX_ABSENT sentinel above models for the chunk ordinal.
      inclusion = inclusion == null ? ContextInclusion.ABSENT : inclusion;
    }

    /**
     * Returns a copy carrying the inclusion state resolved AT the truncation cut (tempdoc 849
     * §5.3, route (a)). The one transformation that may set this component — everything upstream
     * of the cut leaves it {@link ContextInclusion#ABSENT}, so "the producer said nothing" and
     * "the passage reached the model whole" stay distinguishable on the record itself.
     */
    public ContextCitation withInclusion(ContextInclusion resolved) {
      return new ContextCitation(
          parentDocId, chunkIndex, chunkTotal, startChar, endChar, score, excerpt,
          startLine, endLine, headingText, headingLevel,
          resolved == null ? ContextInclusion.ABSENT : resolved);
    }
  }

  /**
   * Tempdoc 849 §5.1 — whether the passage a citation names actually reached the model.
   *
   * <p>Retrieval and inclusion are two different facts. A citation is emitted for every passage the
   * retriever selected; the head's token budget then decides how much of that set the prompt can
   * actually hold. Before 849 the trimmed context still carried every citation, so a passage the
   * cut discarded was indistinguishable from one the model read in full — the class javadoc of
   * {@code RAGContext} documented that gap as permanent.
   *
   * <p>Modelled on {@code SourceExamination} (836) one pipeline stage earlier, including its
   * absence discipline: {@link State#ABSENT} means the producer said nothing, and a consumer must
   * then say nothing — never assume {@link State#INCLUDED} on the producer's behalf. That is what
   * keeps a conversation persisted before 849 from being retroactively described.
   *
   * <p>Containment (the rule {@code SourceExamination} already carries): this is a BUDGET fact. It
   * never feeds a grounding tier, a grounding count, or a relevance score.
   *
   * @param state included / partial / dropped, or ABSENT when unresolved
   * @param includedChars characters of the passage that reached the model;
   *     {@link #INCLUDED_CHARS_UNKNOWN} when the state is ABSENT
   */
  record ContextInclusion(State state, int includedChars) {

    /** The four states, ABSENT included so "unresolved" is modelled rather than defaulted away. */
    enum State {
      /** The producer did not resolve inclusion. Say nothing; do not read as INCLUDED. */
      ABSENT,
      /** The whole passage reached the model. */
      INCLUDED,
      /** The passage reached the model with its tail cut (worker-side or at the head's cut). */
      PARTIAL,
      /** The citation's passage contributed no text to the prompt. */
      DROPPED
    }

    /** No character count is knowable, because no inclusion state was resolved. */
    public static final int INCLUDED_CHARS_UNKNOWN = -1;

    /** The state every {@link ContextCitation} is constructed in. */
    public static final ContextInclusion ABSENT =
        new ContextInclusion(State.ABSENT, INCLUDED_CHARS_UNKNOWN);

    public ContextInclusion {
      state = state == null ? State.ABSENT : state;
      includedChars =
          state == State.ABSENT ? INCLUDED_CHARS_UNKNOWN : Math.max(0, includedChars);
    }

    /** The whole passage reached the model. */
    public static ContextInclusion included(int includedChars) {
      return new ContextInclusion(State.INCLUDED, includedChars);
    }

    /** The passage reached the model with its tail cut. */
    public static ContextInclusion partial(int includedChars) {
      return new ContextInclusion(State.PARTIAL, includedChars);
    }

    /** The passage contributed no text to the prompt. */
    public static ContextInclusion dropped() {
      return new ContextInclusion(State.DROPPED, 0);
    }

    /** True when nothing was resolved — the emitter must then emit no inclusion key at all. */
    public boolean absent() {
      return state == State.ABSENT;
    }

    /**
     * The wire vocabulary the FE mirrors ({@code 'included' | 'partial' | 'dropped'}). Callers
     * must check {@link #absent()} first: absence is expressed by omitting the key, never by a
     * fourth string the consumer would have to interpret.
     */
    public String wireName() {
      return state.name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * The inverse of {@link #wireName()} — read an inclusion back off a wire value (tempdoc 865
     * §7.5, for the delegate plane, whose {@code AgentEvent.AgentSource} carries the state as a wire
     * name because {@code app-agent-api} cannot see this enum).
     *
     * <p>Fails CLOSED, exactly as {@link ScorerKind#fromWire} and the frontend's {@code
     * contextInclusionOf} do: an absent, blank or unrecognised value yields {@link #ABSENT}. An
     * unknown state is not a known one, and guessing which of the three it meant is how a vocabulary
     * drift becomes a false claim about what the model was shown.
     */
    public static ContextInclusion fromWire(String wire, int includedChars) {
      if (wire == null || wire.isBlank()) {
        return ABSENT;
      }
      for (State candidate : State.values()) {
        if (candidate != State.ABSENT
            && candidate.name().toLowerCase(java.util.Locale.ROOT).equals(wire)) {
          return new ContextInclusion(candidate, includedChars);
        }
      }
      return ABSENT;
    }
  }

  /**
   * A section in the assembled RAG context, linking to citation metadata.
   * Phase 4: Enables structured section tracking for citation filtering on truncation.
   */
  record ContextSection(
      String sourceLabel,
      String content,
      boolean truncated,
      int sectionIndex,
      int chunkIndex) {
    public ContextSection {
      sourceLabel = sourceLabel == null ? "" : sourceLabel;
      content = content == null ? "" : content;
      sectionIndex = Math.max(0, sectionIndex);
      chunkIndex = Math.max(0, chunkIndex);
    }
  }

  /**
   * Post-hoc citation matching: matches LLM answer sentences to source chunks
   * via embedding similarity on the Worker side.
   *
   * <p>Convenience overload for callers that have no literal text to verify against: every
   * citation is mapped to a {@link VerificationSource} with blank text, so the Worker re-fetches
   * the chunk by {@code (parentDocId, chunkIndex)} exactly as before.
   *
   * @param answerText the full LLM answer text
   * @param citations the context citations from RAG retrieval
   * @param threshold minimum cosine similarity (0.0-1.0)
   * @return result containing matched sentence-to-chunk mappings
   */
  default CompletionStage<CitationMatchResult> matchCitations(
      String answerText, List<ContextCitation> citations, double threshold, EngineContext engineContext) {
    List<VerificationSource> sources =
        citations == null
            ? List.of()
            : citations.stream().map(c -> new VerificationSource(c, "")).toList();
    return matchCitationsAgainst(answerText, sources, threshold, engineContext);
  }

  /**
   * Post-hoc citation matching against sources that may carry their own literal text
   * (tempdoc 836 §1.4).
   *
   * <p>This is the real method; the {@link #matchCitations(String, List, double, EngineContext)} overload
   * delegates here. A source whose {@link VerificationSource#literalText()} is non-blank is
   * verified against THAT text; a blank one is looked up from the index by its citation's
   * {@code (parentDocId, chunkIndex)}. The choice is per source, never all-or-nothing.
   *
   * @param answerText the full LLM answer text
   * @param sources the sources to verify against, in the order the UI numbers them
   * @param threshold minimum similarity (0.0-1.0)
   * @return result containing matched sentence-to-source mappings
   */
  default CompletionStage<CitationMatchResult> matchCitationsAgainst(
      String answerText, List<VerificationSource> sources, double threshold, EngineContext engineContext) {
    return CompletableFuture.completedFuture(
        new CitationMatchResult(List.of(), 0, 0, 0, 0, ScorerKind.NONE, List.of()));
  }

  /**
   * A source the matcher may verify against: its citation identity plus, optionally, the literal
   * text that was actually shown to the model (tempdoc 836 §1.4).
   *
   * <p>One position authority instead of parallel lists kept equal by discipline. {@code
   * literalText} blank means "this source supplies no text" — look it up.
   */
  record VerificationSource(ContextCitation citation, String literalText) {
    public VerificationSource {
      literalText = literalText == null ? "" : literalText;
    }

    /** True when this source carries text to verify against, rather than a lookup key. */
    public boolean suppliesText() {
      return !literalText.isBlank();
    }
  }

  /**
   * Which producer wrote the {@code similarity} on a match (tempdoc 836 §4). The two scoring
   * producers are on measurably incomparable scales (P4: cross-encoder is bimodal at 0.89-0.999
   * vs below 0.001; cosine is compressed into 0.38-0.72 with the classes interleaving), so
   * "which one ran" is not a diagnostic detail — it is part of reading the number.
   */
  enum ScorerKind {
    /** CPU cross-encoder, sigmoid-normalized relevance. */
    CROSS_ENCODER,
    /** Embedding cosine fallback, raw cosine. */
    EMBEDDING_COSINE,
    /** Nothing scored (no producer available, or no input). */
    NONE;

    /** Parses the wire string, mapping anything unrecognized to {@link #NONE}. */
    public static ScorerKind fromWire(String wire) {
      if (wire == null || wire.isBlank()) {
        return NONE;
      }
      try {
        return valueOf(wire);
      } catch (IllegalArgumentException e) {
        return NONE;
      }
    }
  }

  /** Which text a match was scored against (tempdoc 836 §4). */
  enum TextSource {
    /** The caller's literal passage text. */
    SUPPLIED,
    /** Chunk text re-fetched from the index by {@code (parentDocId, chunkIndex)}. */
    CHUNK_LOOKUP;

    /** Parses the wire string, mapping anything unrecognized to {@link #CHUNK_LOOKUP}. */
    public static TextSource fromWire(String wire) {
      if (wire == null || wire.isBlank()) {
        return CHUNK_LOOKUP;
      }
      try {
        return valueOf(wire);
      } catch (IllegalArgumentException e) {
        return CHUNK_LOOKUP;
      }
    }
  }

  /**
   * Result of post-hoc citation matching.
   *
   * @param sentencesScored how many sentences were actually scored — below {@code sentencesTotal}
   *     whenever the deadline or the admission cap cut the pass short (tempdoc 836 §3.6). It is
   *     the honest denominator for a coverage claim; {@code sentencesMatched} over
   *     {@code sentencesTotal} attributes a budget shortfall to the evidence.
   * @param scorer which producer wrote the similarities (tempdoc 836 §4)
   * @param sourceCoverage per-source examination facts (tempdoc 836 S2S3-A.1) — the TEXT axis,
   *     reported beside the sentence axis and never blended into one ratio. Empty when the Worker
   *     never got as far as preparing passages, which is "nothing is known", not "nothing was
   *     examined"
   */
  record CitationMatchResult(
      List<CitationMatchEntry> matches,
      int sentencesTotal,
      int sentencesMatched,
      long tookMs,
      int sentencesScored,
      ScorerKind scorer,
      List<SourceCoverage> sourceCoverage) {
    public CitationMatchResult {
      matches = matches == null ? List.of() : List.copyOf(matches);
      sentencesTotal = Math.max(0, sentencesTotal);
      sentencesMatched = Math.max(0, sentencesMatched);
      tookMs = Math.max(0, tookMs);
      sentencesScored = Math.max(0, sentencesScored);
      scorer = scorer == null ? ScorerKind.NONE : scorer;
      sourceCoverage = sourceCoverage == null ? List.of() : List.copyOf(sourceCoverage);
    }

    /** True when the pass did not reach every sentence — a scoring-incomplete state, not a ratio. */
    public boolean scoringIncomplete() {
      return sentencesScored < sentencesTotal;
    }

    /**
     * True when every source that had any text was examined in full. DERIVED, so the aggregate
     * never becomes a second authority beside the per-source facts (tempdoc 836 S2S3-A.1). An
     * absent coverage list is vacuously complete — it makes no claim.
     */
    public boolean textCoverageComplete() {
      return sourceCoverage.stream().allMatch(SourceCoverage::fullyExamined);
    }

    /**
     * Positions of the sources the budget starved: text existed, no window survived admission.
     * These are uncitable for reasons of BUDGET, and a caller must not report them as unsupported.
     */
    public List<Integer> starvedSources() {
      return sourceCoverage.stream()
          .filter(SourceCoverage::starved)
          .map(SourceCoverage::sourceIndex)
          .toList();
    }
  }

  /**
   * How much of ONE source's text the matcher actually looked at (tempdoc 836 S2S3-A.1).
   *
   * <p>Admission control preserves SENTENCE coverage by cutting WINDOWS, so an answer can report
   * every sentence scored while most of the supplied text was never read. This is the axis that
   * says so — and it is per source because an aggregate cannot express "source 3 got no window at
   * all", which reads identically to "source 3 supports nothing" without it.
   *
   * @param sourceIndex position in the sources list handed to the matcher (the 836 §5.4 contract)
   * @param windowsConsidered windows this source's text produced, before admission control
   * @param windowsScored how many survived admission and were actually scored
   */
  record SourceCoverage(int sourceIndex, int windowsConsidered, int windowsScored) {

    /** Text existed but the budget gave it no window — never examined, NOT unsupported. */
    public boolean starved() {
      return windowsConsidered > 0 && windowsScored == 0;
    }

    /** No text at all: a blank supply and a failed (or impossible) lookup. Unverifiable. */
    public boolean noText() {
      return windowsConsidered == 0;
    }

    /** Every window this source produced was scored. Only this state supports "unsupported". */
    public boolean fullyExamined() {
      return windowsScored >= windowsConsidered;
    }
  }

  /**
   * A single answer-sentence-to-source citation match.
   *
   * <p>Tempdoc 822 §3b (the numbering contract): {@code sourceIndex} is the matched source's
   * POSITION in the {@code citations} list handed to {@link #matchCitations} — the same list the UI
   * numbers its {@code [n]} marks from. It is NOT the chunk's ordinal inside its parent document
   * (that fact lives only on {@link ContextCitation#chunkIndex()} and never travels on a match).
   * Renamed from {@code chunkIndex}, which let the two facts be conflated silently.
   */
  record CitationMatchEntry(
      int sentenceIndex,
      String sentenceText,
      int sourceIndex,
      double similarity,
      String parentDocId,
      TextSource textSource) {
    public CitationMatchEntry {
      sentenceText = sentenceText == null ? "" : sentenceText;
      parentDocId = parentDocId == null ? "" : parentDocId;
      textSource = textSource == null ? TextSource.CHUNK_LOOKUP : textSource;
    }
  }

  private static String extractFilename(String path) {
    if (path == null) return "unknown";
    int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
    return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
  }

  /**
   * Null Object for environments where the Worker isn't connected. Returns a service whose
   * {@code fetch}/etc. methods complete with a failed future carrying
   * {@code UnsupportedOperationException}. Used as the seed for the {@code LazyDocumentService}
   * supplier chain before late-bind. Tempdoc 519 F2 (refined per §22): kept as the Null Object
   * pattern.
   */
  static DocumentService unavailable() {
    return (docId, engineContext) ->
        CompletableFuture.failedFuture(
            new UnsupportedOperationException("Document service not configured"));
  }

  /** Signals that the underlying document store or index could not be reached. */
  class UnavailableException extends RuntimeException {
    public UnavailableException(String message) {
      super(message);
    }

    public UnavailableException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** Immutable carrier for resolved document data. */
  record DocumentRecord(String docId, String content, Map<String, Object> metadata) {
    @SuppressWarnings("SelfAssignment")
    public DocumentRecord {
      docId = Objects.requireNonNull(docId, "docId");
      content = content == null ? "" : content;
      metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
  }

  /**
   * Fetch a slice of the document content (paged by character offsets).
   *
   * <p>Implementations should prefer serving slices from the Worker/index (extracted text) rather than reading raw
   * files. The default implementation falls back to {@link #fetch(String, EngineContext)} and slices in-process.
   *
   * @param docId canonical document identifier
   * @param offsetChars 0-based offset into the extracted text
   * @param maxChars maximum number of characters to return
   */
  default CompletionStage<DocumentSlice> fetchSlice(String docId, int offsetChars, int maxChars, EngineContext engineContext) {
    int offset = Math.max(0, offsetChars);
    int max = maxChars <= 0 ? 20_000 : maxChars;
    return fetch(docId, engineContext)
        .thenApply(
            record -> {
              if (record == null) {
                return new DocumentSlice(docId, "", Map.of(), false, false, 0, 0, "not_found");
              }
              String content = record.content() == null ? "" : record.content();
              int totalLen = content.length();
              int start = Math.min(offset, totalLen);
              int end = Math.min(start + max, totalLen);
              String slice = start >= end ? "" : content.substring(start, end);
              boolean truncated = end < totalLen;
              return new DocumentSlice(
                  record.docId(),
                  slice,
                  record.metadata(),
                  true,
                  truncated,
                  end,
                  totalLen,
                  null);
            });
  }

  /**
   * Immutable carrier for a paged slice of extracted text content.
   *
   * <p>{@code totalChars} is the full length of the extracted text, or {@code 0} when the producer
   * cannot say (a not-found slice, or a Worker predating tempdoc 878's {@code total_chars} field).
   * Zero therefore means UNKNOWN, never "an empty document" — a consumer must not render it as a
   * denominator.
   */
  record DocumentSlice(
      String docId,
      String content,
      Map<String, Object> metadata,
      boolean found,
      boolean truncated,
      int nextOffsetChars,
      int totalChars,
      String extractionStatus,
      Boolean contentTruncated,
      String extractionPolicyId,
      String extractionParserId,
      String sourceSha256,
      String error) {
    public DocumentSlice {
      Objects.requireNonNull(docId, "docId");
      content = content == null ? "" : content;
      metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
      nextOffsetChars = Math.max(0, nextOffsetChars);
      totalChars = Math.max(0, totalChars);
      extractionStatus = blankToNull(extractionStatus);
      extractionPolicyId = blankToNull(extractionPolicyId);
      extractionParserId = blankToNull(extractionParserId);
      sourceSha256 = blankToNull(sourceSha256);
      error = error == null || error.isBlank() ? null : error;
    }

    /** Backward-compatible constructor for implementations without extraction provenance. */
    public DocumentSlice(
        String docId,
        String content,
        Map<String, Object> metadata,
        boolean found,
        boolean truncated,
        int nextOffsetChars,
        int totalChars,
        String error) {
      this(
          docId,
          content,
          metadata,
          found,
          truncated,
          nextOffsetChars,
          totalChars,
          null,
          null,
          null,
          null,
          null,
          error);
    }

    private static String blankToNull(String value) {
      return value == null || value.isBlank() ? null : value;
    }
  }

  /** Immutable page of parent document IDs returned by the Worker-owned index. */
  record DocumentIdPage(List<String> docIds, long totalCount, long tookMs) {
    public DocumentIdPage {
      docIds = List.copyOf(Objects.requireNonNull(docIds, "docIds"));
      if (totalCount < docIds.size()) {
        throw new IllegalArgumentException("totalCount must cover every returned document ID");
      }
      if (tookMs < 0) {
        throw new IllegalArgumentException("tookMs must be non-negative");
      }
    }
  }
}
