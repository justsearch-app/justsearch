/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.observability;

import io.justsearch.gpu.GpuCapabilities;
import io.justsearch.gpu.GpuCapabilitiesService;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 419 C3 V2 P3 — background scheduler that feeds {@link GpuSaturationMonitor} with
 * NVML samples on a fixed cadence. Owns a single-thread {@link ScheduledExecutorService}; the
 * thread is a daemon so it doesn't block JVM shutdown if {@link #stop()} is skipped.
 *
 * <p>Lifecycle: {@link #start()} probes the GPU once. If NVML is unavailable (no GPU, or
 * driver missing), the sampler short-circuits and never schedules — no zombie thread on
 * headless / non-GPU machines. Otherwise it schedules {@code sampleOnce()} every 15 seconds.
 *
 * <p>The {@code sampleOnce} body wraps the supplier call in try/catch since {@link
 * ScheduledExecutorService#scheduleAtFixedRate} silently swallows uncaught exceptions and
 * cancels the schedule — a transient NVML hiccup must not permanently disable the monitor.
 */
public final class GpuSaturationSampler {

  private static final Logger log = LoggerFactory.getLogger(GpuSaturationSampler.class);

  /** Fixed sample cadence. 15s balances NVML init overhead vs window-fill latency. */
  static final long SAMPLE_INTERVAL_SECONDS = 15;

  private final Supplier<GpuCapabilitiesService> gpuCapabilitiesSupplier;
  private final GpuSaturationMonitor monitor;
  private final ScheduledExecutorService executor;
  private final EngineExecutorRegistry.Registration executorOwner;
  private final AtomicBoolean started = new AtomicBoolean(false);

  public GpuSaturationSampler(
      EngineExecutorRegistry executors,
      Supplier<GpuCapabilitiesService> gpuCapabilitiesSupplier, GpuSaturationMonitor monitor) {
    this.gpuCapabilitiesSupplier = gpuCapabilitiesSupplier;
    this.monitor = monitor;
    ThreadFactory tf =
        r -> {
          Thread t = new Thread(r, "gpu-saturation-sampler");
          t.setDaemon(true);
          return t;
        };
    var limits = executors.limits(EngineExecutorSpec.Kind.BACKGROUND);
    this.executorOwner = executors.register(new EngineExecutorSpec(
        "head.gpu-saturation-sampler", EngineExecutorSpec.Kind.BACKGROUND,
        EngineExecutorSpec.Mode.SCHEDULED, 1, limits.maxQueue(), 1));
    try {
      this.executor = executorOwner.openScheduled(tf);
    } catch (RuntimeException | Error failure) {
      try { executorOwner.close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
      throw failure;
    }
  }

  /**
   * Starts the sampler. Idempotent: subsequent calls are no-ops. Probes NVML once; if
   * unavailable, the scheduler is never engaged (saves a thread on headless machines).
   */
  public void start() {
    if (!started.compareAndSet(false, true)) {
      return;
    }
    if (!nvmlAvailable()) {
      log.debug("GpuSaturationSampler: NVML unavailable, skipping schedule");
      return;
    }
    sampleOnce();
    var unused =
        executor.scheduleAtFixedRate(
            this::sampleOnce, SAMPLE_INTERVAL_SECONDS, SAMPLE_INTERVAL_SECONDS, TimeUnit.SECONDS);
    log.debug("GpuSaturationSampler started ({}s cadence)", SAMPLE_INTERVAL_SECONDS);
  }

  /** Stops the sampler. Idempotent; safe to call without start. */
  public void stop() {
    executorOwner.close();
  }

  /**
   * Visible for tests. Performs one NVML probe + monitor sample. Wraps in try/catch so a
   * transient driver error doesn't cancel the schedule.
   */
  void sampleOnce() {
    try {
      GpuCapabilitiesService svc = gpuCapabilitiesSupplier.get();
      if (svc == null) return;
      GpuCapabilities.Nvml nvml = svc.snapshot().nvml();
      if (nvml == null || !nvml.available()) return;
      Integer pct = nvml.gpuUtilizationPercent();
      if (pct == null) return;
      monitor.recordSample(pct);
    } catch (RuntimeException e) {
      log.debug("GpuSaturationSampler: probe failed: {}", e.getMessage());
    }
  }

  private boolean nvmlAvailable() {
    try {
      GpuCapabilitiesService svc = gpuCapabilitiesSupplier.get();
      if (svc == null) return false;
      GpuCapabilities.Nvml nvml = svc.snapshot().nvml();
      return nvml != null && nvml.available();
    } catch (RuntimeException e) {
      log.debug("GpuSaturationSampler: NVML probe failed during start: {}", e.getMessage());
      return false;
    }
  }
}
