package io.justsearch.app.observability.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.victools.jsonschema.generator.CustomDefinition;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfig;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaKeyword;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jackson.JacksonOption;
import io.justsearch.agent.api.registry.NamespacedId;
import io.justsearch.app.api.operations.OperationOutcomeView;
import io.justsearch.app.api.settings.CompositionV2;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Schema generation for the {@link OperationHistoryEntry} wire-format type.
 *
 * <p>Per slice 444b: capture-or-verify pattern matching {@code HealthEventSchemaTest} and
 * {@code RuntimeContextSchemaTest}. The baseline at
 * {@code SSOT/schemas/operation-history-entry.v1.json} pins the wire payload shape advertised
 * by {@link OperationHistoryResourceCatalog#SCHEMA_URL}.
 */
@SuppressWarnings("removal")
@DisplayName("OperationHistoryEntry schema generation")
final class OperationHistorySchemaTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static SchemaGenerator schemaGenerator;
  private static Path schemasDir;

  @BeforeAll
  static void setupSchemaGenerator() {
    JacksonModule jacksonModule =
        new JacksonModule(
            JacksonOption.RESPECT_JSONPROPERTY_ORDER,
            JacksonOption.RESPECT_JSONPROPERTY_REQUIRED,
            JacksonOption.FLATTENED_ENUMS_FROM_JSONVALUE);
    SchemaGeneratorConfigBuilder configBuilder =
        new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
            .with(jacksonModule);
    // Per slice 444b §B.C: OperationRef is a single-field record with @JsonValue String value
    // — it serializes as a bare string at runtime. Override to emit {type: "string"} with
    // the namespace pattern, mirroring SubstrateSchemaGenTest.
    configBuilder
        .forTypesInGeneral()
        .withCustomDefinitionProvider(
            (javaType, context) -> {
              if (NamespacedId.class.isAssignableFrom(javaType.getErasedType())) {
                ObjectNode node = context.getGeneratorConfig().createObjectNode();
                node.put(
                    SchemaKeyword.TAG_TYPE.forVersion(SchemaVersion.DRAFT_2020_12), "string");
                node.put(
                    SchemaKeyword.TAG_PATTERN.forVersion(SchemaVersion.DRAFT_2020_12),
                    "^(core|vendor\\.[a-z][a-z0-9-]*)\\.[a-z][a-z0-9-]*$");
                return new CustomDefinition(node);
              }
              return null;
            });
    // CompositionV2 writes explicit nulls. Mirror WireSchemaConfig's nullable references
    // for this nested record, preserving the existing history generator's other projections.
    configBuilder.forFields().withNullableCheck(field ->
        field.getDeclaringType().getErasedType() == CompositionV2.class
            ? !field.getType().getErasedType().isPrimitive() : null);
    SchemaGeneratorConfig config = configBuilder.build();
    schemaGenerator = new SchemaGenerator(config);
    Path cursor = Path.of("").toAbsolutePath();
    while (cursor != null && !Files.isDirectory(cursor.resolve("SSOT/schemas"))) {
      cursor = cursor.getParent();
    }
    schemasDir =
        cursor == null
            ? Path.of("SSOT/schemas").toAbsolutePath()
            : cursor.resolve("SSOT/schemas");
  }

  @Test
  @DisplayName("OperationHistoryEntry schema baseline matches generated output")
  void operationHistoryEntrySchema() throws Exception {
    captureOrVerify(OperationHistoryEntry.class, "operation-history-entry.v1.json");
  }

  @Test
  @DisplayName("Keyed operation outcome schema matches the shared HTTP/MCP view")
  void operationOutcomeViewSchema() throws Exception {
    captureOrVerify(OperationOutcomeView.class, "operation-outcome-view.v1.json");
  }

  @Test
  @DisplayName("Outcome state schema enumerates the actual lowercase JSON values")
  void outcomeSchemaUsesSerializedStateValues() {
    JsonNode schema = schemaGenerator.generateSchema(OperationOutcomeView.class);
    assertEquals(MAPPER.valueToTree(OperationOutcomeView.State.values()),
        schema.path("properties").path("state").path("enum"));
  }

  @Test
  void unknownDeviceMemoryRemainsExplicitlyNullInWireAndSchema() {
    var composition = new CompositionV2("REFUSED", "free_device_memory_unknown", null, 1024L);
    var outcome = new OperationOutcomeView(OperationOutcomeView.State.FAILED, null, 0L,
        null, null, null, null, "COMPONENT_PREPARATION_REQUIRED",
        new OperationOutcomeView.Result("COMPONENT_PREPARATION_REQUIRED", null,
            null, null, composition));
    var wire = MAPPER.valueToTree(outcome).path("result").path("composition");
    assertTrue(wire.has("freeBytes"));
    assertTrue(wire.path("freeBytes").isNull());
    var type = schemaGenerator.generateSchema(OperationOutcomeView.class)
        .path("properties").path("result").path("properties").path("composition")
        .path("properties").path("freeBytes").path("type");
    assertEquals(MAPPER.valueToTree(java.util.List.of("integer", "null")), type);
  }

  private static void captureOrVerify(Class<?> type, String fileName) throws IOException {
    JsonNode current = schemaGenerator.generateSchema(type);
    // tempdoc 696: force LF so Windows System.lineSeparator() doesn't churn committed files
    String currentJson =
        MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(current).replace("\r\n", "\n");
    Path path = schemasDir.resolve(fileName);

    if (!Files.exists(path)) {
      Files.createDirectories(path.getParent());
      Files.writeString(path, currentJson + "\n");
      fail(
          "Schema captured at "
              + path
              + ". Re-run to verify (this is expected on first run).");
    }

    String baselineJson = Files.readString(path);
    JsonNode baseline = MAPPER.readTree(baselineJson);
    assertEquals(
        baseline,
        current,
        "Schema for "
            + type.getSimpleName()
            + " diverged from baseline at "
            + path
            + ". If intended, delete the baseline and re-run to recapture.");
    assertTrue(baseline.has("$schema"), "Baseline schema should declare $schema");
  }
}
