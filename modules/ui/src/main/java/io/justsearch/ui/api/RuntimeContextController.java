/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;
import io.justsearch.app.observability.runtime.RuntimeContextChangeRegistry;
import io.justsearch.app.observability.runtime.RuntimeContextHolder;
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
 * REST + SSE endpoints for the {@code core.runtime-context} STATE Resource.
 *
 * <p>Per slice 440 + slice 436 retrofit + Fix A consolidation: SSE lifecycle / resume /
 * heartbeat / broadcast forwarding live in {@link SseEnvelopeWriter#attach}; this
 * controller only supplies the snapshot extras (current RuntimeContext from the holder).
 *
 * <p>Wire shape: universal envelope with constant SSE event name {@code "frame"};
 * snapshot lifecycle carries {@code context}; UPDATE frames carry the full new
 * RuntimeContext (STATE replace-only); resume via {@code ?since=token}.
 */
public final class RuntimeContextController {

  private static final Logger log = LoggerFactory.getLogger(RuntimeContextController.class);

  private static final ObjectMapper REST_MAPPER =
      JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

  private static final long HEARTBEAT_SECONDS = StreamLivenessWindows.STREAM_HEARTBEAT_INTERVAL_SECONDS;

  private final RuntimeContextHolder holder;
  private final RuntimeContextChangeRegistry changes;
  private final EngineExecutorRegistry.Registration heartbeatRegistration;
  private final ScheduledExecutorService heartbeatScheduler;
  private final Clock clock;

  public RuntimeContextController(
      EngineExecutorRegistry processExecutors,
      RuntimeContextHolder holder, RuntimeContextChangeRegistry changes) {
    this(processExecutors, holder, changes, Clock.systemUTC());
  }

  public RuntimeContextController(
      EngineExecutorRegistry processExecutors,
      RuntimeContextHolder holder, RuntimeContextChangeRegistry changes, Clock clock) {
    this.holder = Objects.requireNonNull(holder, "holder");
    this.changes = Objects.requireNonNull(changes, "changes");
    this.clock = Objects.requireNonNull(clock, "clock");
    SchedulerResources resources =
        openHeartbeatScheduler(
            processExecutors,
            "head.runtime-context-heartbeat",
            "runtime-context-heartbeat");
    this.heartbeatRegistration = resources.registration();
    this.heartbeatScheduler = resources.scheduler();
  }

  /** Handles {@code GET /api/runtime-context}. Returns the current snapshot envelope. */
  public void handleGet(Context ctx) {
    try {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("catalogVersion", changes.currentSeq());
      payload.put("context", holder.current());
      ctx.contentType("application/json").result(REST_MAPPER.writeValueAsBytes(payload));
    } catch (Exception e) {
      log.error("Failed to serialize runtime-context snapshot", e);
      throw new IllegalStateException("Runtime-context snapshot serialization failed", e);
    }
  }

  /** Handles SSE {@code GET /api/runtime-context/stream}. */
  public void handleStream(SseClient sseClient) {
    SseEnvelopeWriter.attach(
        sseClient,
        changes.channel(),
        () -> Map.of("context", holder.current()),
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
