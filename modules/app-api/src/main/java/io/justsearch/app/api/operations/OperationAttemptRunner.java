/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import io.justsearch.agent.api.registry.OperationKind;

import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.core.context.EngineContext;
import io.justsearch.app.api.settings.SettingsWitness;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.time.Duration;

/** Shared acceptance/effect/completion owner for dispatch, ingestion, settings and scheduled work. */
public interface OperationAttemptRunner {
  /** Maximum total attempts for durable-operation recovery; distinct from per-file retries. */
  int MAX_DURABLE_ATTEMPTS = 3;

  /** Bounded metadata for the first failed durable transition in this process. */
  record PersistenceFailure(String operationKey, OperationState intendedState) {}

  /** Sticky degradation signal; late Health subscribers still observe the first failure. */
  CompletionStage<PersistenceFailure> persistenceFailure();

  /** Permanently refuse new effect bodies for this process incarnation. */
  void beginClosing();

  /** Monotonic process-close state; a same-process producer replacement preserves pending bodies. */
  default boolean isClosing() { return false; }

  /** Boot-only fixed settings recovery after component owners exist and before request admission. */
  boolean reconcileSettingsAfterComposition();

  /** Wait for executing bodies and their durable completion callbacks to leave the store. */
  boolean awaitDrained(Duration timeout);

  /**
   * Relinquish one durable pending body after its physical producer has stopped and checkpointed.
   * The RUNNING row and its completion observation remain for successor recovery; this only
   * releases this process's live body ownership. The exact runner-issued handle is required.
   */
  default boolean handoffPendingForShutdown(OperationRecordHandle handle) {
    throw new UnsupportedOperationException("Durable attempt handoff is unavailable");
  }

  record Request(String key, OperationDescriptor descriptor, EngineContext context,
      InvocationProvenance provenance, OperationHistoryMode historyMode) {
    public Request {
      java.util.Objects.requireNonNull(historyMode, "historyMode");
    }

    /** Producers with their own history use NONE; audited direct producers supply an explicit mode. */
    public Request(String key, OperationDescriptor descriptor, EngineContext context,
        InvocationProvenance provenance) {
      this(key, descriptor, context, provenance, OperationHistoryMode.NONE);
    }
  }

  /** Opaque runner-issued capability, retained between scheduling and fire-time admission. */
  interface PreparedAttempt {
    OperationRecord accepted();
    boolean existing();
    CompletionStage<OperationRecord> completion();
  }

  record Result(OperationRecord record, OperationResult response, CompletionStage<OperationRecord> completion) {}

  /** Validated metadata snapshot; observing it never publishes completion callbacks. */
  record PreparationScope(Request request, java.util.Optional<OperationRecord> existing) {}

  /**
   * Serialize pure preparation for one key, minting an absent key once. The callback receives
   * the stable request and validated existing-row snapshot outside the SQLite lock. It may
   * read/save pending preparation for this key. Return before calling lookup, acceptance,
   * admission, scheduling or execution: those can publish arbitrary completion listeners.
   * An existing row bypasses preparation and never authorizes a repeated effect.
   */
  <T> T withPreparation(Request request, Function<PreparationScope, T> prepare);

  /** One nonpublishing acceptance capability, valid only on the callback thread and within its scope. */
  interface AcceptanceScope {
    Request request();
    void accept(EngineContext authorizedContext);
  }

  record AdmittedAttempt<T>(PreparedAttempt attempt, java.util.Optional<T> admission) {}

  /**
   * Arbitrate final lookup, effect admission and raw acceptance under the existing key stripe.
   * An existing row skips the callback. An absent row must be accepted exactly once by the
   * callback, which may reserve work and consume deferred consent but must not publish events,
   * install publishing listeners, close handles, schedule work or execute effects. A reserved
   * work's cancellation listener may only update the reservation's local handoff state, including
   * immediate delivery. That listener must not call runner, store or admission APIs, close
   * handles, publish, schedule, or invoke external callbacks. Caller listeners must be registered
   * before entering this scope.
   * Prepared/control futures
   * are attached only after releasing the stripe. The caller owns reservation cleanup on failure.
   */
  <T> AdmittedAttempt<T> admitAndAccept(Request request, java.util.UUID preparationNonce,
      Function<AcceptanceScope, T> reserve);

  /** Composition must not promise explicit durable survival without a boot recovery owner. */
  void requireRecoveryOwner(OperationKind kind);

  java.util.Optional<OperationStore.Preparation> pendingPreparation(Request request);
  java.util.Optional<OperationStore.Preparation> savePreparation(Request request, OperationStore.Preparation preparation);
  PreparedAttempt acceptPrepared(Request request, java.util.UUID nonce);
  java.util.Optional<OperationStore.Preparation> acceptedPreparation(long id);

  /** Persist committed bulk evidence through this runner's live asynchronous attempt capability. */
  default void checkpointBulkReindex(OperationRecordHandle handle, BulkReindexProgress progress) {
    throw new UnsupportedOperationException("Bulk reindex progress is unavailable");
  }

  /**
   * Prepare and arm the exact accepted installer-generation settings projection at its final
   * cutover boundary. The returned collaborator is non-terminal; the recorded owner decides from
   * the generation pointer and the runner remains the only terminal row writer.
   */
  default io.justsearch.app.api.settings.SettingsCommitOwner.PreparedGenerationProjection
      prepareInstallerGenerationProjection(OperationRecordHandle handle,
          RecordedInstallerGenerationPlan plan, io.justsearch.app.api.EngineWorkHandle work) {
    throw new UnsupportedOperationException("Installer generation settings are unavailable");
  }

  /** The recorded owner asks its fixed settings collaborator for exact roll-forward evidence. */
  default boolean installerGenerationProjected(RecordedInstallerGenerationPlan plan) {
    return false;
  }

