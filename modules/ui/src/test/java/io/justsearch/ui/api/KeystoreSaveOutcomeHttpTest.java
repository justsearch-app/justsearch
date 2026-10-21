/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.javalin.Javalin;
import io.justsearch.agent.api.encryption.StoreCipher;
import io.justsearch.app.services.encryption.DataKeyManager;
import io.justsearch.app.services.encryption.EncryptionKeystore;
import io.justsearch.app.services.encryption.WrongPassphraseException;
import io.justsearch.configuration.persistence.AtomicFileWrites;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("cross-platform")
class KeystoreSaveOutcomeHttpTest {
  @TempDir Path dir;
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private Path primary() { return dir.resolve("encryption/keystore.json"); }
  private Path replica() { return dir.resolve("encryption/keystore.replica.json"); }
  private DataKeyManager manager() { return new DataKeyManager(new EncryptionKeystore(dir)); }

  @ParameterizedTest(name = "rollbackFailureReportsSavedWithoutReplica({0})")
  @ValueSource(strings = {"setup", "change-passphrase", "regenerate-recovery"})
  void rollbackFailureReportsSavedWithoutReplica(String action) throws Exception {
    var keys = manager();
    String oldRecovery = action.equals("setup") ? null : keys.setup("old-password".toCharArray());
    String sealed = oldRecovery == null ? null : new StoreCipher(keys).seal("saved conversation");
    var app = server(keys, true, true);
    try (var client = HttpClient.newHttpClient()) {
      var response = post(client, app, action);
      assertEquals(200, response.statusCode(), response.body());
      JsonNode result = JSON.readTree(response.body());
      assertEquals("saved_without_replica", result.path("saveOutcome").asString());
      assertEquals("unlocked", result.path("state").asString());
      assertTrue(result.path("warning").asString().contains("new credentials are in force"));
      assertFalse(Files.exists(replica()), "a stale replica must never restore revoked credentials");
      var restarted = manager();
      if (action.equals("change-passphrase")) {
        assertThrows(WrongPassphraseException.class, () -> restarted.unlock("old-password".toCharArray()));
        restarted.unlock("new-password".toCharArray());
      } else {
        String recovery = result.path("recoveryKey").asString();
        assertFalse(recovery.isBlank(), "a committed recovery key must reach the caller even if rollback failed");
        if (oldRecovery != null) assertThrows(WrongPassphraseException.class, () -> restarted.recover(oldRecovery));
        restarted.recover(recovery);
      }
      assertArrayEquals(keys.dek(), restarted.dek());
      if (sealed != null) assertEquals("saved conversation", new StoreCipher(restarted).open(sealed));
      assertArrayEquals(Files.readAllBytes(primary()), Files.readAllBytes(replica()),
          "startup must repair the replica using the committed primary");
      Files.delete(primary());
      var restored = manager();
      if (action.equals("change-passphrase")) restored.unlock("new-password".toCharArray());
      else restored.recover(result.path("recoveryKey").asString());
      assertArrayEquals(keys.dek(), restored.dek());
    } finally {
      app.stop();
    }
  }

  @ParameterizedTest(name = "replicaFailureReportsRolledBackThroughHttp({0})")
  @ValueSource(strings = {"setup", "change-passphrase", "regenerate-recovery"})
  void replicaFailureReportsRolledBackThroughHttp(String action) throws Exception {
    var keys = manager();
    String recovery = action.equals("setup") ? null : keys.setup("old-password".toCharArray());
    byte[] previous = recovery == null ? null : Files.readAllBytes(primary());
    var app = server(keys, true, false);
    try (var client = HttpClient.newHttpClient()) {
      var response = post(client, app, action);
      assertTrue(response.statusCode() >= 400, response.body());
      JsonNode result = assertDoesNotThrow(() -> JSON.readTree(response.body()),
          "failed saves must return their rollback outcome");
      assertEquals("failed_rolled_back", result.path("saveOutcome").asString());
      assertFalse(result.has("recoveryKey"));
      if (previous == null) {
        assertFalse(Files.exists(primary()));
        assertEquals(DataKeyManager.State.NOT_CONFIGURED, keys.state());
      } else {
        assertArrayEquals(previous, Files.readAllBytes(primary()));
        var restarted = manager();
        assertDoesNotThrow(() -> restarted.recover(recovery));
        restarted.lock();
        assertDoesNotThrow(() -> restarted.unlock("old-password".toCharArray()));
      }
    } finally {
      app.stop();
    }
  }

