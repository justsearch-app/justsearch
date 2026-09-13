/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.lifecycle;

/**
 * Tempdoc 561 P-A — the agent loop's lifecycle state, formalized as a typed enum (it was a bare
 * string in {@code AgentRunStore.meta["state"]}). The §2.2 phase-totality obligation: every phase the
 * loop can be in is a declared, projectable value — no phase is unrepresentable or conflated.
 *
 * <p>This is the state machine 550 Thesis II names. Single-owner liveness (one loop thread = one
 * session, explicit termination) — so, unlike the indexing lifecycle, it carries no orphan-RUNNING /
 * worker-drain hazard (561 §10).
 */
public enum LifecycleState {
  /** Ready to call the LLM (the loop's resting/advancing state). */
  READY_FOR_LLM,
  /** Blocked on a user approval for a proposed tool call. */
  WAITING_APPROVAL,
  /**
   * Tempdoc 577 §2.12 Move 2 — parked at the budget boundary awaiting the human's decision
   * (continue with more tokens / finalize from what it has / stop). The budget sibling of
   * {@link #WAITING_APPROVAL}: a held, non-terminal decision state.
   */
  WAITING_BUDGET,
  /**
   * Tempdoc 577 §2.14 Root II (#14) — parked at the context-pressure boundary awaiting the human's
   * decision (continue anyway / compact older turns / stop). The COGNITIVE sibling of
   * {@link #WAITING_BUDGET}: a held, non-terminal decision state when the prompt approaches n_ctx.
   */
  WAITING_CONTEXT,
  /** A tool executed; the result is being fed back to the LLM. */
  AFTER_TOOL_RESULT,
  /** Terminal — the loop completed and produced a final response. */
  DONE,
  /** Terminal — the loop failed. */
  ERROR,
  /** Terminal — the run was cancelled by the user, an Engine shutdown, or its work deadline. */
  CANCELLED;

  /** Whether this is a terminal state (the loop has stopped). */
  public boolean isTerminal() {
    return this == DONE || this == ERROR || this == CANCELLED;
  }

  /** Parse the persisted string form, defaulting to {@link #READY_FOR_LLM} for unknown values. */
  public static LifecycleState parse(Object raw) {
    if (raw instanceof String s) {
      try {
        return LifecycleState.valueOf(s);
      } catch (IllegalArgumentException ignored) {
        // fall through
      }
    }
    return READY_FOR_LLM;
  }
}
