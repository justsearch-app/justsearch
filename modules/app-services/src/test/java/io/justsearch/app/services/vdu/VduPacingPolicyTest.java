/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link VduPacingPolicy} — the Head-side counterpart to {@code LoopPacingPolicy} (tempdoc
 * 630). Mirrors {@code LoopPacingPolicyTest}'s shape: energy always defers regardless of the
 * other signals. {@code shouldInterrupt} deliberately excludes the LLM-online signal (unlike
 * {@code shouldTrigger}) — see its javadoc; a batch keeps the LLM Online for its own duration, so
 * that signal would self-interrupt on the very first checkpoint if included.
 *
 * <p>Spec E step E3 adapted the signatures: both methods take the bulk work gate's hold. Every row
 * here passes {@code bulkHeld = false}, so each oracle is the one it was before; the held rows are
 * {@code VduPacingBulkHoldTest}.
 */
final class VduPacingPolicyTest {

  private static final long IDLE = VduPacingPolicy.DEFAULT_IDLE_THRESHOLD_MS;

  @Test
  @DisplayName("triggers only when idle long enough, not energy-reduced, and LLM not online")
  void triggersOnlyWhenAllConditionsMet() {
    assertTrue(VduPacingPolicy.shouldTrigger(IDLE, false, false, false), "idle + full power + LLM offline should trigger");
    assertFalse(VduPacingPolicy.shouldTrigger(IDLE - 1, false, false, false), "not idle long enough");
    assertFalse(VduPacingPolicy.shouldTrigger(IDLE, true, false, false), "energy-reduced defers regardless of idle");
    assertFalse(VduPacingPolicy.shouldTrigger(IDLE, false, true, false), "LLM already online defers");
    assertFalse(VduPacingPolicy.shouldTrigger(0, true, true, false), "everything blocking at once");
  }

  @Test
  @DisplayName("energy-reduced defers even when very idle (mirrors LoopPacingPolicy's energy-first ordering)")
  void energyDefersRegardlessOfIdleDuration() {
    assertFalse(VduPacingPolicy.shouldTrigger(Long.MAX_VALUE, true, false, false));
  }

  @Test
  @DisplayName("interrupt fires on recent activity or energy-reduced; not when both clear")
  void interruptCoversActivityAndEnergyOnly() {
    assertTrue(VduPacingPolicy.shouldInterrupt(0, false, false), "just active ⇒ interrupt");
    assertTrue(VduPacingPolicy.shouldInterrupt(IDLE, true, false), "energy-reduced ⇒ interrupt");
    assertFalse(VduPacingPolicy.shouldInterrupt(IDLE, false, false), "idle + full power ⇒ no interrupt");
  }

  @Test
  @DisplayName("interrupt does NOT take an llmOnline signal (regression: batch self-interrupted immediately in live verification)")
  void interruptHasNoLlmOnlineParameter() {
    // Compile-time pin: shouldInterrupt(long, boolean, boolean bulkHeld) — its third input is the
    // bulk work gate's hold, never an LLM-online signal, which would silently reintroduce the
    // self-interrupt bug documented in VduPacingPolicy's javadoc.
    assertFalse(VduPacingPolicy.shouldInterrupt(IDLE, false, false));
  }
}
