/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.containment;

import java.util.Objects;
import java.util.Optional;

/**
 * No Engine job: {@link Status#NOT_APPLICABLE} off Windows, or {@link Status#UNAVAILABLE} where a
 * job cannot be attempted (a 32-bit JVM) or was never installed in this process.
 */
public final class NoEngineContainment implements EngineContainment {
  private final Status status;
  private final Optional<String> reason;

  private NoEngineContainment(Status status, Optional<String> reason) {
    this.status = status;
    this.reason = reason;
  }

  static NoEngineContainment notApplicable() {
    return new NoEngineContainment(Status.NOT_APPLICABLE, Optional.empty());
  }

  static NoEngineContainment unavailable(String reason) {
    return new NoEngineContainment(Status.UNAVAILABLE, Optional.of(Objects.requireNonNull(reason)));
  }

  @Override
  public Status status() {
    return status;
  }

  @Override
  public Optional<String> unavailableReason() {
    return reason;
  }

  /** No job: there is nothing to be inside. */
  @Override
  public Optional<Boolean> contains(ProcessHandle child) {
    return Optional.empty();
  }

  @Override
  public String toString() {
    return "NoEngineContainment[" + status + reason.map(r -> ", " + r).orElse("") + "]";
  }
}
