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
import tools.jackson.databind.json.JsonMapper;

/**
 * The real installer, start operation, HTTP acquisition and durable settings owner, with answers
 * off and the answer model's file missing; the download is held until {@link #finish} releases it.
 * Only native preparation is omitted (the answer model is a single verified file).
 */
final class AnswersInstallRig implements AutoCloseable {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  final byte[] model = "verified downloaded answer model fixture".getBytes(StandardCharsets.UTF_8);
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

  AnswersInstallRig(Path home) throws Exception {
    this(home, null);
  }

  /** As above, with the settings owner's component preparation supplied by the test. */
  AnswersInstallRig(Path home, io.justsearch.app.services.settings.SettingsComponentComposer components)
      throws Exception {
    this.home = home;
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/answer-model", exchange -> {
      downloading.countDown();
      try {
        if (!release.await(20, TimeUnit.SECONDS)) {
          throw new java.io.IOException("Download not released");
        }
        exchange.sendResponseHeaders(200, model.length);
        exchange.getResponseBody().write(model);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new java.io.IOException(interrupted);
      } finally { exchange.close(); }
    });
    server.start();
    String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(model));
    var chat = new ModelPackage("chat", "Answer model", "Fixture", "chat", List.of(),
        List.of(new SupportingFile("answer.gguf", sha, model.length,
            "http://127.0.0.1:" + server.getAddress().getPort() + "/answer-model")),
        0, null, null, "Apache-2.0", CapabilityTier.LLM);
    store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,
        home.resolve("settings.json"));
    var initial = new UiSettings();
    initial.setChatEnabled(false);
    initial.setPictureReadingEnabled(false);
    store.replacePrepared(store.prepare(initial, new SettingsWitness(0, null)));
    prior = ConfigStore.globalOrNull();
    published = new ConfigStore(ConfigStoreRebuilder.prepare(initial));
    ConfigStore.setGlobal(published);
    fixture = components == null
        ? new RuntimeIntentTestFixture(home.resolve("intent"), store, published)
        : new RuntimeIntentTestFixture(home.resolve("intent"), store, published, components);
    settings = new SettingsServiceImpl(store, fixture.runner());
    installer = spy(new AiInstallService(null, store, null, null, home, null, settings));
    doReturn(new ModelRegistry(2, "local answer model fixture", List.of(chat)))
        .when(installer).getManifest();
    doReturn(HardwareProfile.cpuOnly()).when(installer).detectHardwareProfile();
  }

  /** Starts the consented install through the real operation and waits until it downloads. */
  void start(String argumentsJson) throws Exception {
    assertFalse(Files.exists(home.resolve("models/chat/answer.gguf")));
    var attempt = new StartAiInstallHandler(() -> new BrainInstallServiceImpl(installer))
        .executeRecorded(argumentsJson, null, TestEngineContexts.internal(), null);
    completion = attempt.completion();
    assertTrue(attempt.response().success(), attempt.response().toString());
    assertTrue(downloading.await(15, TimeUnit.SECONDS), "The real download must be in flight");
  }

  /** A public settings save during the download, against the current witness. */
  void save(String fields) {
    var patch = JSON.readValue("{" + fields + ",\"witness\":"
        + JSON.writeValueAsString(store.inspect().witness()) + ",\"operationKey\":\""
        + OperationKeys.generate(Clock.systemUTC()) + "\"}", SettingsV2.class);
    var result = settings.applyPublic(patch, null, TestEngineContexts.internal());
    assertTrue(result.response().success(), result.response().toString());
  }

  /** Releases the download, waits for the install to complete and rereads the saved settings. */
  UiSettingsStore.Snapshot finish() throws Exception {
    release.countDown();
    var result = completion.toCompletableFuture().get(30, TimeUnit.SECONDS);
    assertTrue(result.success(), result.toString());
    assertEquals("completed", installer.getStatus().state, installer.getStatus().message);
    assertArrayEquals(model, Files.readAllBytes(home.resolve("models/chat/answer.gguf")));
    var reopened = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,
        home.resolve("settings.json")).inspect();
    assertEquals(store.inspect().witness(), reopened.witness());
    return reopened;
  }

  @Override
  public void close() throws Exception {
    release.countDown();
    try {
      // Waits for the run to end; a test asserts its outcome itself (a cancelled run fails it).
      if (completion != null) {
        completion.toCompletableFuture().handle((result, failure) -> result).get(30, TimeUnit.SECONDS);
      }
    } finally {
      try { fixture.close(); }
      finally {
        server.stop(0);
        ConfigStore.restoreGlobal(published, prior);
      }
    }
  }
}
