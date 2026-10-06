/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import io.justsearch.app.api.EngineAdmissionService;
import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.app.api.OfflineProcessingOutcome;
import io.justsearch.app.api.OfflineProcessingOutcome.BlockReason;
import io.justsearch.app.api.OfflineProcessingOutcome.EmbeddingHandoff;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.core.execution.EngineFutures;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One bounded owner for manual and automatic enrichment through actual procedure cleanup.
 *
 * <p>It does not drive the chat server. The VDU pass takes a VDU hold on the chat controller
 * ({@link VduProcessor#enterVduMode}), which brings the server up in VDU mode and returns it to the
 * user's intent when the hold closes. Embedding backlog is left to the index half's own idle-paced
 * backfill: the hand-off that stopped chat for it is gone (Spec A O2, reconciliation v1).
 */
public final class OfflineCoordinator implements AutoCloseable {
  private static final EngineContext BACKLOG_CONTEXT =
      io.justsearch.app.services.intent.EngineProvenance.internal(
          "offline-enrichment-backlog", EngineContext.Survival.DURABLE,
          EngineContext.Urgency.BACKGROUND);

  private final VduBatchProcessor vduBatchProcessor;
  private final Supplier<KnowledgeClient> knowledgeClientSupplier;
  private final VduCapabilityState vduCapabilityState;
  private final EngineAdmissionService admission;
  private final EngineExecutorRegistry.Registration procedureOwner;
  private final ExecutorService procedureExecutor;
  private final AtomicBoolean processing = new AtomicBoolean();

  public OfflineCoordinator(EngineExecutorRegistry executors, EngineAdmissionService admission,
      VduBatchProcessor vduBatchProcessor, Supplier<KnowledgeClient> knowledgeClientSupplier,
      VduCapabilityState vduCapabilityState) {
    this.admission = Objects.requireNonNull(admission, "admission");
    this.vduBatchProcessor = Objects.requireNonNull(vduBatchProcessor, "vduBatchProcessor");
    this.knowledgeClientSupplier = Objects.requireNonNull(knowledgeClientSupplier, "knowledgeClientSupplier");
    this.vduCapabilityState = Objects.requireNonNull(vduCapabilityState, "vduCapabilityState");
    var limits = executors.limits(EngineExecutorSpec.Kind.BACKGROUND);
    procedureOwner = executors.register(new EngineExecutorSpec(
        "head.offline-procedure", EngineExecutorSpec.Kind.BACKGROUND,
        EngineExecutorSpec.Mode.PLATFORM, 1, limits.maxQueue(), 1));
    try {
      procedureExecutor = procedureOwner.open(
          Thread.ofPlatform().daemon().name("offline-procedure").factory());
    } catch (RuntimeException | Error failure) {
      try { procedureOwner.close(); }
      catch (RuntimeException | Error cleanup) { if (failure != cleanup) failure.addSuppressed(cleanup); }
      throw failure;
    }
  }

  /** The returned stage finishes after mode cleanup, cancellation detachment and admission release. */
  public CompletionStage<OfflineProcessingOutcome> startOfflineProcessing(
      EngineContext context, Consumer<OfflineProcessingOutcome> progress) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(progress, "progress");
    if (!processing.compareAndSet(false, true)) {
      throw new IllegalStateException("Enrichment is already running");
    }
    EngineWorkHandle work;
    try { work = admission.attach(context); }
    catch (RuntimeException | Error failure) { processing.set(false); throw failure; }

    // FutureTask's cancel result precedes actual body exit and discards later body failures.
    // Preserve the actual procedure outcome separately; the transport future only requests stop.
    var body = new CompletableFuture<OfflineProcessingOutcome>();
    var exited = new CompletableFuture<String>();
    var submitted = new CompletableFuture<CompletableFuture<Void>>();
    EngineWorkHandle.Registration cancellation;
    try {
      cancellation = work.onCancel(reason -> submitted.thenAccept(task -> task.cancel(true))
          .exceptionally(failure -> { body.completeExceptionally(procedureFailure(failure)); return null; }));
    } catch (RuntimeException | Error failure) {
      try { work.close(); }
      catch (RuntimeException | Error cleanup) { if (failure != cleanup) failure.addSuppressed(cleanup); }
      finally { processing.set(false); }
      throw failure;
    }
    try {
      CompletableFuture<Void> task = EngineFutures.supplyAsync(() -> {
        try {
          checkCancellation(work);
          var outcome = runProcedure(work.context(), progress);
          checkCancellation(work);
          body.complete(outcome);
        } catch (RuntimeException | Error failure) {
          body.completeExceptionally(procedureFailure(failure));
        }
        return null;
      }, procedureExecutor, () -> finishOwnership(work, cancellation, body, exited));
      submitted.complete(task);
    } catch (RuntimeException | Error refusal) {
      // EngineFutures owns the once-only exit callback even when submission is refused.
      submitted.completeExceptionally(refusal);
      try { exited.getNow(null); }
      catch (CompletionException cleanup) {
        if (cleanup.getCause() != refusal) refusal.addSuppressed(cleanup.getCause());
      }
      throw refusal;
    }
    return exited.handle((cancellationReason, cleanup) -> {
      // Actual exit always records the body (including pre-entry cancellation) before this stage.
      OfflineProcessingOutcome outcome = null;
      Throwable failure = null;
      try { outcome = body.join(); }
      catch (CompletionException failed) { failure = failed.getCause(); }
      catch (CancellationException cancelled) { failure = cancelled; }
      if (failure != null) {
        if (cleanup != null && cleanup != failure) failure.addSuppressed(cleanup);
        throw new CompletionException(failure);
      }
      if (cleanup != null) throw new CompletionException(cleanup);
      if (cancellationReason != null) throw new CancellationException(cancellationReason);
      return outcome;
    }).minimalCompletionStage();
  }

  private void finishOwnership(EngineWorkHandle work, EngineWorkHandle.Registration cancellation,
      CompletableFuture<OfflineProcessingOutcome> body, CompletableFuture<String> exited) {
    Throwable failure = null;
    try { cancellation.close(); }
    catch (RuntimeException | Error cleanup) { failure = cleanup; }
    try { work.close(); }
    catch (RuntimeException | Error cleanup) {
      if (failure == null) failure = cleanup;
      else if (cleanup != failure) failure.addSuppressed(cleanup);
    }
    String cancellationReason = null;
    try { cancellationReason = work.cancellationReason().orElse(null); }
    catch (RuntimeException | Error cleanup) {
      if (failure == null) failure = cleanup;
      else if (cleanup != failure) failure.addSuppressed(cleanup);
    }
    processing.set(false);
    // This wins only for cancellation before supplier entry; a running body already recorded
    // its result or failure before EngineFutures invokes the actual-exit callback.
    body.completeExceptionally(new CancellationException("Enrichment cancelled before task entry"));
    // Success linearizes at this actual-exit snapshot after releasing ownership. Cancellation
    // already known here defeats a prior successful body; later requests cannot rewrite it.
    if (failure == null) exited.complete(cancellationReason);
    else exited.completeExceptionally(failure);
  }

  private static Throwable procedureFailure(Throwable failure) {
    var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
    while (failure instanceof CompletionException && failure.getCause() != null && seen.add(failure)) {
      Throwable cause = failure.getCause();
      for (Throwable suppressed : failure.getSuppressed()) {
        if (suppressed != cause) cause.addSuppressed(suppressed);
      }
      failure = cause;
    }
    if (failure instanceof InterruptedException) {
      var cancelled = new CancellationException("Enrichment interrupted");
      cancelled.initCause(failure);
      return cancelled;
    }
    return failure;
  }

  private static void checkCancellation(EngineWorkHandle work) {
    var reason = work.cancellationReason();
    if (reason.isPresent()) throw new CancellationException(reason.orElseThrow());
    if (Thread.currentThread().isInterrupted()) throw new CancellationException("Enrichment interrupted");
  }

  private OfflineProcessingOutcome runProcedure(
      EngineContext context, Consumer<OfflineProcessingOutcome> progress) {
    KnowledgeClient client = knowledgeClientSupplier.get();
    if (client == null) {
      return report(progress, new OfflineProcessingOutcome(
          0, 0, 0, BlockReason.WORKER_UNAVAILABLE, EmbeddingHandoff.NOT_EVALUATED));
    }
    client.recoverVduProcessing(context);
    OfflineProcessingOutcome outcome;
    // The batch holds the chat server in VDU mode for itself; a refused hold blocks it AI_OFFLINE.
    if (client.countPendingVdu(context) > 0) {
      outcome = vduBatchProcessor.processPendingFiles(context, progress);
    } else {
      vduCapabilityState.clearAll();
      outcome = new OfflineProcessingOutcome(0, 0, 0, BlockReason.NONE, EmbeddingHandoff.NOT_EVALUATED);
    }
    // No chat stop for embeddings (O2): the index half's backfill embeds beside chat.
    return report(progress, outcome.withEmbeddingHandoff(EmbeddingHandoff.NOT_NEEDED));
  }

  private static OfflineProcessingOutcome report(
      Consumer<OfflineProcessingOutcome> progress, OfflineProcessingOutcome outcome) {
    progress.accept(outcome);
    return outcome;
  }

  public boolean hasPendingWork() {
    KnowledgeClient client = knowledgeClientSupplier.get();
    return client != null && (client.countPendingVdu(BACKLOG_CONTEXT) > 0
        || client.countPendingEmbeddings(BACKLOG_CONTEXT) > 0);
  }

  public int getPendingVduCount() {
    KnowledgeClient client = knowledgeClientSupplier.get();
    return client == null ? 0 : client.countPendingVdu(BACKLOG_CONTEXT);
  }

  public int getPendingEmbeddingCount() {
    KnowledgeClient client = knowledgeClientSupplier.get();
    return client == null ? 0 : client.countPendingEmbeddings(BACKLOG_CONTEXT);
  }

  public boolean isProcessing() { return processing.get(); }
  public VduCapabilityState vduCapabilityState() { return vduCapabilityState; }

  @Override public void close() {
    procedureOwner.close();
    if (!procedureExecutor.isTerminated()) {
      throw new IllegalStateException("Enrichment procedure did not terminate; dependencies must remain open");
    }
  }
}
