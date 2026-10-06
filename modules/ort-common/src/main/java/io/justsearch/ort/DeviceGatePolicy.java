/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

/**
 * The device gate's parameters (Spec D §0). The values are provisional; calibration (Spec D step
 * 11) replaces them from the measurement files and records the source beside each.
 *
 * @param seats GPU runs one device allows at once ({@code deviceSeats}); a session never holds two
 * @param agingSeatings foreground seatings after which the oldest background waiter is seated next
 *     ({@code agingSeatings}, design.md lane F §4 "a count of foreground seatings")
 * @param agingWallMs wait after which the oldest background waiter is seated next ({@code
 *     agingWallMs}, design.md lane F §4 "or a wall-clock bound")
 * @param recompositionWaitMs longest wait of a GPU release for its session's in-flight runs ({@code
 *     recompositionWaitMs}, equal to {@code stuckAfterMs}); past it the session is retired to the
 *     run that holds it (Spec D §2.3)
 * @param stuckAfterMs how long a GPU seat that is not its session's first run may be held before
 *     the device's breaker opens ({@code stuckAfterMs}, Spec D §2.1)
 * @param breakerStandingMs how long the breaker stays open before the standing lifecycle code is
 *     shown ({@code breakerStandingMs}, Spec D §6.3)
 */
public record DeviceGatePolicy(
    int seats,
    int agingSeatings,
    long agingWallMs,
    long recompositionWaitMs,
    long stuckAfterMs,
    long breakerStandingMs) {

  /** The provisional values of Spec D §0. */
  public static final DeviceGatePolicy DEFAULTS =
      new DeviceGatePolicy(1, 4, 2_000L, 10_000L, 10_000L, 5_000L);

  public DeviceGatePolicy {
    if (seats < 1) throw new IllegalArgumentException("seats must be at least 1");
    if (agingSeatings < 1) throw new IllegalArgumentException("agingSeatings must be at least 1");
    if (agingWallMs < 1) throw new IllegalArgumentException("agingWallMs must be positive");
    if (recompositionWaitMs < 1) {
      throw new IllegalArgumentException("recompositionWaitMs must be positive");
    }
    if (stuckAfterMs < 1) throw new IllegalArgumentException("stuckAfterMs must be positive");
    if (breakerStandingMs < 0) {
      throw new IllegalArgumentException("breakerStandingMs must not be negative");
    }
  }
}
