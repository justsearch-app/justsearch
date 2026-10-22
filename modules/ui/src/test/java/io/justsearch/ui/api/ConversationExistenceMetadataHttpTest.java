/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.ui.runtime.RuntimeManifestPublisher;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** EB1 metadata through private HTTP servers, with no shared Engine or data. */
class ConversationExistenceMetadataHttpTest {
  @TempDir Path temp;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void listCarriesExistingEngineIdentityAndDeletionCountBeyondBoundedMarkers() throws Exception {
    try (var executors = new TestEngineExecutors();
        var publisher = new RuntimeManifestPublisher(temp);
        var client = HttpClient.newHttpClient()) {
      var server = LocalApiServer.builder(executors,
          new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY), temp.resolve("index"))
          .runtimeManifestPublisher(publisher).build();
      try {
        String base = "http://127.0.0.1:" + server.getPort();
        var initial = JSON.readTree(send(client, base, false).body());
        assertEquals(publisher.instanceId(), initial.path("instanceId").asText());
        assertTrue(initial.path("deletionCount").isIntegralNumber(), initial.toString());
        assertEquals(0, initial.path("deletionCount").asLong());
        for (int i = 0; i < 101; i++) {
          var response = client.send(HttpRequest.newBuilder(URI.create(
              base + "/api/chat/conversations/gone-" + i)).DELETE().build(),
              HttpResponse.BodyHandlers.ofString());
          assertEquals(200, response.statusCode(), response.body());
        }
        var after = JSON.readTree(send(client, base, false).body());
        assertEquals(publisher.instanceId(), after.path("instanceId").asText());
        assertEquals(101, after.path("deletionCount").asLong());
        assertEquals(100, after.path("deletedIds").size());
        assertEquals("gone-1", after.path("deletedIds").get(0).asText());
        assertEquals("gone-100", after.path("deletedIds").get(99).asText());
      } finally { server.stop(); }
    }
  }

  @Test
  void newEngineIdentityStartsANewDeletionCount() throws Exception {
    String previousId = null;
    try (var client = HttpClient.newHttpClient()) {
      for (int run = 0; run < 2; run++) {
        try (var executors = new TestEngineExecutors();
            var publisher = new RuntimeManifestPublisher(temp)) {
          var server = LocalApiServer.builder(executors,
              new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY), temp.resolve("index"))
              .runtimeManifestPublisher(publisher).build();
          try {
            String base = "http://127.0.0.1:" + server.getPort();
            var initial = JSON.readTree(send(client, base, false).body());
            assertEquals(publisher.instanceId(), initial.path("instanceId").asText());
            assertNotEquals(previousId, initial.path("instanceId").asText());
            assertTrue(initial.path("deletionCount").isIntegralNumber(), initial.toString());
            assertEquals(0, initial.path("deletionCount").asLong());
            assertEquals(200, send(client, base, true).statusCode());
            var after = JSON.readTree(send(client, base, false).body());
            assertEquals(1, after.path("deletionCount").asLong());
            previousId = publisher.instanceId();
          } finally { server.stop(); }
        }
      }
    }
  }

  private static HttpResponse<String> send(HttpClient client, String base, boolean delete)
      throws Exception {
    var request = HttpRequest.newBuilder(URI.create(base + "/api/chat/conversations"
        + (delete ? "/gone" : "?limit=20")));
    return client.send((delete ? request.DELETE() : request.GET()).build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
