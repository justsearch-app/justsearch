/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import java.util.LinkedHashMap;
import java.util.Map;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Tempdoc 604 / 859 D live-defect D2 — the out-of-band LIVENESS heartbeat for a BLOCKING SSE
 * handler, extracted from {@link AgentController} so every route that streams a run can carry it.
 *
 * <p><b>Why a stream needs one at all.</b> An agent run's SSE stream is event-only: while the run is
 * parked at a gate — an approval, a budget decision, a context decision — it emits NOTHING. The FE
 * cannot tell that from a hung transport, so its liveness watchdog ({@code STREAM_WATCHDOG_STALE_MS},
 * 40 s) declares the stream dead and the panel goes stale. A parked run's whole purpose is to WAIT
 * for a human, so a stream with no heartbeat makes every gate unanswerable after 40 seconds.
 *
 * <p><b>Why this class exists rather than a second private copy.</b> 604 gave the heartbeat to
 * {@link AgentController}'s own routes only. Search v3 dispatches its delegate runs through {@code
 * POST /api/chat/dispatch} ({@link ChatController}), which had none — so live leg L2 (2026-08-25)
 * watched three parked gates die at 40 s and time out server-side into {@code BUDGET_EDGE_FINALIZE}.
 * The fix is the same mechanism on that route, and the mechanism living in ONE place is what keeps
 * the next streaming route from re-acquiring the defect.
 *
 * <p>The beat is written via {@link SseWriter#writeEvent} (per-context synchronized, so it
 * interleaves safely with the run's own event writes), carries no trace span (so it never enters a
 * replay buffer), and is not an {@code AgentEvent} (so it does not touch the closed event-vocabulary
 * contract). The FE ignores it as an unmapped event and resets its watchdog on it.
 */
final class SseHeartbeat {

  /** A streaming body that blocks the handler thread until the run terminates; may throw. */
  @FunctionalInterface
  interface StreamBody {
    void run() throws Exception;
  }

  /**
   * Where a beat is written. A seam rather than a hard {@link SseWriter} dependency because the two
   * callers hold different write facades ({@link ChatController} the raw writer, {@link
   * AgentController} the run-observer {@code AgentSseWriter}) — and a beat must NOT go through the
   * observer path, whose failed write evicts the observer.
   */
  @FunctionalInterface
  interface BeatSink {
    void write(Context ctx, String event, Map<String, ?> payload);
  }

  private final BeatSink sink;
  private final ScheduledExecutorService scheduler;
  private final long intervalMs;
  private final EngineExecutorRegistry.Registration executorOwner;

  SseHeartbeat(EngineExecutorRegistry executors, BeatSink sink, String threadName) {
    this.sink = sink;
    this.intervalMs = StreamLivenessWindows.STREAM_HEARTBEAT_INTERVAL_MS;
    var limits = executors.limits(EngineExecutorSpec.Kind.BACKGROUND);
    this.executorOwner = executors.register(new EngineExecutorSpec(
        "head." + threadName, EngineExecutorSpec.Kind.BACKGROUND,
        EngineExecutorSpec.Mode.SCHEDULED, 1, limits.maxQueue(), 1));
    try {
      this.scheduler = executorOwner.openScheduled(Thread.ofPlatform().daemon().name(threadName).factory());
    } catch (RuntimeException | Error failure) {
      try { executorOwner.close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
      throw failure;
    }
  }

  /** Test seam: an injected scheduler and a cadence a test can actually wait for. */
  SseHeartbeat(BeatSink sink, ScheduledExecutorService scheduler, long intervalMs) {
    this.sink = sink;
    this.scheduler = scheduler;
    this.intervalMs = intervalMs;
    this.executorOwner = null;
  }

  /**
   * Run a blocking SSE body with a heartbeat beating for its whole life, cancelled the moment the
   * body returns OR throws. The cancel lives in a {@code finally} because the throwing path is the
   * one that matters: a stream that failed must not leave a task writing to a dead socket forever.
   */
  void around(Context ctx, StreamBody body) throws Exception {
    ScheduledFuture<?> heartbeat =
        scheduler.scheduleAtFixedRate(() -> beat(ctx), intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    try {
      body.run();
    } finally {
      heartbeat.cancel(false);
    }
  }

  /** One beat: the frame the FE's watchdog resets on. Package-private so a test can fire it. */
  void beat(Context ctx) {
    Map<String, Object> frame = new LinkedHashMap<>();
    frame.put("ts", System.currentTimeMillis());
    sink.write(ctx, "heartbeat", frame);
  }

  /** Stops the scheduler. Call on shutdown — an unstopped one keeps a live thread after teardown. */
  void shutdown() {
    if (executorOwner != null) executorOwner.close(); else scheduler.shutdownNow();
  }

  /** Test-only (tempdoc 638 PE): whether {@link #shutdown()} has stopped the scheduler. */
  boolean isShutdown() {
    return scheduler.isShutdown();
  }
}
