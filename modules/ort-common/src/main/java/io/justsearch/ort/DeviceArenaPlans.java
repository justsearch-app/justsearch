/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The process-lifetime registry of device-memory plans: the {@link GpuArenaPlan} the Engine
 * resolved for each CUDA device id (Spec E revision 4 section 2.2, step E7b). The Engine publishes
 * a plan whenever it publishes a configuration; each {@link NativeSessionHandle} on a planned
 * profile reads the current plan of its own device for every call it packs, like it looks up its
 * device gate ({@link DeviceEncoderGates}). Nothing else writes here.
 */
public final class DeviceArenaPlans {

  private static final Map<Integer, GpuArenaPlan> PLANS = new ConcurrentHashMap<>();

  private DeviceArenaPlans() {}

  /** Publishes {@code plan} as the current plan of CUDA device {@code cudaDeviceId}. */
  public static void publish(int cudaDeviceId, GpuArenaPlan plan) {
    PLANS.put(cudaDeviceId, Objects.requireNonNull(plan, "plan"));
  }

  /** Withdraws the plan of {@code cudaDeviceId}: its sessions pack as today until the next one. */
  public static void withdraw(int cudaDeviceId) {
    PLANS.remove(cudaDeviceId);
  }

  /** The current plan of {@code cudaDeviceId}, when one was published. */
  public static Optional<GpuArenaPlan> current(int cudaDeviceId) {
    return Optional.ofNullable(PLANS.get(cudaDeviceId));
  }
}
