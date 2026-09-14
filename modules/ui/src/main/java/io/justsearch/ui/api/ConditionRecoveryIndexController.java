/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;
import io.justsearch.app.observability.health.ConditionRecoveryIndex;
import io.justsearch.app.observability.health.ConditionRecoveryIndexBuilder;
import io.justsearch.app.observability.health.ConditionRecoveryIndexChangeRegistry;
import io.justsearch.app.observability.health.ConditionStore;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * REST + SSE endpoints for the {@code core.condition-recovery-index} derived inverse
 * Resource.
 *
 * <p>Per slice 447 §X.3.4 + 447-impl-D + §X.11.5 follow-up Phase 4: STATE × SSE_STREAM
 * semantics. Derived snapshot of {@link ConditionStore}'s current AssertedCondition +
 * ThresholdState entries grouped by their {@code recovery.target()} OperationRef.
 *
 * <p>Two consumption modes:
 *
 * <ul>
 *   <li>{@link #handle(Context)}: REST one-shot snapshot at
 *       {@code GET /api/condition-recovery-index} (fallback for clients that can't do
 *       SSE).
 *   <li>{@link #handleStream(SseClient)}: SSE replace-on-change at
 *       {@code GET /api/condition-recovery-index/stream}. Each
 *       ConditionStore mutation re-emits the full new index per STATE-replace
 *       discipline.
 * </ul>
 */
public final class ConditionRecoveryIndexController {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private static final long HEARTBEAT_SECONDS = StreamLivenessWindows.STREAM_HEARTBEAT_INTERVAL_SECONDS;

  private final ConditionStore conditionStore;
  private final ConditionRecoveryIndexChangeRegistry changes;
  private final Clock clock;
  private final EngineExecutorRegistry.Registration heartbeatRegistration;
  private final ScheduledExecutorService heartbeatScheduler;

  public ConditionRecoveryIndexController(
      EngineExecutorRegistry processExecutors,
      ConditionStore conditionStore, ConditionRecoveryIndexChangeRegistry changes) {
    this(processExecutors, conditionStore, changes, Clock.systemUTC());
  }

  public ConditionRecoveryIndexController(
      EngineExecutorRegistry processExecutors,
      ConditionStore conditionStore,
      ConditionRecoveryIndexChangeRegistry changes,
      Clock clock) {
    this.conditionStore = Objects.requireNonNull(conditionStore, "conditionStore");
    this.changes = Objects.requireNonNull(changes, "changes");
    this.clock = Objects.requireNonNull(clock, "clock");
    SchedulerResources resources =
        openHeartbeatScheduler(
            processExecutors,
            "head.condition-recovery-index-heartbeat",
            "condition-recovery-index-heartbeat");
    this.heartbeatRegistration = resources.registration();
    this.heartbeatScheduler = resources.scheduler();
  }

  /** REST one-shot snapshot. */
  public void handle(Context ctx) {
    ConditionRecoveryIndex index = ConditionRecoveryIndexBuilder.build(conditionStore);
    String json = MAPPER.writeValueAsString(index);
    ctx.contentType("application/json");
    ctx.result(json);
  }

  /** SSE replace-on-change stream. */
  public void handleStream(SseClient sseClient) {
    SseEnvelopeWriter.attach(
        sseClient,
        changes.channel(),
        () -> Map.of("index", ConditionRecoveryIndexBuilder.build(conditionStore)),
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
