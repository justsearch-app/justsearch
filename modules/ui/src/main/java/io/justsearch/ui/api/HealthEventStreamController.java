/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.javalin.http.sse.SseClient;
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.OccurrenceLog;
import io.justsearch.telemetry.Telemetry;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;

/**
 * SSE endpoint at {@code GET /api/health/events/stream}.
 *
 * <p>Per slice 436 retrofit + Fix A consolidation: lifecycle / resume / heartbeat /
 * broadcast forwarding live in {@link SseEnvelopeWriter#attach}; this controller only
 * supplies the snapshot extras (current condition set + recent occurrence buffer).
 *
 * <p>Wire shape: universal envelope with constant SSE event name {@code "frame"};
 * snapshot lifecycle carries {@code conditions} + {@code occurrences}; UPDATE frames
 * forward {@link HealthEventChangeRegistry} broadcasts; resume via {@code ?since=token}.
 */
public final class HealthEventStreamController {

  private static final long HEARTBEAT_SECONDS = StreamLivenessWindows.STREAM_HEARTBEAT_INTERVAL_SECONDS;

  private final ConditionStore conditions;
  private final OccurrenceLog occurrences;
  private final HealthEventChangeRegistry changes;

  // Tempdoc 430 Phase 2: Telemetry kept on the constructor for parity; future SSE-volume
  // metrics will read it.
  @SuppressWarnings("unused")
  private final Telemetry telemetry;

  private final EngineExecutorRegistry.Registration heartbeatRegistration;
  private final ScheduledExecutorService heartbeatScheduler;
  private final Clock clock;

  public HealthEventStreamController(
      EngineExecutorRegistry processExecutors,
      ConditionStore conditions,
      OccurrenceLog occurrences,
      HealthEventChangeRegistry changes,
      Telemetry telemetry) {
    this(processExecutors, conditions, occurrences, changes, telemetry, Clock.systemUTC());
  }

  public HealthEventStreamController(
      EngineExecutorRegistry processExecutors,
      ConditionStore conditions,
      OccurrenceLog occurrences,
      HealthEventChangeRegistry changes,
      Telemetry telemetry,
      Clock clock) {
    this.conditions = Objects.requireNonNull(conditions, "conditions");
    this.occurrences = Objects.requireNonNull(occurrences, "occurrences");
    this.changes = Objects.requireNonNull(changes, "changes");
    this.telemetry = telemetry;
    this.clock = Objects.requireNonNull(clock, "clock");
    SchedulerResources resources =
        openHeartbeatScheduler(
            processExecutors,
            "head.health-events-stream-heartbeat",
            "health-events-stream-heartbeat");
    this.heartbeatRegistration = resources.registration();
    this.heartbeatScheduler = resources.scheduler();
  }

  public void handle(SseClient sseClient) {
    SseEnvelopeWriter.attach(
        sseClient,
        changes.channel(),
        () -> {
          Map<String, Object> extras = new LinkedHashMap<>();
          extras.put("conditions", conditions.currentSnapshot());
          extras.put("occurrences", occurrences.recent());
          return extras;
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
