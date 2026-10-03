/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.app.services.worker.IpcTags.WorkerRestartTags;
import io.justsearch.telemetry.catalog.EmptyTags;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * IPC-focused telemetry helper for worker lifecycle instrumentation.
 *
 * <p>Tempdoc 417 Phase 2e: thin façade over {@link IpcMetricCatalog}. Holders take
 * {@link IpcTelemetry} (not the catalog) and use {@link #noop()} when no telemetry is wired;
 * {@code KnowledgeClient} is the one that survives the process merge, and it records the
 * status-poll pair.
 *
 * <p>All metrics use the "ipc." namespace prefix for consistent filtering.
 *
 * <p><b>Lane F stage A item A10</b> removed the three channel recorders (reconnect and the two
 * circuit-breaker ones) with the wire client stack that called them. The remaining
 * spawn/supervision recorders — port discovery, restart outcomes, shutdown timeouts, PID
 * mismatches — lost their producers at item A11 when the Worker process was deleted; they are
 * kept here deliberately, because unlike the channel metrics their subject (a supervised
 * subprocess) is scheduled to return at stage B rather than to disappear.
 */
public final class IpcTelemetry {

  /** Cached no-op singleton. F6: avoid constructing fresh catalogs per call. */
  private static final IpcTelemetry NOOP = new IpcTelemetry(IpcMetricCatalog.noop());

  /**
   * Creates a no-op IpcTelemetry instance for testing or bridges without a real
   * {@code LocalTelemetry}. All operations are no-ops; no metrics are recorded.
   */
  public static IpcTelemetry noop() {
    return NOOP;
  }

  private final IpcMetricCatalog catalog;

  public IpcTelemetry(IpcMetricCatalog catalog) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
  }

  /** Wraps a port-discovery operation in a timing block. */
  public Sample startPortDiscovery() {
    long startNs = System.nanoTime();
    return () -> {
      long durMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
      catalog.portDiscoveryMs.record(Math.max(0L, durMs), EmptyTags.INSTANCE);
    };
  }

  /** Records a port discovery timeout event. */
  public void recordPortDiscoveryTimeout() {
    catalog.portDiscoveryTimeout.increment(EmptyTags.INSTANCE);
  }

  /** Records a successful worker restart. */
  public void recordRestartSuccess() {
    catalog.workerRestart.increment(new WorkerRestartTags(WorkerRestartOutcome.SUCCESS));
  }

  /** Records a failed worker restart attempt. */
  public void recordRestartFailed() {
    catalog.workerRestart.increment(new WorkerRestartTags(WorkerRestartOutcome.FAILED));
  }

  /** Records that the restart limit was exceeded. */
  public void recordRestartLimitExceeded() {
    catalog.workerRestartLimitExceeded.increment(EmptyTags.INSTANCE);
  }

  /** Records a PID validation mismatch during port discovery. */
  public void recordPidMismatch() {
    catalog.workerPidMismatch.increment(EmptyTags.INSTANCE);
  }

  /** Records a shutdown timeout (worker did not terminate within grace period). */
  public void recordShutdownTimeout() {
    catalog.shutdownTimeout.increment(EmptyTags.INSTANCE);
  }

  /** Records a forcible process kill. */
  public void recordForcibleKill() {
    catalog.shutdownForcibleKill.increment(EmptyTags.INSTANCE);
  }

  /**
   * Records a restart counter reset after stable operation.
   *
   * @param previousCount the restart count that was reset (for logging/diagnostics)
   */
  public void recordStabilityReset(int previousCount) {
    catalog.workerStabilityReset.increment(EmptyTags.INSTANCE);
  }

  /** Wraps a status-poll operation in a timing block. */
  public Sample startStatusPoll() {
    long startNs = System.nanoTime();
    return () -> {
      long durMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
      catalog.statusPollMs.record(Math.max(0L, durMs), EmptyTags.INSTANCE);
    };
  }

  /**
   * Records the size of a status response for capacity monitoring.
   *
   * <p>Useful for detecting when StatusResponse grows unexpectedly
   * (e.g., very large queue depth lists, excessive field additions).
   *
   * @param responseBytes the serialized size of the StatusResponse
   */
  public void recordStatusResponseSize(int responseBytes) {
    catalog.statusResponseBytes.record(responseBytes, EmptyTags.INSTANCE);
  }

  /** AutoCloseable timing handle returned by {@link #startPortDiscovery} and {@link #startStatusPoll}. */
  public interface Sample extends AutoCloseable {
    @Override
    void close();
  }
}
