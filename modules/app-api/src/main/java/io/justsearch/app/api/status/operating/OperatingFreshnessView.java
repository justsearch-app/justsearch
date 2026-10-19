/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status.operating;

import io.justsearch.agent.api.registry.PreciseWire;
import java.util.List;
import java.util.Objects;

/**
 * How fresh the {@code operating} blocks are. A block listed in {@code staleSources} keeps its last
 * value; consumers word it as unknown.
 *
 * <p>A block is listed by the status lanes it reads, each judged on its own. A lane is stale when
 * its last observation failed, when its data was already more than three periods old when it was
 * published, or when its last real observation is older than three of its effective cadences. A
 * lane's effective cadence is its sampling period plus the largest of its recent data ages at
 * publish, counted only up to three periods. So a lane goes stale between 3 and 12 of its periods
 * after its last real observation: 6 to 24 s on the busy 2 s period, 30 to 120 s on the idle 10 s
 * period.
 *
 * <p>Two consequences are accepted. A hung lane is reported stale only after 3 of its own update
 * intervals, so detection is later when idle, about 30 to 120 s; this is the cost of no longer
 * flipping current values to unknown under load. When a fresh read misses its deadline, it shows
 * the last published value labelled current, rather than unknown, so right after an action it can
 * briefly show the pre-action value.
 *
 * @param staleAfterMs three times the sampler period in effect when the block was assembled:
 *     6000 ms busy, 30000 ms idle, and 30000 ms before the first sample. It is the shortest limit a
 *     lane on that period can have, not the point at which a block is listed: a lane whose data
 *     arrives late is listed only after three of its effective cadences, up to four times this
 *     value.
 */
public record OperatingFreshnessView(long staleAfterMs, List<OperatingBlock> staleSources)
    implements PreciseWire {

  public OperatingFreshnessView {
    if (staleAfterMs <= 0) throw new IllegalArgumentException("staleAfterMs must be positive");
    staleSources = List.copyOf(Objects.requireNonNull(staleSources, "staleSources"));
  }
}
