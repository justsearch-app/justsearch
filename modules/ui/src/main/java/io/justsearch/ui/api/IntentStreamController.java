/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.javalin.http.sse.SseClient;
import io.justsearch.app.observability.intent.IntentEnvelopeChangeRegistry;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;

/**
 * SSE endpoint at {@code GET /api/intent/stream}.
 *
 * <p>Per tempdoc 487 §4.3: always-on intent envelope stream — the
 * {@code SseIntentForwardingTransport} realization. The FE
 * {@code EnvelopeStream} subscriber boots once at app start, dedups by the
 * envelope's stable {@code payload.id} against a bounded LRU, and dispatches
 * received {@code Intent} envelopes into the existing FE
 * {@code IntentRouter.dispatch}.
 *
 * <p>Wire shape: universal envelope (slice 436) with constant SSE event name
 * {@code "frame"}; per-frame data is the {@code SseEnvelope} JSON. UPDATE
 * frames carry {@link io.justsearch.app.observability.intent.IntentEnvelopeEvent}
 * payloads with the constant {@code "kind": "intent.envelope"} discriminator.
 *
 * <p><strong>Event-only stream (tempdoc §4.3).</strong> Unlike the four
 * state-shaped always-on streams ({@code /api/runtime-context/stream},
 * {@code /api/health/events/stream},
 * {@code /api/advisory/operation-completed/stream}, {@code /infra/capabilities/stream}),
 * this controller does NOT emit a {@code snapshot} lifecycle frame on
 * subscribe — intent envelopes are events, not state. {@code connected} is
 * sent on subscribe; live UPDATE forwarding starts immediately. On
 * reconnect-miss (resume-token-out-of-window), {@code reset} is emitted
 * alone (no snapshot) and the FE clears its dedup LRU and resumes. Uses the
 * {@link SseEnvelopeWriter#attachEventOnly} helper.
 */
public final class IntentStreamController {

  private static final long HEARTBEAT_SECONDS = StreamLivenessWindows.STREAM_HEARTBEAT_INTERVAL_SECONDS;

  private final IntentEnvelopeChangeRegistry changes;
  private final EngineExecutorRegistry.Registration heartbeatRegistration;
  private final ScheduledExecutorService heartbeatScheduler;
  private final Clock clock;

  public IntentStreamController(
      EngineExecutorRegistry processExecutors,
      IntentEnvelopeChangeRegistry changes) {
    this(processExecutors, changes, Clock.systemUTC());
  }

  public IntentStreamController(
      EngineExecutorRegistry processExecutors,
      IntentEnvelopeChangeRegistry changes, Clock clock) {
    this.changes = Objects.requireNonNull(changes, "changes");
    this.clock = Objects.requireNonNull(clock, "clock");
    SchedulerResources resources =
        openHeartbeatScheduler(
            processExecutors,
            "head.intent-stream-heartbeat",
            "intent-stream-heartbeat");
    this.heartbeatRegistration = resources.registration();
    this.heartbeatScheduler = resources.scheduler();
  }

  public void handle(SseClient sseClient) {
    SseEnvelopeWriter.attachEventOnly(
        sseClient, changes.channel(), clock, heartbeatScheduler, HEARTBEAT_SECONDS);
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
