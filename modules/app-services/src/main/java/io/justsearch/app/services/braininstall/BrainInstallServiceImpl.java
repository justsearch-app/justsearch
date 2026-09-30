/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.braininstall;

import io.justsearch.app.api.AiInstallService;
import io.justsearch.app.api.BrainInstallService;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Production implementation of {@link BrainInstallService}, extracted from
 * {@code AiInstallController} as part of tempdoc 519 §9 Block B3 / Step 3.
 * Composes the B2-extracted {@link AiInstallService} helper interface.
 */
public final class BrainInstallServiceImpl implements BrainInstallService {

  private static final ObjectMapper MAPPER =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  private final AiInstallService helper;

  public BrainInstallServiceImpl(AiInstallService helper) {
    this.helper = Objects.requireNonNull(helper, "helper");
  }

  @Override
  public AiInstallService.Attempt startInstall(boolean acceptTerms) throws Exception {
    return helper.startInstall(acceptTerms);
  }

  @Override
  public Map<String, Object> cancelInstall() throws Exception {
    helper.cancel();
    return statusAsMap();
  }

  @Override
  public AiInstallService.Attempt repairInstall(boolean acceptTerms) throws Exception {
    return helper.repair(acceptTerms);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> statusAsMap() {
    return MAPPER.convertValue(helper.getStatus(), Map.class);
  }
}
