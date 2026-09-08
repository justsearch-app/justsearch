/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static io.justsearch.ui.HeadlessAppShutdownWiringTest.writeRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

@DisplayName("HeadlessApp upgrade shutdown production wiring")
final class HeadlessAppUpgradeShutdownWiringTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void wrongNonceAndStalePreparationNeverDispatch(@TempDir Path dataDir) throws Exception {
    var sequence = new EngineShutdownSequence(dataDir, List.of(), ignored -> {});
    var bridge = new UpgradeShutdownBridge();
    bridge.install(sequence::runAndExitWithReceipt);
    LocalApiServer server = server(dataDir, bridge);
    try {
      HttpClient client = HttpClient.newHttpClient();
      var prepared = JSON.readTree(post(client, server, "/api/upgrade/prepare", "{}").body());
      for (String field : List.of("preparationId", "shutdownNonce")) {
        var wrong = JSON.readTree(capabilityBody(prepared)).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) wrong).put(field, "wrong");
        assertEquals(409, post(client, server, "/api/upgrade/commit-shutdown", wrong.toString()).statusCode());
        assertNull(sequence.resultIfRun());
        assertFalse(Files.exists(ShutdownRequest.pathIn(dataDir.resolve("runtime"))));
      }
    } finally {
      server.stop();
    }
  }

  @Test
  void unboundActionReturnsNonSuccessAndLeavesCapabilityCancellable(@TempDir Path dataDir)
      throws Exception {
    var bridge = new UpgradeShutdownBridge();
    LocalApiServer server = server(dataDir, bridge);
    try {
      HttpClient client = HttpClient.newHttpClient();
      var prepared = JSON.readTree(post(client, server, "/api/upgrade/prepare", "{}").body());
      var refused = post(client, server, "/api/upgrade/commit-shutdown", capabilityBody(prepared));
      assertEquals(503, refused.statusCode());
      assertEquals("UPGRADE_SHUTDOWN_NOT_READY", JSON.readTree(refused.body()).get("errorCode").asText());
      assertEquals(200, post(client, server, "/api/upgrade/cancel", capabilityBody(prepared)).statusCode());
    } finally {
      server.stop();
    }
  }

  @Test
  void acknowledgedLocalActionWritesTheNonceBoundReceiptWithoutARequestFile(@TempDir Path dataDir)
      throws Exception {
    Path runtimeDir = Files.createDirectories(dataDir.resolve("runtime"));
    var bridge = new UpgradeShutdownBridge();
    var exited = new CountDownLatch(1);
    var sequence = new EngineShutdownSequence(dataDir,
        List.of(new EngineShutdownSequence.Step(EngineShutdownSequence.INDEX_HALF_STEP,
            ignored -> "GRACEFUL")), ignored -> exited.countDown());
    // This is the same late-bound method reference installed by the production composition root.
    bridge.install(sequence::runAndExitWithReceipt);
    LocalApiServer server = server(dataDir, bridge);
    try {
      HttpClient client = HttpClient.newHttpClient();
      var prepared = JSON.readTree(post(client, server, "/api/upgrade/prepare", "{}").body());
      var committed = post(client, server, "/api/upgrade/commit-shutdown", capabilityBody(prepared));
      assertEquals(200, committed.statusCode());
      assertTrue(JSON.readTree(committed.body()).get("shutdownAccepted").asBoolean());
      assertTrue(exited.await(2, TimeUnit.SECONDS));
      Path receipt = dataDir.resolve("upgrade").resolve(EngineShutdownSequence.RECEIPT_FILE);
      var body = JSON.readTree(Files.readString(receipt));
      assertEquals(prepared.get("preparationId").asText(), body.get("preparationId").asText());
      assertEquals(prepared.get("shutdownNonce").asText(), body.get("shutdownNonce").asText());
      assertFalse(Files.exists(ShutdownRequest.pathIn(runtimeDir)));
    } finally {
      server.stop();
    }
  }

  @Test
  void evenTheCurrentPreparedCapabilityCannotAuthorizeAFileRequest(@TempDir Path dataDir)
      throws Exception {
    Path runtimeDir = Files.createDirectories(dataDir.resolve("runtime"));
    var sequence = new EngineShutdownSequence(dataDir, List.of(), ignored -> {});
    var bridge = new UpgradeShutdownBridge();
    bridge.install(sequence::runAndExitWithReceipt);
    LocalApiServer server = server(dataDir, bridge);
    try {
      HttpClient client = HttpClient.newHttpClient();
      var prepared = JSON.readTree(post(client, server, "/api/upgrade/prepare", "{}").body());
      try (var watcher = HeadlessApp.startShutdownRequestWatcher(runtimeDir,
          HeadlessApp.shutdownRequestAcceptance(), HeadlessApp.shutdownRequestDispatcher(sequence),
          20L, ignored -> {})) {
        for (String nonce : new String[] {null, "wrong", prepared.get("shutdownNonce").asText()}) {
          writeRequest(new ShutdownRequest(Reason.UPGRADE, Long.MAX_VALUE, nonce, "direct-file-test",
              prepared.get("preparationId").asText()), runtimeDir);
          assertTrue(waitForAbsent(ShutdownRequest.pathIn(runtimeDir)));
          assertFalse(watcher.hasFired());
          assertNull(sequence.resultIfRun());
        }
      }
      assertFalse(Files.exists(dataDir.resolve("upgrade").resolve(EngineShutdownSequence.RECEIPT_FILE)));
    } finally {
      server.stop();
    }
  }

  @Test
  void hostShutdownsUsePlainDispatchAndNeverForgeAReceipt(@TempDir Path tempDir) {
    for (Reason reason : Reason.values()) {
      Path dataDir = tempDir.resolve(reason.wire());
      var sequence = new EngineShutdownSequence(dataDir, List.of(), ignored -> {});
      var request = new ShutdownRequest(reason, Long.MAX_VALUE, null, "supervisor", null);
      assertEquals(io.justsearch.app.engine.ShutdownRequestWatcher.Acceptance.ACCEPT,
          HeadlessApp.shutdownRequestAcceptance().apply(request));
      HeadlessApp.shutdownRequestDispatcher(sequence).accept(request);
      assertTrue(sequence.resultIfRun() != null);
      assertFalse(Files.exists(dataDir.resolve("upgrade").resolve(EngineShutdownSequence.RECEIPT_FILE)));
    }
  }

  private static LocalApiServer server(Path dataDir, UpgradeShutdownBridge bridge) {
    return LocalApiServer.builder(new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY),
            dataDir.resolve("index"))
        .upgradeShutdownAction(bridge).build();
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
