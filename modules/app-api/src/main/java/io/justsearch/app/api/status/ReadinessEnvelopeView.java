/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

import java.util.Map;

/**
 * Full readiness envelope for the /api/status endpoint, containing per-dimension components and
 * composites derived from those components.
 *
 * <p>Stability: stable (API contract)
 */
public record ReadinessEnvelopeView(
    int schemaVersion,
    String observedAt,
    Map<String, EngineComponentView> engineComponents,
    Map<String, ReadinessComponentView> components,
    Map<String, ReadinessCompositeView> composites) {

  public ReadinessEnvelopeView {
    observedAt = observedAt == null ? "" : observedAt;
    engineComponents = engineComponents == null ? Map.of() : Map.copyOf(engineComponents);
    components = components == null ? Map.of() : Map.copyOf(components);
    composites = composites == null ? Map.of() : Map.copyOf(composites);
  }
}
