/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.braininstall.BrainInstallServiceImpl;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.registry.operations.handlers.StartAiInstallHandler;
import io.justsearch.app.services.runtimestate.RuntimeIntentTestFixture;
import io.justsearch.app.services.settings.SettingsServiceImpl;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.model.*;
import io.justsearch.configuration.resolved.ConfigStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;

final class AiInstallRerankerCompletionTest {
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void cancelledOrFailedInstallKeepsThePreference(boolean cancel, @TempDir Path home)
      throws Exception {
    var store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,
        home.resolve("settings.json"));
    var saved = new UiSettings();
    saved.setRerankerEnabled(false);
    store.replacePrepared(store.prepare(saved, new SettingsWitness(0, null)));
    var owner = spy(new AiInstallService(null, store, null, null, home));
    doThrow(new IllegalStateException("fixture manifest failure")).when(owner).getManifest();
    if (cancel) {
      var leases = mock(io.justsearch.app.api.OperationLeaseService.class);
      when(leases.register(anyString(), any(), anyLong(), anyMap(), any())).thenAnswer(call -> {
        ((Runnable) call.getArgument(4)).run();
        return mock(io.justsearch.app.api.OperationLeaseHandle.class);
      });
      owner.setOperationLeaseService(leases);
    }
    var handler = new StartAiInstallHandler(() -> new BrainInstallServiceImpl(owner));
    var attempt = handler.executeRecorded(
        "{\"acceptTerms\":true,\"enableRerankerOnCompletion\":true}", null,
        TestEngineContexts.internal(), null);
    if (cancel) {
      assertThrows(java.util.concurrent.ExecutionException.class,
          () -> attempt.completion().toCompletableFuture().get(10, TimeUnit.SECONDS));
    } else {
      assertFalse(attempt.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).success());
    }
    assertEquals(Boolean.FALSE, store.inspect().settings().getRerankerEnabled());
    assertEquals(0, store.inspect().witness().acceptedRevision());
  }

  @org.junit.jupiter.params.ParameterizedTest(
      name = "consentedEnableCommitsWithInstalledPathAtTheInstallerCurrentRevision({0})")
  @org.junit.jupiter.params.provider.ValueSource(strings = {"installed", "custom-path", "discovered-custom"})
  void consentedEnableCommitsWithInstalledPathAtTheInstallerCurrentRevision(String choice,
      @TempDir Path home)
      throws Exception {
    var store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,
        home.resolve("settings.json"));
    var initial = new UiSettings();
    initial.setRerankerEnabled(false);
    byte[] model = "verified reranker fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(model));
    var reranker = new ModelPackage("reranker", "Reranker", "Fixture", "reranker",
        List.of(new ModelVariant("model.onnx", ModelPrecision.FP32, ExecutionProvider.CPU,
            sha, model.length, "https://example.invalid/model")), List.of(),
        0, null, null, null, CapabilityTier.RETRIEVAL_ENRICHMENT);
    var modelDirectory = home.resolve("models").resolve(InstallPlanner.effectiveTargetDir(reranker, reranker.variants().get(0)));
    Files.createDirectories(modelDirectory);
    Files.write(modelDirectory.resolve("model.onnx"), model);
    var planning = new CountDownLatch(1);
    var continuePlanning = new CountDownLatch(1);
    // Native preparation is a separate Engine check. This fixture supplies its selected-role
    // receipt while exercising the real installer, settings coordinator and durable witness.
    var prepared = mock(io.justsearch.app.services.settings.SettingsComponentComposer.Prepared.class);
    boolean custom = !"installed".equals(choice);
    Path selectedDirectory = custom ? home.resolve("custom-ranker") : modelDirectory;
    String selectedFilename = custom ? "custom-fp32.onnx" : "model.onnx";
    Files.createDirectories(selectedDirectory);
    Files.write(selectedDirectory.resolve(selectedFilename), model);
    var modelFile = new io.justsearch.app.api.operations.CandidateIndexSelection.ModelFile(
        selectedDirectory.resolve(selectedFilename), sha, model.length);
    byte[] tokenizer = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Files.write(selectedDirectory.resolve("tokenizer.json"), tokenizer);
    var tokenizerFile = new io.justsearch.app.api.operations.CandidateIndexSelection.ModelFile(
        selectedDirectory.resolve("tokenizer.json"), HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(tokenizer)), tokenizer.length);
    var selected = new io.justsearch.app.api.settings.QueryRoleSelection(
        io.justsearch.app.api.settings.QueryRoleSelection.Role.selected(selectedFilename,
            modelFile, tokenizerFile, ModelPrecision.FP32, ExecutionProvider.CPU),
        io.justsearch.app.api.settings.QueryRoleSelection.Role.disabled());
    doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; })
        .when(prepared).withOwnerLocks(any());
    var disabled = new io.justsearch.app.api.settings.QueryRoleSelection(
        io.justsearch.app.api.settings.QueryRoleSelection.Role.disabled(),
        io.justsearch.app.api.settings.QueryRoleSelection.Role.disabled());
    io.justsearch.app.services.settings.SettingsComponentComposer components =
        (candidate, desired, affected) -> {
          when(prepared.queryRoleSelection()).thenReturn(java.util.Optional.of(
              Boolean.TRUE.equals(candidate.getRerankerEnabled()) ? selected : disabled));
          return prepared;
        };
    if (custom) initial.setRerankerRestoreSelection(selected.reranker());
    if ("custom-path".equals(choice)) initial.setRerankerModelPath(selectedDirectory.toString());
    store.replacePrepared(store.prepare(initial, new SettingsWitness(0, null), disabled));
    var prior = ConfigStore.globalOrNull();
    var published = new ConfigStore(ConfigStoreRebuilder.prepare(initial));
    ConfigStore.setGlobal(published);
    try (var fixture = new RuntimeIntentTestFixture(home.resolve("intent"), store, published, components)) {
      var settings = new SettingsServiceImpl(store, fixture.runner());
      var owner = spy(new AiInstallService(null, store, null, null, home, null, settings));
      doReturn(new ModelRegistry(2, "local installed fixture", List.of(reranker)))
          .when(owner).getManifest();
      doAnswer(call -> {
        planning.countDown();
        assertTrue(continuePlanning.await(10, TimeUnit.SECONDS));
        return HardwareProfile.cpuOnly();
      }).when(owner).detectHardwareProfile();
      var handler = new StartAiInstallHandler(() -> new BrainInstallServiceImpl(owner));
      var attempt = handler.executeRecorded(
          "{\"acceptTerms\":true,\"enableRerankerOnCompletion\":true}", null,
          TestEngineContexts.internal(), null);
      try {
        assertTrue(attempt.response().success(), attempt.response().toString());
        assertTrue(planning.await(10, TimeUnit.SECONDS));
        // A real intervening settings batch invalidates the page's revision zero witness.
        var intervening = store.inspect();
        var updated = intervening.settings();
        updated.setChatEnabled(true);
        assertTrue(settings.applyInternal(updated, intervening.witness(),
            TestEngineContexts.internal()).response().success());
        assertEquals(1, store.inspect().witness().acceptedRevision());
        continuePlanning.countDown();
        var result = attempt.completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
        assertTrue(result.success(), result.toString());
        var committed = store.inspect();
        assertEquals(Boolean.TRUE, committed.settings().getRerankerEnabled());
        assertEquals(custom ? initial.getRerankerModelPath() : modelDirectory.toString(),
            committed.settings().getRerankerModelPath());
        assertEquals(initial.getRerankerRestoreSelection(),
            committed.settings().getRerankerRestoreSelection());
        assertEquals(selected, committed.queryRoles());
        assertEquals(2, committed.witness().acceptedRevision());
        assertTrue(committed.settings().getChatEnabled());
        assertArrayEquals(model, Files.readAllBytes(modelDirectory.resolve("model.onnx")));
      } finally {
        continuePlanning.countDown();
      }
    } finally {
      ConfigStore.restoreGlobal(published, prior);
    }
  }
}
