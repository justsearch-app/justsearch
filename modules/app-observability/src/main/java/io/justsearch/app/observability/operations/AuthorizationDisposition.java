/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

/**
 * How the trust gate resolved for one dispatch — tempdoc 550 Outcome face (the gate-decision
 * dimension the canonical ledger record was missing).
 *
 * <p>Recorded at the gate-fire site ({@code OperationExecutorImpl.enforceTrustLattice}) so the
 * action ledger — and the 538 trust-firing audit as a read-view of it — sees every gate
 * decision, not only the outcomes of dispatches that completed. (A gated/denied dispatch
 * throws before any {@code OperationHistoryEntry} is emitted, so without this the firing left
 * no trace.)
 */
public enum AuthorizationDisposition {
  /** A non-AUTO gate fired and was NOT satisfied (no valid consent capsule) — dispatch refused. */
  GATED,
  /** The gate produced a categorical DENY — dispatch refused outright. */
  DENIED,
  /** A non-AUTO gate fired and was satisfied by a valid consent capsule — dispatch proceeded. */
  APPROVED,
  /** An unanswered authorization reached its deadline. */
  EXPIRED,
  /**
   * A waiting copy of a request that another request for the same operation key answered. It is
   * not a decision of its own: the answered request carries the decision (L9).
   */
  WITHDRAWN
}
