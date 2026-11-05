/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.scheduling;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class IndexingHoldWithdrawnAdmissionTest {
  @Test
  void withdrawnAdmissionCannotGrantALeaseWhileIndexingIsPaused() throws Exception {
    var hold = new IndexingHold();
    hold.pause(null, "USER_PAUSED");
    var observations = new AtomicInteger();
    assertNull(hold.acquire(() -> observations.incrementAndGet() > 1),
        "the first withdrawn admission must not fall through to an acquisition under the pause");
    assertTrue(hold.held());
    assertEquals(IndexingHold.State.PAUSED, hold.snapshot().state());
    assertEquals(0, hold.snapshot().waiting());
  }
}
