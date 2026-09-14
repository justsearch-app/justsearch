/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.AiInstallStatus;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.runtimestate.RuntimeIntentTestFixture;
import io.justsearch.app.services.settings.SettingsServiceImpl;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.model.DownloadProfile;
import io.justsearch.configuration.model.InstallPlan;
import io.justsearch.configuration.model.ModelRegistry;
import io.justsearch.configuration.resolved.ConfigStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AiInstallOnnxSettingsProducerTest {
  @TempDir Path directory;
  private static final List<String> IDS = List.of("embedding", "reranker", "ner", "splade", "citation-scorer");
  private static final List<String> KEYS = List.of("justsearch.embed.onnx.model_path", "justsearch.rerank.model_path",
      "justsearch.ner.model_path", "justsearch.splade.model_path", "justsearch.citation.scorer.model_path");

  @Test
  void stageCommitsEligiblePathsTogetherWithoutPropertyPromotionAndPreservesEarlierStage() throws Exception {
    var store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, directory.resolve("settings.json"));
    var config = new ConfigStore(ConfigStoreRebuilder.prepare(store.load()));
    try (var fixture = new RuntimeIntentTestFixture(directory, store, config)) {
      var runner = spy(fixture.runner());
      var service = new AiInstallService(null, store, null, null, directory, null,
          new SettingsServiceImpl(store, runner));
      var registry = service.getManifest();
      var beforeProperties = KEYS.stream().map(key -> System.getProperty(key, "<absent>")).toList();
      for (String id : IDS) Files.createDirectories(directory.resolve("models").resolve(registry.findPackage(id).targetDir()));
      var field = AiInstallService.class.getDeclaredField("status");
      field.setAccessible(true);
      var status = (AiInstallStatus) field.get(service);
      var pending = new AiInstallStatus.PackageStatus();
      pending.packageId = "reranker";
      pending.state = "pending";
      status.packages.add(pending);
      assertTrue(apply(service, registry));
      assertEquals(1L, store.inspect().witness().acceptedRevision());
      assertTrue(store.load().getRerankerModelPath().isBlank());
      String embedding = store.load().getEmbedOnnxModelPath();
      assertFalse(embedding.isBlank());
      assertFalse(apply(service, registry), "unchanged stage cannot write again");
      verify(runner, times(1)).accept(any());
      pending.state = "installed";
      assertTrue(apply(service, registry));
      assertEquals(2L, store.inspect().witness().acceptedRevision());
      assertEquals(embedding, store.load().getEmbedOnnxModelPath());
      for (int i = 0; i < IDS.size(); i++) {
        String expected = directory.resolve("models").resolve(registry.findPackage(IDS.get(i)).targetDir()).toAbsolutePath().toString();
        assertEquals(expected, config.get().resolution(KEYS.get(i)).value());
        assertEquals(300, config.get().resolution(KEYS.get(i)).sourceOrdinal());
      }
      assertEquals(beforeProperties, KEYS.stream().map(key -> System.getProperty(key, "<absent>")).toList());
    }
  }

  @Test
  void concurrentIntentRefusesWholeStageWithoutPublishingPaths() throws Exception {
    var store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, directory.resolve("settings.json"));
    var config = new ConfigStore(ConfigStoreRebuilder.prepare(store.load()));
    try (var fixture = new RuntimeIntentTestFixture(directory, store, config)) {
      var runner = spy(fixture.runner());
      doAnswer(call -> {
        fixture.spec().setChatEnabled(true);
        return call.callRealMethod();
      }).when(runner).accept(any());
      var service = new AiInstallService(null, store, null, null, directory, null,
          new SettingsServiceImpl(store, runner));
      var registry = service.getManifest();
      Files.createDirectories(directory.resolve("models").resolve(registry.findPackage("embedding").targetDir()));
      var failure = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> apply(service, registry));
      assertInstanceOf(io.justsearch.app.api.settings.SettingsCommitOwner.Refused.class, failure.getCause());
      assertTrue(fixture.spec().load().chatEnabled());
      assertEquals(1L, store.inspect().witness().acceptedRevision());
      assertTrue(store.load().getEmbedOnnxModelPath().isBlank());
      var path = config.get().resolution(KEYS.get(0));
      assertTrue(path == null || path.value() == null || path.value().isBlank());
    }
  }

  @Test
  void successfulButIncompleteSettingsAttemptCannotCompleteStage() throws Exception {
    var store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, directory.resolve("settings.json"));
    var settings = mock(io.justsearch.app.api.SettingsService.class);
    var row = mock(io.justsearch.app.api.operations.OperationRecord.class);
    when(row.state()).thenReturn(io.justsearch.app.api.operations.OperationState.ACCEPTED);
    when(settings.applyInternal(any(), any(), any())).thenReturn(
        new io.justsearch.app.api.operations.OperationAttemptRunner.Result(row,
            io.justsearch.agent.api.registry.OperationResult.success("Accepted"),
            new java.util.concurrent.CompletableFuture<>()));
    var service = new AiInstallService(null, store, null, null, directory, null, settings);
    var registry = service.getManifest();
    Files.createDirectories(directory.resolve("models").resolve(registry.findPackage("embedding").targetDir()));
    var before = store.inspect().witness();
    var failure = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> apply(service, registry));
    assertInstanceOf(IllegalStateException.class, failure.getCause());
    assertEquals(before, store.inspect().witness());
    assertTrue(store.load().getEmbedOnnxModelPath().isBlank());
  }

  private static boolean apply(AiInstallService service, ModelRegistry registry) throws Exception {
    var method = AiInstallService.class.getDeclaredMethod("applyOnnxSettings", ModelRegistry.class, InstallPlan.class);
    method.setAccessible(true);
    return (boolean) method.invoke(service, registry,
        new InstallPlan(DownloadProfile.values()[0], List.of(), List.of(), 0L, IDS));
  }
}
