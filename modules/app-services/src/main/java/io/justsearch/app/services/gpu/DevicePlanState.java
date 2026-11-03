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
  private long publications; // Guarded by this.

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
      publications++;
      applyArenaPlan(resolution);
      var before = current;
      current = resolution;
      io.justsearch.core.status.StatusChangeCounter.process().changed(
          java.util.Arrays.asList(before.statusProfile(), before.source(), before.unmetSettings(),
              before.diagnosticReasons()),
          java.util.Arrays.asList(resolution.statusProfile(), resolution.source(), resolution.unmetSettings(),
              resolution.diagnosticReasons()));
      for (Consumer<GpuProfileResolver.Resolution> consumer : consumers) deliver(consumer, resolution);
    }
  }

  /**
   * Puts {@code candidate}'s plan in effect for its device's sessions while a configuration that
   * carries it is prepared, so a role the published plan has no budget for (Best match first
   * switched on) warms on the device. Nothing is published: the profile and the consumers keep the
   * current resolution. The candidate becomes the published plan only with the commit that
   * publishes its configuration; the returned action, run when the preparation is refused or
   * abandoned, puts the published plan back unless a publication has superseded the stage.
   */
  public Runnable stage(GpuProfileResolver.Resolution candidate) {
    Objects.requireNonNull(candidate, "candidate");
    synchronized (this) {
      GpuArenaPlan plan = candidate.plan().filter(GpuArenaPlan::planned).orElse(null);
      if (plan == null || candidate.cudaDeviceId() < 0) return () -> {};
      long staged = publications;
      if (publishedDevice >= 0 && publishedDevice != candidate.cudaDeviceId()) {
        DeviceArenaPlans.withdraw(publishedDevice);
      }
      DeviceArenaPlans.publish(candidate.cudaDeviceId(), plan);
      int stagedDevice = candidate.cudaDeviceId();
      return () -> {
        synchronized (this) {
          if (publications != staged) return;
          DeviceArenaPlans.withdraw(stagedDevice);
          if (publishedDevice == stagedDevice) publishedDevice = -1;
          applyArenaPlan(current);
        }
      };
    }
  }

  /** Makes {@code resolution}'s plan its device's current plan, or withdraws it. Guarded by this. */
  private void applyArenaPlan(GpuProfileResolver.Resolution resolution) {
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
