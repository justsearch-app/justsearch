/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import java.util.Map;
import java.util.OptionalLong;
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

  /**
   * When the earliest open breaker among this process's devices opened, on {@link
   * System#nanoTime()}, or empty while every device's breaker is closed (Spec D §6.3: the worker
   * health's standing "semantic search stuck" input).
   */
  public static OptionalLong breakerOpenSinceNanos() {
    long since = Long.MAX_VALUE;
    boolean open = false;
    for (DeviceEncoderGate gate : GATES.values()) {
      OptionalLong device = gate.breakerOpenSinceNanos();
      if (device.isEmpty()) continue;
      if (!open || device.getAsLong() - since < 0) since = device.getAsLong();
      open = true;
    }
    return open ? OptionalLong.of(since) : OptionalLong.empty();
  }
}
