/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.AgentRequest;
import io.justsearch.agent.api.AgentService;
import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.app.api.operations.OperationReceipt;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
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
  private static final tools.jackson.databind.ObjectMapper MAPPER = new tools.jackson.databind.ObjectMapper();
  private final OperationAttemptRunner attempts;
  private final Object timerLock = new Object();
  // Bounded by the registered scheduler queue. Handles are live capabilities, not a second ledger.
  private final java.util.Set<OperationAttemptRunner.PreparedAttempt> pending = new java.util.HashSet<>();

  private static final Logger LOG = LoggerFactory.getLogger(BackgroundRunService.class);

  private final AgentService agentService;
  private final EngineExecutorRegistry.Registration schedulerRegistration;
  private final ScheduledExecutorService scheduler;
  private final io.justsearch.app.api.EngineAdmissionService admission;
  private final AtomicBoolean closed = new AtomicBoolean();

  public BackgroundRunService(OperationAttemptRunner attempts, AgentService agentService, EngineExecutorRegistry processExecutors) {
    this(attempts, agentService, processExecutors, null);
  }

  public BackgroundRunService(
      OperationAttemptRunner attempts,
      AgentService agentService,
      EngineExecutorRegistry processExecutors,
      io.justsearch.app.api.EngineAdmissionService admission) {
    this.attempts = Objects.requireNonNull(attempts, "attempts");
    this.agentService = Objects.requireNonNull(agentService, "agentService");
    attempts.reconcile(OperationKind.SCHEDULED_RUN, this::reconcileInterrupted);
    this.admission = admission;
    Objects.requireNonNull(processExecutors, "processExecutors");
    var resources = openScheduler(processExecutors);
    this.schedulerRegistration = resources.registration();
    this.scheduler = resources.scheduler();
  }

  private OperationAttemptRunner.Reconciliation reconcileInterrupted(io.justsearch.app.api.operations.OperationRecord row) {
    String runId = row.checkpointCursor();
    Map<String, Object> snapshot = runId == null ? null : agentService.sessionSnapshot(runId);
    var state = io.justsearch.agent.api.lifecycle.LifecycleState.parse(snapshot == null ? null : snapshot.get("state"));
    if (state == io.justsearch.agent.api.lifecycle.LifecycleState.DONE) {
      return new OperationAttemptRunner.Reconciliation.Complete(new OperationReceipt("SUCCESS", runId));
    }
    if (state == io.justsearch.agent.api.lifecycle.LifecycleState.ERROR) {
      return new OperationAttemptRunner.Reconciliation.Failed(new OperationReceipt("AGENT_RUN_FAILED", runId));
    }
    if (state == io.justsearch.agent.api.lifecycle.LifecycleState.CANCELLED) {
      return new OperationAttemptRunner.Reconciliation.Cancelled(new OperationReceipt("cancelled", runId));
    }
    // Existing interactive producers cannot replay a private prompt from its identity digest.
    // Durable resume eligibility/authority remains the required C2-8 owner policy.
    return row.context().survival() == EngineContext.Survival.INTERACTIVE
        ? new OperationAttemptRunner.Reconciliation.Failed(new OperationReceipt("interrupted_by_restart", runId))
        : new OperationAttemptRunner.Reconciliation.Wait();
  }

  private static SchedulerResources openScheduler(EngineExecutorRegistry processExecutors) {
    EngineExecutorRegistry.Limits background =
        processExecutors.limits(EngineExecutorSpec.Kind.BACKGROUND);
    EngineExecutorRegistry.Registration registration =
        processExecutors.register(
            new EngineExecutorSpec(
                "head.background-agent-run",
                EngineExecutorSpec.Kind.BACKGROUND,
                EngineExecutorSpec.Mode.SCHEDULED,
                1,
                background.maxQueue(),
                1));
    try {
      ScheduledExecutorService scheduler =
          registration.openScheduled(
              runnable -> {
                Thread thread = new Thread(runnable, "background-agent-run");
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

  /**
   * Run an agent request to completion in the BACKGROUND (no watcher), blocking the calling thread.
   * Captures the run's sessionId from its {@link AgentEvent.SessionStarted} so the completed run can
   * be stamped {@code background=true}. Returns the sessionId (or null if the run never started).
   */
  public String runInBackground(AgentRequest request, EngineContext engineContext) {
    EngineContext child = freshChild(engineContext);
    var prepared = accept(request, Duration.ZERO, child);
    return runAccepted(request, child, prepared).executionId().orElse(null);
  }

  private OperationAttemptRunner.PreparedAttempt accept(AgentRequest request, Duration delay, EngineContext child) {
    Objects.requireNonNull(request, "request");
    String arguments = MAPPER.writeValueAsString(Map.of("request", request, "delayMillis", Math.max(0, delay.toMillis())));
    return attempts.accept(new OperationAttemptRunner.Request(null,
        OperationDescriptor.invocation(OperationKind.SCHEDULED_RUN, null, arguments, false), child,
        InvocationProvenance.fromEngineContext(child, ExecutorTag.AGENT, Clock.systemUTC().instant(), Optional.empty())));
  }

  private static EngineContext freshChild(EngineContext context) {
    Objects.requireNonNull(context, "context");
    return new EngineContext(context.clientKind(), context.clientId(), context.sessionId(),
        context.grantReference(), context.sourceTier(), context.transport(), context.survival(), context.urgency());
  }

  private OperationResult runAccepted(AgentRequest request, EngineContext child,
      OperationAttemptRunner.PreparedAttempt prepared) {
    try (var work = admission == null ? null : admission.admit(child, false)) {
      return attempts.start(prepared, handle -> {
        AtomicReference<String> sessionId = new AtomicReference<>();
        Consumer<AgentEvent> capture = event -> {
          if (event instanceof AgentEvent.SessionStarted started) {
            sessionId.set(started.sessionId());
            // The existing durable agent run is the resume/outcome authority, not its event stream.
            handle.checkpoint(started.sessionId(), 0, 0);
          }
        };
        agentService.runAgent(request, capture, true, work == null ? child : work.context());
        String id = sessionId.get();
        Map<String, Object> snapshot = id == null ? null : agentService.sessionSnapshot(id);
        var state = io.justsearch.agent.api.lifecycle.LifecycleState.parse(snapshot == null ? null : snapshot.get("state"));
        if (state == io.justsearch.agent.api.lifecycle.LifecycleState.CANCELLED) {
          throw new CancellationException("Background agent run cancelled");
        }
        if (state != io.justsearch.agent.api.lifecycle.LifecycleState.DONE) {
          return OperationExecution.finished(OperationResult.failure("Background agent run did not complete",
              state == io.justsearch.agent.api.lifecycle.LifecycleState.ERROR ? "AGENT_RUN_FAILED" : "AGENT_OUTCOME_MISSING",
              Map.of(), false));
        }
        handle.checkpoint(id, 1, 0);
        return OperationExecution.finished(new OperationResult(true, "Background agent run completed",
            Optional.of(id), Map.of(), Optional.empty(), Map.of(), Optional.empty()));
      }).response();
    } catch (RuntimeException failure) {
      reject(prepared, failure);
      throw failure;
    }
  }

  private void reject(OperationAttemptRunner.PreparedAttempt prepared, RuntimeException failure) {
    String reason = switch (failure) {
      case io.justsearch.app.api.EngineAdmissionException denied -> "ADMISSION_" + denied.reason().name();
      case io.justsearch.core.execution.EngineExecutorRejectedException denied -> "EXECUTOR_" + denied.reason().name();
      case RejectedExecutionException ignored -> "EXECUTOR_CLOSED";
      default -> "UNCAUGHT_EXCEPTION";
    };
    try { attempts.rejectBeforeStart(prepared, reason); }
    catch (RuntimeException persistenceFailure) { failure.addSuppressed(persistenceFailure); }
  }

  /** Acceptance is durable before the timer exists; the returned handle observes actual completion. */
  public OperationAttemptRunner.PreparedAttempt schedule(AgentRequest request, Duration delay, EngineContext engineContext) {
    Objects.requireNonNull(delay, "delay");
    EngineContext child = freshChild(engineContext);
    var prepared = accept(request, delay, child);
    try {
      synchronized (timerLock) {
        if (closed.get()) throw new RejectedExecutionException("Background scheduler is closed");
        pending.add(prepared);
        var unused = scheduler.schedule(() -> fire(request, child, prepared), Math.max(0, delay.toMillis()),
            java.util.concurrent.TimeUnit.MILLISECONDS);
      }
    } catch (RuntimeException failure) {
      synchronized (timerLock) { pending.remove(prepared); }
      reject(prepared, failure);
      throw failure;
    }
    return prepared;
  }

  private void fire(AgentRequest request, EngineContext child, OperationAttemptRunner.PreparedAttempt prepared) {
    synchronized (timerLock) {
      if (!pending.remove(prepared)) return; // Shutdown already owns its no-effect refusal.
    }
    try { runAccepted(request, child, prepared); }
    catch (RuntimeException failure) {
      // The runner owns its durable failed outcome; logging also exposes failed outcome persistence.
      LOG.warn("Background agent run failed", failure);
    }
  }

  /** Stop the scheduler and truthfully refuse each accepted timer that will never run. */
  public void shutdown() {
    List<OperationAttemptRunner.PreparedAttempt> cancelled;
    synchronized (timerLock) {
      if (!closed.compareAndSet(false, true)) return;
      cancelled = List.copyOf(pending);
      pending.clear();
      scheduler.shutdownNow();
    }
    RuntimeException failure = null;
    try {
      for (var prepared : cancelled) {
        try { attempts.rejectBeforeStart(prepared, "ENGINE_SHUTDOWN"); }
        catch (RuntimeException persistenceFailure) {
          if (failure == null) failure = persistenceFailure;
          else failure.addSuppressed(persistenceFailure);
        }
      }
    } finally { schedulerRegistration.close(); }
    if (failure != null) throw failure;
  }

  private record SchedulerResources(
      EngineExecutorRegistry.Registration registration, ScheduledExecutorService scheduler) {}
}
