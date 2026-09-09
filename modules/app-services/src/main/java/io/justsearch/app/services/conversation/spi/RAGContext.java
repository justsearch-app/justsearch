/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation.spi;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.api.conversation.ContextInjector;
import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.InjectorResult;
import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.DocumentService.ContextCitation;
import io.justsearch.app.api.DocumentService.ContextInclusion;
import io.justsearch.app.api.DocumentService.ContextResult;
import io.justsearch.app.api.DocumentService.ContextSection;
import io.justsearch.app.api.DocumentService.DocumentRecord;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.RetrieveContextParams;
import io.justsearch.core.util.ContextBudget;
import io.justsearch.core.util.DocumentTypeDetector;
import io.justsearch.core.util.Strings;
import io.justsearch.core.util.TokenEstimation;
import io.justsearch.core.util.TokenEstimation.TruncationResult;
import io.justsearch.indexing.chunking.ChunkSplitter;
import io.justsearch.indexing.rag.ContextBudgeter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RAG retrieval {@link ContextInjector} for the RAG-ask shape.
 *
 * <p>Per tempdoc 491 §C3: lifts the retrieval + fallback logic from
 * {@code RagStreamingHandler.fetchRagContext} / {@code fetchBatchFallback} /
 * {@code formatDocuments}. Reads {@code {question, docIds[], topK?}} from the request body,
 * runs {@link DocumentService#retrieveContextWithMeta}, falls back to
 * {@link DocumentService#fetchBatch} when chunked retrieval is unavailable, applies token
 * truncation as a safety net, emits a {@code rag.meta} event (namespaced per §C3 plan), and
 * persists citations + chunks-used + docIds into {@link ConversationContext#attributes} for
 * {@code CitationMatcher} + {@code RAGDoneEnricher} to consume.
 *
 * <p><b>Retrieved is not received (tempdoc 849).</b> Every retrieved passage still gets a citation
 * — suppressing the ones the budget cut would be a second, quieter dishonesty — but each citation
 * now SAYS which of the two it is. The cut branches on what it is cutting:
 *
 * <ul>
 *   <li><b>Sectioned</b> (the retrieval's own context, assembled by {@code ContextBudgeter}):
 *       re-assembled section by section against the token budget, so the record is produced AT the
 *       cut and every citation carries an {@link DocumentService.ContextInclusion} —
 *       included / partial / dropped. Re-assembly also keeps the {@code [n] label} headers and
 *       {@code SECTION_SEPARATOR}s intact, which the old structure-blind cut destroyed exactly when
 *       the prompt was most crowded (and with them the ordinals {@code OnlineModeOps
 *       .formatContextAsNumberedPassages} parses to keep {@code sources[n - 1]} resolvable).
 *   <li><b>Whole-document fallback</b> (context replaced by {@code fetchBatchFallback}): keeps
 *       {@link TokenEstimation#truncateIfNeeded}, because that branch has no sections to
 *       re-assemble. It emits no citations at all, so it claims nothing about inclusion — absence,
 *       not a guess. A sectioned retrieval that arrived WITHOUT sections degrades to this cut for
 *       the same reason, and its citations stay absent rather than being given a state derived from
 *       nothing.
 * </ul>
 *
 * <p>Post-hoc classification is impossible by construction: the trimmed prompt string has no
 * separators or headers left to parse, which is why the record is minted at the cut rather than
 * recovered after it.
 *
 * <p>Missing {@code question} or {@code docIds} → {@link InjectorResult#terminalError} with
 * code {@code NO_QUESTION} / {@code NO_FILES}; engine aborts before LLM call.
 */
public final class RAGContext implements ContextInjector {

  private static final Logger LOG = LoggerFactory.getLogger(RAGContext.class);

  public static final String ID = "core.rag-context";

  /** Default top-K for chunk retrieval (matches legacy default). */
  public static final int DEFAULT_TOP_K = 5;

  /** Default fetch timeout for retrieval and batch-fetch fallback. */
  static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);

  /** Attribute keys for cross-SPI handoff. */
  public static final String ATTR_CITATIONS = "rag.citations";
  public static final String ATTR_CHUNKS_USED = "rag.chunksUsed";
  public static final String ATTR_CHUNKS_FOUND = "rag.chunksFound";
  public static final String ATTR_USED_RAG = "rag.usedRag";
  public static final String ATTR_DOC_IDS = "rag.docIds";
  public static final String ATTR_FILE_COUNT = "rag.fileCount";

  /**
   * The sources a citation matcher should verify against, WITH the literal text that was actually
   * put in front of the model (tempdoc 836 §1.4). Set by injectors that hold that text; the
   * retrieval path leaves it unset, because its citations carry true chunk ordinals and the
   * Worker's lookup resolves the right text for them.
   *
   * <p>Holds {@code List<DocumentService.VerificationSource>}. It is not a second copy of {@link
   * #ATTR_CITATIONS}: each entry CONTAINS its citation, so the two can never disagree about which
   * source is at position i.
   */
  public static final String ATTR_VERIFICATION_SOURCES = "rag.verificationSources";

  /**
   * Tempdoc 610 §J.3 — the conversation's hidden-source ids (unit-separator-joined parentDocId +
   * chunkIndex), seeded onto the context by the engine from the ConversationStore. RAGContext threads
   * them to retrieval so the Worker drops those chunks pre-search. A {@code List<String>}; absent = none.
   */
  public static final String ATTR_EXCLUDED_SOURCES = "rag.excludedSources";

  /**
   * Tempdoc 561 P-A/P-B — the producer-owned calibration signal for the answer's evidence. Holds the
   * CRAG-style {@link DocumentService.QualitySignals} (best chunk score, score gap, retrieval
   * coverage, chunks considered) the retrieval producer computed, so {@code RAGDoneEnricher} can
   * project it onto the done payload and the engine can persist it WITH the assistant turn. The
   * consumer renders the producer's calibration rather than re-deriving confidence FE-side.
   */
  public static final String ATTR_QUALITY = "rag.quality";

  /**
   * Tempdoc 845 — the completion tokens this turn has reserved, i.e. the exact {@code max_tokens}
   * the engine will send. Seeded by {@code ConversationEngine} from the same variable it later
   * hands to the LLM call, so the reserve used for budgeting cannot drift from the reserve the
   * server actually enforces.
   *
   * <p>An {@code Integer}. Reasoning tokens are spent INSIDE this reservation, not alongside it
   * (tempdoc 835: "reasoning tokens and answer tokens share one ceiling"), so it is the whole
   * reserve — never add a reasoning budget on top.
   *
   * <p>Absent (a caller that is not the engine) = {@link #DEFAULT_COMPLETION_RESERVE_TOKENS}.
   */
  public static final String ATTR_COMPLETION_RESERVE_TOKENS = "llm.completionReserveTokens";

  /**
   * Fallback completion reserve when {@link #ATTR_COMPLETION_RESERVE_TOKENS} is absent. Mirrors
   * {@code ConversationEngine.DEFAULT_MAX_TOKENS}; deliberately not a cross-package reference,
   * because this is a fallback for callers that are NOT the engine.
   */
  static final int DEFAULT_COMPLETION_RESERVE_TOKENS = 1024;

  /**
   * Fallback context window when no live value is available - the smallest rung of the launch
   * ladder ({@code ContextWindowPolicy}), i.e. the smallest window any server this app starts can
   * end up with. Deliberately NOT the derived default, which tempdoc 883 made 32768 / 8192 by
   * backend: this constant is the last resort for callers with no observed and no configured
   * window, and over-committing a budget against a window that may not exist is the failure it
   * exists to prevent. The composition root supplies the live observed window instead.
   *
   * <p>Tempdoc 883 PR 2: the value now LIVES on {@link ContextBudget}, which is where the whole
   * precedence walk lives. This alias is kept because this class's tests and javadoc name it, and
   * because a second literal here would be exactly the fork {@link ContextBudget} exists to end.
   */
  static final int DEFAULT_CONTEXT_WINDOW_TOKENS = ContextBudget.FALLBACK_WINDOW_TOKENS;

  private final DocumentService documents;
  private final Duration timeout;

  /**
   * Tempdoc 845 — the LIVE llama-server context window, read per request.
   *
   * <p>Both budget call sites used to hardcode 8192, a window that does not exist: the shipped
   * default is 4096, so every ask over-committed its input budget by roughly 2x and Thorough
   * (maxTokens 3072) reliably 400ed at the server. Resolved per request, not at construction,
   * because the observed window is unknown until a server is started or adopted and changes when
   * one is restarted.
   *
   * <p>Precedence: observed {@code /props} n_ctx -> configured launch window ->
   * {@link #DEFAULT_CONTEXT_WINDOW_TOKENS}. "Unknown" falls through to the configured value rather
   * than being treated as healthy.
   */
  private final Supplier<OnlineAiService> onlineAi;

  /**
   * Fallback top-K when the request body does not specify one. Sourced from
   * {@code justsearch.rag.top_k} at the composition root (tempdoc 799 N.2). Before that the
   * setting resolved correctly and was read by nothing — {@link #DEFAULT_TOP_K} always won.
   *
   * <p>Precedence is body -> this -> {@link #DEFAULT_TOP_K}; an explicit per-request topK still
   * wins, so wiring config cannot override a caller that asked for a specific value.
   */
  private final int defaultTopK;

  public RAGContext(DocumentService documents) {
    this(documents, DEFAULT_TIMEOUT, DEFAULT_TOP_K);
  }

  public RAGContext(DocumentService documents, Duration timeout) {
    this(documents, timeout, DEFAULT_TOP_K);
  }

  /**
   * Configured-top-K constructor (tempdoc 799 N.2). Keeps {@link #DEFAULT_TIMEOUT} internal so the
   * composition root does not need visibility of it.
   */
  public RAGContext(DocumentService documents, int defaultTopK) {
    this(documents, DEFAULT_TIMEOUT, defaultTopK);
  }

  public RAGContext(DocumentService documents, Duration timeout, int defaultTopK) {
    this(documents, timeout, defaultTopK, null);
  }

  /**
   * Live-window constructor (tempdoc 845). The composition root passes the same
   * {@code Supplier<OnlineAiService>} the engine holds, so the token budget is computed against
   * the window the running server actually has.
   *
   * @param onlineAi supplier of the online-AI handle, or null to budget against
   *     {@link #DEFAULT_CONTEXT_WINDOW_TOKENS}
   */
  public RAGContext(
      DocumentService documents,
      Duration timeout,
      int defaultTopK,
      Supplier<OnlineAiService> onlineAi) {
    this.documents = Objects.requireNonNull(documents, "documents");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
    this.defaultTopK = defaultTopK > 0 ? defaultTopK : DEFAULT_TOP_K;
    this.onlineAi = onlineAi;
  }

  /**
   * Composition-root constructor (tempdoc 845) — configured top-K plus the live window.
   */
  public RAGContext(
      DocumentService documents, int defaultTopK, Supplier<OnlineAiService> onlineAi) {
    this(documents, DEFAULT_TIMEOUT, defaultTopK, onlineAi);
  }

  @Override
  public String id() {
    return ID;
  }

  /**
   * Tempdoc 845 / 883 decision 3 — the ONE place a conversation request decides how many input
   * tokens it may spend, and the ONE place the live context window is read.
   *
   * <p>Every budget consumer in this turn (the shape of the retrieval ask, the wire
   * {@code maxContextTokens}, the post-retrieval cut) reads the single {@link ContextBudget} built
   * from this, so they cannot disagree about how much room the prompt has. Before 845 two of them
   * hardcoded {@code computeSafeInputBudgetTokens(8192, 1024)} independently — a window that does
   * not exist paired with a reserve that ignored the request, yielding ~5990 tokens of promised
   * input against a real 4096-token window.
   *
   * <p>Every context injector in this package budgets through here rather than re-reading
   * {@link OnlineAiService}, so the window walk (observed {@code /props} n_ctx → configured launch
   * window → {@link ContextBudget#FALLBACK_WINDOW_TOKENS}) exists once. Unknown is never treated as
   * generous: it falls through to the next most authoritative value.
   *
   * @param onlineAi supplier of the online-AI handle; null or an unavailable handle budgets against
   *     the fallback window
   * @param ctx the turn, read for the completion reserve the engine published onto it
   */
  public static ContextBudget budgetFor(
      Supplier<OnlineAiService> onlineAi, ConversationContext ctx) {
    return budgetFor(onlineAi, completionReserveTokens(ctx));
  }

  /**
   * The same budget for a caller that knows its own completion reserve — a shape runner whose
   * {@code max_tokens} is its own constant rather than the request's.
   */
  public static ContextBudget budgetFor(
      Supplier<OnlineAiService> onlineAi, int completionReserveTokens) {
    OnlineAiService ai = onlineAi == null ? null : onlineAi.get();
    return ContextBudget.of(
        ai == null ? null : ai.llmContextTokens(),
        ai == null ? null : ai.configuredContextTokens(),
        completionReserveTokens);
  }

  /**
   * The completion tokens this turn reserved — the engine's effective {@code max_tokens}, seeded on
   * the context. Reasoning is spent inside this number (tempdoc 835), so it is the whole reserve.
   */
  private static int completionReserveTokens(ConversationContext ctx) {
    Object raw = ctx.attributes().get(ATTR_COMPLETION_RESERVE_TOKENS);
    if (raw instanceof Number n) {
      int v = n.intValue();
      if (v > 0) {
        return v;
      }
    }
    return DEFAULT_COMPLETION_RESERVE_TOKENS;
  }

  @Override
  public InjectorResult inject(ConversationContext ctx) {
    var engineContext = ctx.engineContext();
    Map<String, Object> body = ctx.requestBody();
    String question = asString(body.get("question"));
    // Tempdoc 603 C2 — prefer the decontextualized standalone question (QueryRewriteInjector runs before
    // us and stashes it). A follow-up only retrieves a coherent passage set once coreference/ellipsis is
    // resolved from the conversation history; absent a rewrite (first turn / AI down / timeout) we use the
    // raw question unchanged.
    Object standalone = ctx.attributes().get(QueryRewriteInjector.ATTR_STANDALONE_QUESTION);
    if (standalone instanceof String s && !s.isBlank()) {
      question = s;
    }
    List<String> docIds = extractDocIds(body);
    // Tempdoc 883 decision 3 — ONE budget per request, built before anything is asked for, so the
    // shape of the ask and the cut that follows it cannot disagree about how much room there is.
    ContextBudget budget = budgetFor(onlineAi, ctx);
    int topK = extractTopK(body, budget);

    if (question == null || question.isBlank()) {
      return InjectorResult.terminalError(errorEvent("No question provided", "NO_QUESTION"));
    }
    // Stash docIds + fileCount for the done enricher (set even on retrieval failure).
    ctx.attributes().put(ATTR_DOC_IDS, docIds);
    ctx.attributes().put(ATTR_FILE_COUNT, docIds.size());

    Set<String> docIdSet = new HashSet<>(docIds);

    // Tempdoc 610 §J.3 — the user's hidden retrieved sources (seeded by the engine from the store);
    // threaded to retrieval so the Worker drops these chunks before ranking.
    List<String> excludedSourceIds = excludedSourcesFrom(ctx);

    // Tempdoc 821 §3-C2 — the conversation's collection scope, carried verbatim to retrieval so an
    // ASK can be scoped the same way a search is. Absent (the FE does not send it yet) = an empty
    // list = the default scope, i.e. behavior unchanged.
    List<String> collection = extractStringList(body, "collection");

    // Try chunked RAG retrieval. When docIds is empty, use open-retrieval
    // (BM25 pre-search discovers relevant documents from the full index).
    RetrievalAttempt attempt;
    if (docIds.isEmpty()) {
      attempt = tryOpenRetrieval(budget, question, topK, excludedSourceIds, collection, engineContext);
    } else {
      attempt = tryRetrieveContext(question, docIdSet, topK, excludedSourceIds, collection, engineContext);
    }
    ContextResult retrieval = attempt.result();
    String context = retrieval == null ? null : retrieval.context();
    int chunksUsed = retrieval == null ? 0 : retrieval.chunksUsed();
    int chunksFound = retrieval == null ? 0 : retrieval.chunksFound();
    List<ContextCitation> citations = retrieval == null ? List.of() : retrieval.citations();
    String retrievalMode = retrieval == null ? "" : retrieval.retrievalMode();
    String retrievalModeReason = retrieval == null ? "" : retrieval.retrievalModeReason();
    boolean contextTruncated = retrieval != null && retrieval.contextTruncated();

    List<SseEvent> events = new ArrayList<>();
    // Tempdoc 845 — built here (so it reads the retrieval's own signals) but EMITTED after the
    // local truncation below, because `context_truncated` must account for that truncation too.
    // Emission order is unchanged: rag.meta still precedes rag.citations.
    Map<String, Object> ragMeta = null;
    if (retrieval != null) {
      ragMeta = new LinkedHashMap<>();
      ragMeta.put("retrieval_mode", retrievalMode);
      ragMeta.put("retrieval_mode_reason", retrievalModeReason);
      ragMeta.put("chunks_used", chunksUsed);
      ragMeta.put("chunks_found", chunksFound);
      DocumentService.QualitySignals qs = retrieval.quality();
      ragMeta.put("best_chunk_score", qs.bestChunkScore());
      ragMeta.put("score_gap", qs.scoreGap());
      ragMeta.put("retrieval_coverage", qs.retrievalCoverage());
      ragMeta.put("chunks_considered", qs.chunksConsidered());
      // Tempdoc 561 P-A/P-B: stash the producer's calibration so RAGDoneEnricher projects it onto the
      // done payload and the engine persists it WITH the assistant turn (evidence first-class on the
      // record). EPHEMERAL retrieval semantics are unchanged — this only exposes the signal downstream.
      ctx.attributes().put(ATTR_QUALITY, qs);
    }

    // Tempdoc 849 §5.2 (review D-1/D-7) — WHICH cut applies is decided by whether the branch below
    // REPLACED `context` with whole-document text, never by `sections.isEmpty()` or `chunksUsed`.
    // A predicate keyed on sections would mis-route RemoteDocumentService's FULLTEXT_FALLBACK,
    // which returns sections > 0 WITH chunksUsed == 0 and no citations: it satisfies the condition
    // below and has its context replaced, so its sections describe text that is no longer there.
    boolean wholeDocumentFallback = false;

    // Fallback to whole-document fetch when chunks are unavailable or empty.
    if (context == null || context.isBlank() || chunksUsed == 0) {
      if ("FALLBACK_FAILED".equals(retrievalMode)) {
        Map<String, Object> err = errorPayload("RAG context retrieval failed", "FETCH_FAILED");
        err.put("docIds", docIds);
        return InjectorResult.terminalError(new SseEvent("error", err));
      }
      if (docIds.isEmpty()) {
        // Tempdoc 806 B.2 (round-12): an unscoped ask whose retrieval never COMPLETED used to answer
        // "No matching documents found in the index" — a confident negative claim about the corpus
        // derived from a call that failed to run. Round 12 saw exactly this on a cold reranker while
        // the same question answered in ~9.5s on retry. An attempt that did not finish reports itself
        // as unfinished; only a retrieval that ran and found nothing may claim nothing is there.
        if (attempt.timedOut()) {
          return InjectorResult.terminalError(
              errorEvent(
                  "Still working on it - the search engine did not answer in time. This is not a"
                      + " result about your documents; ask again in a moment.",
                  "RETRIEVAL_TIMEOUT"));
        }
        if (attempt.failed()) {
          return InjectorResult.terminalError(
              errorEvent(
                  "Retrieval could not run, so nothing was searched. This is not a result about"
                      + " your documents.",
                  "RETRIEVAL_FAILED"));
        }
        Map<String, Object> err =
            errorPayload("No matching documents found in the index", "NO_CONTENT");
        return InjectorResult.terminalError(new SseEvent("error", err));
      }
      // Tempdoc 610 §J.3 — mirror the worker fallback: never re-inject (via whole-doc fetch) a parent
      // doc whose chunks the user hid. If every selected doc is hidden, this empties to NO_CONTENT.
      List<String> fallbackDocIds = dropExcludedParentDocs(docIds, excludedSourceIds);
      String fallback = fallbackDocIds.isEmpty() ? null : fetchBatchFallback(fallbackDocIds, engineContext);
      if (fallback == null || fallback.isBlank()) {
        Map<String, Object> err = errorPayload("No content in selected files", "NO_CONTENT");
        err.put("docIds", docIds);
        return InjectorResult.terminalError(new SseEvent("error", err));
      }
      context = fallback;
      wholeDocumentFallback = true;
      // chunksUsed already 0; chunksFound stays as-is; citations remain empty.
    }

    // Token-budget truncation safety net. Tempdoc 845 — budgeted against the LIVE window and this
    // request's real completion reserve, so it now actually fires when a turn would overflow
    // instead of waving through ~5990 tokens aimed at a 4096-token window.
    int budgetTokens = budget.inputBudget();
    List<ContextSection> sections = retrieval == null ? List.of() : retrieval.sections();
    SectionCut cut = cutContext(context, sections, budgetTokens, wholeDocumentFallback);

    // Tempdoc 845 — an honest budget makes this safety net reachable, so the truncation flag must
    // stop being Worker-only. It previously reported ONLY retrieval.contextTruncated(), leaving a
    // locally-trimmed turn indistinguishable from a complete one — a silent lie this change would
    // otherwise have made routine. Note the trimmed string still carries every citation
    // (see the class javadoc): this flag is what keeps that limit visible rather than hidden.
    if (ragMeta != null) {
      ragMeta.put("context_truncated", contextTruncated || cut.truncated());
      events.add(new SseEvent("rag.meta", ragMeta));
    }

    boolean usedRag = chunksUsed > 0;
    // Citations correspond to chunks; if we used full-doc fallback, no citations. Tempdoc 849:
    // each surviving citation is resolved to what the cut actually did with its passage.
    List<ContextCitation> kept = usedRag ? resolveInclusion(citations, cut.inclusions()) : List.of();
    int keptCount = kept.size();

    // Stash for downstream consumers (StreamingCitationMatcher, RAGDoneEnricher).
    ctx.attributes().put(ATTR_CITATIONS, kept);
    ctx.attributes().put(ATTR_CHUNKS_USED, usedRag ? keptCount : 0);
    ctx.attributes().put(ATTR_CHUNKS_FOUND, chunksFound);
    ctx.attributes().put(ATTR_USED_RAG, usedRag);

    // Slice 493: emit citations at retrieval time so the FE has them before any LLM tokens.
    if (!kept.isEmpty()) {
      List<Map<String, Object>> citationMaps = new ArrayList<>(kept.size());
      for (ContextCitation c : kept) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("parentDocId", c.parentDocId());
        m.put("chunkIndex", c.chunkIndex());
        m.put("chunkTotal", c.chunkTotal());
        m.put("startChar", c.startChar());
        m.put("endChar", c.endChar());
        m.put("score", c.score());
        m.put("excerpt", c.excerpt());
        m.put("startLine", c.startLine());
        m.put("endLine", c.endLine());
        m.put("headingText", c.headingText());
        m.put("headingLevel", c.headingLevel());
        // Tempdoc 849 §5.3a — sourced from the record component, so the live stream and the
        // persisted record (RAGDoneEnricher.toCitationMap) cannot disagree. Absent emits NO key:
        // a consumer that is told nothing must say nothing, never "included".
        ContextInclusion inclusion = c.inclusion();
        if (!inclusion.absent()) {
          m.put("contextInclusion", inclusion.wireName());
          m.put("contextIncludedChars", inclusion.includedChars());
        }
        citationMaps.add(Map.copyOf(m));
      }
      events.add(new SseEvent("rag.citations", Map.of("citations", citationMaps)));
    }

    Map<String, Object> message = new LinkedHashMap<>();
    message.put("role", "user");
    message.put(
        "content", "Documents:\n" + cut.context() + "\n\nQuestion: " + question);
    return InjectorResult.of(List.of(message), events);
  }

  /**
   * The result of fitting the retrieved context into the turn's input budget.
   *
   * @param context the text actually put in front of the model
   * @param truncated true when anything was left out
   * @param inclusions per-section inclusion states, index-aligned with the retrieval's sections;
   *     EMPTY when the cut could not produce a per-passage record (the structure-blind branches)
   */
  private record SectionCut(
      String context, boolean truncated, List<ContextInclusion> inclusions) {}

  /**
   * Tempdoc 849 §5.2 — the cut, branched on what it is cutting.
   *
   * <p>The whole-document fallback keeps the structure-blind {@link TokenEstimation#truncateIfNeeded}
   * because it has no sections; deleting that call would leave a whole-document context entirely
   * untruncated against a live 4096-token window, re-opening exactly the overcommit 845 closed.
   * The same branch is taken when a sectioned retrieval arrived WITHOUT sections: there is nothing
   * to re-assemble, and "no per-passage record" must degrade to an honest structure-blind trim, not
   * to no trim at all.
   *
   * <p><b>The assembled whole is re-checked against the budget</b> (review F6). The section loop
   * budgets the SUM of its parts, but {@link TokenEstimation#estimateTokens} is not additive: it
   * takes {@code max(wordEstimate, charEstimate)} and switches {@code charEstimate} on the
   * whitespace and non-ASCII RATIOS of the string it is handed, so a concatenation can cross a ratio
   * threshold none of its parts crossed and estimate well above their sum. The per-part arithmetic
   * is therefore necessary but not sufficient, and the guarantee this method owes its caller is
   * about the string it returns, not about the parts it assembled. When the assembly overshoots, it
   * falls back to the structure-blind trim and DROPS the per-section record — a record describing
   * sections is not true of a string that was then cut blind, and saying nothing beats saying
   * something false.
   *
   * <p>That fallback is then ENFORCED with {@link #fitToTokenBudget}, and the reason is measured,
   * not defensive: {@link TokenEstimation#truncateIfNeeded} sizes its head and tail windows in
   * WHITESPACE-DELIMITED WORDS, so on whitespace-poor text it has almost no words to drop and
   * returns the input essentially whole (plus its marker). Whitespace-poor text is precisely what
   * trips the ratio thresholds above, so the two conditions coincide: without this line the guard
   * would fire on exactly the inputs its remedy cannot fix. Measured on the regression fixture:
   * 549 tokens in, 571 out, against a 460-token cap.
   */
  private static SectionCut cutContext(
      String context, List<ContextSection> sections, int budgetTokens, boolean wholeDocumentFallback) {
    if (wholeDocumentFallback || sections.isEmpty()) {
      TruncationResult truncation = TokenEstimation.truncateIfNeeded(context, budgetTokens);
      return new SectionCut(truncation.content(), truncation.truncated(), List.of());
    }
    SectionCut sectioned = cutSections(sections, budgetTokens);
    int cap = TokenEstimation.effectiveContextCap(budgetTokens);
    if (TokenEstimation.estimateTokens(sectioned.context()) <= cap) {
      return sectioned;
    }
    LOG.warn(
        "RAGContext: section-aware assembly estimated over budget ({} > {}) though its parts fit;"
            + " falling back to the structure-blind trim and dropping the per-section record",
        TokenEstimation.estimateTokens(sectioned.context()),
        cap);
    TruncationResult truncation =
        TokenEstimation.truncateIfNeeded(sectioned.context(), budgetTokens);
    return new SectionCut(fitToTokenBudget(truncation.content(), cap), true, List.of());
  }

  /**
   * Re-assembles the retrieval's own sections against the token budget, recording per section what
   * the cut did with it.
   *
   * <p>Two arithmetic obligations, both inherited from {@code ContextBudgeter}'s contract that "the
   * budget counts ALL output characters, including section separators and headers": the overhead is
   * charged against the budget (budgeting only section CONTENT would overcommit by the header +
   * separator total), and the {@link TokenEstimation#effectiveContextCap} floor is carried, so a
   * zero budget means the same thing on both branches.
   *
   * <p>The kept set is always a PREFIX and each header keeps its ORIGINAL ordinal {@code i + 1}.
   * Both matter for the same reason: the prompt asks the model to cite {@code [n]} and the FE
   * resolves {@code sources[n - 1]} by POSITION, so a renumbered or non-prefix kept set would point
   * the reader at a different passage than the model read.
   */
  private static SectionCut cutSections(List<ContextSection> sections, int budgetTokens) {
    int cap = TokenEstimation.effectiveContextCap(budgetTokens);
    StringBuilder sb = new StringBuilder();
    List<ContextInclusion> inclusions = new ArrayList<>(sections.size());
    int usedTokens = 0;
    boolean truncated = false;

    for (int i = 0; i < sections.size(); i++) {
      ContextSection section = sections.get(i);
      String content = section.content();
      if (truncated || content.isEmpty()) {
        inclusions.add(ContextInclusion.dropped());
        continue;
      }
      String separator = sb.isEmpty() ? "" : ContextBudgeter.SECTION_SEPARATOR;
      String header = ContextBudgeter.sectionHeader(i + 1, section.sourceLabel());
      int overheadTokens = TokenEstimation.estimateTokens(separator + header);
      int remaining = cap - usedTokens;
      if (overheadTokens >= remaining) {
        truncated = true;
        inclusions.add(ContextInclusion.dropped());
        continue;
      }
      int contentBudget = remaining - overheadTokens;
      int contentTokens = TokenEstimation.estimateTokens(content);
      if (contentTokens <= contentBudget) {
        sb.append(separator).append(header).append(content);
        usedTokens += overheadTokens + contentTokens;
        // The Worker may already have cut this section's tail (ContextBudgeter.Section.truncated).
        // Passing it through whole does not make it whole.
        inclusions.add(
            section.truncated()
                ? ContextInclusion.partial(content.length())
                : ContextInclusion.included(content.length()));
        continue;
      }
      String fitted = fitToTokenBudget(content, contentBudget);
      truncated = true;
      if (fitted.isEmpty()) {
        inclusions.add(ContextInclusion.dropped());
        continue;
      }
      sb.append(separator).append(header).append(fitted);
      usedTokens += overheadTokens + TokenEstimation.estimateTokens(fitted);
      inclusions.add(ContextInclusion.partial(fitted.length()));
    }
    return new SectionCut(sb.toString(), truncated, List.copyOf(inclusions));
  }

  /**
   * The longest code-point-safe prefix of {@code content} that fits {@code budgetTokens}.
   *
   * <p>{@link TokenEstimation#estimateTokens} is not strictly monotone in prefix length (its dense
   * and CJK branches switch on whitespace/non-ASCII RATIOS), so the binary search's answer is
   * verified and shrunk rather than trusted — an over-budget prefix is the failure mode this whole
   * cut exists to prevent.
   */
  private static String fitToTokenBudget(String content, int budgetTokens) {
    if (budgetTokens <= 0) {
      return "";
    }
    int lo = 0;
    int hi = content.length();
    while (lo < hi) {
      int mid = lo + (hi - lo + 1) / 2;
      if (TokenEstimation.estimateTokens(Strings.codePointSafePrefix(content, mid)) <= budgetTokens) {
        lo = mid;
      } else {
        hi = mid - 1;
      }
    }
    String fitted = Strings.codePointSafePrefix(content, lo);
    while (!fitted.isEmpty() && TokenEstimation.estimateTokens(fitted) > budgetTokens) {
      fitted = Strings.codePointSafePrefix(fitted, Math.max(0, (fitted.length() * 9 / 10) - 1));
    }
    return fitted;
  }

  /**
   * Joins the per-section inclusion states onto the citations they describe (tempdoc 849 §5.3).
   *
   * <p>The join is positional, which is exactly the invariant {@code ContextBudgeter.sectionHeader}
   * documents: the Worker's budget loop appends to the used-hit list and to {@code sections()} in
   * ONE iteration. When the two lists nonetheless disagree in length the join is not trustworthy,
   * and the honest answer is to say nothing at all — every citation stays ABSENT rather than being
   * given a state derived from a suspect alignment.
   */
  private static List<ContextCitation> resolveInclusion(
      List<ContextCitation> citations, List<ContextInclusion> inclusions) {
    if (citations.isEmpty()) {
      return List.of();
    }
    if (inclusions.size() != citations.size()) {
      if (!inclusions.isEmpty()) {
        LOG.warn(
            "RAGContext: {} citations but {} context sections — inclusion left unresolved rather"
                + " than joined across a length mismatch",
            citations.size(),
            inclusions.size());
      }
      return citations;
    }
    List<ContextCitation> resolved = new ArrayList<>(citations.size());
    for (int i = 0; i < citations.size(); i++) {
      resolved.add(citations.get(i).withInclusion(inclusions.get(i)));
    }
    return List.copyOf(resolved);
  }

  /**
   * Tempdoc 806 B.2 — the outcome of ONE retrieval attempt, keeping "ran and returned nothing" apart
   * from "never completed". Collapsing the two into a bare {@code null} is what let an unfinished call
   * be reported to the user as a fact about their corpus.
   *
   * @param result the retrieval result, or {@code null} when the attempt did not complete
   * @param timedOut the attempt exceeded its budget (this instance's timeout, or the Worker RPC deadline)
   * @param failed the attempt did not complete for any reason, timeout included
   */
  private record RetrievalAttempt(ContextResult result, boolean timedOut, boolean failed) {
    static RetrievalAttempt ok(ContextResult result) {
      return new RetrievalAttempt(result, false, false);
    }

    static RetrievalAttempt from(Exception e) {
      return new RetrievalAttempt(null, isDeadline(e), true);
    }
  }

  /** True when a throwable (or any cause) is a local budget expiry or a gRPC DEADLINE_EXCEEDED. */
  private static boolean isDeadline(Throwable t) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (c instanceof java.util.concurrent.TimeoutException) {
        return true;
      }
      String message = c.getMessage();
      if (message != null && message.contains("DEADLINE_EXCEEDED")) {
        return true;
      }
    }
    return false;
  }

  private RetrievalAttempt tryRetrieveContext(
      String question, Set<String> docIdSet, int topK, List<String> excludedSourceIds,
      List<String> collection, EngineContext engineContext) {
    try {
      // Tempdoc 610 §J.3 — go through the rich params path so the hidden-source exclusion threads to
      // the Worker. maxContextTokens=0 preserves the scoped path's char-budget behavior.
      RetrieveContextParams params =
          RetrieveContextParams.of(question, topK, 0, docIdSet, excludedSourceIds, collection);
      return RetrievalAttempt.ok(
          documents
              .retrieveContext(params, engineContext)
              .toCompletableFuture()
              .get(timeout.toMillis(), TimeUnit.MILLISECONDS));
    } catch (Exception e) {
      LOG.warn("RAGContext: scoped retrieveContext failed; will fall back to batch fetch", e);
      return RetrievalAttempt.from(e);
    }
  }

  private RetrievalAttempt tryOpenRetrieval(
      ContextBudget budget,
      String question,
      int topK,
      List<String> excludedSourceIds,
      List<String> collection, EngineContext engineContext) {
    try {
      // Tempdoc 845 — the honest budget, not a hardcoded 8192/1024. This one crosses the wire as
      // the Worker's maxContextTokens, so it decides how many passages come back: an over-budget
      // ask now returns FEWER whole passages (each with its citation) instead of a full set the
      // window cannot hold. Floored at 1 because 0 would flip the Worker out of token-aware mode
      // into its 200K-character fallback (RagContextOps: `if (maxContextTokens > 0)`), which is the
      // opposite of what a zero budget means.
      int budgetTokens = Math.max(1, budget.inputBudget());
      RetrieveContextParams params =
          RetrieveContextParams.of(
              question, topK, budgetTokens, Set.of(), excludedSourceIds, collection);
      return RetrievalAttempt.ok(
          documents
              .retrieveContext(params, engineContext)
              .toCompletableFuture()
              .get(timeout.toMillis(), TimeUnit.MILLISECONDS));
    } catch (Exception e) {
      LOG.warn("RAGContext: open-retrieval failed (no docIds, pre-search path)", e);
      return RetrievalAttempt.from(e);
    }
  }

  /** Tempdoc 610 §J.3 — drop docIds whose parentDocId the user hid (parsed from the unit-sep ids). */
  private static List<String> dropExcludedParentDocs(
      List<String> docIds, List<String> excludedSourceIds) {
    if (excludedSourceIds.isEmpty() || docIds.isEmpty()) {
      return docIds;
    }
    Set<String> excludedParents = new HashSet<>();
    for (String id : excludedSourceIds) {
      int sep = id.lastIndexOf((char) 0x1F);
      excludedParents.add(sep > 0 ? id.substring(0, sep) : id);
    }
    List<String> kept = new ArrayList<>(docIds.size());
    for (String d : docIds) {
      if (!excludedParents.contains(d)) {
        kept.add(d);
      }
    }
    return kept;
  }

  /** Tempdoc 610 §J.3 — the hidden-source ids the engine seeded onto the context (never null). */
  private static List<String> excludedSourcesFrom(ConversationContext ctx) {
    Object raw = ctx.attributes().get(ATTR_EXCLUDED_SOURCES);
    if (raw instanceof List<?> l) {
      List<String> out = new ArrayList<>(l.size());
      for (Object o : l) {
        if (o instanceof String s && !s.isBlank()) {
          out.add(s);
        }
      }
      return out;
    }
    return List.of();
  }

  private String fetchBatchFallback(List<String> docIds, EngineContext engineContext) {
    try {
      Map<String, DocumentRecord> docs =
          documents
              .fetchBatch(docIds, engineContext)
              .toCompletableFuture()
              .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      return formatDocuments(docs, docIds);
    } catch (Exception e) {
      LOG.warn("RAGContext: fetchBatch fallback failed for {} docIds", docIds.size(), e);
      return null;
    }
  }

  private static String formatDocuments(Map<String, DocumentRecord> docs, List<String> docIds) {
    StringBuilder sb = new StringBuilder();
    for (String docId : docIds) {
      DocumentRecord record = docs.get(docId);
      if (record == null || record.content() == null || record.content().isBlank()) {
        continue;
      }
      sb.append("--- File: ")
          .append(DocumentTypeDetector.extractFilename(docId))
          .append(" ---\n");
      sb.append(record.content()).append("\n\n");
    }
    return sb.toString();
  }

  @SuppressWarnings("unchecked")
  private static List<String> extractDocIds(Map<String, Object> body) {
    return extractStringList(body, "docIds");
  }

  /** Reads a body key as a list of non-blank strings; absent or non-list yields an empty list. */
  private static List<String> extractStringList(Map<String, Object> body, String key) {
    Object raw = body == null ? null : body.get(key);
    if (!(raw instanceof List<?> list)) {
      return List.of();
    }
    return list.stream()
        .filter(Objects::nonNull)
        .map(Object::toString)
        .filter(s -> !s.isBlank())
        .collect(Collectors.toUnmodifiableList());
  }

  /**
   * How many passages to ask for.
   *
   * <p>An explicit body {@code topK} wins verbatim (the caller asked for a number; config and this
   * budget must not silently override it — see the {@link #defaultTopK} javadoc).
   *
   * <p>Tempdoc 883 decision 3 — the DEFAULT is now derived: how many passages of the corpus's own
   * chunk size the turn's input budget can actually hold, bounded above by the configured
   * {@code justsearch.rag.top_k}. That bound is what keeps a large window from asking for 50
   * passages nobody wants; the derivation is what stops a SMALL window asking for five it cannot
   * hold, which is the shape tempdoc 845's trimmer existed to clean up after.
   */
  private int extractTopK(Map<String, Object> body, ContextBudget budget) {
    Object raw = body == null ? null : body.get("topK");
    if (raw instanceof Number n) {
      int v = n.intValue();
      if (v > 0) {
        return v;
      }
    }
    int affordable = budget.inputBudget() / ChunkSplitter.DEFAULT_CHUNK_TOKENS;
    return Math.max(1, Math.min(defaultTopK, affordable));
  }

  private static String asString(Object o) {
    return o == null ? null : o.toString();
  }

  private static SseEvent errorEvent(String message, String code) {
    return new SseEvent("error", errorPayload(message, code));
  }

  private static Map<String, Object> errorPayload(String message, String code) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("error", message);
    p.put("errorCode", code);
    p.put("i18nKey", "errors." + code);
    return p;
  }
}
