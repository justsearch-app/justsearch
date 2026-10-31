package io.justsearch.app.observability.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.Surface;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Committed frontend declaration projection; ordinary tests compare, -PupdateSchemas writes. */
class CoreSurfacesTsGenerationTest {
  private static final String GENERATED_REL =
      "modules/ui-web/src/api/generated/core-surfaces.generated.ts";

  static String render() {
    Map<String, Map<String, String>> metadata = new LinkedHashMap<>();
    for (Surface surface : new CoreSurfaceCatalog().definitions()) {
      Map<String, String> fields = new LinkedHashMap<>();
      fields.put("id", surface.id().value());
      fields.put("mountTag", surface.mountTag());
      fields.put("labelKey", surface.presentation().labelKey().value());
      fields.put("descriptionKey", surface.presentation().descriptionKey().value());
      fields.put("audience", surface.audience().name());
      fields.put("placement", surface.placement().name());
      metadata.put(surface.id().value(), fields);
    }
    // JSON is also a TypeScript object literal: Jackson escapes quotes, slashes and control chars.
    // Preserve catalog/field order; no unordered collections or runtime metadata enter the projection.
    String json = JsonMapper.builder().build().writerWithDefaultPrettyPrinter()
        .writeValueAsString(metadata).replace("\r\n", "\n")
        .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
    return "/* eslint-disable */\n"
        + "// GENERATED — do not edit by hand.\n"
        + "// Authority: io.justsearch.app.observability.surface.CoreSurfaceCatalog.\n"
        + "// Emitter/check: CoreSurfacesTsGenerationTest.generatedMetadataMatchesJavaAuthority.\n"
        + "// Regenerate: ./gradlew :modules:app-observability:test -PupdateSchemas"
        + " --tests '*CoreSurfacesTsGenerationTest'\n"
        + "// Only shared declaration fields; membership remains wire-owned.\n\n"
        + "export const CORE_SURFACE_METADATA = " + json + " as const;\n";
  }

  @Test
  void generatedMetadataMatchesJavaAuthority() throws IOException {
    Path out = repoRoot().resolve(GENERATED_REL);
    String rendered = render();
    if (Boolean.getBoolean("updateSchemas")) {
      Files.createDirectories(out.getParent());
      Files.writeString(out, rendered);
      return;
    }
    assertTrue(Files.isRegularFile(out), GENERATED_REL + " is missing; regenerate with -PupdateSchemas");
    assertEquals(rendered, Files.readString(out),
        GENERATED_REL + " drifted from CoreSurfaceCatalog; regenerate with -PupdateSchemas");
  }

  private static Path repoRoot() {
    Path dir = Path.of("").toAbsolutePath();
    for (int i = 0; i < 8 && dir != null; i++, dir = dir.getParent()) {
      if (Files.isDirectory(dir.resolve("governance"))
          && Files.isRegularFile(dir.resolve("settings.gradle.kts"))) return dir;
    }
    throw new IllegalStateException("Cannot find repository root");
  }
}
