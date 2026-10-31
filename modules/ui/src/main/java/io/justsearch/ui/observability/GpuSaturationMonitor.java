/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.observability;

import io.justsearch.telemetry.RollingMonitorWindow;
import java.util.ArrayDeque;
import java.util.function.LongSupplier;

/**
 * Tempdoc 419 C3 V2 P3 — head-side rolling-window GPU saturation detector.
 *
 * <p>Mirrors {@code OperationalMetrics.ThroughputMonitor} (worker-core) — the shared window
 * trio lives in {@link RollingMonitorWindow}, same shape — but classifies "GPU pinned high
 * while no workload is active" as SATURATED. The
 * activity gate is the inverse of throughput's: throughput fires STALLED when work is queued
 * but rate is low; saturation fires when nothing is queued but GPU is busy anyway (idle leak,
 * stuck inference session, lingering background job).
 *
 * <p>Thread-safe: all methods are {@code synchronized}. The same instance receives samples from
 * a scheduled sampler and reads from {@code StatusLifecycleHandler} on every {@code /api/status}
 * call; contention is bounded (sampler runs every 15s, status polls at most every few seconds).
 */
public final class GpuSaturationMonitor {

  /** Window size in milliseconds — must accumulate sustained saturation before firing. */
  private static final long WINDOW_MS = RollingMonitorWindow.WINDOW_MS;

  /** If samples are sparser than this, the monitor resets and reports UNKNOWN. */
  private static final long MAX_GAP_MS = RollingMonitorWindow.MAX_GAP_MS;

  /** Cap on retained samples; > WINDOW_MS / sampler-period + slack. */
  private static final int MAX_SAMPLES = RollingMonitorWindow.MAX_SAMPLES;

  /** Average GPU utilization above this percentage triggers SATURATED. */
  private static final double SATURATION_THRESHOLD_PERCENT = 80.0;

  /** Sample states. */
  public static final String STATE_UNKNOWN = "UNKNOWN";

  public static final String STATE_HEALTHY = "HEALTHY";
  public static final String STATE_SATURATED = "SATURATED";

  private record Sample(long timeMs, int gpuPercent) {}

  private final ArrayDeque<Sample> samples = new ArrayDeque<>(MAX_SAMPLES + 1);
  private final LongSupplier clock;

  public GpuSaturationMonitor() {
    this(System::currentTimeMillis);
  }

  /** Visible for tests — inject a controllable clock. */
  GpuSaturationMonitor(LongSupplier clock) {
    this.clock = clock;
  }

  /** Records a GPU utilization sample. Call from the scheduled sampler thread. */
  public synchronized void recordSample(int gpuPercent) {
    long now = clock.getAsLong();
    if (!samples.isEmpty() && now - samples.getLast().timeMs > MAX_GAP_MS) samples.clear();
    if (!samples.isEmpty() && now - samples.getLast().timeMs < RollingMonitorWindow.MIN_SAMPLE_MS) return;
    samples.addLast(new Sample(now, gpuPercent));
    while (samples.size() > 2 && samples.stream().skip(1).findFirst().orElseThrow().timeMs <= now - WINDOW_MS) {
      samples.removeFirst();
    }
  }

  /**
   * Computes the current state given the activity gate.
   *
   * @param activityGate sum of "GPU should be busy" signals; SATURATED requires {@code == 0}.
   *     Typical composition: {@code engineMonitor.queueDepth() + workerView.processingJobsCount()
   *     + (gplCoordinatorRunning ? 1 : 0) + (onlineAi.isAvailable() ? 1 : 0)}.
   */
  public synchronized Result compute(long activityGate) {
    if (samples.size() < 2) {
      return new Result(0.0, STATE_UNKNOWN);
    }
    Sample oldest = samples.getFirst();
    Sample newest = samples.getLast();
    long deltaTimeMs = newest.timeMs - oldest.timeMs;
    if (deltaTimeMs < WINDOW_MS) {
      return new Result(0.0, STATE_UNKNOWN);
    }
    long sum = 0L;
    for (Sample s : samples) {
      sum += s.gpuPercent;
    }
    double avgPercent = sum / (double) samples.size();
    String state;
    if (activityGate > 0) {
      // Activity is happening — high GPU is expected, never SATURATED.
      state = STATE_HEALTHY;
    } else if (avgPercent > SATURATION_THRESHOLD_PERCENT) {
      state = STATE_SATURATED;
    } else {
      state = STATE_HEALTHY;
    }
    return new Result(avgPercent, state);
  }

  /** Clears all samples — used by tests. */
  public synchronized void reset() {
    samples.clear();
  }

  /** Result of a {@link #compute} call. */
  public record Result(double avgPercent, String state) {}
}
