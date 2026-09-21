/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.settings.SettingsCommitOwner;
import io.justsearch.app.api.settings.SettingsWitness;
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
  private final java.util.concurrent.locks.ReentrantLock[] preparationLocks =
      java.util.stream.IntStream.range(0, 256)
          .mapToObj(ignored -> new java.util.concurrent.locks.ReentrantLock())
          .toArray(java.util.concurrent.locks.ReentrantLock[]::new);
  private final OperationStore store;
  private final Clock clock;
  private final SettingsCommitOwner settingsOwner;
  private final IngestPlanResolver ingestPlanResolver;
  private final java.util.function.Consumer<FaultBoundary> faultHook;
  /** Immutable observation for an explicitly composed installed-test barrier; never a writer. */
  public record FaultBoundary(String phase, OperationKind parentKind, String parentKey,
      String operationKey, long operationRecordId, String cursor, long completed, long failed) {}
  public static final java.util.function.Consumer<FaultBoundary> NO_FAULT_HOOK = ignored -> {};
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
    this(store, clock, ownedKinds, null);
  }

  /** The fixed settings owner is composed before this runner and inspects all boot rows first. */
  public OperationAttemptRunnerImpl(OperationStore store, Clock clock, Set<OperationKind> ownedKinds,
      SettingsCommitOwner settingsOwner) {
    this(store, clock, ownedKinds, settingsOwner, null);
  }

  /** Ingestion preparation decoding is fixed by the process root before any parent can execute. */
  public OperationAttemptRunnerImpl(OperationStore store, Clock clock, Set<OperationKind> ownedKinds,
      SettingsCommitOwner settingsOwner, IngestPlanResolver ingestPlanResolver) {
    this(store, clock, ownedKinds, settingsOwner, ingestPlanResolver, NO_FAULT_HOOK);
  }

  /** Only the process composition root selects an installed-test barrier. */
  public OperationAttemptRunnerImpl(OperationStore store, Clock clock, Set<OperationKind> ownedKinds,
      SettingsCommitOwner settingsOwner, IngestPlanResolver ingestPlanResolver,
      java.util.function.Consumer<FaultBoundary> faultHook) {
    this.faultHook = Objects.requireNonNull(faultHook, "faultHook");
    this.ingestPlanResolver = ingestPlanResolver;
    if (ingestPlanResolver != null && !ownedKinds.contains(OperationKind.INGEST)) {
      throw new IllegalArgumentException("Ingest resolver requires ingestion ownership");
    }
    this.store = Objects.requireNonNull(store, "store");
    this.settingsOwner = settingsOwner;
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ownedKinds = Set.copyOf(ownedKinds);
    interrupted = store.openRecords();
    if (settingsOwner != null) {
      if (!this.ownedKinds.containsAll(Set.of(OperationKind.SETTINGS_APPLY, OperationKind.RECONFIGURE))) {
        throw new IllegalArgumentException("Settings owner requires both settings kinds");
      }
      var settingsRows = interrupted.stream().filter(row -> settingsKind(row.descriptor().kind())).toList();
      // More than one armed row is already unresolved; do not load up to the entire row cap's
      // private payloads merely to reach that verdict. Only the sole armed row can need decoding.
      boolean soleArmed = settingsRows.stream().filter(row -> row.expectedSettingsRevision() != null).limit(2).count() == 1;
      settingsOwner.inspectRecovery(settingsRows.stream().map(row -> settingsRecoveryInput(row, soleArmed)).toList());
      reconcileOwned(OperationKind.SETTINGS_APPLY, settingsOwner::reconcile);
      reconcileOwned(OperationKind.RECONFIGURE, settingsOwner::reconcile);
    }
    for (OperationRecord row : interrupted) {
      if (row.context().survival() == EngineContext.Survival.INTERACTIVE
          && !this.ownedKinds.contains(row.descriptor().kind())) {
        finishObserved(new Control(row), OperationState.FAILED, new OperationReceipt("interrupted_by_restart", null));
      }
    }
  }

  private SettingsCommitOwner.RecoveryInput settingsRecoveryInput(OperationRecord row, boolean soleArmed) {
    if (!soleArmed || row.expectedSettingsRevision() == null) {
      return new SettingsCommitOwner.RecoveryInput(row, Optional.empty());
    }
    try {
      return new SettingsCommitOwner.RecoveryInput(row, store.acceptedPreparation(row.id()));
    } catch (IllegalArgumentException malformed) {
      // Invalid stored nonce/payload must not prevent an exact live file witness from resolving
      // commitment. Missing preparation never grants absent-history reset/precommit authority.
      LOG.warn("Settings operation {} has malformed accepted preparation", row.id());
      return new SettingsCommitOwner.RecoveryInput(row, Optional.empty());
    }
  }

  @Override
  public <T> T withPreparation(Request request, Function<PreparationScope, T> prepare) {
    Objects.requireNonNull(prepare, "prepare");
    return withKey(request, stable -> prepare.apply(
        new PreparationScope(stable, store.lookup(stable.key(), stable.descriptor()))));
  }

  private <T> T withKey(Request request, Function<Request, T> prepare) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(prepare, "prepare");
    String key = request.key() == null ? OperationKeys.generate(clock) : request.key();
    var keyLock = preparationLocks[Math.floorMod(key.hashCode(), preparationLocks.length)];
    keyLock.lock();
    try {
      return prepare.apply(new Request(key, request.descriptor(), request.context(), request.provenance(), request.historyMode()));
    } finally {
      keyLock.unlock();
    }
  }

  private record AdmissionSnapshot<T>(OperationStore.Acceptance acceptance, Optional<T> admission) {}

  @Override
  public <T> AdmittedAttempt<T> admitAndAccept(Request request, java.util.UUID preparationNonce,
      Function<AcceptanceScope, T> reserve) {
    requireOutsidePreparation(request);
    Objects.requireNonNull(reserve, "reserve");
    AdmissionSnapshot<T> result = withKey(request, stable -> {
      var existing = store.lookup(stable.key(), stable.descriptor());
      if (existing.isPresent()) return new AdmissionSnapshot<>(
          new OperationStore.Acceptance(existing.orElseThrow(), false), Optional.empty());
      final class Scope implements AcceptanceScope {
        private final Thread owner = Thread.currentThread();
        private boolean open = true;
        private OperationStore.Acceptance accepted;

        @Override public Request request() { return stable; }

        @Override public void accept(EngineContext context) {
          if (!open || Thread.currentThread() != owner || accepted != null) {
            throw new IllegalStateException("Accept exactly once within the owning callback");
          }
          Objects.requireNonNull(context, "context");
          beforeAcceptance(stable);
          accepted = preparationNonce == null
              ? store.accept(stable.key(), stable.descriptor(), context, stable.provenance(), stable.historyMode())
              : store.acceptPrepared(stable.key(), stable.descriptor(), context, stable.provenance(),
                  preparationNonce, stable.historyMode());
          afterAcceptance(accepted);
        }
      }
      var scope = new Scope();
      try {
        T reserved = Objects.requireNonNull(reserve.apply(scope), "admission result");
        if (scope.accepted == null) throw new IllegalStateException("Admission callback did not accept its row");
        return new AdmissionSnapshot<>(scope.accepted, Optional.of(reserved));
      } finally {
        scope.open = false;
      }
    });
    return new AdmittedAttempt<>(prepared(result.acceptance()), result.admission());
  }

  @Override
  public void requireRecoveryOwner(OperationKind kind) {
    if (!ownedKinds.contains(Objects.requireNonNull(kind, "kind"))) {
      throw new IllegalArgumentException("Kind has no declared owner: " + kind);
    }
  }

  @Override
  public PreparedAttempt accept(Request request) {
    requireOutsidePreparation(request);
    return prepared(withKey(request, stable -> {
      beforeAcceptance(stable);
      var accepted = store.accept(stable.key(), stable.descriptor(),
          stable.context(), stable.provenance(), stable.historyMode());
      afterAcceptance(accepted);
      return accepted;
    }));
  }

  @Override
  public PreparedAttempt acceptPrepared(Request request, java.util.UUID nonce) {
    requireOutsidePreparation(request);
    return prepared(withKey(request, stable -> {
      beforeAcceptance(stable);
      var accepted = store.acceptPrepared(stable.key(), stable.descriptor(),
          stable.context(), stable.provenance(), nonce, stable.historyMode());
      afterAcceptance(accepted);
      return accepted;
    }));
  }

  private void beforeAcceptance(Request request) {
    if (faultHook != NO_FAULT_HOOK) faultHook.accept(new FaultBoundary("before-accept", request.descriptor().kind(),
        request.key(), request.key(), -1, null, 0, 0));
  }

  private void afterAcceptance(OperationStore.Acceptance accepted) {
    if (faultHook != NO_FAULT_HOOK && accepted.created()) {
      var row = accepted.record();
      faultHook.accept(new FaultBoundary("after-accept", row.descriptor().kind(), row.key(), row.key(),
          row.id(), row.checkpointCursor(), row.unitsCompleted(), row.unitsFailed()));
    }
  }

  @Override
  public PreparedAttempt acceptIngestChild(OperationRecordHandle parent,
      io.justsearch.app.api.operations.RecordedRootPlan.Root root) {
    if (!(parent instanceof OperationAttemptRunnerImpl.Control control) || control.owner != this
        || control.done.isCompletedExceptionally() || ingestPlanResolver == null) {
      throw new OperationStoreException(OperationStoreException.Code.CHILD_ACCEPTANCE_REFUSED, null);
    }
    if (preparationLocks[Math.floorMod(control.key.hashCode(), preparationLocks.length)].isHeldByCurrentThread()) {
      throw new IllegalStateException("Accept a child after leaving its parent preparation scope");
    }
    var row = store.find(control.key).orElseThrow(() ->
        new OperationStoreException(OperationStoreException.Code.CHILD_ACCEPTANCE_REFUSED, null));
    var preparation = store.acceptedPreparation(control.id).orElseThrow(() ->
        new OperationStoreException(OperationStoreException.Code.CHILD_ACCEPTANCE_REFUSED, null));
    final io.justsearch.app.api.operations.RecordedRootPlan selected;
    try {
      var plan = ingestPlanResolver.resolve(row, preparation);
      var recordedRoot = plan.roots().stream().filter(candidate -> candidate.equals(root))
          .findFirst().orElseThrow(() -> new IllegalArgumentException("Root is outside recorded parent scope"));
      selected = new io.justsearch.app.api.operations.RecordedRootPlan(plan.generation(), List.of(recordedRoot));
    } catch (IllegalArgumentException invalid) {
      throw new OperationStoreException(OperationStoreException.Code.CHILD_ACCEPTANCE_REFUSED, null);
    }
    return prepared(store.acceptIngestChild(control.key, OperationKeys.generate(clock), preparation, selected));
  }

  private PreparedAttempt prepared(OperationStore.Acceptance accepted) {
    return new Prepared(controlFor(accepted.record()), accepted.record(), !accepted.created());
  }

  @Override
  public Optional<OperationStore.Preparation> pendingPreparation(Request request) {
    return store.pendingPreparation(Objects.requireNonNull(request.key(), "preparation key"), request.descriptor());
  }

  @Override
  public Optional<OperationStore.Preparation> savePreparation(Request request, OperationStore.Preparation preparation) {
    Objects.requireNonNull(request.key(), "preparation key");
    return withKey(request, stable -> store.savePreparation(stable.key(), stable.descriptor(), preparation));
  }

  @Override
  public Optional<OperationStore.Preparation> acceptedPreparation(long id) {
    return store.acceptedPreparation(id);
  }

  @Override
  public void checkpointBulkReindex(OperationRecordHandle handle,
      io.justsearch.app.api.operations.BulkReindexProgress progress) {
    if (!(handle instanceof OperationAttemptRunnerImpl.Control control) || control.owner != this
        || control.kind != OperationKind.REINDEX || !control.started.get() || control.done.isDone()) {
      throw new IllegalArgumentException("Bulk progress requires this runner's live reindex capability");
    }
    try {
      if (!store.checkpointBulkReindex(control.id, Objects.requireNonNull(progress, "progress"))) {
        throw new IllegalStateException("Bulk checkpoint refused for a terminal, unstarted or conflicting operation");
      }
    } catch (RuntimeException | Error failure) {
      persistenceFailed(control, OperationState.RUNNING, failure);
      throw failure;
    }
  }

  @Override
  public Optional<PreparedAttempt> lookup(Request request) {
    requireOutsidePreparation(request);
    return store.lookup(request.key(), request.descriptor())
        .map(row -> new Prepared(controlFor(row), row, true));
  }

  private void requireOutsidePreparation(Request request) {
    Objects.requireNonNull(request, "request");
    if (request.key() != null && preparationLocks[Math.floorMod(request.key().hashCode(), preparationLocks.length)]
        .isHeldByCurrentThread()) {
      throw new IllegalStateException("Observe or accept an operation after leaving its preparation scope");
    }
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
    OperationResult committedResponse;
    control.bodyThread = Thread.currentThread();
    try {
      try {
        execution = Objects.requireNonNull(body.apply(control), "Operation execution");
        committedResponse = control.settingsReceipt == null ? null
            : settingsResponse(control.settingsReceipt, execution.response());
      } finally {
        control.bodyThread = null;
      }
    } catch (Error fatal) {
      fatalSettings(control, fatal);
      failObservation(control, fatal);
      throw fatal;
    } catch (RuntimeException failure) {
      if (control.settingsUncertain) {
        persistenceFailed(control, OperationState.RUNNING, failure);
        throw failure;
      }
      if (control.settingsReceipt != null) {
        finishObserved(control, OperationState.COMPLETE, receipt(control.settingsReceipt.response()));
        OperationRecord row = current(control);
        return new Result(row, settingsResponse(control.settingsReceipt), control.done.minimalCompletionStage());
      }
      if (control.settingsFailure instanceof SettingsCommitOwner.Refused refused) {
        finishObserved(control, OperationState.FAILED, receipt(refused.response()));
        OperationRecord row = current(control);
        return new Result(row, refused.response(), control.done.minimalCompletionStage());
      }
      try { finish(control, failure instanceof CancellationException ? OperationState.CANCELLED : OperationState.FAILED,
          new OperationReceipt(failureCode(failure), null)); }
      catch (RuntimeException storageFailure) {
        if (failure != storageFailure) failure.addSuppressed(storageFailure);
        persistenceFailed(control, failure instanceof CancellationException ? OperationState.CANCELLED : OperationState.FAILED,
            storageFailure);
      }
      throw failure;
    }
    // The settings transaction is synchronous. Its fixed owner's verdict is final when the
    // body returns; an unrelated pending adapter stage cannot retain the bounded witness forever.
    if (control.settingsUncertain) {
      var failure = new IllegalStateException("Settings commitment remains unresolved");
      persistenceFailed(control, OperationState.RUNNING, failure);
      throw failure;
    }
    if (control.settingsReceipt != null || control.settingsFailure != null) {
      boolean committed = control.settingsReceipt != null;
      finishObserved(control, committed ? OperationState.COMPLETE : OperationState.FAILED,
          committed ? receipt(control.settingsReceipt.response())
              : new OperationReceipt(failureCode(control.settingsFailure), null));
      OperationRecord row = current(control);
      OperationResult response = committed ? committedResponse
          : control.settingsFailure instanceof SettingsCommitOwner.Refused refused ? refused.response() : receiptResponse(row);
      return new Result(row, response,
          control.done.minimalCompletionStage());
    }
    if (settingsOwner != null && settingsKind(control.kind)) {
      OperationResult refusal = execution.response().success()
          ? OperationResult.failure("Settings operation did not use its commit owner", "SETTINGS_NOT_APPLIED", Map.of(), false)
          : execution.response();
      finishObserved(control, OperationState.FAILED, receipt(refusal));
      return new Result(current(control), refusal, control.done.minimalCompletionStage());
    }
    execution.completion().whenComplete((outcome, failure) -> {
      Throwable cause = failure instanceof CompletionException ? failure.getCause() : failure;
      if (cause instanceof Error) {
        // Fatal errors are not a failure receipt. The durable RUNNING row is reconciled at boot.
        fatalSettings(control, cause);
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
  public OperationResult applySettings(OperationRecordHandle handle,
      SettingsWitness expected, io.justsearch.app.api.UiSettings candidate) {
    Objects.requireNonNull(expected, "expected settings witness");
    return applySettingsOwned(handle, expected, candidate, false);
  }

  @Override
  public OperationResult applySettingsReset(OperationRecordHandle handle) {
    return applySettingsOwned(handle, null, null, true);
  }

  private OperationResult applySettingsOwned(OperationRecordHandle handle, SettingsWitness expected,
      io.justsearch.app.api.UiSettings candidate, boolean reset) {
    if (settingsOwner == null) throw new IllegalStateException("Settings owner is not composed");
    if (!(handle instanceof OperationAttemptRunnerImpl.Control control)
        || active.get(control.id) != control || control.bodyThread != Thread.currentThread()
        || control.done.isDone()) {
      throw new IllegalArgumentException("Settings require this runner's executing body capability");
    }
    OperationRecord row = current(control);
    if (row.state() != OperationState.RUNNING || !settingsKind(row.descriptor().kind())) {
      throw new IllegalStateException("Settings commitment refused for this attempt");
    }
    if (!reset && expected.acceptedRevision() == Long.MAX_VALUE) {
      throw new IllegalArgumentException("Invalid expected settings revision");
    }
    if (!control.settingsStarted.compareAndSet(false, true)) {
      throw new IllegalStateException("Settings commitment already attempted");
    }
    try {
      var reservation = Objects.requireNonNull(reset
          ? settingsOwner.reserveReset(row, store.acceptedPreparation(control.id).orElseThrow(
              () -> new IllegalArgumentException("Settings reset requires accepted preparation")))
          : settingsOwner.reserve(control.id, control.key, expected), "Settings reservation");
      long expectedRevision = reservation.expectedRevision();
      if (expectedRevision < 0 || expectedRevision == Long.MAX_VALUE
          || (!reset && expectedRevision != expected.acceptedRevision())) {
        throw new IllegalArgumentException("Settings reservation marker mismatch");
      }
      control.settingsExpected = expectedRevision;
      if (!store.armSettingsRevision(control.id, expectedRevision)) {
        throw new OperationStoreException(OperationStoreException.Code.STORAGE_FAILED, null);
      }
      if (reset) settingsOwner.applyReset(reservation, control.settingsControl);
      else settingsOwner.apply(reservation, candidate, control.settingsControl);
      if (control.settingsUncertain) throw new IllegalStateException("Settings commitment remains unresolved");
      if (control.settingsReceipt == null) {
        control.settingsUncertain = true;
        throw new IllegalStateException("Settings owner returned without a receipt");
      }
      if (faultHook != NO_FAULT_HOOK) faultHook.accept(new FaultBoundary("after-effect", control.kind,
          control.key, control.key, control.id, null, 0, 0));
      return settingsResponse(control.settingsReceipt);
    } catch (RuntimeException failure) {
      control.settingsFailure = failure;
      if (control.settingsUncertain) fatalSettings(control, failure);
      throw failure;
    } catch (Error fatal) {
      fatalSettings(control, fatal);
      throw fatal;
    }
  }

  private static boolean settingsKind(OperationKind kind) {
    return kind == OperationKind.SETTINGS_APPLY || kind == OperationKind.RECONFIGURE;
  }

  private static OperationResult settingsResponse(SettingsCommitOwner.Receipt receipt) {
    return receipt.response();
  }

  /** First-response observations never replace the owner's prepared commitment or durable receipt. */
  private static OperationResult settingsResponse(SettingsCommitOwner.Receipt receipt, OperationResult observation) {
    OperationResult committed = receipt.response();
    if (!observation.success() || observation.structuredData().isEmpty()) return committed;
    Map<String, Object> data = new java.util.LinkedHashMap<>(observation.structuredData());
    data.putAll(committed.structuredData());
    return new OperationResult(committed.success(), committed.message(), committed.executionId(), data,
        committed.errorCode(), committed.errorDetails(), committed.retryable());
  }

  private void fatalSettings(Control control, Throwable failure) {
    if (settingsOwner != null && (control.settingsStarted.get() || control.settingsRecovery)
        && control.settingsRestartRequested.compareAndSet(false, true)) {
      control.settingsUncertain = true;
      try {
        settingsOwner.retainForRestart(control.id, failure);
      } catch (RuntimeException | Error restartFailure) {
        if (restartFailure != failure) failure.addSuppressed(restartFailure);
      }
    }
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
    if (settingsKind(kind)) throw new IllegalArgumentException("Settings recovery belongs to the fixed owner");
    reconcileOwned(kind, reconciler);
  }

  private void reconcileOwned(OperationKind kind, Function<OperationRecord, Reconciliation> reconciler) {
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
      if (decision instanceof Reconciliation.CheckpointAndWait checkpoint) {
        if (row.state() != OperationState.RUNNING) throw new IllegalArgumentException("Recovery checkpoint requires RUNNING");
        try { control.checkpoint(checkpoint.cursor(), checkpoint.completed(), checkpoint.failed()); }
        catch (RuntimeException | Error failure) {
          persistenceFailed(control, OperationState.RUNNING, failure);
          throw failure;
        }
        continue;
      }
      if (decision instanceof Reconciliation.Wait || !control.started.compareAndSet(false, true)) continue;
      switch (decision) {
        case Reconciliation.Complete complete -> finishObserved(control, OperationState.COMPLETE, complete.receipt());
        case Reconciliation.Failed failed -> finishObserved(control, OperationState.FAILED, failed.receipt());
        case Reconciliation.Cancelled cancelled -> finishObserved(control, OperationState.CANCELLED, cancelled.receipt());
        case Reconciliation.Resume resume -> execute(control, resume.body(), true);
        case Reconciliation.Wait ignored -> throw new IllegalStateException("Wait was already handled");
        case Reconciliation.CheckpointAndWait ignored -> throw new IllegalStateException("Checkpoint was already handled");
      }
    }
  }

  private void finish(Control control, OperationState state, OperationReceipt receipt) {
    try {
      OperationRecord row = store.finish(control.id, state, receipt).orElseThrow(
          () -> new OperationStoreException(OperationStoreException.Code.STORAGE_FAILED, null));
      if (settingsOwner != null && settingsKind(row.descriptor().kind())) {
        settingsOwner.releaseAfterTerminal(control.id);
      }
      publishIfTerminal(control, row);
    } catch (Error fatal) {
      fatalSettings(control, fatal);
      failObservation(control, fatal);
      throw fatal;
    }
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
    fatalSettings(control, failure);
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
    if (failure instanceof SettingsCommitOwner.Refused refused) return refused.response().errorCode().orElseThrow();
    if (failure instanceof OperationStoreException storeFailure) {
      return storeFailure.code().name();
    }
    return "UNCAUGHT_EXCEPTION";
  }

  private static OperationResult receiptResponse(OperationRecord row) {
    boolean failed = row.state() == OperationState.FAILED || row.state() == OperationState.CANCELLED;
    Map<String, Object> data = new java.util.LinkedHashMap<>(Map.of("operationKey", row.key(),
        "operationRecordId", row.id(), "state", row.state().name(),
        "unitsCompleted", row.unitsCompleted(), "unitsFailed", row.unitsFailed()));
    if (row.state() == OperationState.COMPLETE && settingsKind(row.descriptor().kind())
        && row.expectedSettingsRevision() != null) {
      data.put("acceptedRevision", Math.addExact(row.expectedSettingsRevision(), 1));
      data.put("witness", new SettingsWitness(Math.addExact(row.expectedSettingsRevision(), 1), row.key()));
    }
    return new OperationResult(!failed, "Operation " + row.state().name(),
        Optional.ofNullable(row.receipt()).map(OperationReceipt::executionId),
        Map.copyOf(data),
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
    private final OperationAttemptRunnerImpl owner = OperationAttemptRunnerImpl.this;
    private final long id;
    private final String key;
    private final OperationKind kind;
    private final boolean settingsRecovery;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean settingsStarted = new AtomicBoolean();
    private final AtomicBoolean settingsRestartRequested = new AtomicBoolean();
    private volatile Thread bodyThread;
    private volatile SettingsCommitOwner.Receipt settingsReceipt;
    private volatile boolean settingsUncertain;
    private volatile RuntimeException settingsFailure;
    private long settingsExpected;
    // Kept separate from the handler-visible Control: casting its handle must not expose commit authority.
    private final SettingsCommitOwner.AttemptControl settingsControl = new SettingsCommitOwner.AttemptControl() {
      @Override public void committed(SettingsCommitOwner.Receipt receipt) {
        if (receipt == null || settingsReceipt != null || !key.equals(receipt.operationKey())
            || receipt.acceptedRevision() != Math.addExact(settingsExpected, 1)) {
          settingsUncertain = true;
          throw new IllegalStateException("Settings owner supplied a contradictory receipt");
        }
        settingsReceipt = receipt;
      }
      @Override public void uncertain() { settingsUncertain = true; }
    };
    private final CompletableFuture<OperationRecord> done = new CompletableFuture<>();
    private Control(OperationRecord row) {
      id = row.id(); key = row.key(); kind = row.descriptor().kind();
      settingsRecovery = settingsKind(row.descriptor().kind()) && row.expectedSettingsRevision() != null;
    }
    @Override public long id() { return id; }
    @Override public String key() { return key; }
    @Override public void checkpoint(String cursor, long completed, long failed) {
      if (faultHook != NO_FAULT_HOOK && kind == OperationKind.INGEST && completed > 0
          && cursor != null && cursor.startsWith("ingest-receipt:")) {
        var row = current(this);
        String parentKey = io.justsearch.app.api.operations.RecordedIngestChild.parentKey(row.descriptor());
        var parent = store.find(parentKey).orElseThrow();
        faultHook.accept(new FaultBoundary("after-effect", parent.descriptor().kind(), parentKey,
            key, id, cursor, completed, failed));
      }
      if (!store.checkpoint(id, cursor, completed, failed)) {
        throw new IllegalStateException("Checkpoint refused for a terminal, unstarted or newer operation");
      }
    }
  }
}
