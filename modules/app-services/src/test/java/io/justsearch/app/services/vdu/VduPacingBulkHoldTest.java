/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Spec E step E3 (section 1.3 item 6): VDU is GPU bulk work, so the bulk work gate's hold stops a
 * new VDU batch from starting and stops a running one at its next checkpoint.
 */
final class VduPacingBulkHoldTest {

  private static final long IDLE = VduPacingPolicy.DEFAULT_IDLE_THRESHOLD_MS;

  // a held gate defers the VDU trigger even when every other condition allows it
  @Test
  void heldGateDefersTrigger() {
    assertFalse(VduPacingPolicy.shouldTrigger(IDLE, false, false, true));
    assertTrue(VduPacingPolicy.shouldTrigger(IDLE, false, false, false), "the hold clears");
  }

  // a held gate interrupts a running VDU batch while idle and on full power
  @Test
  void heldGateInterruptsBatch() {
    assertTrue(VduPacingPolicy.shouldInterrupt(IDLE, false, true));
    assertFalse(VduPacingPolicy.shouldInterrupt(IDLE, false, false), "the hold clears");
  }
}
