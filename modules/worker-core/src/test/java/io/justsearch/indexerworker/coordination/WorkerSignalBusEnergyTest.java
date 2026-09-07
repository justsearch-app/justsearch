package io.justsearch.indexerworker.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.core.scheduling.GpuSchedulingGauge;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@link WorkerSignalBus} energy defaults (tempdoc 630): {@code isEnergyReduced} defaults
 * to false (conservative — impls/hosts without the signal never throttle) and {@code
 * shouldYieldGpuBackfill} is the OR of GPU-claimed and energy-reduced.
 */
final class WorkerSignalBusEnergyTest {

  /** Minimal stub overriding only the two inputs to the energy/GPU yield defaults. */
  private static WorkerSignalBus stub(boolean gpuActive, Boolean energyReduced) {
    return new WorkerSignalBus() {
      @Override public void open() {}
      @Override public boolean isMainGpuActive() { return gpuActive; }
      @Override public boolean isEnergyReduced() {
        return energyReduced != null ? energyReduced : WorkerSignalBus.super.isEnergyReduced();
      }
      @Override public long startupTime() { return 0; }
      @Override public void close() throws IOException {}
    };
  }

  @Test
  @DisplayName("isEnergyReduced default is false (conservative)")
  void energyDefaultsFalse() {
    // Pass-through to the interface default.
    assertFalse(stub(false, null).isEnergyReduced());
  }

  @Test
  @DisplayName("shouldYieldGpuBackfill = isMainGpuActive OR isEnergyReduced")
  void yieldComposition() {
    assertFalse(stub(false, false).shouldYieldGpuBackfill());
    assertTrue(stub(true, false).shouldYieldGpuBackfill(), "GPU claimed ⇒ yield");
    assertTrue(stub(false, true).shouldYieldGpuBackfill(), "energy reduced ⇒ yield");
    assertTrue(stub(true, true).shouldYieldGpuBackfill());
  }

  @Test
  @DisplayName("the interface default and the in-process gauge are ONE rule, not two (item A5)")
  void interfaceDefaultAndGaugeAgree() {
    // Item A5 moved the composition into GpuSchedulingGauge and left this default delegating to it,
    // so the wire deletion (A10) cannot leave a second, drifting copy of the rule behind.
    for (boolean gpu : new boolean[] {false, true}) {
      for (boolean energy : new boolean[] {false, true}) {
        GpuSchedulingGauge gauge = new GpuSchedulingGauge();
        gauge.setMainGpuActive(gpu);
        gauge.setEnergyReduced(energy);
        assertEquals(
            gauge.shouldYieldGpuBackfill(),
            stub(gpu, energy).shouldYieldGpuBackfill(),
            "gpu=" + gpu + " energy=" + energy);
      }
    }
  }
}
