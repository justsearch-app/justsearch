/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.observability.metrics;

import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.agent.api.registry.ResourceRef;
import io.justsearch.app.observability.metrics.JobQueueDepthMetricChangeRegistry;
import io.justsearch.app.observability.metrics.JobQueueDepthMetricResourceCatalog;
import io.justsearch.app.observability.metrics.TimeseriesSnapshot;
import io.justsearch.app.observability.metrics.TimeseriesSnapshotHolder;
import io.justsearch.telemetry.RrdMetricStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodic producer that builds {@link TimeseriesSnapshot} payloads for the
 * {@code core.metric-worker-job-queue-depth} TIMESERIES Resource — slice 3a.1.4 Phase 5.
 *
 * <p>The status-view tap in {@code StatusLifecycleHandler} passes the index component's
 * recent queue-depth array to the callback wired by {@code CoreApiAssembly}. That callback
 * converts the values to doubles and calls {@link #publishFromValues(double[])}. The producer
 * wraps them in a 30-minute {@link TimeseriesSnapshot} with a 30-second sample interval,
 * updates the holder and broadcasts changes through {@link JobQueueDepthMetricChangeRegistry}.
 *
 * <p>Mirrors the slice 440 {@code RuntimeContextConfigBridge} pattern: holder is updated
 * unconditionally, broadcast suppressed when the snapshot doesn't actually differ from
 * the previous one (no-op suppress to avoid wasted SSE traffic).
 *
 * <p>A separate optional {@link #tick()} queries the supplied {@link RrdMetricStore} for
 * {@code worker.job_queue.depth}. It returns without publishing when the store or query
 * result is unavailable. {@link #start()} schedules that tick every 30 seconds, with the
 * first tick 30 seconds after scheduling. The status-array projection needs no RRD replication.
 */
public final class JobQueueDepthMetricProducer {

  private static final Logger log = LoggerFactory.getLogger(JobQueueDepthMetricProducer.class);

  /** Metric name in the head's RrdMetricStore (mirrors WorkerOpsMetricCatalog.JOB_QUEUE_DEPTH). */
  private static final String METRIC_NAME = "worker.job_queue.depth";

  /** Window length: 30 minutes (slice 419 trends). */
  private static final Duration WINDOW = Duration.ofMinutes(30);

  /** Sample interval: 30 seconds (matches the RRD's primary archive cadence). */
  private static final Duration SAMPLE_INTERVAL = Duration.ofSeconds(30);

  /** Resource id (matches {@link JobQueueDepthMetricResourceCatalog#JOB_QUEUE_DEPTH_ID}). */
  private static final ResourceRef RESOURCE_ID =
      JobQueueDepthMetricResourceCatalog.JOB_QUEUE_DEPTH_ID;

  /** Wire-format unit; free-form per slice 3a.1.4 §B.4. */
  private static final String UNIT = "count";

  private final Supplier<RrdMetricStore> rrdStoreSupplier;
  private final TimeseriesSnapshotHolder holder;
  private final JobQueueDepthMetricChangeRegistry registry;
  private final Clock clock;
  private final EngineExecutorRegistry.Registration schedulerRegistration;
  private final ScheduledExecutorService scheduler;

  public JobQueueDepthMetricProducer(
      EngineExecutorRegistry processExecutors,
      Supplier<RrdMetricStore> rrdStoreSupplier,
      TimeseriesSnapshotHolder holder,
      JobQueueDepthMetricChangeRegistry registry) {
    this(processExecutors, rrdStoreSupplier, holder, registry, Clock.systemUTC());
  }

  public JobQueueDepthMetricProducer(
      EngineExecutorRegistry processExecutors,
      Supplier<RrdMetricStore> rrdStoreSupplier,
      TimeseriesSnapshotHolder holder,
      JobQueueDepthMetricChangeRegistry registry,
      Clock clock) {
    this.rrdStoreSupplier = Objects.requireNonNull(rrdStoreSupplier, "rrdStoreSupplier");
    this.holder = Objects.requireNonNull(holder, "holder");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.clock = Objects.requireNonNull(clock, "clock");
    SchedulerResources resources =
        openScheduler(
            processExecutors,
            "head.metrics-job-queue-depth-producer",
            "metrics-job-queue-depth-producer");
    this.schedulerRegistration = resources.registration();
    this.scheduler = resources.scheduler();
  }

  /** Schedules the periodic tick. Idempotent at the scheduler level. */
  public void start() {
    long intervalSec = SAMPLE_INTERVAL.toSeconds();
    var unused =
        scheduler.scheduleAtFixedRate(this::tick, intervalSec, intervalSec, TimeUnit.SECONDS);
  }

  /** Stops the scheduler. Call on shutdown. */
  public void stop() {
    scheduler.shutdownNow();
    schedulerRegistration.close();
  }

  /**
   * One producer tick: read the latest window from the RRD, build a snapshot, broadcast if
   * the values changed since the previous tick. Public for test-driven invocation.
   */
  public void tick() {
    try {
      TimeseriesSnapshot next = buildSnapshot();
      if (next == null) return;
      TimeseriesSnapshot previous = holder.set(next);
      if (snapshotsEquivalent(previous, next)) return;
      registry.broadcast(next);
    } catch (RuntimeException e) {
      // Producer ticks must not propagate exceptions to the scheduler; one bad tick
      // shouldn't stop the schedule.
      log.warn("Job-queue-depth metric producer tick failed: {}", e.getMessage());
    }
  }

  /**
   * Publishes a snapshot built directly from a values array (instead of querying the
   * supplied RRD). The status-view callback projects the index component's
   * {@code recent_job_queue_depth} array here, without metric replication into another store.
   *
   * <p>No-op when {@code values} is null. Otherwise builds a snapshot using the same
   * window / sample-interval / unit constants as {@link #tick()}, dedupes against the
   * previous snapshot in the holder, and broadcasts via the change registry on change.
   */
  public void publishFromValues(double[] values) {
    if (values == null) return;
    try {
      Instant now = clock.instant();
      long endEpoch = now.getEpochSecond();
      long startEpoch = endEpoch - WINDOW.toSeconds();
      long catalogVersion = registry.currentSeq() + 1L;
      TimeseriesSnapshot next =
          new TimeseriesSnapshot(
              RESOURCE_ID,
              WINDOW.toMillis(),
              SAMPLE_INTERVAL.toMillis(),
              UNIT,
              values,
              Instant.ofEpochSecond(startEpoch),
              Instant.ofEpochSecond(endEpoch),
              catalogVersion);
      TimeseriesSnapshot previous = holder.set(next);
      if (snapshotsEquivalent(previous, next)) return;
      registry.broadcast(next);
    } catch (RuntimeException e) {
      log.warn("Job-queue-depth metric publishFromValues failed: {}", e.getMessage());
    }
  }

  private TimeseriesSnapshot buildSnapshot() {
    RrdMetricStore store;
    try {
      store = rrdStoreSupplier.get();
    } catch (RuntimeException e) {
      return null;
    }
    if (store == null) return null;
    Instant now = clock.instant();
    long endEpoch = now.getEpochSecond();
    long startEpoch = endEpoch - WINDOW.toSeconds();
    RrdMetricStore.TimeSeriesResult result;
    try {
      result = store.query(METRIC_NAME, startEpoch, endEpoch);
    } catch (RuntimeException e) {
      log.debug("RRD query for {} failed: {}", METRIC_NAME, e.getMessage());
      return null;
    }
    if (result == null) return null;
    double[] values = result.values();
    if (values == null) values = new double[0];
    Instant startedAt = Instant.ofEpochSecond(startEpoch);
    Instant endedAt = Instant.ofEpochSecond(endEpoch);
    long catalogVersion = registry.currentSeq() + 1L;
    return new TimeseriesSnapshot(
        RESOURCE_ID,
        WINDOW.toMillis(),
        SAMPLE_INTERVAL.toMillis(),
        UNIT,
        values,
        startedAt,
        endedAt,
        catalogVersion);
  }

  /**
   * No-op-suppression check: returns true when the new snapshot is value-equivalent to
   * the previous one (so the broadcast can be skipped). Compares the values[] array
   * element-wise; ignores window/sample interval (those are static) and timestamps (which
   * always differ).
   */
  private static boolean snapshotsEquivalent(TimeseriesSnapshot prev, TimeseriesSnapshot next) {
    if (prev == null) return false;
    double[] a = prev.values();
    double[] b = next.values();
    if (a.length != b.length) return false;
    for (int i = 0; i < a.length; i++) {
      if (Double.compare(a[i], b[i]) != 0) return false;
    }
    return true;
  }

  private static SchedulerResources openScheduler(
      EngineExecutorRegistry processExecutors, String name, String threadName) {
    Objects.requireNonNull(processExecutors, "processExecutors");
    EngineExecutorRegistry.Limits background =
        processExecutors.limits(EngineExecutorSpec.Kind.BACKGROUND);
    EngineExecutorRegistry.Registration registration =
        processExecutors.register(
            new EngineExecutorSpec(
                name,
                EngineExecutorSpec.Kind.BACKGROUND,
                EngineExecutorSpec.Mode.SCHEDULED,
                1,
                background.maxQueue(),
                1));
    try {
      ScheduledExecutorService scheduler =
          registration.openScheduled(
              runnable -> {
                Thread thread = new Thread(runnable, threadName);
                thread.setDaemon(true);
                return thread;
              });
      return new SchedulerResources(registration, scheduler);
    } catch (RuntimeException | Error failure) {
      try {
        registration.close();
      } catch (RuntimeException | Error cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }
  }

  private record SchedulerResources(
      EngineExecutorRegistry.Registration registration, ScheduledExecutorService scheduler) {}

}
