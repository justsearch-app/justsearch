/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.AiInstallException;
import io.justsearch.app.api.DownloadedFiles;
import io.justsearch.app.api.InstallPlanPreview;
import io.justsearch.app.api.OperationLeaseService;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.inference.chat.ChatActuator;
import io.justsearch.app.inference.chat.ChatHolds;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.lease.OperationLeaseServiceImpl;
import io.justsearch.app.services.runtimestate.RuntimeIntentTestFixture;
import io.justsearch.app.services.settings.SettingsServiceImpl;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.resolved.ConfigStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Repair round 2 of "Remove files" and "Download again" through the install owner and the real
 * settings owner (design D4): the accepted Download again is bound to the plan its consent showed;
 * the answer model's usage comes from the processes the answer runtime started, never from its
 * mode; and a removal holds upgrade admission.
 */
final class AiInstallServiceRemovalRepair2Test {
  /** AiInstallService.DOWNLOAD_CHANGED, as the page shows it. */
  private static final String DOWNLOAD_CHANGED = "The download changed - review it again";

  @TempDir Path tmp;
  private RuntimeIntentTestFixture fixture;
  private UiSettingsStore store;
  private AiInstallService owner;
  private RemovalModelsFixture files;

  @AfterEach
  void close() {
    if (fixture != null) fixture.close();
  }

  private void compose(Consumer<UiSettings> seed, OperationLeaseService leases) throws Exception {
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
    real.setOperationLeaseService(leases); // as the service composition binds it
    store.bindModelFileRemovalFence(real.removalFence());
    owner = spy(real);
    doReturn(HardwareProfile.cpuOnly()).when(owner).detectHardwareProfile();
    owner.setRemovalUsageProbe(id -> Optional.empty());
  }

  private DownloadedFiles.Part part(String id) {
    return owner.downloadedFiles().parts.stream().filter(p -> p.id.equals(id)).findFirst().orElseThrow();
  }

  private List<String> savedDeclines() {
    return store.inspect().settings().getDeclinedAiPackages();
  }

  // --- 3. Consent bound to the accepted plan --------------------------------------------------

  @Test
  void aDownloadAgainAcceptedAfterAnotherCallerChangedTheChoicesIsRefusedBeforeAnythingIsSaved()
      throws Exception {
    compose(s -> s.setDeclinedAiPackages(List.of("reranker")), OperationLeaseService.noOp());
    // Window A opens the consent for Download again; window B then declines the answer model.
    InstallPlanPreview shown = owner.previewInstallPlan(Set.of("reranker"));
    owner.setPackageDeclined("chat", true);
    String stateBefore = owner.getStatus().state;

    var refused = assertThrows(AiInstallException.class,
        () -> owner.startInstall(true, false, false, Set.of("reranker"), shown.planDigest));

    assertEquals(409, refused.httpStatus());
    assertEquals(DOWNLOAD_CHANGED, refused.getMessage());
    assertEquals(List.of("reranker", "chat"), savedDeclines(), "nothing was saved");
    assertFalse(owner.isInstallRunning(), "nothing was downloaded");
    assertEquals(stateBefore, owner.getStatus().state, "the install status is as it was");
  }

  @Test
  void anUnrelatedSaveInAnotherWindowAlsoAsksForReviewBecauseThePlanWasReadBeforeIt()
      throws Exception {
    compose(s -> s.setDeclinedAiPackages(List.of("reranker")), OperationLeaseService.noOp());
    InstallPlanPreview shown = owner.previewInstallPlan(Set.of("reranker"));
    var snapshot = store.inspect();
    UiSettings other = snapshot.settings();
    other.setTheme("dark");
    assertTrue(new SettingsServiceImpl(store, fixture.runner()).applyInternal(other, snapshot.witness(),
        io.justsearch.app.services.TestEngineContexts.internal()).response().success(),
        "precondition: another window saved a setting");

    var refused = assertThrows(AiInstallException.class,
        () -> owner.startInstall(true, false, false, Set.of("reranker"), shown.planDigest));

    assertEquals(DOWNLOAD_CHANGED, refused.getMessage());
    assertEquals(List.of("reranker"), savedDeclines());
    assertFalse(owner.isInstallRunning());
  }

