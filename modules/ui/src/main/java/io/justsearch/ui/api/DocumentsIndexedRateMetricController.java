/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;
import io.justsearch.app.observability.metrics.DocumentsIndexedRateMetricChangeRegistry;
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
 * REST + SSE endpoints for the {@code core.metric-worker-documents-indexed-rate-per-sec}
 * TIMESERIES Resource — slice 3a.1.4b cohort follow-up.
 *
 * <p>Mirrors {@code JobQueueDepthMetricController} (the canonical first instance).
 */
public final class DocumentsIndexedRateMetricController {

  private static final Logger log =
      LoggerFactory.getLogger(DocumentsIndexedRateMetricController.class);

  private static final ObjectMapper REST_MAPPER =
      JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

  private static final long HEARTBEAT_SECONDS = StreamLivenessWindows.STREAM_HEARTBEAT_INTERVAL_SECONDS;

  private final TimeseriesSnapshotHolder holder;
  private final DocumentsIndexedRateMetricChangeRegistry changes;
  private final EngineExecutorRegistry.Registration heartbeatRegistration;
  private final ScheduledExecutorService heartbeatScheduler;
  private final Clock clock;

  public DocumentsIndexedRateMetricController(
      EngineExecutorRegistry processExecutors,
      TimeseriesSnapshotHolder holder, DocumentsIndexedRateMetricChangeRegistry changes) {
    this(processExecutors, holder, changes, Clock.systemUTC());
  }

  public DocumentsIndexedRateMetricController(
      EngineExecutorRegistry processExecutors,
      TimeseriesSnapshotHolder holder,
      DocumentsIndexedRateMetricChangeRegistry changes,
      Clock clock) {
    this.holder = Objects.requireNonNull(holder, "holder");
    this.changes = Objects.requireNonNull(changes, "changes");
    this.clock = Objects.requireNonNull(clock, "clock");
    SchedulerResources resources =
        openHeartbeatScheduler(
            processExecutors,
            "head.metric-documents-indexed-rate-heartbeat",
            "metric-documents-indexed-rate-heartbeat");
    this.heartbeatRegistration = resources.registration();
    this.heartbeatScheduler = resources.scheduler();
  }

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
      log.error("Failed to serialize documents-indexed-rate metric snapshot", e);
      throw new IllegalStateException(
          "Documents-indexed-rate metric snapshot serialization failed", e);
    }
  }

  public void handleStream(SseClient sseClient) {
    SseEnvelopeWriter.attach(
        sseClient,
        changes.channel(),
        () -> {
          TimeseriesSnapshot current = holder.current();
          if (current == null) {
            return Map.of();
          }
          return Map.of("snapshot", current);
        },
        clock,
        heartbeatScheduler,
        HEARTBEAT_SECONDS);
  }

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
