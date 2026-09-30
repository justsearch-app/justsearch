/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.javalin.http.sse.SseClient;
import io.justsearch.app.api.stream.StreamId;
import io.justsearch.app.observability.intent.IntentEnvelopeChangeRegistry;
import io.justsearch.app.observability.operations.PendingAuthorizationChangeRegistry;
import io.justsearch.app.observability.stream.SseStreamChannel;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;

/**
 * SSE endpoint at {@code GET /api/shell-events/stream} — tempdoc 662's cross-channel
 * multiplexer. Aggregates the 6 streams that were previously independent always-on
 * EventSources (which exhausted the browser's ~6-per-host connection pool, starving the cheap
 * {@code /api/status}/{@code /api/inference/status} polls under load — tempdoc 649) onto ONE
 * physical connection via {@link MultiplexedSseWriter}: intent, the three advisory classes
 * (operation-completed, health-recoverable, authorization-pending — tempdoc 655's long-term
 * design pass added the third), action-ledger, and indexing-jobs.
 *
 * <p>This controller only ASSEMBLES the 7 {@link MultiplexedSseWriter.ChannelSource}s — it does
 * NOT re-derive any controller's channel-lookup or snapshot-extraction logic. Each source is
 * obtained via the package-visible {@code channel()}/{@code snapshotExtras()} accessors added
 * to the existing single-channel controllers (tempdoc 662 — extend, don't fork): {@link
 * AdvisoryStreamController}, {@link ActionLedgerController}, {@link IndexingJobsStreamController}.
 * Intent and pending-authorizations (tempdoc 655) have no analogous controller-side reuse beyond
 * {@link IntentEnvelopeChangeRegistry#channel()} / {@link PendingAuthorizationChangeRegistry
 * #channel()} — both are event-only (see {@link SseEnvelopeWriter#attachEventOnly}'s contract,
 * mirrored here via a {@code null} snapshot supplier).
 */
public final class ShellEventsStreamController {

  private static final long HEARTBEAT_SECONDS = StreamLivenessWindows.STREAM_HEARTBEAT_INTERVAL_SECONDS;

  /**
   * Dedicated pseudo-channel for this connection's shared heartbeat only — never one of the 6
   * subscribed data channels (a heartbeat is per-client lifecycle data, not real channel state;
   * see {@link MultiplexedSseWriter}'s class doc). Shared across connections: heartbeat frames
   * are never resumed/replayed, so a process-lifetime seq counter is harmless.
   */
  private static final StreamId HEARTBEAT_STREAM_ID = StreamId.system("shell-events-heartbeat");

  private final IntentEnvelopeChangeRegistry intentChanges;
  private final AdvisoryStreamController operationCompletedAdvisory;
  private final AdvisoryStreamController healthRecoverableAdvisory;
  private final AdvisoryStreamController authorizationPendingAdvisory;
  private final ActionLedgerController actionLedger;
  private final IndexingJobsStreamController indexingJobs;
  private final PendingAuthorizationChangeRegistry pendingAuthorizationChanges;
  private final SseStreamChannel heartbeatChannel;
  private final EngineExecutorRegistry.Registration heartbeatRegistration;
  private final ScheduledExecutorService heartbeatScheduler;
  private final Clock clock;

  public ShellEventsStreamController(
      EngineExecutorRegistry processExecutors,
      IntentEnvelopeChangeRegistry intentChanges,
      AdvisoryStreamController operationCompletedAdvisory,
      AdvisoryStreamController healthRecoverableAdvisory,
      AdvisoryStreamController authorizationPendingAdvisory,
      ActionLedgerController actionLedger,
      IndexingJobsStreamController indexingJobs,
      PendingAuthorizationChangeRegistry pendingAuthorizationChanges) {
    this(processExecutors, intentChanges,
        operationCompletedAdvisory,
        healthRecoverableAdvisory,
        authorizationPendingAdvisory,
        actionLedger,
        indexingJobs,
        pendingAuthorizationChanges,
        Clock.systemUTC());
  }

  public ShellEventsStreamController(
      EngineExecutorRegistry processExecutors,
      IntentEnvelopeChangeRegistry intentChanges,
      AdvisoryStreamController operationCompletedAdvisory,
      AdvisoryStreamController healthRecoverableAdvisory,
      AdvisoryStreamController authorizationPendingAdvisory,
      ActionLedgerController actionLedger,
      IndexingJobsStreamController indexingJobs,
      PendingAuthorizationChangeRegistry pendingAuthorizationChanges,
      Clock clock) {
    this.intentChanges = Objects.requireNonNull(intentChanges, "intentChanges");
    this.operationCompletedAdvisory =
        Objects.requireNonNull(operationCompletedAdvisory, "operationCompletedAdvisory");
    this.healthRecoverableAdvisory =
        Objects.requireNonNull(healthRecoverableAdvisory, "healthRecoverableAdvisory");
    this.authorizationPendingAdvisory =
        Objects.requireNonNull(authorizationPendingAdvisory, "authorizationPendingAdvisory");
    this.actionLedger = Objects.requireNonNull(actionLedger, "actionLedger");
    this.indexingJobs = Objects.requireNonNull(indexingJobs, "indexingJobs");
    this.pendingAuthorizationChanges =
        Objects.requireNonNull(pendingAuthorizationChanges, "pendingAuthorizationChanges");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.heartbeatChannel = new SseStreamChannel(HEARTBEAT_STREAM_ID);
    SchedulerResources resources =
        openHeartbeatScheduler(
            processExecutors,
            "head.shell-events-stream-heartbeat",
            "shell-events-stream-heartbeat");
    this.heartbeatRegistration = resources.registration();
    this.heartbeatScheduler = resources.scheduler();
  }

  public void handle(SseClient sseClient) {
    List<MultiplexedSseWriter.ChannelSource> sources =
        List.of(
            new MultiplexedSseWriter.ChannelSource(intentChanges.channel(), null),
            new MultiplexedSseWriter.ChannelSource(
                operationCompletedAdvisory.channel(), operationCompletedAdvisory::snapshotExtras),
            new MultiplexedSseWriter.ChannelSource(
                healthRecoverableAdvisory.channel(), healthRecoverableAdvisory::snapshotExtras),
            new MultiplexedSseWriter.ChannelSource(
                authorizationPendingAdvisory.channel(), authorizationPendingAdvisory::snapshotExtras),
            new MultiplexedSseWriter.ChannelSource(actionLedger.channel(), actionLedger::snapshotExtras),
            new MultiplexedSseWriter.ChannelSource(indexingJobs.channel(), indexingJobs::snapshotExtras),
            new MultiplexedSseWriter.ChannelSource(pendingAuthorizationChanges.channel(), null));
    MultiplexedSseWriter.attachAll(
        sseClient, sources, heartbeatChannel, clock, heartbeatScheduler, HEARTBEAT_SECONDS);
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
