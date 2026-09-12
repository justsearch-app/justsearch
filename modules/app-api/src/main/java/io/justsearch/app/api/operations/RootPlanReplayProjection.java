/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

/** The safe metadata grammar accepted by the operation row, not another mutable root plan. */
final class RootPlanReplayProjection {
  static final String SCHEMA = "root-plan.v1";
  static final int MAX_PAYLOAD_CHARS = 200000;

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

  /** Parse and validate one complete safe replay payload at the persistence boundary. */
  static Map<?, ?> parsePayload(String schema, String payloadJson) {
    Objects.requireNonNull(payloadJson, "payloadJson");
    if (payloadJson.length() > MAX_PAYLOAD_CHARS) {
      throw new IllegalArgumentException("Replay projection is too large");
    }
    final Object payload;
    try {
      payload = JSON.readValue(payloadJson, Object.class);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("Malformed replay projection", e);
    }
    if (!(payload instanceof Map<?, ?> plan)) {
      throw new IllegalArgumentException("Replay projection must be a JSON object");
    }
    validate(schema, plan);
    return plan;
  }

  static RecordedRootPlan parsePlan(String schema, String payloadJson) {
    return parsePlan(parsePayload(schema, payloadJson));
  }

  /** Decode only the two safe identity shapes that carry an admitted root plan. */
  static RecordedRootPlan parseIdentity(String identityJson) {
    final Object value;
    try {
      value = JSON.readValue(identityJson, Object.class);
    } catch (RuntimeException failure) {
      throw new IllegalArgumentException("Malformed recorded root identity", failure);
    }
    if (!(value instanceof Map<?, ?> identity)) {
      throw new IllegalArgumentException("Recorded root identity must be an object");
    }
    String mode = requireText(identity.get("mode"), 32);
    if (mode.equals("invoke")) {
      requireFields(identity, Set.of("mode", "argumentsSha256", "preparedInvocation"));
      if (!requireText(identity.get("argumentsSha256"), 64).matches("[a-f0-9]{64}")) {
        throw new IllegalArgumentException("Invalid recorded argument digest");
      }
    } else if (mode.equals("ingest-child")) {
      requireFields(identity, Set.of("mode", "parentOperationKey", "preparedInvocation"));
      OperationKeys.timestampMillis(requireText(identity.get("parentOperationKey"), 36));
    } else {
      throw new IllegalArgumentException("Operation identity has no replayable root plan");
    }
    if (!(identity.get("preparedInvocation") instanceof Map<?, ?> prepared)) {
      throw new IllegalArgumentException("Missing recorded root preparation");
    }
    requireFields(prepared, Set.of("schema", "payload"));
    if (!(prepared.get("payload") instanceof Map<?, ?> plan)) {
      throw new IllegalArgumentException("Recorded root plan must be an object");
    }
    if (!SCHEMA.equals(requireText(prepared.get("schema"), 128))) {
      throw new IllegalArgumentException("Unsupported safe replay schema");
    }
    if (JSON.writeValueAsString(plan).length() > MAX_PAYLOAD_CHARS) {
      throw new IllegalArgumentException("Replay projection is too large");
    }
    RecordedRootPlan result = parsePlan(plan);
    if (mode.equals("ingest-child") && result.roots().size() != 1) {
      throw new IllegalArgumentException("An ingest child requires one root");
    }
    return result;
  }

  static RecordedRootPlan parsePlan(Map<?, ?> plan) {
    validate(SCHEMA, plan);
    List<RecordedRootPlan.Root> roots = new ArrayList<>();
    for (Object item : requireList(plan.get("roots"))) {
      Map<?, ?> root = (Map<?, ?>) item;
      roots.add(new RecordedRootPlan.Root(
          requirePath(root.get("path")),
          root.get("collection") == null ? null : requireText(root.get("collection"), 256),
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
    for (Object item : requireList(value)) result.add(requireText(item, limit));
    return List.copyOf(result);
  }

  private static List<Path> pathList(Object value) {
    List<Path> result = new ArrayList<>();
    for (Object item : requireList(value)) result.add(requirePath(item));
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
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("Replay path is invalid", e);
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
