/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class BootRecoveryPolicyTest {

  @Test
  void shippedPolicyAllowsTwoRecoveryAttemptsAfterInitialStart() {
    assertEquals(2, BootRecoveryPolicy.defaults().maxAttempts());
  }

  @Test
  void backoffDoublesAndCapsWithoutOverflow() {
    var policy = new BootRecoveryPolicy(2, 1_000, 4_000);
    assertEquals(1_000, policy.backoffMs(1));
    assertEquals(2_000, policy.backoffMs(2));
    assertEquals(4_000, policy.backoffMs(3));
    assertEquals(4_000, policy.backoffMs(Integer.MAX_VALUE));
  }

  @Test
  void zeroBackoffIsImmediateAndInvalidValuesAreRejected() {
    assertEquals(0, new BootRecoveryPolicy(2, 0, 0).backoffMs(1));
    assertEquals(0, new BootRecoveryPolicy(2, 1, 1).backoffMs(0));
    assertThrows(IllegalArgumentException.class, () -> new BootRecoveryPolicy(-1, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> new BootRecoveryPolicy(2, -1, 0));
    assertThrows(IllegalArgumentException.class, () -> new BootRecoveryPolicy(2, 0, -1));
  }
}
