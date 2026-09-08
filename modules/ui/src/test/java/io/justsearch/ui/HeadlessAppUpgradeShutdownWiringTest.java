/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.engine.EngineShutdownSequence;
import io.justsearch.app.engine.ShutdownRequest;
import io.justsearch.app.engine.ShutdownRequest.Reason;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.ui.api.LocalApiServer;
import io.justsearch.ui.api.UpgradeShutdownBridge;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

@DisplayName("HeadlessApp upgrade shutdown production wiring")
final class HeadlessAppUpgradeShutdownWiringTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  @DisplayName("a wrong commit nonce never reaches the production request writer")
  void nonceMismatchWritesNoRequestFile(@TempDir Path dataDir) throws Exception {
    Path runtimeDir = Files.createDirectories(dataDir.resolve("runtime"));
    var bridge = new UpgradeShutdownBridge();
    bridge.install(HeadlessApp.upgradeShutdownRequestWriter(runtimeDir));
    LocalApiServer server =
        LocalApiServer.builder(
                new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY),
                dataDir.resolve("index"))
            .upgradeShutdownAction(bridge)
            .build();
    try {
      HttpClient client = HttpClient.newHttpClient();
      var prepared = JSON.readTree(post(client, server, "/api/upgrade/prepare", "{}").body());
      String body =
          "{\"schemaVersion\":1,\"preparationId\":\""
              + prepared.get("preparationId").asText()
              + "\",\"shutdownNonce\":\"wrong\"}";

      assertEquals(409, post(client, server, "/api/upgrade/commit-shutdown", body).statusCode());
      assertFalse(Files.exists(ShutdownRequest.pathIn(runtimeDir)));
    } finally {
      server.stop();
    }
  }

  @Test
  @DisplayName("a persistence failure precedes success and leaves the capability cancellable")
  void persistenceFailureReturnsNonSuccessAndLeavesCapabilityOpen(@TempDir Path dataDir)
      throws Exception {
    Path runtimeDir = dataDir.resolve("runtime");
    Files.writeString(runtimeDir, "blocks directory creation");
    var bridge = new UpgradeShutdownBridge();
    bridge.install(HeadlessApp.upgradeShutdownRequestWriter(runtimeDir));
    LocalApiServer server =
        LocalApiServer.builder(
                new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY),
                dataDir.resolve("index"))
            .upgradeShutdownAction(bridge)
            .build();
    try {
      HttpClient client = HttpClient.newHttpClient();
      var prepared = JSON.readTree(post(client, server, "/api/upgrade/prepare", "{}").body());

      assertEquals(
          503,
          post(client, server, "/api/upgrade/commit-shutdown", capabilityBody(prepared))
              .statusCode());
      assertEquals(
          200,
          post(client, server, "/api/upgrade/cancel", capabilityBody(prepared)).statusCode());
    } finally {
      server.stop();
    }
  }

  @Test
  @DisplayName("the production writer and dispatcher preserve upgrade receipt identifiers")
  void productionFactoriesCarryUpgradeRequestToReceipt(@TempDir Path dataDir) throws Exception {
    Path runtimeDir = Files.createDirectories(dataDir.resolve("runtime"));
    var bridge = new UpgradeShutdownBridge();
    bridge.install(HeadlessApp.upgradeShutdownRequestWriter(runtimeDir));
    LocalApiServer server =
        LocalApiServer.builder(
                new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY),
                dataDir.resolve("index"))
            .upgradeShutdownAction(bridge)
            .build();
    var sequence =
        new EngineShutdownSequence(
            dataDir,
            List.of(
                new EngineShutdownSequence.Step(
                    EngineShutdownSequence.INDEX_HALF_STEP, ignored -> "GRACEFUL")),
            ignored -> {});

    try {
      HttpClient client = HttpClient.newHttpClient();
      var prepared = JSON.readTree(post(client, server, "/api/upgrade/prepare", "{}").body());
      String preparationId = prepared.get("preparationId").asText();
      assertEquals(200, post(client, server, "/api/upgrade/commit-shutdown", capabilityBody(prepared)).statusCode());
      assertTrue(Files.isRegularFile(ShutdownRequest.pathIn(runtimeDir)));

      try (var watcher =
          HeadlessApp.startShutdownRequestWatcher(
              runtimeDir,
              HeadlessApp.shutdownRequestAcceptance(bridge),
              HeadlessApp.shutdownRequestDispatcher(sequence),
              20L,
              ignored -> {})) {
      Path receipt = dataDir.resolve("upgrade").resolve(EngineShutdownSequence.RECEIPT_FILE);
      assertTrue(waitForFile(receipt), "the production watcher must dispatch the written request");
      var body = JSON.readTree(Files.readString(receipt));
      assertEquals(preparationId, body.get("preparationId").asText());
        assertEquals(prepared.get("shutdownNonce").asText(), body.get("shutdownNonce").asText());
      }
    } finally {
      server.stop();
    }
  }

  @Test
  @DisplayName("a prepared upgrade must match the current frozen lease snapshot")
  void stalePreparationIsRefusedBeforeDispatch(@TempDir Path dataDir) throws Exception {
    Path runtimeDir = Files.createDirectories(dataDir.resolve("runtime"));
    var bridge = new UpgradeShutdownBridge();
    bridge.install(HeadlessApp.upgradeShutdownRequestWriter(runtimeDir));
    LocalApiServer server =
        LocalApiServer.builder(
                new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY),
                dataDir.resolve("index"))
            .upgradeShutdownAction(bridge)
            .build();
    var sequence = new EngineShutdownSequence(dataDir, List.of(), ignored -> {});

    try {
      HttpClient client = HttpClient.newHttpClient();
      post(client, server, "/api/upgrade/prepare", "{}");
      try (var watcher =
          HeadlessApp.startShutdownRequestWatcher(
              runtimeDir,
              HeadlessApp.shutdownRequestAcceptance(bridge),
              HeadlessApp.shutdownRequestDispatcher(sequence),
              20L,
              ignored -> {})) {
      HeadlessApp.upgradeShutdownRequestWriter(runtimeDir).shutdown("stale-preparation", "nonce-1");
      TimeUnit.MILLISECONDS.sleep(200);
      assertFalse(Files.exists(ShutdownRequest.pathIn(runtimeDir)));
      assertEquals(null, sequence.resultIfRun());
      }
    } finally {
      server.stop();
    }
  }

  @Test
  @DisplayName("direct wrong or missing nonce requests are refused before dispatch")
  void directInvalidNonceRequestsAreRefused(@TempDir Path dataDir) throws Exception {
    Path runtimeDir = Files.createDirectories(dataDir.resolve("runtime"));
    var bridge = new UpgradeShutdownBridge();
    bridge.install(HeadlessApp.upgradeShutdownRequestWriter(runtimeDir));
    LocalApiServer server =
        LocalApiServer.builder(
                new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY),
                dataDir.resolve("index"))
            .upgradeShutdownAction(bridge)
            .build();
    var sequence = new EngineShutdownSequence(dataDir, List.of(), ignored -> {});
    try {
      HttpClient client = HttpClient.newHttpClient();
      var prepared = JSON.readTree(post(client, server, "/api/upgrade/prepare", "{}").body());
      String preparationId = prepared.get("preparationId").asText();
      assertEquals(
          200,
          post(client, server, "/api/upgrade/commit-shutdown", capabilityBody(prepared))
              .statusCode());
      Files.delete(ShutdownRequest.pathIn(runtimeDir));
      try (var watcher =
          HeadlessApp.startShutdownRequestWatcher(
              runtimeDir,
              HeadlessApp.shutdownRequestAcceptance(bridge),
              HeadlessApp.shutdownRequestDispatcher(sequence),
              20L,
              ignored -> {})) {
        for (String nonce : new String[] {"wrong", null}) {
          new ShutdownRequest(
                  Reason.UPGRADE,
                  Long.MAX_VALUE,
                  nonce,
                  "direct-file-test",
                  preparationId)
              .writeTo(runtimeDir);
          assertTrue(waitForAbsent(ShutdownRequest.pathIn(runtimeDir)));
          assertFalse(watcher.hasFired());
        }
      }
      assertEquals(null, sequence.resultIfRun());
    } finally {
      server.stop();
    }
  }

  @Test
  @DisplayName("an unavailable bridge verifier fails closed")
  void missingVerifierRefusesPreparedRequest() {
    var bridge = new UpgradeShutdownBridge();
    var request =
        new ShutdownRequest(Reason.UPGRADE, Long.MAX_VALUE, "nonce", "test", "prep");

    assertEquals(
        io.justsearch.app.engine.ShutdownRequestWatcher.Acceptance.REFUSE,
        HeadlessApp.shutdownRequestAcceptance(bridge).apply(request));
  }

  @Test
  @DisplayName("plain and unprepared supervisor shutdowns never forge an upgrade receipt")
  void unpreparedShutdownsUseThePlainProductionDispatchPath(@TempDir Path tempDir) {
    for (Reason reason : List.of(Reason.QUIT, Reason.UPGRADE)) {
      Path dataDir = tempDir.resolve(reason.wire());
      var sequence = new EngineShutdownSequence(dataDir, List.of(), ignored -> {});
      var request =
          new ShutdownRequest(reason, Long.MAX_VALUE, null, "supervisor", null);

      HeadlessApp.shutdownRequestDispatcher(sequence).accept(request);

      assertTrue(sequence.resultIfRun() != null);
      assertFalse(
          Files.exists(dataDir.resolve("upgrade").resolve(EngineShutdownSequence.RECEIPT_FILE)));
    }
  }

  private static HttpResponse<String> post(
      HttpClient client, LocalApiServer server, String path, String body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + path))
            .timeout(Duration.ofSeconds(3))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static boolean waitForFile(Path path) throws Exception {
    for (int i = 0; i < 100; i++) {
      if (Files.isRegularFile(path)) {
        return true;
      }
      TimeUnit.MILLISECONDS.sleep(20);
    }
    return false;
  }

  private static boolean waitForAbsent(Path path) throws Exception {
    for (int i = 0; i < 100; i++) {
      if (!Files.exists(path)) {
        return true;
      }
      TimeUnit.MILLISECONDS.sleep(20);
    }
    return false;
  }

  private static String capabilityBody(tools.jackson.databind.JsonNode preparation) {
    return "{\"schemaVersion\":1,\"preparationId\":\""
        + preparation.get("preparationId").asText()
        + "\",\"shutdownNonce\":\""
        + preparation.get("shutdownNonce").asText()
        + "\"}";
  }

}
