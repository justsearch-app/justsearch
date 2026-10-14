/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.justsearch.agent.api.registry.OperationInvocation;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.app.observability.health.AssertedCondition;
import io.justsearch.app.observability.health.ConditionStatus;
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.HealthEvent;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.Severity;
import io.justsearch.app.observability.health.Source;
import io.justsearch.configuration.EnvRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Eval-mode-only debug endpoints for synthesizing AssertedCondition state. Tools the
 * Tier-3 verification harness (Playwright specs in {@code modules/ui-web/e2e/}) uses to
 * drive condition-recovery-index updates without a real workload-induced trip.
 *
 * <p>Per slice 447-followup-tier3-tooling §A: this is the synthetic-state primitive
 * §X.12.11 named as the missing tooling. Construction is intentionally minimal — JSON
 * body shape mirrors the {@link AssertedCondition} record's wire fields plus an
 * optional recovery target. The handler:
 *
 * <ol>
 *   <li>Validates the JSON shape and {@code conditionId} is non-blank.
 *   <li>Constructs a {@link HealthEvent} carrying an {@link AssertedCondition} body.
 *   <li>Calls {@link ConditionStore#upsert(HealthEvent)} — same path the production
 *       taps ({@code LifecycleSnapshotTap}, {@code WorkerSnapshotTap},
 *       {@code RuleEmitter}) follow.
 *   <li>Broadcasts via {@link HealthEventChangeRegistry#broadcast} based on the
 *       returned {@code Transition} — same callsite pattern the production taps use
 *       (per {@code LifecycleSnapshotTap.java:404-411}).
 * </ol>
 *
 * <p>{@code POST /api/debug/trip-condition} body:
 *
 * <pre>{@code
 * {
 *   "conditionId": "schema.reindex-required",
 *   "subject":     "worker.schema",
 *   "severity":    "WARNING",          // INFO | WARNING | ERROR
 *   "reason":      "TestSyntheticTrip",
 *   "message":     "synthesized for E2E", // optional
 *   "recovery": {                          // optional
 *     "target":          "core.reindex",
 *     "defaultArgsJson": "{\"force\":true}"
 *   }
 * }
 * }</pre>
 *
 * <p>{@code POST /api/debug/clear-condition} body:
 *
 * <pre>{@code
 * { "conditionId": "schema.reindex-required", "subject": "worker.schema" }
 * }</pre>
 *
 * <p>Both endpoints are gated on the registered {@link EnvRegistry#EVAL_MODE} setting;
 * production builds return 404 (mirroring {@code /api/debug/reset-index} at
 * {@code LocalApiServer.handleResetIndex}).
 */
public final class DebugConditionController {

  private final ConditionStore conditionStore;
  private final HealthEventChangeRegistry changes;
  private final Source source;
  private final Clock clock;

  public DebugConditionController(
      ConditionStore conditionStore,
      HealthEventChangeRegistry changes,
      Source source,
      Clock clock) {
    this.conditionStore = Objects.requireNonNull(conditionStore, "conditionStore");
    this.changes = Objects.requireNonNull(changes, "changes");
    this.source = Objects.requireNonNull(source, "source");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Eval-mode-gated trip handler. */
  public void handleTrip(Context ctx) {
    if (!EnvRegistry.EVAL_MODE.getBoolean(false)) {
      ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "Not available outside eval mode"));
      return;
    }
    Map<String, Object> body;
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> parsed = ctx.bodyAsClass(Map.class);
      body = parsed;
    } catch (Exception e) {
      ctx.status(HttpStatus.BAD_REQUEST)
          .json(Map.of("error", "JSON body required: " + e.getMessage()));
      return;
    }

    String conditionId = stringField(body, "conditionId");
    String subject = stringField(body, "subject");
    String severityName = stringField(body, "severity");
    String reason = body.containsKey("reason") ? stringField(body, "reason") : "TestSyntheticTrip";
    Optional<String> message =
        body.containsKey("message")
            ? Optional.of(stringField(body, "message"))
            : Optional.empty();

    if (conditionId == null || conditionId.isBlank()) {
      ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "conditionId required"));
      return;
    }
    if (subject == null || subject.isBlank()) {
      ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "subject required"));
      return;
    }
    Severity severity;
    try {
      severity = Severity.valueOf(severityName == null ? "WARNING" : severityName.toUpperCase());
    } catch (IllegalArgumentException e) {
      ctx.status(HttpStatus.BAD_REQUEST)
          .json(Map.of("error", "severity must be INFO | WARNING | ERROR"));
      return;
    }

    Optional<OperationInvocation> recovery = Optional.empty();
    if (body.get("recovery") instanceof Map<?, ?> recBody) {
      Object targetObj = recBody.get("target");
      if (!(targetObj instanceof String target) || target.isBlank()) {
        ctx.status(HttpStatus.BAD_REQUEST)
            .json(Map.of("error", "recovery.target required when recovery present"));
        return;
      }
      Object argsObj = recBody.get("defaultArgsJson");
      String argsJson = argsObj instanceof String s ? s : "{}";
      try {
        recovery = Optional.of(new OperationInvocation(new OperationRef(target), argsJson));
      } catch (RuntimeException e) {
        ctx.status(HttpStatus.BAD_REQUEST)
            .json(Map.of("error", "invalid recovery.target or defaultArgsJson: " + e.getMessage()));
        return;
      }
    }

    AssertedCondition condition;
    HealthEvent event;
    try {
      condition =
          new AssertedCondition(
              subject,
              ConditionStatus.TRUE,
              reason,
              clock.instant(),
              message,
              recovery,
              List.of());
      event =
          new HealthEvent(
              conditionId,
              clock.instant(),
              source,
              severity,
              Optional.of("health-events." + conditionId + ".message"),
              condition);
    } catch (RuntimeException e) {
      ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", e.getMessage()));
      return;
    }

    ConditionStore.Transition transition = conditionStore.upsert(event);
    switch (transition) {
      case ADDED -> changes.broadcast(HealthEventChangeRegistry.Kind.CONDITION_ADDED, event);
      case MODIFIED -> changes.broadcast(HealthEventChangeRegistry.Kind.CONDITION_MODIFIED, event);
      case UNCHANGED -> {
        // No broadcast; ConditionStore preserved the prior record.
      }
    }
    ctx.status(HttpStatus.OK)
        .json(
            Map.of(
                "tripped", true,
                "conditionId", conditionId,
                "subject", subject,
                "transition", transition.name()));
  }

  /** Eval-mode-gated clear handler. Removes a synthetic condition by (id, subject). */
  public void handleClear(Context ctx) {
    if (!EnvRegistry.EVAL_MODE.getBoolean(false)) {
      ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "Not available outside eval mode"));
      return;
    }
    Map<String, Object> body;
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> parsed = ctx.bodyAsClass(Map.class);
      body = parsed;
    } catch (Exception e) {
      ctx.status(HttpStatus.BAD_REQUEST)
          .json(Map.of("error", "JSON body required: " + e.getMessage()));
      return;
    }
    String conditionId = stringField(body, "conditionId");
    String subject = stringField(body, "subject");
    if (conditionId == null || conditionId.isBlank()) {
      ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "conditionId required"));
      return;
    }
    if (subject == null || subject.isBlank()) {
      ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "subject required"));
      return;
    }
    Optional<HealthEvent> removed = conditionStore.clear(conditionId, subject);
    if (removed.isPresent()) {
      changes.broadcast(HealthEventChangeRegistry.Kind.CONDITION_REMOVED, removed.get());
      ctx.status(HttpStatus.OK).json(Map.of("cleared", true, "conditionId", conditionId));
    } else {
      ctx.status(HttpStatus.OK)
          .json(
              Map.of(
                  "cleared", false,
                  "conditionId", conditionId,
                  "note", "no condition existed at (id, subject)"));
    }
  }

  private static String stringField(Map<String, Object> body, String key) {
    Object val = body.get(key);
    return val instanceof String s ? s : null;
  }
}
