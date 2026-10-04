/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.app.services.worker.IpcTags.WorkerRestartTags;
import io.justsearch.telemetry.catalog.CounterMetric;
import io.justsearch.telemetry.catalog.EmptyTags;
import io.justsearch.telemetry.catalog.HistogramMetric;
import io.justsearch.telemetry.catalog.MetricCatalog;
import io.justsearch.telemetry.catalog.MetricDefinition;
import io.justsearch.telemetry.catalog.MetricRegistry;
import io.justsearch.telemetry.catalog.NoopMetricRegistry;
import io.justsearch.telemetry.catalog.Unit;
import java.util.List;
import java.util.Objects;

/**
 * Tempdoc 417 Phase 2e catalog for {@code ipc.*} metrics emitted by {@link IpcTelemetry}.
 *
 * <p>Most metrics carry no tags ({@link EmptyTags}); one carries a typed schema:
 * {@code ipc.worker.restart} (outcome).
 *
 * <p><b>Lane F stage A item A10</b> removed the three channel metrics —
 * {@code ipc.grpc.reconnect}, {@code ipc.circuit_breaker.state_change} and
 * {@code ipc.circuit_breaker.rejected}. Reconnects and circuit-breaker transitions are properties
 * of a wire, and there is no longer a wire between the two halves to have them; the classes that
 * emitted them went with the transport. A metric definition outliving every producer is not
 * harmless — it registers an instrument, publishes a name into the catalog contract, and reads to
 * a dashboard as "always zero" rather than "no longer measured".
 *
 * <p>Two histograms are exposed as histograms rather than timers (the legacy {@code Telemetry.Timer}
 * was a thin wrapper over a histogram instrument anyway): {@code ipc.port_discovery_ms} and
 * {@code ipc.status.poll_ms}. Bucket bounds default to OTel's defaults — pre-refactor inline
 * config was {@code (false, null, null)} which left bounds unset.
 */
public final class IpcMetricCatalog implements MetricCatalog {

  public static final String NAMESPACE = "ipc";

  public static final String PORT_DISCOVERY_MS = "ipc.port_discovery_ms";
  public static final String PORT_DISCOVERY_TIMEOUT = "ipc.port_discovery.timeout";
  public static final String WORKER_RESTART = "ipc.worker.restart";
  public static final String WORKER_RESTART_LIMIT_EXCEEDED = "ipc.worker.restart_limit_exceeded";
  public static final String WORKER_PID_MISMATCH = "ipc.worker.pid_mismatch";
  public static final String SHUTDOWN_TIMEOUT = "ipc.shutdown.timeout";
  public static final String SHUTDOWN_FORCIBLE_KILL = "ipc.shutdown.forcible_kill";
  public static final String WORKER_STABILITY_RESET = "ipc.worker.stability_reset";
  public static final String STATUS_POLL_MS = "ipc.status.poll_ms";
  public static final String STATUS_RESPONSE_BYTES = "ipc.status.response_bytes";

  public static final List<MetricDefinition> DEFINITIONS =
      List.of(
          MetricDefinition.histogram(PORT_DISCOVERY_MS).unit(Unit.MILLISECONDS).build(),
          MetricDefinition.counter(PORT_DISCOVERY_TIMEOUT).unit(Unit.COUNT).build(),
          MetricDefinition.counter(WORKER_RESTART)
              .unit(Unit.COUNT)
              .tagKeys(IpcTags.OUTCOME_KEYS)
              .build(),
          MetricDefinition.counter(WORKER_RESTART_LIMIT_EXCEEDED).unit(Unit.COUNT).build(),
          MetricDefinition.counter(WORKER_PID_MISMATCH).unit(Unit.COUNT).build(),
          MetricDefinition.counter(SHUTDOWN_TIMEOUT).unit(Unit.COUNT).build(),
          MetricDefinition.counter(SHUTDOWN_FORCIBLE_KILL).unit(Unit.COUNT).build(),
          MetricDefinition.counter(WORKER_STABILITY_RESET).unit(Unit.COUNT).build(),
          MetricDefinition.histogram(STATUS_POLL_MS).unit(Unit.MILLISECONDS).build(),
          MetricDefinition.histogram(STATUS_RESPONSE_BYTES).unit(Unit.BYTES).build());

  static {
    String prefix = NAMESPACE + ".";
    for (MetricDefinition def : DEFINITIONS) {
      if (!def.name().startsWith(prefix)) {
        throw new ExceptionInInitializerError(
            "IpcMetricCatalog metric '"
                + def.name()
                + "' does not match namespace '"
                + NAMESPACE
                + "'");
      }
    }
  }

  public final HistogramMetric<EmptyTags> portDiscoveryMs;
  public final CounterMetric<EmptyTags> portDiscoveryTimeout;
  public final CounterMetric<WorkerRestartTags> workerRestart;
  public final CounterMetric<EmptyTags> workerRestartLimitExceeded;
  public final CounterMetric<EmptyTags> workerPidMismatch;
  public final CounterMetric<EmptyTags> shutdownTimeout;
  public final CounterMetric<EmptyTags> shutdownForcibleKill;
  public final CounterMetric<EmptyTags> workerStabilityReset;
  public final HistogramMetric<EmptyTags> statusPollMs;
  public final HistogramMetric<EmptyTags> statusResponseBytes;

  public IpcMetricCatalog(MetricRegistry registry) {
    Objects.requireNonNull(registry, "registry");
    this.portDiscoveryMs = registry.buildHistogram(PORT_DISCOVERY_MS);
    this.portDiscoveryTimeout = registry.buildCounter(PORT_DISCOVERY_TIMEOUT);
    this.workerRestart = registry.buildCounter(WORKER_RESTART);
    this.workerRestartLimitExceeded = registry.buildCounter(WORKER_RESTART_LIMIT_EXCEEDED);
    this.workerPidMismatch = registry.buildCounter(WORKER_PID_MISMATCH);
    this.shutdownTimeout = registry.buildCounter(SHUTDOWN_TIMEOUT);
    this.shutdownForcibleKill = registry.buildCounter(SHUTDOWN_FORCIBLE_KILL);
    this.workerStabilityReset = registry.buildCounter(WORKER_STABILITY_RESET);
    this.statusPollMs = registry.buildHistogram(STATUS_POLL_MS);
    this.statusResponseBytes = registry.buildHistogram(STATUS_RESPONSE_BYTES);
  }

  /** Cached no-op singleton. F6 fix. */
  private static final IpcMetricCatalog NOOP =
      new IpcMetricCatalog(new NoopMetricRegistry(DEFINITIONS));

  /** No-op catalog for {@link IpcTelemetry#noop()} and tests. */
  public static IpcMetricCatalog noop() {
    return NOOP;
  }

  @Override
  public String namespace() {
    return NAMESPACE;
  }

  @Override
  public List<MetricDefinition> definitions() {
    return DEFINITIONS;
  }
}
