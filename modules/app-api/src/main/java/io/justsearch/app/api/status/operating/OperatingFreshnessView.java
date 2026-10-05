/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status.operating;

import io.justsearch.agent.api.registry.PreciseWire;
import java.util.List;
import java.util.Objects;

/**
 * How fresh the {@code operating} blocks are. A block listed in {@code staleSources} keeps its last
 * value; consumers word it as unknown.
 *
 * @param staleAfterMs three times the current sampler period
 */
public record OperatingFreshnessView(long staleAfterMs, List<OperatingBlock> staleSources)
    implements PreciseWire {

  public OperatingFreshnessView {
    if (staleAfterMs <= 0) throw new IllegalArgumentException("staleAfterMs must be positive");
    staleSources = List.copyOf(Objects.requireNonNull(staleSources, "staleSources"));
  }
}
