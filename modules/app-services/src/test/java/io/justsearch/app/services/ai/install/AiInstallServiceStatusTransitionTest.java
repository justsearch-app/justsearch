/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.AiInstallStatus;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.model.DownloadProfile;
import io.justsearch.configuration.model.ExecutionProvider;
import io.justsearch.configuration.model.InstallContract;
import io.justsearch.configuration.model.InstallContractIO;
import io.justsearch.configuration.model.ModelPrecision;
import io.justsearch.configuration.resolved.ConfigStore;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Regression coverage for the acquisition-to-activation status boundary. */
final class AiInstallServiceStatusTransitionTest {
  @TempDir Path tmp;

  @Test
  void exactCandidateProjectionCompletesActivationButMissingModelStaysPending() throws Exception {
    Path model = tmp.resolve("models/onnx/embedding/model.onnx");
    Files.createDirectories(model.getParent());
    Files.writeString(model, "candidate", StandardCharsets.UTF_8);
    writeContract(model);

    UiSettingsStore settings =
        new UiSettingsStore(
            UiSettingsStore.PersistenceMode.READ_WRITE, tmp.resolve("settings.json"));
    UiSettings serving = settings.inspect().settings();
    serving.setEmbedOnnxModelPath(tmp.resolve("models/old-embedding").toString());
    settings.replacePrepared(settings.prepareExact(serving, settings.inspect().witness()));

    AiInstallService service = new AiInstallService(null, settings, null, null, tmp);
    AiInstallStatus live = statusOf(service);
    // applyCompletionState has already run when acquisition publishes this terminal phase.
    live.state = "completed";
    live.installedFully = true;
    markActivationRequired(service);

    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore servingConfig = new ConfigStore(ConfigStoreRebuilder.prepare(settings.load()));
    ConfigStore.setGlobal(servingConfig);
    try {
      AiInstallStatus pending = service.getStatus();
      assertEquals("activation_required", pending.phase);
      assertEquals("Downloaded — activation required.", pending.message);
      assertFalse(pending.installedFully);

      Files.delete(model);
      UiSettings candidate = settings.inspect().settings();
      candidate.setEmbedOnnxModelPath(model.getParent().toString());
      settings.replacePrepared(settings.prepareExact(candidate, settings.inspect().witness()));
      servingConfig.swap(ConfigStoreRebuilder.prepare(settings.load()));

      AiInstallStatus missing = service.getStatus();
      assertEquals(
          "activation_required", missing.phase, "exact settings alone cannot activate missing assets");
      assertFalse(missing.installedFully);

      Files.writeString(model, "candidate", StandardCharsets.UTF_8);
      AiInstallStatus done = service.getStatus();
      assertEquals("done", done.phase);
      assertTrue(done.installedFully);
    } finally {
      ConfigStore.restoreGlobal(servingConfig, previous);
    }
  }

  private void writeContract(Path model) throws Exception {
    String contents = Files.readString(model, StandardCharsets.UTF_8);
    String sha =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(contents.getBytes(StandardCharsets.UTF_8)));
    InstallContract contract =
        new InstallContract(
            2,
            1L,
            null,
            DownloadProfile.CPU,
            Map.of(
                "embedding",
                new InstallContract.InstalledModel(
                    "embedding",
                    "model.onnx",
                    ModelPrecision.FP32,
                    ExecutionProvider.CPU,
                    "onnx/embedding",
                    sha,
                    List.of("model.onnx"),
                    false,
                    null)),
            tmp.resolve("models"),
            null);
    InstallContractIO.write(contract, tmp);
  }

  private static AiInstallStatus statusOf(AiInstallService service) throws Exception {
    Field field = AiInstallService.class.getDeclaredField("status");
    field.setAccessible(true);
    return (AiInstallStatus) field.get(service);
  }

  private static void markActivationRequired(AiInstallService service) throws Exception {
    Method method = AiInstallService.class.getDeclaredMethod("markActivationRequired");
    method.setAccessible(true);
    method.invoke(service);
  }
}
