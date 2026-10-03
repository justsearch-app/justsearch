/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.health;

import io.justsearch.agent.api.registry.OperationInvocation;
import io.justsearch.agent.api.registry.OperationRef;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure builder that materializes a {@link ConditionRecoveryIndex} snapshot from a
 * {@link ConditionStore}.
 *
 * <p>Per slice 447-impl-D + 447-followup/2.2 + §X.11.5 follow-up Phase 4: walks the
 * ConditionStore's current snapshot, filters to bodies whose {@code recovery} is
 * non-empty, and groups by the recovery's target OperationRef. Output is stable:
 * targets sorted lexicographically by id, condition refs within each entry sorted by
 * (conditionId, subject). Each ConditionRef carries severity (from
 * {@link HealthEvent#severity()}) + since (from the body's lastTransitionTime).
 *
 * <p>Bodies contributing to the index:
 *
 * <ul>
 *   <li>{@link AssertedCondition} — steady-state condition (k8s-shaped). Primary contributor.
 *   <li>{@link ThresholdState} — magnitude + dwell-time event (Prometheus-shaped). Per
 *       447-followup/2.2: inverse-discovery for "what fix is available for THIS firing
 *       threshold" makes sense alongside AssertedCondition; both have steady-state
 *       lifecycle in {@link ConditionStore}.
 * </ul>
 *
 * <p>{@link LifecycleEvent} bodies (fire-and-forget; appended to {@code OccurrenceLog}
 * rather than {@code ConditionStore}) are excluded by design — inverse-discovery for
 * "what fix is available" doesn't apply to events that are already-completed. The
 * fire-and-forget shape doesn't surface in {@code ConditionStore.currentSnapshot()} either,
 * so the exclusion is enforced by the data path, not by an explicit type check.
 */
public final class ConditionRecoveryIndexBuilder {

  private ConditionRecoveryIndexBuilder() {}

  public static ConditionRecoveryIndex build(ConditionStore store) {
    Objects.requireNonNull(store, "store");
    Map<OperationRef, List<ConditionRecoveryEntry.ConditionRef>> grouped = new LinkedHashMap<>();
    for (HealthEvent event : store.currentSnapshot()) {
      Optional<OperationInvocation> recovery = recoveryOf(event);
      if (recovery.isEmpty()) {
        continue;
      }
      String subject = subjectOf(event);
      Instant since = lastTransitionTimeOf(event);
      if (subject == null || since == null) {
        continue;
      }
      grouped
          .computeIfAbsent(recovery.get().target(), k -> new ArrayList<>())
          .add(
              new ConditionRecoveryEntry.ConditionRef(
                  event.id(), subject, event.severity(), since, recovery.get().defaultArgsJson()));
    }
    List<ConditionRecoveryEntry> entries = new ArrayList<>();
    grouped.entrySet().stream()
        .sorted(Map.Entry.comparingByKey((a, b) -> a.value().compareTo(b.value())))
        .forEach(
            e -> {
              List<ConditionRecoveryEntry.ConditionRef> sorted = new ArrayList<>(e.getValue());
              sorted.sort(
                  java.util.Comparator.comparing(ConditionRecoveryEntry.ConditionRef::conditionId)
                      .thenComparing(ConditionRecoveryEntry.ConditionRef::subject));
              entries.add(new ConditionRecoveryEntry(e.getKey(), List.copyOf(sorted)));
            });
    return new ConditionRecoveryIndex(List.copyOf(entries), store.currentVersion());
  }

  private static Optional<OperationInvocation> recoveryOf(HealthEvent event) {
    return switch (event.body()) {
      case AssertedCondition cond -> cond.recovery();
      case ThresholdState ts -> ts.recovery();
      default -> Optional.empty();
    };
  }

  private static String subjectOf(HealthEvent event) {
    return switch (event.body()) {
      case AssertedCondition cond -> cond.subject();
      case ThresholdState ts -> ts.subject();
      default -> null;
    };
  }

  private static Instant lastTransitionTimeOf(HealthEvent event) {
    return switch (event.body()) {
      case AssertedCondition cond -> cond.lastTransitionTime();
      case ThresholdState ts -> ts.lastTransitionTime();
      default -> null;
    };
  }
}
