/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import java.util.Set;

/** Head-owned global VDU capability state for status/readiness projection. */
public final class VduCapabilityState {
  public static final String REASON_AI_OFFLINE = "vdu.ai_offline";
  public static final String REASON_INSUFFICIENT_VRAM = "vdu.insufficient_vram";
  public static final String REASON_MISSING_MMPROJ = "vdu.missing_mmproj";
  public static final String REASON_CIRCUIT_OPEN = "vdu.circuit_open";
  /** Automatic picture reading is paused: repeated automatic passes had every attempt refused. */
  public static final String REASON_BREAKER_OPEN_REFUSED = "vdu.breaker_open.refused";
  /** Automatic picture reading is paused: repeated automatic passes could not enter vision mode. */
  public static final String REASON_BREAKER_OPEN_ENTRY_FAILED = "vdu.breaker_open.entry_failed";

  private static final Set<String> KNOWN_REASONS =
      Set.of(REASON_AI_OFFLINE, REASON_INSUFFICIENT_VRAM, REASON_MISSING_MMPROJ, REASON_CIRCUIT_OPEN,
          REASON_BREAKER_OPEN_REFUSED, REASON_BREAKER_OPEN_ENTRY_FAILED);

  private volatile Snapshot snapshot = Snapshot.empty();

  public Snapshot snapshot() {
    return snapshot;
  }

  public void block(String reasonCode) {
    block(reasonCode, null);
  }

  /** Blocks with a human-readable detail (for example the refusal the pass saw). */
  public void block(String reasonCode, String detail) {
    if (reasonCode == null || !KNOWN_REASONS.contains(reasonCode)) {
      return;
    }
    snapshot = new Snapshot(reasonCode, System.currentTimeMillis(), detail);
  }

  public void clear(String reasonCode) {
    Snapshot current = snapshot;
    if (reasonCode != null && reasonCode.equals(current.blockedReason())) {
      snapshot = Snapshot.empty();
    }
  }

  public void clearAll() {
    snapshot = Snapshot.empty();
  }

  /** @param detail optional human-readable cause; {@code null} when none was recorded */
  public record Snapshot(String blockedReason, long updatedAtMs, String detail) {
    public Snapshot(String blockedReason, long updatedAtMs) {
      this(blockedReason, updatedAtMs, null);
    }

    public static Snapshot empty() {
      return new Snapshot(null, 0L, null);
    }
  }
}
