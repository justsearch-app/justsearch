/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.app.api.NativeQuiescence;
import io.justsearch.app.engine.EngineShutdownSequence.Step;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec A §7.3 and reconciliation F10: the Engine's restart after a lost GPU records a {@code
 * DEVICE_LOST} self-intent expecting the requested-restart code, closes in restart order and exits
 * 4, so the supervisor restarts it and the next incarnation counts the restart.
 */
final class EngineShutdownDeviceLostTest {

  private final List<String> events = Collections.synchronizedList(new ArrayList<>());
  private final AtomicInteger code = new AtomicInteger(-1);

  private EngineShutdownSequence sequence(Path dataDir, boolean cleanClose) {
    List<Step> steps = List.of(new Step(EngineShutdownSequence.INDEX_HALF_STEP, reason -> {
      events.add("close " + reason.wire());
      if (!cleanClose) throw new IOException("index close refused");
      return "GRACEFUL";
    }));
    return new EngineShutdownSequence(dataDir, steps, code::set, code::set,
        () -> NativeQuiescence.QUIESCED, preliminary -> {},
        (reason, detail, expected) -> events.add("intent " + reason + " "
            + detail.map(Object::toString).orElse("-") + " " + expected));
  }

  @Test
  void failedDeviceLossReservationDoesNotCloseOrRequestRestart(@TempDir Path dataDir) {
    var sequence = new EngineShutdownSequence(dataDir,
        List.of(new Step("owner", reason -> { events.add("closed"); return null; })),
        code::set, code::set, () -> NativeQuiescence.QUIESCED, result -> {},
        (reason, detail, expected) -> { throw new IOException("ledger unavailable"); });
    try { sequence.runAndExitDeviceLost(); } catch (IllegalStateException refused) { /* refused */ }
    assertEquals(-1, code.get(), "a restart without a durable reservation bypasses the limit");
    assertEquals(List.of(), events, "reservation precedes shutting down owners");
  }

  @Test
  void aDeviceLostRestartRecordsItsIntentClosesInRestartOrderAndExitsFour(@TempDir Path dataDir) {
    var sequence = sequence(dataDir, true);

    sequence.runAndExitDeviceLost();

    assertEquals(List.of("intent DEVICE_LOST - 4", "close restart"), events);
    assertEquals(EngineExit.REQUESTED_RESTART, code.get());
  }

  @Test
  void anUncleanCloseExitsFatalInstead(@TempDir Path dataDir) {
    var sequence = sequence(dataDir, false);

    sequence.runAndExitDeviceLost();

    assertEquals(List.of("intent DEVICE_LOST - 4", "close restart"), events);
    assertEquals(EngineExit.FATAL_OR_UNCAUGHT, code.get());
  }

  @Test
  void aSecondExitRequestAddsNothing(@TempDir Path dataDir) {
    var sequence = sequence(dataDir, true);

    sequence.runAndExitDeviceLost();
    sequence.runAndExitDeviceLost();
    sequence.runAndExitEscalated();

    assertEquals(List.of("intent DEVICE_LOST - 4", "close restart"), events);
    assertEquals(EngineExit.REQUESTED_RESTART, code.get());
  }

  @Test
  void theIntentIsTheOneTheSupervisorExpectsForDeviceLost() {
    var intent = EngineShutdownSequence.deviceLostSelfIntent();
    assertEquals("DEVICE_LOST", intent.reason().name());
    assertEquals(java.util.Optional.empty(), intent.detail());
    assertEquals(4, intent.expectedCode());
  }
}
