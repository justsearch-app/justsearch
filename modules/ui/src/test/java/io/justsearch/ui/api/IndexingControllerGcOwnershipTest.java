/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.javalin.Javalin;
import io.justsearch.app.api.IndexingService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

final class IndexingControllerGcOwnershipTest {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void gcResponseSeparatesEmptyRemnantsAndListsUnprovenCleanupPaths(boolean accepted) throws Exception {
    var service = mock(IndexingService.class);
    when(service.runIndexGc(anyInt(), anyBoolean(), any())).thenReturn(
        new IndexingService.IndexGcOutcome(accepted, 2, 1, accepted ? "" : "Index GC incomplete", 3,
            List.of(new IndexingService.IndexCleanupWarning("/index/indices/foreign.del-20260101-000000", "missing sentinel"))));
    var controller = new IndexingController(() -> service,
        new io.justsearch.app.services.excludes.ExcludesServiceImpl(() -> service), null, null);
    var app = Javalin.create(config -> {
      config.showJavalinBanner = false;
      config.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
    }).post("/api/indexing/gc", controller::handleIndexGc).start(0);
    try (var client = HttpClient.newHttpClient()) {
      var response = client.send(HttpRequest.newBuilder(
          URI.create("http://localhost:" + app.port() + "/api/indexing/gc"))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString("{}"))
          .build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(accepted ? 202 : 409, response.statusCode(), response.body());
      var body = new ObjectMapper().readTree(response.body());
      if (accepted) {
        assertEquals(2, body.path("markedCount").asInt());
        assertEquals(1, body.path("prunedCount").asInt());
      } else {
        assertEquals("Index GC incomplete", body.path("error").asString());
      }
      assertEquals(3, body.path("emptyLeftoversRemoved").asInt());
      assertEquals("/index/indices/foreign.del-20260101-000000",
          body.path("cleanupWarnings").get(0).path("path").asString());
      assertEquals("missing sentinel", body.path("cleanupWarnings").get(0).path("reason").asString());
    } finally { app.stop(); }
  }
}
