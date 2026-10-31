package io.justsearch.agent.api.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.interaction.InteractionEventKind;
import io.justsearch.agent.api.conversation.ExecutionMode;
import io.justsearch.agent.api.conversation.IterationMode;
import io.justsearch.agent.api.conversation.PersistenceMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * §4.1/§4.3 anti-drift: the shared trust/dispatch vocabulary has ONE authority — the Java enums in
 * this package — and the frontend mirror is GENERATED from it, not hand-authored. This test emits the
 * TypeScript string-union types from the live Java enum constants (reflection), so a value added to
 * (or removed from) a Java enum forces a regeneration; a stale committed FE file fails the build.
 *
 * <p>Why a self-contained emitter rather than the wire proto pipeline: the FE consumes these as
 * string-literal unions ({@code 'CORE' | …}), whereas protobuf-es emits numeric enums (a style
 * mismatch that would ripple across the FE); and the {@code typescript-generator} path was retired
 * (slice 3a-1-8 Phase 4). This mirrors the {@code StatusRecordSchemaTest} write/verify idiom: run with
 * {@code -PupdateSchemas} (system property {@code updateSchemas=true}) to (re)write the artifact;
 * otherwise the test asserts the committed artifact is byte-identical to the freshly generated one.
 */
class RegistryEnumsTsGenerationTest {

  private static final String GENERATED_REL =
      "modules/ui-web/src/api/generated/registry-enums.generated.ts";

  /** The enums lifted to the shared FE vocabulary, in emission order. */
  private static Map<String, Class<? extends Enum<?>>> sharedEnums() {
    Map<String, Class<? extends Enum<?>>> m = new LinkedHashMap<>();
    m.put("TrustTier", TrustTier.class);
    m.put("Audience", Audience.class);
    m.put("RiskTier", RiskTier.class);
    m.put("Altitude", Altitude.class);
    m.put("ExecutorTag", ExecutorTag.class);
    m.put("SourceTier", SourceTier.class);
    m.put("GateBehavior", GateBehavior.class);
    m.put("SubscriptionMode", SubscriptionMode.class);
    m.put("PathPolicy", PathPolicy.class);
    m.put("OnOverflow", OnOverflow.class);
    m.put("AuditPolicy", AuditPolicy.class);
    m.put("RenderHint", RenderHint.class);
    m.put("HistoryMode", HistoryPolicy.Mode.class);
    m.put("IterationMode", IterationMode.class);
    m.put("PersistenceMode", PersistenceMode.class);
    m.put("ExecutionMode", ExecutionMode.class);
    return m;
  }

  /** Render the generated TS: one string-union `export type` per shared enum, values verbatim. */
  static String render() {
    StringBuilder sb = new StringBuilder();
    sb.append("/* eslint-disable */\n");
    sb.append("// GENERATED — do not edit by hand.\n");
    sb.append(
        "// Single authority: the Java enums in io.justsearch.agent.api.registry /"
            + " io.justsearch.agent.api.conversation (see RegistryEnumsTsGenerationTest.sharedEnums());\n"
            + "// io.justsearch.agent.api.interaction.InteractionEventKind owns INTERACTION_EVENT_KINDS.\n");
    sb.append(
        "// Regenerate: ./gradlew :modules:app-agent-api:test -PupdateSchemas"
            + " --tests '*RegistryEnumsTsGenerationTest'\n");
    sb.append("// Drift-checked by RegistryEnumsTsGenerationTest (tempdoc 560 §4.1/§4.3 anti-drift).\n\n");
    for (Map.Entry<String, Class<? extends Enum<?>>> e : sharedEnums().entrySet()) {
      String union =
          java.util.Arrays.stream(e.getValue().getEnumConstants())
              .map(c -> "'" + ((Enum<?>) c).name() + "'")
              .collect(Collectors.joining(" | "));
      sb.append("export type ").append(e.getKey()).append(" = ").append(union).append(";\n");
    }
    sb.append("\nexport const INTERACTION_EVENT_KINDS = [\n");
    for (InteractionEventKind kind : InteractionEventKind.values()) {
      sb.append("  '").append(kind.name()).append("',\n");
    }
    sb.append("] as const;\n");
    sb.append("export type InteractionEventKind = (typeof INTERACTION_EVENT_KINDS)[number];\n");
    return sb.toString();
  }

  @Test
  void generatedFeEnumsMatchTheJavaAuthority() throws IOException {
    Path out = repoRoot().resolve(GENERATED_REL);
    String rendered = render();
    boolean update = Boolean.getBoolean("updateSchemas");
    if (update) {
      Files.createDirectories(out.getParent());
      Files.writeString(out, rendered);
      assertTrue(Files.exists(out), "expected to write " + GENERATED_REL);
      return;
    }
    assertTrue(
        Files.exists(out),
        GENERATED_REL
            + " is missing — run ./gradlew :modules:app-agent-api:test -PupdateSchemas"
            + " --tests '*RegistryEnumsTsGenerationTest' to generate it.");
    String committed = Files.readString(out);
    assertEquals(
        rendered,
        committed,
        "FE registry-enums.generated.ts has drifted from the Java enum authority — regenerate with"
            + " -PupdateSchemas. (tempdoc 560 anti-drift)");
  }

  /** Walk up to the repo root (the dir holding {@code governance/} + {@code settings.gradle.kts}). */
  private static Path repoRoot() {
    Path dir = Path.of("").toAbsolutePath();
    for (int i = 0; i < 8 && dir != null; i++) {
      if (Files.isDirectory(dir.resolve("governance"))
          && Files.isRegularFile(dir.resolve("settings.gradle.kts"))) {
        return dir;
      }
      dir = dir.getParent();
    }
    return Path.of("").toAbsolutePath();
  }
}
