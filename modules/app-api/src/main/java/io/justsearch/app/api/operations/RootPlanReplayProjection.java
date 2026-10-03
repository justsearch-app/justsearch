/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import io.justsearch.app.api.knowledge.IngestCollectionPolicy;
import io.justsearch.agent.api.registry.OperationPreparation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

/** The safe metadata grammar accepted by a prepared operation, not another mutable root plan. */
final class RootPlanReplayProjection {
  static final String SCHEMA = "root-plan.v1";

  private static final JsonMapper JSON = JsonMapper.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .build();

  private RootPlanReplayProjection() {}

  static void validate(String schema, Map<?, ?> plan) {
    if (!SCHEMA.equals(schema)) {
      throw new IllegalArgumentException("Unsupported safe replay schema");
    }
    Objects.requireNonNull(plan, "plan");
    requireFields(plan, Set.of("generation", "roots"));
    validateGeneration(requireText(plan.get("generation"), 128));
    for (Object item : requireList(plan.get("roots"))) {
      if (!(item instanceof Map<?, ?> root)) {
        throw new IllegalArgumentException("Replay root must be an object");
      }
      requireFields(root, Set.of("path", "collection", "force", "singleFile",
          "excludePatterns", "excludedSubtrees"));
      Path path = requirePath(root.get("path"));
      if (root.get("collection") != null) {
        String collection = requireText(root.get("collection"), RecordedRootPlan.MAX_COLLECTION_LENGTH);
        IngestCollectionPolicy.normalizeRequested(collection);
      }
      if (!(root.get("force") instanceof Boolean)
          || !(root.get("singleFile") instanceof Boolean)) {
        throw new IllegalArgumentException("Replay scan flags must be boolean");
      }
      for (Object pattern : requireList(root.get("excludePatterns"))) {
        requireText(pattern, 4096);
      }
      for (Object subtree : requireList(root.get("excludedSubtrees"))) {
        Path excluded = requirePath(subtree);
        if (excluded.equals(path) || !excluded.startsWith(path)) {
          throw new IllegalArgumentException("Excluded replay subtree must be inside its root");
        }
      }
    }
  }

  /** Validate the complete safe payload using the generic preparation byte ceiling. */
  static void validatePayloadSize(String schema, String payloadJson) {
    new OperationPreparation("{}", schema, payloadJson);
  }

  /** Parse and validate one complete safe replay payload at the persistence boundary. */
  static RecordedRootPlan parsePlan(String schema, String payloadJson) {
    validatePayloadSize(schema, payloadJson);
    final Object payload;
    try {
      payload = JSON.readValue(payloadJson, Object.class);
    } catch (RuntimeException failure) {
      throw new IllegalArgumentException("Malformed replay projection", failure);
    }
    if (!(payload instanceof Map<?, ?> plan)) {
      throw new IllegalArgumentException("Replay projection must be a JSON object");
    }
    return parsePlan(plan);
  }

  static RecordedRootPlan parsePlan(Map<?, ?> plan) {
    validate(SCHEMA, plan);
    List<RecordedRootPlan.Root> roots = new ArrayList<>();
    for (Object item : requireList(plan.get("roots"))) {
      Map<?, ?> root = (Map<?, ?>) item;
      roots.add(new RecordedRootPlan.Root(
          requirePath(root.get("path")),
          root.get("collection") == null
              ? null
              : IngestCollectionPolicy.normalizeRequested(
                  requireText(root.get("collection"), RecordedRootPlan.MAX_COLLECTION_LENGTH)),
          (Boolean) root.get("force"),
          (Boolean) root.get("singleFile"),
          textList(root.get("excludePatterns"), 4096),
          pathList(root.get("excludedSubtrees"))));
    }
    return new RecordedRootPlan(
        validateGeneration(requireText(plan.get("generation"), 128)), roots);
  }

  static String validateGeneration(String generation) {
    if (!generation.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
      throw new IllegalArgumentException("Invalid replay generation identity");
    }
    return generation;
  }

  private static List<String> textList(Object value, int limit) {
    List<String> result = new ArrayList<>();
    for (Object item : requireList(value)) {
      result.add(requireText(item, limit));
    }
    return List.copyOf(result);
  }

  private static List<Path> pathList(Object value) {
    List<Path> result = new ArrayList<>();
    for (Object item : requireList(value)) {
      result.add(requirePath(item));
    }
    return List.copyOf(result);
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
    final Path path;
    try {
      path = Path.of(requireText(value, 32768));
    } catch (RuntimeException failure) {
      throw new IllegalArgumentException("Replay path is invalid", failure);
    }
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
