/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.settings.SettingsV2;
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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** Real installer completion and durable settings, with only native preparation omitted. */
final class PictureReadingInstallCompletionTest {
  @Test
  void consentedEnableTurnsPictureReadingOnAfterDownload(@TempDir Path home) throws Exception {
    try (var rig = new Rig(home)) {
      rig.start();
      var committed = rig.finish();
      assertEquals(Boolean.TRUE, committed.settings().getPictureReadingEnabled());
      assertEquals(committed.witness().acceptedRevision(),
          committed.settings().getPictureReadingPreferenceRevision());
    }
  }

  @Test
  void explicitOffAgainDuringDownloadWinsOverEarlierConsent(@TempDir Path home) throws Exception {
    try (var rig = new Rig(home)) {
      rig.start();
      // False -> false is deliberate: a value-only guard cannot detect this newer choice.
      rig.save("\"pictureReadingEnabled\":false,\"pictureReadingSwitchIntent\":true");
      var off = rig.store.inspect();
      assertTrue(off.settings().getPictureReadingPreferenceRevision() > 0);
      var committed = rig.finish();
      assertEquals(Boolean.FALSE, committed.settings().getPictureReadingEnabled(),
          "Completion must not override an explicit Off saved after consent");
      assertEquals(off.witness(), committed.witness(), "Completion must not save another choice");
      assertEquals(off.settings().getPictureReadingPreferenceRevision(),
          committed.settings().getPictureReadingPreferenceRevision());
    }
  }

  @Test
  void unrelatedSaveDuringDownloadPreservesTheSaveAndAllowsEnable(@TempDir Path home)
      throws Exception {
    try (var rig = new Rig(home)) {
      rig.start();
      rig.save("\"ui\":{\"theme\":\"dark\"},\"pictureReadingEnabled\":false");
      var unrelated = rig.store.inspect();
      assertTrue(unrelated.witness().acceptedRevision() > 0);
      assertEquals(0, unrelated.settings().getPictureReadingPreferenceRevision());
      var committed = rig.finish();
      assertEquals(Boolean.TRUE, committed.settings().getPictureReadingEnabled(),
          "An unrelated save must not invalidate the consented enable");
      assertEquals("dark", committed.settings().getTheme(), "Completion must use the latest settings");
      assertTrue(committed.witness().acceptedRevision() > unrelated.witness().acceptedRevision());
      assertEquals(committed.witness().acceptedRevision(),
          committed.settings().getPictureReadingPreferenceRevision());
    }
  }

  private static final class Rig implements AutoCloseable {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    final byte[] projector = "verified downloaded projector fixture".getBytes(StandardCharsets.UTF_8);
    final CountDownLatch downloading = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final HttpServer server;
    final Path home;
    final UiSettingsStore store;
    final ConfigStore prior;
    final ConfigStore published;
    final RuntimeIntentTestFixture fixture;
    final SettingsServiceImpl settings;
    final AiInstallService installer;
    CompletionStage<OperationResult> completion;

    Rig(Path home) throws Exception {
      this.home = home;
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/projector", exchange -> {
        downloading.countDown();
        try {
          if (!release.await(20, TimeUnit.SECONDS)) {
            throw new java.io.IOException("Download not released");
          }
          exchange.sendResponseHeaders(200, projector.length);
          exchange.getResponseBody().write(projector);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new java.io.IOException(interrupted);
        } finally { exchange.close(); }
      });
      server.start();
      String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(projector));
      // Isolate the supporting-file completion from native model/runtime installation. The real
      // planner, HTTP acquisition, hash verification, placement and completion all run.
      var chat = new ModelPackage("chat", "Picture reading", "Fixture", "chat", List.of(),
          List.of(new SupportingFile("mmproj.gguf", sha, projector.length,
              "http://127.0.0.1:" + server.getAddress().getPort() + "/projector")),
          0, null, null, "Apache-2.0", CapabilityTier.LLM);
      store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,
          home.resolve("settings.json"));
      var initial = new UiSettings();
      initial.setPictureReadingEnabled(false);
      initial.setChatEnabled(false);
      store.replacePrepared(store.prepare(initial, new SettingsWitness(0, null)));
      prior = ConfigStore.globalOrNull();
      published = new ConfigStore(ConfigStoreRebuilder.prepare(initial));
      ConfigStore.setGlobal(published);
      fixture = new RuntimeIntentTestFixture(home.resolve("intent"), store, published);
      settings = new SettingsServiceImpl(store, fixture.runner());
      installer = spy(new AiInstallService(null, store, null, null, home, null, settings));
      doReturn(new ModelRegistry(2, "local projector fixture", List.of(chat)))
          .when(installer).getManifest();
      doReturn(HardwareProfile.cpuOnly()).when(installer).detectHardwareProfile();
    }

    void start() throws Exception {
      assertFalse(Files.exists(home.resolve("models/chat/mmproj.gguf")));
      var attempt = new StartAiInstallHandler(() -> new BrainInstallServiceImpl(installer))
          .executeRecorded("{\"acceptTerms\":true,\"enablePictureReadingOnCompletion\":true}",
              null, TestEngineContexts.internal(), null);
      completion = attempt.completion();
      assertTrue(attempt.response().success(), attempt.response().toString());
      assertTrue(downloading.await(15, TimeUnit.SECONDS), "The real download must be in flight");
      assertEquals(Boolean.FALSE, store.inspect().settings().getPictureReadingEnabled(),
          "Consent must not enable picture reading before completion");
    }

    void save(String fields) {
      var patch = JSON.readValue("{" + fields + ",\"witness\":"
          + JSON.writeValueAsString(store.inspect().witness()) + ",\"operationKey\":\""
          + OperationKeys.generate(Clock.systemUTC()) + "\"}", SettingsV2.class);
      var result = settings.applyPublic(patch, null, TestEngineContexts.internal());
      assertTrue(result.response().success(), result.response().toString());
    }

    UiSettingsStore.Snapshot finish() throws Exception {
      release.countDown();
      var result = completion.toCompletableFuture().get(30, TimeUnit.SECONDS);
      assertTrue(result.success(), result.toString());
      assertEquals("completed", installer.getStatus().state);
      assertTrue(installer.getStatus().installedFully);
      assertArrayEquals(projector, Files.readAllBytes(home.resolve("models/chat/mmproj.gguf")));
      var reopened = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,
          home.resolve("settings.json")).inspect();
      assertEquals(store.inspect().witness(), reopened.witness());
      assertEquals(Boolean.FALSE, reopened.settings().getChatEnabled());
      return reopened;
    }

    @Override
    public void close() throws Exception {
      release.countDown();
      try {
        if (completion != null) completion.toCompletableFuture().get(30, TimeUnit.SECONDS);
      } finally {
        try { fixture.close(); }
        finally {
          server.stop(0);
          ConfigStore.restoreGlobal(published, prior);
        }
      }
    }
  }
}
