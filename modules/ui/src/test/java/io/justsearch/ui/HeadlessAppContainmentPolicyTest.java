/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.containment.EngineContainment.Status;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Spec F §1.3: an Engine that could not join its job keeps booting (with a WARN), except in a run
 * that must prove containment, which refuses before taking the instance lock.
 */
final class HeadlessAppContainmentPolicyTest {
  private static final Optional<String> WHY = Optional.of("AssignProcessToJobObject:5");

  @Test
  void unavailableContainmentRefusesBootOnlyWhereContainmentIsRequired() {
    assertFalse(HeadlessApp.containmentRefusesBoot(Status.UNAVAILABLE, WHY, Map.<String, String>of()::get),
        "a user's Engine boots uncontained");
    assertTrue(HeadlessApp.containmentRefusesBoot(Status.UNAVAILABLE, WHY,
        Map.of("JUSTSEARCH_SUPERVISOR_HARNESS", "1")::get), "the supervisor harness must be contained");
    assertTrue(HeadlessApp.containmentRefusesBoot(Status.UNAVAILABLE, WHY,
        Map.of("JUSTSEARCH_REQUIRE_CONTAINMENT", "1")::get), "an explicit requirement refuses");
    assertFalse(HeadlessApp.containmentRefusesBoot(Status.UNAVAILABLE, WHY,
        Map.of("JUSTSEARCH_REQUIRE_CONTAINMENT", "0")::get), "only 1 requires it");
  }

  @Test
  void containedOrInapplicableNeverRefuses() {
    var required = Map.of("JUSTSEARCH_SUPERVISOR_HARNESS", "1",
        "JUSTSEARCH_REQUIRE_CONTAINMENT", "1");
    assertFalse(HeadlessApp.containmentRefusesBoot(Status.CONTAINED, Optional.empty(), required::get));
    assertFalse(HeadlessApp.containmentRefusesBoot(Status.NOT_APPLICABLE, Optional.empty(),
        required::get), "off Windows there is no job to require");
  }
}
