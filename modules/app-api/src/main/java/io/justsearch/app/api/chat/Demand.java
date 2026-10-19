/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.chat;

import io.justsearch.core.context.EngineContext;
import java.time.Duration;
import java.util.Objects;

/** One request for a generation lease. {@code maxWait} bounds how long admission may wait. */
public record Demand(DemandKind kind, EngineContext context, Duration maxWait) {
  public Demand {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(maxWait, "maxWait");
    if (maxWait.isNegative()) throw new IllegalArgumentException("maxWait must not be negative");
  }
}
