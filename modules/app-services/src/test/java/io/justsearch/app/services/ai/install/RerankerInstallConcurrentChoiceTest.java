/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.settings.QueryRoleSelection;
import io.justsearch.app.api.settings.SettingsV2;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.braininstall.BrainInstallServiceImpl;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.registry.operations.handlers.StartAiInstallHandler;
import io.justsearch.app.services.runtimestate.RuntimeIntentTestFixture;
import io.justsearch.app.services.settings.SettingsComponentComposer;
import io.justsearch.app.services.settings.SettingsServiceImpl;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.model.*;
import io.justsearch.configuration.resolved.ConfigStore;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

final class RerankerInstallConcurrentChoiceTest {
  @ParameterizedTest(name = "concurrentOffDuringDownloadKeepsTheNewerChoice({0})")
  @ValueSource(strings = {"on", "absent", "off", "off-on-off"})
  void concurrentOffDuringDownloadKeepsTheNewerChoice(String initialChoice,
      @TempDir Path home) throws Exception {
    runDownload(initialChoice, true, home);
  }

  @org.junit.jupiter.api.Test
  void unrelatedSettingsWriteDuringDownloadStillAllowsConsentedEnable(@TempDir Path home)
      throws Exception {
    runDownload("off", false, home);
  }

  private static void runDownload(String initialChoice, boolean changePreference, Path home)
      throws Exception {
    var store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,
        home.resolve("settings.json"));
    var initial = new UiSettings();
    initial.setRerankerEnabled("absent".equals(initialChoice) ? null : "on".equals(initialChoice));
    byte[] model = "verified downloaded reranker fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(model));
    var downloading = new CountDownLatch(1);
    var finishDownload = new CountDownLatch(1);
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/model", exchange -> {
      downloading.countDown();
      try {
        if (!finishDownload.await(20, TimeUnit.SECONDS)) throw new java.io.IOException("Download not released");
        exchange.sendResponseHeaders(200, model.length);
        exchange.getResponseBody().write(model);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new java.io.IOException(interrupted);
      } finally { exchange.close(); }
    });
    server.start();
    var reranker = new ModelPackage("reranker", "Reranker", "Fixture", "reranker",
        List.of(new ModelVariant("model.onnx", ModelPrecision.FP32, ExecutionProvider.CPU,
            sha, model.length, "http://127.0.0.1:" + server.getAddress().getPort() + "/model")), List.of(),
        0, null, null, null, CapabilityTier.RETRIEVAL_ENRICHMENT);
    var modelDirectory = home.resolve("models").resolve(
        InstallPlanner.effectiveTargetDir(reranker, reranker.variants().get(0)));
    var modelFile = new io.justsearch.app.api.operations.CandidateIndexSelection.ModelFile(
        modelDirectory.resolve("model.onnx"), sha, model.length);
    var selected = new QueryRoleSelection(QueryRoleSelection.Role.selected("model.onnx", modelFile,
        new io.justsearch.app.api.operations.CandidateIndexSelection.ModelFile(
            modelDirectory.resolve("tokenizer.json"), "b".repeat(64), 2),
        ModelPrecision.FP32, ExecutionProvider.CPU), QueryRoleSelection.Role.disabled());
    var disabled = new QueryRoleSelection(QueryRoleSelection.Role.disabled(),
        QueryRoleSelection.Role.disabled());
    // Native preparation has its own Engine checks; these receipts let the real coordinator
    // publish and persist the selected/disabled role while transport is held at the HTTP boundary.
    SettingsComponentComposer components = (candidate, desired, affected) -> {
      var prepared = mock(SettingsComponentComposer.Prepared.class);
      if (affected.containsKey("encoders")) when(prepared.queryRoleSelection()).thenReturn(Optional.of(
          Boolean.TRUE.equals(candidate.getRerankerEnabled()) ? selected : disabled));
      doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; })
          .when(prepared).withOwnerLocks(any());
      return prepared;
    };
    store.replacePrepared(store.prepare(initial, new SettingsWitness(0, null),
        Boolean.TRUE.equals(initial.getRerankerEnabled()) ? selected : disabled));
    var prior = ConfigStore.globalOrNull();
    var published = new ConfigStore(ConfigStoreRebuilder.prepare(initial));
    ConfigStore.setGlobal(published);
    try (var fixture = new RuntimeIntentTestFixture(home.resolve("intent"), store, published, components)) {
      var settings = new SettingsServiceImpl(store, fixture.runner());
      var owner = spy(new AiInstallService(null, store, null, null, home, null, settings));
      doReturn(new ModelRegistry(2, "local transport fixture", List.of(reranker)))
          .when(owner).getManifest();
      doReturn(HardwareProfile.cpuOnly()).when(owner).detectHardwareProfile();
      var attempt = new StartAiInstallHandler(() -> new BrainInstallServiceImpl(owner)).executeRecorded(
          "{\"acceptTerms\":true,\"enableRerankerOnCompletion\":true}", null,
          TestEngineContexts.internal(), null);
      try {
        assertTrue(attempt.response().success(), attempt.response().toString());
        assertTrue(downloading.await(15, TimeUnit.SECONDS), "The real model download must be in flight");
        if (changePreference) {
          if ("off-on-off".equals(initialChoice)) saveChoice(settings, store, true);
          saveChoice(settings, store, false);
        } else {
          var snapshot = store.inspect();
          var updated = snapshot.settings();
          updated.setChatEnabled(true);
          assertTrue(settings.applyInternal(updated, snapshot.witness(),
              TestEngineContexts.internal()).response().success());
        }
        var newerChoice = store.inspect();
        finishDownload.countDown();
        var result = attempt.completion().toCompletableFuture().get(30, TimeUnit.SECONDS);
        assertTrue(result.success(), result.toString());
        var committed = store.inspect();
        assertEquals(!changePreference, committed.settings().getRerankerEnabled(),
            "Install completion must keep an Off saved during the download");
        assertEquals(changePreference ? disabled : selected, committed.queryRoles());
        if (changePreference) {
          assertTrue(result.message().contains("the newer choice was kept"), result.message());
          var reopened = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,
              home.resolve("settings.json"));
          assertEquals(Boolean.FALSE, reopened.inspect().settings().getRerankerEnabled());
        } else {
          assertTrue(committed.witness().acceptedRevision() > newerChoice.witness().acceptedRevision());
          assertTrue(committed.settings().getChatEnabled());
          assertEquals(modelDirectory.toString(), committed.settings().getRerankerModelPath());
        }
        assertArrayEquals(model, Files.readAllBytes(modelDirectory.resolve("model.onnx")));
      } finally {
        finishDownload.countDown();
        attempt.completion().handle((result, failure) -> null).toCompletableFuture()
            .get(30, TimeUnit.SECONDS);
      }
    } finally {
      finishDownload.countDown();
      server.stop(0);
      ConfigStore.restoreGlobal(published, prior);
    }
  }

  private static void saveChoice(SettingsServiceImpl settings, UiSettingsStore store, boolean enabled) {
    var json = JsonMapper.builder().build();
    var patch = json.readValue("{\"rerankerEnabled\":" + enabled + ",\"witness\":"
        + json.writeValueAsString(store.inspect().witness()) + ",\"operationKey\":\""
        + OperationKeys.generate(Clock.systemUTC()) + "\"}", SettingsV2.class);
    var result = settings.applyPublic(patch, null, TestEngineContexts.internal());
    assertTrue(result.response().success(), result.response().toString());
  }
}
