/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.Source;
import io.justsearch.app.services.observability.rules.CelEvaluator;
import io.justsearch.app.services.observability.rules.DwellTimeScheduler;
import io.justsearch.app.services.observability.rules.RuleCatalog;
import io.justsearch.app.services.observability.rules.RuleEmitter;
import io.justsearch.app.services.observability.rules.RuleRunner;
import io.justsearch.app.services.observability.rules.SignalSource;
import io.justsearch.telemetry.LocalTelemetry;
import io.justsearch.telemetry.RrdMetricStore;
import io.justsearch.telemetry.Telemetry;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 519 §7 / Step 7: Phase 8 rule-engine RuleRunner builder extracted from
 * {@code HeadAssembly#buildRuleRunner}. Returns null when the rule catalog is empty or
 * telemetry cannot expose an {@link RrdMetricStore} (e.g., the noop {@link Telemetry}).
 */
public final class RuleRunnerBuilder {

  private static final Logger log = LoggerFactory.getLogger(RuleRunnerBuilder.class);

  private RuleRunnerBuilder() {}

  public static RuleRunner build(io.justsearch.core.execution.EngineExecutorRegistry executors,
      Telemetry telemetry,
      ConditionStore conditionStore,
      HealthEventChangeRegistry healthEventChangeRegistry,
      Source headSource) {
    RuleCatalog catalog = RuleCatalog.fromClasspath();
    if (catalog.size() == 0) {
      return null;
    }
    if (!(telemetry instanceof LocalTelemetry lt)) {
      log.warn(
          "Tempdoc 430 Phase 8: rule catalog has {} rule(s) but telemetry is not"
              + " LocalTelemetry — rule engine disabled.",
          catalog.size());
      return null;
    }
    RrdMetricStore rrd = lt.getRrdStore();
    if (rrd == null) {
      log.warn(
          "Tempdoc 430 Phase 8: rule catalog has {} rule(s) but RrdMetricStore is null —"
              + " rule engine disabled.",
          catalog.size());
      return null;
    }
    Clock clock = Clock.systemUTC();
    CelEvaluator evaluator = new CelEvaluator();
    SignalSource signalSource = new SignalSource(rrd, clock);
    DwellTimeScheduler scheduler = new DwellTimeScheduler(clock);
    RuleEmitter emitter =
        new RuleEmitter(conditionStore, healthEventChangeRegistry, headSource, clock);
    Duration tickInterval = resolveTickInterval();
    return new RuleRunner(executors, catalog, evaluator, signalSource, scheduler, emitter, tickInterval);
  }

  private static Duration resolveTickInterval() {
    String raw = EnvRegistry.RULE_TICK_MS.get().orElse(null);
    if (raw == null || raw.isBlank()) {
      return RuleRunner.DEFAULT_TICK_INTERVAL;
    }
    try {
      long ms = Long.parseLong(raw.trim());
      if (ms <= 0) {
        return RuleRunner.DEFAULT_TICK_INTERVAL;
      }
      return Duration.ofMillis(ms);
    } catch (NumberFormatException e) {
      return RuleRunner.DEFAULT_TICK_INTERVAL;
    }
  }
}
