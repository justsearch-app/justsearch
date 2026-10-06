/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The process-lifetime registry of device gates: one {@link DeviceEncoderGate} per CUDA device id
 * (Spec D §2.3, §3.2). A gate is never rebuilt, so the old and new session handles of one device
 * share one order while they coexist (an encoder swap or a recomposition after device loss). Each
 * {@link NativeSessionHandle} looks its gate up itself; no caller passes a gate in, so two gates for
 * one device cannot exist.
 */
public final class DeviceEncoderGates {

  private static final Map<Integer, DeviceEncoderGate> GATES = new ConcurrentHashMap<>();

  private DeviceEncoderGates() {}

  /** The gate of CUDA device {@code cudaDeviceId}, created on first use with the default policy. */
  public static DeviceEncoderGate forDevice(int cudaDeviceId) {
    return GATES.computeIfAbsent(
        cudaDeviceId, id -> new DeviceEncoderGate(DeviceGatePolicy.DEFAULTS, System::nanoTime));
  }
}
