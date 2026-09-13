/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.engine.ShutdownRequest.Reason;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Stage B item B5 — which reasons stop llama-server (design 7.3 step 6).
 *
 * <p>Both directions are asserted, not just the memorable one. {@code green-masked-destructive}:
 * a test that only checks "quit stops it" passes just as well against an implementation that stops
 * it for everything — which is precisely what the code did before B5, unconditionally. The
 * assertion that carries the change is that {@code restart} and {@code hang} do NOT.
 */
@DisplayName("shutdown reasons and the generative backend (stage B item B5)")
final class GenerativeBackendByReasonTest {

  @Test
  @DisplayName("quit and upgrade stop it; restart and hang leave it for adoption")
  void reasonsSplitExactlyAsDesignSays() {
    Set<Reason> stops = EnumSet.noneOf(Reason.class);
    Set<Reason> leaves = EnumSet.noneOf(Reason.class);
    for (Reason reason : Reason.values()) {
      (reason.stopsGenerativeBackend() ? stops : leaves).add(reason);
    }

    assertEquals(
        EnumSet.of(Reason.QUIT, Reason.UPGRADE),
        stops,
        "the installer must be able to overwrite llama-server's binary, and nothing may hold VRAM"
            + " behind a closed product");
    assertEquals(
        EnumSet.of(Reason.RESTART, Reason.HANG),
        leaves,
        "THE assertion of this item. Leaving it running is what makes a restart cheap: the model is"
            + " loaded and the VRAM warm, and a restarted Engine that reloads it pays ~40s of"
            + " encoder load for nothing. Before B5 close() stopped it unconditionally, so a test"
            + " asserting only the quit direction would have passed against the defect.");
  }

  @Test
  @DisplayName("each reason individually, so a failure names the reason that regressed")
  void eachReasonIndividually() {
    assertTrue(Reason.QUIT.stopsGenerativeBackend());
    assertTrue(Reason.UPGRADE.stopsGenerativeBackend());
    assertFalse(Reason.RESTART.stopsGenerativeBackend());
    assertFalse(Reason.HANG.stopsGenerativeBackend());
  }
}
