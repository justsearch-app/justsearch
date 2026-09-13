/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import io.justsearch.configuration.model.ModelRegistry;

/**
 * Helper service that orchestrates AI model installation (downloads, verification,
 * apply-settings). Composed by {@link BrainInstallService} implementations.
 *
 * <p>Interface added as part of tempdoc 519 §9 Block B2. The concrete implementation is {@code
 * io.justsearch.app.services.ai.install.AiInstallService} — the same simple name in a different
 * package, so an import here is load-bearing. Consumers (including the {@code modules/ui} HTTP
 * controller) hold this interface; only the composition root names the impl.
 *
 * <p>Stability: stable (API contract).
 */
public interface AiInstallService {

  /** Exact owner snapshots: initial status plus terminal status after actual cleanup. */
  record Attempt(AiInstallStatus started,
      java.util.concurrent.CompletionStage<AiInstallStatus> completion) {}

  /**
   * Return the parsed model registry manifest. Used by the install flow to plan downloads
   * and by callers needing to surface available models.
   */
  ModelRegistry getManifest();

  /**
   * Return the current install status as an independent deep copy, taken under the implementation's
   * own lock. Nothing the install run does afterwards touches the returned object, so a caller may
   * serialize or read it with no lock of its own — which is what the HTTP status handler does at 1 Hz
   * while the run keeps mutating its live state.
   */
  AiInstallStatus getStatus();

  /**
   * Whether an install run is in flight — the one bit, without the cost of {@link #getStatus()}.
   *
   * <p>Separate from the status read because that read does real work (a boot-time disk recompute,
   * a staleness reap, and since tempdoc 824 §3.3c a runtime-observation projection that can issue
   * Worker RPCs on a cache miss). Callers that only need "is an install running" — notably
   * {@code RuntimeActivationService}'s per-directory leftover-variant probe — must not pay for it,
   * and must not re-enter the service the projection reads.
   *
   * <p>The default derives the bit from {@link #getStatus()} so an implementation without a cheaper
   * answer stays correct; the production implementation overrides it with a field read.
   */
  default boolean isInstallRunning() {
    AiInstallStatus status = getStatus();
    return status != null && "running".equals(status.state);
  }

  /**
   * Compute a side-effect-free preview of the download plan grouped by capability tier (tempdoc
   * 657), for the current hardware + install intent. Runs no downloads; drives the pre-install
   * honest weight breakdown in the UI.
   */
  InstallPlanPreview previewInstallPlan();

  /**
   * Start the install flow. Refuse if already running; completion follows actual owner cleanup. Throws {@link AiInstallException}
   * on validation failures (e.g., terms not accepted, policy disallows).
   */
  Attempt startInstall(boolean acceptTerms);

  /** Request cancellation of an in-flight install. */
  void cancel();

  /**
   * Repair an installed AI runtime. <b>Repair is start</b>: this re-derives the whole install plan
   * for the current hardware and re-runs it, so it requires {@code acceptTerms} exactly like a first
   * install and re-downloads anything the plan still considers missing. It is NOT a narrow
   * re-verify-hashes-and-re-apply-settings pass, and it is not scoped to the component that is
   * actually broken — per-component repair is future work (tempdoc 840).
   *
   * <p>Throws {@link AiInstallException} on validation failures.
   */
  Attempt repair(boolean acceptTerms);

  /**
   * Halt an in-flight install before its next file, keeping the run, its op-lease and its place in
   * the set. Not terminal — {@link #cancel()} is.
   *
   * @throws AiInstallException 409 {@code INSTALL_NOT_RUNNING} when no run is in flight. The guard
   *     lives on the contract rather than in the HTTP handler because the pause gate outlives any
   *     one run: arming it with nothing running would halt the NEXT install before its first byte,
   *     with nothing on the wire to say why.
   */
  void pauseInstall();

  /**
   * Continue a paused install at its next file.
   *
   * @throws AiInstallException 409 {@code INSTALL_NOT_RUNNING} when no run is in flight
   */
  void resumeInstall();

  /**
   * Record — or withdraw — the user's decision not to install one registry package, as a durable
   * preference ({@code UiSettings.declinedAiPackages}) that survives runs which never complete.
   *
   * <p>{@code declined = false} is the withdrawal and is always allowed: "install this after all"
   * can never be an invalid request. {@code declined = true} is refused for a package whose {@code
   * Necessity} is not user-declinable — {@code Necessity.userDeclinable()} is the authority, and a
   * request the product cannot honour must fail loudly rather than be dropped on the floor.
   *
   * @throws AiInstallException 400 {@code INVALID_REQUEST} for a blank id; 404 {@code
   *     PACKAGE_NOT_FOUND} when the registry declares no such package; 400 {@code
   *     PACKAGE_NOT_DECLINABLE} when declining a {@code required} / {@code infrastructure} package
   */
  void setPackageDeclined(String packageId, boolean declined);
}
