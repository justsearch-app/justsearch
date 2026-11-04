/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

final class HeavyIndexingStateTest {
  @Test
  void thresholdsAndOverlappingBacklogsUseHysteresis() {
    var state = new HeavyIndexingState();
    assertEquals(50, HeavyIndexingState.ENTER_BACKLOG);
    assertEquals(10, HeavyIndexingState.EXIT_BACKLOG);
    assertFalse(state.update(true, 49, 49));
    assertTrue(state.update(true, 50, 0));
    assertTrue(state.update(true, 10, 0));
    assertTrue(state.update(true, 9, 50));
    assertTrue(state.update(true, 0, 10));
    assertFalse(state.update(true, 9, 9));
    assertFalse(state.update(true, 10, 10));
    assertTrue(state.update(true, 0, 50));
    assertTrue(state.update(true, 50, 9));
    assertFalse(state.update(true, 0, 0));
  }

  @Test
  void holdAndRestartRecomputeAndSingleFileUpdatesNeverEnter() {
    var state = new HeavyIndexingState();
    assertTrue(state.update(true, 100, 100));
    assertFalse(state.update(false, 100, 100));
    assertFalse(state.update(true, 20, 20));
    assertTrue(state.update(true, 100, 0));
    var restarted = new HeavyIndexingState();
    assertFalse(restarted.update(true, 20, 20));
    assertTrue(restarted.update(true, 0, 100));
    assertFalse(restarted.update(true, 1, 1));
    for (int i = 0; i < 100; i++) assertFalse(restarted.update(true, 1, 1));
  }
}
