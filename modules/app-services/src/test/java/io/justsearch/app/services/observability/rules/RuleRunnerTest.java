package io.justsearch.app.services.observability.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.observability.health.AssertedCondition;
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.HealthEvent;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.OccurrenceLog;
import io.justsearch.app.observability.health.Severity;
import io.justsearch.app.observability.health.Source;
import io.justsearch.app.observability.health.ThresholdPhase;
import io.justsearch.app.observability.health.ThresholdState;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.telemetry.RrdMetricStore;
import io.justsearch.telemetry.RrdMetricStore.TimeSeriesResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RuleRunner end-to-end")
final class RuleRunnerTest {

  private static final Instant T0 = Instant.parse("2026-05-02T10:00:00Z");
  private static final Source HEAD_SRC = Source.forProcess("head", "instance-1", "1.0");

  private MutableClock clock;
  private StubRrdStore rrd;
  private SignalSource signalSource;
  private CelEvaluator evaluator;
  private DwellTimeScheduler scheduler;
  private ConditionStore conditions;
  private HealthEventChangeRegistry changes;
  private RecordingListener listener;
  private RuleEmitter emitter;
  private TestEngineExecutors processExecutors;

  @BeforeEach
  void setUp() {
    processExecutors = new TestEngineExecutors();
    clock = new MutableClock(T0);
    rrd = new StubRrdStore();
    signalSource = new SignalSource(rrd, clock);
    evaluator = new CelEvaluator();
    scheduler = new DwellTimeScheduler(clock);
    conditions = new ConditionStore();
    changes = new HealthEventChangeRegistry();
    listener = new RecordingListener();
    changes.subscribeTyped(listener);
    emitter = new RuleEmitter(conditions, changes, HEAD_SRC, clock);
  }

  @AfterEach
  void closeProcessExecutors() {
    processExecutors.close();
  }

  /** The standard memory-pressure rule: for=60s, keep_firing_for=30s. */
  private Rule memoryPressureRule() {
    return new Rule(
        "memory-pressure",
        Rule.Kind.THRESHOLD,
        new Rule.Emits("memory.pressure", "head.memory", "MemoryPressureHigh", Severity.WARNING),
        "signals['head.jvm.memory.heap.used_bytes'].latest()"
            + " / signals['head.jvm.memory.heap.max_bytes'].latest() > 0.9",
        Duration.ofSeconds(60),
        Duration.ofSeconds(30),
        Map.of(
            "used_bytes", "signals['head.jvm.memory.heap.used_bytes'].latest()",
            "max_bytes", "signals['head.jvm.memory.heap.max_bytes'].latest()"));
  }

  private RuleRunner buildRunner(Rule rule) {
    RuleCatalog catalog = RuleCatalog.ofRules(List.of(rule));
    return new RuleRunner(
        processExecutors,
        catalog, evaluator, signalSource, scheduler, emitter, Duration.ofSeconds(5));
  }

  private void writeRatio(double ratio) {
    long now = clock.instant().getEpochSecond();
    rrd.put(
        "head.jvm.memory.heap.used_bytes",
        new TimeSeriesResult(
            "head.jvm.memory.heap.used_bytes",
            new long[] {now},
            new double[] {ratio * 1_000_000_000.0}));
    rrd.put(
        "head.jvm.memory.heap.max_bytes",
        new TimeSeriesResult(
            "head.jvm.memory.heap.max_bytes",
            new long[] {now},
            new double[] {1_000_000_000.0}));
  }

  // ============================================================
  // End-to-end firing lifecycle
  // ============================================================

  @Test
  @DisplayName("memory.pressure fires after for=60s sustained > 0.9 → ConditionStore + broadcast")
  void memoryPressureFiresEndToEnd() {
    Rule rule = memoryPressureRule();
    RuleRunner runner = buildRunner(rule);

    // Tick 1 at T0: predicate true, but `for` not elapsed → PENDING.
    writeRatio(0.95);
    runner.tickOnce();
    assertEquals(0, listener.size(), "No broadcast before `for` elapses");

    // Tick 2 at T0+60s: predicate still true, `for` elapsed → FIRING + STARTED_FIRING broadcast.
    clock.advance(Duration.ofSeconds(60));
    writeRatio(0.95);
    runner.tickOnce();
    assertEquals(1, listener.size(), "STARTED_FIRING broadcast at T0+60");
    HealthEventChangeRegistry.HealthChangeEvent broadcast = listener.events.get(0);
    assertEquals(HealthEventChangeRegistry.Kind.CONDITION_ADDED, broadcast.kind());
    assertEquals("memory.pressure", broadcast.event().id());
    ThresholdState body = (ThresholdState) broadcast.event().body();
    assertEquals("head.memory", body.subject());
    assertEquals(ThresholdPhase.FIRING, body.phase());
    assertEquals(Severity.WARNING, broadcast.event().severity());
    assertTrue(body.magnitudes().containsKey("used_bytes"));
    assertTrue(body.magnitudes().containsKey("max_bytes"));
  }

