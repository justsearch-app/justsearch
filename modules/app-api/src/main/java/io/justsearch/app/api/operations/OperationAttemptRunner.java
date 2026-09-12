/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import io.justsearch.agent.api.registry.OperationKind;

import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.core.context.EngineContext;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/** Shared acceptance/effect/completion owner for dispatch, ingestion, settings and scheduled work. */
public interface OperationAttemptRunner {
  /** Bounded metadata for the first failed durable transition in this process. */
  record PersistenceFailure(String operationKey, OperationState intendedState) {}

  /** Sticky degradation signal; late Health subscribers still observe the first failure. */
  CompletionStage<PersistenceFailure> persistenceFailure();

  record Request(String key, OperationDescriptor descriptor, EngineContext context,
      InvocationProvenance provenance) {}

  /** Opaque runner-issued capability, retained between scheduling and fire-time admission. */
  interface PreparedAttempt {
    OperationRecord accepted();
    boolean existing();
    CompletionStage<OperationRecord> completion();
  }

  record Result(OperationRecord record, OperationResult response, CompletionStage<OperationRecord> completion) {}

  /** Must return before scheduling or any other effect; storage failure propagates. */
  PreparedAttempt accept(Request request);

  /**
   * Find or accept a child inside this runner's parent capability and recorded root scope.
   * The Engine mints its key; callers cannot repartition scope or supply another generation.
   * Existing children never execute through start; interrupted children use INGEST reconciliation.
   */
  PreparedAttempt acceptIngestChild(OperationRecordHandle parent, RecordedRootPlan.Root root);

  /**
   * Called after admission. An existing acceptance never executes the supplied body.
   * Non-dispatched producers use this same port: commit their effect inside the body and return
   * {@link OperationExecution#finished(OperationResult)} for synchronous work, or supply the
   * actual owner completion stage. Success completes the row; failure fails it. The returned
   * completion observes the durable row transition, not merely the effect's completion.
   * Producers never need a catalog, dispatcher, or direct terminal-write access to the store.
   */
  Result start(PreparedAttempt attempt, Function<OperationRecordHandle, OperationExecution> body);

  /** A scheduling, validation or admission refusal cannot overwrite work that already started. */
  void rejectBeforeStart(PreparedAttempt attempt, String reason);

  /** Owner readiness is separate from the process-root's synchronous declaration of owned kinds. */
  void reconcile(OperationKind kind, Function<OperationRecord, Reconciliation> reconciler);

  sealed interface Reconciliation {
    record Wait() implements Reconciliation {}
    record Complete(OperationReceipt receipt) implements Reconciliation {}
    record Failed(OperationReceipt receipt) implements Reconciliation {}
    record Cancelled(OperationReceipt receipt) implements Reconciliation {}
    /** Owner has revalidated dependencies/authority; C2-8 owns the resume eligibility policy. */
    record Resume(Function<OperationRecordHandle, OperationExecution> body) implements Reconciliation {}
  }
}
