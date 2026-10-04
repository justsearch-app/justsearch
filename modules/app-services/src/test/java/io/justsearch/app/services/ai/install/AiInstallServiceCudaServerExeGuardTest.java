/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code AiInstallService.applyCudaServerExe} must not overwrite a user's chosen server executable
 * (tempdoc 883 decision 4 slice 2).
 *
 * <p>The guard used to read the {@code justsearch.server.exe} system property, which a GUI choice
 * reached only because {@code SettingsController} promoted it there. Slice 2 deleted that
 * promotion. Had the guard stayed on the sysprop it would have fallen through and
 * {@code applyCudaServerExe} would have overwritten the choice AND persisted the cuda12 path back
 * into the user's {@code UiSettings} — so the settings-sourced-with-no-sysprop case below is the
 * one that fails on the pre-change guard.
 */
@DisplayName("AiInstallService cuda12 server.exe user-override guard")
final class AiInstallServiceCudaServerExeGuardTest {

  private static final String SERVER_EXE = "justsearch.server.exe";
  private static final String SERVER_EXE_SOURCE = "justsearch.server.exe.source";

  private String savedExe;
  private String savedSource;

  @BeforeEach
  void clearSysprops() {
    savedExe = System.getProperty(SERVER_EXE);
    savedSource = System.getProperty(SERVER_EXE_SOURCE);
    System.clearProperty(SERVER_EXE);
    System.clearProperty(SERVER_EXE_SOURCE);
  }

  @AfterEach
  void restoreSysprops() {
    restore(SERVER_EXE, savedExe);
    restore(SERVER_EXE_SOURCE, savedSource);
  }

  private static void restore(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }

  @Test
  @DisplayName("a settings.json exe with NO sysprop set is respected as a user override")
  void settingsSourcedExeIsRespected() {
    UiSettings settings = new UiSettings();
    settings.setServerExecutablePath("C:/user/chosen/llama-server.exe");

    ResolvedConfigBuilder builder = ResolvedConfig.builder();
    builder.contributeEnvRegistry(); // ordinal 500/400 — deliberately empty for this key
    ConfigStoreRebuilder.contributeUiSettings(builder, settings);
    ResolvedConfig resolved = builder.build();

    assertTrue(
        AiInstallService.serverExeIsUserOwned(resolved),
        "the user picked this exe in the GUI; Install AI must not replace it with cuda12");
  }

  @Test
  @DisplayName("an exe a previous cuda12 auto-selection chose is NOT a user override")
  void autoSelectedCuda12ExeIsNotAUserOverride() {
    ResolvedConfigBuilder builder = ResolvedConfig.builder();
    builder.contributeAutoDetected(java.util.Map.of(SERVER_EXE, "C:/app/variants/cuda12/llama-server.exe"));

    assertFalse(
        AiInstallService.serverExeIsUserOwned(builder.build()),
        "re-selecting cuda12 over a previous cuda12 auto-selection is the method's whole job");
  }

  @Test
  void staleOwnershipMarkerCannotUnlockAnOperatorExecutable() {
    System.setProperty(SERVER_EXE, "C:/operator/llama-server.exe");
    System.setProperty(SERVER_EXE_SOURCE, "auto_selected_cuda12");
    var builder = ResolvedConfig.builder();
    builder.contributeEnvRegistry();
    assertTrue(AiInstallService.serverExeIsUserOwned(builder.build()));
  }

  @Test
  @DisplayName("no exe resolved at all is not a user override")
  void unresolvedExeIsNotAUserOverride() {
    assertFalse(AiInstallService.serverExeIsUserOwned(ResolvedConfig.builder().build()));
  }

  @Test
  @DisplayName("no ConfigStore published yet is not a user override")
  void nullConfigIsNotAUserOverride() {
    assertFalse(AiInstallService.serverExeIsUserOwned(null));
  }
}
