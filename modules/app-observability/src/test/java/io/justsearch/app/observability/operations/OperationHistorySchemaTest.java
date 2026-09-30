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
    captureOrVerify(io.justsearch.app.api.operations.OperationOutcomeView.class, "operation-outcome-view.v1.json");
  }

  @Test
  @DisplayName("Outcome state schema enumerates the actual lowercase JSON values")
  void outcomeSchemaUsesSerializedStateValues() {
    JsonNode schema = schemaGenerator.generateSchema(io.justsearch.app.api.operations.OperationOutcomeView.class);
    assertEquals(MAPPER.valueToTree(io.justsearch.app.api.operations.OperationOutcomeView.State.values()),
        schema.path("properties").path("state").path("enum"));
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
