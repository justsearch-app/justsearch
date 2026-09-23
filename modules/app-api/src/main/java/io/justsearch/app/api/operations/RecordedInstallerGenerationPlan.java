/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import io.justsearch.agent.api.registry.ConfirmStrategy;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.core.context.EngineContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Frozen installer-produced generation activation candidate.
 *
 * <p>This is deliberately a new v2 preparation type. {@link RecordedBulkPlan} remains the v1
 * contract for ordinary bulk and recovery rebuilds. The candidate contains metadata only: settings
 * are detached JSON and model/asset entries contain identities, never live runtime objects,
 * credentials, or executable configuration.
 */
public record RecordedInstallerGenerationPlan(
    String operationId,
    Profile profile,
    String source,
    String operationKey,
    String sourceGeneration,
    RecordedRootPlan scope,
    IndexTargetSnapshot target,
    SettingsWitness settingsWitness,
    CandidateSettings candidateSettings,
    List<ModelIdentity> models,
    List<AssetIdentity> assets,
    AcquisitionProvenance acquisition) implements RecordedGenerationPlan {

  public static final String SCHEMA = "recorded-installer-generation-v2";
  public static final String OPERATION_ID = "core.activate-installed-models";
  public static final String SOURCE = "installer_model_activation";
  public static final int MAX_CANDIDATE_SETTINGS_BYTES = 131_072;
  public static final int MAX_MODELS = 128;
  public static final int MAX_ASSETS = 1024;

  private static final Set<String> FIELDS = Set.of(
      "operationId", "profile", "source", "operationKey", "sourceGeneration", "scope", "target",
      "settingsWitness", "candidateSettings", "models", "assets", "acquisition");
  private static final Set<String> CANDIDATE_FIELDS = Set.of("sha256", "canonicalJson");
  private static final Set<String> WITNESS_FIELDS = Set.of("acceptedRevision", "lastCommittedOperationKey");
  private static final Set<String> MODEL_FIELDS = Set.of(
      "packageId", "variantId", "path", "sha256", "sizeBytes", "provenance");
  private static final Set<String> ASSET_FIELDS = Set.of(
      "assetId", "path", "sha256", "sizeBytes", "provenance");
  private static final Set<String> PROVENANCE_FIELDS = Set.of("kind", "sourceId", "manifestSha256");
  private static final Pattern IDENTITY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final JsonMapper JSON = JsonMapper.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
      .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
      .build();

  public RecordedInstallerGenerationPlan {
    if (!OPERATION_ID.equals(operationId)) {
      throw new IllegalArgumentException("Installer generation operation must be " + OPERATION_ID);
    }
    Objects.requireNonNull(profile, "profile");
    if (profile != Profile.INSTALLER_GENERATION) {
      throw new IllegalArgumentException("Installer generation profile is required");
    }
    if (!SOURCE.equals(source)) {
      throw new IllegalArgumentException("Installer generation source must be " + SOURCE);
    }
    if (operationKey != null) {
      OperationKeys.timestampMillis(operationKey);
    }
    Objects.requireNonNull(sourceGeneration, "sourceGeneration");
    Objects.requireNonNull(scope, "scope");
    if (!sourceGeneration.equals(scope.generation())) {
      throw new IllegalArgumentException("Source generation must match the frozen root scope");
    }
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(settingsWitness, "settingsWitness");
    Objects.requireNonNull(candidateSettings, "candidateSettings");
    models = immutableSorted(models, MAX_MODELS, "models");
    assets = immutableSorted(assets, MAX_ASSETS, "assets");
    if (models.isEmpty()) {
      throw new IllegalArgumentException("Installer activation requires a model identity");
    }
    if (assets.isEmpty()) {
      throw new IllegalArgumentException("Installer activation requires an asset identity");
    }
    Objects.requireNonNull(acquisition, "acquisition");
  }

  /** Convenience constructor for the only supported installer activation operation. */
  public RecordedInstallerGenerationPlan(
      String operationKey,
      String sourceGeneration,
      RecordedRootPlan scope,
      IndexTargetSnapshot target,
      SettingsWitness settingsWitness,
      CandidateSettings candidateSettings,
      List<ModelIdentity> models,
      List<AssetIdentity> assets,
      AcquisitionProvenance acquisition) {
    this(OPERATION_ID, Profile.INSTALLER_GENERATION, SOURCE, operationKey, sourceGeneration, scope,
        target, settingsWitness, candidateSettings, models, assets, acquisition);
  }

  /**
   * Creates the transient handler preparation before the attempt runner assigns its stable key.
   * The executor must bind the key before freezing or displaying this preparation.
   */
  public RecordedInstallerGenerationPlan(
      String sourceGeneration,
      RecordedRootPlan scope,
      IndexTargetSnapshot target,
      SettingsWitness settingsWitness,
      CandidateSettings candidateSettings,
      List<ModelIdentity> models,
      List<AssetIdentity> assets,
      AcquisitionProvenance acquisition) {
    this(OPERATION_ID, Profile.INSTALLER_GENERATION, SOURCE, null, sourceGeneration, scope,
        target, settingsWitness, candidateSettings, models, assets, acquisition);
  }

  public enum Profile {
    INSTALLER_GENERATION
  }

  /** Candidate settings in canonical, detached JSON form. */
  public record CandidateSettings(String sha256, String canonicalJson) {
    public CandidateSettings {
      Objects.requireNonNull(canonicalJson, "canonicalJson");
      byte[] bytes = canonicalJson.getBytes(StandardCharsets.UTF_8);
      if (bytes.length == 0 || bytes.length > MAX_CANDIDATE_SETTINGS_BYTES) {
        throw new IllegalArgumentException("Candidate settings exceed the byte limit");
      }
      if (sha256 == null || !SHA256.matcher(sha256).matches() || !sha256.equals(sha256Hex(bytes))) {
        throw new IllegalArgumentException("Candidate settings SHA-256 does not match canonical JSON");
      }
      Object decoded;
      try {
        decoded = JSON.readValue(canonicalJson, Object.class);
      } catch (RuntimeException malformed) {
        throw new IllegalArgumentException("Candidate settings must be JSON", malformed);
      }
      if (!(decoded instanceof Map<?, ?>)) {
        throw new IllegalArgumentException("Candidate settings must be a complete JSON object");
      }
      String canonical = canonicalize(decoded);
      if (!canonical.equals(canonicalJson)) {
        throw new IllegalArgumentException("Candidate settings must use canonical JSON");
      }
      rejectRuntimeOrCredentialKeys(decoded);
    }

    public static CandidateSettings fromJson(String json) {
      Objects.requireNonNull(json, "json");
      final Object decoded;
      try {
        decoded = JSON.readValue(json, Object.class);
      } catch (RuntimeException malformed) {
        throw new IllegalArgumentException("Candidate settings must be JSON", malformed);
      }
      String canonical = canonicalize(decoded);
      return new CandidateSettings(sha256Hex(canonical.getBytes(StandardCharsets.UTF_8)), canonical);
    }
  }

  /** Typed identity of one selected model variant. */
  public record ModelIdentity(
      String packageId,
      String variantId,
      Path path,
      String sha256,
      long sizeBytes,
      AcquisitionProvenance provenance) {
    public ModelIdentity {
      packageId = identity(packageId, "packageId");
      variantId = identity(variantId, "variantId");
      path = normalizedAbsolutePath(path, "model path");
      validateDigestAndSize(sha256, sizeBytes, "model");
      Objects.requireNonNull(provenance, "provenance");
    }
  }

  /** Typed identity of one staged or installed asset owned by this activation. */
  public record AssetIdentity(
      String assetId,
      Path path,
      String sha256,
      long sizeBytes,
      AcquisitionProvenance provenance) {
    public AssetIdentity {
      assetId = identity(assetId, "assetId");
      path = normalizedAbsolutePath(path, "asset path");
      validateDigestAndSize(sha256, sizeBytes, "asset");
      Objects.requireNonNull(provenance, "provenance");
    }
  }

  /** Typed source and manifest provenance supplied by the acquisition front. */
  public record AcquisitionProvenance(Kind kind, String sourceId, String manifestSha256) {
    public AcquisitionProvenance {
      Objects.requireNonNull(kind, "kind");
      sourceId = identity(sourceId, "sourceId");
      validateDigestAndSize(manifestSha256, 0, "manifest");
    }

    public enum Kind {
      REGISTRY,
      BUNDLED,
      BOOTSTRAP,
      IMPORTED_PACK
    }

  }

  /** True only for the exact core operation and its closed policy contract. */
  public static boolean continuationPolicy(Operation operation) {
    if (operation == null || !OPERATION_ID.equals(operation.id().value())
        || !operation.binding().handlerId().equals(OPERATION_ID)
        || operation.provenance().tier() != io.justsearch.agent.api.registry.TrustTier.CORE
        || !"core".equals(operation.provenance().contributorId())) return false;
    var policy = operation.policy();
    return policy.risk() == RiskTier.HIGH
        && policy.confirm() instanceof ConfirmStrategy.Inline
        && policy.recordKind() == OperationKind.REINDEX
        && policy.declaredSurvival().orElse(null) == EngineContext.Survival.DURABLE;
  }

  /** Validates a stored preparation against the exact operation, schema and frozen candidate. */
  public static boolean continuationPreparation(Operation operation,
      OperationPreparation preparation) {
    if (!continuationPolicy(operation) || preparation == null
        || preparation.content() != OperationPreparation.Content.METADATA
        || !SCHEMA.equals(preparation.replaySchema())) return false;
    try {
      RecordedInstallerGenerationPlan plan = fromReplayPayload(preparation.replayPayloadJson());
      return plan.operationId().equals(operation.id().value())
          && plan.profile() == Profile.INSTALLER_GENERATION
          && SOURCE.equals(plan.source())
          && plan.operationKey() != null;
    } catch (IllegalArgumentException malformed) {
      return false;
    }
  }

  /** Validates that the candidate is bound to the exact accepted runner identity. */
  public static boolean continuationPreparation(Operation operation,
      OperationPreparation preparation, String operationKey) {
    if (!continuationPreparation(operation, preparation)) return false;
    try {
      OperationKeys.timestampMillis(operationKey);
      return operationKey.equals(fromReplayPayload(preparation.replayPayloadJson()).operationKey());
    } catch (IllegalArgumentException malformed) {
      return false;
    }
  }

  /**
   * Binds a transient v2 handler preparation to the attempt runner's stable key. Preparations for
   * every other schema pass through unchanged. A pre-bound v2 preparation is accepted only when it
   * already names the same key, so a handler cannot substitute its own durable identity.
   */
  public static OperationPreparation bindOperationKey(Operation operation,
      OperationPreparation preparation, String operationKey) {
    Objects.requireNonNull(preparation, "preparation");
    if (!SCHEMA.equals(preparation.replaySchema())) return preparation;
    if (!continuationPolicy(operation)
        || preparation.content() != OperationPreparation.Content.METADATA) {
      throw new IllegalArgumentException("Installer generation preparation is not authorized");
    }
    RecordedInstallerGenerationPlan plan = fromReplayPayload(preparation.replayPayloadJson());
    OperationKeys.timestampMillis(operationKey);
    if (plan.operationKey() != null && !plan.operationKey().equals(operationKey)) {
      throw new IllegalArgumentException("Installer generation preparation key mismatch");
    }
    RecordedInstallerGenerationPlan bound = plan.operationKey() == null
        ? plan.withOperationKey(operationKey) : plan;
    return new OperationPreparation(preparation.argumentsJson(), SCHEMA, bound.toReplayPayload(),
        OperationPreparation.Content.METADATA);
  }

  private RecordedInstallerGenerationPlan withOperationKey(String operationKey) {
    return new RecordedInstallerGenerationPlan(operationId, profile, source, operationKey,
        sourceGeneration, scope, target, settingsWitness, candidateSettings, models, assets,
        acquisition);
  }

  public String toReplayPayload() {
    Map<String, Object> encoded = new LinkedHashMap<>();
    encoded.put("operationId", operationId);
    encoded.put("profile", profile.name());
    encoded.put("source", source);
    encoded.put("operationKey", operationKey);
    encoded.put("sourceGeneration", sourceGeneration);
    encoded.put("scope", readObject(scope.toReplayPayload()));
    encoded.put("target", target);
    Map<String, Object> encodedWitness = new LinkedHashMap<>();
    encodedWitness.put("acceptedRevision", settingsWitness.acceptedRevision());
    encodedWitness.put("lastCommittedOperationKey", settingsWitness.lastCommittedOperationKey());
    encoded.put("settingsWitness", encodedWitness);
    encoded.put("candidateSettings", candidateSettings);
    encoded.put("models", models.stream().map(RecordedInstallerGenerationPlan::modelMap).toList());
    encoded.put("assets", assets.stream().map(RecordedInstallerGenerationPlan::assetMap).toList());
    encoded.put("acquisition", provenanceMap(acquisition));
    String payload = JSON.writeValueAsString(encoded);
    new OperationPreparation("{}", SCHEMA, payload);
    return payload;
  }

  public String planHash() {
    return CanonicalOperationArguments.digest(toReplayPayload());
  }

  public static RecordedInstallerGenerationPlan fromReplayPayload(String payload) {
    return fromReplayPayload(SCHEMA, payload);
  }

  /** Strict decoder for a prepared envelope, including its externally stored schema tag. */
  public static RecordedInstallerGenerationPlan fromReplayPayload(String replaySchema, String payload) {
    if (!SCHEMA.equals(replaySchema)) {
      throw new IllegalArgumentException("Installer generation schema mismatch");
    }
    if (payload == null || payload.isBlank()) {
      throw new IllegalArgumentException("Installer generation payload is required");
    }
    new OperationPreparation("{}", SCHEMA, payload);
    final Object decoded;
    try {
      decoded = JSON.readValue(payload, Object.class);
    } catch (RuntimeException malformed) {
      throw new IllegalArgumentException("Invalid installer generation plan JSON", malformed);
    }
    if (!(decoded instanceof Map<?, ?> fields) || !fields.keySet().equals(FIELDS)) {
      throw new IllegalArgumentException("Installer generation plan fields do not match v2 schema");
    }
    try {
      return new RecordedInstallerGenerationPlan(
          string(fields, "operationId"),
          Profile.valueOf(string(fields, "profile")),
          string(fields, "source"),
          nullableString(fields, "operationKey"),
          string(fields, "sourceGeneration"),
          RecordedRootPlan.fromReplayPayload(JSON.writeValueAsString(fields.get("scope"))),
          JSON.readValue(JSON.writeValueAsString(fields.get("target")), IndexTargetSnapshot.class),
          witness(fields.get("settingsWitness")),
          candidate(fields.get("candidateSettings")),
          models(fields.get("models")),
          assets(fields.get("assets")),
          provenance(fields.get("acquisition")));
    } catch (RuntimeException malformed) {
      throw new IllegalArgumentException("Invalid installer generation plan binding", malformed);
    }
  }

  private static List<ModelIdentity> models(Object value) {
    return list(value, MODEL_FIELDS, "models", RecordedInstallerGenerationPlan::model);
  }

  private static List<AssetIdentity> assets(Object value) {
    return list(value, ASSET_FIELDS, "assets", RecordedInstallerGenerationPlan::asset);
  }

  private interface Decoder<T> { T decode(Map<?, ?> fields); }

  private static <T> List<T> list(Object value, Set<String> expected, String label, Decoder<T> decoder) {
    if (!(value instanceof List<?> values)) throw new IllegalArgumentException(label + " must be an array");
    List<T> result = new ArrayList<>();
    for (Object item : values) {
      if (!(item instanceof Map<?, ?> fields) || !fields.keySet().equals(expected)) {
        throw new IllegalArgumentException("Invalid " + label + " identity fields");
      }
      result.add(decoder.decode(fields));
    }
    return result;
  }

  private static ModelIdentity model(Map<?, ?> fields) {
    return new ModelIdentity(string(fields, "packageId"), string(fields, "variantId"),
        path(fields, "path"), string(fields, "sha256"), longValue(fields, "sizeBytes"),
        provenance(fields.get("provenance")));
  }

  private static AssetIdentity asset(Map<?, ?> fields) {
    return new AssetIdentity(string(fields, "assetId"), path(fields, "path"),
        string(fields, "sha256"), longValue(fields, "sizeBytes"), provenance(fields.get("provenance")));
  }

  private static CandidateSettings candidate(Object value) {
    if (!(value instanceof Map<?, ?> fields) || !fields.keySet().equals(CANDIDATE_FIELDS)) {
      throw new IllegalArgumentException("Invalid candidate settings fields");
    }
    return new CandidateSettings(string(fields, "sha256"), string(fields, "canonicalJson"));
  }

  private static SettingsWitness witness(Object value) {
    if (!(value instanceof Map<?, ?> fields) || !fields.keySet().equals(WITNESS_FIELDS)
        || !(fields.get("acceptedRevision") instanceof Number revision)
        || !(revision instanceof Long || revision instanceof Integer)
        || !(fields.get("lastCommittedOperationKey") == null
            || fields.get("lastCommittedOperationKey") instanceof String)) {
      throw new IllegalArgumentException("Invalid settings witness fields");
    }
    return new SettingsWitness(revision.longValue(), (String) fields.get("lastCommittedOperationKey"));
  }

  private static AcquisitionProvenance provenance(Object value) {
    if (!(value instanceof Map<?, ?> fields) || !fields.keySet().equals(PROVENANCE_FIELDS)) {
      throw new IllegalArgumentException("Invalid acquisition provenance fields");
    }
    return new AcquisitionProvenance(
        AcquisitionProvenance.Kind.valueOf(string(fields, "kind")),
        string(fields, "sourceId"), string(fields, "manifestSha256"));
  }

  private static Map<String, Object> modelMap(ModelIdentity value) {
    return Map.of("packageId", value.packageId(), "variantId", value.variantId(),
        "path", value.path().toString(), "sha256", value.sha256(), "sizeBytes", value.sizeBytes(),
        "provenance", provenanceMap(value.provenance()));
  }

  private static Map<String, Object> assetMap(AssetIdentity value) {
    return Map.of("assetId", value.assetId(), "path", value.path().toString(),
        "sha256", value.sha256(), "sizeBytes", value.sizeBytes(),
        "provenance", provenanceMap(value.provenance()));
  }

  private static Map<String, Object> provenanceMap(AcquisitionProvenance value) {
    return Map.of("kind", value.kind().name(), "sourceId", value.sourceId(),
        "manifestSha256", value.manifestSha256());
  }

  private static Object readObject(String payload) {
    try { return JSON.readValue(payload, Object.class); }
    catch (RuntimeException malformed) { throw new IllegalArgumentException("Invalid nested replay object", malformed); }
  }

  private static String string(Map<?, ?> fields, String key) {
    Object value = fields.get(key);
    if (!(value instanceof String text)) throw new IllegalArgumentException("Field " + key + " must be a string");
    return text;
  }

  private static String nullableString(Map<?, ?> fields, String key) {
    Object value = fields.get(key);
    if (value == null) return null;
    if (!(value instanceof String text)) {
      throw new IllegalArgumentException("Field " + key + " must be a string or null");
    }
    return text;
  }

  private static long longValue(Map<?, ?> fields, String key) {
    Object value = fields.get(key);
    if (!(value instanceof Number number) || !(number instanceof Long || number instanceof Integer)) {
      throw new IllegalArgumentException("Field " + key + " must be an integer");
    }
    return number.longValue();
  }

  private static Path path(Map<?, ?> fields, String key) {
    String text = string(fields, key);
    try { return normalizedAbsolutePath(Path.of(text), key); }
    catch (RuntimeException malformed) { throw new IllegalArgumentException("Invalid " + key, malformed); }
  }

  private static String identity(String value, String label) {
    if (value == null || !IDENTITY.matcher(value).matches()
        || value.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("Invalid " + label);
    }
    return value;
  }

  private static Path normalizedAbsolutePath(Path value, String label) {
    Objects.requireNonNull(value, label);
    if (!value.isAbsolute()) {
      throw new IllegalArgumentException("Normalized " + label + " must be absolute");
    }
    Path absolute = value.toAbsolutePath();
    Path normalized = absolute.normalize();
    if (!absolute.equals(normalized) || normalized.toString().isBlank()
        || normalized.toString().length() > 32_768
        || normalized.toString().chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("Invalid normalized " + label);
    }
    return normalized;
  }

  private static void validateDigestAndSize(String digest, long size, String label) {
    if (digest == null || !SHA256.matcher(digest).matches() || size < 0) {
      throw new IllegalArgumentException("Invalid " + label + " identity");
    }
  }

  private static <T> List<T> immutableSorted(List<T> values, int max, String label) {
    Objects.requireNonNull(values, label);
    if (values.size() > max) throw new IllegalArgumentException(label + " exceed the bound");
    List<T> copy = new ArrayList<>(values);
    for (T value : copy) Objects.requireNonNull(value, label + " entry");
    if ("models".equals(label)) {
      @SuppressWarnings("unchecked") List<ModelIdentity> typed = (List<ModelIdentity>) (List<?>) copy;
      typed.sort(Comparator.comparing(ModelIdentity::packageId).thenComparing(ModelIdentity::variantId));
      Set<String> ids = typed.stream().map(ModelIdentity::packageId).collect(java.util.stream.Collectors.toSet());
      if (ids.size() != typed.size()) throw new IllegalArgumentException("Duplicate model package identity");
    } else {
      @SuppressWarnings("unchecked") List<AssetIdentity> typed = (List<AssetIdentity>) (List<?>) copy;
      typed.sort(Comparator.comparing(AssetIdentity::assetId));
      Set<String> ids = typed.stream().map(AssetIdentity::assetId).collect(java.util.stream.Collectors.toSet());
      if (ids.size() != typed.size()) throw new IllegalArgumentException("Duplicate asset identity");
    }
    return List.copyOf(copy);
  }

  private static String canonicalize(Object value) {
    try { return JSON.writeValueAsString(value); }
    catch (RuntimeException malformed) { throw new IllegalArgumentException("Invalid candidate settings", malformed); }
  }

  private static void rejectRuntimeOrCredentialKeys(Object value) {
    if (value instanceof Map<?, ?> fields) {
      for (Object key : fields.keySet()) {
        if (!(key instanceof String name)) throw new IllegalArgumentException("Settings keys must be strings");
        String normalized = name.toLowerCase(Locale.ROOT).replace('-', '_');
        if (normalized.equals("runtime") || normalized.equals("runtime_config")
            || normalized.equals("runtimeconfig")
            || normalized.equals("credentials") || normalized.equals("credential")
            || normalized.equals("password") || normalized.equals("secret")
            || normalized.equals("token") || normalized.equals("access_token")
            || normalized.equals("api_key") || normalized.equals("authorization")
            || normalized.equals("environment") || normalized.equals("system_properties")) {
          throw new IllegalArgumentException("Candidate settings contain runtime or credential data");
        }
        rejectRuntimeOrCredentialKeys(fields.get(key));
      }
    } else if (value instanceof List<?> values) {
      values.forEach(RecordedInstallerGenerationPlan::rejectRuntimeOrCredentialKeys);
    }
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }
}
