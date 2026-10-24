/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;

import io.javalin.Javalin;
import io.justsearch.agent.FileMemoryStore;
import io.justsearch.agent.api.memory.MemoryRecord;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class MemoryConditionalHttpTest {
  @TempDir Path dir;

  @Test
  void staleMalformedAbsentAndReplayedConditionsUseTheRealHttpPath() throws Exception {
    var store = new FileMemoryStore(dir);
    var record = new MemoryRecord("same", "fact", "original", null, "primary", Instant.EPOCH);
    store.remember(record);
    var app = Javalin.create(c -> c.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper()));
    var controller = new MemoryController(store);
    app.get("/api/memory", controller::handleList);
    app.delete("/api/memory/{id}", controller::handleForget);
    try (var client = HttpClient.newHttpClient()) {
      app.start(0);
      URI base = URI.create("http://127.0.0.1:" + app.port() + "/api/memory");
      // An unknown, well-formed revision is enough to reveal the baseline's unconditional delete.
      String stale = "\"" + "0".repeat(64) + "\"";
      var refused = delete(client, base, stale);
      assertEquals(412, refused.statusCode(), refused.body());
      assertEquals("INVALID_STATE", JsonMapper.builder().build().readTree(refused.body()).get("errorCode").asString());
      assertEquals(List.of(record), store.whatItKnows());
      for (String invalid : List.of("", "*", "unquoted", "W/" + stale, stale + ", " + stale)) {
        assertEquals(400, delete(client, base, invalid).statusCode(), invalid);
        assertEquals(List.of(record), store.whatItKnows());
      }
      String current = revision(client, base);
      var removed = delete(client, base, current);
      assertEquals(200, removed.statusCode());
      assertTrue(JsonMapper.builder().build().readTree(removed.body()).get("removed").asBoolean());
      var absent = delete(client, base, current);
      assertEquals(200, absent.statusCode());
      assertFalse(JsonMapper.builder().build().readTree(absent.body()).get("removed").asBoolean());
      store.remember(record); // Same-id identical backup restoration is a new incarnation.
      assertEquals(412, delete(client, base, current).statusCode());
      assertEquals(List.of(record), new FileMemoryStore(dir).whatItKnows());
      current = revision(client, base);
      store.remember(new MemoryRecord("same", "fact", "replacement", null, "primary", Instant.EPOCH));
      assertEquals(412, delete(client, base, current).statusCode());
      assertEquals("replacement", store.whatItKnows().getFirst().content());
    } finally {
      app.stop();
    }
  }

  private static String revision(HttpClient client, URI base) throws Exception {
    var response = client.send(HttpRequest.newBuilder(base).GET().build(), HttpResponse.BodyHandlers.ofString());
    var node = JsonMapper.builder().build().readTree(response.body()).get("memories").get(0).get("revision");
    assertNotNull(node, "GET must supply conditional-delete validators");
    return node.asString();
  }

  private static HttpResponse<String> delete(HttpClient client, URI base, String revision) throws Exception {
    return client.send(HttpRequest.newBuilder(URI.create(base + "/same"))
        .header("If-Match", revision).DELETE().build(), HttpResponse.BodyHandlers.ofString());
  }
}
