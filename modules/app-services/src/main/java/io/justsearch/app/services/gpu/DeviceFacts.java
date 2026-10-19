/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.gpu;

import io.justsearch.app.api.gpu.GpuDevice;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * What the device probe found, and the device the Engine pins (Spec E section 5).
 *
 * <ul>
 *   <li>{@code status}: {@code RAN_OK}, {@code RAN_NO_DEVICE} (with the driver's {@code code}: a
 *       CUresult, 0 for a count of zero, -1 for no driver library), {@code NOT_RUN} or {@code
 *       THREW}. This is the input of section 2.2's NO_GPU and UNRESOLVED rule.
 *   <li>{@code driverVersion}: the CUDA API version the driver supports, as {@code
 *       cuDriverGetVersion} reports it (1000 × major + 10 × minor; 0 when unknown).
 *   <li>{@code devices}: every CUDA device, by ordinal.
 *   <li>{@code pinned}: the chosen device (section 5.2), or empty when none is chosen: no device,
 *       or several devices that could not be mapped (a WARN is logged; the profile is UNRESOLVED).
 * </ul>
 */
public record DeviceFacts(
    ProbeStatus status,
    int code,
    int driverVersion,
    List<CudaDevice> devices,
    Optional<CudaDevice> pinned) {

  /**
   * The CUDA major version of the bundled llama-server runtime ({@code variants/cuda12}). A driver
   * whose CUDA API is older cannot initialise it, so llama-server is not pinned on such a driver.
   */
  public static final int LLAMA_RUNTIME_CUDA_MAJOR = 12;

  /** How the probe ended. */
  public enum ProbeStatus {
    RAN_OK,
    RAN_NO_DEVICE,
    NOT_RUN,
    THREW
  }

  public DeviceFacts {
    Objects.requireNonNull(status, "status");
    devices = List.copyOf(devices);
    Objects.requireNonNull(pinned, "pinned");
    if (pinned.isPresent() && !devices.contains(pinned.get())) {
      throw new IllegalArgumentException("the pinned device must be one of the devices");
    }
  }

  /** No probe has run. */
  public static DeviceFacts notRun() {
    return new DeviceFacts(ProbeStatus.NOT_RUN, 0, 0, List.of(), Optional.empty());
  }

  /**
   * The pinned device as the GPU memory reader takes it: its LUID and CUDA ordinal. Empty when
   * nothing is pinned or the pinned device has no LUID (the reader then maps a single adapter itself).
   */
  public Optional<GpuDevice> pinnedGpuDevice() {
    return pinned.flatMap(d -> d.luid().map(luid -> new GpuDevice(luid, OptionalInt.of(d.ordinal()))));
  }

  /** The pinned device's PCI bus id, which admission's NVML read selects its device by. */
  public Optional<String> pinnedPciBusId() {
    return pinned.flatMap(CudaDevice::pciBusId);
  }

  /** Whether the driver's CUDA API is at least the llama-server runtime's CUDA major. */
  public boolean driverRunsLlamaRuntime() {
    return driverVersion / 1000 >= LLAMA_RUNTIME_CUDA_MAJOR;
  }

  /**
   * The pinned device's UUID, which llama-server's {@code CUDA_VISIBLE_DEVICES} names. Empty when
   * nothing is pinned, or when the driver's CUDA API is older than the llama-server runtime's
   * ({@link #driverRunsLlamaRuntime}): llama-server is then launched unpinned, as before the pin.
   */
  public Optional<String> pinnedUuid() {
    return driverRunsLlamaRuntime() ? pinned.flatMap(CudaDevice::uuid) : Optional.empty();
  }
}
