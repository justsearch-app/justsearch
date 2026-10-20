/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status.operating;

import io.justsearch.agent.api.registry.Nullable;
import io.justsearch.agent.api.registry.PreciseWire;
import java.util.List;
import java.util.Objects;

/**
 * The {@code operating.indexing} block. {@code reasons} is ordered by {@link IndexingReason}
 * precedence; its first entry is the primary reason. It is listed even when {@code state} is IDLE,
 * so readers can describe the hold even when the file queue is empty. Automatic PAUSED reasons
 * describe enrichment/GPU bulk holds, not a stop of text indexing. Energy saver defers idle
 * CPU/GPU enrichment; sparse work interleaved between files may continue. Game/external GPU
 * holds defer GPU-bound idle cycles and all interleaved sparse work, including CPU sparse work;
 * CPU-only idle cycles may continue. The state vocabulary and scheduling are unchanged.
 */
public record OperatingIndexingView(
    IndexingState state,
    List<IndexingReason> reasons,
    @Nullable String reasonCode,
    String since,
    List<BulkWorkKind> affects,
    long pendingJobs,
    @Nullable String agingReleaseAt,
    String sampledAt)
    implements PreciseWire {

  public OperatingIndexingView {
    Objects.requireNonNull(state, "state");
    reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
    Objects.requireNonNull(since, "since");
    affects = List.copyOf(Objects.requireNonNull(affects, "affects"));
    Objects.requireNonNull(sampledAt, "sampledAt");
    if (pendingJobs < 0) throw new IllegalArgumentException("pendingJobs must be non-negative");
    for (int i = 1; i < reasons.size(); i++) {
      if (reasons.get(i - 1).compareTo(reasons.get(i)) >= 0) {
        throw new IllegalArgumentException("reasons must be distinct and in precedence order");
      }
    }
    if ((state == IndexingState.PAUSED || state == IndexingState.SLOWED) && reasons.isEmpty()) {
      throw new IllegalArgumentException(state + " names at least one reason");
    }
  }
}
