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
import io.justsearch.app.services.observability.health.LifecycleSnapshotTap;
import io.justsearch.core.component.EngineComponentRegistry;
import io.justsearch.core.component.EngineComponentSnapshot;
import io.justsearch.app.services.worker.RecoveryContext;
import io.justsearch.app.services.worker.RecoveryOccurrence;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
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
  public static Set<String> emittableIds() {
    return Set.of("worker.restart-attempted", "worker.recovered");
  }

  /** Owns both subscriptions, including ordered current-state replay. */
  public static AutoCloseable wireListeners(
      EngineComponentRegistry components,
      ConditionStore conditionStore,
      HealthEventChangeRegistry changeRegistry,
      Source headSource) {
    return wireListeners(components, conditionStore, changeRegistry, headSource,
        CapabilityHealthBridge::chatFailureCause);
  }

  static AutoCloseable wireListeners(
      EngineComponentRegistry components,
      ConditionStore conditionStore,
      HealthEventChangeRegistry changeRegistry,
      Source headSource,
      Function<String, String> inferenceCause) {
    Clock clock = Clock.systemUTC();
    var inference = new RegistryBackedCapability(components, "generative", "inference");
    var boundary = components.publicationBoundary().orElseGet(ReentrantReadWriteLock::new);
    conditionStore.bindPublicationBoundary(boundary);
    // Only the bounded production state mapping participates in atomic publication. Optional
    // classification/refinement happens outside it and must validate its inputs before writing.
    var projection = components.subscribePublication(snapshot -> projectSnapshot(snapshot,
        conditionStore, changeRegistry, headSource, clock));
    try {
      Runnable refine = () -> reconcile(inference, "inference.capability", "inference", true,
          inferenceCause, Severity.INFO, conditionStore, changeRegistry, headSource, clock, boundary);
      var refinement = components.subscribe(snapshot -> refine.run());
      try {
        refine.run();
        return () -> {
          try { refinement.close(); }
          finally { projection.close(); }
        };
      } catch (RuntimeException | Error failure) {
        refinement.close();
        throw failure;
      }
    } catch (RuntimeException | Error failure) {
      projection.close();
      throw failure;
    }
  }

  private static void projectSnapshot(EngineComponentSnapshot snapshot, ConditionStore store,
      HealthEventChangeRegistry changes, Source source, Clock clock) {
    for (var component : snapshot.components()) {
      String name = component.spec().name();
      if (!name.equals("index") && !name.equals("generative")) continue;
      boolean inference = name.equals("generative");
      CapabilityHealth health = RegistryBackedCapability.healthOf(component.state());
      boolean clear = health == CapabilityHealth.READY || inference && health == CapabilityHealth.PENDING;
      pushCondition(inference ? "inference.capability" : "worker.capability",
          inference ? "inference" : "worker", clear ? CapabilityHealth.READY : health,
          component.reasonCode(), component.evidence(),
          inference && !clear ? chatFailureCause(component.reasonCode()) : null,
          inference ? Severity.INFO : Severity.WARNING, store, changes, source, clock);
    }
  }

  /** A notification is a prompt to project the authority, never a failure/recovery command. */
  private static void reconcile(
      RegistryBackedCapability capability, String condId, String subject, boolean quietPending,
      Function<String, String> causeMapper, Severity severity, ConditionStore conditionStore,
      HealthEventChangeRegistry changeRegistry, Source headSource, Clock clock,
      ReentrantReadWriteLock boundary) {
    try {
      publishCurrent(capability, condId, subject, quietPending, causeMapper, severity,
          conditionStore, changeRegistry, headSource, clock, boundary);
    } catch (Exception failure) {
      log.debug("Failed to map capability condition for {}: {}", condId, failure.getMessage());
      // Re-read after a failed mapping/publication: recovery may have happened while it ran.
      // Classification is optional; a broken classifier must not preserve an older failure.
      try {
        publishCurrent(capability, condId, subject, quietPending, reason -> null, severity,
            conditionStore, changeRegistry, headSource, clock, boundary);
      } catch (Exception retryFailure) {
        log.debug("Failed to reconcile capability condition for {}: {}",
            condId, retryFailure.getMessage());
      }
    }
  }

  private static void publishCurrent(
      RegistryBackedCapability capability, String condId, String subject,
      boolean quietPending, Function<String, String> causeMapper, Severity severity,
      ConditionStore conditionStore, HealthEventChangeRegistry changeRegistry,
      Source headSource, Clock clock, ReentrantReadWriteLock boundary) {
    var observation = capability.current();
    boolean clear = observation.health() == CapabilityHealth.READY
        || quietPending && observation.health() == CapabilityHealth.PENDING;
    String cause = clear ? null : causeMapper.apply(observation.reason());
    boundary.writeLock().lock();
    try {
      var current = capability.current();
      if (observation.health() != current.health()
          || !java.util.Objects.equals(observation.reason(), current.reason())
          || !java.util.Objects.equals(observation.detail(), current.detail())) return;
      pushCondition(condId, subject, clear ? CapabilityHealth.READY : observation.health(),
          observation.reason(), observation.detail(), cause, severity,
          conditionStore, changeRegistry, headSource, clock);
    } finally {
      boundary.writeLock().unlock();
    }
  }

  /**
   * Tempdoc 837 §0.2: the Condition {@code message} is one of the two places a HUMAN sentence
   * legitimately belongs (the other is the 503 debug body), so it prefers the capability's
   * {@code pendingDetail()} — "Health check failed after 4200ms", the corrupt-index remedy paragraph
   * — and falls back to the reason CODE only when there is no sentence. Before the prose→code sweep
   * this field carried the sentence by accident, because the sentence WAS the reason; passing the
   * code alone would have silently degraded every Health event to a bare token.
   *
   * <p>Spec A step 7 (F1): a classified chat failure ({@code cause}, see {@link #chatFailureCause})
   * is the Condition's reason instead, with no message, so Health words it from the catalog's
   * per-reason message ({@code health-events.inference.capability.reason.<Cause>.message}) rather
   * than showing the evidence string ("chat failed: model_load_failed"). The evidence stays on the
   * component's status.
   */
  private static void pushCondition(
      String condId,
      String subject,
      CapabilityHealth health,
      String reason,
      String detail,
      String cause,
      Severity severity,
      ConditionStore conditionStore,
      HealthEventChangeRegistry changeRegistry,
      Source headSource,
      Clock clock) {
    if (health == CapabilityHealth.READY) {
      conditionStore
          .clear(condId, subject)
          .ifPresent(removed ->
              changeRegistry.broadcastAsync(HealthEventChangeRegistry.Kind.CONDITION_REMOVED, removed));
    } else {
      String message =
          cause != null ? null : detail != null && !detail.isBlank() ? detail : reason;
      AssertedCondition condition = new AssertedCondition(
          subject,
          ConditionStatus.TRUE,
          cause != null ? cause : toPascalCase(health.name()),
          Instant.now(clock),
          Optional.ofNullable(message),
          Optional.empty(),
          List.of());
      HealthEvent event = new HealthEvent(
          condId, Instant.now(clock), headSource, severity, Optional.of(condId), condition);
      ConditionStore.Transition transition = conditionStore.upsert(event);
      if (transition != ConditionStore.Transition.UNCHANGED) {
        changeRegistry.broadcastAsync(
            transition == ConditionStore.Transition.ADDED
                ? HealthEventChangeRegistry.Kind.CONDITION_ADDED
                : HealthEventChangeRegistry.Kind.CONDITION_MODIFIED,
            event);
      }
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

  /**
   * The chat failures the chat controller classified (Spec A §7.1) and {@code inference.crashed}
   * for the rest: the same codes LifecycleSnapshotTap maps to {@code ai.not-ready}.
   */
  private static final Set<String> CHAT_FAILURE_REASON_CODES = Set.of(
      "inference.out_of_memory",
      "inference.startup_timeout",
      "inference.start_failed",
      "inference.model_load_failed",
      "inference.crashed");

  /**
   * The Condition reason for a classified chat failure ({@code InferenceModelLoadFailed}), the same
   * form {@code ai.not-ready} carries; null for any other reason code.
   */
  static String chatFailureCause(String reasonCode) {
    return reasonCode != null && CHAT_FAILURE_REASON_CODES.contains(reasonCode)
        ? LifecycleSnapshotTap.toPascalReason(reasonCode)
        : null;
  }

  private static String toPascalCase(String upper) {
    if (upper == null || upper.isEmpty()) {
      return upper;
    }
    return upper.charAt(0) + upper.substring(1).toLowerCase(Locale.ROOT);
  }
}
