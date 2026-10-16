/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status.operating;

import io.justsearch.agent.api.registry.Nullable;
import io.justsearch.agent.api.registry.PreciseWire;
import java.util.Objects;

/** Progress of a loading chat server. No percentage: the server reports none. */
public record ChatLoadView(
    LoadPhase phase,
    String startedAt,
    long elapsedMs,
    @Nullable Long expectedMs,
    @Nullable LoadEstimateSource expectedSource)
    implements PreciseWire {

  public ChatLoadView {
    Objects.requireNonNull(phase, "phase");
    Objects.requireNonNull(startedAt, "startedAt");
    if (elapsedMs < 0) throw new IllegalArgumentException("elapsedMs must be non-negative");
    if ((expectedMs == null) != (expectedSource == null)) {
      throw new IllegalArgumentException("expectedMs and expectedSource come together");
    }
  }
}
