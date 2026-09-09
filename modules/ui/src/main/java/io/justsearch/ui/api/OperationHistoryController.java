/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;
import io.justsearch.app.observability.operations.OperationHistoryChangeRegistry;
import io.justsearch.app.observability.operations.OperationHistoryStore;
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
 * REST + SSE endpoints for the {@code core.operation-history} EVENT_STREAM Resource.
 *
 * <p>Per slice 444b + slice 436 retrofit + Fix A consolidation: SSE lifecycle / resume /
 * heartbeat / broadcast forwarding live in {@link SseEnvelopeWriter#attach}; this
 * controller only supplies the snapshot extras (recent ring-buffer entries).
 *
 * <p>Wire shape: universal envelope with constant SSE event name {@code "frame"};
 * snapshot lifecycle carries {@code entries}; UPDATE frames carry each appended
 * {@code OperationHistoryEntry}; resume via {@code ?since=token}.
 */
public final class OperationHistoryController {

  private static final Logger log = LoggerFactory.getLogger(OperationHistoryController.class);

  private static final ObjectMapper REST_MAPPER =
      JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

  private static final long HEARTBEAT_SECONDS = StreamLivenessWindows.STREAM_HEARTBEAT_INTERVAL_SECONDS;

  private final OperationHistoryStore store;
  private final OperationHistoryChangeRegistry changes;
  private final EngineExecutorRegistry.Registration heartbeatRegistration;
  private final ScheduledExecutorService heartbeatScheduler;
  private final Clock clock;

  public OperationHistoryController(
      EngineExecutorRegistry processExecutors,
      OperationHistoryStore store, OperationHistoryChangeRegistry changes) {
    this(processExecutors, store, changes, Clock.systemUTC());
  }

  public OperationHistoryController(
      EngineExecutorRegistry processExecutors,
      OperationHistoryStore store, OperationHistoryChangeRegistry changes, Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.changes = Objects.requireNonNull(changes, "changes");
    this.clock = Objects.requireNonNull(clock, "clock");
    SchedulerResources resources =
        openHeartbeatScheduler(
            processExecutors,
            "head.operation-history-heartbeat",
            "operation-history-heartbeat");
    this.heartbeatRegistration = resources.registration();
    this.heartbeatScheduler = resources.scheduler();
  }

  /** Handles {@code GET /api/operation-history}. Returns the current snapshot envelope. */
  public void handleGet(Context ctx) {
    try {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("catalogVersion", changes.currentSeq());
      payload.put("entries", store.recent());
      ctx.contentType("application/json").result(REST_MAPPER.writeValueAsBytes(payload));
    } catch (Exception e) {
      log.error("Failed to serialize operation-history snapshot", e);
      throw new IllegalStateException("Operation-history snapshot serialization failed", e);
    }
  }

  /** Handles SSE {@code GET /api/operation-history/stream}. */
  public void handleStream(SseClient sseClient) {
    SseEnvelopeWriter.attach(
        sseClient,
        changes.channel(),
        () -> Map.of("entries", store.recent()),
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
