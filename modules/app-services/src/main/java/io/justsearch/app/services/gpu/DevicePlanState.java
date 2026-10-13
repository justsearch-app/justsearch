/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.gpu;

import io.justsearch.ort.DeviceArenaPlans;
import io.justsearch.ort.GpuArenaPlan;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The device-memory plan the Engine currently applies (Spec E revision 4 section 2.3, step E7b),
 * one per process like the device probe. Each published configuration publishes its plan here: the
 * encoder sessions read it through {@link DeviceArenaPlans}, and the bulk-work gate, the chat
 * controller and the status surface receive its profile. Before the first publication the profile
 * is UNRESOLVED.
 */
public final class DevicePlanState {

  private static final Logger LOG = LoggerFactory.getLogger(DevicePlanState.class);
  private static final DevicePlanState PROCESS = new DevicePlanState();

  private final List<Consumer<GpuProfileResolver.Resolution>> consumers =
      new CopyOnWriteArrayList<>();
  private volatile GpuProfileResolver.Resolution current = GpuProfileResolver.Resolution.unresolved();
  private int publishedDevice = -1; // Guarded by this.

  /** The process's plan state. */
  public static DevicePlanState process() {
    return PROCESS;
  }

  /** A state of its own, for tests. */
  public static DevicePlanState isolated() {
    return new DevicePlanState();
  }

  /** The current resolution; UNRESOLVED until the first publication. */
  public GpuProfileResolver.Resolution current() {
    return current;
  }

  /**
   * Publishes {@code resolution}: a planned profile's plan becomes its device's current plan, any
   * other withdraws it, and every consumer receives it in publication order.
   */
  public void publish(GpuProfileResolver.Resolution resolution) {
    Objects.requireNonNull(resolution, "resolution");
    synchronized (this) {
      if (publishedDevice >= 0 && publishedDevice != resolution.cudaDeviceId()) {
        DeviceArenaPlans.withdraw(publishedDevice);
        publishedDevice = -1;
      }
      GpuArenaPlan plan = resolution.plan().filter(GpuArenaPlan::planned).orElse(null);
      if (plan != null && resolution.cudaDeviceId() >= 0) {
        DeviceArenaPlans.publish(resolution.cudaDeviceId(), plan);
        publishedDevice = resolution.cudaDeviceId();
      } else if (publishedDevice >= 0) {
        DeviceArenaPlans.withdraw(publishedDevice);
        publishedDevice = -1;
      }
      var before = current;
      current = resolution;
      io.justsearch.core.status.StatusChangeCounter.process().changed(
          java.util.Arrays.asList(before.statusProfile(), before.source(), before.unmetSettings()),
          java.util.Arrays.asList(resolution.statusProfile(), resolution.source(), resolution.unmetSettings()));
      for (Consumer<GpuProfileResolver.Resolution> consumer : consumers) deliver(consumer, resolution);
    }
  }

  /** Delivers the current resolution to {@code consumer} now and every later one as published. */
  public void subscribe(Consumer<GpuProfileResolver.Resolution> consumer) {
    Objects.requireNonNull(consumer, "consumer");
    synchronized (this) {
      consumers.add(consumer);
      deliver(consumer, current);
    }
  }

  private static void deliver(
      Consumer<GpuProfileResolver.Resolution> consumer, GpuProfileResolver.Resolution resolution) {
    try {
      consumer.accept(resolution);
    } catch (RuntimeException failure) {
      LOG.warn("A device-memory plan consumer failed", failure);
    }
  }
}
