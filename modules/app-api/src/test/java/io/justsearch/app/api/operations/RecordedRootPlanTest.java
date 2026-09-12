/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

final class RecordedRootPlanTest {
  private static final Path BASE = Path.of("recorded-root-plan").toAbsolutePath().normalize();
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static RecordedRootPlan.Root root(Path path, String collection, boolean singleFile) {
    return new RecordedRootPlan.Root(path, collection, false, singleFile, List.of(), List.of());
  }

  @Test
  void copiesMutableInputsAndExposesImmutableValues() {
    List<String> patterns = new ArrayList<>(List.of("*.tmp"));
    List<Path> subtrees = new ArrayList<>();
    List<RecordedRootPlan.Root> roots = new ArrayList<>(List.of(
        new RecordedRootPlan.Root(BASE, null, false, false, patterns, subtrees)));
    RecordedRootPlan plan = new RecordedRootPlan("g1", roots);
    patterns.add("*.bak");
    subtrees.add(BASE.resolve("ignored"));
    roots.clear();

    assertEquals(List.of("*.tmp"), plan.roots().get(0).excludePatterns());
    assertThrows(UnsupportedOperationException.class, () -> plan.roots().clear());
    assertThrows(UnsupportedOperationException.class,
        () -> plan.roots().get(0).excludePatterns().add("*.bak"));
  }

  @Test
  void rejectsDuplicatePathWithDifferentPolicy() {
    assertThrows(IllegalArgumentException.class, () -> RecordedRootPlan.partition("g1", List.of(
        root(BASE, "a", false), root(BASE, "b", false))));
    assertThrows(IllegalArgumentException.class, () -> RecordedRootPlan.partition("g1", List.of(
        new RecordedRootPlan.Root(BASE, "a", false, false, List.of(), List.of()),
        new RecordedRootPlan.Root(BASE, "a", false, true, List.of(), List.of()))));
    assertThrows(IllegalArgumentException.class, () -> RecordedRootPlan.partition("g1", List.of(
        new RecordedRootPlan.Root(BASE, "a", true, false, List.of(), List.of()),
        new RecordedRootPlan.Root(BASE, "a", false, false, List.of(), List.of()))));
    assertThrows(IllegalArgumentException.class, () -> RecordedRootPlan.partition("g1", List.of(
        new RecordedRootPlan.Root(BASE, "a", false, false, List.of("*.tmp"), List.of()),
        new RecordedRootPlan.Root(BASE, "a", false, false, List.of("*.bak"), List.of()))));
  }

  @Test
  void collapsesExactDuplicatesAndSamePolicyNestedRoots() {
    Path child = BASE.resolve("child");
    RecordedRootPlan plan = RecordedRootPlan.partition("g1", List.of(
        root(child, "a", false), root(BASE, "a", false), root(BASE, "a", false)));

    assertEquals(List.of(BASE), plan.roots().stream().map(RecordedRootPlan.Root::path).toList());
    assertEquals(List.of(), plan.roots().get(0).excludedSubtrees());
  }

  @Test
  void collapsesRedundantSingleFileChildUnderMatchingDirectory() {
    Path child = BASE.resolve("child");
    RecordedRootPlan plan = RecordedRootPlan.partition("g1", List.of(
        root(BASE, "a", false), root(child, "a", true)));

    assertEquals(List.of(BASE), plan.roots().stream().map(RecordedRootPlan.Root::path).toList());
  }

  @Test
  void rejectsMoreThan1024RequestedRootsBeforePartitioning() {
    List<RecordedRootPlan.Root> roots = new ArrayList<>();
    for (int i = 0; i < 1025; i++) {
      roots.add(root(BASE.resolve("root-" + i), "a", false));
    }
    assertThrows(IllegalArgumentException.class, () -> RecordedRootPlan.partition("g1", roots));
  }

  @Test
  void retainsNestedRootsWithDistinctPoliciesAndExcludesImmediateChild() {
    Path child = BASE.resolve("child");
    RecordedRootPlan plan = RecordedRootPlan.partition("g1", List.of(
        root(BASE, "a", false), root(child, "b", false)));

    assertEquals(List.of(BASE, child), plan.roots().stream()
        .map(RecordedRootPlan.Root::path).toList());
    assertEquals(List.of(child), plan.roots().get(0).excludedSubtrees());
  }

