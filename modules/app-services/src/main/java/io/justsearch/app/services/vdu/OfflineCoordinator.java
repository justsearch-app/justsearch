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
  private final Object procedureGate = new Object();
  private final AtomicBoolean processing = new AtomicBoolean();
  private final AtomicBoolean closing = new AtomicBoolean();
  private final java.util.concurrent.atomic.AtomicReference<CompletableFuture<CompletableFuture<Void>>>
      activeProcedure = new java.util.concurrent.atomic.AtomicReference<>();
  private final java.util.concurrent.atomic.AtomicReference<CompletableFuture<String>> activeExit =
      new java.util.concurrent.atomic.AtomicReference<>();

  private final AutomaticVduBreaker automaticBreaker;
  private final AutoCloseable breakerResets;

  public OfflineCoordinator(EngineExecutorRegistry executors, EngineAdmissionService admission,
      VduBatchProcessor vduBatchProcessor, Supplier<KnowledgeClient> knowledgeClientSupplier,
      VduCapabilityState vduCapabilityState) {
    this(executors, admission, vduBatchProcessor, knowledgeClientSupplier, vduCapabilityState,
        System::nanoTime);
  }

  /**
   * @param breakerClock monotonic nanosecond clock of the automatic-pass breaker's back-off
   */
  public OfflineCoordinator(EngineExecutorRegistry executors, EngineAdmissionService admission,
      VduBatchProcessor vduBatchProcessor, Supplier<KnowledgeClient> knowledgeClientSupplier,
      VduCapabilityState vduCapabilityState, java.util.function.LongSupplier breakerClock) {
    this.admission = Objects.requireNonNull(admission, "admission");
    this.vduBatchProcessor = Objects.requireNonNull(vduBatchProcessor, "vduBatchProcessor");
    this.knowledgeClientSupplier = Objects.requireNonNull(knowledgeClientSupplier, "knowledgeClientSupplier");
    this.vduCapabilityState = Objects.requireNonNull(vduCapabilityState, "vduCapabilityState");
    this.automaticBreaker = new AutomaticVduBreaker(vduCapabilityState,
        Objects.requireNonNull(breakerClock, "breakerClock"));
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
    // A committed settings apply or a person switching chat off or on closes the breaker at once.
    var holds = vduBatchProcessor.chatHolds();
    AutoCloseable resets = null;
    if (holds != null) {
      try {
        resets = holds.addAutomaticVduResetListener(automaticBreaker::close);
      } catch (RuntimeException | Error failure) {
        try { procedureOwner.close(); }
        catch (RuntimeException | Error cleanup) { if (failure != cleanup) failure.addSuppressed(cleanup); }
        throw failure;
      }
    }
    breakerResets = resets;
  }

  /** The returned stage finishes after mode cleanup, cancellation detachment and admission release. */
  public CompletionStage<OfflineProcessingOutcome> startOfflineProcessing(
      EngineContext context, Consumer<OfflineProcessingOutcome> progress) {
    return startProcessing(context, progress, null, false);
  }

  /**
   * A person's explicit pass. It is never gated by the automatic-pass breaker and, once accepted,
   * closes it with the count and back-off reset.
   */
  public CompletionStage<OfflineProcessingOutcome> startExplicitProcessing(
      EngineContext context, Consumer<OfflineProcessingOutcome> progress) {
    return startProcessing(context, progress, null, true);
  }

  /** An idle loaded-chat pass, bounded and owned exactly like an explicit pass. */
  public CompletionStage<OfflineProcessingOutcome> startAutomaticProcessing(
      EngineContext context, java.util.function.BooleanSupplier stillEligible) {
    return startProcessing(context, outcome -> {}, Objects.requireNonNull(stillEligible), false);
  }

  private CompletionStage<OfflineProcessingOutcome> startProcessing(
      EngineContext context, Consumer<OfflineProcessingOutcome> progress,
      java.util.function.BooleanSupplier automaticEligibility, boolean explicit) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(progress, "progress");
    // FutureTask's cancel result precedes actual body exit and discards later body failures.
    // Preserve the actual procedure outcome separately; the transport future only requests stop.
    var body = new CompletableFuture<OfflineProcessingOutcome>();
    var exited = new CompletableFuture<String>();
    var recoveryFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    var submitted = new CompletableFuture<CompletableFuture<Void>>();
    synchronized (procedureGate) {
      if (closing.get()) throw new CancellationException("Enrichment is stopping");
      if (!processing.compareAndSet(false, true)) {
        throw new IllegalStateException("Enrichment is already running");
      }
      // Publish ownership at the same boundary as the claim, before admission can block.
      // Terminal shutdown either fences this owner or excludes its start entirely.
      activeExit.set(exited);
      activeProcedure.set(submitted);
    }
    if (explicit) automaticBreaker.close("an explicit pass");
    EngineWorkHandle work;
    try { work = admission.attach(context); }
    catch (RuntimeException | Error failure) {
      submitted.completeExceptionally(failure);
      releaseProcedure(submitted, exited);
      exited.complete(null);
      throw failure;
    }
    EngineWorkHandle.Registration cancellation;
    try {
      cancellation = work.onCancel(reason -> submitted.thenAccept(task -> task.cancel(true))
          .exceptionally(failure -> { body.completeExceptionally(procedureFailure(failure)); return null; }));
    } catch (RuntimeException | Error failure) {
      try { work.close(); }
      catch (RuntimeException | Error cleanup) { if (failure != cleanup) failure.addSuppressed(cleanup); }
      finally {
        submitted.completeExceptionally(failure);
        releaseProcedure(submitted, exited);
        exited.complete(null);
      }
      throw failure;
    }
    try {
      CompletableFuture<Void> task = EngineFutures.supplyAsync(() -> {
        try {
          checkCancellation(work);
          if (closing.get()) throw new CancellationException("Enrichment is stopping");
          var outcome = runProcedure(work.context(), progress, automaticEligibility);
          checkCancellation(work);
          body.complete(outcome);
        } catch (RuntimeException | Error failure) {
          if (closing.get()) {
            // Retain this work's authority through cleanup. Cancelling its transport task stops
            // inference; cancelling the work handle would also forbid the pending-status reset.
            boolean interrupted = Thread.interrupted();
            try {
              KnowledgeClient client = knowledgeClientSupplier.get();
              if (client != null) client.recoverVduProcessingForOwner(work.context());
            } catch (RuntimeException | Error recovery) {
              recoveryFailure.set(recovery);
              if (failure != recovery) failure.addSuppressed(recovery);
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
          }
          body.completeExceptionally(procedureFailure(failure));
        }
        return null;
      }, procedureExecutor, () -> {
        finishOwnership(work, cancellation, body, submitted, exited, recoveryFailure.get());
      });
      submitted.complete(task);
      if (closing.get()) task.cancel(true);
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
      CompletableFuture<OfflineProcessingOutcome> body,
      CompletableFuture<CompletableFuture<Void>> submitted, CompletableFuture<String> exited,
      Throwable recoveryFailure) {
    Throwable failure = recoveryFailure;
    try { cancellation.close(); }
    catch (RuntimeException | Error cleanup) {
      if (failure == null) failure = cleanup;
      else if (cleanup != failure) failure.addSuppressed(cleanup);
    }
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
    releaseProcedure(submitted, exited);
    // This wins only for cancellation before supplier entry; a running body already recorded
    // its result or failure before EngineFutures invokes the actual-exit callback.
    body.completeExceptionally(new CancellationException("Enrichment cancelled before task entry"));
    // Success linearizes at this actual-exit snapshot after releasing ownership. Cancellation
    // already known here defeats a prior successful body; later requests cannot rewrite it.
    if (failure == null) exited.complete(cancellationReason);
    else exited.completeExceptionally(failure);
  }

  private void releaseProcedure(CompletableFuture<CompletableFuture<Void>> submitted,
      CompletableFuture<String> exited) {
    synchronized (procedureGate) {
      activeProcedure.compareAndSet(submitted, null);
      activeExit.compareAndSet(exited, null);
      processing.set(false);
    }
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
      EngineContext context, Consumer<OfflineProcessingOutcome> progress,
      java.util.function.BooleanSupplier automaticEligibility) {
    KnowledgeClient client = knowledgeClientSupplier.get();
    if (client == null) {
      return report(progress, new OfflineProcessingOutcome(
          0, 0, 0, BlockReason.WORKER_UNAVAILABLE, EmbeddingHandoff.NOT_EVALUATED));
    }
    client.recoverVduProcessing(context);
    OfflineProcessingOutcome outcome;
    // The batch holds the chat server in VDU mode for itself; a refused hold blocks it AI_OFFLINE.
    if (client.countPendingVdu(context) > 0) {
      outcome = automaticEligibility == null ? vduBatchProcessor.processPendingFiles(context, progress)
          : vduBatchProcessor.processPendingFiles(context, progress, automaticEligibility);
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
  /** Pauses automatic passes after repeated passes without progress (memory only). */
  public AutomaticVduBreaker automaticBreaker() { return automaticBreaker; }

  /** Fence new passes and let the chat owner become terminal before an active pass is cancelled. */
  public boolean prepareForShutdown(Runnable beforeCancellation) {
    Objects.requireNonNull(beforeCancellation, "beforeCancellation");
    synchronized (procedureGate) {
      boolean active = processing.get();
      if (active) beforeCancellation.run();
      closing.set(true);
      return active;
    }
  }

  /** The chat controller must be terminal first: cancelled cleanup must never relaunch chat. */
  public CompletionStage<Void> cancelForShutdown() {
    CompletableFuture<String> exited;
    CompletableFuture<CompletableFuture<Void>> submitted;
    synchronized (procedureGate) {
      closing.set(true);
      exited = activeExit.get();
      submitted = activeProcedure.get();
    }
    if (submitted != null) submitted.thenAccept(task -> task.cancel(true));
    return exited == null ? CompletableFuture.completedFuture(null)
        : exited.thenApply(ignored -> (Void) null).minimalCompletionStage();
  }

  @Override public void close() {
    if (breakerResets != null) {
      try { breakerResets.close(); }
      catch (Exception failure) {
        // Removing the listener only stops later resets; ownership below must still close.
        org.slf4j.LoggerFactory.getLogger(OfflineCoordinator.class)
            .warn("Breaker reset listener did not close", failure);
      }
    }
    procedureOwner.close();
    if (!procedureExecutor.isTerminated()) {
      throw new IllegalStateException("Enrichment procedure did not terminate; dependencies must remain open");
    }
  }
}
