/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.core.context.EngineContext;

import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.services.lifecycle.WorkerCapability;
import java.io.Closeable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Background health monitor for the Worker (the index half, in-process since lane F stage A item
 * A6). Polls {@link KnowledgeServerBootstrap#checkHealth()} and triggers deferred auxiliary
 * initialization on ERROR→READY recovery transitions.
 *
 * <p>Item A11 note: this class survives the Worker's deletion as a process because two of its
 * three jobs are not process-shaped — the health poll that drives the worker component's
 * READY/LOST transitions on {@code /api/health}, and the boot-recovery arm (an in-process start
 * can still fail on a Lucene open, and the surface must say so). What it lost is every
 * channel/port/process-shaped path: the post-resume reconnect, and the escalation from a lost
 * worker to a respawn. A lost worker component now reports itself lost and stays that way until
 * the user restarts the Engine — stage A §10 records that as a deliberate loss until stage B.
 *
 * <p>Tempdoc 630 (latency-hardening): this periodic loop doubles as the Head-side <b>resume
 * detector</b>. Because it wakes every {@code pollIntervalMs}, an inter-tick wall-clock gap far
 * larger than that interval means the process was frozen — the machine suspended and resumed (see
 * {@link ResumeDetector}). On a detected resume the monitor <b>eagerly</b> reconnects the gRPC
 * channel and re-registers watchers + reconciles, instead of waiting for the reactive recovery
 * (first post-wake RPC reconnects; periodic sync eventually re-walks). Done before {@link
 * KnowledgeServerBootstrap#checkHealth()} so the first post-wake tick checks a fresh channel rather
 * than flipping the capability to DEGRADED on a stale one.
 *
 * <p><b>Tempdoc 825 — one monitor authority, two arms.</b> This monitor is now constructed
 * unconditionally, including when the bootstrap FAILED to start. Before 825 the sole construction
 * site was gated on a non-null bootstrap, so the one outcome that most needed a monitor — the worker
 * never came up — was the one outcome with no monitor at all: {@code /api/health} served 503 for the
 * life of the process and the operator's own escape hatch ({@code POST /api/worker/restart}) 503'd
 * too. Each tick picks its arm from {@link KnowledgeServerBootstrap#hasClient()}:
 *
 * <ul>
 *   <li><b>client bound</b> — today's behaviour: {@link KnowledgeServerBootstrap#checkHealth()} plus
 *       the recovery→READY deferred-initialization hook.
 *   <li><b>no client</b> — the boot-recovery arm: a bounded, backed-off re-attempt of the bootstrap
 *       under {@link BootRecoveryDecision}, ending either in a handover (the API surfaces late-bind
 *       to the now-live worker) or in exactly one terminal
 *       {@code worker.spawn_recovery_exhausted}.
 * </ul>
 *
 * <p>Both arms run on the same single-threaded executor, so an attempt can never overlap a health
 * poll or another attempt — the "one restart authority" constraint from the tempdoc-627 review is
 * structural here, not a convention.
 */
public final class KnowledgeServerHealthMonitor implements Closeable, WorkerRecoveryAuthority {
  private static final EngineContext ENGINE_CONTEXT = io.justsearch.app.services.intent.EngineProvenance.internal(
      "engine-health-monitor", EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.BACKGROUND);
  private static final Logger log = LoggerFactory.getLogger(KnowledgeServerHealthMonitor.class);

  static final long DEFAULT_POLL_INTERVAL_MS = 10_000;

  /**
   * Resume threshold factor (tempdoc 630): a tick is treated as post-resume only if the observed
   * inter-tick gap exceeds {@code pollIntervalMs * RESUME_TOLERANCE_FACTOR}. Generous so ordinary
   * GC / scheduler jitter never false-triggers an eager reconnect+reconcile.
   */
  static final long RESUME_TOLERANCE_FACTOR = 3;

  /** How long {@link #close()} waits for an in-flight boot-recovery attempt to unwind (review F4). */
  static final long CLOSE_AWAIT_MS = 5_000;

  /** Floor for the variable tick interval (tempdoc 885 item 6) — a supplier cannot make it spin. */
  static final long MIN_TICK_INTERVAL_MS = 1_000;

  /**
   * Tempdoc 885 item 6: variable inter-tick delay, installed by the composition root. Null (the
   * default) keeps the fixed {@link #pollIntervalMs} cadence every existing construction site had.
   */
  private volatile LongSupplier tickIntervalSupplier;

  private final KnowledgeServerBootstrap bootstrap;
  private final long pollIntervalMs;
  private final ScheduledExecutorService executor;
  private final LongSupplier nowMs;
  private final BootRecoveryPolicy recoveryPolicy;
  /** Wall-clock (epoch ms) of the previous tick; {@code -1} until the first tick. */
  private volatile long lastTickWallMs = -1;

  /**
   * Tempdoc 825: run once a boot-recovery attempt has bound a client, so the composition root can
   * late-bind the now-live worker into the API surfaces (the handover
   * {@code HeadAssembly.connectKnowledgeServer} + {@code LocalApiServer.lateBindKnowledgeServer}
   * pair). Null in tests and standalone launchers that have no surfaces to bind.
   */
  private volatile Consumer<KnowledgeServerBootstrap> onRecoveryConnected;

  private volatile Runnable onTick = () -> {};

  // --- boot-recovery arm state. Mutated only by the single executor thread (the CAS below admits
  // one attempt at a time), but READ by requestRecoveryNow on the caller's thread, so volatile. ---
  private volatile int recoveryAttemptsMade;
  private volatile long lastRecoveryAttemptMs = -1;
  private volatile boolean recoveryGaveUp;

  /**
   * Which veto (if any) produced {@link #recoveryGaveUp}. Tempdoc 915 R1: the give-up latch is what
   * keeps the terminal state terminal, but an {@link BootRecoveryDecision.Veto#INDEX_FATAL} give-up
   * must not also brick the operator hatch — the operator's remedy changes the very bytes the veto is
   * a function of. Recording WHICH veto latched is what lets exactly that one be re-openable by an
   * explicit request while every other terminal state stays permanent.
   */
  private volatile BootRecoveryDecision.Veto gaveUpVeto;
  private final AtomicBoolean recoveryAttemptRunning = new AtomicBoolean(false);

  /**
   * Review F4: set by {@link #close()} before the executor is shut down, so an attempt that has not
   * yet spawned stands down instead of racing the ordered shutdown. Without it, a recovery attempt
   * running (or queued) while {@code performOrderedShutdown} walks past the monitor could spawn a
   * Worker JVM after the coordinator had already closed the bootstrap — an orphan nothing owns.
   */
  private volatile boolean closed;

  public KnowledgeServerHealthMonitor(KnowledgeServerBootstrap bootstrap) {
    this(bootstrap, DEFAULT_POLL_INTERVAL_MS);
  }

  public KnowledgeServerHealthMonitor(KnowledgeServerBootstrap bootstrap, long pollIntervalMs) {
    this(bootstrap, pollIntervalMs, System::currentTimeMillis);
  }

  /**
   * @param nowMs wall-clock source (epoch ms) for resume detection — injectable so the gap logic is
   *     unit-testable without a real clock or a real OS suspend (tempdoc 630)
   */
  public KnowledgeServerHealthMonitor(
      KnowledgeServerBootstrap bootstrap, long pollIntervalMs, LongSupplier nowMs) {
    this(bootstrap, pollIntervalMs, nowMs, BootRecoveryPolicy.defaults());
  }

  /**
   * @param recoveryPolicy budget for the boot-recovery arm (tempdoc 825) — injectable so a component
   *     test can exercise the give-up path without waiting out the production backoff
   */
  public KnowledgeServerHealthMonitor(
      KnowledgeServerBootstrap bootstrap,
      long pollIntervalMs,
      LongSupplier nowMs,
      BootRecoveryPolicy recoveryPolicy) {
    if (bootstrap == null) {
      throw new IllegalArgumentException("bootstrap must not be null");
    }
    this.bootstrap = bootstrap;
    this.pollIntervalMs = pollIntervalMs > 0 ? pollIntervalMs : DEFAULT_POLL_INTERVAL_MS;
    this.nowMs = nowMs;
    this.recoveryPolicy = recoveryPolicy != null ? recoveryPolicy : BootRecoveryPolicy.defaults();
    this.executor =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "knowledge-server-health-monitor");
              t.setDaemon(true);
              return t;
            });
  }

  public void start() {
    log.info("Knowledge Server health monitor started (poll interval: {}ms)", pollIntervalMs);
    scheduleNextTick(pollIntervalMs);
  }

  /**
   * Tempdoc 885 item 6: install a variable inter-tick delay. The monitor re-arms itself after every
   * tick with {@code supplier.getAsLong()} clamped to {@code [MIN_TICK_INTERVAL_MS, pollIntervalMs]}
   * — so the health sampler it now hosts can observe at 2 s while indexing is in flight and fall
   * back to the configured 10 s when idle, without a second executor.
   *
   * <p>Resume detection deliberately keeps using the CONFIGURED {@code pollIntervalMs} as its
   * reference (not the actual delay): shrinking the reference alongside the delay would shrink the
   * gap threshold to 6 s and turn a long GC pause into a false "the machine resumed" reconnect.
   * With the reference pinned, a faster tick can only make resume detection more conservative.
   */
  public void tickIntervalSupplier(LongSupplier supplier) {
    this.tickIntervalSupplier = supplier;
  }

  private void scheduleNextTick(long delayMs) {
    if (closed) {
      return;
    }
    try {
      @SuppressWarnings("unused")
      var ignored = executor.schedule(this::tickAndReschedule, delayMs, TimeUnit.MILLISECONDS);
    } catch (java.util.concurrent.RejectedExecutionException e) {
      log.debug("Health monitor tick not rescheduled (monitor closing): {}", e.getMessage());
    }
  }

  private void tickAndReschedule() {
    try {
      tick();
    } finally {
      scheduleNextTick(nextTickDelayMs());
    }
  }

  /** The next inter-tick delay, clamped so a bad supplier can neither spin nor stall the monitor. */
  long nextTickDelayMs() {
    LongSupplier supplier = this.tickIntervalSupplier;
    if (supplier == null) {
      return pollIntervalMs;
    }
    long requested;
    try {
      requested = supplier.getAsLong();
    } catch (RuntimeException e) {
      log.debug("Tick-interval supplier failed; falling back to the configured interval", e);
      return pollIntervalMs;
    }
    return Math.max(MIN_TICK_INTERVAL_MS, Math.min(pollIntervalMs, requested));
  }

  void tick() {
    try {
      // Tempdoc 630: detect an OS suspend/resume by the inter-tick wall-clock gap and eagerly
      // re-validate BEFORE the health check. Item A11 removed the channel half of that
      // re-validation (there is no channel); the filesystem half is why this survives — a watcher
      // frozen through a suspend missed every event in the window, and only a reconcile walk
      // catches up. That is a property of the machine sleeping, not of a process boundary.
      long now = nowMs.getAsLong();
      long gap = ResumeDetector.resumeGapMs(lastTickWallMs, now, pollIntervalMs, RESUME_TOLERANCE_FACTOR);
      lastTickWallMs = now;
      if (gap > 0) {
        eagerlyRevalidateAfterResume(gap);
      }

      // Tempdoc 825: pick the arm. No client ⇒ start() never completed, so there is nothing to poll
      // for health and everything to recover.
      if (!bootstrap.hasClient()) {
        runBootRecoveryArm();
        return;
      }

      CapabilityHealth before = bootstrap.workerCapability().health();
      bootstrap.checkHealth();
      CapabilityHealth after = bootstrap.workerCapability().health();
      if (before != CapabilityHealth.READY && after == CapabilityHealth.READY) {
        log.info(
            "Knowledge Server recovered to READY ({}→{}); running deferred auxiliary"
                + " initialization",
            before,
            after);
        bootstrap.completeReadyInitializationFromMonitor();
      }
    } catch (Exception e) {
      log.warn("Knowledge Server health monitor tick failed: {}", e.getMessage(), e);
      WorkerCapability cap = bootstrap.workerCapability();
      if (cap.health() == CapabilityHealth.READY) {
        // Tempdoc 837 §3.1: guarded on READY — the worker was serving, so this is "lost", not
        // "never started". The exception text is the detail behind the code.
        cap.transition(
            CapabilityHealth.DEGRADED,
            LifecycleReasonCode.WORKER_LOST.code(),
            "Health monitor tick exception: " + e.getMessage());
      }
    }
    // Tempdoc 876 §C.8: the worker's operational view has just been refreshed, so this is the
    // moment the readiness snapshot can change WITHOUT a capability transition — INDEX_SERVING
    // settling from NOT_READY to DEGRADED/index.dense_unavailable is exactly that, and it is what
    // left core.search-index gated off until a browser called GET /api/status. Reconciling here
    // reuses the poll the head already runs rather than adding a timer, so the head keeps its
    // health honest at the same cadence whether or not anyone is watching. Outside the catch: a
    // failed check is precisely when the snapshot most needs re-deriving. Fail-soft — the trigger
    // coalesces and swallows, but a broken callback must never stop the monitor.
    try {
      onTick.run();
    } catch (RuntimeException e) {
      log.debug("Readiness reconcile request from monitor tick failed: {}", e.getMessage());
    }
  }

  /**
   * Install a callback run after every tick, once the worker's health has been re-checked.
   * Composition-root wiring, like {@link #onRecoveryConnected}; defaults to a no-op so every
   * existing construction site is unaffected.
   */
  public void onTick(Runnable callback) {
    this.onTick = callback == null ? () -> {} : callback;
  }

  /**
   * Tempdoc 825: install the handover the composition root performs once a recovery attempt has
   * bound a client. Called before {@link #start()} by {@code HeadlessApp}.
   */
  public void onRecoveryConnected(Consumer<KnowledgeServerBootstrap> handover) {
    this.onRecoveryConnected = handover;
  }

  /**
   * The boot-recovery arm (tempdoc 825 §D2). Reads the observable state, asks the pure
   * {@link BootRecoveryDecision} what to do, and executes that verbatim.
   */
  private void runBootRecoveryArm() {
    BootRecoveryDecision.Decision decision =
        BootRecoveryDecision.decide(currentRecoveryInput(), recoveryPolicy);
    switch (decision.action()) {
      case NONE -> {
        // Already recovered, or already terminal. Silence is the correct behaviour.
      }
      case WAIT ->
          log.debug(
              "Boot recovery attempt {} due in {}ms",
              decision.nextAttempt(),
              decision.waitMs());
      case ATTEMPT -> attemptBootRecovery(false, false);
      case GIVE_UP -> narrateGiveUp(decision.veto());
    }
  }

  /** The observable state the boot-recovery decision is a function of. */
  private BootRecoveryDecision.Input currentRecoveryInput() {
    return currentRecoveryInput(false);
  }

  /**
   * @param operatorRequested when true, the {@link BootRecoveryDecision.Veto#INDEX_FATAL} veto is
   *     withheld: the operator's remedy for both fatal index causes is a settings or filesystem
   *     change the NEXT spawn reads, so an explicit request is the one input that can make a
   *     deterministic refusal stop being deterministic (tempdoc 915 R1). The local
   *     attempt bound remains unchanged — this is a reason to try again, not to try more.
   */
  private BootRecoveryDecision.Input currentRecoveryInput(boolean operatorRequested) {
    long sinceLastAttempt =
        lastRecoveryAttemptMs < 0 ? Long.MAX_VALUE : nowMs.getAsLong() - lastRecoveryAttemptMs;
    return new BootRecoveryDecision.Input(
        bootstrap.hasClient(),
        !operatorRequested && bootstrap.indexFatalCode() != null,
        recoveryAttemptsMade,
        // Withholding the veto is not enough on its own: the give-up it produced has already latched,
        // and a latched give-up short-circuits to NONE before any veto is consulted. So an operator
        // request re-opens exactly the INDEX_FATAL terminal state and no other.
        recoveryGaveUp
            && !(operatorRequested && gaveUpVeto == BootRecoveryDecision.Veto.INDEX_FATAL),
        sinceLastAttempt);
  }

  /**
   * One bounded re-attempt of the bootstrap. The capability is held at RECOVERING for the whole arc
   * (the bootstrap suppresses its per-attempt narration while
   * {@link KnowledgeServerBootstrap#startForRecovery()} runs), so a multi-cycle recovery that
   * ultimately succeeds narrates ONE {@code worker.restart-attempted} milestone and ONE
   * {@code worker.recovered}, not a flap per cycle.
   */
  private void attemptBootRecovery(boolean operatorRequested, boolean slotAlreadyHeld) {
    // Review F4: the closed check is INSIDE the running-slot and re-checked after it, because close()
    // waits for an in-flight attempt but must not let a new one start. A spawn begun after
    // performOrderedShutdown has passed the monitor would orphan a worker JVM that nothing closes.
    if (closed && !slotAlreadyHeld) {
      return;
    }
    if (!slotAlreadyHeld && !recoveryAttemptRunning.compareAndSet(false, true)) {
      return;
    }
    try {
      if (closed) {
        return;
      }
      // Review F5: re-decide HERE, on the executor thread, against state read now. The caller's
      // decision is a stale hint: requestRecoveryNow decides on the HTTP thread, and by the time
      // this runs the budget may be spent (N rapid requests), the worker may already be up (a
      // duplicate queued behind a successful handover), or a veto may have appeared. Without this,
      // a burst of manual requests out-spends the declared budget and can re-spawn over a live
      // worker. This is also why the attempt NUMBER comes from the decision rather than the caller.
      BootRecoveryDecision.Decision decision =
          BootRecoveryDecision.decide(currentRecoveryInput(operatorRequested), recoveryPolicy);
      if (decision.action() == BootRecoveryDecision.Action.GIVE_UP) {
        // The budget was spent (or a veto appeared) between the request and this runnable. The
        // decision to stop is still a decision: narrate it here rather than dropping quietly, or a
        // manual-only sequence never reaches its terminal state and the operator is left with a
        // "scheduled" answer and permanent silence.
        narrateGiveUp(decision.veto());
        return;
      }
      boolean due =
          decision.action() == BootRecoveryDecision.Action.ATTEMPT
              // An operator's request is what makes a backoff-pending attempt due — but only that:
              // it cannot convert a veto, a spent budget, or a live worker into an attempt.
              || (operatorRequested && decision.action() == BootRecoveryDecision.Action.WAIT);
      if (!due) {
        log.info(
            "Boot recovery attempt dropped before spawning: the state now says {} ({})",
            decision.action(),
            decision.veto());
        return;
      }
      int attemptNo = decision.nextAttempt();
      // An operator re-opening an INDEX_FATAL give-up gets a genuinely live arc back: clearing the
      // latch is what lets the NEXT failure narrate its terminal state again instead of falling
      // silent (narrateGiveUp returns early while the latch is set).
      recoveryGaveUp = false;
      gaveUpVeto = null;
      recoveryAttemptsMade = recoveryAttemptsMade + 1;
      lastRecoveryAttemptMs = nowMs.getAsLong();
      WorkerCapability cap = bootstrap.workerCapability();
      // Park the forensic context first: the capability-health bridge reads it synchronously from
      // inside the transition below, to attach attempt/kind attributes to the occurrence.
      cap.setRecoveryContext(
          new RecoveryContext(
              attemptNo, "boot", BootRecoveryDecision.backoffMs(attemptNo, recoveryPolicy)));
      cap.transition(
          CapabilityHealth.RECOVERING,
          LifecycleReasonCode.WORKER_RECOVERING.code(),
          "Retrying knowledge server start (attempt "
              + attemptNo
              + " of "
              + recoveryPolicy.maxAttempts()
              + ")");
      log.info(
          "Boot recovery: re-attempting Knowledge Server start ({}/{})",
          attemptNo,
          recoveryPolicy.maxAttempts());
      if (closed) {
        // Last gate before the spawn: close() may have landed while we narrated.
        return;
      }
      try {
        bootstrap.startForRecovery();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.warn("Boot recovery attempt {} interrupted", attemptNo);
        return;
      } catch (Exception e) {
        log.warn("Boot recovery attempt {} failed: {}", attemptNo, e.toString());
        settleAfterFailedAttempt();
        return;
      }
      if (bootstrap.hasClient()) {
        // The bootstrap is up. Hand it to the surfaces that were late-bound with null at boot, then
        // reset the budget: the arc is over, and any LATER fault is supervision's (the spawner now
        // exists) or the health arm's.
        handOverRecoveredWorker(attemptNo);
        recoveryAttemptsMade = 0;
        lastRecoveryAttemptMs = -1;
      }
    } finally {
      recoveryAttemptRunning.set(false);
    }
  }

  private void handOverRecoveredWorker(int attemptNo) {
    log.info(
        "Boot recovery succeeded on attempt {} — knowledge server is bound (health: {})",
        attemptNo,
        bootstrap.workerCapability().health());
    Consumer<KnowledgeServerBootstrap> handover = this.onRecoveryConnected;
    if (handover == null) {
      return;
    }
    try {
      handover.accept(bootstrap);
    } catch (RuntimeException e) {
      // Never let a surface-binding failure kill the monitor thread: the worker IS up, and the next
      // tick's health arm keeps it observable even if a controller failed to rebind.
      log.error("Boot recovery handover failed after a successful re-attempt", e);
    }
  }

  /** Narrates the local budget exhaustion or the fatal index cause once per recovery arc. */
  private void narrateGiveUp(BootRecoveryDecision.Veto veto) {
    if (recoveryGaveUp) {
      // The terminal state is narrated exactly once per arc. Reachable when a manual request and a
      // periodic tick both resolve to GIVE_UP before either has run.
      return;
    }
    switch (veto) {
      // Tempdoc 915 R1. The one veto this authority NARRATES, because it is the one whose cause the
      // Head owns and may never have said out loud: the bootstrap latched it from the dying worker's
      // fatal-reason marker, and that read can land inside a suppressed boot arc (all three
      // startWithRetry attempts are suppressed, and each one consumes the marker the worker just
      // rewrote). Stamping it here is what makes the terminal readiness carry
      // worker.index_schema_mismatch / worker.index_corrupt WITH its remedy instead of this arm's
      // generic worker.spawn_recovery_exhausted — the exact substitution live arm 2 observed.
      case INDEX_FATAL -> {
        latchGaveUp(veto);
        LifecycleReasonCode cause = bootstrap.indexFatalCode();
        log.error(
            "Boot recovery declining to re-attempt: the worker refused with {} — the condition is"
                + " on disk, so re-spawning would read the same bytes and refuse the same way",
            cause == null ? "a fatal index reason" : cause.code());
        if (cause != null) {
          // Unconditional, and deliberately so (R2). An earlier draft skipped the write when the
          // reason slot already held the cause — a wrong-gate: it compared the REASON and ignored the
          // HEALTH, so an operator-requested attempt that re-refused left the capability parked at
          // the RECOVERING this arm had set before the spawn. The reason was right and the state was
          // a lie: readinessNotice.ts renders it as "recovering" for a condition that never recovers
          // on its own, which live R2 watched sit there for two minutes. This cannot double-narrate:
          // WorkerCapability.transition fires listeners only when the health OR the effective reason
          // changes, and the sticky reason is retained, so the already-terminal case is a no-op.
          bootstrap
              .workerCapability()
              .transition(CapabilityHealth.DEGRADED, cause.code(), bootstrap.indexFatalDetail());
        }
      }
      case NONE -> {
        latchGaveUp(veto);
        log.error(
            "Boot recovery exhausted after {} attempt(s); the knowledge server will not be retried"
                + " again in this process",
            recoveryAttemptsMade);
        bootstrap
            .workerCapability()
            .transition(
                CapabilityHealth.DEGRADED,
                LifecycleReasonCode.WORKER_SPAWN_RECOVERY_EXHAUSTED.code(),
                "Knowledge server failed to start and "
                    + recoveryAttemptsMade
                    + " recovery attempt(s) did not bring it up");
      }
    }
  }

  /**
   * Tempdoc 915 R2: re-ask the decision the moment an attempt fails, instead of leaving the
   * capability parked at the RECOVERING this arm set before the spawn until the next tick happens to
   * come round. Runs on the executor thread (its only caller is the attempt itself), asks the same
   * pure function the tick asks, and narrates through the same funnel — so it can only reach a state
   * the periodic arm would have reached anyway, sooner. Live R2 watched an operator-requested retry
   * that re-refused report "recovering" for two minutes; a condition that cannot recover on its own
   * must not be rendered as one that is recovering.
   *
   * <p>Scoped to {@link BootRecoveryDecision.Veto#INDEX_FATAL} on purpose. It is the only veto whose
   * cause is already known-terminal at the instant the attempt fails, and the only one this arm
   * narrates. The budget-exhaustion give-up keeps its designed timing (narrated by the tick after the
   * last attempt) — {@code KnowledgeServerBootRecoveryTest.arcGivesUpOnceAfterTheBudget} pins that,
   * and pulling it forward here would be a scheduling change R2 did not ask for.
   */
  private void settleAfterFailedAttempt() {
    BootRecoveryDecision.Decision decision =
        BootRecoveryDecision.decide(currentRecoveryInput(), recoveryPolicy);
    if (decision.action() == BootRecoveryDecision.Action.GIVE_UP
        && decision.veto() == BootRecoveryDecision.Veto.INDEX_FATAL) {
      narrateGiveUp(decision.veto());
    }
  }

  /** Boot-recovery attempts spent in this arc. Visible for tests. */
  int recoveryAttemptsMadeForTest() {
    return recoveryAttemptsMade;
  }

  /** Whether an attempt holds the single attempt slot right now. Visible for tests. */
  boolean recoveryAttemptRunningForTest() {
    return recoveryAttemptRunning.get();
  }

  /** Records the terminal state AND which veto produced it, so the two can never disagree. */
  private void latchGaveUp(BootRecoveryDecision.Veto veto) {
    gaveUpVeto = veto;
    recoveryGaveUp = true;
  }

  /**
   * Tempdoc 825 §D5 decision 4: the manual path. Schedules an immediate attempt on the SAME executor
   * the periodic arm uses, so a manual request can never race a tick into two concurrent spawns, and
   * returns what the recovery authority decided rather than blocking an HTTP request on a spawn.
   *
   * <p>An operator's explicit request clears the backoff wait and may retry a repaired fatal index
   * cause, but cannot exceed the local attempt budget.
   */
  @Override
  public Verdict requestRecoveryNow() {
    if (bootstrap.hasClient()) {
      return Verdict.NOT_APPLICABLE;
    }
    // Reserve the single attempt slot on the CALLER's thread and hand it to the runnable. Checking a
    // "is one running" flag instead let a burst queue N runnables before the first had started, so
    // N requests were all told ACCEPTED and the later ones were then dropped by the executor-side
    // re-decide — an answer that promised an attempt nobody made. Reserving makes the verdict true:
    // ACCEPTED means exactly one attempt will be considered, and everyone else is told to wait.
    if (!recoveryAttemptRunning.compareAndSet(false, true)) {
      return Verdict.ALREADY_RUNNING;
    }
    boolean slotHandedOff = false;
    BootRecoveryDecision.Decision decision =
        BootRecoveryDecision.decide(currentRecoveryInput(true), recoveryPolicy);
    try {
      return switch (decision.action()) {
        case NONE -> recoveryGaveUp ? Verdict.EXHAUSTED : Verdict.NOT_APPLICABLE;
        case GIVE_UP -> {
          // Narrate on the executor (the arm's own thread) so the manual path lands the same
          // terminal state the periodic path would, exactly once, and no capability write happens
          // off-thread.
          executor.execute(() -> narrateGiveUp(decision.veto()));
          yield Verdict.EXHAUSTED;
        }
        // WAIT is an ATTEMPT whose backoff has not elapsed; the request is what makes it due. The
        // decision is re-run on the executor before anything spawns, so this is a hint, not a
        // licence — a burst of requests still cannot out-spend the budget (review F5).
        case ATTEMPT, WAIT -> {
          executor.execute(() -> attemptBootRecovery(true, true));
          slotHandedOff = true;
          yield Verdict.ACCEPTED;
        }
      };
    } catch (java.util.concurrent.RejectedExecutionException e) {
      // The monitor is closed (shutdown in progress). Nothing will recover, but an HTTP request must
      // not become a 500 because the process is on its way out — the caller falls back to its own
      // unavailable answer.
      log.debug("Worker recovery request rejected — the monitor is shut down");
      return Verdict.NOT_APPLICABLE;
    } finally {
      // Every path that did NOT hand the slot to a runnable must release it, or one refused request
      // would wedge the arm for the life of the process.
      if (!slotHandedOff) {
        recoveryAttemptRunning.set(false);
      }
    }
  }

  /**
   * Tempdoc 630: on a detected resume, close the stale-after-suspend window that survives one
   * process — re-register watchers and kick a (freshness-skipping) reconcile walk ({@link
   * KnowledgeClient#reindexPersistedRoots()}), which catches filesystem events missed while the
   * watcher was frozen. Best-effort and guarded so a transient failure never aborts the tick; the
   * periodic sync remains the backstop.
   *
   * <p>Item A11 removed the second actuator, a channel reconnect: there is no channel.
   */
  private void eagerlyRevalidateAfterResume(long gapMs) {
    log.info(
        "Resume detected (process frozen ~{}ms); re-registering watchers and reconciling", gapMs);
    // Tempdoc 630: stamp the resume so /api/status can surface a brief "Catching up after sleep"
    // transient while the reconcile below runs (auto-clears after the notice window).
    bootstrap.markResumed(nowMs.getAsLong());
    KnowledgeClient client;
    try {
      client = bootstrap.client();
    } catch (RuntimeException e) {
      // Worker not started/ready (client() throws IllegalStateException) — nothing to re-validate;
      // the normal start/spawn path owns bringing it up. Benign on resume.
      log.debug("Post-resume re-validation skipped — worker client not available: {}", e.getMessage());
      return;
    }
    // Item A11: the channel reconnect that used to run here is gone. It was a no-op from item A6
    // (an in-process client has no connection to lose) and its only reason to exist was a stale
    // post-wake socket.
    try {
      client.reindexPersistedRoots(ENGINE_CONTEXT);
    } catch (RuntimeException e) {
      log.warn("Post-resume watcher re-register + reconcile failed: {}", e.getMessage());
    }
  }

  /**
   * Review F4: close is now ORDERED, not just requested. The flag stops an attempt that has not
   * spawned yet; the bounded await makes the return value mean "no recovery is in flight any more",
   * which is what {@code performOrderedShutdown} needs before it closes the bootstrap underneath us.
   * Bounded at {@value #CLOSE_AWAIT_MS}ms so a wedged attempt delays shutdown rather than blocking
   * it; {@code shutdownNow} has already interrupted it, and the Worker's own Job Object / heartbeat
   * suicide-pact is the backstop for a process that still slips through.
   */
  @Override
  public void close() {
    closed = true;
    executor.shutdownNow();
    try {
      if (!executor.awaitTermination(CLOSE_AWAIT_MS, TimeUnit.MILLISECONDS)) {
        log.warn(
            "Knowledge Server health monitor did not stop within {}ms; a boot-recovery attempt may"
                + " still be unwinding",
            CLOSE_AWAIT_MS);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
