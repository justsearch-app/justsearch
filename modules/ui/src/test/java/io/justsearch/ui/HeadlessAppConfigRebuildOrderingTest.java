/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.app.api.UiSettings;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
  }

  @Test
  @DisplayName("a sysprop written after the build reaches the config the rest of boot sees")
  void postBuildSyspropReachesTheReturnedConfig() {
    // The boot build: settings.json at ordinal 300 names the exe the user chose.
    ResolvedConfigBuilder builder = ResolvedConfig.builder();
    builder.putSettings(SERVER_EXE, "C:/installed/llama-server.exe");
    builder.contributeEnvRegistry();
    ConfigStore store = new ConfigStore(builder.build());
    assertEquals(
        "C:/installed/llama-server.exe",
        store.get().resolution(SERVER_EXE).value(),
        "precondition: the pre-write config names the original exe");

    // What maybeAutoSelectCuda12Variant does: writes the sysprop at ordinal 500, does NOT rebuild.
    System.setProperty(SERVER_EXE, "C:/installed/variants/cuda12/llama-server.exe");

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
}
