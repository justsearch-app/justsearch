/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LifecycleStateTest {
  @Test
  void aCancelledCheckpointCannotReappearAsReadyForAnotherModelCall() {
    var restored = LifecycleState.parse("CANCELLED");
    assertEquals(LifecycleState.CANCELLED, restored);
    assertTrue(restored.isTerminal());
  }
}
