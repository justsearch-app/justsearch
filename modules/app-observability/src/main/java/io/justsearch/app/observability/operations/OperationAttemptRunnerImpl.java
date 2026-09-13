/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.app.api.operations.OperationReceipt;
import io.justsearch.app.api.operations.OperationRecord;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.api.operations.OperationStoreException;
import io.justsearch.core.context.EngineContext;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** One terminal writer. Live capabilities/futures are projections, never a second durable ledger. */
public final class OperationAttemptRunnerImpl implements OperationAttemptRunner {
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(OperationAttemptRunnerImpl.class);
  private final OperationStore store;
  private final Clock clock;
  private final Set<OperationKind> ownedKinds;
  private final List<OperationRecord> interrupted;
  private final Map<Long, Control> active = new ConcurrentHashMap<>();
  private final CompletableFuture<PersistenceFailure> persistenceFailure = new CompletableFuture<>();

  @Override
  public CompletionStage<PersistenceFailure> persistenceFailure() {
    return persistenceFailure.minimalCompletionStage();
  }

  /** Construct synchronously before the index fork; kind ownership is declared before the sweep. */
  public OperationAttemptRunnerImpl(OperationStore store, Clock clock, Set<OperationKind> ownedKinds) {
    this.store = Objects.requireNonNull(store, "store");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ownedKinds = Set.copyOf(ownedKinds);
    interrupted = store.openRecords();
    for (OperationRecord row : interrupted) {
      if (row.context().survival() == EngineContext.Survival.INTERACTIVE
          && !this.ownedKinds.contains(row.descriptor().kind())) {
        finishObserved(new Control(row), OperationState.FAILED, new OperationReceipt("interrupted_by_restart", null));
      }
    }
  }

  @Override
  public PreparedAttempt accept(Request request) {
    String key = request.key() == null ? OperationKeys.generate(clock) : request.key();
    var accepted = store.accept(key, request.descriptor(), request.context(), request.provenance());
    return new Prepared(controlFor(accepted.record()), accepted.record(), !accepted.created());
  }

  private Control controlFor(OperationRecord row) {
    if (row.state().terminal()) {
      Control completed = new Control(row);
      completed.done.complete(row);
      return completed;
    }
    Control control = active.computeIfAbsent(row.id(), ignored -> new Control(row));
    // Acceptance and attaching to its live future are separate calls. Completion can win that
    // interval; a fresh read prevents constructing a never-completing future for a terminal row.
    publishIfTerminal(control, current(control));
    return control;
  }

  @Override
  public Result start(PreparedAttempt attempt, Function<OperationRecordHandle, OperationExecution> body) {
    Prepared prepared = requirePrepared(attempt);
    if (prepared.existing || !prepared.control.started.compareAndSet(false, true)) {
      return existingResult(prepared.control);
    }
    return execute(prepared.control, body, false);
  }

