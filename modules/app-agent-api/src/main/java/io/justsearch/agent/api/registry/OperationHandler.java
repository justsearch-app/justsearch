/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import io.justsearch.core.context.EngineContext;
import java.util.Objects;

/**
 * SPI for executing an Operation invocation.
 *
 * <p>Per tempdoc 429 §E.4: handlers register at boot via {@code HandlerRegistry}
 * (explicit boot-time construction in {@code HeadAssembly}; ServiceLoader
 * extension point deferred to V1.5 per §A.5). The trust-tier-aware
 * {@code OperationExecutor} resolves the handler via {@code Binding.handlerId()} and
 * dispatches.
 *
 * <p>{@code execute(argumentsJson, engineContext)} takes the raw argument JSON string (mirroring the
 * legacy {@code ToolDefinition.execute} contract per §A.2 — bit-for-bit preserved
 * AgentLoopService behavior). Handlers parse via their own ObjectMapper (this module
 * has Jackson annotations only).
 *
 * <p>{@code undo(executionId, engineContext)} default throws {@link UnsupportedOperationException}.
 * Handlers that support undo override it; the executor checks
 * {@link OperationPolicy#undoSupported()} before delegating per §E.3.
 */
public interface OperationHandler {

  /** Execute the operation against the parsed argument JSON. */
  OperationResult execute(String argumentsJson, EngineContext engineContext);

  /**
   * Slice 491 F6 — context-aware execute overload. Receives the
   * {@link InvocationProvenance} record alongside the args JSON. Default delegates
   * to {@link #execute(String, EngineContext)} while preserving the required Engine context.
   *
   * <p>Handlers that need transport / source-tier / dispatch-time context override
   * this overload. Reference case: {@code NavigateToSurfaceHandler} reads
   * {@code provenance.transport()} to tag the dispatched Navigation Intent's
   * transport correctly (the prior C4.B implementation hardcoded
   * {@code TransportTag.AGENT_LOOP}, misrepresenting transport for UI-initiated
   * invocations).
   *
   * <p>The executor (e.g., {@code OperationExecutorImpl}) calls this overload at
   * every dispatch site; handlers can read dispatch-only fields while downstream port calls
   * retain the same Engine context.
   */
  default OperationResult execute(String argumentsJson, InvocationProvenance provenance, EngineContext engineContext) {
    return execute(argumentsJson, engineContext);
  }

  /**
   * Prepare an invocation before its parent operation is accepted.
   *
   * <p>The default adapts existing handlers by carrying the raw arguments transiently. This hook
   * is pure: it must not schedule work, register an operation, or perform another effect. An
   * expected no-effect refusal should be reported with {@link OperationPreparationRefused} so the
   * runner can retain the generic invocation identity and return its typed failure without
   * scheduling or admitting work.
   */
  default OperationPreparation prepare(
      String argumentsJson, InvocationProvenance provenance, EngineContext engineContext) {
    return OperationPreparation.passthrough(argumentsJson);
  }

  /**
   * Execute a previously prepared invocation while preserving its frozen preparation.
   *
   * <p>The default supports only the transient passthrough preparation. A handler that returns a
   * replay payload must override this method so that the payload cannot be silently ignored. When
   * no record exists, the ordinary context-aware execute path is used; a record is forwarded to
   * the existing recorded execution path unchanged.
   */
  default OperationExecution executePrepared(
      OperationPreparation prepared,
      InvocationProvenance provenance,
      EngineContext engineContext,
      OperationRecordHandle record) {
    Objects.requireNonNull(prepared, "prepared");
    if (prepared.replaySchema() != null) {
      throw new UnsupportedOperationException(
          "Handler must override executePrepared for replay-capable preparation");
    }
    if (record != null) {
      return executeRecorded(prepared.argumentsJson(), provenance, engineContext, record);
    }
    return OperationExecution.finished(execute(prepared.argumentsJson(), provenance, engineContext));
  }

  /** Synchronous default; asynchronous owners override and supply actual completion. */
  default OperationExecution executeRecorded(String argumentsJson, InvocationProvenance provenance,
      EngineContext engineContext, OperationRecordHandle record) {
    return OperationExecution.finished(execute(argumentsJson, provenance, engineContext));
  }

  /** Undo participates in the same runner-owned attempt and actual-completion contract. */
  default OperationExecution undoRecorded(String executionId, EngineContext engineContext,
      OperationRecordHandle record) {
    return OperationExecution.finished(undo(executionId, engineContext));
  }

  /**
   * Undo a previous execution identified by {@code executionId}. Default throws.
   *
   * <p>Handlers that support undo override this method AND set
   * {@code OperationPolicy.undoSupported = true} on their declared Operation entry.
   * The executor's {@code undo(op, executionId)} checks the policy flag before delegating
   * — invocations on operations without undo support fail fast with a typed denial,
   * never reach the handler.
   */
  default OperationResult undo(String executionId, EngineContext engineContext) {
    throw new UnsupportedOperationException(
        "Undo not supported by " + getClass().getSimpleName());
  }
}
