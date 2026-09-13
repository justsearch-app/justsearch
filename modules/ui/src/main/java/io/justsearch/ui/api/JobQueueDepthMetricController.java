/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;
import io.justsearch.app.observability.metrics.JobQueueDepthMetricChangeRegistry;
import io.justsearch.app.observability.metrics.TimeseriesSnapshot;
import io.justsearch.app.observability.metrics.TimeseriesSnapshotHolder;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * REST + SSE endpoints for the {@code core.metric-worker-job-queue-depth} TIMESERIES
 * Resource — slice 3a.1.4 Phase 5.
 *
 * <p>Mirrors the slice 440 {@code RuntimeContextController} pattern: SSE lifecycle /
 * resume / heartbeat / broadcast forwarding live in {@link SseEnvelopeWriter#attach};
 * this controller only supplies the snapshot extras (current
 * {@link TimeseriesSnapshot} from the holder).
 *
 * <p>Wire shape: universal envelope with constant SSE event name {@code "frame"};
 * snapshot lifecycle carries {@code snapshot} (the current TimeseriesSnapshot); UPDATE
 * frames carry the full new snapshot (TIMESERIES is replace-on-frame, like STATE);
 * resume via {@code ?since=token}.
 *
 * <p>Endpoint paths (registered by {@code LocalApiServer}):
 *
 * <ul>
 *   <li>{@code GET /api/metrics/worker.job_queue.depth} — snapshot read.
 *   <li>{@code GET /api/metrics/worker.job_queue.depth/stream} — SSE stream.
 * </ul>
 *
 * <p>Future TIMESERIES Resources for other metrics ({@code docs_per_sec},
 * {@code gpu.utilization.percent}, etc.) ship their own controller per the same pattern,
 * or refactor to a generic {@code MetricsController} dispatching by {@code {id}} path
 * parameter when the third metric earns the abstraction (per recipe
 * {@code 30-agent-workflows/01f-add-timeseries-resource.md}).
 */
public final class JobQueueDepthMetricController {

  private static final Logger log = LoggerFactory.getLogger(JobQueueDepthMetricController.class);

  private static final ObjectMapper REST_MAPPER =
      JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

  private static final long HEARTBEAT_SECONDS = StreamLivenessWindows.STREAM_HEARTBEAT_INTERVAL_SECONDS;

  private final TimeseriesSnapshotHolder holder;
  private final JobQueueDepthMetricChangeRegistry changes;
  private final EngineExecutorRegistry.Registration heartbeatRegistration;
  private final ScheduledExecutorService heartbeatScheduler;
  private final Clock clock;

  public JobQueueDepthMetricController(
      EngineExecutorRegistry processExecutors,
      TimeseriesSnapshotHolder holder, JobQueueDepthMetricChangeRegistry changes) {
    this(processExecutors, holder, changes, Clock.systemUTC());
  }

  public JobQueueDepthMetricController(
      EngineExecutorRegistry processExecutors,
      TimeseriesSnapshotHolder holder,
      JobQueueDepthMetricChangeRegistry changes,
      Clock clock) {
    this.holder = Objects.requireNonNull(holder, "holder");
    this.changes = Objects.requireNonNull(changes, "changes");
    this.clock = Objects.requireNonNull(clock, "clock");
    SchedulerResources resources =
        openHeartbeatScheduler(
            processExecutors,
            "head.metric-job-queue-depth-heartbeat",
            "metric-job-queue-depth-heartbeat");
    this.heartbeatRegistration = resources.registration();
    this.heartbeatScheduler = resources.scheduler();
  }

  /**
   * Handles {@code GET /api/metrics/worker.job_queue.depth}. Returns the current snapshot
   * envelope. Returns 503 when no snapshot has been produced yet (first producer tick
   * hasn't fired).
   */
  public void handleGet(Context ctx) {
    try {
      TimeseriesSnapshot snapshot = holder.current();
      if (snapshot == null) {
        ctx.status(503)
            .contentType("application/json")
            .result(
                REST_MAPPER.writeValueAsBytes(
                    Map.of(
                        "error",
                        "No snapshot available yet — RRD store has not accumulated samples")));
        return;
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("catalogVersion", changes.currentSeq());
      payload.put("snapshot", snapshot);
      ctx.contentType("application/json").result(REST_MAPPER.writeValueAsBytes(payload));
    } catch (Exception e) {
      log.error("Failed to serialize job-queue-depth metric snapshot", e);
      throw new IllegalStateException(
          "Job-queue-depth metric snapshot serialization failed", e);
    }
  }

  /** Handles SSE {@code GET /api/metrics/worker.job_queue.depth/stream}. */
  public void handleStream(SseClient sseClient) {
    SseEnvelopeWriter.attach(
        sseClient,
        changes.channel(),
        () -> {
          TimeseriesSnapshot current = holder.current();
          // The snapshot extras supplier returns the current snapshot when present, or an
          // empty map otherwise. SSE consumers receive a `snapshot` lifecycle frame either
          // way; absent payload signals "no data yet" without breaking the stream contract.
          if (current == null) {
            return Map.of();
          }
          return Map.of("snapshot", current);
        },
        clock,
        heartbeatScheduler,
        HEARTBEAT_SECONDS);
  }

  /** Stops the heartbeat scheduler. Call on shutdown. */
  public void shutdown() {
    heartbeatScheduler.shutdownNow();
    heartbeatRegistration.close();
  }

  private static SchedulerResources openHeartbeatScheduler(
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
