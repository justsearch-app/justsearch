/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.gpu;

import io.justsearch.configuration.EnvRegistry;
import io.justsearch.gpu.memory.GpuAdapters;
import io.justsearch.ort.GpuDriverApiProbe;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The device pinning rule of Spec E section 5.2 and its mappings (section 5.3). Pure.
 *
 * <ol>
 *   <li>Among the hardware NVIDIA adapters DXGI lists (so a hybrid laptop's integrated GPU never
 *       counts), those a CUDA device maps to by LUID are the candidates. The one with the largest
 *       dedicated memory wins; ties go to DXGI's enumeration order.
 *   <li>No candidate (a TCC device, a missing LUID, no DXGI): one CUDA device is pinned as ordinal
 *       0's device; several are not pinned (UNRESOLVED).
 * </ol>
 *
 * <p>Explicit choices ({@code justsearch.gpu.device}, agreeing per-role device ids) belong to the
 * explicit keys of step E7. Here a role's explicit device id keeps its value: the pinned ordinal is
 * only the auto-detected default ({@link #ortDeviceDefaults}).
 */
public final class DevicePinning {

  /** The per-role ORT device-id keys that take the pinned ordinal unless set explicitly. */
  static final List<EnvRegistry> ORT_DEVICE_KEYS =
      List.of(
          EnvRegistry.EMBED_GPU_DEVICE_ID,
          EnvRegistry.SPLADE_GPU_DEVICE_ID,
          EnvRegistry.NER_GPU_DEVICE_ID,
          EnvRegistry.BGE_M3_GPU_DEVICE_ID,
          EnvRegistry.RERANK_GPU_DEVICE_ID,
          EnvRegistry.RERANK_CHUNKS_GPU_DEVICE_ID);

  private DevicePinning() {}

  /** The device to pin, or empty when none can be chosen. */
  public static Optional<CudaDevice> choose(
      List<CudaDevice> devices, List<GpuAdapters.Adapter> adapters) {
    if (devices.isEmpty()) return Optional.empty();
    CudaDevice best = null;
    long bestBytes = Long.MIN_VALUE;
    for (GpuAdapters.Adapter adapter : adapters) {
      if (!adapter.nvidiaHardware()) continue;
      Optional<CudaDevice> mapped =
          devices.stream().filter(d -> d.luid().map(adapter.luid()::equals).orElse(false)).findFirst();
      // Strictly larger only, so a tie keeps the adapter DXGI listed first.
      if (mapped.isPresent() && adapter.dedicatedBytes() > bestBytes) {
        best = mapped.get();
        bestBytes = adapter.dedicatedBytes();
      }
    }
    if (best != null) return Optional.of(best);
    return devices.size() == 1 ? Optional.of(devices.get(0)) : Optional.empty();
  }

  /** The facts for one probe result and adapter listing, with the pinned device chosen. */
  public static DeviceFacts facts(GpuDriverApiProbe.Result probe, List<GpuAdapters.Adapter> adapters) {
    return switch (probe.status()) {
      case OK -> {
        List<CudaDevice> devices = new ArrayList<>();
        for (GpuDriverApiProbe.Device d : probe.devices()) {
          devices.add(new CudaDevice(d.ordinal(), d.luid(), d.uuid(), d.pciBusId(), d.totalBytes()));
        }
        yield new DeviceFacts(
            DeviceFacts.ProbeStatus.RAN_OK,
            0,
            probe.driverVersion(),
            devices,
            choose(devices, adapters));
      }
      case NO_DEVICE ->
          new DeviceFacts(
              DeviceFacts.ProbeStatus.RAN_NO_DEVICE,
              probe.code(),
              probe.driverVersion(),
              List.of(),
              Optional.empty());
      case FAILED ->
          new DeviceFacts(DeviceFacts.ProbeStatus.THREW, 0, 0, List.of(), Optional.empty());
    };
  }

  /**
   * The pinned ordinal as the auto-detected value of every per-role ORT device-id key (ordinal 150:
   * any explicit source wins). Empty when nothing is pinned, so the keys keep their defaults.
   */
  public static Map<String, String> ortDeviceDefaults(DeviceFacts facts) {
    Map<String, String> out = new LinkedHashMap<>();
    facts.pinned().ifPresent(device -> {
      for (EnvRegistry key : ORT_DEVICE_KEYS) {
        out.put(key.sysProp(), String.valueOf(device.ordinal()));
      }
    });
    return out;
  }
}
