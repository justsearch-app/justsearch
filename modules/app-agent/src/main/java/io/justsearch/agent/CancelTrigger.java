/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

/**
 * What caused a {@link TerminalDisposition#CANCELLED} disposition. Distinguishes a user-initiated
 * cancel (via {@code AgentService.cancelSession}) from internal triggers like budget exhaustion
 * landing on the cancellation gate, or the loop guard escalating after repeated identical tool
 * calls.
 *
 * <p>Tempdoc 415 — appears as the {@code cancel_trigger} tag on
 * {@code agent.session.terminate_total} only when {@code disposition == CANCELLED}.
 */
enum CancelTrigger {
  /** {@code AgentService.cancelSession} was invoked from outside the loop. */
  USER,
  /** Token budget was exhausted and graceful finalize did not produce a response. */
  BUDGET,
  /** Session was terminated because the loop guard tripped on repeated identical tool calls. */
  TOOL_LOOP,
  /** Engine shutdown, caller interruption, or a work deadline cancelled the run. */
  SYSTEM
}