  @Test
  @DisplayName("memory.pressure resolves after keep_firing_for=30s sustained false → CONDITION_REMOVED")
  void memoryPressureResolvesEndToEnd() {
    Rule rule = memoryPressureRule();
    RuleRunner runner = buildRunner(rule);

    // Drive to FIRING.
    writeRatio(0.95);
    runner.tickOnce(); // → PENDING
    clock.advance(Duration.ofSeconds(60));
    writeRatio(0.95);
    runner.tickOnce(); // → FIRING (1 broadcast)

    // Predicate flips false → KEEP_FIRING (no broadcast yet).
    clock.advance(Duration.ofSeconds(5));
    writeRatio(0.5);
    runner.tickOnce();
    assertEquals(1, listener.size(), "No broadcast on transition to KEEP_FIRING");

    // After keep_firing_for=30s sustained false → INACTIVE + RESOLVED broadcast.
    clock.advance(Duration.ofSeconds(30));
    writeRatio(0.5);
    runner.tickOnce();
    assertEquals(2, listener.size(), "RESOLVED broadcast after grace expires");
    HealthEventChangeRegistry.HealthChangeEvent resolveBroadcast = listener.events.get(1);
    assertEquals(HealthEventChangeRegistry.Kind.CONDITION_REMOVED, resolveBroadcast.kind());
    assertEquals("memory.pressure", resolveBroadcast.event().id());
  }