  private Result execute(Control control, Function<OperationRecordHandle, OperationExecution> body, boolean resume) {
    try {
      if (!(resume ? store.resume(control.id) : store.start(control.id))) {
        OperationRecord row = current(control);
        if (!row.state().terminal()) {
          throw new OperationStoreException(OperationStoreException.Code.STORAGE_FAILED, null);
        }
        publishIfTerminal(control, row);
        return new Result(row, receiptResponse(row), control.done.minimalCompletionStage());
      }
    } catch (RuntimeException failure) {
      persistenceFailed(control, OperationState.RUNNING, failure);
      throw failure;
    }
    OperationExecution execution;
    try {
      execution = Objects.requireNonNull(body.apply(control), "Operation execution");
    } catch (RuntimeException failure) {
      try { finish(control, failure instanceof CancellationException ? OperationState.CANCELLED : OperationState.FAILED,
          new OperationReceipt(failureCode(failure), null)); }
      catch (RuntimeException storageFailure) {
        if (failure != storageFailure) failure.addSuppressed(storageFailure);
        persistenceFailed(control, failure instanceof CancellationException ? OperationState.CANCELLED : OperationState.FAILED,
            storageFailure);
      }
      throw failure;
    }
    execution.completion().whenComplete((outcome, failure) -> {
      Throwable cause = failure instanceof CompletionException ? failure.getCause() : failure;
      if (cause instanceof Error) {
        // Fatal errors are not a failure receipt. The durable RUNNING row is reconciled at boot.
        failObservation(control, cause);
        return;
      }
      OperationState intendedState = cause instanceof CancellationException ? OperationState.CANCELLED
          : cause != null || outcome == null || !outcome.success() ? OperationState.FAILED : OperationState.COMPLETE;
      try {
        if (cause != null) {
          finish(control, cause instanceof CancellationException ? OperationState.CANCELLED : OperationState.FAILED,
              new OperationReceipt(failureCode(cause), null));
        } else if (outcome == null) {
          finish(control, OperationState.FAILED, new OperationReceipt("MISSING_OUTCOME", null));
        } else {
          finish(control, outcome.success() ? OperationState.COMPLETE : OperationState.FAILED, receipt(outcome));
        }
      } catch (RuntimeException storageFailure) {
        if (cause != null && cause != storageFailure) storageFailure.addSuppressed(cause);
        persistenceFailed(control, intendedState, storageFailure);
      }
    });
    // Synchronous adapters finish before start returns. Do not return their successful effect
    // response if terminal persistence already failed; an unfinished async attempt still returns
    // its immediate response and exposes any later failure through the completion observation.
    if (control.done.isCompletedExceptionally()) {
      try { control.done.join(); }
      catch (CompletionException failure) {
        if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
        if (failure.getCause() instanceof Error fatal) throw fatal;
        throw failure;
      }
    }
    return new Result(current(control), execution.response(), control.done.minimalCompletionStage());
  }

  @Override
  public void rejectBeforeStart(PreparedAttempt attempt, String reason) {
    Prepared prepared = requirePrepared(attempt);
    if (prepared.existing) return;
    if (prepared.control.done.isDone()) {
      prepared.control.done.join();
      return;
    }
    try {
      OperationRecord row = store.rejectBeforeStart(prepared.control.id, new OperationReceipt(reason, null));
      if (row.state() == OperationState.ACCEPTED) {
        throw new OperationStoreException(OperationStoreException.Code.STORAGE_FAILED, null);
      }
      publishIfTerminal(prepared.control, row);
    } catch (RuntimeException failure) {
      persistenceFailed(prepared.control, OperationState.FAILED, failure);
      throw failure;
    }
  }

  @Override
  public void reconcile(OperationKind kind, Function<OperationRecord, Reconciliation> reconciler) {
    if (!ownedKinds.contains(kind)) throw new IllegalArgumentException("Kind has no declared owner");
    for (OperationRecord previous : interrupted) {
      if (previous.descriptor().kind() != kind) continue;
      var retained = store.find(previous.key());
      if (retained.isEmpty()) continue; // Only terminal rows can have been evicted since the boot snapshot.
      OperationRecord row = retained.get();
      if (row.state().terminal()) continue;
      Control control = controlFor(row);
      if (control.started.get()) continue;
      Reconciliation decision = Objects.requireNonNull(reconciler.apply(row), "Reconciliation verdict");
      if (decision instanceof Reconciliation.Wait || !control.started.compareAndSet(false, true)) continue;
      switch (decision) {
        case Reconciliation.Complete complete -> finishObserved(control, OperationState.COMPLETE, complete.receipt());
        case Reconciliation.Failed failed -> finishObserved(control, OperationState.FAILED, failed.receipt());
        case Reconciliation.Cancelled cancelled -> finishObserved(control, OperationState.CANCELLED, cancelled.receipt());
        case Reconciliation.Resume resume -> execute(control, resume.body(), true);
        case Reconciliation.Wait ignored -> throw new IllegalStateException("Wait was already handled");
      }
    }
  }

  private void finish(Control control, OperationState state, OperationReceipt receipt) {
    OperationRecord row = store.finish(control.id, state, receipt).orElseThrow(
        () -> new OperationStoreException(OperationStoreException.Code.STORAGE_FAILED, null));
    publishIfTerminal(control, row);
  }

