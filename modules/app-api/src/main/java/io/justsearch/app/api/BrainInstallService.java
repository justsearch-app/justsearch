/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import java.util.Map;

/**
 * AI install lifecycle surface exposed to the AppFacade.
 *
 * <p>Slice 3a-2-c continuation (BrainInstall cluster): backs
 * {@code core.start-ai-install}, {@code core.cancel-ai-install}, and
 * {@code core.repair-ai-install} Operations. Production wiring:
 * {@code BrainInstallServiceImpl} is composed by {@code ServicePhase}.
 *
 * <p>Start/repair return the owner's initial snapshot and actual completion; the
 * handler projects the snapshot into the existing REST response shape. Cancellation
 * returns its synchronous control-request status.
 *
 * <p>Stability: stable (API contract).
 */
public interface BrainInstallService {

  /**
   * Begin a fresh AI install. Returns the post-start install status snapshot
   * (mirrors AiInstallStatus). Actual install runs asynchronously; the FE
   * polls /api/ai/install/status for progress.
   *
   * @param acceptTerms whether the user has accepted the install terms
   *     (required for first-time install; the controller / underlying
   *     service enforces).
   * @return frozen initial status and owner completion
   * @throws Exception on install-start failure (typed errors carry codes;
   *     handler surfaces messages)
   */
  AiInstallService.Attempt startInstall(boolean acceptTerms) throws Exception;

  /**
   * Cancel a running install. Idempotent if no install is running. Returns
   * the post-cancel install status snapshot.
   *
   * @return current install status as a serializable map
   * @throws Exception on cancel failure
   */
  Map<String, Object> cancelInstall() throws Exception;

  /**
   * Repair an existing install (re-runs install steps to recover from a
   * partial / corrupted state).
   *
   * @param acceptTerms whether the user has accepted the repair terms
   * @return frozen initial status and owner completion
   * @throws Exception on repair-start failure
   */
  AiInstallService.Attempt repairInstall(boolean acceptTerms) throws Exception;

}
