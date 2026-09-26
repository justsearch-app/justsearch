/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import java.util.Map;

/**
 * SPI for declaring operation-scoped leases against the dev-runner ownership model. Tempdoc 542
 * Layer 3.
 *
 * <p>Call sites that initiate long-running operations register a lease before the first
 * irreversible step. The lease is persisted to {@code tmp/dev-runner/op-leases.json} (single
 * writer = Head) and read by the dev-runner's {@code acquireAdmission} gate to decide takeover
 * policy.
 *
 * <p>Outside the shared full-stack dev mode (e.g., production Tauri, isolated backend-only
 * eval), the {@code JUSTSEARCH_DEV_RUNNER_STATE_ROOT} environment variable is unset; in that
 * case only the file projection is disabled. The production implementation still enforces
 * process-local admission and owner cancellation; call sites do not branch on environment.
 *
 * <p>Stability: stable SPI.
 */
public interface OperationLeaseService {

  /**
   * No-op implementation for tests, isolated launches, and callers that don't want lease
   * semantics. {@code register} returns a handle whose methods all do nothing.
   */
  static OperationLeaseService noOp() {
    return NoOpOperationLeaseService.INSTANCE;
  }

  /**
   * Register a new operation lease and return a handle that controls its lifetime.
   *
   * @param opClass stable string identifying the op type (e.g. {@code "indexing.migration"}).
   *                Must be non-null and non-blank.
   * @param criticality admission-policy class; see {@link OpCriticality}.
   * @param expectedDurationSec optimistic upper bound on op duration. Informs expiry safety;
   *                            should not be the worst-case timeout.
   * @param metadata op-class-specific payload (may be null or empty); persisted in the lease file
   *                 verbatim. Useful for audit (e.g. {@code {"sourceGen": "g-...", "targetGen": "g-..."}}).
   * @return handle to {@code renew} / {@code release} the lease; never null.
   */
  OperationLeaseHandle register(
      String opClass,
      OpCriticality criticality,
      long expectedDurationSec,
      Map<String, Object> metadata);

  /**
   * Register an operation with an owner-controlled cancellation request.
   *
   * <p>The callback is invoked at most once per preparation, and only for interruptible operations.
   * Invocation happens outside the registry lock so the owner may acknowledge by releasing its
   * handle without deadlocking. The callback requests cancellation; it does not acknowledge it.
   */
  default OperationLeaseHandle register(
      String opClass,
      OpCriticality criticality,
      long expectedDurationSec,
      Map<String, Object> metadata,
      Runnable cancellationRequest) {
    return register(opClass, criticality, expectedDurationSec, metadata);
  }

  /**
   * Atomically freeze new registrations and return the leases that were active at the boundary.
   * Repeated calls while frozen return the existing preparation rather than replacing its owner.
   */
  OperationLeaseSnapshot freezeAdmission(String reason);

  /** Return the current process-local admission and active-lease state. */
  OperationLeaseSnapshot snapshot();

  /**
   * Request cancellation from interruptible owners and return the state after callbacks have run.
   * Must-complete and unsafe owners remain blockers and are never interrupted by this method.
   */
  default OperationLeaseSnapshot requestCancellation(String preparationId) {
    return snapshot();
  }

  /** Release a barrier only when the caller presents its opaque preparation id. */
  void releaseAdmission(String preparationId);
}