  @Test
  void theReviewedPlanIsAcceptedAndItsDeclinesAreWithdrawnAtStart() throws Exception {
    compose(s -> s.setDeclinedAiPackages(List.of("reranker")), OperationLeaseService.noOp());
    InstallPlanPreview shown = owner.previewInstallPlan(Set.of("reranker"));
    owner.setPackageDeclined("chat", true);
    InstallPlanPreview reviewed = owner.previewInstallPlan(Set.of("reranker"));

    var attempt = owner.startInstall(true, false, false, Set.of("reranker"), reviewed.planDigest);
    try {
      assertFalse(reviewed.planDigest.isBlank(), "the consent names the plan it shows");
      assertFalse(reviewed.planDigest.equals(shown.planDigest), "the review showed the changed plan");
      assertEquals(List.of("chat"), savedDeclines(), "only the included part was withdrawn");
    } finally {
      owner.cancel();
      attempt.completion().toCompletableFuture().handle((status, failure) -> null)
          .get(60, TimeUnit.SECONDS);
    }
  }

  // --- 2. The answer model's usage comes from the processes, not from the mode ----------------

  @Test
  void aFailedAnswerServerStopOrASurvivingSelfTestRefusesTheRemovalThroughTheComposedOwner()
      throws Exception {
    compose(s -> {}, OperationLeaseService.noOp());
    files.writeChat();
    InferenceLifecycleManager runtime = mock(InferenceLifecycleManager.class);
    ChatActuator actuator = mock(ChatActuator.class);
    when(runtime.chatActuator()).thenReturn(actuator);
    when(actuator.serverRunning()).thenReturn(false); // the mode says offline
    ChatHolds holds = mock(ChatHolds.class);
    when(holds.openHolds()).thenReturn(List.of()); // the self-test hold is already closed
    owner.setRemovalUsageProbe(ModelFileUsage.probe(null, runtime, holds));

    for (String reason : List.of("The answer model is still loaded - try again in a moment",
        "The answer model is being tested - try again when it finishes")) {
      when(runtime.answerFilesInUse()).thenReturn(Optional.of(reason));
      DownloadedFiles.Part shown = part("chat");
      assertEquals(reason, shown.reason);

      var refused = assertThrows(AiInstallException.class,
          () -> owner.removeFiles("chat", shown.previewDigest));

      assertEquals(reason, refused.getMessage());
      assertArrayEquals(RemovalModelsFixture.CHAT, Files.readAllBytes(files.models.resolve("answer.gguf")));
      assertTrue(Files.exists(files.models.resolve("mmproj-F16.gguf")));
      assertTrue(savedDeclines().isEmpty());
    }

    when(runtime.answerFilesInUse()).thenReturn(Optional.empty());
    var removal = owner.removeFiles("chat", part("chat").previewDigest).completion()
        .toCompletableFuture().get(30, TimeUnit.SECONDS);
    assertEquals("removed", removal.state, "once the processes are confirmed released");
  }

  // --- 7. Upgrade admission -------------------------------------------------------------------

  @Test
  void theInstallOwnersRemovalIsRefusedWhileAnUpdateIsBeingPrepared() throws Exception {
    var leases = OperationLeaseServiceImpl.processLocal();
    compose(s -> {}, leases);
    files.writeReranker();
    var upgrade = leases.freezeAdmission("application upgrade");
    String digest = part("reranker").previewDigest;

    var refused = assertThrows(AiInstallException.class, () -> owner.removeFiles("reranker", digest));

    assertEquals(409, refused.httpStatus());
    assertEquals("An update is being prepared - try again after it", refused.getMessage());
    assertTrue(Files.exists(files.rerankerDir(0).resolve("model.onnx")));
    assertTrue(savedDeclines().isEmpty());

    leases.releaseAdmission(upgrade.preparationId());
    var removal = owner.removeFiles("reranker", part("reranker").previewDigest).completion()
        .toCompletableFuture().get(30, TimeUnit.SECONDS);
    assertEquals("removed", removal.state);
    assertTrue(leases.snapshot().activeLeases().isEmpty(), "the removal's lease ended with it");
  }
}
