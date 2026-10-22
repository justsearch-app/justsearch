/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.javalin.Javalin;
import io.justsearch.agent.AgentRunStore;
import io.justsearch.agent.api.encryption.StoreCipher;
import io.justsearch.agent.api.encryption.StoreDescriptor;
import io.justsearch.app.services.HeadAssembly;
import io.justsearch.app.services.encryption.DataKeyManager;
import io.justsearch.app.services.encryption.EncryptionKeystore;
import io.justsearch.app.services.observability.HeadApiMetricCatalog;
import io.justsearch.core.execution.TestEngineExecutors;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class ConversationRestoreAfterDeleteTest {
  @TempDir Path temp;

  @Test
  void explicitBackupImportCanRestoreAndExtendADeletedConversation() throws Exception {
    var head = mock(HeadAssembly.class, RETURNS_DEEP_STUBS);
    when(head.storeCipher(any())).thenReturn(StoreCipher.disabled());
    when(head.agentRunStore()).thenReturn(new AgentRunStore(temp.resolve("runs")));
    var descriptors = new ArrayList<StoreDescriptor>();
    doAnswer(call -> { descriptors.add(call.getArgument(0)); return null; })
        .when(head).registerAuthoredStore(any());
    when(head.authoredStores()).thenAnswer(call -> List.copyOf(descriptors));
    var keys = new DataKeyManager(new EncryptionKeystore(temp.resolve("keys")));
    keys.setup("local-passphrase".toCharArray());
    var json = JsonMapper.builder().build();
    try (var executors = new TestEngineExecutors(); var client = HttpClient.newHttpClient()) {
      var builder = LocalApiServer.builder(executors, null, temp.resolve("index"))
          .HeadAssembly(head).documentService(mock(io.justsearch.app.api.DocumentService.class))
          .onlineAiService(io.justsearch.app.api.OnlineAiService.unavailable());
      var assembly = ConversationApiAssembly.assemble(builder, null, HeadApiMetricCatalog.noop(), false, () -> null);
      var store = assembly.conversationStore();
      store.createSession("conversation", "core.free-chat");
      store.appendMessage("conversation", "core.free-chat", Map.of("role", "user", "content", "backup content"));
      var entries = descriptors.getFirst().source().read();
      store.deleteSession("conversation");
    store.conversationWork().completed("conversation");
      var container = Map.of("format", "justsearch-backup/v1", "keystore", keys.keystoreRecord(),
          "data", new StoreCipher(keys).seal(json.writeValueAsString(Map.of("conversations", entries))));
      var backup = new ConversationBackupController(head, keys);
      var app = Javalin.create(c -> c.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper()));
      try {
        app.post("/import", backup::handleImport).start(0);
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/import"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(
                "container", container, "passphrase", "local-passphrase")))).build();
        var restored = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, restored.statusCode(), restored.body());
        assertEquals(1, json.readTree(restored.body()).path("restored").path("conversations").asInt());
        assertEquals("backup content", store.loadHistory("conversation").getFirst().get("content"));
        store.appendMessage("conversation", "core.free-chat", Map.of("role", "user", "content", "next turn"));
        assertEquals(2, store.loadHistory("conversation").size());
        var repeated = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, repeated.statusCode(), repeated.body());
        assertEquals(0, json.readTree(repeated.body()).path("restored").path("conversations").asInt());
      } finally {
        app.stop();
        assembly.chatController().shutdown();
        assembly.agentController().shutdown();
        assembly.runStreamController().shutdown();
      }
    }
  }
}
