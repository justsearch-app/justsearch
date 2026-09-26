/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.app.observability.health.AssertedCondition;
import io.justsearch.app.observability.health.ConditionStatus;
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.HealthEvent;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.LifecycleEvent;
import io.justsearch.app.observability.health.OccurrenceLog;
import io.justsearch.app.observability.health.Severity;
import io.justsearch.app.observability.health.Source;
import io.justsearch.app.services.lifecycle.RegistryBackedCapability;
import io.justsearch.core.component.EngineComponentRegistry;
import io.justsearch.app.services.worker.RecoveryContext;
import io.justsearch.app.services.worker.RecoveryOccurrence;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Projects registry capability observations into conditions and narrates recovery decisions. */
public final class CapabilityHealthBridge {

  private static final Logger log = LoggerFactory.getLogger(CapabilityHealthBridge.class);

  private CapabilityHealthBridge() {}

  /**
   * Occurrence IDs this bridge emits (tempdoc 627) — the producer-set declaration the
   * {@code HealthEventEmitCoverageTest} reconciles against the canonical catalog.
   */
  public static java.util.Set<String> emittableIds() {
    return java.util.Set.of("worker.restart-attempted", "worker.recovered");
  }

  /** Owns both subscriptions, including ordered current-state replay. */
  public static AutoCloseable wireListeners(
      EngineComponentRegistry components,
      ConditionStore conditionStore,
      HealthEventChangeRegistry changeRegistry,
      Source headSource) {
    Clock clock = Clock.systemUTC();
    var worker = new RegistryBackedCapability(components, "index", "worker");
    var inference = new RegistryBackedCapability(components, "generative", "inference");
    var workerSubscription = worker.subscribeCurrent(observation -> pushCondition(
        "worker.capability", "worker", observation.health(), observation.reason(),
        observation.detail(), Severity.WARNING, conditionStore, changeRegistry, headSource, clock));
    try {
      var inferenceSubscription = inference.subscribeCurrent(observation -> {
        if (observation.health() != CapabilityHealth.PENDING) {
          pushCondition("inference.capability", "inference", observation.health(),
              observation.reason(), observation.detail(), Severity.INFO,
              conditionStore, changeRegistry, headSource, clock);
        }
      });
      return () -> {
        try { inferenceSubscription.close(); }
        finally { workerSubscription.close(); }
      };
    } catch (RuntimeException | Error failure) {
      workerSubscription.close();
      throw failure;
    }
  }

  /**
   * Tempdoc 837 §0.2: the Condition {@code message} is one of the two places a HUMAN sentence
   * legitimately belongs (the other is the 503 debug body), so it prefers the capability's
   * {@code pendingDetail()} — "Health check failed after 4200ms", the corrupt-index remedy paragraph
   * — and falls back to the reason CODE only when there is no sentence. Before the prose→code sweep
   * this field carried the sentence by accident, because the sentence WAS the reason; passing the
   * code alone would have silently degraded every Health event to a bare token.
   */
  private static void pushCondition(
      String condId,
      String subject,
      CapabilityHealth health,
      String reason,
      String detail,
      Severity severity,
      ConditionStore conditionStore,
      HealthEventChangeRegistry changeRegistry,
      Source headSource,
      Clock clock) {
    try {
      if (health == CapabilityHealth.READY) {
        conditionStore
            .clear(condId, subject)
            .ifPresent(removed ->
                changeRegistry.broadcast(HealthEventChangeRegistry.Kind.CONDITION_REMOVED, removed));
      } else {
        String message = detail != null && !detail.isBlank() ? detail : reason;
        AssertedCondition condition = new AssertedCondition(
            subject,
            ConditionStatus.TRUE,
            toPascalCase(health.name()),
            Instant.now(clock),
            Optional.ofNullable(message),
            Optional.empty(),
            List.of());
        HealthEvent event = new HealthEvent(
            condId, Instant.now(clock), headSource, severity, Optional.of(condId), condition);
        ConditionStore.Transition transition = conditionStore.upsert(event);
        if (transition != ConditionStore.Transition.UNCHANGED) {
          changeRegistry.broadcast(
              transition == ConditionStore.Transition.ADDED
                  ? HealthEventChangeRegistry.Kind.CONDITION_ADDED
                  : HealthEventChangeRegistry.Kind.CONDITION_MODIFIED,
              event);
        }
      }
    } catch (Exception e) {
      log.debug("Failed to push capability condition for {}: {}", condId, e.getMessage());
    }
  }

  /** Best-effort rendering of the monitor's actual recovery decisions. */
  public static void emitRecoveryOccurrence(
      RecoveryOccurrence occurrence,
      OccurrenceLog occurrenceLog,
      HealthEventChangeRegistry changeRegistry,
      Source headSource) {
    Clock clock = Clock.systemUTC();
    try {
      String id = switch (occurrence.kind()) {
        case ATTEMPTED -> "worker.restart-attempted";
        case RECOVERED -> "worker.recovered";
      };
      RecoveryContext ctx = occurrence.context();
      // Tempdoc 627 (N2): attach the forensic context the supervisor computed (which attempt, why).
      // restart-attempted carries the full context; recovered carries which attempt it came back on.
      Map<String, Object> attributes = new LinkedHashMap<>();
      if (ctx != null) {
        if ("worker.restart-attempted".equals(id)) {
          attributes.put("attempt", ctx.attempt());
          attributes.put("faultKind", ctx.faultKind());
          attributes.put("backoffMs", ctx.backoffMs());
        } else {
          attributes.put("recoveredAfterAttempts", ctx.attempt());
        }
      }
      HealthEvent event = new HealthEvent(
          id,
          clock.instant(),
          headSource,
          Severity.INFO,
          Optional.of("health-events." + id + ".message"),
          new LifecycleEvent(attributes, Optional.empty()));
      occurrenceLog.append(event);
      changeRegistry.broadcast(HealthEventChangeRegistry.Kind.OCCURRENCE_APPENDED, event);
    } catch (Exception e) {
      log.debug("Failed to emit recovery occurrence {}: {}", occurrence.kind(), e.getMessage());
    }
  }

  private static String toPascalCase(String upper) {
    if (upper == null || upper.isEmpty()) {
      return upper;
    }
    return upper.charAt(0) + upper.substring(1).toLowerCase(Locale.ROOT);
  }
}
