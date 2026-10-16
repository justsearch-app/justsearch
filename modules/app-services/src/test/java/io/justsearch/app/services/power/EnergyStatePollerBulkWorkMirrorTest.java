/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.util.EnergyState;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.core.scheduling.BulkWorkGate;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Spec E step E1: the energy poll is mirrored into the bulk-work gate as status only. The gauge
 * stays the energy actuation (P7: "Energy-saver indexing pause and replacement admission keep
 * their behaviour").
 */
final class EnergyStatePollerBulkWorkMirrorTest {

  private final TestEngineExecutors processExecutors = new TestEngineExecutors();

  @AfterEach
  void closeProcessExecutors() {
    processExecutors.close();
  }

  // each poll mirrors energy into the gate's verdict; the gate never holds for it
  @Test
  void pollMirrorsIntoTheGate() {
    GpuSchedulingGauge gauge = new GpuSchedulingGauge();
    BulkWorkGate gate = new BulkWorkGate();
    AtomicReference<EnergyState> probe =
        new AtomicReference<>(new EnergyState(EnergyState.Intent.REDUCED, EnergyState.Source.BATTERY));

    try (EnergyStatePoller poller = new EnergyStatePoller(processExecutors, gauge, probe::get)) {
      poller.mirrorEnergyTo(gate);
      assertEquals(List.of(), gate.verdict().reasons(), "UNKNOWN before the first poll");

      poller.poll();
      assertEquals(List.of(BulkWorkGate.Reason.ENERGY_SAVER), gate.verdict().reasons());
      assertFalse(gate.held(), "status only");
      assertTrue(gauge.shouldYieldGpuBackfill(), "the gauge still acts on energy, as before");

      probe.set(new EnergyState(EnergyState.Intent.FULL, EnergyState.Source.AC));
      poller.poll();
      assertEquals(List.of(), gate.verdict().reasons());
    }
  }

  // mirroring after a poll publishes the current state at once
  @Test
  void mirrorPublishesTheCurrentState() {
    BulkWorkGate gate = new BulkWorkGate();
    try (EnergyStatePoller poller =
        new EnergyStatePoller(
            processExecutors,
            new GpuSchedulingGauge(),
            () -> new EnergyState(EnergyState.Intent.REDUCED, EnergyState.Source.AC))) {
      poller.poll();
      poller.mirrorEnergyTo(gate);
      assertEquals(List.of(BulkWorkGate.Reason.ENERGY_SAVER), gate.verdict().reasons());
    }
  }
}
