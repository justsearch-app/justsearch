/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.javalin.Javalin;
import io.justsearch.agent.api.encryption.StoreCatalog;
import io.justsearch.agent.api.encryption.StoreDescriptor;
import io.justsearch.app.services.HeadAssembly;
import io.justsearch.app.services.encryption.DataKeyManager;
import io.justsearch.app.services.encryption.EncryptionKeystore;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("cross-platform")
class KeystoreBackupDurabilityHttpTest {
  @TempDir Path dir;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void wrappedKeyExportAndImportWorkAcrossIndependentManagers() throws Exception {
    var keys = new DataKeyManager(new EncryptionKeystore(dir));
    String recovery = keys.setup("old".toCharArray());
    roundTrip(keys, "old", recovery);
  }

  @Test
  @Timeout(90)
  void failedRotationAndRetryKeepHttpExportsDecryptable() throws Exception {
    // Windows passes take 26-27 s against the 30 s default, with a timeout in the second
    // round trip's import. Allow roughly three times the slowest observed pass for headroom.
    System.out.println("Rotation/retry: setting up source keystore");
    var keys = new DataKeyManager(new EncryptionKeystore(dir));
    String recovery = keys.setup("old".toCharArray());
    Path replica = dir.resolve("encryption/keystore.replica.json");
    Files.deleteIfExists(replica);
    Files.createDirectories(replica);
    Files.writeString(replica.resolve("blocker"), "keep");
    System.out.println("Rotation/retry: forcing failed rotation");
    assertThrows(UncheckedIOException.class,
        () -> keys.changePassphrase("old".toCharArray(), "new".toCharArray()));
    System.out.println("Rotation/retry: round trip after failed rotation");
    roundTrip(keys, "old", recovery);
    Files.delete(replica.resolve("blocker"));
    Files.delete(replica);
    System.out.println("Rotation/retry: retrying rotation");
    keys.changePassphrase("old".toCharArray(), "new".toCharArray());
    System.out.println("Rotation/retry: round trip after successful retry");
    roundTrip(keys, "new", recovery);
  }

  private void roundTrip(DataKeyManager keys, String passphrase, String recovery) throws Exception {
    long started = System.nanoTime();
    System.out.println("Backup round trip: preparing target keystore");
    List<Map<String, Object>> entries = List.of(Map.of("sessionId", "saved", "message", "keep me"));
    var imported = new ArrayList<Map<String, Object>>();
    var sourceHead = mock(HeadAssembly.class);
    when(sourceHead.authoredStores()).thenReturn(List.of(new StoreDescriptor(StoreCatalog.CONVERSATIONS,
        dir.resolve("source"), () -> entries, ignored -> 0)));
    var targetKeys = new DataKeyManager(new EncryptionKeystore(Files.createTempDirectory(dir, "import-")));
    targetKeys.setup("target".toCharArray());
    System.out.println("Backup round trip: target keystore ready after "
        + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) + " ms");
    var targetHead = mock(HeadAssembly.class);
    when(targetHead.authoredStores()).thenReturn(List.of(new StoreDescriptor(StoreCatalog.CONVERSATIONS,
        dir.resolve("target"), List::of, restored -> { imported.addAll(restored); return restored.size(); })));
    var export = new ConversationBackupController(sourceHead, keys);
    var restore = new ConversationBackupController(targetHead, targetKeys);
    var app = Javalin.create(c -> {
      c.showJavalinBanner = false;
      c.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
    });
    try (var client = HttpClient.newHttpClient()) {
      app.post("/export", export::handleExport);
      app.post("/import", restore::handleImport);
      app.start(0);
      JsonNode container = post(client, app, "/export", Map.of());
      for (var credential : List.of(Map.of("passphrase", passphrase), Map.of("recoveryKey", recovery))) {
        imported.clear();
        var body = new java.util.LinkedHashMap<String, Object>(credential);
        body.put("container", container);
        JsonNode result = post(client, app, "/import", body);
        assertEquals(1, result.path("restored").path("conversations").asInt());
        assertEquals(entries, imported);
      }
    } finally {
      System.out.println("Backup round trip: stopping HTTP server");
      app.stop();
      System.out.println("Backup round trip: completed cleanup; elapsed="
          + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) + " ms");
    }
  }

  private static JsonNode post(HttpClient client, Javalin app, String path, Map<String, ?> body) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
    long started = System.nanoTime();
    String operation = "POST " + path + " " + body.keySet();
    System.out.println(operation + ": sending");
    try {
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode(), response.body());
      return JSON.readTree(response.body());
    } finally {
      System.out.println(operation + ": elapsed="
          + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) + " ms");
    }
  }
}
