/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.observability.health;

import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.app.services.lifecycle.InferenceCapability;
import io.justsearch.app.services.lifecycle.WorkerCapability;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 876 §B.2a: a reconciliation trigger that is not an HTTP request.
 *
 * <p><b>The defect, as it stood in 876.</b> Every health tap in the head reconciled only from
 * inside {@code StatusLifecycleHandler}'s {@code GET /api/status} handler. {@link
 * LifecycleSnapshotTap} is the sole writer that asserts or clears {@code index.unavailable}, and
 * its only production caller is that handler; the same holds for {@link WorkerSnapshotTap}, the
 * index-drift tap, the at-rest tap and the conversation-protection tap. Request-driven
 * reconciliation is a <em>cache</em>, not a state: the browser's ~10 s poll is the only reason the
 * condition store has ever looked correct. A client that does not poll — the MCP dev tools, an eval
 * harness, a direct {@code POST /api/chat/agent} — inherits whatever the last request left behind,
 * indefinitely. Tempdoc 868 §C.3 recorded the consequence: an agent run was offered neither {@code
 * core_search_index} nor {@code core_read_document} because a stale {@code index.unavailable}
 * assertion was never cleared, and the model improvised a nonsense call instead.
 *
 * <p><b>What this adds.</b> A second <em>trigger</em> for the existing computation — never a second
 * authority. {@code StatusLifecycleHandler.buildReadinessEnvelope} remains the one place the
 * readiness envelope is derived; this class only causes the same snapshot to be recomputed (and
 * therefore the same taps to reconcile) when a capability transitions, rather than when someone
 * asks.
 *
 * <p><b>Where it ended up (tempdoc 885 item 6).</b> This trigger's thunk is now the Worker status
 * <em>sampler</em>: it performs the one {@code IndexStatus} unary AND is the only path that
 * reconciles the taps, which no longer run on a request thread at all. So the relationship in the
 * paragraph above is inverted — {@code GET /api/status} reads what this trigger last left behind,
 * rather than this trigger being a second way to reach what a request would have done.
 *
 * <p>The shape mirrors {@code CapabilityHealthBridge.wireListeners} one layer down: subscribe to
 * {@link WorkerCapability} / {@link InferenceCapability} transitions, and replay current state at
 * wire time so a transition that happened before the listener existed is not lost. Here the replay
 * is {@link #attach(Runnable)}'s self-seed — the ui side supplies its thunk late, well after the
 * capabilities have been driven.
 *
 * <p>Execution discipline: the thunk runs on a single daemon thread, never on the transition
 * thread (it performs a Worker gRPC call, and a health reconciliation must never be able to stall
 * or kill a capability transition). Requests coalesce — a burst of transitions produces one
 * reconcile, not N — and every failure is swallowed, so a throwing thunk can neither propagate into
 * a capability listener nor wedge the trigger.
 */
public final class ReadinessReconciliationTrigger implements AutoCloseable {

  private static final Logger log =
      LoggerFactory.getLogger(ReadinessReconciliationTrigger.class);

  private final EngineExecutorRegistry.Registration executorRegistration;
  private final ExecutorService executor;

  public ReadinessReconciliationTrigger(EngineExecutorRegistry processExecutors) {
    Objects.requireNonNull(processExecutors, "processExecutors");
    EngineExecutorRegistry.Limits background =
        processExecutors.limits(EngineExecutorSpec.Kind.BACKGROUND);
    EngineExecutorRegistry.Registration registration =
        processExecutors.register(
            new EngineExecutorSpec(
                "head.readiness-reconcile",
                EngineExecutorSpec.Kind.BACKGROUND,
                EngineExecutorSpec.Mode.PLATFORM,
                1,
                background.maxQueue(),
                1));
    try {
      ExecutorService executor =
          registration.open(
              runnable -> {
                Thread thread = new Thread(runnable, "readiness-reconcile");
                thread.setDaemon(true);
                return thread;
              });
      this.executorRegistration = registration;
      this.executor = executor;
    } catch (RuntimeException | Error failure) {
      try {
        registration.close();
      } catch (RuntimeException | Error cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }
  }

  /** Coalescing latch: true while exactly one reconcile is queued and not yet started. */
  private final AtomicBoolean pending = new AtomicBoolean(false);

  private final AtomicBoolean closed = new AtomicBoolean(false);

  /** Late-bound by the ui side; null until {@link #attach(Runnable)}. */
  private volatile Runnable reconcile;

  /**
   * Binds the "recompute the readiness snapshot" thunk and immediately requests one reconcile.
   *
   * <p>The self-seed is the point: capability transitions may all have happened before the ui side
   * existed to attach, so without it the first reconcile would wait for a transition that already
   * occurred.
   */
  public void attach(Runnable reconcile) {
    this.reconcile = reconcile;
    request();
  }

  /**
   * Subscribes {@link #request()} to worker + inference capability transitions. Null-tolerant on
   * either argument (test wiring supplies partial capability graphs).
   */
  public void wireTo(WorkerCapability worker, InferenceCapability inference) {
    if (worker != null) {
      worker.addListener((prev, next) -> request());
    }
    if (inference != null) {
      inference.addListener((prev, next) -> request());
    }
  }

  /**
   * Requests one reconcile. No-op when no thunk is attached or after {@link #close()}. Coalescing:
   * if a reconcile is already queued this call adds nothing. The queued task clears the flag before
   * running the thunk, so a transition arriving mid-run schedules exactly one follow-up.
   */
  public void request() {
    if (closed.get() || reconcile == null) {
      return;
    }
    if (!pending.compareAndSet(false, true)) {
      return;
    }
    try {
      executor.execute(this::runOnce);
    } catch (RejectedExecutionException e) {
      pending.set(false);
      log.debug("Readiness reconcile rejected (trigger closing): {}", e.getMessage());
    }
  }

  private void runOnce() {
    pending.set(false);
    Runnable thunk = this.reconcile;
    if (thunk == null || closed.get()) {
      return;
    }
    try {
      thunk.run();
    } catch (Exception e) {
      log.debug("Readiness reconcile failed: {}", e.getMessage());
    }
  }

  /** Idempotent. Stops the daemon thread and makes further {@link #request()} calls no-ops. */
  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    executor.shutdownNow();
    executorRegistration.close();
  }
}