  @Test
  void recoveryFromReplicaIsExposedThroughEncryptionStatus() throws Exception {
    var keys = manager();
    keys.setup("old-password".toCharArray());
    Files.delete(primary());
    var restarted = manager();
    var app = server(restarted, false, false);
    try (var client = HttpClient.newHttpClient()) {
      var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/status"))
          .GET().build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertTrue(JSON.readTree(response.body()).path("warning").asString().contains("restored"));
    } finally {
      app.stop();
    }
  }

  @ParameterizedTest(name = "successfulSaveReportsSavedThroughHttp({0})")
  @ValueSource(strings = {"setup", "change-passphrase", "regenerate-recovery"})
  void successfulSaveReportsSavedThroughHttp(String action) throws Exception {
    var keys = manager();
    if (!action.equals("setup")) keys.setup("old-password".toCharArray());
    var app = server(keys, false, false);
    try (var client = HttpClient.newHttpClient()) {
      var response = post(client, app, action);
      assertEquals(200, response.statusCode(), response.body());
      JsonNode result = JSON.readTree(response.body());
      assertEquals("saved", result.path("saveOutcome").asString());
      assertFalse(result.has("warning"));
      assertArrayEquals(Files.readAllBytes(primary()), Files.readAllBytes(replica()));
    } finally {
      app.stop();
    }
  }

  private Javalin server(DataKeyManager keys, boolean failReplica, boolean failRollback) {
    var controller = new ConversationEncryptionController(keys);
    var app = Javalin.create(c -> {
      c.showJavalinBanner = false;
      c.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
    });
    app.get("/status", controller::handleStatus);
    app.post("/{action}", ctx -> {
      var primaryWrites = new AtomicInteger();
      // Faults are scoped to the real HTTP handler thread; all successful writes use real NIO.
      try (var _ = mockStatic(AtomicFileWrites.class, invocation -> {
        if (invocation.getMethod().getName().equals("replaceStrict")
            && invocation.getMethod().getParameterCount() == 2) {
          Path target = invocation.getArgument(0);
          if (target.equals(replica()) && failReplica) throw new IOException("replica refused");
          if (target.equals(primary()) && primaryWrites.incrementAndGet() > 1 && failRollback) {
            throw new IOException("rollback refused");
          }
        }
        return invocation.callRealMethod();
      }); var _ = mockStatic(Files.class, invocation -> {
        if (failRollback && primaryWrites.get() > 0 && invocation.getMethod().getName().equals("deleteIfExists")
            && primary().equals(invocation.getArgument(0))) throw new IOException("setup rollback refused");
        return invocation.callRealMethod();
      })) {
        switch (ctx.pathParam("action")) {
          case "setup" -> controller.handleSetup(ctx);
          case "change-passphrase" -> controller.handleChangePassphrase(ctx);
          case "regenerate-recovery" -> controller.handleRegenerateRecovery(ctx);
          default -> throw new IllegalArgumentException("unknown action");
        }
      }
    });
    return app.start(0);
  }

  private static HttpResponse<String> post(HttpClient client, Javalin app, String action) throws Exception {
    return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/" + action))
        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(
            Map.of("passphrase", "new-password", "oldPassphrase", "old-password", "newPassphrase", "new-password"))))
        .build(), HttpResponse.BodyHandlers.ofString());
  }
}
