/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Semantic and declaration-parity authority for {@code governance/config-apply.v1.json}. */
final class ConfigApplyRegisterTest {

  private static final String REGISTER = "governance/config-apply.v1.json";
  private static final ObjectMapper JSON = new ObjectMapper();

  private static final Set<String> COMPONENTS = Set.of("api", "index", "encoders", "generative");
  private static final Set<String> SIMPLE_SCOPES =
      Set.of("hot", "generation-bound", "restart-required");

  // These declarations directly select values rendered by the current IndexFingerprint.Inputs
  // assembly in SsotCommitMetadataSource. Referencing the declaration constants keeps this oracle
  // tied to canonical key identity without adding a forbidden configuration -> adapters-lucene
  // module dependency.
  private static final Set<String> CURRENT_DIRECT_FINGERPRINT_INPUTS =
      keys(
          EnvRegistry.EMBED_BACKEND,
          EnvRegistry.EMBED_ONNX_MODEL_PATH,
          EnvRegistry.SPLADE_MODEL_PATH,
          EnvRegistry.NER_MODEL_PATH,
          EnvRegistry.SPARSE_MODEL,
          EnvRegistry.INDEX_VECTOR_HNSW_M,
          EnvRegistry.INDEX_VECTOR_HNSW_EF_CONSTRUCTION,
          EnvRegistry.INDEX_VECTOR_QUANTIZATION_ENABLED);

  // These controls change persisted output but do not yet participate in generation identity.
  // D1-12 owns their model binding and boot proof; registering their lifecycle does not close it.
  private static final Set<String> OPEN_D1_12_IDENTITY_GAPS =
      keys(
          EnvRegistry.AI_EMBED_ENABLED,
          EnvRegistry.BGE_M3_ENABLED,
          EnvRegistry.BGE_M3_MAX_SEQ_LEN,
          EnvRegistry.BGE_M3_MODEL_PATH,
          EnvRegistry.EMBED_CONTEXT_LENGTH,
          EnvRegistry.EMBED_LATE_CHUNKING_CONTEXT_LENGTH,
          EnvRegistry.EMBED_LATE_CHUNKING_ENABLED,
          EnvRegistry.FIELD_CATALOG,
          EnvRegistry.NER_CONFIDENCE_THRESHOLD,
          EnvRegistry.NER_ENABLED,
          EnvRegistry.NER_MAX_SEQ_LEN,
          EnvRegistry.SPLADE_ACTIVATION,
          EnvRegistry.SPLADE_ENABLED,
          EnvRegistry.SPLADE_MAX_SEQ_LEN);

  private static final Set<String> FINGERPRINT_RESTART_SELECTORS =
      keys(
          EnvRegistry.HOME,
          EnvRegistry.DATA_DIR,
          EnvRegistry.MODELS_DIR,
          EnvRegistry.REPO_ROOT,
          EnvRegistry.SSOT_PATH);

  @Test
  void registerHasClosedSchemaAndExactlyOneSortedRowPerDeclaredKey() throws IOException {
    Map<String, String> rows = validate(loadRegister());

    assertEquals("hot", rows.get(EnvRegistry.QU_ENABLED.configKey()));
    assertEquals("hot", rows.get(EnvRegistry.FILTER_NORM_ENABLED.configKey()));
  }

  @Test
  void generationBoundRowsAreCurrentInputsPlusTheExplicitD1_12GapSet() throws IOException {
    Map<String, String> rows = validate(loadRegister());
    Set<String> generation = keysWithScope(rows, "generation-bound");
    Set<String> expected = new TreeSet<>(CURRENT_DIRECT_FINGERPRINT_INPUTS);
    expected.addAll(OPEN_D1_12_IDENTITY_GAPS);

    assertEquals(8, CURRENT_DIRECT_FINGERPRINT_INPUTS.size());
    assertEquals(14, OPEN_D1_12_IDENTITY_GAPS.size());
    assertTrue(
        CURRENT_DIRECT_FINGERPRINT_INPUTS.stream().noneMatch(OPEN_D1_12_IDENTITY_GAPS::contains),
        "current inputs and known identity gaps must remain disjoint");
    assertEquals(expected, generation);
    FINGERPRINT_RESTART_SELECTORS.forEach(
        key -> assertEquals("restart-required", rows.get(key), key));
  }

  @Test
  void missingUnknownAndDuplicateRowsAreRejected() throws IOException {
    ObjectNode missing = mutableRegister();
    ((ArrayNode) missing.get("entries")).remove(0);
    assertFailureContains(missing, "missing declared keys");

    ObjectNode unknown = mutableRegister();
    ((ArrayNode) unknown.get("entries"))
        .addObject()
        .put("key", "zzzz.unknown")
        .put("applyScope", "hot");
    assertFailureContains(unknown, "unknown keys");

    ObjectNode duplicate = mutableRegister();
    ArrayNode duplicateEntries = (ArrayNode) duplicate.get("entries");
    duplicateEntries.insert(1, duplicateEntries.get(0).deepCopy());
    assertFailureContains(duplicate, "duplicate key");
  }