  @Test
  void nearestRetainedAncestorPreventsAlternatingPolicyCollapse() {
    Path b = BASE.resolve("b");
    Path c = b.resolve("c");
    RecordedRootPlan plan = RecordedRootPlan.partition("g1", List.of(
        root(BASE, "x", false), root(b, "y", false), root(c, "x", false)));

    assertEquals(List.of(BASE, b, c), plan.roots().stream()
        .map(RecordedRootPlan.Root::path).toList());
    assertEquals(List.of(b), plan.roots().get(0).excludedSubtrees());
    assertEquals(List.of(c), plan.roots().get(1).excludedSubtrees());
  }

  @Test
  void pathPrefixSiblingsRemainIndependentAndSingleFileIsPreserved() {
    Path sibling = BASE.resolveSibling(BASE.getFileName() + "2");
    RecordedRootPlan plan = RecordedRootPlan.partition("g1", List.of(
        root(BASE, "a", true), root(sibling, "a", false)));

    List<Path> paths = plan.roots().stream().map(RecordedRootPlan.Root::path).toList();
    assertEquals(2, paths.size());
    assertTrue(paths.contains(BASE));
    assertTrue(paths.contains(sibling));
    RecordedRootPlan.Root file = plan.roots().stream()
        .filter(RecordedRootPlan.Root::singleFile).findFirst().orElseThrow();
    assertEquals(BASE, file.path());
    assertFalse(BASE.startsWith(sibling));
  }

  @Test
  void serializationIsDeterministicAndRoundTrips() {
    Path child = BASE.resolve("child");
    RecordedRootPlan plan = RecordedRootPlan.partition("g1", List.of(
        root(child, "b", false), root(BASE, "a", false)));
    String first = plan.toReplayPayload();
    String second = RecordedRootPlan.partition("g1", List.of(
        root(BASE, "a", false), root(child, "b", false))).toReplayPayload();

    assertEquals(first, second);
    assertEquals(plan, RecordedRootPlan.fromReplayPayload(first));
  }

  @Test
  void strictReplayBoundaryRejectsUnknownFieldsUnsafePathsAndNonBooleanFlags() {
    String base = new RecordedRootPlan("g1", List.of(root(BASE, null, false))).toReplayPayload();
    assertEquals(new RecordedRootPlan("g1", List.of(root(BASE, null, false))),
        RecordedRootPlan.fromReplayPayload(base));
    assertThrows(IllegalArgumentException.class,
        () -> RecordedRootPlan.fromReplayPayload(base.replace(
            "\"excludedSubtrees\":[]}", "\"excludedSubtrees\":[],\"unknown\":1}")));
    assertThrows(IllegalArgumentException.class,
        () -> RecordedRootPlan.fromReplayPayload(base.replace(
            JSON.writeValueAsString(BASE.toString()), "\"relative\"")));
    assertThrows(IllegalArgumentException.class,
        () -> RecordedRootPlan.fromReplayPayload(base.replace("\"force\":false", "\"force\":1")));
  }

  @Test
  void rejectsDuplicateKeysAndUnnormalizedPathsAtCommonBoundary() {
    String valid = new RecordedRootPlan("g1", List.of(root(BASE, null, false))).toReplayPayload();
    assertThrows(IllegalArgumentException.class,
        () -> RecordedRootPlan.fromReplayPayload(valid.replace(
            "\"generation\":\"g1\"", "\"generation\":\"g1\",\"generation\":\"g1\"")));

    Path unnormalized = BASE.resolve("child").resolve("..");
    String pathJson = JSON.writeValueAsString(BASE.toString());
    String unnormalizedJson = JSON.writeValueAsString(unnormalized.toString());
    assertThrows(IllegalArgumentException.class,
        () -> RecordedRootPlan.fromReplayPayload(valid.replace(pathJson, unnormalizedJson)));
  }

  @Test
  void rejectsTrailingTokensAfterAValidPlan() {
    var plan = new RecordedRootPlan("g1", List.of(root(BASE, null, false)));
    String valid = plan.toReplayPayload();
    assertEquals(plan, RecordedRootPlan.fromReplayPayload(valid));
    for (String tail : List.of("{}", "[]", "null", "true", "1", "garbage")) {
      assertThrows(IllegalArgumentException.class,
          () -> RecordedRootPlan.fromReplayPayload(valid + " " + tail), tail);
    }
  }
}
