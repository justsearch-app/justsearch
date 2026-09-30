/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.api.AgentEvent;
import java.util.function.Consumer;

/**
 * Tempdoc 560 WS5 (the one window) — the seam that lets the agent loop invoke a projected
 * {@link Workflow} as a <em>streaming</em> tool.
 *
 * <p>A workflow is a LANGUAGE_MEDIATED Manifest (an ordered sequence of LLM / tool / gate nodes). The
 * one-window goal is that the model sees workflows in the <em>same</em> tool list as core operations
 * and MCP tools, and that invoking one streams its node-by-node progress into the agent's live event
 * stream rather than returning a single opaque blob at the end.
 *
 * <p>The standard {@link OperationHandler#execute} contract is synchronous (it returns one {@link
 * OperationResult} with no progress channel), so a workflow tool cannot stream through it. This
 * interface is the streaming alternative: the agent loop detects a workflow-kind tool call
 * ({@link #handles}) and routes it here, passing its own {@link AgentEvent} sink so the workflow's
 * progress is forwarded live (as {@link AgentEvent.AgentProgress} events) while the final node output
 * becomes the returned {@link OperationResult} the model reads.
 *
 * <p>The interface lives in {@code app-agent-api} (which both {@link AgentEvent} and {@link
 * OperationResult} already inhabit) so the agent loop ({@code app-agent}) depends only on this
 * abstraction; the concrete bridge to {@code WorkflowShapeRunner} lives in {@code app-services}.
 */
public interface WorkflowToolRunner {

  /** True when {@code ref} is a projected workflow this runner executes (vs. a normal operation). */
  boolean handles(OperationRef ref);

  /**
   * Run the workflow projected as {@code ref}, forwarding its progress to {@code sink} as
   * {@link AgentEvent.AgentProgress} events and returning the terminal node output as the result.
   *
   * @param ref the projected workflow operation ref (must satisfy {@link #handles}).
   * @param argumentsJson the model-supplied tool arguments (JSON object source; may be blank).
   * @param sink the agent loop's live event sink — the same one the surrounding tool call streams to.
   * @return the workflow's final result; a workflow error becomes an {@link OperationResult#failure}
   *     the model can see and recover from (host owns truth, §4.5).
   */
  OperationResult run(OperationRef ref, String argumentsJson, Consumer<AgentEvent> sink, EngineContext engineContext);

  /** Server-owned run posture; implementations must explicitly support background execution. */
  default OperationResult run(OperationRef ref, String argumentsJson, Consumer<AgentEvent> sink,
      EngineContext engineContext, boolean background) {
    return background ? OperationResult.failure("Workflow runner does not support background execution")
        : run(ref, argumentsJson, sink, engineContext);
  }

  /** Public gate details for an enclosing run's snapshot; never frozen approval display. */
  default java.util.List<AgentEvent.PendingApproval> pendingApprovals(String sessionId) {
    return java.util.List.of();
  }
}
