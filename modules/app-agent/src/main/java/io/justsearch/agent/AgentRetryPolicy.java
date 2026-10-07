/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import io.justsearch.agent.api.AgentErrorCode;
import io.justsearch.agent.api.RetryAction;
import java.util.concurrent.ThreadLocalRandom;

/** Central retry decision table for agent loop failures. */
final class AgentRetryPolicy {
  private AgentRetryPolicy() {}

  static final class RetryDecision {
    private final RetryAction action;
    private final int maxRetries;
    private final long[] backoffMs;
    private final boolean jitter;

    RetryDecision(RetryAction action, int maxRetries, long[] backoffMs, boolean jitter) {
      this.action = action;
      this.maxRetries = maxRetries;
      this.backoffMs = backoffMs;
      this.jitter = jitter;
    }

    @SuppressWarnings("unused") // Called from AgentRetryPolicyTest
    RetryAction action() {
      return action;
    }

    int maxRetries() {
      return maxRetries;
    }

    long delayMsForAttempt(int retryAttempt) {
      if (retryAttempt <= 0 || backoffMs.length == 0) {
        return 0L;
      }
      int idx = Math.min(retryAttempt - 1, backoffMs.length - 1);
      long base = backoffMs[idx];
      if (!jitter || base <= 1L) {
        return base;
      }
      long variance = Math.max(1L, Math.round(base * 0.2)); // +-20%
      return base + ThreadLocalRandom.current().nextLong(-variance, variance + 1L);
    }
  }

  static RetryDecision forCode(AgentErrorCode code) {
    return switch (code) {
      case LLM_TRANSIENT -> new RetryDecision(RetryAction.RETRY, 2, new long[] {250L, 750L}, true);
      case TOOL_TRANSIENT_READ_ONLY ->
          new RetryDecision(RetryAction.RETRY, 1, new long[] {150L}, true);
      case EMPTY_RESPONSE -> new RetryDecision(RetryAction.RETRY, 1, new long[] {250L}, false);
      case CONTEXT_EXCEEDED,
          ADMISSION_INFERENCE_BUSY,
          BUDGET_EXHAUSTED,
          POLICY_DENIED,
          TOOL_CONTRACT,
          TOOL_LOOP,
          HANDOFF_CYCLE_DETECTED,
          UNKNOWN_TOOL,
          CANCELLED,
          INTERNAL_ERROR,
          NO_TOOLS,
          UNAVAILABLE,
          UNSUPPORTED_RESUME_STATE -> new RetryDecision(RetryAction.ABORT, 0, new long[] {}, false);
    };
  }

  /**
   * Sleeps for a retry back-off delay, restoring the interrupt flag if interrupted. Shared by the
   * LLM-retry loop ({@link AgentLlmCaller}) and the tool-retry loop in {@code AgentLoopService}.
   */
  static void sleepRetryDelay(long delayMs) {
    if (delayMs <= 0L) {
      return;
    }
    try {
      Thread.sleep(delayMs);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    }
  }
}
