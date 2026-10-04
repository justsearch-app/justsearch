/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.javalin.http.sse.SseClient;
import io.justsearch.app.api.stream.WireContractVersion;
import io.justsearch.app.observability.CapabilitiesChangeRegistry;
import io.justsearch.telemetry.Telemetry;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;

/**
 * SSE endpoint for capability change events at {@code /infra/capabilities/stream}.
 *
 * <p>Per tempdoc 429 §A.4 + slice 436 retrofit + Fix A consolidation: lifecycle /
 * resume / heartbeat / broadcast forwarding live in {@link SseEnvelopeWriter#attach};
 * this controller only supplies the snapshot extras. The capabilities snapshot extras
 * carry {@code detail: "initial"} (the FE fetches the actual capability snapshot via
 * the REST {@code /infra/capabilities} endpoint; this lifecycle frame is the wire-level
 * "I just connected" signal).
 *
 * <p>Wire shape: universal envelope with constant SSE event name {@code "frame"};
 * UPDATE frames forward {@link CapabilitiesChangeRegistry} broadcasts; resume via
 * {@code ?since=token}.
 */
public final class CapabilitiesStreamController {

  private static final long HEARTBEAT_SECONDS = StreamLivenessWindows.STREAM_HEARTBEAT_INTERVAL_SECONDS;

  private final CapabilitiesChangeRegistry changes;

  // Telemetry retained on the constructor for parity; future SSE-volume metrics will read it.
  @SuppressWarnings("unused")
  private final Telemetry telemetry;

  private final EngineExecutorRegistry.Registration heartbeatRegistration;
  private final ScheduledExecutorService heartbeatScheduler;
  private final Clock clock;

  public CapabilitiesStreamController(
      EngineExecutorRegistry processExecutors,
      CapabilitiesChangeRegistry changes, Telemetry telemetry) {
    this(processExecutors, changes, telemetry, Clock.systemUTC());
  }

  public CapabilitiesStreamController(
      EngineExecutorRegistry processExecutors,
      CapabilitiesChangeRegistry changes, Telemetry telemetry, Clock clock) {
    this.changes = Objects.requireNonNull(changes, "changes");
    this.telemetry = telemetry;
    this.clock = Objects.requireNonNull(clock, "clock");
    SchedulerResources resources =
        openHeartbeatScheduler(
            processExecutors,
            "head.capabilities-stream-heartbeat",
            "capabilities-stream-heartbeat");
    this.heartbeatRegistration = resources.registration();
    this.heartbeatScheduler = resources.scheduler();
  }

  public void handle(SseClient sseClient) {
    SseEnvelopeWriter.attach(
        sseClient,
        changes.channel(),
        // Slice 3a-1-8 Phase 5a: opt-in adopter of per-envelope contract version
        // tagging. The snapshot extras are wrapped with `contractVersion` so FE
        // consumers exercising the runtime-continuous capability can detect
        // cross-version reads. Other endpoints' frames remain untagged per the
        // substrate's capability-vs-mandate discipline.
        () -> WireContractVersion.tagPayload(Map.of("detail", "initial")),
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
