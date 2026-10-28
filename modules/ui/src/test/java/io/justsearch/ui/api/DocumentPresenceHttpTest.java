/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import io.javalin.Javalin;
import io.justsearch.app.api.DocumentService;
import io.justsearch.core.context.EngineContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import tools.jackson.databind.ObjectMapper;

final class DocumentPresenceHttpTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  private Javalin app;
  @AfterEach void stop() { if (app != null) app.stop(); }

  @Test
  void presenceRouteIsBoundedDoesNotRecordActivityAndOnlyReturnsRequestedIds() throws Exception {
    AtomicInteger activity = new AtomicInteger();
    DocumentService service = new DocumentService() {
      @Override public CompletionStage<DocumentRecord> fetch(String id, EngineContext context) {
        return CompletableFuture.completedFuture(null);
      }
      @Override public CompletionStage<Map<String, Presence>> checkPresence(List<String> ids, EngineContext context) {
        return CompletableFuture.completedFuture(Map.of("held", Presence.PRESENT, "gone", Presence.ABSENT,
            "bad", Presence.UNKNOWN, "not-requested", Presence.ABSENT));
      }
    };
    PreviewController controller = new PreviewController(service, Duration.ofSeconds(1), activity::incrementAndGet);
    app = server().post(PreviewController.PRESENCE_PATH, controller::handlePresence)
        .get("/api/preview", controller::handlePreview).start(0);
    {
      var response = post(app, "{\"docIds\":[\"held\",\"gone\",\"bad\",\"unreported\"]}");
      assertEquals(200, response.statusCode());
      var states = JSON.readTree(response.body()).path("states");
      assertEquals("present", states.path("held").asText());
      assertEquals("absent", states.path("gone").asText());
      assertEquals("unknown", states.path("bad").asText());
      assertEquals("unknown", states.path("unreported").asText());
      assertEquals(4, states.size());
      assertEquals(0, activity.get());
      assertEquals(400, post(app, JSON.writeValueAsString(Map.of("docIds", java.util.Collections.nCopies(65, "id")))).statusCode());
      assertEquals(400, post(app, "{\"docIds\":[null]}").statusCode());
      assertEquals(400, post(app, "{\"docIds\":[\" \"],\"extra\":true}").statusCode());
      var preview = get(app, "/api/preview?docId=gone");
      assertEquals(404, preview.statusCode());
      assertEquals("absent", JSON.readTree(preview.body()).path("sourceState").asText());
      assertEquals(1, activity.get());
    }
  }

  private static Javalin server() {
    return Javalin.create(config -> {
      config.showJavalinBanner = false;
      config.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
    });
  }
  private static HttpResponse<String> get(Javalin app, String path) throws Exception {
    return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
        URI.create("http://127.0.0.1:" + app.port() + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
  }
  private static HttpResponse<String> post(Javalin app, String body) throws Exception {
    return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
        URI.create("http://127.0.0.1:" + app.port() + PreviewController.PRESENCE_PATH))
        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
