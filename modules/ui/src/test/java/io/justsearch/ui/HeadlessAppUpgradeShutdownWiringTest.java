/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.engine.EngineShutdownSequence;
import io.justsearch.app.engine.ShutdownRequest;
import io.justsearch.app.engine.ShutdownRequest.Reason;
import io.justsearch.app.services.lease.OperationLeaseServiceImpl;
import io.justsearch.ui.api.LocalApiServer;
import io.justsearch.app.services.settings.UiSettingsStore;
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
    LocalApiServer server =
        LocalApiServer.builder(
                new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY),
                dataDir.resolve("index"))
            .upgradeShutdownAction(HeadlessApp.upgradeShutdownRequestWriter(runtimeDir))
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
  @DisplayName("the production writer and dispatcher preserve upgrade receipt identifiers")
  void productionFactoriesCarryUpgradeRequestToReceipt(@TempDir Path dataDir) throws Exception {
    Path runtimeDir = Files.createDirectories(dataDir.resolve("runtime"));
    var leases = new OperationLeaseServiceImpl();
    String preparationId = leases.freezeAdmission("upgrade").preparationId();
    var sequence =
        new EngineShutdownSequence(
            dataDir,
            List.of(
                new EngineShutdownSequence.Step(
                    EngineShutdownSequence.INDEX_HALF_STEP, ignored -> "GRACEFUL")),
            ignored -> {});

    try (var watcher =
        HeadlessApp.startShutdownRequestWatcher(
            runtimeDir,
            HeadlessApp.shutdownRequestAcceptance(leases),
            HeadlessApp.shutdownRequestDispatcher(sequence),
            20L,
            ignored -> {})) {
      HeadlessApp.upgradeShutdownRequestWriter(runtimeDir).shutdown(preparationId, "nonce-1");
      Path receipt = dataDir.resolve("upgrade").resolve(EngineShutdownSequence.RECEIPT_FILE);
      assertTrue(waitForFile(receipt), "the production watcher must dispatch the written request");
      var body = JSON.readTree(Files.readString(receipt));
      assertEquals(preparationId, body.get("preparationId").asText());
      assertEquals("nonce-1", body.get("shutdownNonce").asText());
    }
  }

  @Test
  @DisplayName("a prepared upgrade must match the current frozen lease snapshot")
  void stalePreparationIsRefusedBeforeDispatch(@TempDir Path dataDir) throws Exception {
    Path runtimeDir = Files.createDirectories(dataDir.resolve("runtime"));
    var leases = new OperationLeaseServiceImpl();
    leases.freezeAdmission("upgrade");
    var sequence = new EngineShutdownSequence(dataDir, List.of(), ignored -> {});

    try (var watcher =
        HeadlessApp.startShutdownRequestWatcher(
            runtimeDir,
            HeadlessApp.shutdownRequestAcceptance(leases),
            HeadlessApp.shutdownRequestDispatcher(sequence),
            20L,
            ignored -> {})) {
      HeadlessApp.upgradeShutdownRequestWriter(runtimeDir).shutdown("stale-preparation", "nonce-1");
      TimeUnit.MILLISECONDS.sleep(200);
      assertFalse(Files.exists(ShutdownRequest.pathIn(runtimeDir)));
      assertEquals(null, sequence.resultIfRun());
    }
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

}
