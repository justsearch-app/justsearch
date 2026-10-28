/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import io.javalin.Javalin;
import io.justsearch.app.api.DocumentService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import tools.jackson.databind.ObjectMapper;

final class PreviewReadFailureHttpTest {
  private Javalin app;
  @AfterEach void stop() { if (app != null) app.stop(); }

  @Test
  void unreadableOrNoTextIsNotA404WithoutIndependentAbsence() throws Exception {
    DocumentService service = (id, context) -> CompletableFuture.completedFuture(null);
    PreviewController controller = new PreviewController(service, Duration.ofSeconds(1));
    app = Javalin.create(config -> {
      config.showJavalinBanner = false;
      config.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
    }).get("/api/preview", controller::handlePreview).start(0);
    {
      var result = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
          URI.create("http://127.0.0.1:" + app.port() + "/api/preview?docId=no-extracted-text"))
          .GET().build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(503, result.statusCode());
      assertEquals("unknown", new ObjectMapper().readTree(result.body()).path("sourceState").asText());
    }
  }
}
