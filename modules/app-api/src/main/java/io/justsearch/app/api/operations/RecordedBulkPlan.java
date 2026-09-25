/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.ConfirmStrategy;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.core.context.EngineContext;
import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.app.api.status.MigrationSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Frozen finite source scope and physical target; per-file capture follows durable acceptance. */
public record RecordedBulkPlan(Profile profile, String source, RecordedRootPlan scope,
    IndexTargetSnapshot target, List<String> projectionSourceIds) implements RecordedGenerationPlan {
  public static final String SCHEMA = "recorded-bulk-reindex-v1";
  public static final String SCHEMA_V2 = "recorded-bulk-reindex-v2";
  public static final int MAX_PROJECTION_SOURCE_IDS = 64;
  public static final int MAX_PROJECTION_SOURCE_ID_LENGTH = 256;
  private static final Set<String> V1_FIELDS = Set.of("profile", "source", "scope", "target");
  private static final Set<String> V2_FIELDS = Set.of(
      "profile", "source", "scope", "target", "projectionSourceIds");
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
    projectionSourceIds = normalizeProjectionSourceIds(projectionSourceIds);
  }

  /** Legacy constructor: a v1 plan has no captured source-owner list. */
  public RecordedBulkPlan(Profile profile, String source, RecordedRootPlan scope,
      IndexTargetSnapshot target) {
    this(profile, source, scope, target, null);
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
    if (projectionSourceIds == null) {
      // Keep this branch byte-for-byte compatible with the released v1 payload shape.
      String payload = JSON.writeValueAsString(Map.of("profile", profile.name(), "source", source,
          "scope", JSON.readValue(scope.toReplayPayload(), Object.class), "target", target));
      new OperationPreparation("{}", SCHEMA, payload);
      return payload;
    }
    String payload = JSON.writeValueAsString(Map.of("profile", profile.name(), "source", source,
        "scope", JSON.readValue(scope.toReplayPayload(), Object.class), "target", target,
        "projectionSourceIds", projectionSourceIds));
    new OperationPreparation("{}", SCHEMA_V2, payload);
    return payload;
  }

  /** Schema carried by this plan's replay payload. */
  public String replaySchema() {
    return projectionSourceIds == null ? SCHEMA : SCHEMA_V2;
  }

  public static boolean isSupportedSchema(String replaySchema) {
    return SCHEMA.equals(replaySchema) || SCHEMA_V2.equals(replaySchema);
  }

  public String planHash() { return CanonicalOperationArguments.digest(toReplayPayload()); }

  /** Closed catalog contract for the only profiles whose one-time approval spans restarts. */
  public static boolean continuationPolicy(Operation operation) {
    if (operation == null) return false;
    try { Profile.fromOperationRef(operation.id().value()); }
    catch (IllegalArgumentException unrelated) { return false; }
    if (!operation.binding().handlerId().equals(operation.id().value())
        || operation.provenance().tier() != io.justsearch.agent.api.registry.TrustTier.CORE
        || !"core".equals(operation.provenance().contributorId())) return false;
    var policy = operation.policy();
    return policy.risk() == RiskTier.HIGH
        && policy.confirm() instanceof ConfirmStrategy.Inline
        && policy.recordKind() == OperationKind.REINDEX
        && policy.declaredSurvival().orElse(null) == EngineContext.Survival.DURABLE;
  }

  /** Validate the frozen profile before minting or revalidating a continuation locator. */
  public static boolean continuationPreparation(Operation operation,
      OperationPreparation preparation) {
    if (!continuationPolicy(operation) || preparation == null
        || preparation.content() != OperationPreparation.Content.METADATA
        || !isSupportedSchema(preparation.replaySchema())) return false;
    try {
      var plan = fromReplayPayload(preparation.replaySchema(), preparation.replayPayloadJson());
      return plan.profile().operationRef().equals(operation.id().value())
          && plan.source().equals(sourceForArguments(plan.profile(), preparation.argumentsJson()));
    } catch (IllegalArgumentException malformed) { return false; }
  }

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
      if (!(args.get("corpusIds") instanceof List<?> ids)
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
    final Object decoded = decode(payload);
    if (!(decoded instanceof Map<?, ?> fields)) {
      throw new IllegalArgumentException("Invalid bulk plan fields");
    }
    if (fields.keySet().equals(V1_FIELDS)) return fromReplayPayload(SCHEMA, payload);
    if (fields.keySet().equals(V2_FIELDS)) return fromReplayPayload(SCHEMA_V2, payload);
    throw new IllegalArgumentException("Invalid bulk plan fields");
  }

  /** Strict decoder for a prepared envelope, including its externally stored schema tag. */
  public static RecordedBulkPlan fromReplayPayload(String replaySchema, String payload) {
    if (!isSupportedSchema(replaySchema)) {
      throw new IllegalArgumentException("Bulk plan schema mismatch");
    }
    new OperationPreparation("{}", replaySchema, payload);
    final Object decoded;
    try { decoded = JSON.readValue(payload, Object.class); }
    catch (RuntimeException malformed) { throw new IllegalArgumentException("Invalid bulk plan JSON"); }
    Set<String> expectedFields = SCHEMA.equals(replaySchema) ? V1_FIELDS : V2_FIELDS;
    if (!(decoded instanceof Map<?, ?> fields)
        || !fields.keySet().equals(expectedFields)
        || !(fields.get("profile") instanceof String profile)
        || !(fields.get("source") instanceof String source)
        || !(fields.get("scope") instanceof Map<?, ?>)
        || !(fields.get("target") instanceof Map<?, ?>)) {
      throw new IllegalArgumentException("Invalid bulk plan fields");
    }
    try {
      return new RecordedBulkPlan(Profile.valueOf(profile), source,
          RecordedRootPlan.fromReplayPayload(JSON.writeValueAsString(fields.get("scope"))),
          JSON.readValue(JSON.writeValueAsString(fields.get("target")), IndexTargetSnapshot.class),
          SCHEMA.equals(replaySchema) ? null : projectionSourceIds(fields.get("projectionSourceIds")));
    } catch (RuntimeException malformed) {
      throw new IllegalArgumentException("Invalid bulk plan binding", malformed);
    }
  }

  private static Object decode(String payload) {
    new OperationPreparation("{}", SCHEMA, payload);
    try { return JSON.readValue(payload, Object.class); }
    catch (RuntimeException malformed) { throw new IllegalArgumentException("Invalid bulk plan JSON"); }
  }

  private static List<String> projectionSourceIds(Object value) {
    if (!(value instanceof List<?> raw)) {
      throw new IllegalArgumentException("projectionSourceIds must be an array");
    }
    List<String> ids = new ArrayList<>(raw.size());
    for (Object item : raw) {
      if (!(item instanceof String id)) {
        throw new IllegalArgumentException("projectionSourceIds must contain strings");
      }
      ids.add(id);
    }
    return ids;
  }

  private static List<String> normalizeProjectionSourceIds(List<String> ids) {
    if (ids == null) return null;
    if (ids.size() > MAX_PROJECTION_SOURCE_IDS) {
      throw new IllegalArgumentException("Too many projection source identities");
    }
    List<String> normalized = new ArrayList<>(ids.size());
    int totalLength = 0;
    for (String id : ids) {
      if (id == null || id.isBlank() || id.length() > MAX_PROJECTION_SOURCE_ID_LENGTH
          || id.chars().anyMatch(Character::isISOControl)) {
        throw new IllegalArgumentException("Projection source identity must be bounded");
      }
      totalLength += id.length();
      normalized.add(id);
    }
    if (totalLength > 4_096) {
      throw new IllegalArgumentException("Projection source identities exceed manifest budget");
    }
    normalized.sort(String::compareTo);
    if (Set.copyOf(normalized).size() != normalized.size()) {
      throw new IllegalArgumentException("Duplicate projection source identity");
    }
    return List.copyOf(normalized);
  }
}
