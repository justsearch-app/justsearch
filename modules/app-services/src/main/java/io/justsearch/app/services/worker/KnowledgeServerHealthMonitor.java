/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.core.component.ComponentRecoveryAction;
import io.justsearch.core.component.EngineComponentSnapshot;
import io.justsearch.app.api.lifecycle.RetentionClass;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import io.justsearch.core.context.EngineContext;
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.ComponentHandle;
import io.justsearch.core.component.EngineComponentRegistry;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.core.harness.HarnessBarrierProtocol;
import io.justsearch.configuration.SystemAccess;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Background health monitor for the Worker (the index half, in-process since lane F stage A item
 * A6). Polls {@link KnowledgeServerBootstrap#checkHealth()} and triggers deferred auxiliary
 * initialization on ERROR→READY recovery transitions.
 *
 * <p>The monitor observes the Engine index component and admits component recovery through
 * the sealed composition-root bindings. It does not discover ports or spawn an index process.
 *
 * <p>The periodic loop also detects suspend/resume through {@link ResumeDetector}: an
 * inter-tick wall-clock gap larger than the polling interval causes watcher re-registration
 * and reconciliation before the next health observation.
 *
 * <p>Physical recovery is component-shaped. The composition root seals one binding for every
 * registered component before this monitor starts. Timer and operator requests share one executor
 * and one admission slot; the component registry owns attempt counts, deadlines, and terminal
 * observations.
 */
public final class KnowledgeServerHealthMonitor implements Closeable, ComponentRecoveryAuthority {
  private static final EngineContext ENGINE_CONTEXT = io.justsearch.app.services.intent.EngineProvenance.internal(
      "engine-health-monitor", EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.BACKGROUND);
  private static final Logger log = LoggerFactory.getLogger(KnowledgeServerHealthMonitor.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  static final long DEFAULT_POLL_INTERVAL_MS = 10_000;

  /**
   * Resume threshold factor (tempdoc 630): a tick is treated as post-resume only if the observed
   * inter-tick gap exceeds {@code pollIntervalMs * RESUME_TOLERANCE_FACTOR}. Generous so ordinary
   * GC / scheduler jitter never false-triggers an eager reconnect+reconcile.
   */
  static final long RESUME_TOLERANCE_FACTOR = 3;

  /** How long {@link #close()} waits for an in-flight physical recovery to unwind (review F4). */
  static final long CLOSE_AWAIT_MS = 5_000;

  /** Floor for the variable tick interval (tempdoc 885 item 6) — a supplier cannot make it spin. */
  static final long MIN_TICK_INTERVAL_MS = 1_000;
  private static final long START_ADMISSION_BUDGET_MS = 5_000;

  /**
   * Tempdoc 885 item 6: variable inter-tick delay, installed by the composition root. Null (the
   * default) keeps the fixed {@link #pollIntervalMs} cadence every existing construction site had.
   */
  private volatile LongSupplier tickIntervalSupplier;

  private final KnowledgeServerBootstrap bootstrap;
  private final long pollIntervalMs;
  private final EngineExecutorRegistry.Registration executorRegistration;
  private final ScheduledExecutorService executor;
  private final EngineExecutorRegistry.Registration recoveryExecutorRegistration;
  private final ExecutorService recoveryExecutor;
  private final LongSupplier nowMs;
  private final BootRecoveryPolicy recoveryPolicy;
  private final Function<String, String> environment;
  /** Wall-clock (epoch ms) of the previous tick; {@code -1} until the first tick. */
  private volatile long lastTickWallMs = -1;

  /** Completes the structural handover for an initial start that outlived Head's bounded wait. */
  private volatile Consumer<KnowledgeServerBootstrap> onRecoveryConnected;

  /** Activates asynchronous bridges after a recovered index has been published exactly READY. */
  private volatile Consumer<KnowledgeServerBootstrap> onRecoveryPublished;

  /** The initial index start may outlive the API's bounded wait; it still owns its opening. */
  private volatile CompletableFuture<?> initialStartup;
  private volatile boolean initialHandoverPending;
  private volatile EngineComponentRegistry componentRegistry;
  private volatile Map<String, ComponentRecoveryBinding> recoveryBindings;
  private final Object componentRecoveryLock = new Object();
  private final Map<String, ComponentEpisode> componentEpisodes = new HashMap<>();
  private final List<RecoveryOccurrence> pendingComponentOccurrences = new ArrayList<>();
  private EngineComponentRegistry.Subscription recoverySubscription;
  private volatile Thread timerThread;
  private volatile Thread recoveryThread;
  private volatile String lastDispatchedComponent;
  private Consumer<EngineComponentSnapshot.Component> onEscalation;
  private final AtomicBoolean escalationRequested = new AtomicBoolean();
  private final AtomicBoolean recoveryAttemptRunning = new AtomicBoolean();

  private static final class ComponentEpisode {
    int recoveredBaseline;
    // Highest cumulative admission observed; also fences delayed terminal observations.
    int contextRecoveryAttempt;
    long lastAttemptMs = -1;
    RecoveryContext context;
  }

  private volatile Runnable onTick = () -> {};
  private volatile Consumer<RecoveryOccurrence> onRecoveryOccurrence = occurrence -> {};
  /**
   * Review F4: set by {@link #close()} before the executor is shut down, so an attempt that has not
   * yet begun stands down instead of racing the ordered shutdown. Without it, a recovery attempt
   * running (or queued) while {@code performOrderedShutdown} walks past the monitor could rebuild
   * an index component after the coordinator had closed the bootstrap, leaving an unowned resource.
   */
  private volatile boolean closed;
  private final AtomicBoolean startClaimed = new AtomicBoolean();
  private long nextTickAtNanos;

  public KnowledgeServerHealthMonitor(
      EngineExecutorRegistry processExecutors, KnowledgeServerBootstrap bootstrap) {
    this(processExecutors, bootstrap, DEFAULT_POLL_INTERVAL_MS);
  }

  public KnowledgeServerHealthMonitor(
      EngineExecutorRegistry processExecutors,
      KnowledgeServerBootstrap bootstrap,
      long pollIntervalMs) {
    this(processExecutors, bootstrap, pollIntervalMs, System::currentTimeMillis);
  }

  /**
   * @param nowMs wall-clock source (epoch ms) for resume detection — injectable so the gap logic is
   *     unit-testable without a real clock or a real OS suspend (tempdoc 630)
   */
  public KnowledgeServerHealthMonitor(
      EngineExecutorRegistry processExecutors,
      KnowledgeServerBootstrap bootstrap, long pollIntervalMs, LongSupplier nowMs) {
    this(processExecutors, bootstrap, pollIntervalMs, nowMs, BootRecoveryPolicy.defaults());
  }

  /**
   * @param recoveryPolicy budget and backoff for generic component recovery
   */
  public KnowledgeServerHealthMonitor(
      EngineExecutorRegistry processExecutors,
      KnowledgeServerBootstrap bootstrap,
      long pollIntervalMs,
      LongSupplier nowMs,
      BootRecoveryPolicy recoveryPolicy) {
    this(processExecutors, bootstrap, pollIntervalMs, nowMs, recoveryPolicy,
        SystemAccess::rawEnvVar);
  }

  KnowledgeServerHealthMonitor(
      EngineExecutorRegistry processExecutors,
      KnowledgeServerBootstrap bootstrap,
      long pollIntervalMs,
      LongSupplier nowMs,
      BootRecoveryPolicy recoveryPolicy,
      Function<String, String> environment) {
    if (bootstrap == null) {
      throw new IllegalArgumentException("bootstrap must not be null");
    }
    this.bootstrap = bootstrap;
    this.pollIntervalMs = pollIntervalMs > 0 ? pollIntervalMs : DEFAULT_POLL_INTERVAL_MS;
    this.nowMs = nowMs;
    this.recoveryPolicy = recoveryPolicy != null ? recoveryPolicy : BootRecoveryPolicy.defaults();
    this.environment = Objects.requireNonNull(environment, "environment");
    Objects.requireNonNull(processExecutors, "processExecutors");
    EngineExecutorRegistry.Limits background =
        processExecutors.limits(EngineExecutorSpec.Kind.BACKGROUND);
    EngineExecutorRegistry.Registration registration =
        processExecutors.register(
            new EngineExecutorSpec(
                "head.knowledge-server-health-monitor",
                EngineExecutorSpec.Kind.BACKGROUND,
                EngineExecutorSpec.Mode.SCHEDULED,
                1,
                background.maxQueue(),
                1));
    ScheduledExecutorService timer = null;
    EngineExecutorRegistry.Registration recoveryRegistration = null;
    try {
      timer = registration.openScheduled(
              r -> {
                Thread t = new Thread(r, "knowledge-server-health-monitor");
                timerThread = t;
                t.setDaemon(true);
                return t;
              });
      recoveryRegistration = processExecutors.register(new EngineExecutorSpec(
          "head.component-recovery", EngineExecutorSpec.Kind.BACKGROUND,
          EngineExecutorSpec.Mode.PLATFORM, 1, background.maxQueue(), 1));
      ExecutorService recovery = recoveryRegistration.open(
          r -> {
            Thread t = Thread.ofPlatform().daemon().name("engine-component-recovery").unstarted(r);
            recoveryThread = t;
            return t;
          });
      this.executorRegistration = registration;
      this.executor = timer;
      this.recoveryExecutorRegistration = recoveryRegistration;
      this.recoveryExecutor = recovery;
    } catch (RuntimeException | Error failure) {
      if (recoveryRegistration != null) {
        try { recoveryRegistration.close(); }
        catch (RuntimeException | Error cleanupFailure) { failure.addSuppressed(cleanupFailure); }
      }
      if (timer != null) timer.shutdownNow();
      try {
        registration.close();
      } catch (RuntimeException | Error cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }
  }

  public void start() {
    if (closed || !startClaimed.compareAndSet(false, true)) return;
    if (recoveryBindings == null) {
      startClaimed.set(false);
      throw new IllegalStateException("Recovery bindings must be installed before monitor start");
    }
    boolean installed = false;
    nextTickAtNanos = System.nanoTime();
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(START_ADMISSION_BUDGET_MS);
    io.justsearch.core.execution.EngineExecutorRejectedException lastRefusal = null;
    try {
      while (!closed) {
        if (lastRefusal != null && System.nanoTime() - deadline >= 0) throw lastRefusal;
        try {
          @SuppressWarnings("unused")
          var ignored = executor.scheduleWithFixedDelay(this::tickIfDue, pollIntervalMs,
              Math.min(MIN_TICK_INTERVAL_MS, pollIntervalMs), TimeUnit.MILLISECONDS);
          installed = true;
          log.info("Knowledge Server health monitor started (poll interval: {}ms)", pollIntervalMs);
          return;
        } catch (io.justsearch.core.execution.EngineExecutorRejectedException refusal) {
          if (refusal.reason() == io.justsearch.core.execution.EngineExecutorRejectedException.Reason.CLOSED) {
            log.debug("Health monitor start refused after owner close");
            throw refusal;
          }
          long waitNanos = TimeUnit.SECONDS.toNanos(refusal.retryAfterSeconds());
          if (deadline - System.nanoTime() <= waitNanos) throw refusal;
          lastRefusal = refusal;
          log.warn("Health monitor start retained for capacity retry (reason={})", refusal.reason());
          try {
            TimeUnit.NANOSECONDS.sleep(waitNanos);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting to start health monitor", interrupted);
          }
        }
      }
    } finally {
      if (!installed) startClaimed.set(false);
    }
  }

  /**
   * Chooses the actual sampling delay on one retained periodic timer. The heartbeat checks a
   * monotonic due time, so changing sampling cadence does not need another timer reservation.
   * Resume detection still compares wall-clock gaps against the configured poll interval.
   */
  public void tickIntervalSupplier(LongSupplier supplier) {
    this.tickIntervalSupplier = supplier;
  }

  private void tickIfDue() {
    if (closed || System.nanoTime() - nextTickAtNanos < 0) return;
    try {
      tick();
    } finally {
      nextTickAtNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(nextTickDelayMs());
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
    if (closed) return;
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

      tickComponents();
    } catch (Exception e) {
      log.warn("Knowledge Server health monitor tick failed: {}", e.getMessage(), e);
      // checkHealth owns physical-loss reporting; setup/resume exceptions are not evidence
      // that the index stopped serving. Leave pending initialization available for the next poll.
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
      if (!closed) onTick.run();
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

  /** Installs the observer for durable recovery-attempt occurrences. */
  public void onRecoveryOccurrence(Consumer<RecoveryOccurrence> observer) {
    this.onRecoveryOccurrence = Objects.requireNonNull(observer, "observer");
  }

  private void emitRecoveryOccurrence(RecoveryOccurrence occurrence) {
    if (closed) return;
    try { onRecoveryOccurrence.accept(occurrence); }
    catch (RuntimeException failure) {
      log.warn("Recovery occurrence delivery failed: {}", occurrence.kind(), failure);
    }
  }

  /**
   * Install the structural handover for an initial index start that completes after Head's bounded
   * wait. Called before {@link #start()} by {@code HeadlessApp}.
   */
  public void onRecoveryConnected(Consumer<KnowledgeServerBootstrap> handover) {
    this.onRecoveryConnected = handover;
  }

  /** Installs the post-publication activation callback for a successfully recovered index. */
  public void onRecoveryPublished(Consumer<KnowledgeServerBootstrap> activation) {
    this.onRecoveryPublished = activation;
  }

  /** Arms a late handover without adding another timer or a second recovery authority. */
  public void observeInitialStartup(CompletableFuture<?> startup) {
    this.initialStartup = Objects.requireNonNull(startup, "startup");
    this.initialHandoverPending = true;
  }

  /** Binds the already-registered component authority before the monitor becomes visible. */
  public void componentRegistry(EngineComponentRegistry registry) {
    if (recoveryBindings != null) throw new IllegalStateException("Recovery registry is already bound");
    this.componentRegistry = Objects.requireNonNull(registry, "registry");
  }

  /** Seals the composition-root projection before starting the one monitor timer. */
  public void componentRecoveryBindings(Map<String, ComponentRecoveryBinding> bindings,
      Consumer<EngineComponentSnapshot.Component> escalation) {
    if (startClaimed.get() || recoveryBindings != null) {
      throw new IllegalStateException("Recovery bindings must be installed once before monitor start");
    }
    var registry = Objects.requireNonNull(componentRegistry, "componentRegistry");
    var sealed = Map.copyOf(bindings);
    var observed = registry.snapshot().components();
    var names = observed.stream().map(row -> row.spec().name())
        .collect(java.util.stream.Collectors.toSet());
    if (!sealed.keySet().equals(names)) {
      throw new IllegalArgumentException("Recovery bindings must cover every registered component");
    }
    for (var row : observed) {
      if (!sealed.get(row.spec().name()).handle().spec().equals(row.spec())
          || !sealed.get(row.spec().name()).handle().belongsTo(registry)) {
        throw new IllegalArgumentException("Recovery handle does not match " + row.spec().name());
      }
    }
    onEscalation = Objects.requireNonNull(escalation, "escalation");
    synchronized (componentRecoveryLock) {
      for (var row : observed) {
        var episode = new ComponentEpisode();
        episode.recoveredBaseline = row.recoveryAttempts();
        episode.contextRecoveryAttempt = row.recoveryAttempts();
        componentEpisodes.put(row.spec().name(), episode);
      }
      recoverySubscription = registry.subscribe(this::observeRecoveryEpisodes);
      observeRecoveryEpisodes(registry.snapshot());
      recoveryBindings = sealed;
    }
  }

  private void tickComponents() {
    for (var row : componentRegistry.snapshot().components()) {
      long deadline = row.spec().startDeadline().toNanos();
      if (deadline != 0 && row.state() == ComponentState.STARTING
          && System.nanoTime() - row.stateSinceMonotonicNanos() >= deadline) {
        String awaited = row.evidence() == null ? "unknown startup resource" : row.evidence();
        var retention = LifecycleReasonCode.retentionClassOf(row.reasonCode());
        boolean causeHeld = retention == RetentionClass.FAULT
            || retention == RetentionClass.STICKY;
        recoveryBindings.get(row.spec().name()).handle().transitionIfUnchanged(row,
            ComponentState.FAILED, causeHeld ? row.reasonCode()
                : LifecycleReasonCode.COMPONENT_START_DEADLINE.code(),
            "Start deadline exceeded while waiting for " + awaited);
      }
    }
    CompletableFuture<?> startup = initialStartup;
    boolean indexPhysicallyHealthy = false;
    if ((startup == null || startup.isDone()) && bootstrap.hasClient()) {
      var health = bootstrap.tryCheckHealth();
      indexPhysicallyHealthy = health.orElse(false);
      if (health.isPresent() && initialHandoverPending) {
        indexPhysicallyHealthy = handOverInitialWorker();
      }
    }
    emitPendingComponentOccurrences();
    if (recoveryAttemptRunning.get()) return;
    var rows = componentRegistry.snapshot().components();
    int first = 0;
    for (int i = 0; i < rows.size(); i++) {
      if (rows.get(i).spec().name().equals(lastDispatchedComponent)) {
        first = (i + 1) % rows.size();
        break;
      }
    }
    for (int i = 0; i < rows.size(); i++) {
      var row = rows.get((first + i) % rows.size());
      boolean healthyIndexWithoutExplicitFailure = "index".equals(row.spec().name())
          && indexPhysicallyHealthy
          && !LifecycleReasonCode.COMPONENT_RECOVERY_FAILED.code().equals(row.reasonCode())
          && !immediateEscalation(row);
      if (eligibleForComponentRecovery(row)
          && !healthyIndexWithoutExplicitFailure
          && !("index".equals(row.spec().name()) && initialOwnerPending())) {
        var outcome = scheduleComponentRecovery(row.spec().name(), false);
        if (outcome == ComponentRecoveryAuthority.Outcome.ACCEPTED
            || recoveryAttemptRunning.get() || escalationRequested.get()) return;
      }
    }
  }

  /** Listener delivery can run under a physical owner lock: update projections only. */
  private void observeRecoveryEpisodes(EngineComponentSnapshot snapshot) {
    synchronized (componentRecoveryLock) {
      for (var row : snapshot.components()) {
        var episode = componentEpisodes.get(row.spec().name());
        if (episode == null) continue;
        if (row.recoveryAttempts() > episode.contextRecoveryAttempt) {
          episode.contextRecoveryAttempt = row.recoveryAttempts();
          episode.lastAttemptMs = nowMs.getAsLong();
        }
        if ((row.state() == ComponentState.READY || row.state() == ComponentState.ABSENT)
            && row.recoveryAttempts() >= episode.recoveredBaseline) {
          episode.recoveredBaseline = row.recoveryAttempts();
          if (row.recoveryAttempts() < episode.contextRecoveryAttempt) continue;
          episode.lastAttemptMs = -1;
          if (row.state() == ComponentState.READY && episode.context != null) {
            pendingComponentOccurrences.add(new RecoveryOccurrence(
                RecoveryOccurrence.Kind.RECOVERED, episode.context));
          }
          episode.context = null;
        }
      }
    }
  }

  private void emitPendingComponentOccurrences() {
    List<RecoveryOccurrence> pending;
    synchronized (componentRecoveryLock) {
      pending = List.copyOf(pendingComponentOccurrences);
      pendingComponentOccurrences.clear();
    }
    if (!closed) pending.forEach(this::emitRecoveryOccurrence);
  }

  private boolean immediateEscalation(
      EngineComponentSnapshot.Component row) {
    return row.spec().essential()
        && (LifecycleReasonCode.INDEX_CORRUPT.code().equals(row.reasonCode())
            || LifecycleReasonCode.INDEX_SCHEMA_OPEN_REFUSED.code().equals(row.reasonCode()));
  }

  private static boolean eligibleForComponentRecovery(
      EngineComponentSnapshot.Component row) {
    return row.state() == ComponentState.FAILED
        || ("encoders".equals(row.spec().name())
            && row.state() == ComponentState.UNAVAILABLE
            && LifecycleReasonCode.COMPONENT_RECOVERY_FAILED.code().equals(row.reasonCode()));
  }

  private ComponentRecoveryAuthority.Outcome scheduleComponentRecovery(String name,
      boolean operatorRequested) {
    if (closed || escalationRequested.get()) return ComponentRecoveryAuthority.Outcome.NOT_APPLICABLE;
    var bindings = recoveryBindings;
    if (bindings == null) return ComponentRecoveryAuthority.Outcome.NOT_APPLICABLE;
    var binding = bindings.get(name);
    if (binding == null) return ComponentRecoveryAuthority.Outcome.UNKNOWN_COMPONENT;
    if (("index".equals(name) && initialOwnerPending())
        || !recoveryAttemptRunning.compareAndSet(false, true)) {
      return ComponentRecoveryAuthority.Outcome.ALREADY_RUNNING;
    }
    boolean handedOff = false;
    EngineComponentSnapshot.Component escalation = null;
    try {
      var row = binding.handle().snapshot();
      if (!eligibleForComponentRecovery(row)) {
        return ComponentRecoveryAuthority.Outcome.NOT_APPLICABLE;
      }
      if (operatorRequested && binding.action() == null) {
        return ComponentRecoveryAuthority.Outcome.OWNER_UNAVAILABLE;
      }
      boolean budgetExhausted;
      synchronized (componentRecoveryLock) {
        var episode = componentEpisodes.computeIfAbsent(name, ignored -> new ComponentEpisode());
        int budget = Math.min(2, Math.min(row.spec().recoveryBudget(), recoveryPolicy.maxAttempts()));
        int attempts = Math.max(0, row.recoveryAttempts() - episode.recoveredBaseline);
        budgetExhausted = attempts >= budget;
        if (!budgetExhausted && !operatorRequested && immediateEscalation(row)) {
          if (row.spec().essential() && binding.handle().snapshot().equals(row)) escalation = row;
          return ComponentRecoveryAuthority.Outcome.EXHAUSTED;
        }
        if (!budgetExhausted) {
          long backoff = recoveryPolicy.backoffMs(attempts + 1);
          if (!operatorRequested && episode.lastAttemptMs >= 0
              && nowMs.getAsLong() - episode.lastAttemptMs < backoff) {
            return ComponentRecoveryAuthority.Outcome.NOT_APPLICABLE;
          }
        }
      }
      if (budgetExhausted) {
        var terminal = LifecycleReasonCode.COMPONENT_RECOVERY_EXHAUSTED.code().equals(row.reasonCode())
            ? Optional.of(row)
            : binding.handle().tryTransitionIfUnchanged(row, ComponentState.FAILED,
                LifecycleReasonCode.COMPONENT_RECOVERY_EXHAUSTED.code(),
                "Component recovery attempt budget exhausted");
        if (row.spec().essential()) escalation = terminal.orElse(null);
        return ComponentRecoveryAuthority.Outcome.EXHAUSTED;
      }
      if (binding.action() == null) return ComponentRecoveryAuthority.Outcome.OWNER_UNAVAILABLE;
      recoveryExecutor.execute(() -> attemptComponentRecovery(binding, operatorRequested));
      handedOff = true;
      return ComponentRecoveryAuthority.Outcome.ACCEPTED;
    } catch (io.justsearch.core.execution.EngineExecutorRejectedException refusal) {
      if (closed || refusal.reason()
          == io.justsearch.core.execution.EngineExecutorRejectedException.Reason.CLOSED) {
        return ComponentRecoveryAuthority.Outcome.NOT_APPLICABLE;
      }
      var failure = new io.justsearch.app.api.EngineAdmissionException(
          io.justsearch.app.api.EngineAdmissionException.Reason.ENGINE_LIMIT,
          refusal.retryAfterSeconds());
      failure.initCause(refusal);
      throw failure;
    } catch (java.util.concurrent.RejectedExecutionException refusal) {
      if (closed || recoveryExecutor.isShutdown()) return ComponentRecoveryAuthority.Outcome.NOT_APPLICABLE;
      throw refusal;
    } finally {
      if (!handedOff) recoveryAttemptRunning.set(false);
      if (escalation != null) requestEscalation(escalation);
    }
  }

  private void requestEscalation(EngineComponentSnapshot.Component row) {
    if (closed || !escalationRequested.compareAndSet(false, true)) return;
    try {
      onEscalation.accept(row);
    } catch (RuntimeException failure) {
      escalationRequested.set(false);
      log.error("Essential-component escalation dispatch failed; the next tick will retry", failure);
    }
  }

  private void attemptComponentRecovery(ComponentRecoveryBinding binding, boolean operatorRequested) {
    var handle = binding.handle();
    // Fairness only: a zero-cost owner refusal must not monopolize subsequent timer ticks.
    lastDispatchedComponent = handle.spec().name();
    var claimed = new AtomicBoolean();
    var completionClaimed = new AtomicBoolean();
    var completed = new AtomicReference<
        EngineComponentSnapshot.Component>();
    var admitted = new AtomicReference<
        EngineComponentSnapshot.Component>();
    try {
    var expected = handle.snapshot();
    var request = new ComponentRecoveryAction.Request() {
      @Override public EngineComponentSnapshot.Component expected() {
        return expected;
      }
      @Override public EngineComponentSnapshot.Component current() {
        return handle.snapshot();
      }
      @Override public Optional<EngineComponentSnapshot.Component>
          admitted() { return Optional.ofNullable(admitted.get()); }
      @Override public Optional<EngineComponentSnapshot.Component>
          complete(EngineComponentSnapshot.Component current,
              ComponentState state, String reason, String evidence) {
        if (state != ComponentState.READY && state != ComponentState.FAILED
            && state != ComponentState.UNAVAILABLE) {
          throw new IllegalArgumentException("Recovery completion must be READY, UNAVAILABLE or FAILED");
        }
        if (state == ComponentState.UNAVAILABLE
            && (!"encoders".equals(handle.spec().name())
                || LifecycleReasonCode.COMPONENT_RECOVERY_FAILED.code().equals(reason))) {
          throw new IllegalArgumentException(
              "Only encoder restoration may degrade, with its recovery failure cleared");
        }
        var begun = admitted.get();
        if (closed || begun == null || !begun.spec().equals(current.spec())
            || begun.recoveryAttempts() != current.recoveryAttempts()
            || !completionClaimed.compareAndSet(false, true)) return Optional.empty();
        try {
          var publication = handle.tryTransitionIfUnchanged(current, state, reason, evidence);
          publication.ifPresent(completed::set);
          return publication;
        } finally {
          if (completed.get() == null) completionClaimed.set(false);
        }
      }
      @Override public boolean cancelled() { return closed; }
      @Override public boolean begin() {
        if (closed || (!operatorRequested && immediateEscalation(expected))
            || !claimed.compareAndSet(false, true)) return false;
        var publication = handle.tryBeginRecovery(expected,
            LifecycleReasonCode.COMPONENT_RECOVERING.code(), "Recomposing the applied configuration");
        if (publication.isEmpty()) return false;
        admitted.set(publication.orElseThrow());
        synchronized (componentRecoveryLock) {
          var episode = componentEpisodes.computeIfAbsent(handle.spec().name(),
              ignored -> new ComponentEpisode());
          int attempts = Math.max(0, publication.orElseThrow().recoveryAttempts()
              - episode.recoveredBaseline);
          episode.lastAttemptMs = nowMs.getAsLong();
          episode.contextRecoveryAttempt = publication.orElseThrow().recoveryAttempts();
          if (episode.context == null) {
            episode.context = new RecoveryContext(attempts, handle.spec().name(),
                recoveryPolicy.backoffMs(attempts));
            pendingComponentOccurrences.add(new RecoveryOccurrence(
                RecoveryOccurrence.Kind.ATTEMPTED, episode.context));
          }
        }
        return true;
      }
    };
      if (closed || !eligibleForComponentRecovery(expected)
          || ("index".equals(handle.spec().name()) && initialOwnerPending())) return;
      awaitGenerativeRecoveryBarrier(expected, operatorRequested);
      var result = binding.action().recover(request);
      var begun = admitted.get();
      if (closed || begun == null) return;
      var terminal = completed.get();
      if (terminal == null || !terminal.equals(result.observation())
          || !terminal.equals(handle.snapshot())) return;
      if (terminal.state() == ComponentState.READY
          && "index".equals(handle.spec().name()) && bootstrap.hasClient()) {
        publishRecoveredWorker(begun.recoveryAttempts());
      }
    } catch (Exception failure) {
      if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
      var begun = admitted.get();
      if (!closed && begun != null) handle.transitionIfUnchanged(begun, ComponentState.FAILED,
          LifecycleReasonCode.COMPONENT_RECOVERY_FAILED.code(),
          "Physical recovery failed: " + failure.getMessage());
      log.warn("Component recovery failed for {}", handle.spec().name(), failure);
    } finally {
      try {
        emitPendingComponentOccurrences();
      } finally {
        recoveryAttemptRunning.set(false);
      }
    }
  }

  private void awaitGenerativeRecoveryBarrier(
      EngineComponentSnapshot.Component expected, boolean operatorRequested)
      throws IOException, InterruptedException {
    if (operatorRequested || !"generative".equals(expected.spec().name())
        || expected.state() != ComponentState.FAILED || expected.recoveryAttempts() != 0) return;
    String selected = environment.apply("JUSTSEARCH_GENERATIVE_RECOVERY_BARRIER");
    if (selected == null || selected.isBlank()) return;
    if (!"1".equals(selected)) {
      throw new IllegalArgumentException("Unknown generative recovery barrier selector: " + selected);
    }
    if (!"1".equals(environment.apply("JUSTSEARCH_SUPERVISOR_HARNESS"))) {
      throw new IllegalStateException("Generative recovery barrier requires supervisor harness mode");
    }
    String family = "generative-recovery-1";
    if (Files.exists(HarnessBarrierProtocol.reached(bootstrap.dataDirForHarness(), family))) return;
    Map<String, Object> marker = new java.util.LinkedHashMap<>();
    marker.put("point", "component-recovery-pre-admission");
    marker.put("component", expected.spec().name());
    marker.put("pid", ProcessHandle.current().pid());
    marker.put("state", expected.state().name());
    marker.put("stateSince", expected.stateSince().toString());
    marker.put("reasonCode", expected.reasonCode());
    marker.put("evidence", expected.evidence());
    marker.put("recoveryAttempts", expected.recoveryAttempts());
    marker.put("appliedVersion", expected.appliedVersion());
    marker.put("desiredVersion", expected.desiredVersion());
    HarnessBarrierProtocol.await(bootstrap.dataDirForHarness(), family,
        JSON.writeValueAsString(marker), false);
  }

  @Override
  public ComponentRecoveryAuthority.Outcome requestComponentRecovery(String name) {
    return name == null ? ComponentRecoveryAuthority.Outcome.NOT_APPLICABLE
        : scheduleComponentRecovery(name, true);
  }

  private boolean handOverInitialWorker() {
    if (closed) return false;
    Consumer<KnowledgeServerBootstrap> handover = this.onRecoveryConnected;
    if (handover == null) {
      initialHandoverPending = false;
      return true;
    }
    var binding = recoveryBindings.get("index");
    var expected = binding == null ? null : binding.handle().snapshot();
    try {
      handover.accept(bootstrap);
      initialHandoverPending = false;
      return true;
    } catch (RuntimeException failure) {
      initialHandoverPending = false;
      if (!closed && binding != null && expected != null) {
        String detail = failure.getMessage();
        if (detail == null || detail.isBlank()) detail = failure.getClass().getSimpleName();
        binding.handle().transitionIfUnchanged(expected, ComponentState.FAILED,
            LifecycleReasonCode.COMPONENT_RECOVERY_FAILED.code(),
            "Initial index handover failed: " + detail);
      }
      log.error("Initial index handover failed after its physical start completed", failure);
      return false;
    }
  }

  private void publishRecoveredWorker(int attemptNo) {
    if (closed) return;
    log.info("Index recovery succeeded on attempt {} — activating published services", attemptNo);
    Consumer<KnowledgeServerBootstrap> activation = this.onRecoveryPublished;
    if (activation == null) return;
    try {
      activation.accept(bootstrap);
    } catch (RuntimeException failure) {
      // READY is already exact and current. A bridge callback cannot spend another physical attempt.
      log.error("Index recovery activation failed after successful READY publication", failure);
    }
  }

  private boolean initialOwnerPending() {
    CompletableFuture<?> startup = initialStartup;
    return startup != null && (!startup.isDone()
        || (initialHandoverPending && bootstrap.hasClient()));
  }

  /** Whether an attempt holds the single attempt slot right now. Visible for tests. */
  boolean recoveryAttemptRunningForTest() {
    return recoveryAttemptRunning.get();
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
    if (bootstrap.automaticRootProducersSuppressed()) return;
    log.info(
        "Resume detected (process frozen ~{}ms); re-registering watchers and reconciling", gapMs);
    // Tempdoc 630: stamp the resume so /api/status can surface a brief "Catching up after sleep"
    // transient while the reconcile below runs (auto-clears after the notice window).
    bootstrap.markResumed(nowMs.getAsLong());
    KnowledgeServerBootstrap.ClientLease lease;
    try {
      lease = bootstrap.captureClient();
    } catch (IllegalStateException unavailable) {
      log.debug("Post-resume re-validation skipped — worker client not available: {}",
          unavailable.getMessage());
      return;
    }
    // Item A11: the channel reconnect that used to run here is gone. Hold the current in-process
    // client until this reconcile actually returns; recovery may be retiring it concurrently.
    try (lease) {
      lease.withClient(client -> {
        client.reindexPersistedRoots(ENGINE_CONTEXT);
        return null;
      });
    } catch (RuntimeException e) {
      log.warn("Post-resume watcher re-register + reconcile failed: {}", e.getMessage());
    }
  }

  /**
   * Stop new recovery work and wait up to {@value #CLOSE_AWAIT_MS}ms for an admitted attempt.
   * An attempt that ignores interruption can still be unwinding after this returns; it must not
   * deliver recovery or readiness callbacks to the Head owners that shutdown closes next.
   * The bootstrap retains ownership of any late client and serializes its own teardown with start.
   */
  @Override
  public void close() {
    closed = true;
    if (recoverySubscription != null) recoverySubscription.close();
    executor.shutdownNow();
    recoveryExecutor.shutdownNow();
    try {
      if (Thread.currentThread() != timerThread
          && !executor.awaitTermination(CLOSE_AWAIT_MS, TimeUnit.MILLISECONDS)) {
        log.warn(
            "Knowledge Server health monitor did not stop within {}ms; a recovery attempt may"
                + " still be unwinding",
            CLOSE_AWAIT_MS);
      }
      if (Thread.currentThread() != recoveryThread
          && !recoveryExecutor.awaitTermination(CLOSE_AWAIT_MS, TimeUnit.MILLISECONDS)) {
        log.warn("Component recovery did not stop within {}ms", CLOSE_AWAIT_MS);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      recoveryExecutorRegistration.close();
      executorRegistration.close();
    }
  }
}
