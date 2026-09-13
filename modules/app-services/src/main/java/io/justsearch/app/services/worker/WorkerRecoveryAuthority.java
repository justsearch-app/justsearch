/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

/**
 * The one entry point through which anything OTHER than the periodic tick asks for a worker recovery
 * (tempdoc 825 §D5 decision 4). Today that is {@code POST /api/worker/restart}, which 503s in exactly
 * the state an operator would reach for it — the worker never started, so there is no spawner to
 * restart. Routing it here gives the manual path the same bounded budget, the same vetoes, and the
 * same narration as the automatic one, instead of a second restart authority.
 */
public interface WorkerRecoveryAuthority {

  /** How the authority answered a manual recovery request. */
  enum Verdict {
    /** An attempt was scheduled on the recovery loop. Poll {@code /api/health} for the outcome. */
    ACCEPTED,
    /** A worker is already bound — use the ordinary restart path, there is nothing to recover. */
    NOT_APPLICABLE,
    /** An attempt is already running; the request is a no-op rather than a second concurrent spawn. */
    ALREADY_RUNNING,
    /** This authority's own bounded budget is spent ({@code worker.spawn_recovery_exhausted}). */
    EXHAUSTED
  }

  /**
   * Requests one recovery attempt. Never blocks on the attempt itself: a boot attempt can take tens of
   * seconds (spawn + port discovery + health budget), which is not an HTTP request's business.
   */
  Verdict requestRecoveryNow();
}
