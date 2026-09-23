/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.justsearch.app.api.UiSettings;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ConfigApplyScopes;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

/**
 * Tempdoc 883 §C.5c — the config the rest of boot sees is rebuilt AFTER the boot steps that write
 * system properties the resolver has already read past.
 *
 * <p>The residue 883 found: {@code resolveConfig} built a {@code ResolvedConfig}, then
 * {@code maybeAutoSelectCuda12Variant} wrote {@code justsearch.server.exe} (and
 * {@code maybeMirrorOrtNativePath} wrote {@code justsearch.onnxruntime.native_path}), and every
 * later reader was handed the config built BEFORE those writes. A boot-time cuda12 auto-select
 * therefore changed nothing.
 *
 * <p><b>This class was {@code HeadlessAppWorkerSnapshotOrderingTest} until lane F item A19.</b> It
 * asserted the same ordering through two consumers — the Worker's config snapshot and the returned
 * config. A19 deleted the snapshot tier (there is no second process to load an ordinal-450 file),
 * so the snapshot assertions had no subject. The ordering itself is not a snapshot property and did
 * not go with it: it is what makes the two sysprop writes visible at all, and the ORT native-pack
 * detection (review B2) reads the rebuilt config immediately afterwards. Retargeted onto the
 * surviving consumer rather than deleted, because deleting it would have left that ordering pinned
 * by nothing at exactly the moment the method around it was being rewritten.
 */
@DisplayName("HeadlessApp — config rebuild ordering (883 §C.5c)")
final class HeadlessAppConfigRebuildOrderingTest {

  private static final String SERVER_EXE = "justsearch.server.exe";

  @AfterEach
  void clearProps() {
    System.clearProperty(SERVER_EXE);
    io.justsearch.app.services.config.ConfigStoreRebuilder.rememberAutoDetected(java.util.Map.of());
  }

  @Test
  @DisplayName("remembered boot discovery reaches later rebuilds at ordinal150")
  void rememberedBootDiscoveryReachesTheReturnedConfig() {
    // The boot build: settings.json at ordinal 300 names the exe the user chose.
    ResolvedConfigBuilder builder = ResolvedConfig.builder();
    builder.putSettings(SERVER_EXE, "C:/installed/llama-server.exe");
    builder.contributeEnvRegistry();
    ConfigStore store = new ConfigStore(builder.build());
    assertEquals(
        "C:/installed/llama-server.exe",
        store.get().resolution(SERVER_EXE).value(),
        "precondition: the pre-write config names the original exe");

    io.justsearch.app.services.config.ConfigStoreRebuilder.rememberAutoDetected(
        java.util.Map.of(SERVER_EXE, "C:/installed/variants/cuda12/llama-server.exe"));

    ResolvedConfig effective =
        HeadlessApp.rebuildAfterPostBuildWrites(store, new UiSettings());

    assertEquals(
        "C:/installed/variants/cuda12/llama-server.exe",
        effective.resolution(SERVER_EXE).value(),
        "the rest of boot must see the exe the Head just auto-selected");
    assertEquals(
        "C:/installed/variants/cuda12/llama-server.exe",
        store.get().resolution(SERVER_EXE).value(),
        "the ConfigStore is updated in place, so no reader is left on the stale config");
    assertEquals(150, effective.resolution(SERVER_EXE).sourceOrdinal());
    org.junit.jupiter.api.Assertions.assertNull(System.getProperty(SERVER_EXE));
  }

  @Test
  void persistedCpuSelectionAndOperatorOverrideBeatRememberedCudaDiscovery() {
    io.justsearch.app.services.config.ConfigStoreRebuilder.rememberAutoDetected(
        java.util.Map.of(SERVER_EXE, "C:/installed/variants/cuda12/llama-server.exe"));
    UiSettings settings = new UiSettings();
    settings.setServerExecutablePath("C:/installed/llama-server.exe");
    ConfigStore store = new ConfigStore(ResolvedConfig.builder().build());
    var effective = HeadlessApp.rebuildAfterPostBuildWrites(store, settings);
    assertEquals(settings.getServerExecutablePath(), effective.resolution(SERVER_EXE).value());
    assertEquals(300, effective.resolution(SERVER_EXE).sourceOrdinal());
    System.setProperty(SERVER_EXE, "C:/operator/llama-server.exe");
    effective = HeadlessApp.rebuildAfterPostBuildWrites(store, settings);
    assertEquals("C:/operator/llama-server.exe", effective.resolution(SERVER_EXE).value());
    assertEquals(500, effective.resolution(SERVER_EXE).sourceOrdinal());
  }

  @Test
  @DisplayName("the rebuild does not drop the settings the initial build contributed")
  void rebuildKeepsSettingsContributions() {
    ResolvedConfigBuilder builder = ResolvedConfig.builder();
    UiSettings settings = new UiSettings();
    settings.setLlmModelPath("C:/models/chat.gguf");
    io.justsearch.app.services.config.ConfigStoreRebuilder.contributeUiSettings(builder, settings);
    ConfigStore store = new ConfigStore(builder.build());

    ResolvedConfig effective = HeadlessApp.rebuildAfterPostBuildWrites(store, settings);

    assertEquals(
        "C:/models/chat.gguf",
        effective.resolution("justsearch.llm.model_path").value(),
        "a rebuild that dropped ordinal 300 would silently un-set every GUI value at boot");
  }

  @Test
  @DisplayName("boot policy publication is already present in the first settings candidate")
  void policySnapshotDoesNotLookLikeAnEncoderChangeOnFirstApply(@TempDir Path temp) {
    String previousHome = System.getProperty("justsearch.home");
    String previousGpuPolicy = System.getProperty("policy.gpu_acceleration_enabled");
    String previousExternalPolicy = System.getProperty(
        "justsearch.policy.disallowExternalInferenceServers");
    ConfigStore previousStore = ConfigStore.globalOrNull();
    System.setProperty("justsearch.home", temp.toString());
    System.clearProperty("policy.gpu_acceleration_enabled");
    System.clearProperty("justsearch.policy.disallowExternalInferenceServers");
    io.justsearch.app.services.config.ConfigStoreRebuilder.rememberAutoDetected(java.util.Map.of());
    UiSettings settings = new UiSettings();
    ResolvedConfigBuilder initial = ResolvedConfig.builder();
    initial.contributeBaseSources();
    io.justsearch.app.services.config.ConfigStoreRebuilder.contributeUiSettings(initial, settings);
    ConfigStore store = new ConfigStore(initial.build());
    try {
      ConfigStore.setGlobal(store);
      HeadlessApp.loadPolicySources();
      HeadlessApp.rebuildAfterPostBuildWrites(store, settings);

      ResolvedConfig firstCandidate =
          io.justsearch.app.services.config.ConfigStoreRebuilder.prepare(settings);
      assertEquals(firstCandidate.resolution("policy.gpu_acceleration_enabled").value(),
          store.get().resolution("policy.gpu_acceleration_enabled").value());
      assertFalse(ConfigApplyScopes.classify(store.get(), firstCandidate)
          .component().containsKey("encoders"));
    } finally {
      io.justsearch.configuration.resolved.TestResolvedConfigHelper.restoreGlobal(previousStore);
      restoreProperty("justsearch.home", previousHome);
      restoreProperty("policy.gpu_acceleration_enabled", previousGpuPolicy);
      restoreProperty("justsearch.policy.disallowExternalInferenceServers", previousExternalPolicy);
    }
  }

  private static void restoreProperty(String name, String value) {
    if (value == null) System.clearProperty(name);
    else System.setProperty(name, value);
  }
}