  @Test
  @DisplayName("missing metric → blind-monitor UNKNOWN condition, NOT a threshold fire (600 Design B)")
  void missingMetricEmitsBlindConditionNoFire() {
    Rule rule = memoryPressureRule();
    RuleRunner runner = buildRunner(rule);

    // No metrics written to RRD → CelEvaluator returns Indeterminate (NOT predicate-false).
    runner.tickOnce();
    clock.advance(Duration.ofSeconds(60));
    runner.tickOnce();

    // The threshold rule must NOT fire (the rule cannot evaluate — it is not "healthy" nor "firing").
    long memoryPressureAdds =
        listener.events.stream()
            .filter(e -> e.kind() == HealthEventChangeRegistry.Kind.CONDITION_ADDED)
            .filter(e -> e.event().id().equals("memory.pressure"))
            .count();
    assertEquals(0, memoryPressureAdds, "memory.pressure must not fire while blind");

    // But the blindness IS surfaced as a monitor.unobservable UNKNOWN AssertedCondition.
    HealthEvent blind =
        listener.events.stream()
            .filter(e -> e.kind() == HealthEventChangeRegistry.Kind.CONDITION_ADDED)
            .map(HealthEventChangeRegistry.HealthChangeEvent::event)
            .filter(ev -> ev.id().equals("monitor.unobservable"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected a monitor.unobservable condition"));
    AssertedCondition body = (AssertedCondition) blind.body();
    assertEquals("head.memory", body.subject());
    assertEquals(io.justsearch.app.observability.health.ConditionStatus.UNKNOWN, body.status());
    assertEquals(Severity.INFO, blind.severity(), "blind monitor is diagnostic, not an alarm");
  }

  @Test
  @DisplayName("blind monitor condition does NOT churn across ticks (600 C-2: stable identity)")
  void blindMonitorDoesNotChurn() {
    Rule rule = memoryPressureRule();
    RuleRunner runner = buildRunner(rule);

    // Tick blind 4 times, advancing the clock between ticks. Before C-2 the missing-metric reason
    // embedded a moving window, so each tick re-emitted a CONDITION_MODIFIED; now the reason is the
    // stable fact, so ConditionStore dedups to UNCHANGED after the first ADD.
    for (int i = 0; i < 4; i++) {
      runner.tickOnce();
      clock.advance(Duration.ofSeconds(5));
    }

    long added =
        listener.events.stream()
            .filter(e -> e.event().id().equals("monitor.unobservable"))
            .filter(e -> e.kind() == HealthEventChangeRegistry.Kind.CONDITION_ADDED)
            .count();
    long modified =
        listener.events.stream()
            .filter(e -> e.event().id().equals("monitor.unobservable"))
            .filter(e -> e.kind() == HealthEventChangeRegistry.Kind.CONDITION_MODIFIED)
            .count();
    assertEquals(1, added, "blind condition added exactly once across 4 blind ticks");
    assertEquals(
        0, modified, "stable blind-condition identity must not churn CONDITION_MODIFIED every tick");
  }

  @Test
  @DisplayName("blind monitor condition clears when samples return (600 Design B)")
  void blindMonitorClearsWhenSamplesReturn() {
    Rule rule = memoryPressureRule();
    RuleRunner runner = buildRunner(rule);

    runner.tickOnce(); // blind → monitor.unobservable ADDED
    clock.advance(Duration.ofSeconds(5));
    writeRatio(0.5); // samples return, predicate false (healthy) → blind condition cleared
    runner.tickOnce();

    boolean cleared =
        listener.events.stream()
            .anyMatch(
                e ->
                    e.kind() == HealthEventChangeRegistry.Kind.CONDITION_REMOVED
                        && e.event().id().equals("monitor.unobservable"));
    assertTrue(cleared, "blind condition must clear once the rule can evaluate again");
  }

  @Test
  @DisplayName("a blind tick never RESOLVES a firing rule (600 Design B — no false healthy)")
  void blindDoesNotResolveFiringRule() {
    Rule rule = memoryPressureRule();
    RuleRunner runner = buildRunner(rule);

    // Drive to FIRING.
    writeRatio(0.95);
    runner.tickOnce(); // → PENDING
    clock.advance(Duration.ofSeconds(60));
    writeRatio(0.95);
    runner.tickOnce(); // → FIRING

    // Metrics disappear (blind). Advance well past keep_firing_for=30s with only blind ticks.
    clock.advance(Duration.ofSeconds(120));
    rrd.clear();
    runner.tickOnce();

    // memory.pressure must NOT be RESOLVED — a missing metric is not evidence the pressure cleared.
    boolean resolved =
        listener.events.stream()
            .anyMatch(
                e ->
                    e.kind() == HealthEventChangeRegistry.Kind.CONDITION_REMOVED
                        && e.event().id().equals("memory.pressure"));
    assertTrue(!resolved, "blind tick must not spuriously resolve a firing rule");
  }

  @Test
  @DisplayName("predicate flapping within keep_firing_for stays FIRING (no double-emit)")
  void flappingNoDoubleEmit() {
    Rule rule = memoryPressureRule();
    RuleRunner runner = buildRunner(rule);

    // Drive to FIRING.
    writeRatio(0.95);
    runner.tickOnce();
    clock.advance(Duration.ofSeconds(60));
    writeRatio(0.95);
    runner.tickOnce(); // STARTED_FIRING broadcast (1)

    // Predicate flips false then true within keep_firing_for.
    clock.advance(Duration.ofSeconds(10));
    writeRatio(0.5);
    runner.tickOnce(); // FIRING → KEEP_FIRING (no broadcast)
    clock.advance(Duration.ofSeconds(5));
    writeRatio(0.95);
    runner.tickOnce(); // KEEP_FIRING → FIRING (NO new STARTED_FIRING broadcast — but
    // emitFiringMagnitudes may broadcast a CONDITION_MODIFIED for magnitude update)

    // Verify NO new STARTED_FIRING was emitted (CONDITION_ADDED count remains 1).
    long addedCount =
        listener.events.stream()
            .filter(e -> e.kind() == HealthEventChangeRegistry.Kind.CONDITION_ADDED)
            .count();
    assertEquals(1, addedCount, "Only 1 CONDITION_ADDED across the flap");
  }

  // ============================================================
  // Helpers
  // ============================================================

  private static final class StubRrdStore extends RrdMetricStore {
    private final Map<String, TimeSeriesResult> data = new HashMap<>();

    StubRrdStore() {
      super(java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "rule-runner-stub"));
    }

    void put(String name, TimeSeriesResult result) {
      data.put(name, result);
    }

    void clear() {
      data.clear();
    }

    @Override
    public synchronized TimeSeriesResult query(String metricName, long start, long end) {
      return data.get(metricName);
    }

    @Override
    public synchronized void initialize() {
      // No-op for tests.
    }
  }

  private static final class RecordingListener
      implements java.util.function.Consumer<HealthEventChangeRegistry.HealthChangeEvent> {
    final List<HealthEventChangeRegistry.HealthChangeEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void accept(HealthEventChangeRegistry.HealthChangeEvent change) {
      events.add(change);
    }

    int size() {
      return events.size();
    }
  }

  private static final class MutableClock extends Clock {
    private volatile Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration d) {
      this.now = now.plus(d);
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
