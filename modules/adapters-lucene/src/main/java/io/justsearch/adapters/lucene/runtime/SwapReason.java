/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import java.util.Map;
import java.util.Objects;

/**
 * Bounded set of attribution values for swap/drain telemetry events
 * ({@code index.runtime.swap_started_total}, {@code index.runtime.swap_duration_ms}).
 * Tempdoc 417 Phase 1: replaces free-form strings on {@link RunningRuntime#drainAndClose} and
 * {@code KnowledgeServer.swapRuntime} with a compile-time-bounded enum.
 */
public enum SwapReason {
  ADMIN_TRIGGERED("admin_triggered"),
  CONFIG_RELOAD("config_reload"),
  BLUE_GREEN_CUTOVER("blue_green_cutover"),
  DEFERRED_UPGRADE("deferred_upgrade"),
  UNKNOWN("unknown");

  private static final Map<String, SwapReason> BY_WIRE;

  static {
    java.util.HashMap<String, SwapReason> m = new java.util.HashMap<>();
    for (SwapReason r : values()) {
      m.put(r.wireValue, r);
    }
    BY_WIRE = Map.copyOf(m);
  }

  private final String wireValue;

  SwapReason(String wireValue) {
    this.wireValue = Objects.requireNonNull(wireValue, "wireValue");
  }

  public String wireValue() {
    return wireValue;
  }

  /**
   * Parses a wire-format string back to a typed reason. Unknown values map to {@link #UNKNOWN};
   * the port/HTTP {@code reloadRuntime} handler uses this to defensively accept legacy or
   * caller-typo'd inputs without crashing.
   */
  public static SwapReason fromWire(String wire) {
    if (wire == null || wire.isBlank()) return UNKNOWN;
    SwapReason r = BY_WIRE.get(wire);
    return r != null ? r : UNKNOWN;
  }
}
