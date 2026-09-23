package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.settings.LlmSettingsV2;
import io.justsearch.app.api.settings.SettingsV2;
import io.justsearch.app.api.settings.UiSettingsV2;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cross-language contract test for SettingsV2 (368 RC1).
 *
 * <p>Generates a fixture from current Java types and verifies round-trip fidelity.
 * The fixture is consumed by the TypeScript contract test in
 * {@code modules/ui-web/src/api/contract.test.ts}.
 */
@DisplayName("SettingsV2 cross-language contract")
final class SettingsV2ContractTest {

  private static final JsonMapper MAPPER = JsonMapper.builder()
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .build();

  private static final Path FIXTURE_PATH = Path.of("..", "ui-web", "src", "api",
      "__fixtures__", "settings-v2-live.json");

  @Test
  @DisplayName("Generate SettingsV2 fixture and verify round-trip fidelity")
  void generateFixtureAndVerifyRoundTrip() throws Exception {
    SettingsV2 original = new SettingsV2(
        new UiSettingsV2("dark", true, "compact", true, "reveal", 400,
            true, "advanced", true, List.of("*.tmp", "node_modules/**"), true),
        new LlmSettingsV2("llama-server.exe", 8192, 2048, 35, "C:/models/chat.gguf", null),
        List.of("C:/docs", "D:/papers"),
        "read_write", new io.justsearch.app.api.settings.SettingsWitness(0, null), null, null);

    // Serialize
    // tempdoc 696: force LF so Windows System.lineSeparator() doesn't churn committed files
    String json =
        MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(original).replace("\r\n", "\n");

    // Write fixture for TypeScript test
    Files.createDirectories(FIXTURE_PATH.getParent());
    Files.writeString(FIXTURE_PATH, json + "\n", StandardCharsets.UTF_8);

    // Round-trip: deserialize back and compare
    SettingsV2 roundTripped = MAPPER.readValue(json, SettingsV2.class);
    assertNotNull(roundTripped.ui());
    assertNotNull(roundTripped.llm());
    assertNotNull(roundTripped.indexPaths());

    // Verify every UI field survives the round-trip
    assertEquals(original.ui().theme(), roundTripped.ui().theme());
    assertEquals(original.ui().highContrast(), roundTripped.ui().highContrast());
    assertEquals(original.ui().density(), roundTripped.ui().density());
    assertEquals(original.ui().vimMode(), roundTripped.ui().vimMode());
    assertEquals(original.ui().defaultAction(), roundTripped.ui().defaultAction());
    assertEquals(original.ui().inspectorWidth(), roundTripped.ui().inspectorWidth());
    assertEquals(original.ui().pauseIndexingDuringAi(), roundTripped.ui().pauseIndexingDuringAi());
    assertEquals(original.ui().mode(), roundTripped.ui().mode());
    assertEquals(original.ui().hasSeenTrustLoopNudge(), roundTripped.ui().hasSeenTrustLoopNudge());
    assertEquals(original.ui().excludePatterns(), roundTripped.ui().excludePatterns());
    assertEquals(original.ui().chatEnabled(), roundTripped.ui().chatEnabled());

    // Verify LLM fields
    assertEquals(original.llm().serverExecutable(), roundTripped.llm().serverExecutable());
    assertEquals(original.llm().contextWindow(), roundTripped.llm().contextWindow());
    assertEquals(original.llm().maxTokens(), roundTripped.llm().maxTokens());
    assertEquals(original.llm().gpuLayers(), roundTripped.llm().gpuLayers());
    assertEquals(original.llm().modelPath(), roundTripped.llm().modelPath());
    assertEquals(original.llm().llamaLibPath(), roundTripped.llm().llamaLibPath());

    // Verify indexPaths
    assertEquals(original.indexPaths(), roundTripped.indexPaths());

    // Verify settingsMode
    assertEquals(original.settingsMode(), roundTripped.settingsMode());
    assertEquals(original.witness(), roundTripped.witness());

    // Verify fixture JSON has expected keys
    JsonNode tree = MAPPER.readTree(json);
    assertTrue(tree.has("ui"), "fixture missing 'ui' key");
    assertTrue(tree.has("llm"), "fixture missing 'llm' key");
    assertTrue(tree.has("indexPaths"), "fixture missing 'indexPaths' key");
    assertTrue(tree.has("settingsMode"), "fixture missing 'settingsMode' key");
    assertTrue(tree.has("apiPort"), "fixture missing 'apiPort' key");
    assertTrue(tree.has("restartScheduled"), "fixture missing 'restartScheduled' key");
    assertTrue(tree.get("ui").has("vimMode"), "fixture missing 'ui.vimMode'");
  }
}
