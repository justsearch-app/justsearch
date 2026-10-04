/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.runtimevariant;

import io.justsearch.app.api.RuntimeActivationService;
import io.justsearch.app.api.RuntimeVariantService;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Production implementation of {@link RuntimeVariantService}, extracted from
 * {@code AiRuntimeController} as part of tempdoc 519 §9 Block B3 / Step 3.
 *
 * <p>Composes the {@link RuntimeActivationService} helper interface (defined in {@code app-api}
 * by B2; impl in {@code modules/app-services/.../ai/runtime/RuntimeActivationService.java}), which as of
 * tempdoc 737 (task 3) is also the one authoritative site for admin-policy enforcement
 * ({@link RuntimeActivationService#enforceActivationPolicy()}) — this class no longer holds its
 * own {@code EnterprisePolicyService} copy of that check.
 */
public final class RuntimeVariantServiceImpl implements RuntimeVariantService {

  private static final ObjectMapper MAPPER =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  private final RuntimeActivationService helper;

  public RuntimeVariantServiceImpl(RuntimeActivationService helper) {
    this.helper = Objects.requireNonNull(helper, "helper");
  }

  @Override
  public Attempt activate(String variantId) throws Exception {
    if (variantId == null || variantId.isBlank()) {
      throw new IllegalArgumentException("Missing variantId");
    }
    // Tempdoc 737 (task 3): the policy check itself is no longer duplicated here — it now lives
    // once on RuntimeActivationService.enforceActivationPolicy (also used by
    // AiRuntimeController.handleActivate and this class's helper's own async runActivate), this
    // call is the fast-fail adapter that lets ActivateRuntimeVariantHandler return a synchronous
    // denial instead of forcing a status poll.
    helper.enforceActivationPolicy();
    var snapshot = statusAsMap();
    return attempt(snapshot, helper.startActivate(variantId));
  }

  @Override
  public Attempt deactivate() throws Exception {
    var snapshot = statusAsMap();
    return attempt(snapshot, helper.startDeactivate());
  }

  private static Attempt attempt(Map<String, Object> snapshot, RuntimeActivationService.Attempt owner) {
    // The fallible filesystem/settings/GPU status read happened before start. Only the owner's
    // frozen in-memory scalar DTO is projected afterward; no mutable global status is re-read.
    snapshot.put("activation", MAPPER.convertValue(owner.started(), Map.class));
    return new Attempt(snapshot, owner.completion());
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> statusAsMap() {
    return MAPPER.convertValue(helper.getStatus(), Map.class);
  }
}
