/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import io.justsearch.core.context.EngineContext;

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
