/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status.operating;

import io.justsearch.agent.api.registry.Nullable;
import io.justsearch.agent.api.registry.PreciseWire;
import java.util.Objects;

/**
 * One standing degradation: present while its condition holds. {@code reasonCode} is the lifecycle
 * reason code the UI words.
 */
public record OperatingDegradationView(
    DegradationCode code,
    @Nullable String cause,
    DegradationScope scope,
    String reasonCode,
    String since,
    String lastSeenAt,
    long count)
    implements PreciseWire {

  public OperatingDegradationView {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(reasonCode, "reasonCode");
    Objects.requireNonNull(since, "since");
    Objects.requireNonNull(lastSeenAt, "lastSeenAt");
    if (count < 1) throw new IllegalArgumentException("a listed degradation was seen at least once");
  }
}
