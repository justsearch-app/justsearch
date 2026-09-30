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
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ContextInjector} that resolves a {@code docId} (and/or inline {@code content})
 * from the request body, loads the document text via {@link DocumentService}, and returns
 * a single user message containing the resolved text plus the summarize instruction.
 *
 * <p>Per tempdoc 491 §5.1 + §9 Phase C: lifts the doc-loading logic that lived in
 * {@code SummaryController#resolveContent}. The legacy controller's
 * {@code ContentResolution} record + truncation-by-character-count are inlined here for
 * Phase C; token-budget truncation is handled separately by the engine via the LLM
 * service's context window limits (or, when needed, by a sibling {@code TokenBudget}
 * injector that hard-truncates before the LLM call).
 *
 * <p>Request body schema (read via {@link ConversationContext#requestBody}): {@code docId}
 * (string, optional) — canonical document id to fetch; {@code content} (string, optional)
 * — inline content to summarize (used when no docId is supplied or fetch returns empty).
 *
 * <p>Stateful per-instance (holds the {@link DocumentService} reference + timeout), not
 * stateless — the engine constructs one instance per process.
 */
public final class DocAccess implements ContextInjector {

  private static final Logger LOG = LoggerFactory.getLogger(DocAccess.class);

  /** Stable id used by {@code ConversationShape.contextInjectorIds}. */
  public static final String ID = "core.doc-access";

  /** Default fetch timeout. Matches the legacy {@code SummaryController.timeout}. */
  static final Duration DEFAULT_FETCH_TIMEOUT = Duration.ofSeconds(10);

  /** Soft character cap on injected content — mirrors the Worker's gRPC transport cap. */
  static final int MAX_CONTENT_CHARS = 200_000;

  /**
   * Display-preview length for the citation's {@code excerpt} (tempdoc 836 §1.4). The excerpt is a
   * PREVIEW, never the verification text — the literal text rides on the {@code VerificationSource}
   * instead, so nothing can end up verifying a document against 200 characters of it.
   */
  private static final int EXCERPT_CHARS = 200;

  private final DocumentService documents;
  private final Duration fetchTimeout;

  public DocAccess(DocumentService documents) {
    this(documents, DEFAULT_FETCH_TIMEOUT);
  }

  public DocAccess(DocumentService documents, Duration fetchTimeout) {
    this.documents = Objects.requireNonNull(documents, "documents");
    this.fetchTimeout = Objects.requireNonNull(fetchTimeout, "fetchTimeout");
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public InjectorResult inject(ConversationContext ctx) {
    var engineContext = ctx.engineContext();
    Map<String, Object> body = ctx.requestBody();
    // Per tempdoc 526 §13 §13.4 R8 — positional body.startChar/endChar
    // fallback is retired. Selection-range summarize is the typed body.selection
    // path exclusively (handled by SelectionContextInjector); DocAccess is now
    // strictly the full-doc loader for shapes that don't ride selection
    // substrate. When a typed selection is present, defer to it (selection
    // injector emits the full request).
    if (body.get("selection") != null) {
      return InjectorResult.empty();
    }
    String docId = asString(body.get("docId"));
    String providedContent = asString(body.get("content"));

    Resolved resolved = resolveContent(docId, providedContent, engineContext);
    String fullContent = resolved.content();
    if (fullContent == null || fullContent.isBlank()) {
      return InjectorResult.empty();
    }

    String truncated = fullContent.length() > MAX_CONTENT_CHARS
        ? fullContent.substring(0, MAX_CONTENT_CHARS)
        : fullContent;

    Map<String, Object> message = new LinkedHashMap<>();
    message.put("role", "user");
    message.put("content", "Summarize the following document:\n\n" + truncated);

    if (!resolved.fromDocument()) {
      // Inline `content` with no resolved document has no citable identity. Minting a citation for
      // it would name a source that does not exist, so this path keeps returning messages only.
      return InjectorResult.messagesOnly(List.of(message));
    }

    // Tempdoc 836 S2S3-A.4 — the doc path publishes what it injected, so the matcher is no longer
    // starved (it returned empty at StreamingCitationMatcher's source lookup because nothing was
    // ever stashed). The literal text supplied here is the SAME string the model was given, so
    // verification is about the text the answer was written from.
    //
    // Tempdoc 836 §8.4 — the citation carries CHUNK_INDEX_ABSENT, not 0. A whole document has no
    // chunk ordinal; `0` would claim it is the document's first chunk, which is precisely the
    // fabrication this design exists to remove. The Worker never looks it up (text is supplied),
    // and if it ever had to, an absent ordinal resolves to no text rather than to the wrong text.
    //
    // Honest limit (§3.3): up to MAX_CONTENT_CHARS of text is supplied, and whole-document literal
    // verification is two orders of magnitude outside the per-turn envelope. The per-source
    // coverage fields (S2S3-A.1) are what make that legible instead of silent — the response says
    // how many of this document's windows were actually examined.
    ContextCitation citation =
        new ContextCitation(
            resolved.docId(),
            ContextCitation.CHUNK_INDEX_ABSENT,
            1,
            0,
            truncated.length(),
            1.0f,
            truncated.length() > EXCERPT_CHARS ? truncated.substring(0, EXCERPT_CHARS) : truncated,
            0,
            0,
            "",
            0,
            DocumentService.ContextInclusion.ABSENT);
    List<DocumentService.VerificationSource> sources =
        List.of(new DocumentService.VerificationSource(citation, truncated));
    ctx.attributes().put(RAGContext.ATTR_VERIFICATION_SOURCES, sources);
    ctx.attributes().put(RAGContext.ATTR_CITATIONS, List.of(citation));
    ctx.attributes().put(RAGContext.ATTR_USED_RAG, true);

    return InjectorResult.of(List.of(message), List.of(citationsEvent(citation)));
  }

  /** The retrieval-shaped announcement of the one source this injector resolved. */
  private static SseEvent citationsEvent(ContextCitation citation) {
    Map<String, Object> citationMap = new LinkedHashMap<>();
    citationMap.put("parentDocId", citation.parentDocId());
    citationMap.put("chunkIndex", citation.chunkIndex());
    citationMap.put("chunkTotal", citation.chunkTotal());
    citationMap.put("startChar", citation.startChar());
    citationMap.put("endChar", citation.endChar());
    citationMap.put("score", citation.score());
    citationMap.put("excerpt", citation.excerpt());
    return new SseEvent("rag.citations", Map.of("citations", List.of(citationMap)));
  }

  /**
   * The resolved content plus WHERE it came from. The distinction is load-bearing: only text the
   * document service actually returned may be attributed to {@code docId} as a citation — inline
   * body content has no document identity to name.
   */
  private record Resolved(String content, String docId, boolean fromDocument) {}

  /**
   * Resolve the document content. Prefers the documents service when a {@code docId} is
   * supplied; falls back to {@code providedContent} when the fetch returns nothing or the
   * service is unavailable.
   */
  private Resolved resolveContent(String docId, String providedContent, EngineContext engineContext) {
    String fallback = providedContent == null ? "" : providedContent;
    if (docId == null || docId.isBlank()) {
      return new Resolved(fallback, "", false);
    }
    try {
      DocumentRecord record =
          documents.fetch(docId, engineContext).toCompletableFuture().get(fetchTimeout.toMillis(), TimeUnit.MILLISECONDS);
      if (record != null && record.content() != null && !record.content().isBlank()) {
        return new Resolved(record.content(), docId, true);
      }
    } catch (java.util.concurrent.ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof UnsupportedOperationException) {
        LOG.info("DocAccess: document service unavailable; falling back to provided content");
      } else {
        LOG.warn("DocAccess: document fetch failed for {}", docId, cause);
      }
    } catch (java.util.concurrent.TimeoutException e) {
      LOG.warn("DocAccess: document fetch timed out for {} after {}", docId, fetchTimeout);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.warn("DocAccess: document fetch interrupted for {}", docId);
    } catch (UnsupportedOperationException e) {
      LOG.info("DocAccess: document service unavailable; falling back to provided content");
    } catch (RuntimeException e) {
      LOG.warn("DocAccess: document fetch failed for {}", docId, e);
    }
    // The fetch did not produce the document's text, so whatever the caller passed inline is NOT
    // this document — it carries no citable identity and must not be attributed to `docId`.
    return new Resolved(fallback, "", false);
  }

  private static String asString(Object o) {
    if (o == null) return null;
    return o.toString();
  }
}
