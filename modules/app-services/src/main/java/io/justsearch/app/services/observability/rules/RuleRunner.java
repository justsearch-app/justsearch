/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.observability.rules;

import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodic rule evaluator: ticks every {@link #tickInterval}, evaluates each rule's CEL
 * predicate, advances the {@link DwellTimeScheduler}, emits transitions through
 * {@link RuleEmitter}.
 *
 * <p>Per tempdoc 430 §A.3 + rev 3.11 §B.X.6: lifecycle methods {@link #start()} and
 * {@link #stop()} are symmetric with {@code LocalApiServer.stop()} to avoid tick interleaving
 * with subscriber broadcasts during shutdown.
 *
 * <p>Tempdoc 600 Design B: predicate evaluation that throws {@link MissingMetricException} is
 * caught upstream by {@link CelEvaluator#evaluatePredicate} and returned as
 * {@link PredicateOutcome.Indeterminate} (the rule <em>cannot evaluate</em>) — NOT predicate-false.
 * On an indeterminate tick the {@link DwellTimeScheduler} freezes (no state change) and the
 * blindness is surfaced as a {@code monitor.unobservable} {@code AssertedCondition(status=UNKNOWN)}
 * via {@link RuleEmitter}, so a blind monitor can never masquerade as healthy. Other CEL evaluation
 * exceptions are caught here, logged at WARN, and treated as no-op for the tick (no state change).
 */
public final class RuleRunner {

  private static final Logger log = LoggerFactory.getLogger(RuleRunner.class);

  /** Default tick interval. Configurable via {@code JUSTSEARCH_RULE_TICK_MS} env var. */
  public static final Duration DEFAULT_TICK_INTERVAL = Duration.ofSeconds(5);

  private final RuleCatalog catalog;
  private final CelEvaluator evaluator;
  private final SignalSource signalSource;
  private final DwellTimeScheduler scheduler;
  private final RuleEmitter emitter;
  private final Duration tickInterval;
  private final EngineExecutorRegistry.Registration executorRegistration;
  private final ScheduledExecutorService executor;

  /** Cached compiled programs per (rule, expression-key). */
  private final ConcurrentMap<String, CelRuntime.Program> predicatePrograms =
      new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Map<String, CelRuntime.Program>> magnitudePrograms =
      new ConcurrentHashMap<>();

  private final AtomicLong tickCount = new AtomicLong();
  private volatile ScheduledFuture<?> running;

  public RuleRunner(
      EngineExecutorRegistry processExecutors,
      RuleCatalog catalog,
      CelEvaluator evaluator,
      SignalSource signalSource,
      DwellTimeScheduler scheduler,
      RuleEmitter emitter,
      Duration tickInterval) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
    this.signalSource = Objects.requireNonNull(signalSource, "signalSource");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.emitter = Objects.requireNonNull(emitter, "emitter");
    this.tickInterval = Objects.requireNonNull(tickInterval, "tickInterval");
    if (tickInterval.isZero() || tickInterval.isNegative()) {
      throw new IllegalArgumentException("tickInterval must be > 0, got " + tickInterval);
    }
    Objects.requireNonNull(processExecutors, "processExecutors");
    EngineExecutorRegistry.Limits background =
        processExecutors.limits(EngineExecutorSpec.Kind.BACKGROUND);
    EngineExecutorRegistry.Registration registration =
        processExecutors.register(
            new EngineExecutorSpec(
                "head.rule-runner",
                EngineExecutorSpec.Kind.BACKGROUND,
                EngineExecutorSpec.Mode.SCHEDULED,
                1,
                background.maxQueue(),
                1));
    try {
      this.executorRegistration = registration;
      this.executor =
          registration.openScheduled(daemonThreadFactory("rule-runner"));
      precompileAll();
    } catch (RuntimeException | Error failure) {
      try {
        registration.close();
      } catch (RuntimeException | Error cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }
  }

  /** Starts periodic evaluation. Idempotent: a second call is a no-op. */
  public synchronized void start() {
    if (running != null) {
      return;
    }
    long ms = tickInterval.toMillis();
    running = executor.scheduleAtFixedRate(this::tickSafely, ms, ms, TimeUnit.MILLISECONDS);
    log.info(
        "RuleRunner started; {} rules registered; tick={}ms", catalog.size(), ms);
  }

  /** Stops periodic evaluation. Waits up to 5s for the in-flight tick to complete. */
  public synchronized void stop() {
    ScheduledFuture<?> task = running;
    if (task != null) {
      task.cancel(false);
      running = null;
    }
    executor.shutdown();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      executor.shutdownNow();
    } finally {
      executorRegistration.close();
    }
  }

  /** Runs one evaluation tick synchronously. Exposed for tests; production uses {@link #start}. */
  public void tickOnce() {
    tickSafely();
  }

  /** Returns the number of completed ticks. */
  public long tickCount() {
    return tickCount.get();
  }

  // ============================================================
  // Internal
  // ============================================================

  private void tickSafely() {
    try {
      runTick();
    } catch (RuntimeException e) {
      // Defensive: a single bad tick must never kill the scheduler. Logged at WARN.
      log.warn("RuleRunner: tick failed: {}", e.getMessage(), e);
    } finally {
      tickCount.incrementAndGet();
    }
  }

  private void runTick() {
    List<Rule> rules = catalog.rules();
    if (rules.isEmpty()) {
      return;
    }
    for (Rule rule : rules) {
      try {
        evaluateRule(rule);
      } catch (RuntimeException e) {
        // One rule's failure must not poison the whole tick.
        log.warn("RuleRunner: rule '{}' evaluation failed: {}", rule.name(), e.getMessage(), e);
      }
    }
  }

  private void evaluateRule(Rule rule) {
    Map<String, Signal> signals = collectSignals(rule);
    PredicateOutcome outcome = evaluatePredicate(rule, signals);
    if (outcome instanceof PredicateOutcome.Indeterminate indeterminate) {
      // Tempdoc 600 Design B: the predicate could not be evaluated (its metric has no samples).
      // Surface the blindness as an UNKNOWN condition and FREEZE the dwell machine — never treat
      // "can't see" as "healthy" (which would reset PENDING / spuriously RESOLVE a firing rule).
      emitter.emitUnobservable(rule, indeterminate.reason());
      scheduler.tick(rule, outcome); // frozen: returns empty, no state change
      return;
    }
    // Evaluated: clear any prior blind-monitor condition (idempotent no-op if none is asserted).
    emitter.clearUnobservable(rule);
    boolean predicateTrue = ((PredicateOutcome.Evaluated) outcome).value();
    Optional<DwellTimeScheduler.Transition> transition = scheduler.tick(rule, outcome);
    if (transition.isEmpty()) {
      // No emission this tick; if FIRING and magnitudes_cel is non-empty, emit a
      // magnitude update so subscribers see the latest values without re-emitting STARTED_FIRING.
      if (predicateTrue
          && scheduler.currentState(rule.name()) == DwellTimeScheduler.State.FIRING
          && !rule.magnitudesCel().isEmpty()) {
        Map<String, Number> magnitudes = evaluateMagnitudes(rule, signals);
        emitter.emitFiringMagnitudes(rule, magnitudes);
      }
      return;
    }
    switch (transition.get()) {
      case STARTED_FIRING -> {
        Map<String, Number> magnitudes = evaluateMagnitudes(rule, signals);
        emitter.emitStartedFiring(rule, magnitudes);
      }
      case RESOLVED -> emitter.emitResolved(rule);
    }
  }

  private Map<String, Signal> collectSignals(Rule rule) {
    // Build the signals map from every metric name referenced in this rule's CEL expressions.
    // The CEL evaluator demands all referenced map keys be resolvable; we let SignalSource
    // construct Signal stubs for any name (Signal.latest throws → predicate-false handling).
    LinkedHashMap<String, Signal> signals = new LinkedHashMap<>();
    for (String metricName : extractMetricNames(rule.exprCel())) {
      signals.put(metricName, signalSource.forName(metricName));
    }
    for (String expr : rule.magnitudesCel().values()) {
      for (String metricName : extractMetricNames(expr)) {
        signals.computeIfAbsent(metricName, signalSource::forName);
      }
    }
    return signals;
  }

  /**
   * Extracts {@code signals['<name>']} bracket lookups from a CEL expression text. This is a
   * deliberately simple substring scan; metric names are stable identifiers (no escaping
   * concerns) and the rule files are vetted at boot. A more robust extractor would walk the
   * compiled CelAbstractSyntaxTree; deferred to V1.5 with the rest of the rule-engine
   * generalization.
   */
  static List<String> extractMetricNames(String exprCel) {
    List<String> result = new java.util.ArrayList<>();
    int idx = 0;
    while (true) {
      int open = exprCel.indexOf("signals['", idx);
      if (open < 0) {
        break;
      }
      int start = open + "signals['".length();
      int close = exprCel.indexOf("']", start);
      if (close < 0) {
        break;
      }
      result.add(exprCel.substring(start, close));
      idx = close + 2;
    }
    return result;
  }

  private PredicateOutcome evaluatePredicate(Rule rule, Map<String, Signal> signals) {
    String key = "rule:" + rule.name() + ":expr_cel";
    CelRuntime.Program program =
        predicatePrograms.computeIfAbsent(key, k -> evaluator.compile(k, rule.exprCel()));
    return evaluator.evaluatePredicate(program, signals, rule.name());
  }

  private Map<String, Number> evaluateMagnitudes(Rule rule, Map<String, Signal> signals) {
    Map<String, CelRuntime.Program> compiled =
        magnitudePrograms.computeIfAbsent(
            rule.name(), n -> compileMagnitudes(rule));
    LinkedHashMap<String, Number> out = new LinkedHashMap<>();
    for (Map.Entry<String, CelRuntime.Program> entry : compiled.entrySet()) {
      try {
        Object value = evaluator.evaluate(entry.getValue(), signals);
        if (value instanceof Number n) {
          out.put(entry.getKey(), n);
        }
        // Non-numeric values are dropped — magnitudes are documented as Map<String, Number>.
      } catch (CelEvaluationException e) {
        // Per §A.9: magnitude extraction failure logs at WARN; doesn't block firing.
        log.debug(
            "RuleRunner: rule '{}' magnitude '{}' evaluation failed: {}",
            rule.name(),
            entry.getKey(),
            e.getMessage());
      }
    }
    return out;
  }

  private Map<String, CelRuntime.Program> compileMagnitudes(Rule rule) {
    LinkedHashMap<String, CelRuntime.Program> map = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : rule.magnitudesCel().entrySet()) {
      String key = "rule:" + rule.name() + ":magnitudes:" + entry.getKey();
      map.put(entry.getKey(), evaluator.compile(key, entry.getValue()));
    }
    return Map.copyOf(map);
  }

  private void precompileAll() {
    for (Rule rule : catalog.rules()) {
      String key = "rule:" + rule.name() + ":expr_cel";
      predicatePrograms.put(key, evaluator.compile(key, rule.exprCel()));
      if (!rule.magnitudesCel().isEmpty()) {
        magnitudePrograms.put(rule.name(), compileMagnitudes(rule));
      }
    }
  }

  private static ThreadFactory daemonThreadFactory(String name) {
    return r -> {
      Thread t = new Thread(r, name);
      t.setDaemon(true);
      return t;
    };
  }
}
