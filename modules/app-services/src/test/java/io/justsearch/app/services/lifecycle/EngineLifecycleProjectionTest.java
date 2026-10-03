/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.app.api.lifecycle.LifecycleSnapshotV2;
import io.justsearch.contract.wire.LifecycleState;
import io.justsearch.core.component.ComponentSpec;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.ComposeEvidence;
import io.justsearch.core.component.EngineComponentSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class EngineLifecycleProjectionTest {
  private static final List<ComponentState> STATES = List.of(ComponentState.ABSENT,
      ComponentState.STARTING, ComponentState.READY, ComponentState.RELOADING,
      ComponentState.FAILED, ComponentState.UNAVAILABLE);
  // Independent essential-pair truth table: rows api, columns index, in STATES order.
  // D=degraded, S=starting, R=ready, E=error. Optional components matter only in R.
  private static final List<String> ESSENTIAL_TABLE = List.of(
      "DSDEED", "SSSEES", "DSREED", "EEEEEE", "EEEEEE", "DSDEED");
  private static final Set<String> OPTIONAL_READY = Set.of(
      "ABSENT/ABSENT", "ABSENT/READY", "READY/ABSENT", "READY/READY");
  private static final Instant OBSERVED_AT = Instant.parse("2026-09-21T15:00:00Z");

  @Test
  void all1296CombinationsPreserveComponentVectorAndUseTheOneAggregatePolicy() {
    int checked = 0;
    for (int api = 0; api < STATES.size(); api++) {
      for (int index = 0; index < STATES.size(); index++) {
        for (var encoders : STATES) {
          for (var generative : STATES) {
            var snapshot = new EngineComponentSnapshot(19, List.of(
                component("generative", generative), component("index", STATES.get(index)),
                component("api", STATES.get(api)), component("encoders", encoders)));
            var projection = LifecycleProjection.project(snapshot, OBSERVED_AT);
            var expected = expected(api, index, encoders, generative);
            String context = List.of(STATES.get(api), STATES.get(index), encoders, generative).toString();
            assertEquals(expected, projection.lifecycle().lifecycle().state(), context);
            assertEquals(expected, LifecycleProjection.derive(snapshot), context);
            assertEquals(2, projection.lifecycle().schema_version());
            assertEquals(OBSERVED_AT.toString(), projection.lifecycle().observed_at());
            var slots = projection.lifecycle().components();
            var byName = Map.of("api", slots.api(), "index", slots.index(),
                "encoders", slots.encoders(), "generative", slots.generative());
            for (var component : snapshot.components()) {
              var view = projection.engineComponents().get(component.spec().name());
              assertEquals(component.state(), view.state());
              assertEquals(component.reasonCode(), view.reasonCode());
              assertEquals(component.stateSince().toString(), view.stateSince());
              assertEquals(component.appliedVersion(), view.appliedVersion());
              assertEquals(component.desiredVersion(), view.desiredVersion());
              assertEquals(component.lastCompose().mode(), view.mode());
              assertEquals(component.lastCompose().reason(), view.reason());
              assertEquals(component.lastCompose().freeBytes(), view.freeBytes());
              assertEquals(component.lastCompose().footprintBytes(), view.footprintBytes());
              assertEquals(component.spec().startDeadline().toMillis(), view.deadlineMs());
              assertEquals(component.recoveryAttempts(), view.recoveryAttempts());
              assertEquals(component.evidence(), view.evidence());
              assertEquals(new LifecycleSnapshotV2.Component(view.state(), view.reasonCode(),
                  view.stateSince()), byName.get(component.spec().name()));
            }
            checked++;
          }
        }
      }
    }
    assertEquals(1296, checked);
  }

  @Test
  void projectsEvidenceAndChoosesTheDiagnosticCauseIndependentlyOfRegistrationOrder() {
    var api = component("api", ComponentState.FAILED);
    var index = component("index", ComponentState.FAILED);
    var encoders = component("encoders", ComponentState.UNAVAILABLE);
    var generative = component("generative", ComponentState.ABSENT);
    var first = LifecycleProjection.project(
        new EngineComponentSnapshot(11, List.of(index, generative, api, encoders)), OBSERVED_AT);
    var reversed = LifecycleProjection.project(
        new EngineComponentSnapshot(11, List.of(encoders, api, generative, index)), OBSERVED_AT);
    assertEquals(first, reversed);
    assertEquals("api.reason", first.lifecycle().lifecycle().reason_code());
    assertEquals("api evidence", first.lifecycle().lifecycle().message());
    var view = first.engineComponents().get("index");
    assertEquals("applied-index", view.appliedVersion());
    assertEquals("desired-index", view.desiredVersion());
    assertEquals(ComposeEvidence.Mode.BESIDE, view.mode());
    assertEquals("capacity available", view.reason());
    assertEquals(100L, view.freeBytes());
    assertEquals(20L, view.footprintBytes());
    assertEquals(12_000, view.deadlineMs());
    assertEquals(1, view.recoveryAttempts());
    assertEquals("index evidence", view.evidence());
    assertThrows(UnsupportedOperationException.class,
        () -> first.engineComponents().put("other", view));
  }

  @Test
  void missingRegistrationIsNotInterpretedAsIntentionalOptionalAbsence() {
    var names = List.of("api", "index", "encoders", "generative");
    for (var missing : names) {
      var incomplete = new EngineComponentSnapshot(1, names.stream()
          .filter(name -> !name.equals(missing))
          .map(name -> component(name, ComponentState.READY)).toList());
      assertThrows(IllegalStateException.class, () -> LifecycleProjection.derive(incomplete), missing);
      assertThrows(IllegalStateException.class,
          () -> LifecycleProjection.project(incomplete, OBSERVED_AT), missing);
    }
  }

  @Test
  void diagnosticCauseFollowsWinningRowBeforeNameOrderAndOptionalAbsenceHasNoCause() {
    record Case(String api, String index, String encoders, String generative, String cause) {}
    var cases = List.of(
        new Case("STARTING", "FAILED", "FAILED", "FAILED", "index"),
        new Case("UNAVAILABLE", "STARTING", "READY", "ABSENT", "index"),
        new Case("READY", "UNAVAILABLE", "FAILED", "RELOADING", "index"),
        new Case("READY", "READY", "ABSENT", "FAILED", "generative"),
        new Case("READY", "READY", "STARTING", "FAILED", "encoders"),
        new Case("READY", "READY", "ABSENT", "ABSENT", null));
    for (var row : cases) {
      var snapshot = new EngineComponentSnapshot(4, List.of(
          component("api", ComponentState.valueOf(row.api())),
          component("index", ComponentState.valueOf(row.index())),
          component("encoders", ComponentState.valueOf(row.encoders())),
          component("generative", ComponentState.valueOf(row.generative()))));
      var lifecycle = LifecycleProjection.project(snapshot, OBSERVED_AT).lifecycle().lifecycle();
      assertEquals(row.cause() == null ? null : row.cause() + ".reason",
          lifecycle.reason_code(), row.toString());
      assertEquals(row.cause() == null ? null : row.cause() + " evidence",
          lifecycle.message(), row.toString());
    }
  }

  private static LifecycleState expected(int api, int index,
      ComponentState encoders, ComponentState generative) {
    char essential = ESSENTIAL_TABLE.get(api).charAt(index);
    return switch (essential) {
      case 'E' -> LifecycleState.LIFECYCLE_STATE_ERROR;
      case 'S' -> LifecycleState.LIFECYCLE_STATE_STARTING;
      case 'D' -> LifecycleState.LIFECYCLE_STATE_DEGRADED;
      case 'R' -> OPTIONAL_READY.contains(encoders.name() + "/" + generative.name())
          ? LifecycleState.LIFECYCLE_STATE_READY : LifecycleState.LIFECYCLE_STATE_DEGRADED;
      default -> throw new AssertionError("invalid expected table entry");
    };
  }

  private static EngineComponentSnapshot.Component component(String name, ComponentState state) {
    int identity = List.of("api", "index", "encoders", "generative").indexOf(name) + 1;
    var spec = new ComponentSpec(name, name.equals("api") || name.equals("index"), Set.of(),
        ComponentSpec.ComposeCapability.BESIDE, Duration.ofSeconds(identity * 6L), 2);
    return new EngineComponentSnapshot.Component(spec, state, name + ".reason",
        OBSERVED_AT.minusSeconds(identity), 91 + identity, "applied-" + name, "desired-" + name,
        new ComposeEvidence(identity % 2 == 0 ? ComposeEvidence.Mode.BESIDE : ComposeEvidence.Mode.IN_PLACE,
            "capacity available", 100L, 20L),
        identity - 1, name + " evidence");
  }
}
