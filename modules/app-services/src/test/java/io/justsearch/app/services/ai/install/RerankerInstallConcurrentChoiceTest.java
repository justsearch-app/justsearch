/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import io.justsearch.agent.api.registry.OperationResult;
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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

final class RerankerInstallConcurrentChoiceTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @ParameterizedTest(name = "concurrentOffDuringDownloadKeepsTheNewerChoice({0})")
  @ValueSource(strings = {"on", "absent", "off", "off-on-off"})
  void concurrentOffDuringDownloadKeepsTheNewerChoice(String initialChoice,
      @TempDir Path home) throws Exception {
    try (var rig = new Rig(home, choice(initialChoice))) {
      var attempt = rig.startConsentedInstall();
      if ("off-on-off".equals(initialChoice)) rig.switchTo(true);
      rig.switchTo(false);
      var newerChoice = rig.store.inspect();
      var result = rig.finish(attempt);
      var committed = rig.store.inspect();
      assertEquals(Boolean.FALSE, committed.settings().getRerankerEnabled(),
          "Install completion must keep an Off saved during the download");
      assertEquals(rig.disabled, committed.queryRoles());
      assertTrue(result.message().contains("the newer choice was kept"), result.message());
      var reopened = rig.reopenedStore().inspect();
      assertEquals(Boolean.FALSE, reopened.settings().getRerankerEnabled());
      assertEquals(newerChoice.witness().acceptedRevision(),
          reopened.settings().getRerankerPreferenceRevision(),
          "The persisted choice revision is the revision that accepted the Off");
      rig.assertModelBytesKept();
    }
  }

  @Test
  void unrelatedSettingsWriteDuringDownloadStillAllowsConsentedEnable(@TempDir Path home)
      throws Exception {
    try (var rig = new Rig(home, false)) {
      var attempt = rig.startConsentedInstall();
      var snapshot = rig.store.inspect();
      var updated = snapshot.settings();
      updated.setChatEnabled(true);
      assertTrue(rig.settings.applyInternal(updated, snapshot.witness(),
          TestEngineContexts.internal()).response().success());
      var unrelated = rig.store.inspect();
      assertTrue(rig.finish(attempt).success());
      var committed = rig.store.inspect();
      assertTrue(committed.witness().acceptedRevision() > unrelated.witness().acceptedRevision());
      assertTrue(committed.settings().getChatEnabled());
      rig.assertEnabledWithDownloadedModel(committed);
    }
  }

  @Test
  void publicSaveEchoingTheUnchangedChoiceDuringDownloadStillAllowsConsentedEnable(
      @TempDir Path home) throws Exception {
    try (var rig = new Rig(home, false)) {
      var attempt = rig.startConsentedInstall();
      // A theme save from another window echoes the saved false without the switch's intent.
      rig.publicSave("{\"ui\":{\"theme\":\"dark\"},\"rerankerEnabled\":false");
      var echoed = rig.store.inspect();
      assertEquals("dark", echoed.settings().getTheme());
      assertEquals(0, echoed.settings().getRerankerPreferenceRevision(),
          "An echoed unchanged value is not a new choice");
      var result = rig.finish(attempt);
      assertTrue(result.success(), result.toString());
      assertFalse(result.message().contains("the newer choice was kept"), result.message());
      rig.assertEnabledWithDownloadedModel(rig.store.inspect());
    }
  }

  /** A fenced consented install is followed by Repair, additions or a second consent. */
  @ParameterizedTest(name = "followUpInstallAfterAFencedEnable({0})")
  @ValueSource(strings = {"repair", "additions", "consent-again"})
  void followUpInstallAfterAFencedEnable(String followUp, @TempDir Path home) throws Exception {
    try (var rig = new Rig(home, false)) {
      var first = rig.startConsentedInstall();
      rig.switchTo(false);
      var offChoice = rig.store.inspect();
      assertTrue(rig.finish(first).message().contains("the newer choice was kept"));
      if ("additions".equals(followUp)) rig.packages.add(rig.citationScorer());
      var second = switch (followUp) {
        case "repair" -> rig.startRepair();
        case "additions" -> rig.startPlainInstall();
        default -> rig.startConsentedInstall();
      };
      var result = rig.finish(second);
      assertTrue(result.success(), result.toString());
      var committed = rig.store.inspect();
      if ("consent-again".equals(followUp)) {
        // The second consent takes its own snapshot; the first one's fence does not linger.
        rig.assertEnabledWithDownloadedModel(committed);
        assertTrue(committed.settings().getRerankerPreferenceRevision()
            > offChoice.witness().acceptedRevision());
      } else {
        assertEquals(Boolean.FALSE, committed.settings().getRerankerEnabled(),
            "A plain install after a fenced enable must not switch Best match first on");
        assertEquals(rig.disabled, committed.queryRoles());
        assertEquals(offChoice.witness().acceptedRevision(),
            committed.settings().getRerankerPreferenceRevision());
        if ("additions".equals(followUp)) {
          assertEquals(rig.citationDirectory().toString(),
              committed.settings().getCitationScorerModelPath());
        }
      }
      rig.assertModelBytesKept();
    }
  }

  /** Named limit: the install-start snapshot is memory-only, so a restart drops the switch-on. */
  @Test
  void interruptedConsentedInstallDoesNotEnableAfterRestart(@TempDir Path home) throws Exception {
    var rig = new Rig(home, false);
    try {
      var first = rig.startConsentedInstall();
      rig.owner.cancel();
      rig.releaseDownload();
      first.handle((result, failure) -> null).toCompletableFuture().get(30, TimeUnit.SECONDS);
      assertEquals(Boolean.FALSE, rig.store.inspect().settings().getRerankerEnabled());
      rig = rig.restart();
      var resumed = rig.startRepair();
      var result = rig.finish(resumed);
      assertTrue(result.success(), result.toString());
      var committed = rig.reopenedStore().inspect();
      assertEquals(Boolean.FALSE, committed.settings().getRerankerEnabled(),
          "Completion after a restart must not enable from a consent the Engine no longer holds");
      assertEquals(rig.disabled, committed.queryRoles());
      rig.assertModelBytesKept();
    } finally {
      rig.close();
    }
  }

  private record Outcome(boolean success, String message, String detail) {
    @Override public String toString() { return detail; }
  }

  private static Boolean choice(String initialChoice) {
    return "absent".equals(initialChoice) ? null : "on".equals(initialChoice);
  }

  /** A real settings coordinator and installer over a local HTTP model source. */
  private static final class Rig implements AutoCloseable {
    final Path home;
    final byte[] model = "verified downloaded reranker fixture".getBytes(
        java.nio.charset.StandardCharsets.UTF_8);
    final byte[] citationModel = "verified citation scorer fixture".getBytes(
        java.nio.charset.StandardCharsets.UTF_8);
    final CountDownLatch downloading;
    final CountDownLatch finishDownload;
    final HttpServer server;
    final ModelPackage reranker;
    final Path modelDirectory;
    final QueryRoleSelection selected;
    final QueryRoleSelection disabled = new QueryRoleSelection(QueryRoleSelection.Role.disabled(),
        QueryRoleSelection.Role.disabled());
    final List<ModelPackage> packages;
    final ConfigStore prior;
    final ConfigStore published;
    final UiSettingsStore store;
    final RuntimeIntentTestFixture fixture;
    final SettingsServiceImpl settings;
    final AiInstallService owner;

    Rig(Path home, Boolean initialChoice) throws Exception {
      this(home, initialChoice, null);
    }

    private Rig(Path home, Boolean initialChoice, Rig previous) throws Exception {
      this.home = home;
      if (previous == null) {
        downloading = new CountDownLatch(1);
        finishDownload = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/model", exchange -> {
          downloading.countDown();
          try {
            if (!finishDownload.await(20, TimeUnit.SECONDS)) {
              throw new java.io.IOException("Download not released");
            }
            exchange.sendResponseHeaders(200, model.length);
            exchange.getResponseBody().write(model);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new java.io.IOException(interrupted);
          } finally { exchange.close(); }
        });
        server.createContext("/citation", exchange -> {
          try {
            exchange.sendResponseHeaders(200, citationModel.length);
            exchange.getResponseBody().write(citationModel);
          } finally { exchange.close(); }
        });
        server.start();
        reranker = new ModelPackage("reranker", "Reranker", "Fixture", "reranker",
            List.of(new ModelVariant("model.onnx", ModelPrecision.FP32, ExecutionProvider.CPU,
                sha(model), model.length, url("/model"))), List.of(),
            0, null, null, null, CapabilityTier.RETRIEVAL_ENRICHMENT);
        packages = new ArrayList<>(List.of(reranker));
        prior = ConfigStore.globalOrNull();
      } else {
        downloading = previous.downloading;
        finishDownload = previous.finishDownload;
        server = previous.server;
        reranker = previous.reranker;
        packages = previous.packages;
        prior = previous.prior;
      }
      modelDirectory = home.resolve("models").resolve(
          InstallPlanner.effectiveTargetDir(reranker, reranker.variants().get(0)));
      var modelFile = new io.justsearch.app.api.operations.CandidateIndexSelection.ModelFile(
          modelDirectory.resolve("model.onnx"), sha(model), model.length);
      selected = new QueryRoleSelection(QueryRoleSelection.Role.selected("model.onnx", modelFile,
          new io.justsearch.app.api.operations.CandidateIndexSelection.ModelFile(
              modelDirectory.resolve("tokenizer.json"), "b".repeat(64), 2),
          ModelPrecision.FP32, ExecutionProvider.CPU), QueryRoleSelection.Role.disabled());
      store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,
          home.resolve("settings.json"));
      UiSettings initial;
      if (previous == null) {
        initial = new UiSettings();
        initial.setRerankerEnabled(initialChoice);
        store.replacePrepared(store.prepare(initial, new SettingsWitness(0, null),
            Boolean.TRUE.equals(initialChoice) ? selected : disabled));
      } else {
        initial = store.inspect().settings();
      }
      // Native preparation has its own Engine checks; these receipts let the real coordinator
      // publish and persist the selected/disabled role while transport is held at HTTP.
      SettingsComponentComposer components = (candidate, desired, affected) -> {
        var prepared = mock(SettingsComponentComposer.Prepared.class);
        if (affected.containsKey("encoders")) when(prepared.queryRoleSelection()).thenReturn(Optional.of(
            Boolean.TRUE.equals(candidate.getRerankerEnabled()) ? selected : disabled));
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; })
            .when(prepared).withOwnerLocks(any());
        return prepared;
      };
      published = new ConfigStore(ConfigStoreRebuilder.prepare(initial));
      ConfigStore.setGlobal(published);
      fixture = new RuntimeIntentTestFixture(home.resolve("intent"), store, published, components);
      settings = new SettingsServiceImpl(store, fixture.runner());
      owner = spy(new AiInstallService(null, store, null, null, home, null, settings));
      doAnswer(call -> new ModelRegistry(2, "local transport fixture", List.copyOf(packages)))
          .when(owner).getManifest();
      doReturn(HardwareProfile.cpuOnly()).when(owner).detectHardwareProfile();
    }

    /** A new Engine over the same files: no in-memory install state survives. */
    Rig restart() throws Exception {
      fixture.close();
      return new Rig(home, null, this);
    }

    String url(String path) {
      return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    ModelPackage citationScorer() {
      return new ModelPackage("citation-scorer", "Citation scorer", "Fixture", "citation",
          List.of(new ModelVariant("model.onnx", ModelPrecision.FP32, ExecutionProvider.CPU,
              sha(citationModel), citationModel.length, url("/citation"))), List.of(),
          0, null, null, null, CapabilityTier.RETRIEVAL_ENRICHMENT);
    }

    Path citationDirectory() {
      var citation = citationScorer();
      return home.resolve("models").resolve(
          InstallPlanner.effectiveTargetDir(citation, citation.variants().get(0)));
    }

    CompletionStage<?> startConsentedInstall() throws Exception {
      return start("{\"acceptTerms\":true,\"enableRerankerOnCompletion\":true}");
    }

    CompletionStage<?> startPlainInstall() throws Exception {
      return start("{\"acceptTerms\":true}");
    }

    CompletionStage<?> startRepair() {
      return owner.repair(true).completion();
    }

    private CompletionStage<?> start(String arguments) throws Exception {
      var attempt = new StartAiInstallHandler(() -> new BrainInstallServiceImpl(owner))
          .executeRecorded(arguments, null, TestEngineContexts.internal(), null);
      assertTrue(attempt.response().success(), attempt.response().toString());
      if (finishDownload.getCount() > 0) {
        assertTrue(downloading.await(15, TimeUnit.SECONDS), "The real model download must be in flight");
      }
      return attempt.completion();
    }

    void releaseDownload() {
      finishDownload.countDown();
    }

    Outcome finish(CompletionStage<?> completion) throws Exception {
      releaseDownload();
      Object result = completion.toCompletableFuture().get(30, TimeUnit.SECONDS);
      if (result instanceof OperationResult operation) {
        return new Outcome(operation.success(), operation.message(), operation.toString());
      }
      var status = (io.justsearch.app.api.AiInstallStatus) result;
      return new Outcome("completed".equals(status.state), status.message,
          status.state + ": " + status.message);
    }

    void switchTo(boolean enabled) {
      publicSave("{\"rerankerSwitchIntent\":true,\"rerankerEnabled\":" + enabled);
    }

    void publicSave(String fieldsWithoutClosingBrace) {
      var patch = JSON.readValue(fieldsWithoutClosingBrace + ",\"witness\":"
          + JSON.writeValueAsString(store.inspect().witness()) + ",\"operationKey\":\""
          + OperationKeys.generate(Clock.systemUTC()) + "\"}", SettingsV2.class);
      var result = settings.applyPublic(patch, null, TestEngineContexts.internal());
      assertTrue(result.response().success(), result.response().toString());
    }

    UiSettingsStore reopenedStore() {
      return new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,
          home.resolve("settings.json"));
    }

    void assertEnabledWithDownloadedModel(UiSettingsStore.Snapshot committed) throws Exception {
      assertEquals(Boolean.TRUE, committed.settings().getRerankerEnabled());
      assertEquals(selected, committed.queryRoles());
      assertEquals(modelDirectory.toString(), committed.settings().getRerankerModelPath());
      assertModelBytesKept();
    }

    void assertModelBytesKept() throws Exception {
      assertArrayEquals(model, Files.readAllBytes(modelDirectory.resolve("model.onnx")));
    }

    @Override
    public void close() throws Exception {
      finishDownload.countDown();
      try {
        fixture.close();
      } finally {
        server.stop(0);
        ConfigStore.restoreGlobal(published, prior);
      }
    }

    private static String sha(byte[] bytes) {
      try {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      } catch (java.security.NoSuchAlgorithmException impossible) {
        throw new AssertionError(impossible);
      }
    }
  }
}
