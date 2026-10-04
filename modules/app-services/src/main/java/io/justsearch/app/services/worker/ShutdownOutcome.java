/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

/**
 * How the index half's shutdown went, as reported by
 * {@link KnowledgeServerBootstrap#closeForUpgrade()} and surfaced by the Head's shutdown
 * coordinator.
 *
 * <p>Lane F stage A item A11 lifted this out of {@code WorkerSpawner}, where it described a
 * <em>process</em> termination. The vocabulary survived the spawner because it is about the
 * shutdown's outcome, not about how the thing being shut down was started — and the Head's
 * shutdown report has always been the consumer.
 *
 * <p><b>{@link #FORCED} has no producer in stage A.</b> It meant "the Worker process did not exit
 * within the grace period, so it was killed"; an in-process index half is closed, not killed, so
 * only {@link #GRACEFUL} and {@link #FAILED} are reachable. It is kept rather than deleted because
 * design 7.1's one supervisor contract lands at stage B with a real child-process kill path
 * (llama-server already has one), and the shutdown report's vocabulary should not have to be
 * re-widened then.
 */
public enum ShutdownOutcome {
  /** Everything closed in order, within its budget. */
  GRACEFUL,

  /** Something had to be killed rather than asked to stop. No producer in stage A. */
  FORCED,

  /** A close step threw, and the shutdown report says so rather than claiming success. */
  FAILED
}
