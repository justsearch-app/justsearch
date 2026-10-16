/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.justsearch.ort.QueryDevice;
import java.util.concurrent.TimeUnit;

/**
 * The semantic sub-budget parameters of a search (Spec D §0, §1.1). Every value is changed here and
 * nowhere else; calibration (Spec D step 11) replaces the provisional ones and records each source
 * beside it.
 *
 * <pre>
 * semanticDeadline = min(start + cap, D - lexicalReserveMs - marginMs)
 * cap              = semanticCapGpuMs when every requested query leg selects a GPU session,
 *                    semanticCapCpuMs otherwise
 * vectorOnly       = D - marginMs (VECTOR-only has no keyword fallback, Spec D §5.3)
 * </pre>
 *
 * <p>The request deadline {@code D} itself is the head's ({@code requestDeadlineMs}, 15 000 ms,
 * {@code KnowledgeServerConfig.DEFAULT_DEADLINE_MS}); this policy only divides it.
 *
 * @param semanticCapGpuMs cap when every requested leg runs on a GPU session. 2 000 ms: the
 *     orchestrator's start value for item 5a (task t-20261005-b6d536); R7 GPU hybrid 300-336 ms.
 * @param semanticCapCpuMs cap when any requested leg runs on CPU. 8 000 ms: provisional (Spec D §0;
 *     M4's CPU-only arm is pending), so CPU-only machines are not made keyword-only (P2).
 * @param lexicalReserveMs time kept for the lexical leg after the semantic deadline. 250 ms: the
 *     measured lexical p99 under load (A1 26.5, A2 21.8, B1 19.0, B2 22.5 ms) doubled, rounded up
 *     to 250 ms, with the 250 ms floor of Spec D §0.
 * @param marginMs time kept for plan, execute, respond and the head merge. 500 ms (Spec D §0).
 */
public record SearchBudgetPolicy(
    long semanticCapGpuMs, long semanticCapCpuMs, long lexicalReserveMs, long marginMs) {

  /** The values in force. */
  public static final SearchBudgetPolicy DEFAULT = new SearchBudgetPolicy(2_000L, 8_000L, 250L, 500L);

  public SearchBudgetPolicy {
    if (semanticCapGpuMs <= 0 || semanticCapCpuMs <= 0 || lexicalReserveMs < 0 || marginMs < 0) {
      throw new IllegalArgumentException("caps must be positive and reserves non-negative");
    }
  }

  /**
   * The absolute semantic deadline for a search that started at {@code startNanos} with request
   * deadline {@code requestDeadlineNanos}, both on the {@link System#nanoTime()} clock.
   */
  public long semanticDeadline(long startNanos, long requestDeadlineNanos, QueryDevice device) {
    long capMs = device == QueryDevice.GPU ? semanticCapGpuMs : semanticCapCpuMs;
    long byCap = startNanos + TimeUnit.MILLISECONDS.toNanos(capMs);
    long byReserve = requestDeadlineNanos - TimeUnit.MILLISECONDS.toNanos(lexicalReserveMs + marginMs);
    return byCap - byReserve < 0 ? byCap : byReserve;
  }

  /** The deadline of a VECTOR-only search, which waits for its vector within {@code D}. */
  public long vectorOnlyDeadline(long requestDeadlineNanos) {
    return requestDeadlineNanos - TimeUnit.MILLISECONDS.toNanos(marginMs);
  }
}
