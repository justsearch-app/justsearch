/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.engine.ShutdownRequest.Reason;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which shutdown reasons stop llama-server (ADR-0051 decision 2, scenario S14).
 *
 * <p>Every reason stops it. No successor Engine adopts a chat server, so a llama-server left
 * running by a restart or a hang would be an orphan holding VRAM and a port that nothing owns. The
 * earlier stage B item B5 split (restart and hang leave it for adoption) is retired with adoption.
 */
@DisplayName("shutdown reasons and the generative backend (ADR-0051 decision 2)")
final class GenerativeBackendByReasonTest {

  /** Every reason stops it; none leaves it for a successor. */
  @Test
  void everyReasonStopsTheGenerativeBackend() {
    Set<Reason> stops = EnumSet.noneOf(Reason.class);
    Set<Reason> leaves = EnumSet.noneOf(Reason.class);
    for (Reason reason : Reason.values()) {
      (reason.stopsGenerativeBackend() ? stops : leaves).add(reason);
    }

    assertEquals(EnumSet.allOf(Reason.class), stops);
    assertEquals(
        EnumSet.noneOf(Reason.class),
        leaves,
        "no successor Engine adopts llama-server, so a reason that leaves it running leaves an"
            + " orphan");
  }

  /** Each reason individually, so a failure names the reason that regressed. */
  @Test
  void eachReasonIndividually() {
    assertTrue(Reason.QUIT.stopsGenerativeBackend());
    assertTrue(Reason.UPGRADE.stopsGenerativeBackend());
    assertTrue(Reason.RESTART.stopsGenerativeBackend());
    assertTrue(Reason.HANG.stopsGenerativeBackend());
  }
}
