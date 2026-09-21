/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.app.api.status.MigrationSource;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Frozen finite source scope and physical target; per-file capture follows durable acceptance. */
public record RecordedBulkPlan(Profile profile, String source, RecordedRootPlan scope,
    IndexTargetSnapshot target) {
  public static final String SCHEMA = "recorded-bulk-reindex-v1";
  private static final JsonMapper JSON = JsonMapper.builder()
      .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
      .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

  public RecordedBulkPlan {
    Objects.requireNonNull(profile, "profile");
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(target, "target");
    MigrationSource normalized = MigrationSource.fromWire(source);
    if (normalized == MigrationSource.UNKNOWN || !normalized.wire().equals(source)) {
      throw new IllegalArgumentException("Bulk migration source must be a normalized known value");
    }
    if (scope.roots().stream().anyMatch(root -> root.singleFile() || !root.force())) {
      throw new IllegalArgumentException("Bulk scope must force complete watched-root traversal");
    }
  }

  public enum Profile {
    USER_BULK("core.bulk-reindex", MigrationSource.USER_REQUESTED_BULK_REINDEX),
    RECOVERY_REBUILD("core.rebuild-index", MigrationSource.USER_REQUESTED_REBUILD);

    private final String operationRef;
    private final MigrationSource source;
    Profile(String operationRef, MigrationSource source) {
      this.operationRef = operationRef;
      this.source = source;
    }
    public String operationRef() { return operationRef; }
    public String defaultSource() { return source.wire(); }
    public static Profile fromOperationRef(String operationRef) {
      for (Profile profile : values()) if (profile.operationRef.equals(operationRef)) return profile;
      throw new IllegalArgumentException("Unsupported recorded bulk producer");
    }
  }

  public String toReplayPayload() {
    String payload = JSON.writeValueAsString(Map.of("profile", profile.name(), "source", source,
        "scope", JSON.readValue(scope.toReplayPayload(), Object.class), "target", target));
    new OperationPreparation("{}", SCHEMA, payload);
    return payload;
  }

  public String planHash() { return CanonicalOperationArguments.digest(toReplayPayload()); }

  /** Public arguments retain legacy corpus labels; this operation rebuilds all frozen watched roots. */
  public static String sourceForArguments(Profile profile, String argumentsJson) {
    final Object decoded;
    try { decoded = JSON.readValue(argumentsJson, Object.class); }
    catch (RuntimeException malformed) { throw new IllegalArgumentException("Invalid bulk arguments JSON", malformed); }
    if (!(decoded instanceof Map<?, ?> args)
        || !Set.of("source", "corpusIds").containsAll(args.keySet())
        || profile == Profile.RECOVERY_REBUILD && args.containsKey("corpusIds")) {
      throw new IllegalArgumentException("Invalid bulk operation arguments");
    }
    if (profile == Profile.USER_BULK) {
      if (!(args.get("corpusIds") instanceof java.util.List<?> ids)
          || ids.stream().anyMatch(id -> !(id instanceof String label) || label.isBlank()
              || label.length() > RecordedRootPlan.MAX_COLLECTION_LENGTH
              || label.chars().anyMatch(Character::isISOControl))) {
        throw new IllegalArgumentException("Bulk corpus labels must be bounded nonblank strings");
      }
    }
    if (!args.containsKey("source")) return profile.defaultSource();
    if (!(args.get("source") instanceof String source)
        || MigrationSource.fromWire(source) == MigrationSource.UNKNOWN
        || !MigrationSource.fromWire(source).wire().equals(source)) {
      throw new IllegalArgumentException("Bulk source must be a normalized known value");
    }
    return source;
  }

  public static RecordedBulkPlan fromReplayPayload(String payload) {
    new OperationPreparation("{}", SCHEMA, payload);
    final Object decoded;
    try { decoded = JSON.readValue(payload, Object.class); }
    catch (RuntimeException malformed) { throw new IllegalArgumentException("Invalid bulk plan JSON"); }
    if (!(decoded instanceof Map<?, ?> fields)
        || !fields.keySet().equals(Set.of("profile", "source", "scope", "target"))
        || !(fields.get("profile") instanceof String profile)
        || !(fields.get("source") instanceof String source)
        || !(fields.get("scope") instanceof Map<?, ?>)
        || !(fields.get("target") instanceof Map<?, ?>)) {
      throw new IllegalArgumentException("Invalid bulk plan fields");
    }
    try {
      return new RecordedBulkPlan(Profile.valueOf(profile), source,
          RecordedRootPlan.fromReplayPayload(JSON.writeValueAsString(fields.get("scope"))),
          JSON.readValue(JSON.writeValueAsString(fields.get("target")), IndexTargetSnapshot.class));
    } catch (RuntimeException malformed) {
      throw new IllegalArgumentException("Invalid bulk plan binding", malformed);
    }
  }
}
