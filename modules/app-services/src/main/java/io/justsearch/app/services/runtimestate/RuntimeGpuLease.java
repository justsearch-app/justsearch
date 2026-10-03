/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.runtimestate;

import io.justsearch.app.api.Mode;
import java.util.OptionalLong;

/**
 * Engine GPU lease state. Models chat and indexing work as holders of a single GPU grant:
 * {@code ONLINE ≡ CHAT}, {@code INDEXING ≡ INDEXING}, {@code OFFLINE ≡ NONE}.
 *
 * <p>This is a passive mirror driven by {@link #mirrorFromMode(Mode)}. Scheduling uses shared
 * in-process gauges; this class does not write a cross-process signal or arbitrate admission.
 * {@code TRANSITIONING} keeps the previous holder until the mode change commits.
 *
 * <p>{@link #requestGrant(Holder, OptionalLong)} implements binary grant logic only.
 * {@code sizeBytes} is accepted and ignored; sized admission remains future work.
 */
public final class RuntimeGpuLease {

  /** Who currently holds the GPU grant. */
  public enum Holder {
    CHAT,
    INDEXING,
    NONE
  }

  /** Outcome of a grant request. {@code sizeBytes} is echoed for the future sized path. */
  public record Grant(boolean granted, Holder holder, OptionalLong sizeBytes, String reason) {}

  private volatile Holder holder = Holder.NONE;

  public Holder holder() {
    return holder;
  }

  /**
   * Binary grant logic (Phase 1): granted iff the lease is free ({@code NONE}) or already held by
   * the requester. {@code sizeBytes} is accepted for forward-compatibility and IGNORED — sized
   * co-residency admission is future work (§12a / P4).
   */
  public Grant requestGrant(Holder requester, OptionalLong sizeBytes) {
    if (requester == null || requester == Holder.NONE) {
      return new Grant(false, holder, sizeBytes, "invalid-requester");
    }
    Holder current = holder;
    if (current == Holder.NONE || current == requester) {
      return new Grant(true, requester, sizeBytes, "granted");
    }
    return new Grant(false, current, sizeBytes, "held-by-" + current);
  }

  /**
   * Passive mirror: derive the holder from the observed engine mode. {@code TRANSITIONING} leaves
   * the holder unchanged (mid-swap — do not flip the lease until the transition commits).
   */
  public void mirrorFromMode(Mode mode) {
    if (mode == null) {
      return;
    }
    switch (mode) {
      case ONLINE -> holder = Holder.CHAT;
      case INDEXING -> holder = Holder.INDEXING;
      case OFFLINE -> holder = Holder.NONE;
      case TRANSITIONING -> {
        /* keep last holder mid-swap */
      }
    }
  }
}
