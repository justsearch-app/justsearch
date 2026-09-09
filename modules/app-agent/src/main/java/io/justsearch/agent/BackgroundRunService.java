/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.AgentRequest;
import io.justsearch.agent.api.AgentService;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 561 P-D2 — the presence axis's REAL background producer. The agent loop already runs
 * server-side; an INTERACTIVE run streams its {@link AgentEvent}s to a watching SSE client. A
 * BACKGROUND run is the same loop driven WITHOUT a watcher: events go to a no-op sink, the run
 * proceeds to completion, persists to the ONE durable {@code AgentRunStore} record, and is stamped
 * {@code background=true} so the render-on-return inbox ({@code AgentService.presenceSince}) can
 * surface "what completed while you were away".
 *
 * <p>This is what Appendix C said did not exist ("no scheduled/background agent-run PRODUCER today"),
 * built now that the §7/C-018 brake is overridden: a non-interactive trigger that produces real runs.
 * {@link #runInBackground} is the immediate producer; {@link #schedule} is the scheduled producer (a
 * single-thread {@link ScheduledExecutorService} — a real timer, not an FE-only banner).
 */
public final class BackgroundRunService {

  private static final Logger LOG = LoggerFactory.getLogger(BackgroundRunService.class);

  private final AgentService agentService;
  private final ScheduledExecutorService scheduler;
  private final io.justsearch.app.api.EngineAdmissionService admission;

  public BackgroundRunService(AgentService agentService) {
    this(agentService, defaultScheduler(), null);
  }

  public BackgroundRunService(AgentService agentService, io.justsearch.app.api.EngineAdmissionService admission) {
    this(agentService, defaultScheduler(), admission);
  }

  BackgroundRunService(AgentService agentService, ScheduledExecutorService scheduler) {
    this(agentService, scheduler, null);
  }

  BackgroundRunService(AgentService agentService, ScheduledExecutorService scheduler,
      io.justsearch.app.api.EngineAdmissionService admission) {
    this.agentService = Objects.requireNonNull(agentService, "agentService");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.admission = admission;
  }

  private static ScheduledExecutorService defaultScheduler() {
    ThreadFactory tf =
        r -> {
          Thread t = new Thread(r, "background-agent-run");
          t.setDaemon(true);
          return t;
        };
    return Executors.newSingleThreadScheduledExecutor(tf);
  }

  /**
   * Run an agent request to completion in the BACKGROUND (no watcher), blocking the calling thread.
   * Captures the run's sessionId from its {@link AgentEvent.SessionStarted} so the completed run can
   * be stamped {@code background=true}. Returns the sessionId (or null if the run never started).
   */
  public String runInBackground(AgentRequest request, EngineContext engineContext) {
    Objects.requireNonNull(request, "request");
    AtomicReference<String> sessionId = new AtomicReference<>();
    Consumer<AgentEvent> capture =
        ev -> {
          if (ev instanceof AgentEvent.SessionStarted started) {
            sessionId.set(started.sessionId());
          }
        };
    // A presence callback starts distinct work. Never retain a finished scheduling request's id.
    var child = new EngineContext(engineContext.clientKind(), engineContext.clientId(),
        engineContext.sessionId(), engineContext.grantReference(), engineContext.sourceTier(),
        engineContext.transport(), engineContext.survival(), engineContext.urgency());
    try (var work = admission == null ? null : admission.admit(child, false)) {
      // Tempdoc 561 P-D: background=true makes the run safe-by-default (the safety gate rejects
      // write/destructive tool calls — no watcher) AND marks the durable record background inside
      // AgentLoopService, so the presence projection (presenceSince) surfaces it on the user's return.
      agentService.runAgent(request, capture, true, work == null ? child : work.context());
    } catch (RuntimeException e) {
      LOG.warn("Background agent run failed", e);
    }
    return sessionId.get();
  }

  /**
   * Schedule a background run to start after {@code delay} (the scheduled-producer flavor of the
   * presence axis). Returns immediately; the run executes on the background scheduler thread.
   */
  public void schedule(AgentRequest request, Duration delay, EngineContext engineContext) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(delay, "delay");
    scheduler.schedule(() -> runInBackground(request, engineContext), Math.max(0, delay.toMillis()),
        java.util.concurrent.TimeUnit.MILLISECONDS);
  }

  /** Stop the scheduler (lifecycle shutdown). */
  public void shutdown() {
    scheduler.shutdownNow();
  }
}
