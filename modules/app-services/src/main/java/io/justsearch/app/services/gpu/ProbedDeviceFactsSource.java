/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.gpu;

import io.justsearch.gpu.memory.GpuAdapters;
import io.justsearch.ort.GpuDriverApiProbe;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The production {@link DeviceFactsSource}: the CUDA driver probe (devices by ordinal, with LUID,
 * UUID, PCI bus id and total memory) and DXGI's adapter list, probed once on first use and kept for
 * the life of the process.
 */
final class ProbedDeviceFactsSource implements DeviceFactsSource {
  private static final Logger LOG = LoggerFactory.getLogger(ProbedDeviceFactsSource.class);

  static final ProbedDeviceFactsSource PROCESS = new ProbedDeviceFactsSource();

  private DeviceFacts facts;

  private ProbedDeviceFactsSource() {}

  @Override
  public synchronized DeviceFacts facts() {
    if (facts == null) facts = probe();
    return facts;
  }

  private static DeviceFacts probe() {
    GpuDriverApiProbe.Result result;
    try {
      result = GpuDriverApiProbe.probe();
    } catch (RuntimeException | LinkageError e) {
      LOG.warn("The CUDA device probe threw; no GPU device is pinned", e);
      return new DeviceFacts(
          DeviceFacts.ProbeStatus.THREW, 0, 0, List.of(), java.util.Optional.empty());
    }
    List<GpuAdapters.Adapter> adapters = List.of();
    if (result.status() == GpuDriverApiProbe.Status.OK) {
      try {
        adapters = GpuAdapters.list();
      } catch (Exception | LinkageError e) {
        LOG.debug("DXGI adapter listing failed; pinning by CUDA facts alone", e);
      }
    }
    DeviceFacts facts = DevicePinning.facts(result, adapters);
    if (facts.status() == DeviceFacts.ProbeStatus.RAN_OK && facts.pinned().isEmpty()) {
      LOG.warn(
          "{} CUDA devices and none maps to a DXGI adapter by LUID; no GPU device is pinned, so"
              + " llama-server and the encoders keep their default device choice",
          facts.devices().size());
    } else {
      LOG.info("GPU device facts: status={} code={} driverVersion={} devices={} pinned={}",
          facts.status(), facts.code(), facts.driverVersion(), facts.devices(), facts.pinned());
    }
    if (facts.pinned().isPresent() && !facts.driverRunsLlamaRuntime()) {
      LOG.warn(
          "The CUDA driver supports CUDA {}.{}, below the llama-server runtime's CUDA {}; llama-server"
              + " is launched unpinned (no CUDA_VISIBLE_DEVICES, no --device), as before device"
              + " pinning",
          facts.driverVersion() / 1000,
          (facts.driverVersion() % 1000) / 10,
          DeviceFacts.LLAMA_RUNTIME_CUDA_MAJOR);
    }
    return facts;
  }
}
