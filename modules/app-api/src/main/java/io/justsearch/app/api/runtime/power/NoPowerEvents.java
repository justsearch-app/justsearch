/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.power;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/** No power notifications: off Windows, or where registration failed (with the reason). */
public final class NoPowerEvents implements SystemPowerEvents {
  private static final NoPowerEvents NOT_APPLICABLE = new NoPowerEvents(Optional.empty());

  private final Optional<String> unavailableReason;

  private NoPowerEvents(Optional<String> unavailableReason) {
    this.unavailableReason = unavailableReason;
  }

  /** A platform with no notification source. */
  public static NoPowerEvents notApplicable() {
    return NOT_APPLICABLE;
  }

  /** Windows, where registration failed for {@code reason}. */
  static NoPowerEvents unavailable(String reason) {
    return new NoPowerEvents(Optional.of(Objects.requireNonNull(reason, "reason")));
  }

  /** Why notifications are missing where they were expected; empty off Windows. */
  public Optional<String> unavailableReason() {
    return unavailableReason;
  }

  @Override
  public Subscription subscribe(Consumer<PowerEvent> listener) {
    Objects.requireNonNull(listener, "listener");
    return () -> {};
  }

  @Override
  public long suspendedNanosBetween(long t0, long t1) {
    return 0L;
  }

  @Override
  public String toString() {
    return "NoPowerEvents" + unavailableReason.map(r -> "[" + r + "]").orElse("");
  }
}
