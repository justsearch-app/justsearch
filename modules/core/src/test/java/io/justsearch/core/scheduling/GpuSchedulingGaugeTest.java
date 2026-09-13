/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Item A5. Pins the composition rule the gauge inherits verbatim from
 * {@code WorkerSignalBus.shouldYieldGpuBackfill()} (tempdoc 630) and the conservative defaults, so
 * folding two MMF bytes into one in-process holder cannot change what the pacing policy sees.
 */
final class GpuSchedulingGaugeTest {

  @Test
  @DisplayName("both signals default to false — an unwritten gauge never throttles")
  void defaultsAreConservative() {
    GpuSchedulingGauge gauge = new GpuSchedulingGauge();
    assertFalse(gauge.isMainGpuActive());
    assertFalse(gauge.isEnergyReduced());
    assertFalse(gauge.shouldYieldGpuBackfill());
  }

  @Test
  @DisplayName("shouldYieldGpuBackfill = mainGpuActive OR energyReduced (all four combinations)")
  void yieldComposition() {
    assertFalse(gauge(false, false).shouldYieldGpuBackfill());
    assertTrue(gauge(true, false).shouldYieldGpuBackfill(), "GPU claimed => yield");
    assertTrue(gauge(false, true).shouldYieldGpuBackfill(), "energy reduced => yield");
    assertTrue(gauge(true, true).shouldYieldGpuBackfill());
  }

  @Test
  @DisplayName("the static rule and the instance method are the same rule")
  void staticRuleMatchesInstanceMethod() {
    for (boolean gpu : new boolean[] {false, true}) {
      for (boolean energy : new boolean[] {false, true}) {
        assertEquals(
            GpuSchedulingGauge.shouldYield(gpu, energy),
            gauge(gpu, energy).shouldYieldGpuBackfill(),
            "gpu=" + gpu + " energy=" + energy);
      }
    }
  }

  @Test
  @DisplayName("the two signals are independent — clearing one does not clear the other")
  void signalsAreIndependent() {
    GpuSchedulingGauge gauge = gauge(true, true);

    gauge.setMainGpuActive(false);
    assertFalse(gauge.isMainGpuActive());
    assertTrue(gauge.isEnergyReduced(), "the energy reason must survive the GPU reason clearing");
    assertTrue(gauge.shouldYieldGpuBackfill());

    gauge.setEnergyReduced(false);
    assertFalse(gauge.shouldYieldGpuBackfill());

    gauge.setMainGpuActive(true);
    assertTrue(gauge.shouldYieldGpuBackfill());
    assertFalse(gauge.isEnergyReduced());
  }

  @Test
  @DisplayName("toString names both signals (the gauge is read in log lines and status payloads)")
  void toStringNamesBothSignals() {
    assertEquals(
        "GpuSchedulingGauge[mainGpuActive=true, energyReduced=false]", gauge(true, false).toString());
  }

  private static GpuSchedulingGauge gauge(boolean mainGpuActive, boolean energyReduced) {
    GpuSchedulingGauge gauge = new GpuSchedulingGauge();
    gauge.setMainGpuActive(mainGpuActive);
    gauge.setEnergyReduced(energyReduced);
    return gauge;
  }
}