  @Test
  void closedShapeInvalidComponentAndGenerationMisclassificationAreRejected()
      throws IOException {
    ObjectNode extraField = mutableRegister();
    ((ObjectNode) extraField.get("entries").get(0)).put("owner", "invented");
    assertFailureContains(extraField, "entry fields");

    ObjectNode wrongType = mutableRegister();
    wrongType.put("schemaVersion", "1");
    assertFailureContains(wrongType, "schemaVersion");

    ObjectNode invalidComponent = mutableRegister();
    findEntry(invalidComponent, EnvRegistry.QU_ENABLED.configKey())
        .put("applyScope", "component:unknown");
    assertFailureContains(invalidComponent, "invalid applyScope");

    ObjectNode misclassified = mutableRegister();
    findEntry(misclassified, EnvRegistry.EMBED_BACKEND.configKey()).put("applyScope", "hot");
    assertFailureContains(misclassified, "generation-bound rows");
  }

  private static Map<String, String> validate(JsonNode root) {
    require(root != null && root.isObject(), "register root must be an object");
    require(fields(root).equals(Set.of("schemaVersion", "note", "entries")),
        "register fields must be exactly schemaVersion, note, entries");
    require(root.get("schemaVersion").isInt() && root.get("schemaVersion").asInt() == 1,
        "schemaVersion must be integer 1");
    require(root.get("note").isTextual() && !root.get("note").stringValue().isBlank(),
        "note must be nonblank text");
    require(root.get("entries").isArray(), "entries must be an array");

    Map<String, String> rows = new LinkedHashMap<>();
    String previous = null;
    for (JsonNode entry : root.get("entries")) {
      require(entry.isObject(), "each entry must be an object");
      require(fields(entry).equals(Set.of("key", "applyScope")),
          "entry fields must be exactly key and applyScope");
      JsonNode keyNode = entry.get("key");
      JsonNode scopeNode = entry.get("applyScope");
      require(keyNode.isTextual() && !keyNode.stringValue().isBlank(),
          "entry key must be nonblank text");
      require(scopeNode.isTextual() && !scopeNode.stringValue().isBlank(),
          "entry applyScope must be nonblank text");
      String key = keyNode.stringValue();
      String scope = scopeNode.stringValue();
      require(!rows.containsKey(key), "duplicate key: " + key);
      require(
          previous == null || previous.compareTo(key) < 0,
          "entries must be sorted by key: `" + previous + "` must precede `" + key + "`");
      require(validScope(scope), "invalid applyScope: " + scope);
      rows.put(key, scope);
      previous = key;
    }

    Set<String> declared = declaredKeys();
    Set<String> registered = rows.keySet();
    Set<String> missing = new TreeSet<>(declared);
    missing.removeAll(registered);
    Set<String> unknown = new TreeSet<>(registered);
    unknown.removeAll(declared);
    require(missing.isEmpty(), "missing declared keys: " + missing);
    require(unknown.isEmpty(), "unknown keys: " + unknown);

    Set<String> expectedGeneration = new TreeSet<>(CURRENT_DIRECT_FINGERPRINT_INPUTS);
    expectedGeneration.addAll(OPEN_D1_12_IDENTITY_GAPS);
    require(keysWithScope(rows, "generation-bound").equals(expectedGeneration),
        "generation-bound rows must equal current inputs plus known D1-12 gaps");
    for (String selector : FINGERPRINT_RESTART_SELECTORS) {
      require("restart-required".equals(rows.get(selector)),
          "fingerprint selector must be restart-required: " + selector);
    }
    return Map.copyOf(rows);
  }

  private static boolean validScope(String scope) {
    if (SIMPLE_SCOPES.contains(scope)) return true;
    if (!scope.startsWith("component:")) return false;
    return COMPONENTS.contains(scope.substring("component:".length()));
  }

  private static Set<String> declaredKeys() {
    return Stream.concat(
            Arrays.stream(EnvRegistry.values()).map(EnvRegistry::configKey),
            Arrays.stream(ConfigKey.values()).map(ConfigKey::configKey))
        .collect(Collectors.toUnmodifiableSet());
  }

  private static Set<String> fields(JsonNode node) {
    return node.propertyStream().map(Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());
  }

  private static Set<String> keys(EnvRegistry... declarations) {
    return Arrays.stream(declarations)
        .map(EnvRegistry::configKey)
        .collect(Collectors.toUnmodifiableSet());
  }

  private static Set<String> keysWithScope(Map<String, String> rows, String scope) {
    return rows.entrySet().stream()
        .filter(entry -> scope.equals(entry.getValue()))
        .map(Map.Entry::getKey)
        .collect(Collectors.toCollection(TreeSet::new));
  }

  private static JsonNode loadRegister() throws IOException {
    return JSON.readTree(repoRoot().resolve(REGISTER).toFile());
  }

  private static ObjectNode mutableRegister() throws IOException {
    return (ObjectNode) loadRegister().deepCopy();
  }

  private static ObjectNode findEntry(ObjectNode root, String key) {
    for (JsonNode entry : root.get("entries")) {
      if (key.equals(entry.path("key").stringValue())) return (ObjectNode) entry;
    }
    throw new AssertionError("register entry not found: " + key);
  }

  private static void assertFailureContains(JsonNode root, String expected) {
    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> validate(root));
    assertTrue(failure.getMessage().contains(expected), failure.getMessage());
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalArgumentException(message);
  }

  private static Path repoRoot() {
    Path path = Paths.get("").toAbsolutePath();
    while (path != null) {
      if (Files.isRegularFile(path.resolve("settings.gradle.kts"))
          && Files.isDirectory(path.resolve("modules"))) {
        return path;
      }
      path = path.getParent();
    }
    throw new IllegalStateException("repository root not found from " + Paths.get("").toAbsolutePath());
  }
}
