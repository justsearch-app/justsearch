/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Enterprise policy file model for {@code policy.v1.json}.
 *
 * <p>This is intentionally permissive (unknown fields ignored) so we can evolve the schema without
 * breaking older binaries.
 *
 * <p>Moved from {@code io.justsearch.ui.policy} to {@code app-api} as part of tempdoc 519 §9
 * module-boundary inversion. The interface {@link EnterprisePolicyService} returns
 * {@link EffectivePolicy} which references this type via {@code PolicySource.parsed}, so this DTO
 * must live in {@code app-api} for the interface to be reachable from {@code app-services}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class EnterprisePolicy {
  public int schemaVersion = 0;
  public String updatedAt;

  public Boolean appUpdatesEnabled;

  public Boolean downloadsEnabled;
  public Boolean onlineAiEnabled;
  public Boolean gpuAccelerationEnabled;
  public Boolean disallowExternalInferenceServers;

  public Allowlists allowlists;

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Allowlists {
    public List<String> modelSha256;
    public List<String> packManifestSha256;
  }
}
