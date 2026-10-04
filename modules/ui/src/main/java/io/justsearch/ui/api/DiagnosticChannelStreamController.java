/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.javalin.http.sse.SseClient;
import io.justsearch.agent.api.registry.DiagnosticChannelRef;
import io.justsearch.app.observability.diagnostic.DiagnosticChannelStreamRegistry;
import io.justsearch.telemetry.Telemetry;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;

/**
 * SSE endpoint at {@code GET /api/diagnostic-channels/<channel>/stream}.
 *
 * <p>Per slice 448 phase 3 D7: firehose semantics. The snapshot supplier returns an empty
 * extras map because DiagnosticChannel has no current-state model — the connection opens
 * with the {@code connected} lifecycle frame and immediately tails live UPDATE frames
 * forwarded from the appender via {@link DiagnosticChannelStreamRegistry}. Resume via
 * {@code ?since=token} replays from the underlying {@code SseStreamChannel}'s ring buffer
 * within the resume window; stale tokens trigger the standard {@code reset} lifecycle.
 *
 * <p>Mirrors the {@link HealthEventStreamController} structure — heartbeat scheduler,
 * shutdown hook — but with no snapshot extras. Multi-channel routing (one controller
 * dispatching across several channel ids) is a phase-5+ concern; V1 dispatches by URL
 * path (one route per channel id).
 */
public final class DiagnosticChannelStreamController {

  private static final long HEARTBEAT_SECONDS = StreamLivenessWindows.STREAM_HEARTBEAT_INTERVAL_SECONDS;

  private final DiagnosticChannelStreamRegistry registry;

  // Telemetry kept on the constructor for parity with HealthEventStreamController; future
  // SSE-volume metrics will read it.
  @SuppressWarnings("unused")
  private final Telemetry telemetry;

  private final EngineExecutorRegistry.Registration heartbeatRegistration;
  private final ScheduledExecutorService heartbeatScheduler;
  private final Clock clock;

  public DiagnosticChannelStreamController(
      EngineExecutorRegistry processExecutors,
      DiagnosticChannelStreamRegistry registry, Telemetry telemetry) {
    this(processExecutors, registry, telemetry, Clock.systemUTC());
  }

  public DiagnosticChannelStreamController(
      EngineExecutorRegistry processExecutors,
      DiagnosticChannelStreamRegistry registry, Telemetry telemetry, Clock clock) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.telemetry = telemetry;
    this.clock = Objects.requireNonNull(clock, "clock");
    SchedulerResources resources =
        openHeartbeatScheduler(
            processExecutors,
            "head.diagnostic-channel-stream-heartbeat",
            "diagnostic-channel-stream-heartbeat");
    this.heartbeatRegistration = resources.registration();
    this.heartbeatScheduler = resources.scheduler();
  }

  /**
   * Handles an incoming SSE connection for the channel identified by {@code id}.
   * V1 wires one route per channel; the URL pre-determines the id at registration time
   * in {@link LocalApiServer}.
   */
  public void handle(SseClient sseClient, DiagnosticChannelRef id) {
    SseEnvelopeWriter.attach(
        sseClient,
        registry.channel(id),
        // Firehose: no snapshot. Empty extras map preserves the connected → tail flow.
        Map::of,
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