  /** Physical bulk boundaries observed by the existing installed recovery harness. */
  enum BulkBoundary {
    PARTIAL_CAPTURE, AFTER_PROMOTION,
    INSTALLER_BEFORE_MARKER, INSTALLER_BEFORE_ARM, INSTALLER_BEFORE_POINTER,
    INSTALLER_POINTER_BEFORE_SETTINGS, INSTALLER_SETTINGS_BEFORE_PUBLICATION,
    INSTALLER_BEFORE_RECEIPT
  }

  /** Observe an already committed effect; this does not persist progress or grant authority. */
  default void observeBulkBoundary(OperationRecordHandle handle, BulkBoundary boundary) {}

  /** Root-composed decoder of the actual accepted parent preparation, never supplied by a handler. */
  @FunctionalInterface
  interface IngestPlanResolver {
    RecordedRootPlan resolve(OperationRecord parent, OperationStore.Preparation preparation);
  }

  /** Accept the exact frozen root beneath a runner-issued parent; parent composes child completion. */
  default PreparedAttempt acceptIngestChild(OperationRecordHandle parent, RecordedRootPlan.Root root) {
    throw new OperationStoreException(OperationStoreException.Code.CHILD_ACCEPTANCE_REFUSED, null);
  }

  /** Must return before scheduling or any other effect; storage failure propagates. */
  PreparedAttempt accept(Request request);

  /** Validated row lookup before pure preparation; an existing attempt never authorizes a new effect. */
  java.util.Optional<PreparedAttempt> lookup(Request request);

  /**
   * Called after admission. An existing acceptance never executes the supplied body.
   * Non-dispatched producers use this same port: commit their effect inside the body and return
   * {@link OperationExecution#finished(OperationResult)} for synchronous work, or supply the
   * actual owner completion stage. Success completes the row; failure fails it. The returned
   * completion observes the durable row transition, not merely the effect's completion.
   * Producers never need a catalog, dispatcher, or direct terminal-write access to the store.
   */
  Result start(PreparedAttempt attempt, Function<OperationRecordHandle, OperationExecution> body);

  /**
   * Synchronous settings commitment inside this runner's currently executing body. The capability
   * must be live, issued by this runner, and used on that body thread. A retained handle cannot
   * race later terminal completion. Async producers perform this step before returning their stage.
   * Expected must be the full witness read with the candidate's base snapshot; refreshing only
   * its revision/key would allow a stale whole-document candidate to overwrite a newer change.
   */
  OperationResult applySettings(OperationRecordHandle handle, SettingsWitness expected,
      io.justsearch.app.api.UiSettings candidate);

  /** Internal transient target belongs to this accepted attempt, not a second runtime effect. */
  default OperationResult applySettings(OperationRecordHandle handle, SettingsWitness expected,
      io.justsearch.app.api.UiSettings candidate,
      io.justsearch.app.api.settings.SettingsCandidateContext candidateContext) {
    if (io.justsearch.app.api.settings.SettingsCandidateContext.NONE.equals(candidateContext)) {
      return applySettings(handle, expected, candidate);
    }
    throw new UnsupportedOperationException("Transient settings candidate context is unavailable");
  }

  /** Bind cancellation of the exact admitted effect to precommit arbitration. */
  default OperationResult applySettings(OperationRecordHandle handle, SettingsWitness expected,
      io.justsearch.app.api.UiSettings candidate, io.justsearch.app.api.EngineWorkHandle work) {
    return applySettings(handle, expected, candidate);
  }

  /** Bind the accepted transient intent and the exact admitted cancellation scope together. */
  default OperationResult applySettings(OperationRecordHandle handle, SettingsWitness expected,
      io.justsearch.app.api.UiSettings candidate,
      io.justsearch.app.api.settings.SettingsCandidateContext candidateContext,
      io.justsearch.app.api.EngineWorkHandle work) {
    if (io.justsearch.app.api.settings.SettingsCandidateContext.NONE.equals(candidateContext)) {
      return applySettings(handle, expected, candidate, work);
    }
    throw new UnsupportedOperationException("Transient settings candidate context is unavailable");
  }

  /** Fixed reset from this executing attempt's persisted server preparation; no caller authority flags. */
  OperationResult applySettingsReset(OperationRecordHandle handle);

  /** A scheduling, validation or admission refusal cannot overwrite work that already started. */
  void rejectBeforeStart(PreparedAttempt attempt, String reason);

  /** Owner readiness is separate from the process-root's synchronous declaration of owned kinds. */
  void reconcile(OperationKind kind, Function<OperationRecord, Reconciliation> reconciler);

  sealed interface Reconciliation {
    record Wait() implements Reconciliation {}
    /**
     * Persist a RUNNING owner's recovery decision without starting an effect or spending an
     * attempt. The owner serializes its decisions and remains eligible for later reconciliation.
     */
    record CheckpointAndWait(String cursor, long completed, long failed) implements Reconciliation {}
    /** Persist typed bulk progress for a recovered RUNNING attempt without resuming it. */
    record CheckpointBulkAndWait(BulkReindexProgress progress) implements Reconciliation {
      public CheckpointBulkAndWait {
        java.util.Objects.requireNonNull(progress, "progress");
      }
    }
    record Complete(OperationReceipt receipt) implements Reconciliation {}
    record Failed(OperationReceipt receipt) implements Reconciliation {}
    record Cancelled(OperationReceipt receipt) implements Reconciliation {}
    /** Owner has revalidated dependencies/authority; C2-8 owns the resume eligibility policy. */
    record Resume(Function<OperationRecordHandle, OperationExecution> body) implements Reconciliation {}
  }
}
