package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.model.DownloadProfile;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.model.InstallContract;
import io.justsearch.configuration.model.InstallContractIO;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Tempdoc 804 §B4.2 — the adverse-precondition test for the shipped configuration.
 *
 * <p>Round 10 shipped a build nothing had ever exercised: the packaged Tauri shell launches the
 * Head with {@code -Djustsearch.prod=true} (PR #350), which used to switch {@code UiSettingsStore}
 * to in-memory. Every settings write was discarded, so {@code llmModelPath} never persisted and AI
 * activation always failed {@code MODEL_PATH_REQUIRED} with no in-product recovery. Dev, ui-shot,
 * RAIL and unit tiers all booted WITHOUT {@code prod=true}, so the defect was invisible
 * ({@code green-masked-destructive}, config-axis form).
 *
 * <p>This boots the Head in-process with {@code justsearch.prod=true} and NO settings-mode
 * override, on a data dir seeded the way a v0.1.0 install leaves it (populated install contract +
 * chat model file on disk, no ui settings file), and asserts the three things the round found
 * broken.
 */
@DisplayName("prod=true boot on a v0.1.0-shaped data dir (tempdoc 804 §B4.2)")
class ProdModeSettingsPersistenceIntegrationTest extends LocalApiIntegrationTestBase {

  private static final String SESSION_TOKEN = "integration-test-session-token";
  private static final String VARIANT_ID = "cuda12";

  private ConfigStore previousConfigStore;
  private Path chatModel;

  @Override
  protected void beforeServerStart() throws Exception {
    // Exactly what the packaged shell passes, and nothing else: persistence must not be implied.
    setPropForTest("justsearch.prod", "true");

    // v0.1.0-shaped data dir: install contract + chat model on disk, runtime variant installed,
    // and NO ui settings file (the upgrade case the round measured).
    chatModel = seedInstalledChatModel();
    seedRuntimeVariant();
    assertFalse(
        Files.exists(aiHome.resolve("ui").resolve("settings.json")),
        "precondition: the seeded data dir has no ui settings file");

    // The prod trust boundary itself is read from the resolved config, so build one the way the
    // launcher does (it picks up justsearch.prod from the sysprop set above).
    previousConfigStore = ConfigStore.globalOrNull();
    ResolvedConfigBuilder rc = ResolvedConfig.builder();
    rc.contributeBaseSources();
    ConfigStore.setGlobal(new ConfigStore(rc.build()));
  }

  @Override
  protected LocalApiServer.Builder configureServer(LocalApiServer.Builder builder) {
    // Prod mode without a session token is REFUSED at startup (tempdoc 884 item 23), exactly as the
    // shipped app is configured — it always mints one.
    return builder.sessionToken(SESSION_TOKEN);
  }

  @AfterEach
  void restoreConfigStore() {
    TestResolvedConfigHelper.restoreGlobal(previousConfigStore);
  }

  @Test
  @DisplayName("(a) activation resolves the installed chat model instead of MODEL_PATH_REQUIRED")
  void activationDoesNotFailModelPathRequired() throws Exception {
    HttpJsonResponse start =
        postJson("/api/ai/runtime/activate", Map.of("variantId", VARIANT_ID), tokenHeader());
    assertEquals(200, start.statusCode(), start.body());

    JsonNode activation = awaitActivationDone();
    assertNotEquals(
        "MODEL_PATH_REQUIRED",
        activation.path("errorCode").asText(""),
        "the install contract records " + chatModel + " — activation must not claim none is configured: "
            + activation);
    // Precision: the self-test runs only AFTER the model-path and model-exists checks, so a blank
    // result would mean the assertion above passed for an unrelated reason.
    assertFalse(
        activation.path("result").asText("").isBlank(),
        "activation must reach the GPU self-test: " + activation);
  }

  @Test
  @DisplayName("(b)+(c) settings are writable and survive a fresh store load")
  void settingsWriteIsAcceptedAndPersists() throws Exception {
    HttpJsonResponse current = getJson("/api/settings/v2");
    assertEquals(200, current.statusCode(), current.body());
    String key = io.justsearch.app.api.operations.OperationKeys.generate(java.time.Clock.systemUTC());
    HttpJsonResponse resp =
        postJson(
            "/api/settings/v2",
            Map.of("ui", Map.of("theme", "dark", "density", "compact"),
                "witness", current.json().path("witness"), "operationKey", key),
            tokenHeader());

    assertNotEquals(409, resp.statusCode(), "settings must not be read-only in prod: " + resp.body());
    assertNotEquals(
        "SETTINGS_READ_ONLY", resp.json().path("errorCode").asText(""), resp.body());
    assertEquals(200, resp.statusCode(), resp.body());
    assertEquals("read_write", resp.json().path("settingsMode").asText(), resp.body());
    String operationKey = resp.json().path("operationKey").asText();
    assertEquals(key, operationKey, "settings response must identify its durable operation");
    assertEquals(
        io.justsearch.app.api.operations.OperationOutcomeView.State.COMPLETE,
        operationOutcome(operationKey).state(),
        "the successful settings response must address the committed SQLite outcome");

    // A fresh store — the same resolution the next app launch performs — must see the write.
    UiSettingsStore reloaded = new UiSettingsStore(UiSettingsStore.PersistenceMode.resolveMode());
    assertEquals(
        "dark",
        reloaded.load().getTheme(),
        "a setting written through the API must survive a fresh store load in prod mode");
    assertTrue(
        Files.isRegularFile(aiHome.resolve("ui").resolve("settings.json")),
        "prod mode must persist settings to disk");
  }

  /**
   * Tempdoc 804 §B7 probe (report-only): rounds 9 and 10 measured a 400 with a ZERO-LENGTH body
   * from the packaged app on exactly this call, while {@code AiRuntimeController} has written a
   * JSON body since v0.1.0. This pins what an in-process prod-mode boot returns, which
   * discriminates a prod-linked strip from a packaged-app-only strip.
   */
  @Test
  @DisplayName("prod boot really enforces the session token on mutating calls")
  void mutatingCallWithoutSessionTokenIs401() throws Exception {
    HttpJsonResponse resp = postJson("/api/settings/v2", Map.of("ui", Map.of("theme", "dark")));

    assertEquals(401, resp.statusCode(), resp.body());
    assertEquals("UI_TOKEN_REQUIRED", resp.json().path("errorCode").asText(""), resp.body());
  }

  @Test
  @DisplayName("(B7 probe) empty activate body returns a non-empty 400 JSON body in prod mode")
  void activateWithEmptyBodyReturnsNonEmptyErrorBody() throws Exception {
    HttpJsonResponse resp = postJson("/api/ai/runtime/activate", Map.of(), tokenHeader());

    assertEquals(400, resp.statusCode(), resp.body());
    assertFalse(resp.body().isEmpty(), "mutating 4xx responses must carry a body");
    assertEquals("VARIANT_ID_REQUIRED", resp.json().path("errorCode").asText(""), resp.body());
  }

  private Map<String, String> tokenHeader() {
    return Map.of(LocalApiServer.SESSION_TOKEN_HEADER, SESSION_TOKEN);
  }

  private JsonNode awaitActivationDone() throws Exception {
    long deadline = System.currentTimeMillis() + 90_000;
    String last = "";
    while (System.currentTimeMillis() < deadline) {
      HttpJsonResponse resp = getJson("/api/ai/runtime/status");
      last = resp.body();
      JsonNode activation = resp.json().path("activation");
      String state = activation.path("state").asText("");
      if (!"running".equalsIgnoreCase(state) && !"idle".equalsIgnoreCase(state)) {
        return activation;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("Timed out waiting for runtime activation. Last status: " + last);
  }

  /** Writes the install contract through the same IO the install pipeline uses, plus the model. */
  private Path seedInstalledChatModel() throws Exception {
    Path modelsDir = aiHome.resolve("models");
    Path chatDir = modelsDir.resolve("chat");
    Files.createDirectories(chatDir);
    Path model = chatDir.resolve("chat-model.gguf");
    Files.writeString(model, "gguf-bytes", StandardCharsets.UTF_8);

    InstallContract contract =
        new InstallContract(
            2,
            System.currentTimeMillis(),
            HardwareProfile.cpuOnly(),
            DownloadProfile.GPU_FULL,
            Map.of(
                "chat",
                new InstallContract.InstalledModel(
                    "chat",
                    model.getFileName().toString(),
                    null,
                    null,
                    "chat",
                    "sha",
                    List.of(model.getFileName().toString()),
                    false,
                    null)),
            modelsDir.toAbsolutePath(),
            null);
    InstallContractIO.write(contract, aiHome);
    return model;
  }

  /** A runtime variant directory so activation gets past the variant-installed check. */
  private void seedRuntimeVariant() throws Exception {
    Path variantDir =
        aiHome.resolve("native-bin").resolve("llama-server").resolve("variants").resolve(VARIANT_ID);
    Files.createDirectories(variantDir);
    Files.writeString(variantDir.resolve("llama-server.exe"), "not-a-real-exe", StandardCharsets.UTF_8);
  }
}
