/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation.spi;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.api.conversation.ContextInjector;
import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.InjectorResult;
import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.DocumentService.ContextCitation;
import io.justsearch.app.api.DocumentService.DocumentRecord;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.selection.DocumentAddress;
import io.justsearch.app.api.selection.SelectionPayload;
import io.justsearch.app.api.selection.SourceCitation;
import io.justsearch.core.util.ContextBudget;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Typed-selection {@link ContextInjector} per tempdoc 526 §4.4.
 *
 * <p>Handles every shipped {@link SelectionPayload} variant — one {@code case} per kind.
 * Adding a new kind is one switch arm here plus its FE-side discriminator entry.
 *
 * <p>Supported address coordinate systems via {@link #resolveAddress}:
 *
 * <ul>
 *   <li>{@link DocumentAddress.Canonical}: pass through.
 *   <li>{@link DocumentAddress.Display}: viewId-keyed translation. Every {@code preview-*}
 *       viewId is identity-mapped (the preview is a pure substring of canonical content);
 *       other view ids defer to the FE-supplied {@code canonicalHint} when present, else
 *       fail loudly with {@code UNRESOLVABLE_ADDRESS}.
 *   <li>{@link DocumentAddress.Lines}: walks newlines to compute canonical char range.
 * </ul>
 *
 * <p>Coexistence with {@link DocAccess}: when {@code body.selection} is present, {@link
 * DocAccess#inject} returns empty so the LLM doesn't receive the slice twice.
 */
public final class SelectionContextInjector implements ContextInjector {

  private static final Logger LOG = LoggerFactory.getLogger(SelectionContextInjector.class);

  /** Stable id used by {@code ConversationShape.contextInjectorIds}. */
  public static final String ID = "core.selection";

  /** Default fetch timeout (mirrors {@link DocAccess#DEFAULT_FETCH_TIMEOUT}). */
  static final Duration DEFAULT_FETCH_TIMEOUT = Duration.ofSeconds(10);

  /** Maximum docs fetched for a {@link SelectionPayload.ResultSet} injection. */
  static final int MAX_RESULT_SET_DOCS = 5;

  /**
   * Id of the summarize shape, as a literal so this SPI does not depend on the {@code shapes}
   * package (the dependency runs shapes → spi everywhere else). {@code SelectionContextInjectorTest}
   * asserts it still equals {@code SummarizeShape.ID}, so the literal cannot drift silently.
   */
  static final String SUMMARIZE_SHAPE_ID = "core.summarize";

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private final DocumentService documents;
  private final Duration fetchTimeout;

  /**
   * The live context window, read per request (tempdoc 883 decision 3). Null (a caller that wired
   * no inference handle) budgets against {@link ContextBudget#FALLBACK_WINDOW_TOKENS}.
   */
  private final Supplier<OnlineAiService> onlineAi;

  public SelectionContextInjector(DocumentService documents) {
    this(documents, DEFAULT_FETCH_TIMEOUT, null);
  }

  public SelectionContextInjector(DocumentService documents, Duration fetchTimeout) {
    this(documents, fetchTimeout, null);
  }

  /** Composition-root constructor: the default fetch timeout plus the live window. */
  public SelectionContextInjector(
      DocumentService documents, Supplier<OnlineAiService> onlineAi) {
    this(documents, DEFAULT_FETCH_TIMEOUT, onlineAi);
  }

  /**
   * Composition-root constructor (tempdoc 883 decision 3) — takes the same
   * {@code Supplier<OnlineAiService>} {@link RAGContext} gets, so a selected passage is cut against
   * the window the running server actually has instead of a flat 200,000-character soft cap that
   * exceeded every real window by an order of magnitude.
   */
  public SelectionContextInjector(
      DocumentService documents, Duration fetchTimeout, Supplier<OnlineAiService> onlineAi) {
    this.documents = Objects.requireNonNull(documents, "documents");
    this.fetchTimeout = Objects.requireNonNull(fetchTimeout, "fetchTimeout");
    this.onlineAi = onlineAi;
  }

  /**
   * The characters this turn's PRIMARY material may occupy.
   *
   * <p>A selection is not a fraction of the budget: it is what the user asked about, the same role
   * the retrieved context plays in a RAG ask. So it gets the whole input budget, converted to
   * characters. The old {@code MAX_CONTENT_CHARS} was 200,000 — roughly 50,000 tokens, i.e. more
   * than any window this app launches, so the "soft cap" never once bounded a prompt and the real
   * cut happened at the server with a 400.
   */
  private int selectionCapChars(ConversationContext ctx) {
    return RAGContext.budgetFor(onlineAi, ctx).inputBudgetChars();
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public InjectorResult inject(ConversationContext ctx) {
    Object raw = ctx.requestBody().get("selection");
    if (raw == null) {
      return InjectorResult.empty();
    }
    SelectionPayload payload;
    try {
      payload = MAPPER.convertValue(raw, SelectionPayload.class);
    } catch (RuntimeException e) {
      LOG.warn("SelectionContextInjector: failed to decode body.selection: {}", e.getMessage());
      return InjectorResult.terminalError(errorEvent("Invalid selection payload", "BAD_SELECTION"));
    }

    return switch (payload) {
      case SelectionPayload.TextRange tr -> injectTextRange(ctx, tr);
      case SelectionPayload.Item item -> injectItem(ctx, item);
      case SelectionPayload.Citation cit -> injectCitation(ctx, cit);
      case SelectionPayload.ResultSet rs -> injectResultSet(ctx, rs);
      case SelectionPayload.HealthCondition hc -> injectHealthCondition(hc);
      case SelectionPayload.SearchTrace st -> injectSearchTrace(st);
    };
  }

  /**
   * Tempdoc 549 Slice 4 (G33/G111 LLM narration). The user asked the LLM to explain a search
   * trace in words. Like {@link #injectHealthCondition}, this is a meta-message describing the
   * pipeline trace (no doc fetch); the rag-ask shape treats it as context-bearing input.
   */
  private InjectorResult injectSearchTrace(SelectionPayload.SearchTrace st) {
    StringBuilder sb = new StringBuilder();
    sb.append(
        "hit".equals(st.scope())
            ? "Explain, in plain language, why this specific search result ranked where it did, "
                + "based on the pipeline trace below."
            : "Explain, in plain language, what this search did and why — based on the pipeline "
                + "trace below.");
    if (st.summary() != null && !st.summary().isBlank()) {
      sb.append("\n\nSearch trace:\n").append(st.summary());
    }
    Map<String, Object> message = userMessage("", sb.toString());
    return InjectorResult.messagesOnly(List.of(message));
  }

  /**
   * Tempdoc 526 §17 T1C — health-condition consumer (F6 / F21). The injection
   * is a meta-message describing the condition; no doc fetch. The chat shape
   * (rag-ask) treats it as context-bearing prompt input.
   */
  private InjectorResult injectHealthCondition(SelectionPayload.HealthCondition hc) {
    StringBuilder sb = new StringBuilder();
    sb.append("Health condition ").append(hc.conditionId());
    if (hc.severity() != null && !hc.severity().isBlank()) {
      sb.append(" (severity=").append(hc.severity()).append(")");
    }
    if (hc.summary() != null && !hc.summary().isBlank()) {
      sb.append(":\n\n").append(hc.summary());
    }
    Map<String, Object> message = userMessage("", sb.toString());
    return InjectorResult.messagesOnly(List.of(message));
  }

  // -------------------------------------------------------------------------
  // Variant cases
  // -------------------------------------------------------------------------

  private InjectorResult injectTextRange(ConversationContext ctx, SelectionPayload.TextRange tr) {
    var engineContext = ctx.engineContext();
    ResolvedRange resolved;
    try {
      resolved = resolveAddress(tr.address());
    } catch (UnresolvableAddressException e) {
      LOG.info("SelectionContextInjector: {}", e.getMessage());
      return InjectorResult.terminalError(errorEvent(e.getMessage(), "UNRESOLVABLE_ADDRESS"));
    }

    String fullContent = fetchDocContent(resolved.docId(), engineContext);
    if (fullContent == null || fullContent.isBlank()) {
      return InjectorResult.terminalError(
          errorEvent("Document content unavailable", "DOC_UNAVAILABLE"));
    }

    if (resolved.needsLineResolution()) {
      resolved = resolveLinesToChars(fullContent, resolved.docId(), resolved.startLine(), resolved.endLine());
    }

    int startChar = clamp(resolved.startChar(), 0, fullContent.length());
    int endChar = clamp(resolved.endChar(), 0, fullContent.length());
    if (startChar >= endChar) {
      return InjectorResult.terminalError(
          errorEvent("Selection range is empty or out of bounds", "EMPTY_SELECTION"));
    }

    String slice = fullContent.substring(startChar, endChar);
    String truncated = truncateToBudget(ctx, slice, "selected text range", resolved.docId());
    Map<String, Object> message = userMessage(textRangePrefix(ctx.shapeId()), truncated);

    ContextCitation citation =
        new ContextCitation(
            resolved.docId(),
            0,
            1,
            startChar,
            endChar,
            1.0f,
            truncate(truncated, 200),
            0,
            0,
            "",
            0,
            DocumentService.ContextInclusion.ABSENT);
    stashCitation(ctx, citation, truncated);
    return InjectorResult.of(List.of(message), List.of(citationsEvent(citation, startChar, endChar)));
  }

  /**
   * Tempdoc 526 §16 F7: when an item has no fetchable content the injector emits a typed
   * {@code ITEM_UNAVAILABLE} terminal error instead of silently returning empty (which
   * stranded the user with no UX feedback).
   */
  private InjectorResult injectItem(ConversationContext ctx, SelectionPayload.Item item) {
    var engineContext = ctx.engineContext();
    String docId = item.itemId();
    String fullContent = fetchDocContent(docId, engineContext);
    if (fullContent == null || fullContent.isBlank()) {
      LOG.info(
          "SelectionContextInjector: item kind {} id {} has no fetchable content",
          item.itemKind(),
          docId);
      return InjectorResult.terminalError(
          errorEvent(
              "Item has no fetchable content: " + item.itemKind() + "/" + docId,
              "ITEM_UNAVAILABLE"));
    }
    String truncated = truncateToBudget(ctx, fullContent, "selected item", docId);
    Map<String, Object> message =
        userMessage("Use the following document for context:\n\n", truncated);
    ContextCitation citation =
        new ContextCitation(
            docId, 0, 1, 0, truncated.length(), 1.0f, truncate(truncated, 200), 0, 0, "", 0,
            DocumentService.ContextInclusion.ABSENT);
    stashCitation(ctx, citation, truncated);
    return InjectorResult.of(
        List.of(message), List.of(citationsEvent(citation, 0, truncated.length())));
  }

  /**
   * Tempdoc 526 §16 F3: SourceCitation is now a flat record (no sealed sum). The switch
   * collapsed into a direct substring + emit.
   */
  private InjectorResult injectCitation(ConversationContext ctx, SelectionPayload.Citation cit) {
    var engineContext = ctx.engineContext();
    SourceCitation sc = cit.citation();
    String fullContent = fetchDocContent(sc.parentDocId(), engineContext);
    if (fullContent == null || fullContent.isBlank()) {
      return injectInlineExcerpt(ctx, sc.parentDocId(), sc.excerpt());
    }
    int startChar = clamp(sc.startChar(), 0, fullContent.length());
    int endChar = clamp(sc.endChar(), 0, fullContent.length());
    if (startChar >= endChar) {
      return injectInlineExcerpt(ctx, sc.parentDocId(), sc.excerpt());
    }
    String slice = fullContent.substring(startChar, endChar);
    String truncated = truncateToBudget(ctx, slice, "cited passage", sc.parentDocId());
    Map<String, Object> message =
        userMessage("Use the following cited passage as context:\n\n", truncated);
    ContextCitation citation =
        new ContextCitation(
            sc.parentDocId(),
            0,
            1,
            startChar,
            endChar,
            1.0f,
            truncate(truncated, 200),
            0,
            0,
            "",
            0,
            DocumentService.ContextInclusion.ABSENT);
    stashCitation(ctx, citation, truncated);
    return InjectorResult.of(
        List.of(message), List.of(citationsEvent(citation, startChar, endChar)));
  }

  private InjectorResult injectInlineExcerpt(ConversationContext ctx, String docId, String excerpt) {
    String content = excerpt == null || excerpt.isBlank() ? "(citation excerpt unavailable)" : excerpt;
    Map<String, Object> message =
        userMessage("Use the following cited passage as context:\n\n", content);
    ContextCitation citation =
        new ContextCitation(
            docId, 0, 1, 0, content.length(), 1.0f, truncate(content, 200), 0, 0, "", 0,
            DocumentService.ContextInclusion.ABSENT);
    stashCitation(ctx, citation, content);
    return InjectorResult.of(
        List.of(message), List.of(citationsEvent(citation, 0, content.length())));
  }

  /**
   * Tempdoc 836 §A.5 — this arm emitted a {@code rag.citations} event from a hand-built map but
   * never stashed anything, so it set neither {@code ATTR_CITATIONS} nor {@code ATTR_USED_RAG} and
   * its citations were invisible to every downstream consumer. With the other three arms supplying
   * literal text it would have been the only starved arm of the same class, reachable from the
   * same menu — so it stashes here too, with the per-doc text it actually injected.
   */
  private InjectorResult injectResultSet(ConversationContext ctx, SelectionPayload.ResultSet rs) {
    var engineContext = ctx.engineContext();
    List<SelectionPayload.ResultRef> refs = rs.items();
    if (refs.isEmpty()) return InjectorResult.empty();
    StringBuilder concat = new StringBuilder();
    List<Map<String, Object>> citations = new ArrayList<>();
    List<DocumentService.VerificationSource> sources = new ArrayList<>();
    // Tempdoc 883 decision 3 — the turn's budget SPLIT across the documents it may take, rather
    // than a flat 10,000 chars each (50,000 for a full set, which no window this app launches can
    // hold). MAX_RESULT_SET_DOCS stays a document COUNT: it is not a window quantity.
    int perDocChars = Math.max(1, selectionCapChars(ctx) / MAX_RESULT_SET_DOCS);
    int taken = 0;
    for (SelectionPayload.ResultRef ref : refs) {
      if (taken >= MAX_RESULT_SET_DOCS) break;
      String content = fetchDocContent(ref.id(), engineContext);
      if (content == null || content.isBlank()) continue;
      String truncated = truncateToBudget(content, "result-set document", ref.id(), perDocChars);
      if (concat.length() > 0) concat.append(DocumentService.SECTION_SEPARATOR);
      concat.append("Document: ").append(ref.id()).append("\n\n").append(truncated);
      Map<String, Object> citation = new LinkedHashMap<>();
      citation.put("parentDocId", ref.id());
      citation.put("chunkIndex", 0);
      citation.put("startChar", 0);
      citation.put("endChar", truncated.length());
      citation.put("score", 1.0f);
      citation.put("excerpt", truncate(truncated, 200));
      citations.add(citation);
      sources.add(
          new DocumentService.VerificationSource(
              new ContextCitation(
                  ref.id(), 0, 1, 0, truncated.length(), 1.0f, truncate(truncated, 200), 0, 0, "",
                  0, DocumentService.ContextInclusion.ABSENT),
              truncated));
      taken++;
    }
    if (concat.length() == 0) {
      return InjectorResult.terminalError(
          errorEvent("None of the selected documents had fetchable content", "DOC_UNAVAILABLE"));
    }
    stashCitations(ctx, sources);
    String prefix =
        rs.query() != null && !rs.query().isBlank()
            ? "The user picked these documents from a search for '" + rs.query() + "':\n\n"
            : "Use the following documents as context:\n\n";
    Map<String, Object> message = userMessage(prefix, concat.toString());
    return InjectorResult.of(
        List.of(message), List.of(new SseEvent("rag.citations", Map.of("citations", citations))));
  }

  // -------------------------------------------------------------------------
  // Address resolution
  // -------------------------------------------------------------------------

  private ResolvedRange resolveAddress(DocumentAddress address) {
    return switch (address) {
      case DocumentAddress.Canonical c -> new ResolvedRange(c.docId(), c.startChar(), c.endChar(), -1, -1);
      case DocumentAddress.Display d -> {
        if (d.viewId().startsWith("preview-")) {
          yield new ResolvedRange(d.docId(), d.displayStart(), d.displayEnd(), -1, -1);
        }
        if (d.canonicalHint() != null) {
          yield new ResolvedRange(
              d.docId(), d.canonicalHint().startChar(), d.canonicalHint().endChar(), -1, -1);
        }
        throw new UnresolvableAddressException(
            "Unsupported viewId without canonicalHint: " + d.viewId());
      }
      case DocumentAddress.Lines l ->
          new ResolvedRange(l.docId(), -1, -1, l.startLine(), l.endLine());
    };
  }

  private ResolvedRange resolveLinesToChars(
      String content, String docId, int startLine, int endLine) {
    int line = 0;
    int startOffset = 0;
    int endOffset = content.length();
    boolean foundStart = startLine == 0;
    if (foundStart) startOffset = 0;
    for (int i = 0; i < content.length(); i++) {
      if (content.charAt(i) == '\n') {
        line++;
        if (!foundStart && line == startLine) {
          startOffset = i + 1;
          foundStart = true;
        }
        if (line == endLine + 1) {
          endOffset = i;
          break;
        }
      }
    }
    return new ResolvedRange(docId, startOffset, endOffset, -1, -1);
  }

  private record ResolvedRange(String docId, int startChar, int endChar, int startLine, int endLine) {
    boolean needsLineResolution() {
      return startLine >= 0;
    }
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  /**
   * The instruction that fronts an injected text-range.
   *
   * <p>A selection is MATERIAL, not a task, so the default frames it as context and leaves the task
   * to the requesting shape's own prompt contributors and user message. The summarize shape is the
   * exception: it declares no {@code core.user-prompt} injector, so this prefix is the only
   * instruction in its message list and must keep saying "summarize".
   */
  private static String textRangePrefix(String shapeId) {
    return SUMMARIZE_SHAPE_ID.equals(shapeId)
        ? "Summarize the following selection:\n\n"
        : "Use the following selected passage as context:\n\n";
  }

  private static Map<String, Object> userMessage(String prefix, String content) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("role", "user");
    m.put("content", prefix + content);
    return m;
  }

  private static String truncate(String s, int max) {
    return s == null ? "" : (s.length() > max ? s.substring(0, max) : s);
  }

  /** Cuts to the turn's whole input budget, saying so when the cut actually removed something. */
  private String truncateToBudget(ConversationContext ctx, String s, String what, String docId) {
    return truncateToBudget(s, what, docId, selectionCapChars(ctx));
  }

  /**
   * Cuts to a character budget and reports a real drop at INFO (tempdoc 883 decision 3).
   *
   * <p>The old cut was silent, and its cap was so far above any real window that the prompt was
   * cut at the SERVER instead — as a 400, with nothing in our logs saying which selection was too
   * big. A drop that changes what the model was shown is a fact about the answer, so it is stated.
   */
  private String truncateToBudget(String s, String what, String docId, int capChars) {
    String cut = truncate(s, capChars);
    if (s != null && cut.length() < s.length()) {
      LOG.info(
          "SelectionContextInjector: {} for {} cut to fit the context budget ({} -> {} chars,"
              + " ~{} -> ~{} tokens)",
          what,
          docId,
          s.length(),
          cut.length(),
          io.justsearch.core.util.TokenEstimation.estimateTokens(s),
          io.justsearch.core.util.TokenEstimation.estimateTokens(cut));
    }
    return cut;
  }

  private static int clamp(int v, int lo, int hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  /**
   * Stashes the sources for this turn, each paired with the literal text this injector put in the
   * user message (tempdoc 836 §1.1).
   *
   * <p>The literal text is what makes verification honest here. A selected passage has no chunk
   * ordinal — the {@code 0} these citations carry is a fabrication, and a matcher that re-fetched
   * "chunk 0" would score the answer against the document's opening instead of the selection
   * (measured live: tempdoc 836 §9.8.1). Supplying the text stops {@code chunkIndex=0} being
   * load-bearing rather than correcting it to another number a selection does not have.
   *
   * <p>{@link RAGContext#ATTR_CITATIONS} is derived from the same list, so the citation a consumer
   * reads and the text it is verified against cannot drift apart.
   */
  private static void stashCitations(
      ConversationContext ctx, List<DocumentService.VerificationSource> sources) {
    ctx.attributes().put(RAGContext.ATTR_VERIFICATION_SOURCES, List.copyOf(sources));
    ctx.attributes()
        .put(
            RAGContext.ATTR_CITATIONS,
            sources.stream().map(DocumentService.VerificationSource::citation).toList());
    ctx.attributes().put(RAGContext.ATTR_USED_RAG, true);
  }

  private static void stashCitation(
      ConversationContext ctx, ContextCitation citation, String literalText) {
    stashCitations(ctx, List.of(new DocumentService.VerificationSource(citation, literalText)));
  }

  private static SseEvent citationsEvent(ContextCitation citation, int startChar, int endChar) {
    Map<String, Object> citationMap = new LinkedHashMap<>();
    citationMap.put("parentDocId", citation.parentDocId());
    citationMap.put("chunkIndex", 0);
    citationMap.put("startChar", startChar);
    citationMap.put("endChar", endChar);
    citationMap.put("score", 1.0f);
    citationMap.put("excerpt", citation.excerpt());
    return new SseEvent("rag.citations", Map.of("citations", List.of(citationMap)));
  }

  private String fetchDocContent(String docId, EngineContext engineContext) {
    if (docId == null || docId.isBlank()) return null;
    try {
      DocumentRecord record =
          documents
              .fetch(docId, engineContext)
              .toCompletableFuture()
              .get(fetchTimeout.toMillis(), TimeUnit.MILLISECONDS);
      if (record != null && record.content() != null && !record.content().isBlank()) {
        return record.content();
      }
    } catch (java.util.concurrent.ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof UnsupportedOperationException) {
        LOG.info("SelectionContextInjector: document service unavailable for {}", docId);
      } else {
        LOG.warn("SelectionContextInjector: document fetch failed for {}", docId, cause);
      }
    } catch (java.util.concurrent.TimeoutException e) {
      LOG.warn(
          "SelectionContextInjector: document fetch timed out for {} after {}", docId, fetchTimeout);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (UnsupportedOperationException e) {
      LOG.info("SelectionContextInjector: document service unavailable for {}", docId);
    } catch (RuntimeException e) {
      LOG.warn("SelectionContextInjector: document fetch failed for {}", docId, e);
    }
    return null;
  }

  private static SseEvent errorEvent(String message, String code) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("error", message);
    payload.put("errorCode", code);
    return new SseEvent("error", payload);
  }

  private static final class UnresolvableAddressException extends RuntimeException {
    UnresolvableAddressException(String message) {
      super(message);
    }
  }
}
