/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Health check node from the index component.
 *
 * <p>Uses snake_case JSON naming to match the existing /api/health response shape.
 *
 * <p>Stability: stable (API contract)
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record HealthNodeView(
    boolean serving,
    String version,
    long pid,
    String workerState,
    boolean aiReady,
    boolean embeddingReady) {

  public HealthNodeView {
    version = version == null ? "" : version;
    workerState = workerState == null ? "" : workerState;
  }
}
