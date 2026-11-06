/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import io.justsearch.app.api.AiInstallStatus;
import io.justsearch.app.api.DownloadedFiles;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.runtimestate.RuntimeIntentTestFixture;
import io.justsearch.app.services.settings.SettingsServiceImpl;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.resolved.ConfigStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Design D4 repair round 1: after "Remove files" the install owner's retained package rows must
 * not keep saying "installed". The page trusts those rows; a stale one let a switch-on withdraw
 * the decline without consent. The rows here come from a real finished install run over a
 * temporary models folder (every file already on disk), not from a hand-made status.
 */
final class AiInstallServiceRemovalPresenceTest {

  @TempDir Path tmp;
  private RuntimeIntentTestFixture fixture;

  @AfterEach
  void close() {
    if (fixture != null) fixture.close();
  }

  @Test
  void aRemovedPartsRetainedInstalledRowNoLongerSaysInstalled() throws Exception {
    Path home = tmp.resolve("home");
    var files = new RemovalModelsFixture(home);
    var store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, home.resolve("settings.json"));
    UiSettings initial = new UiSettings();
    initial.setRerankerEnabled(false);
    initial.setChatEnabled(false);
    initial.setPictureReadingEnabled(false);
    store.replacePrepared(store.prepare(initial, new SettingsWitness(0, null)));
    fixture = new RuntimeIntentTestFixture(tmp.resolve("intent"), store,
        new ConfigStore(ConfigStoreRebuilder.prepare(initial)));
    var real = new AiInstallService(null, store, null, null, home, null,
        new SettingsServiceImpl(store, fixture.runner()));
    real.setManifestForTest(files.registry);
    store.bindModelFileRemovalFence(real.removalFence());
    AiInstallService owner = spy(real);
    doReturn(HardwareProfile.cpuOnly()).when(owner).detectHardwareProfile();
    owner.setRemovalUsageProbe(id -> Optional.empty());
    // Everything the plan wants is already on disk, so the run only records it as installed.
    Path embed = files.models.resolve(io.justsearch.configuration.model.InstallPlanner
        .effectiveTargetDir(files.pkg("embedding"), files.pkg("embedding").variants().get(0)));
    Files.createDirectories(embed);
    Files.write(embed.resolve("model.onnx"), RemovalModelsFixture.EMBED);
    files.writeReranker();
    files.writeChat();
    owner.startInstall(true).completion().toCompletableFuture().get(60, TimeUnit.SECONDS);
    assertEquals("installed", row(owner.getStatus(), "reranker").state,
        "precondition: the finished run retains the part as installed");
    DownloadedFiles.Part reranker = owner.downloadedFiles().parts.stream()
        .filter(p -> p.id.equals("reranker")).findFirst().orElseThrow();
    assertEquals(null, reranker.reason, "precondition: the part can be removed");

    DownloadedFiles.Removal removal = owner.removeFiles("reranker", reranker.previewDigest)
        .completion().toCompletableFuture().get(30, TimeUnit.SECONDS);

    assertEquals("removed", removal.state, removal.message);
    AiInstallStatus.PackageStatus after = row(owner.getStatus(), "reranker");
    assertNotEquals("installed", after.state, "its files are gone; the row must not say installed");
    assertEquals("installed", row(owner.getStatus(), "chat").state, "other parts keep their rows");
  }

  private static AiInstallStatus.PackageStatus row(AiInstallStatus status, String id) {
    return status.packages.stream().filter(p -> id.equals(p.packageId)).findFirst()
        .orElseThrow(() -> new AssertionError("no row for " + id + " in "
            + status.packages.stream().map(p -> p.packageId + "=" + p.state).toList()));
  }
}
