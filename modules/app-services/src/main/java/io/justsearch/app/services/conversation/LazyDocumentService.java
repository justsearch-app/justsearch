/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import io.justsearch.core.context.EngineContext;

import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.RetrieveContextParams;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Late-resolving {@link DocumentService} proxy that delegates to a supplier on every call.
 *
 * <p>Solves the Worker late-binding problem: when the Head constructs SPI instances (RAGContext,
 * DocAccess, StreamingCitationMatcher) before the Worker is connected, a frozen
 * {@link DocumentService} reference stays unavailable even after the Worker reconnects.
 * This proxy resolves the current service on each call, so SPIs automatically start working
 * when the Worker late-binds.
 *
 * <p>Tempdoc 519 F2: when {@code delegate.get()} returns {@code null} (Worker not yet connected),
 * each method returns a failed {@link CompletionStage} with {@link UnavailableException} instead
 * of NPE. The Null Object {@code DocumentService.unavailable()} was previously the fallback;
 * F2 deleted it, so this proxy now owns the unavailable behavior for the Document SPI flow.
 */
public final class LazyDocumentService implements DocumentService {

  private final Supplier<DocumentService> delegate;
  private final Map<Object, DocumentService> operationDelegates = new HashMap<>();

  public LazyDocumentService(Supplier<DocumentService> delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate supplier");
  }

  /**
   * Binds one admitted operation to one immutable document facade.
   *
   * <p>The admitted work id survives context rebasing and nested engine calls. Contexts without a
   * work id retain identity semantics for direct callers and tests. The returned scope must cover
   * the entire operation and be closed by its owning boundary.
   */
  public Binding bind(EngineContext context, DocumentService documents) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(documents, "documents");
    Object key = key(context);
    synchronized (operationDelegates) {
      if (operationDelegates.putIfAbsent(key, documents) != null) {
        throw new IllegalStateException("DocumentService already bound for this operation");
      }
    }
    return new Binding(key, documents);
  }

  private static Object key(EngineContext context) {
    return context.workId().<Object>map(id -> id).orElseGet(() -> new ContextIdentity(context));
  }

  private record ContextIdentity(EngineContext context) {
    @Override public boolean equals(Object other) {
      return other instanceof ContextIdentity identity && identity.context == context;
    }

    @Override public int hashCode() { return System.identityHashCode(context); }
  }

  private <T> CompletionStage<T> resolve(
      EngineContext context, Function<DocumentService, CompletionStage<T>> op) {
    DocumentService d;
    synchronized (operationDelegates) {
      d = operationDelegates.get(key(context));
    }
    if (d == null) {
      d = delegate.get();
    }
    if (d == null) {
      return CompletableFuture.failedFuture(
          new UnavailableException("DocumentService unavailable (Worker not connected)"));
    }
    return op.apply(d);
  }

  @Override
  public CompletionStage<DocumentRecord> fetch(String docId, EngineContext engineContext) {
    return resolve(engineContext, d -> d.fetch(docId, engineContext));
  }

  @Override
  public CompletionStage<Map<String, DocumentRecord>> fetchBatch(List<String> docIds, EngineContext engineContext) {
    return resolve(engineContext, d -> d.fetchBatch(docIds, engineContext));
  }

  @Override
  public CompletionStage<DocumentSlice> fetchSlice(String docId, int offsetChars, int maxChars, EngineContext engineContext) {
    return resolve(engineContext, d -> d.fetchSlice(docId, offsetChars, maxChars, engineContext));
  }

  @Override
  public CompletionStage<DocumentIdPage> listAllDocumentIds(int offset, int limit, EngineContext engineContext) {
    return resolve(engineContext, d -> d.listAllDocumentIds(offset, limit, engineContext));
  }

  @Override
  public CompletionStage<ContextResult> retrieveContextWithMeta(
      String question, Set<String> docIds, int topK, EngineContext engineContext) {
    return resolve(engineContext, d -> d.retrieveContextWithMeta(question, docIds, topK, engineContext));
  }

  @Override
  public CompletionStage<ContextResult> retrieveContextWithMeta(
      String question, Set<String> docIds, int topK, int maxContextTokens, EngineContext engineContext) {
    return resolve(
        engineContext,
        d -> d.retrieveContextWithMeta(question, docIds, topK, maxContextTokens, engineContext));
  }

  @Override
  public CompletionStage<ContextResult> retrieveContext(RetrieveContextParams params, EngineContext engineContext) {
    return resolve(engineContext, d -> d.retrieveContext(params, engineContext));
  }

  @Override
  public CompletionStage<CitationMatchResult> matchCitations(
      String answerText, List<ContextCitation> citations, double threshold, EngineContext engineContext) {
    return resolve(
        engineContext, d -> d.matchCitations(answerText, citations, threshold, engineContext));
  }

  /**
   * Tempdoc 836 §1.1 — this proxy must forward the literal-text overload too. Without the
   * override the call would land on {@link io.justsearch.app.api.DocumentService}'s empty default
   * and return a zero result: a green build with a silently inert seam.
   */
  @Override
  public CompletionStage<CitationMatchResult> matchCitationsAgainst(
      String answerText, List<VerificationSource> sources, double threshold, EngineContext engineContext) {
    return resolve(
        engineContext,
        d -> d.matchCitationsAgainst(answerText, sources, threshold, engineContext));
  }

  public final class Binding implements AutoCloseable {
    private final Object key;
    private final DocumentService documents;
    private boolean closed;

    private Binding(Object key, DocumentService documents) {
      this.key = key;
      this.documents = documents;
    }

    @Override
    public void close() {
      synchronized (operationDelegates) {
        if (closed) {
          return;
        }
        closed = true;
        if (!operationDelegates.remove(key, documents)) {
          throw new IllegalStateException("DocumentService operation binding changed unexpectedly");
        }
      }
    }
  }
}
