/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.health;

import io.justsearch.agent.api.registry.OperationRef;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One row of the {@code core.condition-recovery-index} derived inverse Resource: an
 * Operation that's referenced as a recovery, plus the AssertedCondition entries that
 * reference it.
 *
 * <p>Per slice 447 §X.3.4 + 447-impl-D: the metadata-not-edge framing keeps recovery
 * declarations on the Condition body (where they're authored). This record is the inverse
 * view — given an Operation id, list the Conditions that point at it. Discoverability
 * without graph-edge substrate.
 *
 * <p>Per §X.11.5 follow-up Phase 4: each {@link ConditionRef} now carries
 * {@code severity} + {@code since} fields populated from the source HealthEvent. The
 * fields enable consumers (Health surface, agent retrospection) to rank or filter
 * recovery candidates without re-fetching the source ConditionStore.
 */
public record ConditionRecoveryEntry(OperationRef target, List<ConditionRef> conditions) {

  public ConditionRecoveryEntry {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(conditions, "conditions");
    conditions = List.copyOf(conditions);
  }

  /**
   * Identifies a single Condition that references the {@link #target} Operation, plus
   * just-enough metadata for consumer ranking/display.
   *
   * @param conditionId the HealthEvent id (e.g., {@code "schema.reindex-required"})
   * @param subject the AssertedCondition subject (e.g., {@code "worker.schema"})
   * @param severity HealthEvent severity at the moment of index build
   * @param defaultArgsJson exact argument object from the source recovery invocation; arguments
   *     belong to each condition because conditions sharing a target can require different inputs
   * @param since the AssertedCondition's lastTransitionTime — when the condition first
   *     entered its current status. Stable across reason/message-only updates per the
   *     k8s SetStatusCondition convention.
   */
  public record ConditionRef(
      String conditionId, String subject, Severity severity, Instant since, String defaultArgsJson) {
    public ConditionRef {
      Objects.requireNonNull(conditionId, "conditionId");
      Objects.requireNonNull(subject, "subject");
      Objects.requireNonNull(severity, "severity");
      Objects.requireNonNull(since, "since");
      Objects.requireNonNull(defaultArgsJson, "defaultArgsJson");
    }
  }
}
