/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import io.justsearch.app.api.Mode;
import io.justsearch.app.api.ConfigCode;
import io.justsearch.app.api.HealthCode;
import io.justsearch.app.api.InferenceFailure;
import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.StartupCode;
import io.justsearch.app.api.TransitionCode;
import io.justsearch.app.api.OnlineAiRuntimeIntrospection.FailureRecord;
import io.justsearch.app.api.OnlineAiRuntimeIntrospection.TransitionRecord;
import io.justsearch.app.inference.telemetry.InferenceTelemetryEvents;
import io.justsearch.app.inference.telemetry.TransitionReason;
import io.justsearch.observable.ObservableNotifier;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.jcip.annotations.GuardedBy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The transition envelope, tempdoc 518 P1.
 *
 * <p>Owns the lock, the {@link ModeStateMachine}, the view atom, the listener registry, the
 * generation counter, and the typed event sink. Every transition method on {@link
 * InferenceLifecycleManager} delegates here via
 * {@link #run(TransitionReason, java.util.function.Consumer, TransitionBody)}.
 *
 * <p>The runner provides three update granularities (tempdoc 518 Appendix A.2):
 * <ol>
 *   <li>{@link #run} — full transition, lock-held, body returns the next {@link
 *       InferenceRuntimeView} as a {@link TransitionOutcome}; runner swaps on success or
 *       installs a failure-recorded view on rollback.
 *   <li>{@link #mergeProps} — CAS merge for {@code /props} reads from {@link ServerPropsOps}.
 *       May race with concurrent transitions; the loop preserves transition-set fields and
 *       overwrites only model-id / context-tokens.
 *   <li>{@link #installViewForTest} — synthetic view installation for tests. Production code
 *       does not call this.
 * </ol>
 *
 * <p>The runner also provides {@link #runForceOffline} for the crash-recovery + external-server-
 * unhealthy paths that bypass {@code beginTransition} / {@code complete} — these forcibly
 * transition to {@code OFFLINE} regardless of the current FSM state.
 */
final class TransitionRunner {

  private static final Logger LOG = LoggerFactory.getLogger(TransitionRunner.class);

  private final Object lock;

  @GuardedBy("lock")
  private final ModeStateMachine modeState;

  private final AtomicReference<InferenceRuntimeView> viewRef =
      new AtomicReference<>(InferenceRuntimeView.initial());
  private final AtomicLong generationCounter = new AtomicLong(0L);

  private final InferenceTelemetryEvents events;

  /** Tempdoc 518 Appendix F W4.1 — shared listener substrate. Wraps each typed
   *  {@link io.justsearch.app.api.ModeChangeListener} in a Consumer adapter so the
   *  exception-swallow iteration matches ConfigStore + (eventually) IndexingLoop. */
  private record ModeChange(Mode from, Mode to, TransitionReason reason) {}

  private final ObservableNotifier<ModeChange> listeners =
      new ObservableNotifier<>("ModeChangeListener");

  private final java.util.Map<io.justsearch.app.api.ModeChangeListener, Consumer<ModeChange>>
      listenerAdapters = new java.util.concurrent.ConcurrentHashMap<>();

  /** Tempdoc 837 §D.2 — the reason-bearing listeners, notified from the SAME notify sites. */
  private final java.util.Map<ModeTransitionListener, Consumer<ModeChange>>
      reasonListenerAdapters = new java.util.concurrent.ConcurrentHashMap<>();

  /**
   * Bounded failure-history ring buffer. Newest entries at the head (addFirst); oldest are
   * evicted from the tail when the buffer reaches {@link #MAX_FAILURE_HISTORY}.
   *
   * <p>Tempdoc 518 Appendix F W2.1. Recorded from three sites: {@link #installFailureView}
   * (rollback), {@link #runForceOffline} (forced demote with a failure), and
   * {@link #recordFailureOutsideTransition} (pre-guard fast-fail). Kept on a parallel
   * concurrency tier — not on the view atom — to preserve the three-tier view-atom rules
   * (Appendix E §E.6 decision: separate atomic vs. embedding on the view).
   */
  private static final int MAX_FAILURE_HISTORY = 20;

  private final Deque<FailureRecord> failureHistory = new ArrayDeque<>(MAX_FAILURE_HISTORY);
  private final Object failureHistoryLock = new Object();

  /**
   * Bounded transition-history ring buffer (tempdoc 518 Appendix F W3.2). Records every
   * commit + rollback emitted by {@link #emitTransition} so the Brain panel timeline can
   * render the recent sequence of mode changes. Newest at the head; oldest evicted at
   * {@link #MAX_TRANSITION_HISTORY}.
   */
  private static final int MAX_TRANSITION_HISTORY = 20;

  private final Deque<TransitionRecord> transitionHistory =
      new ArrayDeque<>(MAX_TRANSITION_HISTORY);
  private final Object transitionHistoryLock = new Object();

  /** Tempdoc 518 Appendix G W4.B.1 — persistent sidecar. Defaults to NOOP. */
  private volatile InferenceTransitionLog transitionLog = InferenceTransitionLog.NOOP;

  void setTransitionLog(InferenceTransitionLog log) {
    this.transitionLog = log == null ? InferenceTransitionLog.NOOP : log;
  }

  TransitionRunner(Object lock, ModeStateMachine modeState, InferenceTelemetryEvents events) {
    this.lock = Objects.requireNonNull(lock, "lock");
    this.modeState = Objects.requireNonNull(modeState, "modeState");
    this.events = Objects.requireNonNull(events, "events");
    // Tempdoc 518 Appendix F W2.2: publish the generation counter to the telemetry static
    // slot so every OTel span gets tagged with the current inference generation. Last-write
    // wins; only one runner runs in production.
    io.justsearch.telemetry.InferenceGenerationContext.set(generationCounter::get);
  }

  /** The monitor object used by the runner. Exposed so ILM helpers can synchronize on the same lock. */
  Object lock() {
    return lock;
  }

  /** Current FSM mode. Volatile read; safe outside the lock. */
  Mode currentMode() {
    return modeState.current();
  }

  /** Current observed runtime view. Volatile-equivalent read via AtomicReference; safe outside the lock. */
  InferenceRuntimeView view() {
    return viewRef.get();
  }

  /** Current monotonic generation counter value. */
  long generation() {
    return generationCounter.get();
  }

  /**
   * Returns the N most recent failures recorded on this runner, newest first. {@code limit}
   * is clamped to {@link #MAX_FAILURE_HISTORY}; non-positive returns the empty list. The
   * returned list is a defensive snapshot and is safe to mutate by the caller.
   */
  List<FailureRecord> recentFailures(int limit) {
    if (limit <= 0) {
      return List.of();
    }
    int cap = Math.min(limit, MAX_FAILURE_HISTORY);
    synchronized (failureHistoryLock) {
      List<FailureRecord> snapshot = new ArrayList<>(Math.min(cap, failureHistory.size()));
      int taken = 0;
      for (FailureRecord rec : failureHistory) {
        if (taken == cap) break;
        snapshot.add(rec);
        taken++;
      }
      return snapshot;
    }
  }

  private static String categoryOf(InferenceFailure failure) {
    return switch (failure) {
      case InferenceFailure.StartupFailure ignored -> "startup";
      case InferenceFailure.HealthFailure ignored -> "health";
      case InferenceFailure.ConfigFailure ignored -> "config";
      case InferenceFailure.TransitionFailure ignored -> "transition";
    };
  }

  private void recordFailureToHistory(InferenceFailure failure) {
    if (failure == null) return;
    FailureRecord rec =
        new FailureRecord(
            System.currentTimeMillis(),
            categoryOf(failure),
            failure.wireCode(),
            failure.detail());
    synchronized (failureHistoryLock) {
      failureHistory.addFirst(rec);
      while (failureHistory.size() > MAX_FAILURE_HISTORY) {
        failureHistory.pollLast();
      }
    }
  }

  /**
   * Returns the N most recent mode transitions, newest first. {@code limit} clamped to
   * {@link #MAX_TRANSITION_HISTORY}; non-positive returns empty. Defensive snapshot.
   * Tempdoc 518 Appendix F W3.2.
   */
  List<TransitionRecord> recentTransitions(int limit) {
    if (limit <= 0) return List.of();
    int cap = Math.min(limit, MAX_TRANSITION_HISTORY);
    synchronized (transitionHistoryLock) {
      List<TransitionRecord> snapshot = new ArrayList<>(Math.min(cap, transitionHistory.size()));
      int taken = 0;
      for (TransitionRecord rec : transitionHistory) {
        if (taken == cap) break;
        snapshot.add(rec);
        taken++;
      }
      return snapshot;
    }
  }

  private void recordTransitionToHistory(
      Mode from, Mode to, TransitionReason reason, long durationMs, InferenceFailure failureOrNull) {
    long timestampMs = System.currentTimeMillis();
    TransitionRecord rec =
        new TransitionRecord(
            timestampMs,
            from.name(),
            to.name(),
            reason.name(),
            failureOrNull == null,
            durationMs,
            failureOrNull == null ? null : failureOrNull.wireCode());
    synchronized (transitionHistoryLock) {
      transitionHistory.addFirst(rec);
      while (transitionHistory.size() > MAX_TRANSITION_HISTORY) {
        transitionHistory.pollLast();
      }
    }
    // Tempdoc 518 Appendix G W4.B.1 — sidecar log write. Same record shape; default NOOP
    // makes test ILMs zero-overhead. Exceptions from the log impl are caught + logged so
    // the runner's lock-held path never blocks on sidecar I/O failures.
    try {
      transitionLog.record(
          timestampMs,
          from.name(),
          to.name(),
          reason.name(),
          failureOrNull == null,
          durationMs,
          failureOrNull == null ? null : failureOrNull.wireCode(),
          generationCounter.get());
    } catch (RuntimeException e) {
      LOG.warn("Transition log threw (best-effort): {}", e.getMessage());
    }
  }

  /** Register a listener to be notified of mode transitions. */
  void addListener(io.justsearch.app.api.ModeChangeListener listener) {
    Consumer<ModeChange> adapter = listenerAdapters.computeIfAbsent(
        listener, l -> change -> l.onModeChange(change.from(), change.to()));
    // The 2-arg listeners ignore change.reason() — unchanged behaviour, no signature churn.
    listeners.register(adapter);
  }

  /** Unregister a previously added listener. */
  void removeListener(io.justsearch.app.api.ModeChangeListener listener) {
    Consumer<ModeChange> adapter = listenerAdapters.remove(listener);
    if (adapter != null) {
      listeners.unregister(adapter);
    }
  }

  /**
   * Tempdoc 837 §D.2 — register a listener that also receives the {@link TransitionReason} this
   * runner already holds. Same notify sites, same exception-swallow iteration; the reason is the
   * only difference, and it is what lets a consumer tell crash recovery apart from a deactivation.
   */
  void addReasonListener(ModeTransitionListener listener) {
    Consumer<ModeChange> adapter =
        reasonListenerAdapters.computeIfAbsent(
            listener, l -> change -> l.onModeTransition(change.from(), change.to(), change.reason()));
    listeners.register(adapter);
  }

  /** Unregister a previously added reason-bearing listener. */
  void removeReasonListener(ModeTransitionListener listener) {
    Consumer<ModeChange> adapter = reasonListenerAdapters.remove(listener);
    if (adapter != null) {
      listeners.unregister(adapter);
    }
  }

  /**
   * Run a transition under the lock with a failure-event sink. The body emits its own typed
   * attempt / complete / failure events (e.g., {@code events.onStartupAttempt}); the runner
   * emits the umbrella {@code events.onTransition} exactly once on success or rollback.
   *
   * <p>When the body returns {@link TransitionOutcome.Failure} or throws, the runner invokes
   * the {@code failureSink} with the typed {@link InferenceFailure} before emitting
   * {@code onTransition} and re-throwing. The sink is typically
   * {@code events::onStartupFailure} for startup-context transitions or
   * {@code events::onConfigApplyFailure} for config-apply transitions; the adapter routes
   * per-category internally. Tempdoc 518 P3.
   *
   * <h4>Idempotency / re-entry contract (tempdoc 518 Appendix E §E.4 / Appendix F W1.2)</h4>
   *
   * <p>Re-entry from within the same transition is <b>not supported</b>; callers must serialize
   * themselves above the runner. Two scenarios:
   *
   * <ul>
   *   <li><b>Sequential concurrent calls</b> (two threads call {@code run()} back-to-back): the
   *       second thread blocks on the Java monitor of {@link #lock()} until the first completes
   *       (success or rollback); the FSM has returned to a stable state by then, so the second
   *       call proceeds normally. Safe by design.
   *   <li><b>Re-entrant calls during a listener callback</b> (a {@link
   *       io.justsearch.app.api.ModeChangeListener} invoked by {@code notifyListeners} calls
   *       back into a method that delegates to {@code run()} on the same thread): the Java
   *       monitor is reentrant and lets the inner call past {@code synchronized(lock)}, but
   *       {@link ModeStateMachine#beginTransition()} sees the FSM still in
   *       {@code TRANSITIONING} and throws {@link IllegalStateException} ("Already
   *       transitioning"). This is the canonical failure mode and is pinned by a regression
   *       test.
   * </ul>
   *
   * <p>Callers that need debounced or queued behavior must wrap {@code run()} themselves
   * (e.g., with an {@code AtomicBoolean} guard at the entry point, as
   * {@code OfflineCoordinator.startOfflineProcessing} does). Relaxing the contract to
   * "queue-on-busy" is out of scope until a named consumer demands it.
   *
   * @return the FSM state after the transition (the target on success; the rolled-back state on
   *     failure)
   * @throws ModeTransitionException either thrown directly from the body or constructed from a
   *     returned {@link TransitionOutcome.Failure}
   * @throws IllegalStateException if invoked re-entrantly from a listener callback on the same
   *     thread (from {@link ModeStateMachine#beginTransition()})
   */
  Mode run(TransitionReason reason, Consumer<InferenceFailure> failureSink, TransitionBody body)
      throws ModeTransitionException {
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(body, "body");
    synchronized (lock) {
      long startNanos = System.nanoTime();
      InferenceRuntimeView priorView = viewRef.get();
      Mode prev = modeState.beginTransition();
      notifyListeners(prev, Mode.TRANSITIONING, reason);

      // Tempdoc 518 Appendix G W4.3 — wrap the body in an OTel span. The span carries
      // inference.from_phase / inference.to_phase / inference.reason / inference.success
      // attributes and (via InferenceGenerationSpanProcessor) the inference.generation
      // attribute. The W2.2 span processor reads the static-slot supplier at span start;
      // wrapping the body specifically — not the lock acquisition — keeps the timed span
      // aligned with the transition work, not the wait. Span is no-op when
      // GlobalOpenTelemetry hasn't been initialized (head with HEAD_TRACING_LEVEL=none).
      io.opentelemetry.api.trace.Span span =
          io.opentelemetry.api.GlobalOpenTelemetry.getTracer("io.justsearch.app.inference")
              .spanBuilder("inference.transition")
              .setSpanKind(io.opentelemetry.api.trace.SpanKind.INTERNAL)
              .setAttribute("inference.from_phase", prev.name())
              .setAttribute("inference.reason", reason.name())
              .startSpan();

      // Tempdoc 518 fix G: structure body invocation as { compute outcome } → { handle outcome }
      // so the FSM rollback + view install + listener notify + sink + emit happens EXACTLY
      // ONCE per transition. The prior structure (throw inside switch arm, caught by the same
      // try's `catch (MTE)` block) caused a double-rollback that threw IllegalStateException
      // ("Not transitioning") on every Failure-returning body.
      TransitionOutcome outcome;
      ModeTransitionException thrownByBody = null;
      try {
        outcome = body.execute(priorView);
      } catch (ModeTransitionException mte) {
        thrownByBody = mte;
        outcome = TransitionOutcome.failure(mapExceptionToFailure(mte), priorView);
      } catch (RuntimeException unexpected) {
        thrownByBody =
            new ModeTransitionException(
                ModeTransitionException.Reason.ONLINE_START_FAILED,
                safeMessage(unexpected),
                unexpected);
        outcome =
            TransitionOutcome.failure(
                new InferenceFailure.TransitionFailure(
                    TransitionCode.ONLINE_START_FAILED,
                    "Unexpected runtime exception in transition body: " + safeMessage(unexpected),
                    unexpected),
                priorView);
      }

      try {
        switch (outcome) {
          case TransitionOutcome.Success success -> {
            modeState.complete(success.target());
            installSuccessView(success.nextView(), success.target());
            notifyListeners(Mode.TRANSITIONING, success.target(), reason);
            emitTransition(prev, success.target(), reason, startNanos);
            span.setAttribute("inference.to_phase", success.target().name());
            span.setAttribute("inference.success", true);
            return success.target();
          }
          case TransitionOutcome.Failure failure -> {
            Mode restored =
                switch (failure.restoration()) {
                  case PREVIOUS -> modeState.rollback();
                  case OFFLINE -> {
                    modeState.complete(Mode.OFFLINE);
                    yield Mode.OFFLINE;
                  }
                };
            installFailureView(failure.rollbackView(), restored, failure.failure());
            notifyListeners(Mode.TRANSITIONING, restored, reason);
            emitFailureSink(failureSink, failure.failure());
            emitTransition(prev, restored, reason, startNanos, failure.failure());
            span.setAttribute("inference.to_phase", restored.name());
            span.setAttribute("inference.success", false);
            span.setAttribute("inference.wire_code", failure.failure().wireCode());
            span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, failure.failure().wireCode());
            throw thrownByBody != null ? thrownByBody : mapFailureToException(failure.failure());
          }
        }
      } finally {
        span.end();
      }
    }
  }

  private static void emitFailureSink(
      Consumer<InferenceFailure> sink, InferenceFailure failure) {
    if (sink == null) return;
    try {
      sink.accept(failure);
    } catch (RuntimeException ex) {
      LOG.warn("Failure-event sink threw (best-effort): {}", ex.getMessage());
    }
  }

  /**
   * Force the FSM and view to OFFLINE without going through {@code beginTransition} / {@code
   * complete}. Used by the crash-recovery + external-server-unhealthy callbacks fired from
   * {@link LlamaServerOps}, which trigger asynchronously and need to demote the inference
   * runtime without coordinating with a pending {@code beginTransition}.
   */
  void runForceOffline(TransitionReason reason, InferenceFailure failureOrNull) {
    Objects.requireNonNull(reason, "reason");
    synchronized (lock) {
      long startNanos = System.nanoTime();
      InferenceRuntimeView priorView = viewRef.get();
      Mode prev = modeState.forceOffline();
      InferenceRuntimeView next =
          priorView.withPhase(Mode.OFFLINE).withExternal(false);
      if (failureOrNull != null) {
        next = next.withLastFailure(failureOrNull);
      }
      installView(next, Mode.OFFLINE);
      if (failureOrNull != null) {
        recordFailureToHistory(failureOrNull);
      }
      notifyListeners(prev, Mode.OFFLINE, reason);
      emitTransition(prev, Mode.OFFLINE, reason, startNanos, failureOrNull);
    }
  }

  /**
   * CAS-merge a {@code /props} delta into the view atom. Safe outside the transition lock —
   * the loop retries if a concurrent transition swaps the view in between read and CAS.
   *
   * <p>Tempdoc 518 Appendix A.2 tier 2.
   */
  void mergeProps(String observedModelId, Integer observedContextTokens) {
    viewRef.updateAndGet(
        current -> {
          InferenceRuntimeView next = current;
          if (observedModelId != null && !observedModelId.isBlank()) {
            next = next.withModelId(observedModelId);
          }
          if (observedContextTokens != null && observedContextTokens > 0) {
            next = next.withContextTokens(observedContextTokens);
          }
          return next;
        });
  }

  /**
   * Install a synthetic view directly. Tempdoc 518 Appendix A.2 tier 3 — test-only affordance
   * replacing the prior {@code setUsingExternalServerForTest} package-private setter on ILM.
   */
  void installViewForTest(InferenceRuntimeView view) {
    viewRef.set(view);
  }

  /**
   * CAS-clear the {@code /props}-derived fields ({@code lastKnownModelId},
   * {@code lastKnownContextTokens}) on the view atom. Tempdoc 518 fix A — transition bodies
   * call this BEFORE {@code serverOps.startLlamaServer()} when restarting / replacing the
   * server, so any stale props from the prior server instance are wiped before the new
   * {@code /props} observation arrives via {@link #mergeProps}. Without the clear, the body's
   * post-startup {@code runner.view()} read could carry the prior server's stale model-id /
   * context-tokens through to the installed view if the new server's {@code /props} doesn't
   * fire (e.g., blank {@code model_alias}).
   */
  void clearProps() {
    viewRef.updateAndGet(current -> current.withModelId(null).withContextTokens(null));
  }

  /**
   * CAS-record a failure on the view atom <i>without</i> going through a transition. Used by
   * orchestrator-level precondition fast-fails (e.g., {@code applyConfig(null)} or
   * {@code shouldRestart && isExternalServerActive()}) that need the failure visible on
   * {@code view().lastFailure()} but do not change phase. Tempdoc 518 P2 — replaces the
   * prior direct write to {@code lastFailure} (AtomicReference) in ILM's pre-envelope guard
   * checks.
   */
  void recordFailureOutsideTransition(InferenceFailure failure) {
    viewRef.updateAndGet(current -> current.withLastFailure(failure));
    recordFailureToHistory(failure);
  }

  @GuardedBy("lock")
  private void installSuccessView(InferenceRuntimeView next, Mode target) {
    // Successful transition to a non-OFFLINE / non-TRANSITIONING phase clears prior failure.
    InferenceRuntimeView withCleared =
        (target == Mode.ONLINE || target == Mode.INDEXING) ? next.clearedFailure() : next;
    installView(withCleared, target);
  }

  @GuardedBy("lock")
  private void installFailureView(
      InferenceRuntimeView base, Mode restored, InferenceFailure failure) {
    InferenceRuntimeView next = base.withPhase(restored).withLastFailure(failure);
    installView(next, restored);
    recordFailureToHistory(failure);
  }

  @GuardedBy("lock")
  private void installView(InferenceRuntimeView next, Mode target) {
    // Tempdoc 518 fix C: bump generation UNCONDITIONALLY on every transition install.
    // The prior phase-change guard was wrong — applyConfig with restart (ONLINE → ONLINE)
    // and detachExternalServer (ONLINE → ONLINE on new port) both create a new
    // llama-server process and MUST bump the generation counter for cross-restart
    // correlation. RuntimeIdentity.generationId is documented as "increments on every
    // successful complete() transition"; the guard violated that contract.
    long gen = generationCounter.incrementAndGet();
    InferenceRuntimeView withIdentity =
        next.withPhase(target).withIdentity(buildIdentity(gen, target, next));
    viewRef.set(withIdentity);
  }

  private RuntimeIdentity buildIdentity(long generationId, Mode to, InferenceRuntimeView view) {
    if (to == Mode.OFFLINE || to == Mode.INDEXING) {
      return RuntimeIdentity.nonProcess(generationId);
    }
    String modelId = view.lastKnownModelId() != null ? view.lastKnownModelId() : "";
    return new RuntimeIdentity(generationId, modelId, 0, System.currentTimeMillis());
  }

  private void notifyListeners(Mode from, Mode to, TransitionReason reason) {
    listeners.notifyAll(new ModeChange(from, to, reason));
  }

  private void emitTransition(Mode from, Mode to, TransitionReason reason, long startNanos) {
    emitTransition(from, to, reason, startNanos, null);
  }

  private void emitTransition(
      Mode from,
      Mode to,
      TransitionReason reason,
      long startNanos,
      InferenceFailure failureOrNull) {
    Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
    try {
      events.onTransition(from.name(), to.name(), reason, elapsed);
    } catch (RuntimeException e) {
      LOG.warn("Telemetry events.onTransition threw (best-effort): {}", e.getMessage());
    }
    // Tempdoc 518 Appendix F W3.2 — record the transition (success or failure) so the
    // Brain-panel timeline can render the recent sequence. Best-effort; failures here must
    // not break the transition itself.
    try {
      recordTransitionToHistory(from, to, reason, elapsed.toMillis(), failureOrNull);
    } catch (RuntimeException e) {
      LOG.warn("Transition-history record threw (best-effort): {}", e.getMessage());
    }
  }

  /**
   * Maps a returned {@link TransitionOutcome.Failure} into a {@link ModeTransitionException} for
   * the Slice 1 compatibility bridge. Slice 2 (P3) replaces this with
   * {@code InferenceFailureException(failure)}, removing the bridge entirely.
   */
  private static ModeTransitionException mapFailureToException(InferenceFailure failure) {
    String detail = failure.detail();
    Throwable cause = failure.cause().orElse(null);
    return switch (failure) {
      case InferenceFailure.StartupFailure sf ->
          new ModeTransitionException(mapStartupCode(sf.code()), detail, cause);
      case InferenceFailure.HealthFailure hf ->
          new ModeTransitionException(mapHealthCode(hf.code()), detail, cause);
      case InferenceFailure.ConfigFailure cf ->
          new ModeTransitionException(mapConfigCode(cf.code()), detail, cause);
      case InferenceFailure.TransitionFailure tf ->
          new ModeTransitionException(mapTransitionCode(tf.code()), detail, cause);
    };
  }

  /**
   * Inverse of {@link #mapFailureToException}: when a body throws {@link ModeTransitionException}
   * directly (e.g., from {@code config.validate()} or a collaborator's checked exception), the
   * runner converts it back to a typed {@link InferenceFailure} for the view's
   * {@code lastFailure} field. Slice 2 removes both directions.
   */
  static InferenceFailure mapExceptionToFailure(ModeTransitionException mte) {
    String detail = mte.getMessage() != null ? mte.getMessage() : "unknown";
    Throwable cause = mte.getCause();
    return switch (mte.reason()) {
      case INVALID_CONFIG -> new InferenceFailure.ConfigFailure(ConfigCode.INVALID_CONFIG, detail);
      case CONFIG_REQUIRED ->
          new InferenceFailure.ConfigFailure(ConfigCode.CONFIG_REQUIRED, detail);
      case ALREADY_TRANSITIONING ->
          new InferenceFailure.ConfigFailure(ConfigCode.ALREADY_TRANSITIONING, detail);
      case EXTERNAL_SERVER_CONFLICT ->
          new InferenceFailure.ConfigFailure(ConfigCode.EXTERNAL_SERVER_CONFLICT, detail);
      case INSUFFICIENT_VRAM ->
          new InferenceFailure.StartupFailure(StartupCode.INSUFFICIENT_VRAM, detail, cause);
      case MISSING_DLL -> new InferenceFailure.StartupFailure(StartupCode.MISSING_DLL, detail, cause);
      case EXECUTABLE_NOT_FOUND ->
          new InferenceFailure.StartupFailure(StartupCode.EXECUTABLE_NOT_FOUND, detail, cause);
      case PROCESS_EXITED ->
          new InferenceFailure.StartupFailure(StartupCode.PROCESS_EXITED, detail, cause);
      case PORT_ALLOCATION_FAILED ->
          new InferenceFailure.StartupFailure(StartupCode.PORT_ALLOCATION_FAILED, detail, cause);
      case EXTERNAL_SERVER_POLICY_BLOCKED ->
          new InferenceFailure.StartupFailure(
              StartupCode.EXTERNAL_SERVER_POLICY_BLOCKED, detail, cause);
      case HEALTH_CHECK_TIMEOUT ->
          new InferenceFailure.HealthFailure(HealthCode.HEALTH_TIMEOUT, detail, cause);
      case HEALTH_CHECK_INTERRUPTED ->
          new InferenceFailure.HealthFailure(HealthCode.HEALTH_INTERRUPTED, detail, cause);
      case ONLINE_START_FAILED ->
          new InferenceFailure.TransitionFailure(TransitionCode.ONLINE_START_FAILED, detail, cause);
      case INDEXING_START_FAILED ->
          new InferenceFailure.TransitionFailure(
              TransitionCode.INDEXING_START_FAILED, detail, cause);
      case CONFIG_APPLY_FAILED ->
          new InferenceFailure.TransitionFailure(TransitionCode.CONFIG_APPLY_FAILED, detail, cause);
      case INTERRUPTED ->
          new InferenceFailure.TransitionFailure(TransitionCode.INTERRUPTED, detail, cause);
    };
  }

  private static ModeTransitionException.Reason mapStartupCode(StartupCode code) {
    return switch (code) {
      case INSUFFICIENT_VRAM -> ModeTransitionException.Reason.INSUFFICIENT_VRAM;
      case MISSING_DLL -> ModeTransitionException.Reason.MISSING_DLL;
      case EXECUTABLE_NOT_FOUND -> ModeTransitionException.Reason.EXECUTABLE_NOT_FOUND;
      case PROCESS_EXITED -> ModeTransitionException.Reason.PROCESS_EXITED;
      case PORT_ALLOCATION_FAILED -> ModeTransitionException.Reason.PORT_ALLOCATION_FAILED;
      case EXTERNAL_SERVER_POLICY_BLOCKED ->
          ModeTransitionException.Reason.EXTERNAL_SERVER_POLICY_BLOCKED;
      case UNKNOWN -> ModeTransitionException.Reason.ONLINE_START_FAILED;
    };
  }

  private static ModeTransitionException.Reason mapHealthCode(HealthCode code) {
    return switch (code) {
      case HEALTH_TIMEOUT -> ModeTransitionException.Reason.HEALTH_CHECK_TIMEOUT;
      case HEALTH_INTERRUPTED -> ModeTransitionException.Reason.HEALTH_CHECK_INTERRUPTED;
      case PROCESS_DIED -> ModeTransitionException.Reason.PROCESS_EXITED;
      case CONNECTION_REFUSED, UNKNOWN -> ModeTransitionException.Reason.HEALTH_CHECK_TIMEOUT;
    };
  }

  private static ModeTransitionException.Reason mapConfigCode(ConfigCode code) {
    return switch (code) {
      case INVALID_CONFIG -> ModeTransitionException.Reason.INVALID_CONFIG;
      case CONFIG_REQUIRED -> ModeTransitionException.Reason.CONFIG_REQUIRED;
      case ALREADY_TRANSITIONING -> ModeTransitionException.Reason.ALREADY_TRANSITIONING;
      case EXTERNAL_SERVER_CONFLICT -> ModeTransitionException.Reason.EXTERNAL_SERVER_CONFLICT;
      case UNKNOWN -> ModeTransitionException.Reason.INVALID_CONFIG;
    };
  }

  private static ModeTransitionException.Reason mapTransitionCode(TransitionCode code) {
    return switch (code) {
      case ONLINE_START_FAILED -> ModeTransitionException.Reason.ONLINE_START_FAILED;
      case INDEXING_START_FAILED -> ModeTransitionException.Reason.INDEXING_START_FAILED;
      case CONFIG_APPLY_FAILED -> ModeTransitionException.Reason.CONFIG_APPLY_FAILED;
      case INTERRUPTED -> ModeTransitionException.Reason.INTERRUPTED;
    };
  }

  private static String safeMessage(Throwable t) {
    if (t == null) return "unknown";
    String m = t.getMessage();
    if (m != null && !m.isBlank()) return m;
    return t.getClass().getSimpleName();
  }
}
