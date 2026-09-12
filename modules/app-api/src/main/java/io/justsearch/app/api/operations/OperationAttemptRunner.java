/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.core.context.EngineContext;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/** Shared acceptance/effect/completion owner for dispatch, ingestion, settings and scheduled work. */
public interface OperationAttemptRunner {
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

  /** Called after admission. An existing acceptance never executes the supplied body. */
  Result start(PreparedAttempt attempt, Function<OperationRecordHandle, OperationExecution> body);

  /** A scheduling, validation or admission refusal cannot overwrite work that already started. */
  void rejectBeforeStart(PreparedAttempt attempt, String reason);

  /** Owner readiness is separate from the process-root's synchronous declaration of owned kinds. */
  void reconcile(OperationKind kind, Function<OperationRecord, Reconciliation> reconciler);

  sealed interface Reconciliation {
    record Wait() implements Reconciliation {}
    record Complete(OperationReceipt receipt) implements Reconciliation {}
    record Failed(OperationReceipt receipt) implements Reconciliation {}
    /** Owner has revalidated dependencies/authority; C2-8 owns the resume eligibility policy. */
    record Resume(Function<OperationRecordHandle, OperationExecution> body) implements Reconciliation {}
  }
}
