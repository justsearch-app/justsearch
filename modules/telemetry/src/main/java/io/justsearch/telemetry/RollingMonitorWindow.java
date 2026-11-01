/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.telemetry;

/**
 * Shared rolling-window sampling parameters for the head/worker saturation-and-throughput
 * monitors (tempdoc 682 Item 3 — single authority for a trio that was previously duplicated
 * per process).
 *
 * <p>Consumers: {@code OperationalMetrics.ThroughputMonitor} (worker-core — indexing throughput,
 * fires STALLED when work is queued but rate is low) and {@code GpuSaturationMonitor} (ui —
 * GPU utilization, fires SATURATED when nothing is queued but the GPU is busy anyway, tempdoc
 * 419 C3 V2 P3). Both are deliberate mirrors: same window, same gap-reset semantics, same
 * sample cap — only the sampled quantity and the activity gate differ.
 */
public final class RollingMonitorWindow {

  /** Window size in milliseconds — a verdict needs this much sustained evidence before firing. */
  public static final long WINDOW_MS = 180_000;

  /** If samples are sparser than this, the monitor resets its window and reports UNKNOWN. */
  public static final long MAX_GAP_MS = 600_000;

  /** Coalesce polling bursts so retention is bounded independently of request rate. */
  public static final long MIN_SAMPLE_MS = 1_000;

  /** Horizon plus one sample on either side of its cutoff. */
  public static final int MAX_SAMPLES = (int) (WINDOW_MS / MIN_SAMPLE_MS) + 2;

  private RollingMonitorWindow() {}
}
