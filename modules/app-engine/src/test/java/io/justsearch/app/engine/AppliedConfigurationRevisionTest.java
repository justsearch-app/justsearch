/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.app.api.knowledge.KnowledgeClientException;
import io.justsearch.app.api.operations.AppliedIndexGeneration;
import io.justsearch.app.api.operations.IndexTargetSnapshot;
import io.justsearch.core.component.ComponentSpec;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.ComposeEvidence;
import io.justsearch.core.component.EngineComponentSnapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class AppliedConfigurationRevisionTest {
  private static final List<String> COMPONENTS =
      List.of("api", "encoders", "generative", "index");

  @Test
  void ignoresOrderingAndObservationMetadataButIncludesEveryAppliedVersion() {
    Map<String, String> applied = appliedVersions();
    AppliedIndexGeneration generation = generation("generation-a", "{\"b\":2,\"a\":1}");
    String expected = AppliedConfigurationRevision.digest(snapshot(7, COMPONENTS, applied, false), generation);

    assertEquals(expected, AppliedConfigurationRevision.digest(
        snapshot(99, COMPONENTS.reversed(), applied, true), generation));

    for (String component : COMPONENTS) {
      var changed = new LinkedHashMap<>(applied);
      changed.put(component, applied.get(component) + "-changed");
      assertNotEquals(expected, AppliedConfigurationRevision.digest(
          snapshot(7, COMPONENTS, changed, false), generation), component);
    }
  }

  @Test
  void generationIdentityAndCanonicalInputValuesParticipateInTheDigest() {
    EngineComponentSnapshot snapshot = snapshot(1, COMPONENTS, appliedVersions(), false);
    String first = AppliedConfigurationRevision.digest(
        snapshot, generation("generation-a", "{\"nested\":{\"b\":2,\"a\":1}}"));

    assertEquals(first, AppliedConfigurationRevision.digest(
        snapshot, generation("generation-a", "{\"nested\":{\"a\":1,\"b\":2}}")));
    assertNotEquals(first, AppliedConfigurationRevision.digest(
        snapshot, generation("generation-b", "{\"nested\":{\"a\":1,\"b\":2}}")));
    assertNotEquals(first, AppliedConfigurationRevision.digest(
        snapshot, generation("generation-a", "{\"nested\":{\"a\":1,\"b\":3}}")));
  }

  @Test
  void refusesMissingDuplicateOrUnestablishedComponentObservations() {
    AppliedIndexGeneration generation = generation("generation-a", "{}");
    var missing = new ArrayList<>(COMPONENTS);
    missing.remove("generative");
    assertUnavailable(() -> AppliedConfigurationRevision.digest(
        snapshot(1, missing, appliedVersions(), false), generation));

    var duplicate = new ArrayList<>(COMPONENTS);
    duplicate.add("api");
    assertUnavailable(() -> AppliedConfigurationRevision.digest(
        snapshot(1, duplicate, appliedVersions(), false), generation));

    for (String component : COMPONENTS) {
      var incomplete = new LinkedHashMap<>(appliedVersions());
      incomplete.put(component, null);
      assertUnavailable(() -> AppliedConfigurationRevision.digest(
          snapshotWithAbsent(component, incomplete), generation));
    }
  }

  @Test
  void refusesMalformedOrNonObjectCommittedInputs() {
    EngineComponentSnapshot snapshot = snapshot(1, COMPONENTS, appliedVersions(), false);
    assertUnavailable(() -> AppliedConfigurationRevision.digest(
        snapshot, generation("generation-a", "{")));
    assertUnavailable(() -> AppliedConfigurationRevision.digest(
        snapshot, generation("generation-a", "[]")));
  }

  private static EngineComponentSnapshot snapshot(
      long revision, List<String> order, Map<String, String> applied, boolean noisy) {
    List<EngineComponentSnapshot.Component> rows = order.stream()
        .map(name -> component(name, applied.get(name), noisy))
        .toList();
    return new EngineComponentSnapshot(revision, rows);
  }

  private static EngineComponentSnapshot snapshotWithAbsent(
      String absentName, Map<String, String> applied) {
    return new EngineComponentSnapshot(1, COMPONENTS.stream()
        .map(name -> name.equals(absentName)
            ? component(name, null, false, ComponentState.ABSENT)
            : component(name, applied.get(name), false))
        .toList());
  }

  private static EngineComponentSnapshot.Component component(
      String name, String appliedVersion, boolean noisy) {
    return component(name, appliedVersion, noisy,
        noisy ? ComponentState.FAILED : ComponentState.READY);
  }

  private static EngineComponentSnapshot.Component component(
      String name, String appliedVersion, boolean noisy, ComponentState state) {
    var spec = new ComponentSpec(name, name.equals("api") || name.equals("index"), Set.of(name + ".key"),
        ComponentSpec.ComposeCapability.CHOOSES_PER_APPLY, Duration.ofSeconds(1), 2);
    return new EngineComponentSnapshot.Component(
        spec,
        state,
        noisy ? name + ".reason" : null,
        noisy ? Instant.parse("2030-01-01T00:00:00Z") : Instant.EPOCH,
        noisy ? 987_654_321L : 1L,
        appliedVersion,
        noisy ? name + "-desired" : null,
        noisy ? new ComposeEvidence(ComposeEvidence.Mode.IN_PLACE, "diagnostic", 10L, 20L) : null,
        noisy ? 2 : 0,
        noisy ? "opaque evidence" : null);
  }

  private static Map<String, String> appliedVersions() {
    var versions = new LinkedHashMap<String, String>();
    for (String component : COMPONENTS) versions.put(component, component + "-applied");
    return versions;
  }

  private static AppliedIndexGeneration generation(String id, String inputs) {
    return new AppliedIndexGeneration(id, new IndexTargetSnapshot(sha256(inputs), inputs));
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static void assertUnavailable(org.junit.jupiter.api.function.Executable executable) {
    KnowledgeClientException failure = assertThrows(KnowledgeClientException.class, executable);
    assertEquals(KnowledgeClientException.Status.UNAVAILABLE, failure.status());
  }
}
