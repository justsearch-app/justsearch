/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status.operating;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.justsearch.agent.api.registry.PreciseWire;
import java.util.List;
import java.util.Objects;

/**
 * The {@code operating} block of {@code /api/status} (schema version 1): chat, indexing and GPU
 * state, assembled in the same sampler pass as the rest of the response.
 *
 * <p>{@code chat} is absent while the Engine is leaving: lifecycle speaks then. {@code indexing} is
 * absent until the Worker has been sampled. {@code seq} grows by one whenever any field other than
 * the sample times changes, so a consumer can detect a missed transition.
 */
public record OperatingStateView(
    int schemaVersion,
    long seq,
    String assembledAt,
    @JsonInclude(JsonInclude.Include.NON_NULL) OperatingChatView chat,
    @JsonInclude(JsonInclude.Include.NON_NULL) OperatingIndexingView indexing,
    OperatingGpuView gpu,
    List<OperatingDegradationView> degradations,
    OperatingFreshnessView freshness)
    implements PreciseWire {

  public static final int SCHEMA_VERSION = 1;

  public OperatingStateView {
    if (schemaVersion != SCHEMA_VERSION) {
      throw new IllegalArgumentException("schemaVersion must be " + SCHEMA_VERSION);
    }
    if (seq < 1) throw new IllegalArgumentException("seq starts at 1");
    Objects.requireNonNull(assembledAt, "assembledAt");
    Objects.requireNonNull(gpu, "gpu");
    degradations = List.copyOf(Objects.requireNonNull(degradations, "degradations"));
    Objects.requireNonNull(freshness, "freshness");
  }
}
