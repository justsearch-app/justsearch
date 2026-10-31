package io.justsearch.app.api.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfig;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jackson.JacksonOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Capture-or-verify baseline for {@link IndexedRootView}'s wire-format JSON schema.
 *
 * <p>Tempdoc 813 Slice A: {@code SSOT/schemas/indexed-root.v1.json} is the source the FE type +
 * Zod validator are generated from ({@code scripts/codegen/gen-wire-schema-types.mjs}), but unlike
 * its sibling {@link IndexingJobView} it had NO drift guard — adding fields to the Java record left
 * the schema (and therefore the {@code z.strictObject} validator) silently behind, so the new keys
 * on the wire would have been rejected FE-side. This test closes that asymmetry.
 *
 * <p>Same capture-or-verify contract as {@code IndexingJobViewSchemaTest}: delete the baseline and
 * re-run to recapture after an intended record change.
 */
@SuppressWarnings("removal")
@DisplayName("IndexedRootView schema generation")
final class IndexedRootViewSchemaTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static SchemaGenerator schemaGenerator;
  private static Path schemasDir;

  @BeforeAll
  static void setupSchemaGenerator() {
    JacksonModule jacksonModule =
        new JacksonModule(
            JacksonOption.RESPECT_JSONPROPERTY_ORDER,
            JacksonOption.RESPECT_JSONPROPERTY_REQUIRED);
    SchemaGeneratorConfig config =
        new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
            .with(jacksonModule)
            .with(com.github.victools.jsonschema.generator.Option.MAP_VALUES_AS_ADDITIONAL_PROPERTIES)
            .build();
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
  @DisplayName("IndexedRootView schema matches the committed SSOT baseline")
  void indexedRootViewSchema() throws Exception {
    captureOrVerify(IndexedRootView.class, "indexed-root.v1.json");
  }

  @Test
  @DisplayName("baseline carries the tempdoc 813 per-root coverage fields")
  void baselineCarriesCoverageFields() throws Exception {
    JsonNode properties =
        MAPPER.readTree(Files.readString(schemasDir.resolve("indexed-root.v1.json")))
            .path("properties");
    for (String field :
        new String[] {
          "parentDocsTotalEmbedding",
          "parentDocsSettledEmbedding",
          "parentDocsTotalSplade",
          "parentDocsSettledSplade",
          "parentDocsTotalNer",
          "parentDocsSettledNer",
          "chunkDocsTotal",
          "chunkDocsSettled"
        }) {
      assertTrue(properties.has(field), "Baseline is missing coverage field " + field);
      assertEquals(
          "integer",
          properties.path(field).path("type").asString(),
          field + " must be an integer on the wire");
    }
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
      fail("Schema captured at " + path + ". Re-run to verify (this is expected on first run).");
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
    assertTrue(baseline.has("$schema"), "Baseline should declare $schema");
  }
}
