/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The safe metadata grammar accepted by the operation row, not another mutable root plan. */
final class RootPlanReplayProjection {
  private RootPlanReplayProjection() {}

  static void validate(String schema, Map<?, ?> plan) {
    if (!"root-plan.v1".equals(schema)) {
      throw new IllegalArgumentException("Unsupported safe replay schema");
    }
    requireFields(plan, Set.of("generation", "roots"));
    String generation = requireText(plan.get("generation"), 128);
    if (!generation.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
      throw new IllegalArgumentException("Invalid replay generation identity");
    }
    for (Object item : requireList(plan.get("roots"))) {
      if (!(item instanceof Map<?, ?> root)) {
        throw new IllegalArgumentException("Replay root must be an object");
      }
      requireFields(root, Set.of("path", "collection", "force", "singleFile",
          "excludePatterns", "excludedSubtrees"));
      Path path = requirePath(root.get("path"));
      if (root.get("collection") != null) requireText(root.get("collection"), 256);
      if (!(root.get("force") instanceof Boolean) || !(root.get("singleFile") instanceof Boolean)) {
        throw new IllegalArgumentException("Replay scan flags must be boolean");
      }
      for (Object pattern : requireList(root.get("excludePatterns"))) requireText(pattern, 4096);
      for (Object subtree : requireList(root.get("excludedSubtrees"))) {
        Path excluded = requirePath(subtree);
        if (excluded.equals(path) || !excluded.startsWith(path)) {
          throw new IllegalArgumentException("Excluded replay subtree must be inside its root");
        }
      }
    }
  }

  private static void requireFields(Map<?, ?> value, Set<String> fields) {
    if (!value.keySet().equals(fields)) {
      throw new IllegalArgumentException("Replay projection has missing or unsupported fields");
    }
  }

  private static List<?> requireList(Object value) {
    if (!(value instanceof List<?> list) || list.size() > 1024) {
      throw new IllegalArgumentException("Replay list must contain at most 1024 entries");
    }
    return list;
  }

  private static Path requirePath(Object value) {
    Path path = Path.of(requireText(value, 32768));
    if (!path.isAbsolute() || !path.normalize().equals(path)) {
      throw new IllegalArgumentException("Replay path must be absolute and normalized");
    }
    return path;
  }

  private static String requireText(Object value, int limit) {
    if (!(value instanceof String text) || text.isBlank() || text.length() > limit) {
      throw new IllegalArgumentException("Replay value must be bounded nonblank text");
    }
    return text;
  }
}