  private void finishObserved(Control control, OperationState state, OperationReceipt receipt) {
    try { finish(control, state, receipt); }
    catch (RuntimeException failure) {
      persistenceFailed(control, state, failure);
      throw failure;
    }
  }

  private void publishIfTerminal(Control control, OperationRecord row) {
    if (!row.state().terminal()) return;
    control.done.complete(row);
    active.remove(row.id(), control);
  }

  private void failObservation(Control control, Throwable failure) {
    control.done.completeExceptionally(failure);
    active.remove(control.id, control);
  }

  private void persistenceFailed(Control control, OperationState intendedState, Throwable failure) {
    LOG.error("Operation durable transition failed: key={} intendedState={}; outcome remains unresolved",
        control.key, intendedState, failure);
    persistenceFailure.complete(new PersistenceFailure(control.key, intendedState));
    failObservation(control, failure);
  }

  private OperationRecord current(Control control) {
    OperationRecord completed = control.done.getNow(null);
    return completed == null ? store.find(control.key).orElseThrow() : completed;
  }

  private Result existingResult(Control control) {
    OperationRecord row = current(control);
    publishIfTerminal(control, row);
    return new Result(row, receiptResponse(row), control.done.minimalCompletionStage());
  }

  private static OperationReceipt receipt(OperationResult result) {
    String code = result.success() ? "SUCCESS" : result.errorCode()
        .filter(value -> value.matches("[A-Za-z][A-Za-z0-9_]{0,95}")).orElse("HANDLER_FAILED");
    String execution = result.executionId().filter(value -> value.matches("[A-Za-z0-9_.:/-]{1,128}")).orElse(null);
    return new OperationReceipt(code, execution);
  }

  private static String failureCode(Throwable failure) {
    if (failure instanceof CancellationException) return "cancelled";
    if (failure instanceof OperationStoreException storeFailure) {
      return storeFailure.code().name();
    }
    return "UNCAUGHT_EXCEPTION";
  }

  private static OperationResult receiptResponse(OperationRecord row) {
    boolean failed = row.state() == OperationState.FAILED || row.state() == OperationState.CANCELLED;
    return new OperationResult(!failed, "Operation " + row.state().name(),
        Optional.ofNullable(row.receipt()).map(OperationReceipt::executionId),
        Map.of("operationKey", row.key(), "operationRecordId", row.id(), "state", row.state().name(),
            "unitsCompleted", row.unitsCompleted(), "unitsFailed", row.unitsFailed()),
        failed ? Optional.ofNullable(row.failureReason()) : Optional.empty(), Map.of(), Optional.empty());
  }

  private Prepared requirePrepared(PreparedAttempt attempt) {
    if (!(attempt instanceof OperationAttemptRunnerImpl.Prepared prepared) || prepared.owner != this) {
      throw new IllegalArgumentException("Attempt was not issued by this runner");
    }
    return prepared;
  }

  private final class Prepared implements PreparedAttempt {
    private final OperationAttemptRunnerImpl owner = OperationAttemptRunnerImpl.this;
    private final Control control;
    private final OperationRecord accepted;
    private final boolean existing;
    private Prepared(Control control, OperationRecord accepted, boolean existing) {
      this.control = control; this.accepted = accepted; this.existing = existing;
    }
    @Override public OperationRecord accepted() { return accepted; }
    @Override public boolean existing() { return existing; }
    @Override public CompletionStage<OperationRecord> completion() { return control.done.minimalCompletionStage(); }
  }

  private final class Control implements OperationRecordHandle {
    private final long id;
    private final String key;
    private final AtomicBoolean started = new AtomicBoolean();
    private final CompletableFuture<OperationRecord> done = new CompletableFuture<>();
    private Control(OperationRecord row) { id = row.id(); key = row.key(); }
    @Override public long id() { return id; }
    @Override public String key() { return key; }
    @Override public void checkpoint(String cursor, long completed, long failed) {
      if (!store.checkpoint(id, cursor, completed, failed)) {
        throw new IllegalStateException("Checkpoint refused for a terminal, unstarted or newer operation");
      }
    }
  }
}
