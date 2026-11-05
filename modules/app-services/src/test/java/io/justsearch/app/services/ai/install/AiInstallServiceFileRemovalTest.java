/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import io.justsearch.app.api.AiInstallException;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.api.DownloadedFiles;
import io.justsearch.app.api.InstallPlanPreview;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.SettingsCommitOwner;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.runtimestate.RuntimeIntentTestFixture;
import io.justsearch.app.services.settings.SettingsServiceImpl;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.model.InstallPlanner;
import io.justsearch.configuration.resolved.ConfigStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * "Remove files" and "Download again" through the install owner and the real settings owner
 * (design D4): the decline is recorded through the settings owner, the removal lock refuses a
 * switch-on, an undecline and an install in either order, and Download again changes the saved
 * choice only when the accepted install starts.
 */
final class AiInstallServiceFileRemovalTest {

  @TempDir Path tmp;
  private RuntimeIntentTestFixture fixture;
  private UiSettingsStore store;
  private AiInstallService owner;
  private RemovalModelsFixture files;

  @AfterEach
  void close() {
    if (fixture != null) fixture.close();
  }

  private void compose(java.util.function.Consumer<UiSettings> seed) throws Exception {
    Path home = tmp.resolve("home");
    files = new RemovalModelsFixture(home);
    store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, home.resolve("settings.json"));
    UiSettings initial = new UiSettings();
    initial.setRerankerEnabled(false);
    initial.setChatEnabled(false);
    initial.setPictureReadingEnabled(false);
    seed.accept(initial);
    store.replacePrepared(store.prepare(initial, new SettingsWitness(0, null)));
    fixture = new RuntimeIntentTestFixture(tmp.resolve("intent"), store,
        new ConfigStore(ConfigStoreRebuilder.prepare(initial)));
    var real = new AiInstallService(null, store, null, null, home, null,
        new SettingsServiceImpl(store, fixture.runner()));
    real.setManifestForTest(files.registry);
    store.bindModelFileRemovalFence(real.removalFence()); // as the service composition does
    owner = spy(real);
    doReturn(HardwareProfile.cpuOnly()).when(owner).detectHardwareProfile();
    owner.setRemovalUsageProbe(id -> Optional.empty());
  }

  private DownloadedFiles.Part part(String id) {
    return owner.downloadedFiles().parts.stream().filter(p -> p.id.equals(id)).findFirst().orElseThrow();
  }

  private DownloadedFiles.Removal remove(String id) throws Exception {
    return owner.removeFiles(id, part(id).previewDigest).completion().toCompletableFuture()
        .get(30, TimeUnit.SECONDS);
  }

  private List<String> savedDeclines() {
    return store.inspect().settings().getDeclinedAiPackages();
  }

  @Test
  void theDeclineIsRecordedThroughTheSettingsOwnerAndRepairDoesNotBringThePartBack() throws Exception {
    compose(s -> {});
    files.writeReranker();
    long revisionBefore = store.inspect().witness().acceptedRevision();

    DownloadedFiles.Removal removal = remove("reranker");

    assertEquals("removed", removal.state, removal.message);
    assertEquals(List.of("reranker"), savedDeclines());
    assertTrue(store.inspect().witness().acceptedRevision() > revisionBefore,
        "committed by the settings owner, not written around it");
    DownloadedFiles.Part after = part("reranker");
    assertEquals("not-downloaded", after.state);
    assertTrue(after.declined);
    // Repair re-runs the whole plan with the saved declines: nothing of the part is planned.
    var plan = InstallPlanner.plan(files.registry, HardwareProfile.cpuOnly(),
        io.justsearch.configuration.model.InstallIntent.FULL_DESKTOP, owner.declinedPackages(),
        files.models, files.home);
    assertTrue(plan.downloads().stream().noneMatch(d -> d.packageId().equals("reranker")));
    InstallPlanPreview repairView = owner.previewInstallPlan();
    assertEquals("declined", repairView.components.stream()
        .filter(c -> c.id.equals("reranker")).findFirst().orElseThrow().state);
  }

  @Test
  void aSwitchedOnPartIsRefusedWithAPlainReasonAndNothingChanges() throws Exception {
    compose(s -> s.setRerankerEnabled(true));
    var paths = files.writeReranker();

    DownloadedFiles.Part preview = part("reranker");
    assertFalse(preview.removable);
    assertEquals("Switch Best match first off first", preview.reason);
    var refused = assertThrows(AiInstallException.class,
        () -> owner.removeFiles("reranker", preview.previewDigest));

    assertEquals(409, refused.httpStatus());
    assertEquals(ApiErrorCode.FILES_NOT_REMOVED, refused.errorCode());
    assertEquals("Switch Best match first off first", refused.getMessage());
    assertTrue(Files.exists(paths.get("fp32")));
    assertTrue(savedDeclines().isEmpty());
  }

  @Test
  void answersRequirePictureReadingOffToo() throws Exception {
    compose(s -> s.setPictureReadingEnabled(null));
    files.writeChat();

    assertEquals("Switch picture reading off first", part("chat").reason);
  }

  @Test
  void switchingOnUndecliningOrInstallingWhileRemovingIsRefused() throws Exception {
    compose(s -> {});
    files.writeChat();
    List<String> refusals = new ArrayList<>();
    owner.removalForTest().setCheckpointHookForTest(name -> {
      if (!name.equals("declined")) return;
      try {
        fixture.spec().setChatEnabled(true);
        refusals.add("chat switched on");
      } catch (SettingsCommitOwner.Refused refused) {
        refusals.add(refused.response().errorCode().orElse("?"));
      }
      try {
        owner.setPackageDeclined("chat", false);
        refusals.add("undeclined");
      } catch (AiInstallException refused) {
        refusals.add(refused.getMessage().contains(ModelFileRemovalFence.REMOVING) ? "undecline refused" : refused.getMessage());
      }
      try {
        var snapshot = store.inspect();
        UiSettings other = snapshot.settings();
        other.setLlmModelPath(files.models.resolve("another.gguf").toString());
        var result = new SettingsServiceImpl(store, fixture.runner()).applyInternal(other, snapshot.witness(),
            io.justsearch.app.services.intent.EngineProvenance.internal("test",
                io.justsearch.core.context.EngineContext.Survival.INTERACTIVE,
                io.justsearch.core.context.EngineContext.Urgency.FOREGROUND));
        refusals.add(result.response().success() ? "model path changed"
            : result.response().errorCode().orElse("?"));
      } catch (SettingsCommitOwner.Refused refused) {
        refusals.add(refused.response().errorCode().orElse("?"));
      }
      try {
        owner.startInstall(true);
        refusals.add("install started");
      } catch (AiInstallException refused) {
        refusals.add(refused.errorCode().name());
      }
      try {
        owner.repair(true);
        refusals.add("repair started");
      } catch (AiInstallException refused) {
        refusals.add(refused.errorCode().name());
      }
    });

    DownloadedFiles.Removal removal = remove("chat");

    assertEquals(List.of("MODEL_FILES_REMOVING", "undecline refused", "MODEL_FILES_REMOVING",
        "MODEL_FILES_REMOVING", "MODEL_FILES_REMOVING"), refusals);
    assertEquals("removed", removal.state);
    assertFalse(Boolean.TRUE.equals(store.inspect().settings().getChatEnabled()), "still off");
    assertEquals(List.of("chat"), savedDeclines());
    assertFalse(owner.isInstallRunning());
  }

  @Test
  void aSwitchOnThatLandedFirstRefusesTheRemoval() throws Exception {
    compose(s -> {});
    files.writeChat();
    String digest = part("chat").previewDigest;

    fixture.spec().setChatEnabled(true);
    var refused = assertThrows(AiInstallException.class, () -> owner.removeFiles("chat", digest));

    assertEquals("Switch answers off first", refused.getMessage());
    assertTrue(Files.exists(files.models.resolve("answer.gguf")));
    assertTrue(savedDeclines().isEmpty());
  }

  @Test
  void anOpenInstalledModelActivationRefusesTheRemovalAndDeletesNothing() throws Exception {
    compose(s -> {});
    files.writeChat();
    String digest = part("chat").previewDigest;
    // Accepted, running, or continuing after a restart: the recorded-ingestion owner says so.
    owner.setInstallerActivationOpen(() -> true);

    assertEquals("Downloaded models are being activated - try again when it finishes", part("chat").reason);
    var refused = assertThrows(AiInstallException.class, () -> owner.removeFiles("chat", digest));

    assertEquals("Downloaded models are being activated - try again when it finishes", refused.getMessage());
    assertTrue(Files.exists(files.models.resolve("answer.gguf")));
    assertTrue(Files.exists(files.models.resolve("mmproj-F16.gguf")));
    assertTrue(savedDeclines().isEmpty());

    owner.setInstallerActivationOpen(() -> false);
    assertEquals("removed", remove("chat").state, "once the activation has finished the removal runs");
  }

  @Test
  void aRunningDownloadRefusesTheRemovalAndARemovalRefusesTheDownload() throws Exception {
    compose(s -> {});
    files.writeChat();
    String digest = part("chat").previewDigest;

    try (var _ = owner.removalFence().enter("A download or Repair")) {
      var refused = assertThrows(AiInstallException.class, () -> owner.removeFiles("chat", digest));
      assertEquals("A download or Repair is running - try again when it finishes", refused.getMessage());
    }
    try (var _ = owner.removalFence().begin("chat")) {
      var refused = assertThrows(AiInstallException.class, () -> owner.startInstall(true));
      assertEquals(ApiErrorCode.MODEL_FILES_REMOVING, refused.errorCode());
      assertFalse(owner.isInstallRunning(), "the refused install never took the running guard");
    }
    assertTrue(Files.exists(files.models.resolve("answer.gguf")));
  }

  @Test
  void downloadAgainPreviewsWithoutChangingTheChoiceAndCancelLeavesRepairUnchanged() throws Exception {
    compose(s -> s.setDeclinedAiPackages(List.of("reranker")));
    // One retained file is still on disk: Download again fetches only what is missing.
    Files.createDirectories(files.rerankerDir(0));
    Files.write(files.rerankerDir(0).resolve("model.onnx"), RemovalModelsFixture.RERANK_FP32);

    InstallPlanPreview again = owner.previewInstallPlan(Set.of("reranker"));

    var reranker = again.components.stream().filter(c -> c.id.equals("reranker")).findFirst().orElseThrow();
    assertFalse(reranker.declined);
    assertEquals("to-download", reranker.state);
    assertEquals((long) RemovalModelsFixture.TOKENIZER.length + RemovalModelsFixture.CONFIG.length,
        reranker.downloadBytes, "only the missing supporting files");
    // The consent was closed: nothing was saved, and Repair still skips the part.
    assertEquals(List.of("reranker"), savedDeclines());
    assertEquals("declined", owner.previewInstallPlan().components.stream()
        .filter(c -> c.id.equals("reranker")).findFirst().orElseThrow().state);
  }

  @Test
  void anAcceptedDownloadAgainWithdrawsTheDeclineAtStart() throws Exception {
    compose(s -> s.setDeclinedAiPackages(List.of("reranker")));

    var attempt = owner.startInstall(true, false, false, Set.of("reranker"));
    try {
      assertTrue(savedDeclines().isEmpty(), "withdrawn inside the accepted start");
    } finally {
      owner.cancel();
      attempt.completion().toCompletableFuture().handle((status, failure) -> null)
          .get(60, TimeUnit.SECONDS);
    }
  }
}
