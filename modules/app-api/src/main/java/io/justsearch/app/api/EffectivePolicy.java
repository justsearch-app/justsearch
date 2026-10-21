/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import java.nio.file.Path;
import java.util.List;

/**
 * Computed effective policy (machine + user + restrictive env overrides).
 *
 * <p>This is returned via {@code /api/policy/effective} for diagnostics UX.
 *
 * <p>Moved from {@code io.justsearch.ui.policy} to {@code app-api} as part of tempdoc 519 §9
 * module-boundary inversion. {@link EnterprisePolicyService}'s {@code snapshot()} method returns
 * this type, so the DTO must be reachable from {@code app-services}.
 */
public record EffectivePolicy(
    boolean appUpdatesEnabled,
    boolean downloadsEnabled,
    boolean onlineAiEnabled,
    boolean gpuAccelerationEnabled,
    boolean disallowExternalInferenceServers,
    List<String> allowlistedModelSha256,
    List<String> allowlistedPackManifestSha256,
    String packAllowlistSource,
    boolean packAllowlistConfigured,
    PolicySource machine,
    PolicySource user,
    boolean aiDisabledOverride) {

  public record PolicySource(
      Path path,
      boolean present,
      boolean loaded,
      String error,
      EnterprisePolicy parsed) {}

  /**
   * Returns true if a machine policy file exists but failed to load, causing AI features to be
   * disabled. The UI should show a warning banner in this case.
   */
  public boolean machinePolicyLoadFailed() {
    return machine != null && machine.present && !machine.loaded;
  }

  /**
   * Returns the error message if machine policy load failed, or null otherwise.
   */
  public String machinePolicyLoadError() {
    return machinePolicyLoadFailed() ? machine.error : null;
  }
}
